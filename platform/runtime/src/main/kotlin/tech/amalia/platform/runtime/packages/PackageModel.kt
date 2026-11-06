package tech.amalia.platform.runtime.packages

import java.io.IOException
import java.util.Collections

/** Limits apply to both metadata and actual streamed bytes, including reserved files. */
data class PackageLimits(
    val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 2048,
    val maxFileBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
    val maxManifestBytes: Int = 256 * 1024,
    val maxSignatureBytes: Int = 4096,
    val maxCompressionRatio: Int = 200,
    val maxPathLength: Int = 240,
    val maxPathDepth: Int = 16,
) {
    init {
        require(maxArchiveBytes > 0 && maxEntries > 0 && maxFileBytes > 0 && maxTotalBytes > 0)
        require(maxManifestBytes > 0 && maxSignatureBytes > 0 && maxCompressionRatio > 0)
        require(maxPathLength > 0 && maxPathDepth > 0)
    }
}

class PackageVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A claimed publisher is never itself evidence of identity. */
sealed interface PublisherIdentity {
    data class Unverified(val claimedPublisher: String) : PublisherIdentity
    data class PinnedSignature(val publisher: String, val keyId: String) : PublisherIdentity
}

class PackageManifest internal constructor(
    val packageId: String,
    val version: String,
    val publisher: String,
    val runtime: String,
    val entryPoint: String?,
    sha256: Map<String, String>,
    val compatibility: PackageCompatibility? = null,
    val dependencies: List<org.amalia.platform.runtime.ContractRequirement> = emptyList(),
    val offers: List<PackageOffer> = emptyList(),
    val conflicts: Set<String> = emptySet(),
    val data: PackageDataDeclaration? = null,
    capabilities: Set<String> = emptySet(),
) {
    val capabilities: Set<String> = Collections.unmodifiableSet(capabilities.toSet())
    val sha256: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(sha256))
    val isDeclarationOnly: Boolean get() = runtime == "declarations"
}

/** Describes bytes just verified; it does not authorize later execution or replacement. */
data class VerifiedPackage internal constructor(
    val manifest: PackageManifest,
    val identity: PublisherIdentity,
    val manifestSha256: String,
    val fileCount: Int,
    val expandedBytes: Long,
)

internal fun invalid(message: String): Nothing = throw PackageVerificationException(message)
internal fun ByteArray.sha256Hex(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).toHex()
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun validatePackagePath(path: String, limits: PackageLimits) {
    // Portable ASCII paths avoid Unicode normalization, case and platform separator aliases.
    if (path.isEmpty() || path.length > limits.maxPathLength || path.startsWith('/') ||
        !path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-/" }
    ) invalid("Invalid package path")
    val parts = path.split('/')
    if (parts.size > limits.maxPathDepth || parts.any {
            it.isEmpty() || it == "." || it == ".." || it.endsWith('.') ||
                it.substringBefore('.').uppercase(java.util.Locale.ROOT) in WINDOWS_RESERVED
        }) invalid("Unsafe package path: $path")
}

private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
    (1..9).flatMap { listOf("COM$it", "LPT$it") }
