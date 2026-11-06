package org.example.sdk3.telegram

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

        api.tools.register("draft", "Prepare a Telegram message; does not send it") {
            string("chat", "Destination username or chat reference")
            string("text", "Message body")
            idempotency = Idempotency.INHERENT
            execute {
                val intent = buildJsonObject {
                    put("type", "telegram.send-message")
                    put("chat", text(string("chat"), 128))
                    put("text", text(string("text")))
                    put("dispatched", false)
                    put("requires", "User confirmation and a separately authorized Telegram adapter")
                }
                api.storage.putString("telegram.draft", intent.toString())
                intent
            }
        }
        api.tools.register("draft_read", "Read the pending Telegram draft") {
            effect = OperationEffect.READ
            execute { api.storage.getString("telegram.draft")?.let { Json.parseToJsonElement(it) } ?: JsonNull }
        }
    }
}
