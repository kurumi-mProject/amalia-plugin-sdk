package org.example.sdk3.screen

import amalia.generated.GeneratedManifest
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*
import org.amalia.platform.protocol.sdk.*
import org.amalia.platform.protocol.ui.*

class Plugin : AmaliaPlugin() {
    override val manifest = GeneratedManifest.value
    override suspend fun install(api: AmaliaApi) {
        val title = "Plugin notes"
        val document = UiDocument(id = "${manifest.id}.notes", route = "${manifest.id}.notes", titleKey = "title", root = "body",
            nodes = listOf(UiNode(id = "body", component = "org.amalia.ui.text", labelKey = "body")),
            strings = UiDocuments.locales.associateWith { mapOf("title" to title, "body" to "A host-rendered plugin screen. No Android Context or Compose dependency.") })
        api.ui.registerScreen(document.route, title, document)
        api.tools.register("presentation", "Read registered screen route") {
            effect = OperationEffect.READ
            execute { JsonPrimitive(document.route) }
        }
    }
}
