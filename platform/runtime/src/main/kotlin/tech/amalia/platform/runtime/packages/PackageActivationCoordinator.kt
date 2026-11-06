package tech.amalia.platform.runtime.packages

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.amalia.platform.protocol.CallerIdentity
import org.amalia.platform.protocol.PluginEntrypoint
import org.amalia.platform.protocol.ProtocolClient
import org.amalia.platform.runtime.CoreRuntime

/** Installer-owned transaction; never passed to package code. */
internal interface PackageActivationTransaction {
    fun beforeBegin(verified: VerifiedPackage, current: PackageActivation?)
    fun afterWithdrawal()
    fun afterConfirmation()
    fun reconcile()
}

/** Trusted host policy. DATA_ONLY forbids executing package bytes, including embedded expressions. */
enum class UnsignedDeclarationsPolicy { REJECT, DATA_ONLY }

/**
 * Host-only loader, never implemented or selected by package input. Loading must use only this
 * verified private stage; unsupported runtime IDs must never reach a fallback code loader.
 * A declarations loader is a restricted data interpreter, even for signed declarations.
 */
interface RuntimeLoader {
    val supportedRuntimeIds: Set<String>
    val unsignedDeclarationsPolicy: UnsignedDeclarationsPolicy

    /**
     * Creates a fresh entrypoint without starting it. On failure/cancellation before returning,
     * the loader owns cleanup of all partially created resources. Do not retain host administration
     * in the entrypoint. The store's private archive/content must remain immutable while in use.
     */
    suspend fun load(context: VerifiedStagedContext): LoadedPackageRuntime
}

/** Constructed only after current verification and a durable journal begin. Not a permission grant. */
class VerifiedStagedContext internal constructor(
    val stage: ActivationStageRef,
    val verification: VerifiedPackage,
    val archive: Path,
    val contentDirectory: Path,
    val identity: CallerIdentity,
    val journalGeneration: Long,
)

/** Host-owned loader resources. close follows runtime withdrawal and the bounded stop hook. */
interface LoadedPackageRuntime {
    val entrypoint: PluginEntrypoint
    suspend fun close()
}

/** The host must actively verify readiness; returning false rejects the candidate. No default probe. */
fun interface PackageHealthProbe {
    suspend fun check(context: PackageHealthContext): Boolean
}

/** No runtime administration or implicit grants are exposed by this probe context. */
class PackageHealthContext internal constructor(
    val stage: ActivationStageRef,
    val identity: CallerIdentity,
    val journalGeneration: Long,
    val runtimeGeneration: Long,
    val client: ProtocolClient,
)

/** A confirmed live activation in this process, distinct from the journal's historical selection. */
data class LivePackageActivation(
    val stage: ActivationStageRef,
    val identity: CallerIdentity,
    val journalGeneration: Long,
    val runtimeGeneration: Long,
)

/**
 * Trusted host administration; do not hand this object, its loader, or CoreRuntime to plugins.
 * Use one coordinator as exclusive administrator of its packages and journal. All operations are
 * serialized, including host callbacks, which must not reenter this coordinator. See COORDINATOR.md.
 * This object owns loaded activations, not the runtime, store or journal supplied by the host.
 */
class PackageActivationCoordinator(
    private val store: StagedPackageStore,
    private val journal: ActivationJournal,
    private val runtime: CoreRuntime,
    private val deviceId: String,
    private val loader: RuntimeLoader,
    private val healthProbe: PackageHealthProbe,
    private val healthTimeoutMillis: Long = 30_000,
    private val cleanupTimeoutMillis: Long = 10_000,
) {
    private val mutex = Mutex()
    private val owned = mutableMapOf<String, OwnedActivation>()
    private val mutableLive = MutableStateFlow<Map<String, LivePackageActivation>>(emptyMap())
    private var closed = false

    /** Missing means unavailable here, even when journal.selected still names a historical stage. */
    val live: StateFlow<Map<String, LivePackageActivation>> = mutableLive.asStateFlow()

    init {
        require(deviceId.isNotBlank())
        require(healthTimeoutMillis > 0 && cleanupTimeoutMillis > 0)
    }

    /** Legacy source-compatible entry point. Fails closed: activation must use PackageInstaller,
     * whose internal transaction supplies compatibility/dependency and data policy.
     */
    suspend fun installAndActivate(stageId: String, expectedRevision: Long): PackageActivation = mutex.withLock {
        usable()
        val verified = store.verifyStaged(stageId)
        activate(stageId, verified, expectedRevision)
    }

    internal suspend fun installWithTransaction(
        stageId: String, expectedRevision: Long, transaction: PackageActivationTransaction,
    ): PackageActivation = mutex.withLock {
        usable()
        activate(stageId, store.verifyStaged(stageId), expectedRevision, transaction)
    }

    /** Legacy source-compatible entry point, fails closed without installer transaction.
     * Use installer.reviewSelected/resumeSelected for explicit reverified restart.
     */
    suspend fun activateSelected(packageId: String): PackageActivation = mutex.withLock {
        usable()
        val selected = journal.get(packageId) ?: error("Package has no journal state")
        val stage = selected.selected ?: error("Package has no enabled selection")
        val verified = store.verifyStaged(stage.stageId)
        check(stageRef(stage.stageId, verified) == stage) { "Selected stage verification changed" }
        activate(stage.stageId, verified, selected.revision)
    }

    /**
     * Durable disable followed by withdrawal and bounded cleanup. A failed/indeterminate commit also
     * withdraws live code; a preflight revision conflict leaves it alone. No automatic grants exist.
     */
    suspend fun disable(packageId: String, expectedRevision: Long): PackageActivation = mutex.withLock {
        usable()
        val state = journal.get(packageId) ?: error("Package has no journal state")
        if (state.revision != expectedRevision) throw ActivationRevisionException(expectedRevision, state.revision)
        try {
            val disabled = journal.disable(packageId, expectedRevision)
            withContext(NonCancellable) { withdraw(packageId) }
            disabled
        } catch (error: Throwable) {
            withContext(NonCancellable) { suppress(error) { withdraw(packageId) } }
            throw error
        }
    }

    /**
     * Process cleanup, not durable disable. Leaves selection intact for an explicit restart probe.
     * Suspends until bounded cleanup has been attempted for every owned activation. Idempotent.
     * Call before closing the host runtime/journal. Cancellation cannot skip withdrawal.
     */
    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                var failure: Throwable? = null
                // Withdraw every provider before awaiting any plugin hook.
                val remaining = owned.values.toList()
                owned.clear()
                mutableLive.value = emptyMap()
                remaining.forEach { entry ->
                    try { entry.activation.close() }
                    catch (error: Throwable) { failure = combine(failure, error) }
                }
                remaining.forEach { entry ->
                    try { dispose(entry.loaded, entry.entrypoint, entry.activation) }
                    catch (error: Throwable) { failure = combine(failure, error) }
                }
                failure?.let { throw it }
            }
        }
    }

    /** Called only under mutex; begin is durable before any loader call or old-provider withdrawal. */
    private suspend fun activate(
        stageId: String,
        verified: VerifiedPackage,
        expectedRevision: Long,
        transaction: PackageActivationTransaction? = null,
    ): PackageActivation {
        val stage = stageRef(stageId, verified)
        check(stage.runtime in loader.supportedRuntimeIds) { "Unsupported package runtime" }
        if (verified.manifest.isDeclarationOnly) {
            check(stage.identity is PublisherIdentity.PinnedSignature ||
                loader.unsignedDeclarationsPolicy == UnsignedDeclarationsPolicy.DATA_ONLY) {
                "Loader rejects unsigned declarations"
            }
        } else {
            check(stage.identity is PublisherIdentity.PinnedSignature) { "Executable package requires pinned signature" }
        }
        val current = journal.get(stage.packageId)
        if ((current?.revision ?: 0) != expectedRevision) {
            throw ActivationRevisionException(expectedRevision, current?.revision ?: 0)
        }
        check(transaction != null) { "Activation requires installer compatibility/data transaction policy" }
        transaction.beforeBegin(verified, current)
        currentCoroutineContext().ensureActive()
        val begun = journal.begin(stage.packageId, stageId, expectedRevision)
        val attempt = requireNotNull(begun.pending)
        var loaded: LoadedPackageRuntime? = null
        var entrypoint: PluginEntrypoint? = null
        var activation: CoreRuntime.Activation? = null
        var failureCode = ActivationFailureCode.START_FAILED
        try {
            check(attempt.stage == stage) { "Stage verification changed before begin" }
            // The journal retains its historical selection, but it is no longer available here.
            withContext(NonCancellable) { withdraw(stage.packageId) }
            transaction.afterWithdrawal()
            currentCoroutineContext().ensureActive()
            val publisher = when (val identity = stage.identity) {
                is PublisherIdentity.PinnedSignature -> identity.publisher
                is PublisherIdentity.Unverified -> identity.claimedPublisher
            }
            // Token is a fresh durable UUID; generation is monotonic in this package journal.
            // Unverified names are permitted only for restricted declaration interpretation.
            val identity = CallerIdentity(publisher, stage.packageId,
                "package:${stage.packageId}:${attempt.generation}:${attempt.token}", deviceId)
            val directory = store.stageDirectory(stageId)
            val context = VerifiedStagedContext(stage, verified, directory.resolve("archive.zip"),
                directory.resolve("content"), identity, attempt.generation)
            val candidate = loader.load(context)
            loaded = candidate
            currentCoroutineContext().ensureActive()
            val candidateEntrypoint = candidate.entrypoint
            entrypoint = candidateEntrypoint
            val started = runtime.activate(identity, candidateEntrypoint)
            activation = started
            failureCode = ActivationFailureCode.HEALTH_CHECK_FAILED
            val healthy = withTimeout(healthTimeoutMillis) {
                healthProbe.check(PackageHealthContext(stage, identity, attempt.generation,
                    started.generation, started.client))
            }
            check(healthy) { "Package health probe rejected activation" }
            currentCoroutineContext().ensureActive()
            started.scope.coroutineContext.ensureActive()
            val confirmed = journal.confirm(stage.packageId, begun.revision, attempt.generation, attempt.token)
            transaction.afterConfirmation()
            val liveEntry = LivePackageActivation(stage, identity, attempt.generation, started.generation)
            owned[stage.packageId] = OwnedActivation(candidate, candidateEntrypoint, started)
            mutableLive.value = mutableLive.value + (stage.packageId to liveEntry)
            return confirmed
        } catch (error: Throwable) {
            // Keep the original exception (especially cancellation), attaching cleanup/persistence errors.
            withContext(NonCancellable) {
                suppress(error) { activation?.close() }
                suppress(error) { dispose(loaded, entrypoint, activation) }
                suppress(error) {
                    journal.fail(stage.packageId, begun.revision, attempt.generation, attempt.token,
                        if (error is CancellationException) ActivationFailureCode.CANCELLED else failureCode)
                }
                // Read authoritative journal outcome, never blindly restore after uncertain confirm.
                suppress(error) { transaction.reconcile() }
            }
            throw error
        }
    }

    private suspend fun withdraw(packageId: String) {
        mutableLive.value = mutableLive.value - packageId
        val old = owned.remove(packageId) ?: return
        var failure: Throwable? = null
        try { old.activation.close() } catch (error: Throwable) { failure = error }
        try { dispose(old.loaded, old.entrypoint, old.activation) }
        catch (error: Throwable) { failure = combine(failure, error) }
        failure?.let { throw it }
    }

    private suspend fun dispose(
        loaded: LoadedPackageRuntime?,
        entrypoint: PluginEntrypoint?,
        activation: CoreRuntime.Activation?,
    ) {
        var failure: Throwable? = null
        try {
            if (activation != null) withTimeout(cleanupTimeoutMillis) { activation.stop() }
            else if (entrypoint != null) withTimeout(cleanupTimeoutMillis) { entrypoint.stop() }
        } catch (error: Throwable) { failure = error }
        try { if (loaded != null) withTimeout(cleanupTimeoutMillis) { loaded.close() } }
        catch (error: Throwable) { failure = combine(failure, error) }
        failure?.let { throw it }
    }

    private suspend fun suppress(original: Throwable, cleanup: suspend () -> Unit) {
        try { cleanup() } catch (error: Throwable) { if (error !== original) original.addSuppressed(error) }
    }

    private fun combine(first: Throwable?, next: Throwable): Throwable {
        if (first == null) return next
        if (first !== next) first.addSuppressed(next)
        return first
    }

    private fun stageRef(stageId: String, verified: VerifiedPackage): ActivationStageRef =
        verified.manifest.let {
            ActivationStageRef(stageId, it.packageId, it.version, it.runtime, verified.manifestSha256, verified.identity)
        }

    private fun usable() { check(!closed) { "Package activation coordinator is closed" } }

    private class OwnedActivation(
        val loaded: LoadedPackageRuntime,
        val entrypoint: PluginEntrypoint,
        val activation: CoreRuntime.Activation,
    )
}
