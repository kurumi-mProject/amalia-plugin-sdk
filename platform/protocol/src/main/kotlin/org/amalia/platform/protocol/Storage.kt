package org.amalia.platform.protocol

/** Host-bound private package namespaces, shared across activations, never arbitrary files or paths.
 * Every operation rechecks owner, generation and host policy. Calls are synchronous bounded IO;
 * use a worker dispatcher. Hosts may impose key/value and total payload limits.
 */
interface PluginStorage {
    val data: StorageNamespace
    val settings: StorageNamespace
}

/** No package selector, enumeration, migration, snapshot or other administrative access. */
interface StorageNamespace {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}

/** Compatibility default for contexts/hosts that do not provide package storage. */
object DeniedPluginStorage : PluginStorage {
    private val denied = object : StorageNamespace {
        override fun read(key: String): String? = throw SecurityException("Package storage is not configured")
        override fun write(key: String, value: String): Unit = throw SecurityException("Package storage is not configured")
        override fun delete(key: String): Unit = throw SecurityException("Package storage is not configured")
    }
    override val data: StorageNamespace get() = denied
    override val settings: StorageNamespace get() = denied
}
