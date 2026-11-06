package org.example.sdk3.reminders

import amalia.generated.GeneratedManifest
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*
import org.amalia.platform.protocol.sdk.*

class Plugin : AmaliaPlugin() {
    override val manifest = GeneratedManifest.value
    override suspend fun install(api: AmaliaApi) {
        api.tools.register("deliver", "Show a scheduled reminder notification") {
            string("text")
            execute { api.notifications.notify(HostNotification("reminder", manifest.name, string("text"))); JsonPrimitive("notified") }
        }
        api.tools.register("prepare", "Schedule one inexact reminder for this live plugin activation") {
            string("text", "Reminder text")
            string("at", "UTC ISO-8601 instant")
            execute {
                val input = buildJsonObject { put("text", string("text")) }
                api.scheduler.schedule(HostSchedule("reminder", java.time.Instant.parse(string("at")).toEpochMilli(), HostOperation("deliver", input)))
                JsonPrimitive("scheduled; delivery requires the same authorized activation")
            }
        }
        api.tools.register("cancel", "Cancel the pending reminder") { execute { api.scheduler.cancel("reminder"); JsonPrimitive("cancelled") } }
        api.tools.register("pending", "Read pending reminders") {
            effect = OperationEffect.READ
            execute { JsonArray(api.scheduler.pending().map { JsonPrimitive(it.id) }) }
        }
    }
}
