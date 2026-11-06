package tech.amalia.platform.runtime.packages

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Host-generated, immutable proposal, not a grant or proof of user interaction. No public
 * constructor or copy operation: pass this exact object back only after explicit host approval.
 * [current] is historical journal state; [replaces] is its enabled selection, not live availability.
 */
class PackageInstallReview internal constructor(
    private val issuer: Any,
    val candidate: ActivationStageRef,
    val archiveSha256: String,
    val current: PackageActivation?,
    val compatibility: PackageCompatibility?,
    val activationPlan: org.amalia.platform.runtime.ActivationPlan,
    val chosenProfile: PackageSelectionProfile,
    val dataDeclaration: PackageDataDeclaration?,
    internal val rollbackReview: Boolean,
    val capabilities: Set<String>,
) {
    val expectedRevision: Long get() = current?.revision ?: 0
    val replaces: ActivationStageRef? get() = current?.selected

    internal fun issuedBy(owner: Any): Boolean = issuer === owner
}

/**
 * Exclusive host-only import/review/activate administration. Use the SAME store and journal as
 * [coordinator], and route all package mutations through this instance. Callbacks must not reenter
 * it. Neither this service nor its reviews belong in plugin contexts. See INSTALLER.md.
 *
 * [allowedRuntimeIds] is host compatibility policy, snapshotted at construction. It must be a
 * subset of the loader's supported IDs; the coordinator independently checks loader policy.
 * This service owns coordinator cleanup, but does not close the host's runtime or journal.
 */
class PackageInstaller(
    private val store: StagedPackageStore,
    private val journal: ActivationJournal,
    private val coordinator: PackageActivationCoordinator,
    allowedRuntimeIds: Set<String>,
    private val hostPolicy: PackageHostPolicy? = null,
) {
    private val allowedRuntimes = allowedRuntimeIds.toSet()
    private val issuer = Any()
    private val mutex = Mutex()
    private var closed = false

    /** Process-confirmed availability, initially empty after restart; not historical selection. */
    val live: StateFlow<Map<String, LivePackageActivation>> get() = coordinator.live

    /** Import only, on IO. Caller retains ownership of input; no loader or grant is invoked. */
    suspend fun stage(input: InputStream): StagedPackage = operation {
        val context = currentCoroutineContext()
        val cancellableInput = object : FilterInputStream(input) {
            override fun read(): Int {
                context.ensureActive()
                return input.read()
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                context.ensureActive()
                return input.read(bytes, offset, length)
            }
        }
        store.stage(cancellableInput).also { context.ensureActive() }
    }

    /** Durable historical records only. A selected stage need not be running in this process. */
    suspend fun historicalPackages(): List<PackageActivation> = operation { journal.list() }

    /** Re-verifies current archive bytes and returns a proposal without beginning an activation. */
    suspend fun review(stageId: String): PackageInstallReview = operation { makeReview(stageId) }

    /** Explicit host selection for a later resume; does not execute or automatically approve it. */
    suspend fun reviewSelected(packageId: String): PackageInstallReview = operation {
        val state = journal.get(packageId) ?: error("Package has no journal state")
        val selected = state.selected ?: error("Package has no enabled selection")
        makeReview(selected.stageId).also {
            check(it.current == state && it.candidate == selected) { "Selected stage verification changed" }
        }
    }

    /**
     * Calling this with a review is the trusted caller's explicit approval of exactly that proposal.
     * Rechecks bytes, policy and revision before delegating to the coordinator's fenced begin.
     * This API cannot establish that a person saw or approved any UI. It grants no permissions.
     */
    suspend fun activate(review: PackageInstallReview): PackageActivation = operation {
        activateReviewed(review)
    }

    /** Explicit resume only of the reviewed enabled selection, using the same approval checks. */
    suspend fun resumeSelected(review: PackageInstallReview): PackageActivation = operation {
        check(review.issuedBy(issuer)) { "Review belongs to another installer" }
        check(review.current?.selected == review.candidate) { "Review is not an enabled selection" }
        activateReviewed(review)
    }

    /** Exact explicit persistent choices; does not execute packages or grant operations. */
    suspend fun chooseProfile(
        profile: org.amalia.platform.runtime.SelectionProfile, expectedRevision: Long,
    ): PackageSelectionProfile = operation { policy().profiles.choose(profile, expectedRevision) }

    suspend fun chosenProfile(): PackageSelectionProfile = operation { policy().profiles.read() }

    /**
     * Recovery-host explicit rollback approval. Restores only the snapshot for current.previous,
     * re-verifies bytes/policy/dependencies and loads a fresh generation through the health probe.
     * Both the review and explicit data assertion are mandatory. Never calls journal.rollback's
     * selection-only shortcut. No external effects, arbitrary files or grants are restored.
     */
    suspend fun reviewRollback(packageId: String): PackageInstallReview = operation {
        val current = journal.get(packageId) ?: error("Package has no journal state")
        val previous = current.previous ?: error("No confirmed previous stage")
        makeReview(previous.stageId, rollback = true)
    }

    suspend fun rollback(review: PackageInstallReview, dataCompatible: Boolean): PackageActivation = operation {
        check(dataCompatible) { "Explicit data compatibility assertion required" }
        check(review.current?.previous == review.candidate) { "Review is not the previous stage" }
        activateReviewed(review, rollback = true)
    }

    /** Durable disable plus withdrawal/cleanup; stale revisions leave the live activation alone. */
    suspend fun disable(packageId: String, expectedRevision: Long): PackageActivation = operation {
        coordinator.disable(packageId, expectedRevision)
    }

    /** Process cleanup only; selection survives. Call before closing the runtime and journal. */
    suspend fun shutdown() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                coordinator.close()
            }
        }
    }

    private suspend fun activateReviewed(review: PackageInstallReview, rollback: Boolean = false): PackageActivation {
        check(review.issuedBy(issuer)) { "Review belongs to another installer" }
        check(review.rollbackReview == rollback) { "Review requires its exact activation or rollback action" }
        val refreshed = makeReview(review.candidate.stageId, rollback)
        if (refreshed.expectedRevision != review.expectedRevision) {
            throw ActivationRevisionException(review.expectedRevision, refreshed.expectedRevision)
        }
        check(refreshed.current == review.current && refreshed.candidate == review.candidate &&
            refreshed.archiveSha256 == review.archiveSha256 && refreshed.chosenProfile == review.chosenProfile &&
            refreshed.activationPlan == review.activationPlan && refreshed.capabilities == review.capabilities) { "Package review changed; review again" }
        currentCoroutineContext().ensureActive()
        // Coordinator re-verifies and journal.begin performs final revision/publisher checks before
        // any old provider is withdrawn. Never substitute activateSelected's implicit revision read.
        val policy = policy()
        return coordinator.installWithTransaction(review.candidate.stageId, review.expectedRevision,
            object : PackageActivationTransaction {
                private var prepared = false
                override fun beforeBegin(verified: VerifiedPackage, current: PackageActivation?) {
                    check(current == review.current) { "Journal changed after review" }
                    check(policy.profiles.read() == review.chosenProfile) { "Chosen profile changed" }
                    check(policy.preflight(verified.manifest, review.chosenProfile.profile) == review.activationPlan) {
                        "Dependency inventory changed; review again"
                    }
                    if (rollback) check(current?.previous == review.candidate) { "Rollback target changed" }
                    policy.data.preflight(review.candidate.packageId, verified.manifest.data,
                        review.candidate.stageId.takeIf { rollback })
                }
                override fun afterWithdrawal() {
                    // begin publishes image+intent atomically. Reconcile even if fsync outcome is unknown.
                    prepared = true
                    policy.data.begin(review.candidate.packageId, review.current, review.candidate,
                        review.dataDeclaration, review.candidate.stageId.takeIf { rollback })
                }
                override fun afterConfirmation() { policy.data.commit(review.candidate.packageId) }
                override fun reconcile() {
                    if (prepared) policy.data.recover(review.candidate.packageId, journal.get(review.candidate.packageId))
                }
            })
    }

    private suspend fun makeReview(stageId: String, rollback: Boolean = false): PackageInstallReview {
        currentCoroutineContext().ensureActive()
        val verified = store.verifyStaged(stageId)
        val manifest = verified.manifest
        val candidate = ActivationStageRef(stageId, manifest.packageId, manifest.version,
            manifest.runtime, verified.manifestSha256, verified.identity)
        check(candidate.runtime in allowedRuntimes) { "Runtime is not allowed by host compatibility policy" }
        val current = journal.get(candidate.packageId)
        if (current != null) requirePublisher(current.publisher, candidate.identity)
        val policy = policy()
        // Crash recovery is safe only when no generation can still write this namespace.
        // A pending transaction in this process is a failed/indeterminate operation and must be
        // recovered under a fresh coordinator, never concurrently with live package code.
        if (candidate.packageId !in coordinator.live.value) policy.data.recover(candidate.packageId, current)
        if (rollback) check(current?.previous == candidate) { "Not the confirmed previous stage" }
        val profile = policy.profiles.read()
        val resolved = policy.preflight(manifest, profile.profile)
        val plan = resolved.copy(
            orderedPackages = java.util.Collections.unmodifiableList(resolved.orderedPackages.toList()),
            bindings = java.util.Collections.unmodifiableList(resolved.bindings.map { binding ->
                binding.copy(provider = binding.provider.copy(
                    contracts = java.util.Collections.unmodifiableList(binding.provider.contracts.map { offer ->
                        offer.copy(features = java.util.Collections.unmodifiableSet(offer.features.toSet()))
                    }),
                    transports = java.util.Collections.unmodifiableSet(binding.provider.transports.toSet()),
                ))
            }),
            optionalUnavailable = java.util.Collections.unmodifiableList(resolved.optionalUnavailable.map {
                it.copy(candidates = java.util.Collections.unmodifiableList(it.candidates.toList()))
            }),
            blocking = java.util.Collections.unmodifiableList(resolved.blocking.toList()),
        )
        policy.data.preflight(candidate.packageId, manifest.data, stageId.takeIf { rollback })
        val archiveHash = archiveHash(stageId)
        currentCoroutineContext().ensureActive()
        return PackageInstallReview(issuer, candidate, archiveHash, current, manifest.compatibility, plan, profile, manifest.data, rollback, manifest.capabilities)
    }

    private fun policy(): PackageHostPolicy = hostPolicy ?: error("Explicit package host policy is required for activation")

    /** Mirror ActivationJournal.requirePublisher; begin remains the authoritative final check. */
    private fun requirePublisher(owner: PublisherIdentity, candidate: PublisherIdentity) {
        check(publisherName(owner) == publisherName(candidate)) { "Publisher takeover rejected" }
        check(owner !is PublisherIdentity.PinnedSignature || candidate is PublisherIdentity.PinnedSignature) {
            "Unsigned replacement of authenticated publisher rejected"
        }
    }

    private fun publisherName(identity: PublisherIdentity): String = when (identity) {
        is PublisherIdentity.Unverified -> identity.claimedPublisher
        is PublisherIdentity.PinnedSignature -> identity.publisher
    }

    private suspend fun archiveHash(stageId: String): String {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(store.stageDirectory(stageId).resolve("archive.zip"), READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Package installer is shut down" }
            currentCoroutineContext().ensureActive()
            block()
        }
    }
}
