package org.amalia.platform.protocol.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*

/** Flat, bounded graph. No executable expressions, reflection, URLs or shell commands. */
@Serializable
data class UiDocument(
    val schemaVersion: Int = 1,
    val id: String,
    val route: String,
    val titleKey: String,
    val placement: String = "screen",
    val root: String,
    val nodes: List<UiNode>,
    val actions: List<UiAction> = emptyList(),
    val sources: List<UiSource> = emptyList(),
    val strings: Map<String, Map<String, String>>,
    val states: Map<UiPhase, String> = emptyMap(),
)

@Serializable
data class UiNode(
    val id: String,
    val component: String,
    val required: Boolean = true,
    val labelKey: String? = null,
    val actionId: String? = null,
    val value: UiBinding? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val children: List<String> = emptyList(),
)

@Serializable enum class UiPhase { LOADING, EMPTY, READY, ERROR, DENIED, CONFLICT }
@Serializable enum class BindingSource { LITERAL, STATE, FORM, ITEM }

/** STATE selects a declared source, FORM selects a node, ITEM selects the current list row.
 * Path is an RFC6901 pointer. ITEM is available only inside a list row template. */
@Serializable
data class UiBinding(
    val source: BindingSource = BindingSource.LITERAL,
    val literal: JsonElement = JsonNull,
    val sourceId: String? = null,
    val pointer: String = "",
)

@Serializable
data class UiAction(
    val id: String,
    val providerId: String,
    val contractId: String,
    val contractVersion: Version,
    val operationId: String,
    val input: Map<String, UiBinding> = emptyMap(),
    val confirmationKey: String? = null,
    val cancellable: Boolean = false,
)

@Serializable
data class UiSource(
    val id: String,
    val providerId: String,
    val contractId: String,
    val contractVersion: Version,
    val topicId: String,
)

/** A registered component declares and validates its own properties; no domain switch in core. */
data class UiComponentContract(val id: String, val properties: JsonSchema, val acceptsChildren: Boolean)

data class UiIssue(val path: String, val code: String)

object UiDocuments {
    const val MAX_BYTES = 256 * 1024
    val locales = setOf("ru", "en", "ja", "de", "es", "fr", "zh", "hi", "ar")
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    /** Scan BEFORE JSON deserialization so hostile nested JSON never reaches recursive decoders. */
    fun decode(text: String): UiDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "ui.size" }
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 24) { "ui.depth" } }
                '}', ']' -> { depth--; require(depth >= 0) { "ui.syntax" } }
            }
        }
        require(!quoted && depth == 0) { "ui.syntax" }
        return json.decodeFromString(UiDocument.serializer(), text)
    }

    fun validate(document: UiDocument, components: Collection<UiComponentContract>): List<UiIssue> = buildList {
        fun issue(path: String, code: String) { if (size < 128) add(UiIssue(path, code)) }
        if (document.schemaVersion != 1) issue("schemaVersion", "ui.version")
        val name = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        if (!name.matches(document.id) || !name.matches(document.route) || !name.matches(document.placement)) issue("id", "ui.identifier")
        if (document.nodes.size !in 1..128 || document.actions.size > 64 || document.sources.size > 16 ||
            document.nodes.any { it.children.size > 128 } || document.strings.size > 9 || document.strings.values.any { it.size > 512 }) {
            issue("nodes", "ui.limit"); return@buildList
        }
        fun bounded(value: JsonElement, depth: Int = 0): Boolean {
            if (depth > 16) return false
            return when (value) {
                is JsonObject -> value.size <= 128 && value.all { it.key.length <= 128 && bounded(it.value, depth + 1) }
                is JsonArray -> value.size <= 128 && value.all { bounded(it, depth + 1) }
                is JsonPrimitive -> value.content.length <= 4096
            }
        }
        if (document.nodes.any { !bounded(it.properties) || it.value?.literal?.let { v -> !bounded(v) } == true } ||
            document.actions.any { action -> action.input.values.any { !bounded(it.literal) } }) {
            issue("document", "ui.value_limit"); return@buildList
        }
        val nodes = document.nodes.associateBy { it.id }
        val actions = document.actions.associateBy { it.id }
        val sources = document.sources.associateBy { it.id }
        if (nodes.size != document.nodes.size || actions.size != document.actions.size || sources.size != document.sources.size) issue("id", "ui.duplicate")
        if ((nodes.keys + actions.keys + sources.keys).any { !name.matches(it) }) issue("id", "ui.identifier")
        val specs = components.associateBy { it.id }
        val keys = mutableSetOf(document.titleKey)
        val propertyBindings = mutableMapOf<String, List<UiBinding>>()
        fun binding(b: UiBinding, path: String) {
            if (b.pointer.length > 512 || b.pointer.count { it == '/' } > 32 || (b.pointer.isNotEmpty() && !b.pointer.startsWith('/')) || Regex("~(?![01])").containsMatchIn(b.pointer)) issue(path, "ui.pointer")
            if (b.source == BindingSource.STATE && b.sourceId !in sources) issue(path, "ui.source")
            if (b.source == BindingSource.FORM && b.sourceId !in nodes) issue(path, "ui.form")
        }
        fun translationKeys(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key.endsWith("Key") && child is JsonPrimitive && child.isString) keys += child.content
                    translationKeys(child)
                }
                is JsonArray -> value.forEach(::translationKeys)
                else -> Unit
            }
        }
        document.nodes.forEach { node ->
            val spec = specs[node.component]
            if (spec == null && node.required) issue(node.id, "ui.component_missing")
            if (node.labelKey?.length?.let { it > 128 } == true) issue(node.id, "ui.limit")
            if (spec != null) {
                spec.properties.validate(node.properties).forEach { issue("${node.id}${it.path}", "ui.properties") }
                if (!spec.acceptsChildren && node.children.isNotEmpty()) issue(node.id, "ui.children")
            }
            if (node.actionId != null && node.actionId !in actions) issue(node.id, "ui.action")
            if ((node.actionId != null || node.component in setOf("org.amalia.ui.input", "org.amalia.ui.toggle", "org.amalia.ui.slider", "org.amalia.ui.choices")) && node.labelKey == null) issue(node.id, "ui.accessibility")
            node.labelKey?.let(keys::add)
            translationKeys(JsonObject(node.properties.filterKeys { node.component != "org.amalia.ui.item" || it !in setOf("titleBinding", "subtitleBinding", "valueBinding") }))
            node.value?.let { binding(it, node.id) }
            val bindings = mutableListOf<UiBinding>()
            if (node.component == "org.amalia.ui.item") {
                listOf("titleBinding", "subtitleBinding", "valueBinding").forEach { property ->
                    node.properties[property]?.let { value ->
                        try {
                            val parsed = json.decodeFromJsonElement(UiBinding.serializer(), value)
                            binding(parsed, "${node.id}/$property")
                            bindings += parsed
                        } catch (_: IllegalArgumentException) {
                            issue("${node.id}/$property", "ui.binding")
                        }
                    }
                }
            }
            propertyBindings[node.id] = bindings
            if (node.component == "org.amalia.ui.list") {
                if (node.children.size != 1) issue(node.id, "ui.list_template")
                if (node.value == null) issue(node.id, "ui.list_binding")
            }
            if (node.component == "org.amalia.ui.progress" && node.value == null) issue(node.id, "ui.binding")
            if (node.component == "org.amalia.ui.button") {
                if (node.labelKey == null) issue(node.id, "ui.accessibility")
                if (node.actionId == null) issue(node.id, "ui.action")
                if (node.properties["variant"] == JsonPrimitive("destructive") &&
                    actions[node.actionId]?.confirmationKey == null) issue(node.id, "ui.confirmation")
            }
            if (node.component in setOf("org.amalia.ui.toggle", "org.amalia.ui.slider", "org.amalia.ui.choices") &&
                node.value?.source == BindingSource.FORM && node.value.sourceId == node.id) issue(node.id, "ui.form_self")
            if (node.component == "org.amalia.ui.slider") {
                val minimum = (node.properties["min"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val maximum = (node.properties["max"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                if (!minimum.isFinite() || !maximum.isFinite() || maximum <= minimum) issue(node.id, "ui.slider_range")
            }
        }
        document.actions.forEach { action ->
            if (listOf(action.providerId, action.contractId, action.operationId).any { it.isBlank() || it.length > 128 } || action.input.size > 64) issue(action.id, "ui.action")
            action.confirmationKey?.let(keys::add)
            action.input.forEach { (key, b) ->
                binding(b, "${action.id}/$key")
            }
        }
        document.sources.forEach { source ->
            if (listOf(source.providerId, source.contractId, source.topicId).any { it.isBlank() || it.length > 128 }) issue(source.id, "ui.source")
        }
        // Count every visit: a DAG cannot expand exponentially through shared children.
        var visits = 0
        fun walk(id: String, ancestors: Set<String>, inItem: Boolean = false) {
            if (++visits > 256) { issue(id, "ui.expansion"); return }
            if (id in ancestors || ancestors.size >= 12) { issue(id, "ui.cycle_or_depth"); return }
            val node = nodes[id]
            if (node == null) { issue(id, "ui.node_missing"); return }
            val bindings = listOfNotNull(node.value) + propertyBindings[id].orEmpty()
            if (!inItem && (bindings.any { it.source == BindingSource.ITEM } ||
                    actions[node.actionId]?.input?.values?.any { it.source == BindingSource.ITEM } == true)) {
                issue(id, "ui.item_scope")
            }
            if (inItem && node.component in setOf("org.amalia.ui.input", "org.amalia.ui.toggle",
                    "org.amalia.ui.slider", "org.amalia.ui.choices")) issue(id, "ui.list_form")
            node.children.forEach { walk(it, ancestors + id, inItem || node.component == "org.amalia.ui.list") }
        }
        (listOf(document.root) + document.states.values).forEach { walk(it, emptySet()) }
        if (document.strings.keys != locales) issue("strings", "ui.locales")
        document.strings.forEach { (locale, strings) ->
            if (strings.size > 512) issue("strings/$locale", "ui.limit")
            keys.forEach { key -> if (strings[key].isNullOrBlank()) issue("strings/$locale/$key", "ui.translation") }
            if (strings.keys.any { it.length > 128 } || strings.values.any { it.length > 4096 }) issue("strings/$locale", "ui.limit")
        }
    }

    fun resolve(binding: UiBinding, states: Map<String, JsonElement>, forms: Map<String, JsonElement>,
        item: JsonElement = JsonNull,
    ): JsonElement {
        var value = when (binding.source) {
            BindingSource.LITERAL -> binding.literal
            BindingSource.STATE -> states[binding.sourceId] ?: JsonNull
            BindingSource.FORM -> forms[binding.sourceId] ?: JsonNull
            BindingSource.ITEM -> item
        }
        if (binding.pointer.isEmpty()) return value
        binding.pointer.drop(1).split('/').take(32).forEach { segment ->
            val key = segment.replace("~1", "/").replace("~0", "~")
            value = when (val current = value) {
                is JsonObject -> current[key] ?: JsonNull
                is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) } ?: JsonNull
                else -> JsonNull
            }
        }
        return value
    }
}
