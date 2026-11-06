package tech.amalia.platform.runtime.packages

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import org.amalia.platform.runtime.SelectionProfile

/** One host owner, private trusted directory, atomic rename + file/directory fsync required. */
internal class PackageStateStorage(private val root: Path) {
    private var poisoned = false
    init { require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Existing private state directory required" } }
    fun read(name: String): ByteArray? {
        check(!poisoned) { "Reopen state storage after IO failure" }
        val path = root.resolve(name)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= MAX_BYTES) { "Invalid state image" }
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= MAX_BYTES - output.size()) { "State image limit exceeded" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            check(bytes.size >= 64) { "Truncated state image" }
            val payload = bytes.copyOf(bytes.size - 64)
            check(String(bytes, payload.size, 64, Charsets.US_ASCII) == payload.sha256Hex()) { "State checksum mismatch" }
            payload
        }
    }
    fun write(name: String, payload: ByteArray) {
        check(!poisoned && payload.size <= MAX_BYTES - 64) { "State storage unavailable or full" }
        val temporary = root.resolve("$name.tmp")
        val bytes = payload + payload.sha256Hex().toByteArray(Charsets.US_ASCII)
        try {
            // Only our uncommitted scratch file is replaceable; all ancestors remain host-private.
            FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            Files.move(temporary, root.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING)
            FileChannel.open(root, READ).use { it.force(true) }
        } catch (error: Exception) { poisoned = true; throw IOException("State publication uncertain; reopen and recover", error) }
    }
    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}

/** Persistent explicit bindings. CAS revision is also captured by installation reviews. */
class PackageProfileStore(root: Path) {
    private val storage = PackageStateStorage(root)
    @Synchronized fun read(): PackageSelectionProfile {
        val bytes = storage.read("selection.profile") ?: return PackageSelectionProfile(0, SelectionProfile())
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1) { "Unknown profile format" }
            val revision = input.readLong(); check(revision > 0)
            val count = input.readInt(); check(count in 0..1024)
            val bindings = linkedMapOf<String, String>()
            repeat(count) {
                val key = input.readUTF(); val value = input.readUTF()
                validStateKey(key); validStateKey(value)
                check(bindings.put(key, value) == null) { "Duplicate profile binding" }
            }
            check(input.available() == 0)
            PackageSelectionProfile(revision, SelectionProfile(java.util.Collections.unmodifiableMap(bindings)))
        }
    }
    @Synchronized fun choose(profile: SelectionProfile, expectedRevision: Long): PackageSelectionProfile {
        val current = read()
        check(current.revision == expectedRevision) { "Profile revision conflict" }
        check(current.revision < Long.MAX_VALUE && profile.bindings.size <= 1024)
        profile.bindings.forEach { (key, value) -> validStateKey(key); validStateKey(value) }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1); output.writeLong(current.revision + 1); output.writeInt(profile.bindings.size)
            profile.bindings.toSortedMap().forEach { (key, value) -> output.writeUTF(key); output.writeUTF(value) }
        }
        storage.write("selection.profile", bytes.toByteArray())
        return read()
    }
}
data class PackageSelectionProfile(val revision: Long, val profile: SelectionProfile)
internal fun validStateKey(key: String) {
    require(Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,199}").matches(key)) { "Invalid namespace key" }
}
