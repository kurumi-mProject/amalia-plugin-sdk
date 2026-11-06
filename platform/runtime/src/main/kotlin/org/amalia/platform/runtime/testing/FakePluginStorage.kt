package org.amalia.platform.runtime.testing

import org.amalia.platform.protocol.PluginStorage
import org.amalia.platform.protocol.StorageNamespace

/** Bounded in-memory package storage. A host should allocate one instance per package. */
class FakePluginStorage(
    private val maxEntries: Int = 256,
    private val maxKeyBytes: Int = 256,
    private val maxValueBytes: Int = 64 * 1024,
    private val maxTotalBytes: Int = 1024 * 1024,
) : PluginStorage {
    init { require(maxEntries > 0 && maxKeyBytes > 0 && maxValueBytes > 0 && maxTotalBytes > 0) }
    private val lock = Any()
    private val values = mutableMapOf<Pair<String, String>, String>()
    private fun bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size
    private fun key(key: String) { require(key.isNotBlank() && bytes(key) <= maxKeyBytes) { "Invalid storage key" } }
    private fun namespace(name: String) = object : StorageNamespace {
        override fun read(key: String): String? = synchronized(lock) { key(key); values[name to key] }
        override fun write(key: String, value: String) = synchronized(lock) {
            key(key)
            require(bytes(value) <= maxValueBytes) { "Storage value exceeds limit" }
            val id = name to key
            require(id in values || values.size < maxEntries) { "Storage entry limit exceeded" }
            val total = values.entries.sumOf { bytes(it.key.second).toLong() + bytes(it.value) }
            val previous = values[id]?.let { bytes(key).toLong() + bytes(it) } ?: 0L
            require(total - previous + bytes(key) + bytes(value) <= maxTotalBytes) { "Storage quota exceeded" }
            values[id] = value
        }
        override fun delete(key: String) { synchronized(lock) { key(key); values.remove(name to key) } }
    }
    override val data = namespace("data")
    override val settings = namespace("settings")
}
