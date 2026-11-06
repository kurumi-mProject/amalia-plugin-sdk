package org.amalia.platform.runtime.testing

import org.amalia.platform.protocol.Registration
import org.amalia.platform.protocol.sdk.PluginUiAccess
import org.amalia.platform.protocol.ui.UiDocument

/** Records declarative documents; no renderer or Android navigation is simulated. */
class FakePluginUi(private val packageId: String, private val approved: () -> Boolean = { false }) : PluginUiAccess, AutoCloseable {
    private val records = linkedMapOf<String, UiDocument>()
    private var closed = false
    val screens: Map<String, UiDocument> get() = synchronized(this) { records.toMap() }
    @Synchronized override fun registerScreen(route: String, title: String, document: UiDocument): Registration {
        check(!closed); if (!approved()) throw SecurityException("UI not granted by fake host")
        require(route == document.route && route.startsWith("$packageId.") && title.isNotBlank())
        require(route !in records && records.size < 16)
        records[route] = document
        return object : Registration { override fun close() { synchronized(this@FakePluginUi) { records.remove(route) } } }
    }
    @Synchronized override fun close() { closed = true; records.clear() }
}
