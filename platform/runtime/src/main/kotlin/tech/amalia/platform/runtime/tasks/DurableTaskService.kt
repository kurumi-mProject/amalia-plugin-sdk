package tech.amalia.platform.runtime.tasks

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** A trusted, version-pinned executor. Recovery never invokes it for an uncertain effect. */
interface DurableTaskExecutor {
    val binding: ProviderBinding
    val definitionVersion: String
    val packageHash: String
    val contractVersion: String
    suspend fun execute(session: TaskExecution): TaskStep
    /** Stop local work; this is not a claim that external actions were rolled back. */
    suspend fun cancel(task: TaskRecord)
}

sealed interface TaskStep {
    data class Complete(val succeeded: Boolean, val evidence: JsonObject) : TaskStep
    data class Question(val id: String, val step: String, val version: Long, val recipient: String,
        val schema: JsonObject, val expiresAtMillis: Long? = null) : TaskStep
    data class Waiting(val checkpoint: JsonObject) : TaskStep
}

data class EffectReceipt(val state: EffectState, val receipt: JsonObject, val evidence: JsonObject) {
    init { require(state == EffectState.CONFIRMED || state == EffectState.REJECTED) }
}

/**
 * Single host/process dispatcher. The host owns the store and must close it AFTER stop().
 * All methods may fsync; call on an IO dispatcher. No automatic retry, provider migration,
 * schedule interpreter, or permission inference. A dispatch is bounded by its persisted lease.
 */
class DurableTaskService(
    private val store: DurableWorkflowStore,
    executors: List<DurableTaskExecutor>,
    parentScope: CoroutineScope,
    private val leaseMillis: Long = 240_000,
    private val onFailure: (String, Throwable) -> Unit,
) : Closeable {
    private val root = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + root + Dispatchers.IO)
    private val owner = UUID.randomUUID().toString()
    private val providers = executors.associateBy { it.binding }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lifecycle = Any()
    init {
        require(leaseMillis in 1..300_000)
        require(providers.size == executors.size)
    }

    fun get(id: String): TaskRecord = store.get(id)
    fun list(): List<TaskRecord> = store.list()

    /** Stable caller-owned birth ID; an exact duplicate returns the existing durable task. */
    fun submit(task: TaskRecord): TaskRecord = synchronized(lifecycle) {
        check(root.isActive)
        val executor = requireNotNull(providers[task.provider]) { "Executor unavailable" }
        require(executor.definitionVersion == task.definitionVersion && executor.packageHash == task.executorPackageHash &&
            executor.contractVersion == task.contractVersion) { "Executor version mismatch" }
        val existing = store.list().find { it.id == task.id }
        val accepted = if (existing == null) store.create(task) else {
            require(existing.copy(state = task.state, revision = task.revision, checkpoint = task.checkpoint,
                fence = task.fence, lease = task.lease, questions = task.questions, effects = task.effects,
                completionEvidence = task.completionEvidence) == task) { "Birth key conflict" }
            existing
        }
        dispatch(accepted.id)
        accepted
    }

    /** Only never-dispatched tasks are automatically picked up on startup. */
    fun recover() {
        store.list().filter { it.state == TaskState.CREATED || it.state == TaskState.READY }
            .filter { it.effects.none { effect -> effect.state != EffectState.CONFIRMED && effect.state != EffectState.REJECTED } }
            .forEach { dispatch(it.id) }
        // RUNNING checkpoints may contain nondeterministic or external work. Require explicit inspection.
    }

    fun respond(id: String, revision: Long, question: String, version: Long, answer: QuestionAnswer): TaskRecord {
        val result = store.answer(id, revision, question, version, answer)
        if (result.state == TaskState.READY) dispatch(id)
        return result
    }

    /** Explicit scheduling/dependency signal; no unsafe replay of RUNNING/RECONCILING is allowed. */
    fun dispatch(id: String): Unit = synchronized(lifecycle) {
        check(root.isActive)
        if (jobs[id]?.isCompleted == false) return@synchronized
        val task = store.get(id)
        if (task.state !in setOf(TaskState.CREATED, TaskState.READY, TaskState.WAITING_DEPENDENCY)) return@synchronized
        if (task.effects.any { it.state != EffectState.CONFIRMED && it.state != EffectState.REJECTED }) return@synchronized
        if (task.checkpoint["requiresIntervention"] == kotlinx.serialization.json.JsonPrimitive(true)) return@synchronized
        val executor = providers[task.provider] ?: return@synchronized
        if (executor.definitionVersion != task.definitionVersion || executor.packageHash != task.executorPackageHash ||
            executor.contractVersion != task.contractVersion) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) { runTask(id, executor) }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            // An answer/resume can arrive while the old worker still owns its slot.
            if (root.isActive) {
                try {
                    if (store.get(id).state == TaskState.READY) dispatch(id)
                } catch (error: Exception) { onFailure(id, error) }
            }
        }
        job.start()
        Unit
    }

    suspend fun cancel(id: String, revision: Long): TaskRecord {
        // Intent is durable BEFORE cancellation/join, so a late result cannot complete the task.
        val cancelled = store.cancel(id, revision)
        val job = synchronized(lifecycle) { jobs[id]?.also { it.cancel() } }
        job?.join()
        providers[cancelled.provider]?.cancel(store.get(id))
        return store.get(id)
    }

    fun pause(id: String, revision: Long): TaskRecord {
        val paused = store.pause(id, revision)
        synchronized(lifecycle) { jobs[id]?.cancel() }
        return paused
    }

    fun resume(id: String, revision: Long): TaskRecord {
        val result = store.resume(id, revision)
        if (result.state == TaskState.READY) dispatch(id)
        return result
    }

    /** Host-authorized evidence only; never sends or resumes. Terminal cancellation remains terminal. */
    fun reconcile(id: String, revision: Long, effectId: String, receipt: EffectReceipt): TaskRecord {
        val task = store.get(id)
        require(task.revision == revision)
        val leased = store.acquireLease(id, revision, owner, task.provider.generation, leaseMillis)
        val token = requireNotNull(leased.lease).token
        val resolved = store.resolveEffect(id, leased.revision, token, effectId, receipt.state, receipt.receipt, receipt.evidence)
        return store.releaseLease(id, resolved.revision, token)
    }

    private suspend fun runTask(id: String, executor: DurableTaskExecutor) {
        var session: TaskExecution? = null
        try {
            val task = store.get(id)
            val leased = store.acquireLease(id, task.revision, owner, executor.binding.generation, leaseMillis)
            val execution = TaskExecution(store, id, requireNotNull(leased.lease).token, owner, leaseMillis)
            session = execution
            execution.checkpoint(leased.checkpoint, TaskState.RUNNING)
            val step = withTimeout(leaseMillis) { executor.execute(execution) }
            currentCoroutineContext().ensureActive()
            when (step) {
                is TaskStep.Complete -> execution.complete(step)
                is TaskStep.Question -> execution.ask(step)
                is TaskStep.Waiting -> execution.checkpoint(step.checkpoint, TaskState.WAITING_DEPENDENCY)
            }
        } catch (error: CancellationException) {
            // Timeout/cancellation is uncertainty, never evidence of failure or permission to replay.
            if (error is TimeoutCancellationException) onFailure(id, error)
            throw error
        } catch (error: Exception) {
            onFailure(id, error)
        } finally {
            // The worker has exited. Conservatively retain every dispatched intent as UNKNOWN.
            session?.let { execution ->
                try { execution.retire(owner, leaseMillis) }
                catch (error: Exception) { onFailure(id, error) }
            }
        }
    }

    /** Await before closing the store. close() only requests cancellation. */
    suspend fun stop() { root.cancelAndJoin() }
    override fun close() { root.cancel() }
}

/** Lease-scoped mutation API. Re-reads permit concurrent answer/cancel without reusing stale revisions. */
class TaskExecution internal constructor(
    private val store: DurableWorkflowStore, val id: String, val token: Long,
    private val owner: String, private val duration: Long,
) {
    fun task(): TaskRecord = store.get(id)
    fun checkpoint(value: JsonObject, state: TaskState = TaskState.RUNNING): TaskRecord {
        val task = task()
        return store.checkpoint(id, task.revision, token, value, state)
    }

    /** A single attempt: persist PREPARED + checkpoint, persist DISPATCHED, then call the recipient. */
    suspend fun effect(intent: EffectRecord, checkpoint: JsonObject, send: suspend (Long) -> EffectReceipt): EffectReceipt {
        currentCoroutineContext().ensureActive()
        val task = task()
        val prepared = store.prepareEffect(id, task.revision, token, intent, checkpoint)
        store.dispatchEffect(id, prepared.revision, token, intent.id)
        currentCoroutineContext().ensureActive()
        val receipt = send(token)
        var current = task()
        var fence = token
        if (current.lease == null) {
            // Cancellation/pause revoked the original fence. Persist the late fact using a fresh lease;
            // resolveEffect preserves CANCELLED/PAUSED instead of turning it into success.
            current = store.acquireLease(id, current.revision, owner, current.provider.generation, duration)
            fence = requireNotNull(current.lease).token
        }
        try {
            store.resolveEffect(id, current.revision, fence, intent.id, receipt.state, receipt.receipt, receipt.evidence)
        } catch (error: WorkflowStoreException) {
            if (error.code != WorkflowStoreException.Code.STALE_LEASE) throw error
            current = task()
            val renewed = store.acquireLease(id, current.revision, owner, current.provider.generation, duration)
            store.resolveEffect(id, renewed.revision, requireNotNull(renewed.lease).token, intent.id,
                receipt.state, receipt.receipt, receipt.evidence)
        }
        return receipt
    }

    internal fun complete(step: TaskStep.Complete) {
        val task = task()
        store.complete(id, task.revision, token, if (step.succeeded) TaskState.SUCCEEDED else TaskState.FAILED, step.evidence)
    }
    internal fun ask(step: TaskStep.Question) {
        val task = task()
        store.ask(id, task.revision, token, QuestionRecord(step.id, step.step, step.version, task.revision,
            step.recipient, step.schema, step.expiresAtMillis))
    }
    internal fun retire(owner: String, duration: Long) {
        var current = task()
        // Pause/cancel/expiry can invalidate the original lease. Reconcile only after execution exited.
        if (current.effects.any { it.state == EffectState.DISPATCHED }) {
            if (current.lease == null) {
                current = store.acquireLease(id, current.revision, owner, current.provider.generation, duration)
            }
            require(current.lease?.owner == owner) { "Reconciliation lease belongs to another worker" }
            current.effects.filter { it.state == EffectState.DISPATCHED }.forEach { effect ->
                try {
                    current = store.resolveEffect(id, current.revision, requireNotNull(current.lease).token,
                        effect.id, EffectState.UNKNOWN, null, null)
                } catch (error: WorkflowStoreException) {
                    if (error.code != WorkflowStoreException.Code.STALE_LEASE) throw error
                    current = store.acquireLease(id, current.revision, owner, current.provider.generation, duration)
                    current = store.resolveEffect(id, current.revision, requireNotNull(current.lease).token,
                        effect.id, EffectState.UNKNOWN, null, null)
                }
            }
        }
        current.lease?.let { lease ->
            // Do not release a different worker's lease. An expired lease is already unusable.
            if (lease.owner == owner) {
                try { store.releaseLease(id, current.revision, lease.token) }
                catch (error: WorkflowStoreException) {
                    if (error.code != WorkflowStoreException.Code.STALE_LEASE) throw error
                }
            }
        }
    }
}
