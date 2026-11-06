package tech.amalia.platform.runtime.packages

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** No class loading, interpretation of declarations, dependency resolution or access grants. */
class PackageArchiveVerifier(
    val limits: PackageLimits = PackageLimits(),
    private val publishers: PinnedPublishers = PinnedPublishers(),
) {
    /** Caller must keep the archive immutable for the duration of verification. Staging snapshots it. */
    fun verify(archive: Path): VerifiedPackage = process(archive, null)

    /** Destination must be a new private directory owned by the store. */
    internal fun extract(archive: Path, destination: Path): VerifiedPackage = process(archive, destination)

    private fun process(archive: Path, destination: Path?): VerifiedPackage {
        try {
            if (!Files.isRegularFile(archive, NOFOLLOW_LINKS)) invalid("Archive must be a regular file")
            val archiveSize = Files.size(archive)
            if (archiveSize <= 0 || archiveSize > limits.maxArchiveBytes) invalid("Archive size exceeds limit")
            ZipFile(archive.toFile()).use { zip ->
                val entries = ArrayList<ZipEntry>()
                val names = HashSet<String>()
                val foldedFiles = HashSet<String>()
                val foldedParents = HashSet<String>()
                val enumeration = zip.entries()
                var declaredTotal = 0L
                while (enumeration.hasMoreElements()) {
                    if (entries.size >= limits.maxEntries) invalid("Archive entry count exceeds limit")
                    val entry = enumeration.nextElement()
                    // Explicit directory records are unnecessary: the store creates parents itself.
                    if (entry.isDirectory) invalid("Directory ZIP entries are not supported")
                    validatePackagePath(entry.name, limits)
                    if (!names.add(entry.name)) invalid("Duplicate ZIP entry")
                    val folded = entry.name.lowercase(Locale.ROOT)
                    if (!foldedFiles.add(folded) || folded in foldedParents) invalid("Conflicting ZIP paths")
                    var parent = folded.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        if (parent in foldedFiles) invalid("File/directory path collision")
                        foldedParents.add(parent)
                        parent = parent.substringBeforeLast('/', "")
                    }
                    if ((folded == "platform.json" || folded == "package.sig") && entry.name != folded) {
                        invalid("Reserved filename alias")
                    }
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) invalid("Unsupported ZIP method")
                    val cap = fileLimit(entry.name)
                    if (entry.size < 0 || entry.size > cap || entry.compressedSize < 0 ||
                        entry.compressedSize > archiveSize || entry.crc < 0) invalid("Invalid ZIP sizes or CRC")
                    if (entry.size > limits.maxTotalBytes - declaredTotal) invalid("Expanded archive exceeds limit")
                    declaredTotal += entry.size
                    val compressed = maxOf(1L, entry.compressedSize)
                    // ceil(size / ratio) avoids overflow even with host-supplied Long limits.
                    val ratio = limits.maxCompressionRatio.toLong()
                    val requiredCompressed = entry.size / ratio + if (entry.size % ratio == 0L) 0 else 1
                    if (compressed < requiredCompressed) invalid("ZIP compression ratio exceeds limit")
                    entries.add(entry)
                }
                if ("platform.json" !in names) invalid("Missing platform.json")
                var total = 0L
                var manifestBytes: ByteArray? = null
                var signatureBytes: ByteArray? = null
                val hashes = LinkedHashMap<String, String>()
                for (entry in entries) {
                    val reserved = entry.name == "platform.json" || entry.name == "package.sig"
                    val bytes = if (reserved) ByteArrayOutputStream() else null
                    val hash = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var count = 0L
                    val target = destination?.resolve(entry.name)
                    if (target != null) Files.createDirectories(target.parent)
                    val output = target?.let { Files.newOutputStream(it, CREATE_NEW) }
                    try {
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                if (read.toLong() > fileLimit(entry.name) - count ||
                                    read.toLong() > limits.maxTotalBytes - total) invalid("Streamed ZIP bytes exceed limit")
                                count += read
                                total += read
                                hash.update(buffer, 0, read)
                                crc.update(buffer, 0, read)
                                bytes?.write(buffer, 0, read)
                                output?.write(buffer, 0, read)
                            }
                        }
                    } finally {
                        output?.close()
                    }
                    if (count != entry.size || crc.value != entry.crc) invalid("ZIP size or CRC mismatch")
                    when (entry.name) {
                        "platform.json" -> manifestBytes = bytes?.toByteArray()
                        "package.sig" -> signatureBytes = bytes?.toByteArray()
                        else -> hashes[entry.name] = hash.digest().toHex()
                    }
                }
                val raw = manifestBytes ?: invalid("Missing manifest bytes")
                val manifest = PackageMetadata.manifest(raw, limits)
                if (manifest.sha256 != hashes) invalid("Inventory must exactly cover every payload file and digest")
                val identity = signatureBytes?.let { publishers.verify(manifest.publisher, raw, it) }
                    ?: PublisherIdentity.Unverified(manifest.publisher)
                if (!manifest.isDeclarationOnly && identity !is PublisherIdentity.PinnedSignature) {
                    invalid("Code packages require a pinned publisher signature")
                }
                return VerifiedPackage(manifest, identity, raw.sha256Hex(), entries.size, total)
            }
        } catch (error: PackageVerificationException) {
            throw error
        } catch (error: IOException) {
            throw PackageVerificationException("Cannot read package archive", error)
        } catch (error: IllegalArgumentException) {
            throw PackageVerificationException("Malformed package archive", error)
        }
    }

    private fun fileLimit(name: String): Long = minOf(limits.maxFileBytes, when (name) {
        "platform.json" -> limits.maxManifestBytes.toLong()
        "package.sig" -> limits.maxSignatureBytes.toLong()
        else -> limits.maxFileBytes
    })
}
