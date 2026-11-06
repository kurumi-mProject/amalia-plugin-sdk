package org.example.sdk3.notes

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

        api.tools.register("save", "Save a named note") {
            string("name", "Note name, at most 80 characters")
            string("text", "Note text, at most 4000 characters")
            idempotency = Idempotency.INHERENT
            execute {
                val name = text(string("name"), 80)
                api.storage.putString("note.$name", text(string("text")))
                buildJsonObject { put("saved", name) }
            }
        }
        api.tools.register("read", "Read a named note") {
            string("name", "Note name")
            effect = OperationEffect.READ
            idempotency = Idempotency.INHERENT
            execute {
                val value = api.storage.getString("note." + text(string("name"), 80))
                buildJsonObject { put("text", value?.let(::JsonPrimitive) ?: JsonNull) }
            }
        }
        api.tools.register("delete", "Delete a named note") {
            string("name", "Note name")
            idempotency = Idempotency.INHERENT
            execute {
                api.storage.delete("note." + text(string("name"), 80))
                buildJsonObject { put("deleted", true) }
            }
        }
    }
}
