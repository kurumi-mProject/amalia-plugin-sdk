package tech.amalia.platform.runtime.tasks

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tech.amalia.platform.runtime.tasks.WorkflowStoreException.Code

/** Single-process store. Open only in a private, already-created directory on a local filesystem. */
class DurableWorkflowStore private constructor(
    private val directory: File,
    private val validator: QuestionAnswerValidator,
    private val limits: WorkflowStoreLimits,
    private val clock: () -> Long,
) : Closeable {
    private val json = Json { encodeDefaults = true }
    private val journal = WorkflowJournal(directory, limits)
    private val tasks = linkedMapOf<String, TaskRecord>()
    private var closed = false
    private var inCallback = false
    private var failed = false

    companion object {
        private val openDirectories = mutableSetOf<String>()

        fun open(
            directory: File,
            validator: QuestionAnswerValidator,
            limits: WorkflowStoreLimits = WorkflowStoreLimits(),
            clock: () -> Long = System::currentTimeMillis,
        ): DurableWorkflowStore {
            if (limits.maxTasks <= 0 || limits.maxQuestionsPerTask <= 0 || limits.maxEffectsPerTask <= 0 ||
                limits.maxIdentifierChars <= 0 || limits.maxJsonBytes <= 0 || limits.maxJsonDepth <= 0 ||
                limits.maxJsonNodes <= 0 || limits.maxRecordBytes <= 0 || limits.maxStoreBytes <= 0 ||
                limits.maxStoreBytes > Int.MAX_VALUE / 8 || limits.maxRecordBytes > Int.MAX_VALUE / 8 || limits.maxJournalBytes <= 0 ||
                limits.maxJournalEntries <= 0 || limits.maxLeaseMillis <= 0
            ) throw WorkflowStoreException(Code.INVALID_INPUT)
            val canonical = try { directory.canonicalFile } catch (_: IOException) {
                throw WorkflowStoreException(Code.IO_FAILURE)
            } catch (_: SecurityException) { throw WorkflowStoreException(Code.IO_FAILURE) }
            synchronized(openDirectories) {
                if (!openDirectories.add(canonical.path)) throw WorkflowStoreException(Code.STORE_ALREADY_OPEN)
            }
            try {
                return DurableWorkflowStore(canonical, validator, limits, clock).also { it.recover() }
            } catch (e: Exception) {
                synchronized(openDirectories) { openDirectories.remove(canonical.path) }
                if (e is WorkflowStoreException) throw e
                throw WorkflowStoreException(Code.IO_FAILURE)
            }
        }
    }

    private fun recover() {
        journal.replay { entry ->
            val old = tasks[entry.task.id]
            if (entry.task.revision != (old?.revision?.plus(1) ?: 0L)) fail(Code.CORRUPT_STORE)
            try { validateRecord(entry.task) } catch (_: WorkflowStoreException) { fail(Code.CORRUPT_STORE) }
            if (old == null && tasks.size >= limits.maxTasks) fail(Code.LIMIT_EXCEEDED)
            checkStoreSize(entry.task)
            tasks[entry.task.id] = freeze(entry.task)
        }
        for (task in tasks.values.toList()) {
            if (task.lease != null || task.effects.any { it.state == EffectState.DISPATCHED }) {
                val uncertain = task.effects.any { it.state == EffectState.DISPATCHED }
                commit(task.copy(
                    revision = next(task.revision), lease = null,
                    state = if (uncertain && !terminal(task.state)) TaskState.RECONCILING else task.state,
                    effects = task.effects.map {
                        if (it.state == EffectState.DISPATCHED) it.copy(state = EffectState.UNKNOWN) else it
                    },
                ))
            }
        }
    }

    @Synchronized
    fun get(taskId: String): TaskRecord = freeze(read(taskId))

    @Synchronized
    fun list(): List<TaskRecord> { available(); return tasks.values.map(::freeze) }

    /** Only initial metadata/input are accepted. State, revision and outbox cannot be injected. */
    @Synchronized
    fun create(task: TaskRecord): TaskRecord {
        available()
        if (task.state != TaskState.CREATED || task.revision != 0L || task.fence != 0L || task.lease != null ||
            task.questions.isNotEmpty() || task.effects.isNotEmpty() || task.checkpoint.isNotEmpty() ||
            task.completionEvidence != null
        ) fail(Code.INVALID_INPUT)
        if (tasks.containsKey(task.id)) fail(Code.ALREADY_EXISTS)
        if (tasks.size >= limits.maxTasks) fail(Code.LIMIT_EXCEEDED)
        return commit(task)
    }

    @Synchronized
    fun acquireLease(taskId: String, expectedRevision: Long, owner: String, generation: Long, durationMillis: Long): TaskRecord {
        val task = expected(taskId, expectedRevision)
        text(owner)
        if ((terminal(task.state) || task.state == TaskState.PAUSED) &&
            task.effects.none { it.state == EffectState.DISPATCHED || it.state == EffectState.UNKNOWN }) fail(Code.INVALID_STATE)
        if (task.provider.generation != generation) fail(Code.GENERATION_CONFLICT)
        if (durationMillis <= 0 || durationMillis > limits.maxLeaseMillis) fail(Code.INVALID_INPUT)
        val now = now()
        if (task.lease?.let { it.expiresAtMillis > now } == true) fail(Code.LEASE_CONFLICT)
        if (now > Long.MAX_VALUE - durationMillis) fail(Code.INVALID_INPUT)
        val fence = next(task.fence)
        return commit(task.copy(revision = next(task.revision), fence = fence,
            lease = TaskLease(owner, fence, now + durationMillis, generation)))
    }

    @Synchronized
    fun releaseLease(taskId: String, expectedRevision: Long, token: Long): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        return commit(task.copy(revision = next(task.revision), lease = null))
    }

    /** Generic execution position; no workflow interpretation or network work is performed. */
    @Synchronized
    fun checkpoint(taskId: String, expectedRevision: Long, token: Long, checkpoint: JsonObject, state: TaskState): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        if (terminal(task.state) || task.state == TaskState.PAUSED || terminal(state) || state == TaskState.PAUSED ||
            state == TaskState.CREATED || (state == TaskState.WAITING_INPUT && task.questions.none { it.answer == null }) ||
            (state != TaskState.RECONCILING && task.effects.any { it.state == EffectState.UNKNOWN })
        ) fail(Code.INVALID_STATE)
        jsonValue(checkpoint)
        return commit(task.copy(revision = next(task.revision), checkpoint = checkpoint, state = state))
    }

    @Synchronized
    fun pause(taskId: String, expectedRevision: Long): TaskRecord {
        val task = expected(taskId, expectedRevision)
        if (terminal(task.state)) fail(Code.INVALID_STATE)
        if (task.state == TaskState.PAUSED) return freeze(task)
        return commit(task.copy(revision = next(task.revision), state = TaskState.PAUSED, lease = null))
    }

    @Synchronized
    fun resume(taskId: String, expectedRevision: Long): TaskRecord {
        val task = expected(taskId, expectedRevision)
        if (task.state != TaskState.PAUSED) fail(Code.INVALID_STATE)
        val state = when {
            task.effects.any { it.state == EffectState.UNKNOWN } -> TaskState.RECONCILING
            task.questions.any { it.answer == null } -> TaskState.WAITING_INPUT
            else -> TaskState.READY
        }
        return commit(task.copy(revision = next(task.revision), state = state))
    }

    @Synchronized
    fun cancel(taskId: String, expectedRevision: Long): TaskRecord {
        val task = expected(taskId, expectedRevision)
        if (task.state == TaskState.CANCELLED) return freeze(task)
        if (terminal(task.state)) fail(Code.INVALID_STATE)
        return commit(task.copy(revision = next(task.revision), state = TaskState.CANCELLED, lease = null))
    }

    /** Migration is explicit and cannot abandon an in-flight or uncertain effect. */
    @Synchronized
    fun replaceProvider(taskId: String, expectedRevision: Long, provider: ProviderBinding, migratedCheckpoint: JsonObject): TaskRecord {
        val task = expected(taskId, expectedRevision)
        if (task.state != TaskState.PAUSED || task.effects.any {
                it.state == EffectState.DISPATCHED || it.state == EffectState.UNKNOWN || it.state == EffectState.PREPARED
            }) fail(Code.INVALID_STATE)
        if (provider.generation <= task.provider.generation) fail(Code.GENERATION_CONFLICT)
        text(provider.id); jsonValue(migratedCheckpoint)
        return commit(task.copy(revision = next(task.revision), provider = provider, checkpoint = migratedCheckpoint, lease = null))
    }

    @Synchronized
    fun ask(taskId: String, expectedRevision: Long, token: Long, question: QuestionRecord): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        active(task)
        if (question.answer != null || question.version <= 0 || question.taskRevision != task.revision ||
            question.expiresAtMillis?.let { it <= now() } == true) fail(Code.INVALID_INPUT)
        if (task.questions.any { it.id == question.id }) fail(Code.ALREADY_EXISTS)
        return commit(task.copy(revision = next(task.revision), state = TaskState.WAITING_INPUT,
            questions = task.questions + question))
    }

    /** Exact retransmission is idempotent, even with the original expected revision. */
    @Synchronized
    fun answer(taskId: String, expectedRevision: Long, questionId: String, questionVersion: Long, answer: QuestionAnswer): TaskRecord {
        val task = read(taskId)
        val question = task.questions.find { it.id == questionId } ?: fail(Code.NOT_FOUND)
        if (question.version != questionVersion) fail(Code.QUESTION_CLOSED)
        text(answer.key); text(answer.author); text(answer.channel)
        jsonValue(answer.value); jsonValue(answer.consentEvidence)
        question.answer?.let {
            if (it.key == answer.key && it.author == answer.author && it.channel == answer.channel &&
                it.value == answer.value && it.consentEvidence == answer.consentEvidence) return freeze(task)
            fail(Code.QUESTION_CLOSED)
        }
        if (task.revision != expectedRevision) fail(Code.REVISION_CONFLICT)
        if (task.state != TaskState.WAITING_INPUT) fail(Code.INVALID_STATE)
        val received = now()
        if (question.expiresAtMillis?.let { it <= received } == true) fail(Code.QUESTION_EXPIRED)
        // Callbacks run under the monitor; reject reentrancy and pass detached values.
        val valid = try {
            inCallback = true
            validator.isValid(copyJson(question.schema), copyJson(answer.value))
        } catch (_: Exception) { fail(Code.VALIDATOR_FAILED) } finally { inCallback = false }
        if (!valid) fail(Code.INVALID_ANSWER)
        if (question.expiresAtMillis?.let { it <= now() } == true) fail(Code.QUESTION_EXPIRED)
        val updated = task.questions.map { if (it.id == questionId) it.copy(answer = answer.copy(receivedAtMillis = received)) else it }
        return commit(task.copy(revision = next(task.revision), questions = updated,
            state = if (updated.any { it.answer == null }) TaskState.WAITING_INPUT else TaskState.READY))
    }

    /** Persists intent and the new execution checkpoint together, before dispatch is allowed. */
    @Synchronized
    fun prepareEffect(taskId: String, expectedRevision: Long, token: Long, effect: EffectRecord, checkpoint: JsonObject): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        active(task)
        if (task.state != TaskState.RUNNING || task.effects.any { it.state == EffectState.UNKNOWN }) fail(Code.INVALID_STATE)
        if (effect.state != EffectState.PREPARED || effect.receipt != null || effect.evidence != null ||
            effect.providerGeneration != task.provider.generation) fail(Code.INVALID_INPUT)
        if (task.effects.any { it.id == effect.id || it.idempotencyKey == effect.idempotencyKey }) fail(Code.EFFECT_CONFLICT)
        jsonValue(checkpoint)
        return commit(task.copy(revision = next(task.revision), effects = task.effects + effect, checkpoint = checkpoint))
    }

    /** Caller may send ONLY after this durable operation returns successfully. No automatic retry exists. */
    @Synchronized
    fun dispatchEffect(taskId: String, expectedRevision: Long, token: Long, effectId: String): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        active(task)
        if (task.state != TaskState.RUNNING || task.effects.any { it.state == EffectState.UNKNOWN }) fail(Code.INVALID_STATE)
        val effect = task.effects.find { it.id == effectId } ?: fail(Code.NOT_FOUND)
        if (effect.state != EffectState.PREPARED) fail(Code.EFFECT_CONFLICT)
        if (effect.providerGeneration != task.provider.generation) fail(Code.GENERATION_CONFLICT)
        return commit(task.copy(revision = next(task.revision), effects = task.effects.map {
            if (it.id == effectId) it.copy(state = EffectState.DISPATCHED) else it
        }))
    }

    /** Reconciliation needs a fresh lease too. It records facts but cannot revive a cancelled task. */
    @Synchronized
    fun resolveEffect(taskId: String, expectedRevision: Long, token: Long, effectId: String,
        result: EffectState, receipt: JsonObject?, evidence: JsonObject?): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        val effect = task.effects.find { it.id == effectId } ?: fail(Code.NOT_FOUND)
        if (effect.providerGeneration != task.provider.generation) fail(Code.GENERATION_CONFLICT)
        if (effect.state != EffectState.DISPATCHED && effect.state != EffectState.UNKNOWN) fail(Code.EFFECT_CONFLICT)
        if (result != EffectState.CONFIRMED && result != EffectState.REJECTED && result != EffectState.UNKNOWN) fail(Code.INVALID_INPUT)
        if (result != EffectState.UNKNOWN && (receipt.isNullOrEmpty() || evidence.isNullOrEmpty())) fail(Code.EVIDENCE_REQUIRED)
        receipt?.let(::jsonValue); evidence?.let(::jsonValue)
        val effects = task.effects.map { if (it.id == effectId) it.copy(state = result, receipt = receipt, evidence = evidence) else it }
        return commit(task.copy(revision = next(task.revision), effects = effects,
            state = if (result == EffectState.UNKNOWN && !terminal(task.state) && task.state != TaskState.PAUSED) TaskState.RECONCILING else task.state))
    }

    @Synchronized
    fun complete(taskId: String, expectedRevision: Long, token: Long, result: TaskState, evidence: JsonObject): TaskRecord {
        val task = leased(taskId, expectedRevision, token)
        active(task)
        if (result != TaskState.SUCCEEDED && result != TaskState.FAILED) fail(Code.INVALID_INPUT)
        if (evidence.isEmpty()) fail(Code.EVIDENCE_REQUIRED)
        if (task.effects.any { it.state != EffectState.CONFIRMED && it.state != EffectState.REJECTED } ||
            task.questions.any { it.answer == null }) fail(Code.INVALID_STATE)
        jsonValue(evidence)
        return commit(task.copy(revision = next(task.revision), state = result, completionEvidence = evidence, lease = null))
    }

    @Synchronized
    fun snapshot() {
        available()
        try { journal.snapshot(tasks.values.toList()) } catch (e: WorkflowStoreException) {
            if (e.code == Code.IO_FAILURE) failed = true
            throw e
        }
    }

    @Synchronized
    override fun close() {
        if (inCallback) fail(Code.REENTRANT_CALL)
        if (!closed) {
            closed = true
            synchronized(openDirectories) { openDirectories.remove(directory.path) }
        }
    }

    private fun available() {
        if (inCallback) fail(Code.REENTRANT_CALL)
        if (closed) fail(Code.STORE_CLOSED)
        if (failed) fail(Code.IO_FAILURE)
    }

    private fun read(id: String): TaskRecord { available(); text(id); return tasks[id] ?: fail(Code.NOT_FOUND) }
    private fun expected(id: String, revision: Long): TaskRecord = read(id).also {
        if (it.revision != revision) fail(Code.REVISION_CONFLICT)
    }
    private fun leased(id: String, revision: Long, token: Long): TaskRecord = expected(id, revision).also {
        val lease = it.lease ?: fail(Code.STALE_LEASE)
        if (lease.token != token || lease.expiresAtMillis <= now() || lease.providerGeneration != it.provider.generation) fail(Code.STALE_LEASE)
    }
    private fun active(task: TaskRecord) { if (terminal(task.state) || task.state == TaskState.PAUSED) fail(Code.INVALID_STATE) }
    private fun terminal(state: TaskState) = state == TaskState.SUCCEEDED || state == TaskState.FAILED || state == TaskState.CANCELLED
    private fun next(value: Long): Long { if (value == Long.MAX_VALUE) fail(Code.LIMIT_EXCEEDED); return value + 1 }
    private fun now(): Long {
        if (inCallback) fail(Code.REENTRANT_CALL)
        return try { inCallback = true; clock().also { if (it < 0) fail(Code.INVALID_INPUT) } }
        catch (e: WorkflowStoreException) { throw e }
        catch (_: Exception) { fail(Code.INVALID_INPUT) }
        finally { inCallback = false }
    }

    private fun commit(value: TaskRecord): TaskRecord {
        validateRecord(value)
        val frozen = freeze(value)
        checkStoreSize(frozen)
        try { journal.append(frozen) } catch (e: WorkflowStoreException) {
            if (e.code == Code.IO_FAILURE) failed = true
            throw e
        }
        tasks[value.id] = frozen
        return freeze(frozen)
    }

    private fun checkStoreSize(value: TaskRecord) {
        val size = tasks.values.filter { it.id != value.id }.sumOf { json.encodeToString(it).toByteArray(Charsets.UTF_8).size.toLong() } +
            json.encodeToString(value).toByteArray(Charsets.UTF_8).size
        if (size > limits.maxStoreBytes) fail(Code.LIMIT_EXCEEDED)
    }

    private fun validateRecord(task: TaskRecord) {
        listOf(task.id, task.owner, task.scope, task.initiator, task.correlation, task.definitionVersion,
            task.executorPackageHash, task.contractVersion, task.provider.id).forEach(::text)
        if (task.provider.generation < 0 || task.revision < 0 || task.fence < 0) fail(Code.INVALID_INPUT)
        jsonValue(task.input); jsonValue(task.checkpoint); task.completionEvidence?.let(::jsonValue)
        if ((task.state == TaskState.SUCCEEDED || task.state == TaskState.FAILED) && task.completionEvidence.isNullOrEmpty()) fail(Code.EVIDENCE_REQUIRED)
        task.lease?.let {
            text(it.owner)
            if (it.token != task.fence || it.token <= 0 || it.expiresAtMillis < 0 || it.providerGeneration != task.provider.generation) fail(Code.INVALID_INPUT)
        }
        if (task.questions.size > limits.maxQuestionsPerTask || task.effects.size > limits.maxEffectsPerTask) fail(Code.LIMIT_EXCEEDED)
        if (task.questions.map { it.id }.distinct().size != task.questions.size ||
            task.effects.map { it.id }.distinct().size != task.effects.size ||
            task.effects.map { it.idempotencyKey }.distinct().size != task.effects.size) fail(Code.INVALID_INPUT)
        for (question in task.questions) {
            listOf(question.id, question.step, question.recipient).forEach(::text)
            if (question.version <= 0 || question.taskRevision < 0 || question.taskRevision > task.revision ||
                question.expiresAtMillis?.let { it < 0 } == true) fail(Code.INVALID_INPUT)
            jsonValue(question.schema)
            question.answer?.let {
                listOf(it.key, it.author, it.channel).forEach(::text)
                if (it.receivedAtMillis < 0) fail(Code.INVALID_INPUT)
                jsonValue(it.value); jsonValue(it.consentEvidence)
            }
        }
        for (effect in task.effects) {
            listOf(effect.id, effect.step, effect.operation, effect.recipient, effect.requestHash, effect.idempotencyKey).forEach(::text)
            if (effect.providerGeneration < 0) fail(Code.INVALID_INPUT)
            effect.receipt?.let(::jsonValue); effect.evidence?.let(::jsonValue)
            if ((effect.state == EffectState.CONFIRMED || effect.state == EffectState.REJECTED) &&
                (effect.receipt.isNullOrEmpty() || effect.evidence.isNullOrEmpty())) fail(Code.EVIDENCE_REQUIRED)
        }
        if (json.encodeToString(task).toByteArray(Charsets.UTF_8).size > limits.maxRecordBytes) fail(Code.LIMIT_EXCEEDED)
    }

    private fun text(value: String) {
        if (value.isBlank()) fail(Code.INVALID_INPUT)
        if (value.length > limits.maxIdentifierChars) fail(Code.LIMIT_EXCEEDED)
    }

    private fun jsonValue(value: JsonObject) {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(value to 1)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            if (++nodes > limits.maxJsonNodes || depth > limits.maxJsonDepth) fail(Code.LIMIT_EXCEEDED)
            val children = when (element) {
                is JsonObject -> element.values
                is JsonArray -> element
                else -> emptyList()
            }
            if (children.size > limits.maxJsonNodes - nodes || pending.size > limits.maxJsonNodes - children.size) fail(Code.LIMIT_EXCEEDED)
            children.forEach { pending.add(it to depth + 1) }
        }
        if (value.toString().toByteArray(Charsets.UTF_8).size > limits.maxJsonBytes) fail(Code.LIMIT_EXCEEDED)
    }

    private fun copyJson(value: JsonObject): JsonObject = json.decodeFromString(value.toString())
    private fun freeze(value: TaskRecord): TaskRecord = json.decodeFromString(json.encodeToString(value))
    private fun fail(code: Code): Nothing = throw WorkflowStoreException(code)
}
