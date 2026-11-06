package org.example.sdk3.smarthome

import amalia.generated.GeneratedManifest
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.OperationEffect
import org.amalia.platform.protocol.Idempotency
import org.amalia.platform.protocol.sdk.*

class Plugin : AmaliaPlugin() {
    override val manifest = GeneratedManifest.value
    private fun text(value: String, limit: Int = 4000): String {
        require(value.isNotBlank() && value.length <= limit) { "Text is empty or too long" }
        return value
    }
    override suspend fun install(api: AmaliaApi) {

        api.tools.register("light", "Prepare a room lighting intent; does not control hardware") {
            string("room", "Room name")
            string("brightness", "Integer brightness from 0 through 100")
            idempotency = Idempotency.INHERENT
            execute {
                val brightness = string("brightness").toInt()
                require(brightness in 0..100)
                val intent = buildJsonObject {
                    put("type", "smarthome.light")
                    put("room", text(string("room"), 80))
                    put("brightness", brightness)
                    put("applied", false)
                    put("requires", "Authorized smart-home device adapter and user confirmation")
                }
                api.storage.putString("smarthome.scene", intent.toString())
                intent
            }
        }
        api.tools.register("scene", "Read the prepared lighting intent") {
            effect = OperationEffect.READ
            execute { api.storage.getString("smarthome.scene")?.let { Json.parseToJsonElement(it) } ?: JsonNull }
        }
    }
}
