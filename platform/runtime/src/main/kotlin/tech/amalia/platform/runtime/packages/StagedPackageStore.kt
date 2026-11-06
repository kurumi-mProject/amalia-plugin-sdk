package tech.amalia.platform.runtime.packages

import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/** A staging result is neither an installed package nor an execution authorization. */
data class StagedPackage internal constructor(
    val stageId: String,
    val directory: Path,
    val contentDirectory: Path,
    val verification: VerifiedPackage,
)

/**
 * Root must be app-private, on a filesystem supporting atomic directory rename, and not writable by
 * packages or other principals. Other code must not mutate completed stages. No active state exists.
 */
class StagedPackageStore(root: Path, private val verifier: PackageArchiveVerifier = PackageArchiveVerifier()) {
    private val root = root.toAbsolutePath().normalize()
    private val transactions: Path
    private val staged: Path

    init {
        Files.createDirectories(this.root)
        require(Files.isDirectory(this.root, NOFOLLOW_LINKS)) { "Store root must be a real directory" }
        transactions = directory(this.root.resolve("transactions"))
        staged = directory(this.root.resolve("staged"))
    }

    /** Does not close the caller's stream. Every call creates an independent stage, never overwrites. */
    fun stage(input: InputStream): StagedPackage {
        val id = UUID.randomUUID().toString()
        val transaction = Files.createDirectory(transactions.resolve(id))
        try {
            val archive = transaction.resolve("archive.zip")
            Files.newOutputStream(archive, CREATE_NEW).use { output ->
                val buffer = ByteArray(16 * 1024)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (read.toLong() > verifier.limits.maxArchiveBytes - count) invalid("Archive stream exceeds limit")
                    count += read
                    output.write(buffer, 0, read)
                }
            }
            val content = Files.createDirectory(transaction.resolve("content"))
            val verification = verifier.extract(archive, content)
            val destination = staged.resolve(id)
            // Deliberately no non-atomic fallback and no REPLACE_EXISTING.
            Files.move(transaction, destination, ATOMIC_MOVE)
            return StagedPackage(id, destination, destination.resolve("content"), verification)
        } catch (error: Throwable) {
            try {
                deleteTree(transaction)
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    fun stage(archive: Path): StagedPackage {
        if (!Files.isRegularFile(archive, NOFOLLOW_LINKS)) invalid("Archive must be a regular file")
        return Files.newInputStream(archive).use { stage(it) }
    }

    /** Rechecks stored archive with CURRENT pins/limits. Never trust directory names as identities. */
    fun verifyStaged(stageId: String): VerifiedPackage {
        val path = stageDirectory(stageId)
        return verifier.verify(path.resolve("archive.zip"))
    }

    /** Enumerates committed stages only; metadata must be reverified with verifyStaged. */
    fun stageIds(): List<String> = Files.newDirectoryStream(staged).use { entries ->
        entries.filter { validId(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
            .map { it.fileName.toString() }.sorted()
    }

    fun stageDirectory(stageId: String): Path {
        require(validId(stageId)) { "Invalid stage ID" }
        return staged.resolve(stageId).also {
            if (!Files.isDirectory(it, NOFOLLOW_LINKS)) throw IOException("Stage does not exist")
        }
    }

    /** Explicit recovery operation: call only with exclusive access and no staging in progress. */
    fun discardIncompleteTransactions(): Int {
        var removed = 0
        Files.newDirectoryStream(transactions).use { entries ->
            for (path in entries) {
                if (validId(path.fileName.toString())) { deleteTree(path); removed++ }
            }
        }
        return removed
    }

    private fun directory(path: Path): Path {
        Files.createDirectories(path)
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Store subdirectory must be a real directory" }
        return path
    }

    private fun validId(value: String): Boolean =
        value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        // Files.walk does not follow symbolic links.
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
