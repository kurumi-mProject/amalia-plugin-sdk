package org.amalia.platform.protocol.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.JsonElement

/** Commands share tool validation/authorization, rather than introducing a second execution path. */
class Commands internal constructor(private val tools: Tools) {
    fun register(name: String, description: String, block: ToolBuilder.() -> Unit) = tools.register(name, description, block)
}

data class PluginEvent(val topic: String, val payload: JsonElement)
/** Activation-local events only; cross-plugin events use the authorized protocol subscriptions. */
class PluginEvents internal constructor(private val active: () -> Unit) {
    private val bus = MutableSharedFlow<PluginEvent>(extraBufferCapacity = 32)
    fun subscribe(topic: String): Flow<PluginEvent> { active(); require(topic.isNotBlank()); return bus.filter { it.topic == topic } }
    suspend fun emit(topic: String, payload: JsonElement) {
        active(); require(topic.isNotBlank() && topic.length <= 128)
        require(payload.toString().length <= 65536)
        bus.emit(PluginEvent(topic, payload))
        active()
    }
}
