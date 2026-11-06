package org.example.sdk3.weather

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

        api.tools.register("forecast", "Get the current Berlin temperature from Open-Meteo") {
            effect = OperationEffect.READ
            idempotency = Idempotency.INHERENT
            execute {
                val response = api.network.get("https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&current=temperature_2m")
                require(response.status == 200) { "Weather service returned a non-success response" }
                val payload = Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
                buildJsonObject {
                    put("city", "Berlin")
                    put("current", payload.getValue("current"))
                    put("units", payload.getValue("current_units"))
                    put("source", "Open-Meteo")
                }
            }
        }
    }
}
