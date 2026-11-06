package org.amalia.platform.runtime.resources

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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.amalia.platform.protocol.CallerIdentity
import org.amalia.platform.protocol.ResourceAccess
import org.amalia.platform.protocol.ResourceOwner
import org.amalia.platform.protocol.ResourceRef
import org.amalia.platform.protocol.ResourceRight
import org.amalia.platform.protocol.ResourceWriteRequest

/** Quota counts file lengths, including staging and metadata, rather than filesystem block usage. */
data class ResourceStoreLimits(
    val maxResourceBytes: Long = 32L * 1024 * 1024,
    val maxDiskBytes: Long = 256L * 1024 * 1024,
    val chunkBytes: Int = 64 * 1024,
    val maxResources: Int = 4096,
    val maxGrants: Int = 4096,
) {
    init {
        require(maxResourceBytes > 0 && maxDiskBytes >= METADATA_LIMIT)
        require(chunkBytes in 1..1024 * 1024)
        require(maxResources in 1..100_000 && maxGrants in 1..100_000)
    }
}

private const val METADATA_LIMIT = 16 * 1024
private const val TEXT_LIMIT = 2048
private val OWNER_RIGHTS = setOf(ResourceRight.READ, ResourceRight.DELETE)
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val HASH_PATTERN = Regex("[0-9a-f]{64}")

/** Stable public failures never include paths, identities, metadata, or underlying exception causes. */
class ResourceStoreException internal constructor(message: String) : IOException(message)

/**
 * Trusted host only. Root and every ancestor must be app-private and must not be mutated externally.
 * No filesystem API can provide a sandbox against code running with the host's OS identity.
 * Use [accessFor] to give plugins only a caller-bound ResourceAccess, never this store.
 */
class FileResourceStore(
    root: Path,
    private val limits: ResourceStoreLimits = ResourceStoreLimits(),
) : AutoCloseable {
    private val root = root.toAbsolutePath().normalize()
    private val monitor = Any()
    private lateinit var objects: Path
    private lateinit var staging: Path
    private lateinit var trash: Path
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var closed = false
    private var writers = 0
    private var entries = 0
    private var usedBytes = 0L
    private val revoked = mutableSetOf<String>()
    private val grants = mutableMapOf<String, Grant>()

    private data class Grant(
        val caller: CallerIdentity,
        val resourceId: String,
        val rights: Set<ResourceRight>,
        val expiresAt: Long,
    )

    private data class Metadata(
        val id: String,
        val owner: ResourceOwner,
        val mime: String,
        val size: Long,
        val hash: String,
    ) {
        fun reference() = ResourceRef(id, owner, mime, hash, OWNER_RIGHTS.toSet(), size)
    }

    init {
        safe {
            try {
                prepareRoot()
                val lockPath = this.root.resolve("store.lock")
                if (Files.exists(lockPath, NOFOLLOW_LINKS)) regular(lockPath)
                val channel = FileChannel.open(lockPath, CREATE_NEW_OR_EXISTING, WRITE, NOFOLLOW_LINKS)
                lockChannel = channel
                fileLock = channel.tryLock() ?: fail("Resource store is already open")
                objects = directory(this.root.resolve("objects"))
                staging = directory(this.root.resolve("staging"))
                trash = directory(this.root.resolve("trash"))
                recover()
                syncDirectory(this.root)
            } catch (error: Exception) {
                releaseLock()
                throw error
            }
        }
    }

    /** Identity must come from the trusted host, never a request body or a plugin-selected identity. */
    fun accessFor(identity: CallerIdentity): ResourceAccess = safe {
        synchronized(monitor) {
            ensureOpen()
            validateIdentity(identity)
            object : ResourceAccess {
                override fun read(resource: ResourceRef): Flow<ByteArray> = readFor(identity, resource)
                override suspend fun write(request: ResourceWriteRequest, chunks: Flow<ByteArray>): ResourceRef =
                    writeFor(identity, request, chunks)
                override suspend fun delete(resource: ResourceRef) {
                    safe { synchronized(monitor) { deleteFor(identity, resource) } }
                }
            }
        }
    }

    /** Host-only exact caller/id grant. Grants are volatile and never survive reopening the store. */
    fun grant(
        resource: ResourceRef,
        caller: CallerIdentity,
        rights: Set<ResourceRight>,
        expiresAtEpochMillis: Long,
    ): String = safe {
        synchronized(monitor) {
            ensureOpen()
            validateIdentity(caller)
            canonical(resource)
            if (rights.isEmpty() || !OWNER_RIGHTS.containsAll(rights) || expiresAtEpochMillis <= System.currentTimeMillis()) {
                fail("Invalid resource grant")
            }
            pruneGrants()
            if (grants.size >= limits.maxGrants) fail("Resource grant limit exceeded")
            val token = UUID.randomUUID().toString()
            grants[token] = Grant(caller, resource.id, rights.toSet(), expiresAtEpochMillis)
            token
        }
    }

    fun revokeGrant(grantId: String) = safe {
        synchronized(monitor) { ensureOpen(); grants.remove(grantId); Unit }
    }

    /** Stops new operations immediately. An in-flight writer retains the OS lock until its cleanup. */
    override fun close() = safe {
        synchronized(monitor) {
            closed = true
            grants.clear()
            if (writers == 0) releaseLock()
        }
    }

    private fun readFor(identity: CallerIdentity, supplied: ResourceRef): Flow<ByteArray> = flow {
        try {
            val metadata = synchronized(monitor) {
                ensureOpen()
                val metadata = authorized(identity, supplied, ResourceRight.READ)
                // Verify the complete bounded payload before emitting any bytes.
                verifyPayload(metadata)
                metadata
            }
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = synchronized(monitor) {
                    ensureOpen()
                    val current = authorized(identity, supplied, ResourceRight.READ)
                    if (current != metadata) fail("Resource unavailable")
                    if (offset == metadata.size) null else {
                        val data = objects.resolve(metadata.id).resolve("data")
                        regular(data)
                        FileChannel.open(data, READ, NOFOLLOW_LINKS).use { channel ->
                            if (channel.size() != metadata.size) fail("Resource unavailable")
                            channel.position(offset)
                            val bytes = ByteArray(minOf(limits.chunkBytes.toLong(), metadata.size - offset).toInt())
                            readFully(channel, ByteBuffer.wrap(bytes))
                            bytes
                        }
                    }
                } ?: break
                emit(chunk)
                offset += chunk.size
            }
        } catch (error: Exception) {
            sanitized(error)
        }
    }

    private suspend fun writeFor(
        identity: CallerIdentity,
        request: ResourceWriteRequest,
        chunks: Flow<ByteArray>,
    ): ResourceRef {
        val expectedSizeBytes = request.expectedSizeBytes
        var transaction: Path? = null
        var reservation = 0L
        var counted = false
        var committed = false
        try {
            val id = UUID.randomUUID().toString()
            synchronized(monitor) {
                ensureOpen()
                validateText(request.mimeType)
                if (expectedSizeBytes != null && expectedSizeBytes > limits.maxResourceBytes) {
                    fail("Resource size limit exceeded")
                }
                if (entries >= limits.maxResources) fail("Resource count limit exceeded")
                reserve(METADATA_LIMIT.toLong())
                reservation = METADATA_LIMIT.toLong()
                writers++
                entries++
                counted = true
                transaction = Files.createDirectory(staging.resolve(id))
            }
            val stage = transaction ?: fail("Resource operation failed")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileChannel.open(stage.resolve("data"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                chunks.collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    synchronized(monitor) {
                        ensureOpen()
                        if (chunk.size > limits.chunkBytes || chunk.size.toLong() > limits.maxResourceBytes - size) {
                            fail("Resource size limit exceeded")
                        }
                        if (expectedSizeBytes != null && chunk.size.toLong() > expectedSizeBytes - size) {
                            fail("Resource size mismatch")
                        }
                        reserve(chunk.size.toLong())
                        reservation += chunk.size
                        val buffer = ByteBuffer.wrap(chunk)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        digest.update(chunk)
                        size += chunk.size
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(monitor) { ensureOpen(); channel.force(true) }
            }
            if (expectedSizeBytes != null && size != expectedSizeBytes) fail("Resource size mismatch")
            val owner = ResourceOwner(identity.publisherId, identity.packageId, "resource-store", identity.deviceId)
            val metadata = Metadata(id, owner, request.mimeType, size, hex(digest.digest()))
            val encoded = encode(metadata)
            return synchronized(monitor) {
                ensureOpen()
                FileChannel.open(stage.resolve("metadata"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(encoded)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                syncDirectory(stage)
                val destination = objects.resolve(id)
                Files.move(stage, destination, ATOMIC_MOVE)
                // Retain this path for fail-closed cleanup if directory fsync fails.
                transaction = destination
                syncDirectory(objects)
                syncDirectory(staging)
                usedBytes -= METADATA_LIMIT - encoded.size
                reservation = size + encoded.size
                committed = true
                metadata.reference()
            }
        } catch (error: Exception) {
            sanitized(error)
        } finally {
            synchronized(monitor) {
                if (counted) {
                    try {
                        if (!committed) {
                            transaction?.let { removeFlat(it) }
                            usedBytes -= reservation
                            entries--
                        }
                    } catch (_: Exception) {
                        // Retain conservative quota and deny all further work until reopen/recovery.
                        closed = true
                    } finally {
                        writers--
                        if (closed && writers == 0) releaseLock()
                    }
                }
            }
        }
    }

    private fun deleteFor(identity: CallerIdentity, supplied: ResourceRef) {
        ensureOpen()
        val metadata = authorized(identity, supplied, ResourceRight.DELETE)
        val directory = objects.resolve(metadata.id)
        revoked.add(metadata.id)
        grants.entries.removeAll { it.value.resourceId == metadata.id }
        // Mark before rename: a crash or rename failure must never restore read authority.
        FileChannel.open(directory.resolve("deleted"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        syncDirectory(directory)
        val destination = trash.resolve(metadata.id)
        Files.move(directory, destination, ATOMIC_MOVE)
        syncDirectory(objects)
        syncDirectory(trash)
        val bytes = flatSize(destination)
        removeFlat(destination)
        syncDirectory(trash)
        usedBytes -= bytes
        entries--
        revoked.remove(metadata.id)
    }

    private fun authorized(identity: CallerIdentity, supplied: ResourceRef, right: ResourceRight): Metadata {
        val metadata = canonical(supplied)
        val owner = metadata.owner
        val owns = identity.publisherId == owner.publisherId && identity.packageId == owner.packageId &&
            identity.deviceId == owner.deviceId
        pruneGrants()
        if (!owns && grants.values.none { it.caller == identity && it.resourceId == metadata.id && right in it.rights }) {
            fail("Resource access denied")
        }
        return metadata
    }

    private fun canonical(supplied: ResourceRef): Metadata {
        validateId(supplied.id)
        if (supplied.id in revoked) fail("Resource unavailable")
        val metadata = load(supplied.id)
        if (supplied != metadata.reference()) fail("Resource reference is invalid")
        return metadata
    }

    private fun load(id: String): Metadata {
        validateId(id)
        val directory = objects.resolve(id)
        realDirectory(directory)
        if (Files.exists(directory.resolve("deleted"), NOFOLLOW_LINKS)) fail("Resource unavailable")
        val path = directory.resolve("metadata")
        regular(path)
        val encoded = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 1..METADATA_LIMIT.toLong()) fail("Resource metadata is invalid")
            val bytes = ByteArray(size.toInt())
            readFully(channel, ByteBuffer.wrap(bytes))
            if (channel.read(ByteBuffer.allocate(1)) != -1) fail("Resource metadata is invalid")
            bytes
        }
        return decode(encoded).also {
            if (it.id != id || it.size > limits.maxResourceBytes) fail("Resource metadata is invalid")
            regular(directory.resolve("data"))
            if (Files.size(directory.resolve("data")) != it.size) fail("Resource unavailable")
        }
    }

    private fun verifyPayload(metadata: Metadata) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(objects.resolve(metadata.id).resolve("data"), READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != metadata.size) fail("Resource unavailable")
            val buffer = ByteBuffer.allocate(limits.chunkBytes)
            var remaining = metadata.size
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
                readFully(channel, buffer)
                digest.update(buffer.array(), 0, buffer.position())
                remaining -= buffer.position()
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) fail("Resource unavailable")
        }
        if (hex(digest.digest()) != metadata.hash) fail("Resource integrity check failed")
    }

    private fun recover() {
        for (parent in listOf(staging, trash)) {
            forEachEntry(parent) { path -> validateId(path.fileName.toString()); removeFlat(path) }
            syncDirectory(parent)
        }
        forEachEntry(objects) { path ->
            val id = path.fileName.toString()
            validateId(id)
            realDirectory(path)
            if (Files.exists(path.resolve("deleted"), NOFOLLOW_LINKS)) {
                removeFlat(path)
            } else {
                if (entries >= limits.maxResources) fail("Resource count limit exceeded")
                val metadata = load(id)
                verifyPayload(metadata)
                reserve(flatSize(path))
                entries++
            }
        }
        syncDirectory(objects)
    }

    private fun forEachEntry(parent: Path, action: (Path) -> Unit) {
        var count = 0
        Files.newDirectoryStream(parent).use { paths ->
            for (path in paths) {
                if (++count > limits.maxResources) fail("Resource count limit exceeded")
                action(path)
            }
        }
    }

    private fun flatFiles(directory: Path): List<Path> {
        realDirectory(directory)
        val result = mutableListOf<Path>()
        Files.newDirectoryStream(directory).use { files ->
            for (file in files) {
                if (result.size >= 3 || file.fileName.toString() !in setOf("data", "metadata", "deleted")) {
                    fail("Resource storage is invalid")
                }
                regular(file)
                result.add(file)
            }
        }
        return result
    }

    private fun flatSize(directory: Path): Long {
        var total = 0L
        for (path in flatFiles(directory)) {
            val size = Files.size(path)
            if (size > limits.maxDiskBytes - total) fail("Resource quota exceeded")
            total += size
        }
        return total
    }

    private fun removeFlat(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        // Validate everything before deleting anything; never traverse or follow links.
        val files = flatFiles(directory)
        for (file in files.sortedBy { if (it.fileName.toString() == "deleted") 1 else 0 }) Files.delete(file)
        Files.delete(directory)
    }

    private fun reserve(bytes: Long) {
        if (bytes < 0 || bytes > limits.maxDiskBytes - usedBytes) fail("Resource quota exceeded")
        usedBytes += bytes
    }

    private fun pruneGrants() {
        val now = System.currentTimeMillis()
        grants.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun prepareRoot() {
        // Check before creation too: createDirectories must never traverse a symlink ancestor.
        var cursor = root.root
        for (part in root) {
            cursor = cursor.resolve(part)
            if (Files.exists(cursor, NOFOLLOW_LINKS)) realDirectory(cursor) else Files.createDirectory(cursor)
        }
        realDirectory(root)
    }

    private fun directory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        realDirectory(path)
        return path
    }

    private fun realDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) fail("Resource storage is invalid")
    }

    private fun regular(path: Path) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("Resource unavailable")
    }

    private fun syncDirectory(path: Path) {
        realDirectory(path)
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun ensureOpen() { if (closed) fail("Resource store is closed") }

    private fun releaseLock() {
        try {
            fileLock?.release()
        } finally {
            fileLock = null
            try { lockChannel?.close() } finally { lockChannel = null }
        }
    }

    private fun validateIdentity(identity: CallerIdentity) {
        listOf(identity.publisherId, identity.packageId, identity.instanceId, identity.deviceId).forEach(::validateText)
    }

    private fun validateId(id: String) { if (!UUID_PATTERN.matches(id)) fail("Invalid resource ID") }

    private fun validateText(text: String) {
        if (text.isBlank() || text.length > TEXT_LIMIT || text.toByteArray(Charsets.UTF_8).size > TEXT_LIMIT) {
            fail("Invalid resource input")
        }
    }

    private fun encode(metadata: Metadata): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            for (text in listOf(metadata.id, metadata.owner.publisherId, metadata.owner.packageId,
                metadata.owner.providerId, metadata.owner.deviceId, metadata.mime, metadata.hash)) {
                validateText(text)
                val encoded = text.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            output.writeLong(metadata.size)
        }
        return bytes.toByteArray().also { if (it.size > METADATA_LIMIT) fail("Resource metadata is invalid") }
    }

    private fun decode(bytes: ByteArray): Metadata = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != 1) fail("Resource metadata is invalid")
        fun text(): String {
            val size = input.readInt()
            if (size !in 1..TEXT_LIMIT || size > input.available()) fail("Resource metadata is invalid")
            val value = ByteArray(size)
            input.readFully(value)
            val text = value.toString(Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(value)) fail("Resource metadata is invalid")
            validateText(text)
            return text
        }
        val id = text()
        validateId(id)
        val owner = ResourceOwner(text(), text(), text(), text())
        val mime = text()
        val hash = text()
        val size = input.readLong()
        if (owner.providerId != "resource-store" || !HASH_PATTERN.matches(hash) || size < 0 || input.available() != 0) {
            fail("Resource metadata is invalid")
        }
        Metadata(id, owner, mime, size, hash)
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) fail("Resource unavailable")
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fail(message: String): Nothing = throw ResourceStoreException(message)

    private fun sanitized(error: Exception): Nothing = when (error) {
        is CancellationException -> throw CancellationException("Resource operation cancelled")
        is ResourceStoreException -> throw error
        else -> fail("Resource operation failed")
    }

    private inline fun <T> safe(block: () -> T): T = try { block() } catch (error: Exception) { sanitized(error) }

    private companion object {
        val CREATE_NEW_OR_EXISTING = java.nio.file.StandardOpenOption.CREATE
    }
}
