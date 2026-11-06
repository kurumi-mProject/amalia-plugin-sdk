package tech.amalia.platform.runtime.packages

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Historical verified metadata, never an execution handle or a grant. */
data class ActivationStageRef(
    val stageId: String,
    val packageId: String,
    val version: String,
    val runtime: String,
    val manifestSha256: String,
    val identity: PublisherIdentity,
)

enum class ActivationAttemptStatus { STARTING, FAILED }

/** Only fixed, bounded codes are persisted; never pass exception messages or user input. */
enum class ActivationFailureCode {
    START_FAILED, HEALTH_CHECK_FAILED, CANCELLED, INTERRUPTED, DISABLED_BY_HOST,
}

data class ActivationAttempt(
    val stage: ActivationStageRef,
    val generation: Long,
    val token: String,
    val status: ActivationAttemptStatus,
)

data class PackageActivation(
    val packageId: String,
    val revision: Long,
    val generation: Long,
    val publisher: PublisherIdentity,
    val active: ActivationStageRef?,
    val previous: ActivationStageRef?,
    val pending: ActivationAttempt?,
    val disabled: Boolean,
    val failure: ActivationFailureCode?,
) {
    /** Selection only. The host still owns loading, policy, bindings, scopes and permissions. */
    val selected: ActivationStageRef? get() = active.takeUnless { disabled }
}

class ActivationJournalException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ActivationRevisionException(val expected: Long, val actual: Long) :
    IOException("Activation revision conflict: expected $expected, actual $actual")

/**
 * An exclusively locked, bounded, append-only journal. Use an existing app-private directory on a
 * filesystem supporting atomic rename and directory fsync. Never remove journal files or its lock.
 * All methods are serialized; close releases the lifetime process lock. See ACTIVATION.md.
 */
class ActivationJournal(root: Path, private val store: StagedPackageStore) : AutoCloseable {
    private val root = root.toAbsolutePath().normalize()
    private val lockChannel: FileChannel
    private val lock: FileLock
    private val states = linkedMapOf<String, PackageActivation>()
    private var sequence = 0L
    private var chain = ZERO_HASH
    private var closed = false
    private var poisoned = false

    init {
        require(Files.isDirectory(this.root, NOFOLLOW_LINKS)) { "Journal root must be an existing real directory" }
        val lockPath = this.root.resolve("journal.lock")
        lockChannel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            lock = lockChannel.tryLock() ?: throw ActivationJournalException("Journal is already locked")
            try {
                syncDirectory()
                replay()
                val interrupted = states.values.filter { it.pending?.status == ActivationAttemptStatus.STARTING }
                checkJournal(sequence + interrupted.size <= MAX_RECORDS) { "Journal capacity exhausted during recovery" }
                interrupted.forEach { old ->
                    commit(Operation.RECOVER, failed(old, ActivationFailureCode.INTERRUPTED))
                }
            } catch (error: Throwable) {
                try { lock.release() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        } catch (error: Throwable) {
            try { lockChannel.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized
    fun list(): List<PackageActivation> { usable(); return states.values.sortedBy { it.packageId } }

    @Synchronized
    fun get(packageId: String): PackageActivation? { usable(); validPackageId(packageId); return states[packageId] }

    /** Persist STARTING before calling any loader. Missing packages have expectedRevision = 0. */
    @Synchronized
    fun begin(packageId: String, stageId: String, expectedRevision: Long): PackageActivation {
        val old = current(packageId, expectedRevision)
        checkJournal(old?.pending?.status != ActivationAttemptStatus.STARTING) { "An attempt is already starting" }
        val stage = verifiedStage(stageId)
        checkJournal(stage.packageId == packageId) { "Stage package ID mismatch" }
        if (old != null) requirePublisher(old.publisher, stage.identity)
        checkJournal(old != null || states.size < MAX_PACKAGES) { "Journal package limit reached" }
        val generation = increment(old?.generation ?: 0)
        val next = PackageActivation(
            packageId, increment(old?.revision ?: 0), generation, stage.identity,
            old?.active, old?.previous,
            ActivationAttempt(stage, generation, UUID.randomUUID().toString(), ActivationAttemptStatus.STARTING),
            old?.disabled ?: true, null,
        )
        return commit(Operation.BEGIN, next)
    }

    /** Health is asserted by the caller; the journal never runs a probe or loads code. */
    @Synchronized
    fun confirm(
        packageId: String, expectedRevision: Long, generation: Long, token: String,
    ): PackageActivation {
        val old = existing(packageId, expectedRevision)
        val attempt = fenced(old, generation, token)
        // Recheck current store policy and immutable archive identity before changing selection.
        checkJournal(verifiedStage(attempt.stage.stageId) == attempt.stage) { "Stage verification changed" }
        return commit(Operation.CONFIRM, confirmed(old))
    }

    @Synchronized
    fun fail(
        packageId: String, expectedRevision: Long, generation: Long, token: String,
        reason: ActivationFailureCode,
    ): PackageActivation {
        val old = existing(packageId, expectedRevision)
        fenced(old, generation, token)
        checkJournal(reason in CALLER_FAILURES) { "Failure code is reserved for journal recovery or disable" }
        return commit(Operation.FAIL, failed(old, reason))
    }

    /** Records intent only: the host must separately stop running code and revoke registrations. */
    @Synchronized
    fun disable(packageId: String, expectedRevision: Long): PackageActivation {
        val old = existing(packageId, expectedRevision)
        return commit(Operation.DISABLE, disabled(old))
    }

    /** No data snapshot is created or restored. An explicit compatibility assertion is mandatory. */
    @Synchronized
    fun rollback(
        packageId: String, expectedRevision: Long, dataCompatible: Boolean,
    ): PackageActivation {
        val old = existing(packageId, expectedRevision)
        checkJournal(dataCompatible) { "Rollback requires caller assertion of data compatibility" }
        checkJournal(old.pending?.status != ActivationAttemptStatus.STARTING) { "Fail the pending attempt first" }
        val previous = old.previous ?: throw ActivationJournalException("No confirmed previous stage")
        requirePublisher(old.publisher, previous.identity)
        checkJournal(verifiedStage(previous.stageId) == previous) { "Previous stage verification changed" }
        return commit(Operation.ROLLBACK, rolledBack(old))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { lock.release() } finally { lockChannel.close() }
    }

    private fun current(id: String, expectedRevision: Long): PackageActivation? {
        usable()
        validPackageId(id)
        require(expectedRevision >= 0) { "Negative expected revision" }
        val old = states[id]
        val actual = old?.revision ?: 0
        if (actual != expectedRevision) throw ActivationRevisionException(expectedRevision, actual)
        return old
    }

    private fun existing(id: String, revision: Long): PackageActivation =
        current(id, revision) ?: throw ActivationJournalException("Package has no journal state")

    private fun usable() {
        checkJournal(!closed && !poisoned) { "Journal is closed or needs reopening after an I/O failure" }
    }

    private fun verifiedStage(id: String): ActivationStageRef {
        val verified = store.verifyStaged(id)
        val manifest = verified.manifest
        return ActivationStageRef(id, manifest.packageId, manifest.version, manifest.runtime,
            verified.manifestSha256, verified.identity).also(::validateStage)
    }

    private fun fenced(old: PackageActivation, generation: Long, token: String): ActivationAttempt {
        val attempt = old.pending
        checkJournal(attempt != null && attempt.status == ActivationAttemptStatus.STARTING &&
            attempt.generation == generation && old.generation == generation && attempt.token == token) {
            "Stale or non-starting activation attempt"
        }
        return requireNotNull(attempt)
    }

    private fun confirmed(old: PackageActivation): PackageActivation = old.copy(
        revision = increment(old.revision), active = requireNotNull(old.pending).stage,
        previous = if (old.active == old.pending?.stage) old.previous else old.active,
        pending = null, disabled = false, failure = null,
    )

    private fun failed(old: PackageActivation, reason: ActivationFailureCode): PackageActivation = old.copy(
        revision = increment(old.revision),
        pending = requireNotNull(old.pending).copy(status = ActivationAttemptStatus.FAILED), failure = reason,
    )

    private fun disabled(old: PackageActivation): PackageActivation = old.copy(
        revision = increment(old.revision), generation = increment(old.generation), disabled = true,
        pending = old.pending?.copy(status = ActivationAttemptStatus.FAILED),
        failure = ActivationFailureCode.DISABLED_BY_HOST,
    )

    private fun rolledBack(old: PackageActivation): PackageActivation = old.copy(
        revision = increment(old.revision), generation = increment(old.generation),
        active = old.previous, previous = null, pending = null, disabled = false, failure = null,
    )

    private fun requirePublisher(owner: PublisherIdentity, candidate: PublisherIdentity) {
        checkJournal(publisherName(owner) == publisherName(candidate)) { "Publisher takeover rejected" }
        checkJournal(owner !is PublisherIdentity.PinnedSignature || candidate is PublisherIdentity.PinnedSignature) {
            "Unsigned replacement of authenticated publisher rejected"
        }
    }

    private fun publisherName(identity: PublisherIdentity): String = when (identity) {
        is PublisherIdentity.Unverified -> identity.claimedPublisher
        is PublisherIdentity.PinnedSignature -> identity.publisher
    }

    private fun validateStage(stage: ActivationStageRef) {
        checkJournal(UUID_PATTERN.matches(stage.stageId)) { "Invalid stage ID" }
        validPackageId(stage.packageId)
        checkJournal(VERSION_PATTERN.matches(stage.version) && RUNTIME_PATTERN.matches(stage.runtime) &&
            HASH_PATTERN.matches(stage.manifestSha256)) { "Invalid stage metadata" }
        validateIdentity(stage.identity)
        checkJournal(stage.runtime == "declarations" || stage.identity is PublisherIdentity.PinnedSignature) {
            "Code stage must have a pinned publisher"
        }
    }

    private fun validateIdentity(identity: PublisherIdentity) {
        validPackageId(publisherName(identity))
        if (identity is PublisherIdentity.PinnedSignature) {
            checkJournal(KEY_PATTERN.matches(identity.keyId)) { "Invalid publisher key ID" }
        }
    }

    private fun validateState(state: PackageActivation) {
        validPackageId(state.packageId)
        validateIdentity(state.publisher)
        checkJournal(state.revision > 0 && state.generation > 0) { "Invalid counters" }
        listOfNotNull(state.active, state.previous, state.pending?.stage).forEach { stage ->
            validateStage(stage)
            checkJournal(stage.packageId == state.packageId &&
                publisherName(stage.identity) == publisherName(state.publisher)) { "Stage identity mismatch" }
        }
        checkJournal(state.active != null || state.disabled) { "Missing active stage must be disabled" }
        checkJournal(state.previous == null || state.active != null) { "Previous without active stage" }
        checkJournal(state.previous == null || state.previous != state.active) { "Duplicate active and previous" }
        state.pending?.let {
            checkJournal(it.generation in 1..state.generation && UUID_PATTERN.matches(it.token)) { "Invalid attempt fence" }
            if (it.status == ActivationAttemptStatus.STARTING) {
                checkJournal(it.generation == state.generation && state.failure == null) { "Invalid starting attempt" }
            } else checkJournal(state.failure != null) { "Failed attempt without reason" }
        }
        if (state.failure != null) {
            checkJournal(state.pending?.status == ActivationAttemptStatus.FAILED ||
                (state.disabled && state.failure == ActivationFailureCode.DISABLED_BY_HOST)) { "Orphan failure" }
        }
    }

    private fun validateTransition(operation: Operation, next: PackageActivation) {
        validateState(next)
        val old = states[next.packageId]
        checkJournal(next.revision == increment(old?.revision ?: 0)) { "Revision discontinuity" }
        val expected = when (operation) {
            Operation.BEGIN -> {
                checkJournal(old?.pending?.status != ActivationAttemptStatus.STARTING) { "Overlapping attempt" }
                val attempt = next.pending ?: throw ActivationJournalException("Missing begin attempt")
                checkJournal(attempt.status == ActivationAttemptStatus.STARTING &&
                    attempt.generation == increment(old?.generation ?: 0) &&
                    attempt.token != old?.pending?.token) { "Invalid begin fence" }
                if (old != null) requirePublisher(old.publisher, attempt.stage.identity)
                PackageActivation(next.packageId, next.revision, attempt.generation, attempt.stage.identity,
                    old?.active, old?.previous, attempt, old?.disabled ?: true, null)
            }
            else -> {
                val before = old ?: throw ActivationJournalException("Transition without package")
                when (operation) {
                    Operation.CONFIRM -> {
                        checkJournal(before.pending?.status == ActivationAttemptStatus.STARTING) { "Confirm without starting" }
                        confirmed(before)
                    }
                    Operation.FAIL, Operation.RECOVER -> {
                        checkJournal(before.pending?.status == ActivationAttemptStatus.STARTING) { "Fail without starting" }
                        val reason = next.failure ?: throw ActivationJournalException("Missing failure code")
                        checkJournal(if (operation == Operation.RECOVER) reason == ActivationFailureCode.INTERRUPTED
                            else reason in CALLER_FAILURES) { "Invalid failure code" }
                        failed(before, reason)
                    }
                    Operation.DISABLE -> disabled(before)
                    Operation.ROLLBACK -> {
                        checkJournal(before.previous != null &&
                            before.pending?.status != ActivationAttemptStatus.STARTING) { "Invalid rollback" }
                        requirePublisher(before.publisher, requireNotNull(before.previous).identity)
                        rolledBack(before)
                    }
                    Operation.BEGIN -> throw ActivationJournalException("Invalid operation")
                }
            }
        }
        checkJournal(next == expected) { "Invalid state transition" }
        checkJournal(old != null || states.size < MAX_PACKAGES) { "Journal package limit reached" }
    }

    private fun commit(operation: Operation, next: PackageActivation): PackageActivation {
        usable()
        checkJournal(sequence < MAX_RECORDS) { "Journal capacity exhausted" }
        validateTransition(operation, next)
        val number = sequence + 1
        val payload = encode(number, chain, operation, next)
        checkJournal(payload.size + HASH_BYTES <= MAX_RECORD_BYTES) { "Record size limit reached" }
        val digest = payload.sha256Hex()
        val bytes = payload + hexBytes(digest)
        val temporary = root.resolve("record.tmp")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No replacement and no non-atomic fallback. The lifetime lock protects the namespace.
            Files.move(temporary, root.resolve(recordName(number)), ATOMIC_MOVE)
            syncDirectory()
        } catch (error: Exception) {
            poisoned = true // Rename/fsync errors have an indeterminate commit outcome; reopen and replay.
            throw ActivationJournalException("Journal commit failed; close and reopen", error)
        }
        sequence = number
        chain = digest
        states[next.packageId] = next
        return next
    }

    private fun replay() {
        val records = ArrayList<Path>()
        var temporary: Path? = null
        Files.newDirectoryStream(root).use { entries ->
            var count = 0
            for (entry in entries) {
                checkJournal(++count <= MAX_RECORDS + 2) { "Journal file count limit exceeded" }
                val name = entry.fileName.toString()
                checkJournal(Files.isRegularFile(entry, NOFOLLOW_LINKS)) { "Non-regular journal entry" }
                when {
                    name == "journal.lock" -> checkJournal(Files.size(entry) == 0L) { "Invalid lock file" }
                    name == "record.tmp" -> temporary = entry
                    RECORD_PATTERN.matches(name) -> records.add(entry)
                    else -> throw ActivationJournalException("Unknown journal entry")
                }
            }
        }
        records.sortBy { it.fileName.toString() }
        for (path in records) {
            checkJournal(path.fileName.toString() == recordName(sequence + 1)) { "Journal sequence gap" }
            val bytes = boundedRead(path, MAX_RECORD_BYTES)
            checkJournal(bytes.size > HASH_BYTES) { "Truncated journal record" }
            val payload = bytes.copyOf(bytes.size - HASH_BYTES)
            val digest = payload.sha256Hex()
            checkJournal(bytes.copyOfRange(payload.size, bytes.size).toHex() == digest) { "Record checksum mismatch" }
            try {
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    checkJournal(input.readInt() == MAGIC && input.readInt() == FORMAT) { "Unknown journal format" }
                    checkJournal(input.readLong() == sequence + 1 && input.readUTF() == chain) { "Broken record chain" }
                    val operation = enumValue<Operation>(input.readUTF())
                    val state = readState(input)
                    checkJournal(input.available() == 0) { "Trailing record bytes" }
                    validateTransition(operation, state)
                    states[state.packageId] = state
                }
            } catch (error: ActivationJournalException) {
                throw error
            } catch (error: Exception) {
                throw ActivationJournalException("Malformed journal record", error)
            }
            sequence++
            chain = digest
        }
        // A temp file was never committed, even if fully checksummed. Never use it as active state.
        temporary?.let {
            checkJournal(Files.size(it) <= MAX_RECORD_BYTES) { "Oversized incomplete record" }
            Files.delete(it)
            syncDirectory()
        }
    }

    private fun boundedRead(path: Path, limit: Int): ByteArray {
        checkJournal(Files.size(path) in 1..limit.toLong()) { "Journal record size invalid" }
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                checkJournal(read <= limit - output.size()) { "Journal record exceeds limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun encode(number: Long, previousHash: String, operation: Operation, state: PackageActivation): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT)
            output.writeLong(number)
            output.writeUTF(previousHash)
            output.writeUTF(operation.name)
            output.writeUTF(state.packageId)
            output.writeLong(state.revision)
            output.writeLong(state.generation)
            writeIdentity(output, state.publisher)
            writeStage(output, state.active)
            writeStage(output, state.previous)
            output.writeBoolean(state.pending != null)
            state.pending?.let {
                writeStage(output, it.stage)
                output.writeLong(it.generation)
                output.writeUTF(it.token)
                output.writeUTF(it.status.name)
            }
            output.writeBoolean(state.disabled)
            output.writeUTF(state.failure?.name ?: "")
        }
        return bytes.toByteArray()
    }

    private fun readState(input: DataInputStream): PackageActivation {
        val id = input.readUTF()
        val revision = input.readLong()
        val generation = input.readLong()
        val publisher = readIdentity(input)
        val active = readStage(input)
        val previous = readStage(input)
        val pending = if (readBoolean(input)) {
            ActivationAttempt(readStage(input) ?: throw ActivationJournalException("Missing pending stage"),
                input.readLong(), input.readUTF(), enumValue(input.readUTF()))
        } else null
        val disabled = readBoolean(input)
        val failure = input.readUTF().let { if (it.isEmpty()) null else enumValue<ActivationFailureCode>(it) }
        return PackageActivation(id, revision, generation, publisher, active, previous, pending, disabled, failure)
    }

    private fun writeStage(output: DataOutputStream, stage: ActivationStageRef?) {
        output.writeBoolean(stage != null)
        if (stage == null) return
        output.writeUTF(stage.stageId)
        output.writeUTF(stage.packageId)
        output.writeUTF(stage.version)
        output.writeUTF(stage.runtime)
        output.writeUTF(stage.manifestSha256)
        writeIdentity(output, stage.identity)
    }

    private fun readStage(input: DataInputStream): ActivationStageRef? = if (readBoolean(input)) {
        ActivationStageRef(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), readIdentity(input))
    } else null

    private fun writeIdentity(output: DataOutputStream, identity: PublisherIdentity) {
        when (identity) {
            is PublisherIdentity.Unverified -> { output.writeUTF("unverified"); output.writeUTF(identity.claimedPublisher) }
            is PublisherIdentity.PinnedSignature -> {
                output.writeUTF("pinned"); output.writeUTF(identity.publisher); output.writeUTF(identity.keyId)
            }
        }
    }

    private fun readIdentity(input: DataInputStream): PublisherIdentity = when (input.readUTF()) {
        "unverified" -> PublisherIdentity.Unverified(input.readUTF())
        "pinned" -> PublisherIdentity.PinnedSignature(input.readUTF(), input.readUTF())
        else -> throw ActivationJournalException("Invalid publisher identity kind")
    }

    private fun readBoolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw ActivationJournalException("Invalid boolean")
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw ActivationJournalException("Unknown record code")

    private fun syncDirectory() { FileChannel.open(root, READ).use { it.force(true) } }

    private fun recordName(number: Long): String = number.toString().padStart(10, '0') + ".act"

    private fun hexBytes(value: String): ByteArray = ByteArray(HASH_BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun validPackageId(value: String) {
        checkJournal(value.length <= 200 && ID_PATTERN.matches(value)) { "Invalid package or publisher ID" }
    }

    private fun increment(value: Long): Long {
        checkJournal(value < Long.MAX_VALUE) { "Activation counter exhausted" }
        return value + 1
    }

    private fun checkJournal(condition: Boolean, message: () -> String) {
        if (!condition) throw ActivationJournalException(message())
    }

    private enum class Operation { BEGIN, CONFIRM, FAIL, DISABLE, ROLLBACK, RECOVER }

    private companion object {
        const val MAGIC = 0x4143544a
        const val FORMAT = 1
        const val HASH_BYTES = 32
        const val MAX_RECORD_BYTES = 16 * 1024
        const val MAX_RECORDS = 10_000L
        const val MAX_PACKAGES = 1024
        val ZERO_HASH = "0".repeat(64)
        val ID_PATTERN = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")
        val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
        val RUNTIME_PATTERN = Regex("[a-z][a-z0-9.-]{0,63}")
        val KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val RECORD_PATTERN = Regex("[0-9]{10}\\.act")
        val CALLER_FAILURES = setOf(ActivationFailureCode.START_FAILED, ActivationFailureCode.HEALTH_CHECK_FAILED,
            ActivationFailureCode.CANCELLED)
    }
}
