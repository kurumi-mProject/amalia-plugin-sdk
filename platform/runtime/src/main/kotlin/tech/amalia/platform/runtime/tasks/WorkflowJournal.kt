package tech.amalia.platform.runtime.tasks

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.amalia.platform.runtime.tasks.WorkflowStoreException.Code

@Serializable
internal data class JournalEntry(val format: Int = 1, val sequence: Long, val previousHash: String, val task: TaskRecord)

@Serializable
internal data class JournalSnapshot(val format: Int = 1, val sequence: Long, val journalHash: String, val tasks: List<TaskRecord>)

@Serializable
private data class CheckedDocument(val payload: String, val sha256: String)

/** Append-only, one atomically published file per transaction. Snapshots never replace history. */
internal class WorkflowJournal(private val directory: File, private val limits: WorkflowStoreLimits) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private var sequence = 0L
    private var hash = ""
    private var journalBytes = 0L
    private var poisoned = false

    fun replay(accept: (JournalEntry) -> Unit) {
        io {
            if (!directory.isDirectory) throw WorkflowStoreException(Code.IO_FAILURE)
            val files = directory.listFiles() ?: throw WorkflowStoreException(Code.IO_FAILURE)
            val entries = files.filter { it.name.endsWith(".entry") }.sortedBy { it.name }
            if (entries.size > limits.maxJournalEntries) throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
            for (file in entries) {
                val size = file.length()
                if (size > limits.maxRecordBytes.toLong() * 8 || journalBytes > limits.maxJournalBytes - size) {
                    throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
                }
                val payload = unpack(readBounded(file, limits.maxRecordBytes.toLong() * 8))
                val entry = decode<JournalEntry>(payload)
                if (entry.format != 1 || entry.sequence != sequence + 1 ||
                    file.name != entryName(entry.sequence) || entry.previousHash != hash
                ) throw WorkflowStoreException(Code.CORRUPT_STORE)
                accept(entry)
                sequence = entry.sequence
                hash = digest(payload)
                journalBytes += size
            }
            // A bad snapshot is disposable because all committed journal entries are retained.
            val snapshot = File(directory, "snapshot.json")
            if (snapshot.exists() && snapshot.length() <= limits.maxStoreBytes * 8) {
                try {
                    val saved = decode<JournalSnapshot>(unpack(readBounded(snapshot, limits.maxStoreBytes * 8)))
                    if (saved.format != 1 || saved.sequence > sequence) {
                        throw WorkflowStoreException(Code.CORRUPT_STORE)
                    }
                } catch (_: WorkflowStoreException) {
                    // Replay above remains authoritative; never discard or skip a journal entry.
                }
            }
            // A crash may have followed rename but preceded directory fsync. Fence the recovered namespace.
            syncDirectory()
        }
    }

    fun append(task: TaskRecord) {
        ensureHealthy()
        if (sequence >= limits.maxJournalEntries || sequence == Long.MAX_VALUE) {
            throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
        }
        val payload = json.encodeToString(JournalEntry(sequence = sequence + 1, previousHash = hash, task = task))
        val bytes = pack(payload)
        if (bytes.size > limits.maxRecordBytes.toLong() * 8 || journalBytes > limits.maxJournalBytes - bytes.size) {
            throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
        }
        publish(File(directory, entryName(sequence + 1)), bytes)
        sequence++
        hash = digest(payload)
        journalBytes += bytes.size
    }

    fun snapshot(tasks: List<TaskRecord>) {
        ensureHealthy()
        val payload = json.encodeToString(JournalSnapshot(sequence = sequence, journalHash = hash, tasks = tasks))
        val bytes = pack(payload)
        if (bytes.size > limits.maxStoreBytes * 8) throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
        publish(File(directory, "snapshot.json"), bytes)
    }

    private fun ensureHealthy() {
        if (poisoned) throw WorkflowStoreException(Code.IO_FAILURE)
    }

    private fun publish(target: File, bytes: ByteArray) {
        // Any failed write has an ambiguous commit outcome: require close/reopen before further use.
        try {
            val temp = File.createTempFile("pending-", ".tmp", directory)
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } catch (_: Exception) {
            poisoned = true
            throw WorkflowStoreException(Code.IO_FAILURE)
        }
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun pack(payload: String): ByteArray =
        json.encodeToString(CheckedDocument(payload, digest(payload))).toByteArray(Charsets.UTF_8)

    private fun unpack(bytes: ByteArray): String {
        val document = decode<CheckedDocument>(bytes.toString(Charsets.UTF_8))
        if (digest(document.payload) != document.sha256) throw WorkflowStoreException(Code.CORRUPT_STORE)
        return document.payload
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        throw WorkflowStoreException(Code.CORRUPT_STORE)
    }

    private fun readBounded(file: File, maximum: Long): ByteArray {
        val size = file.length()
        if (size < 0 || size > maximum || size > Int.MAX_VALUE) throw WorkflowStoreException(Code.LIMIT_EXCEEDED)
        return FileInputStream(file).use { input ->
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw WorkflowStoreException(Code.CORRUPT_STORE)
                offset += count
            }
            if (input.read() != -1) throw WorkflowStoreException(Code.CORRUPT_STORE)
            bytes
        }
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: WorkflowStoreException) {
        throw e
    } catch (_: IOException) {
        throw WorkflowStoreException(Code.IO_FAILURE)
    } catch (_: SecurityException) {
        throw WorkflowStoreException(Code.IO_FAILURE)
    }

    private fun entryName(value: Long): String = value.toString().padStart(20, '0') + ".entry"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
