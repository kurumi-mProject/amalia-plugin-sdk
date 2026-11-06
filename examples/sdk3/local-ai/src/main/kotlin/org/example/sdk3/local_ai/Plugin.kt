package org.example.sdk3.local_ai

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

        api.tools.register("prepare", "Prepare local inference; does not run a model") {
            string("model", "Local model identifier")
            string("prompt", "Prompt text")
            string("max_tokens", "Integer output budget from 1 through 2048")
            idempotency = Idempotency.INHERENT
            execute {
                val tokens = string("max_tokens").toInt()
                require(tokens in 1..2048)
                val intent = buildJsonObject {
                    put("type", "local-ai.generate")
                    put("model", text(string("model"), 128))
                    put("prompt", text(string("prompt")))
                    put("maxTokens", tokens)
                    put("executed", false)
                    put("requires", "Host local inference adapter, installed model and resource policy")
                }
                api.storage.putString("local-ai.request", intent.toString())
                intent
            }
        }
        api.tools.register("request", "Read the pending local inference request") {
            effect = OperationEffect.READ
            execute { api.storage.getString("local-ai.request")?.let { Json.parseToJsonElement(it) } ?: JsonNull }
        }
    }
}
