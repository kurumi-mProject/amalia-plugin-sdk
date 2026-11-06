package tech.amalia.platform.runtime.packages

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import org.amalia.platform.protocol.*
import org.amalia.platform.runtime.ContractRequirement

internal object PackageMetadata {
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    private val id = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
    private val runtime = Regex("[a-z][a-z0-9.-]{0,63}")
    private val digest = Regex("[0-9a-f]{64}")

    fun manifest(bytes: ByteArray, limits: PackageLimits): PackageManifest {
        if (bytes.size > limits.maxManifestBytes) invalid("Manifest exceeds limit")
        val value = objectValue(bytes)
        fields(value, setOf("packageId", "version", "publisher", "runtime", "sha256"), setOf("entryPoint", "compatibility", "dependencies", "offers", "conflicts", "data", "capabilities"))
        val packageId = string(value, "packageId")
        val publisher = string(value, "publisher")
        val versionValue = string(value, "version")
        val runtimeValue = string(value, "runtime")
        if (packageId.length > 200 || !id.matches(packageId)) invalid("Invalid packageId")
        if (publisher.length > 200 || !id.matches(publisher)) invalid("Invalid publisher")
        if (!version.matches(versionValue)) invalid("Invalid version")
        if (!runtime.matches(runtimeValue)) invalid("Invalid runtime")
        val entryPoint = if ("entryPoint" in value) string(value, "entryPoint") else null
        if (runtimeValue == "declarations") {
            if (entryPoint != null) invalid("Declarations cannot have an entryPoint")
        } else if (entryPoint == null || entryPoint.length > 512 ||
            !Regex("[A-Za-z_][A-Za-z0-9_.$]*(?:::[A-Za-z_][A-Za-z0-9_]*)?").matches(entryPoint)
        ) invalid("Code runtime requires a symbolic entryPoint")
        val inventory = value["sha256"] as? JsonObject ?: invalid("sha256 must be an object")
        if (inventory.size > limits.maxEntries) invalid("Inventory exceeds entry limit")
        val hashes = inventory.mapValues { (path, hash) ->
            validatePackagePath(path, limits)
            if (path.equals("platform.json", true) || path.equals("package.sig", true)) {
                invalid("Reserved file in inventory")
            }
            val text = (hash as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("Inventory hash must be a string")
            if (!digest.matches(text)) invalid("Invalid SHA-256 for $path")
            text
        }
        val compatibility = value["compatibility"]?.let {
            val obj = objectOf(it); fields(obj, setOf("protocol", "runtimeVersion", "abi"))
            PackageCompatibility(range(obj["protocol"]), range(obj["runtimeVersion"]), token(string(obj, "abi")))
        }
        val dependencies = array(value, "dependencies").map { element ->
            val obj = objectOf(element)
            fields(obj, setOf("id", "contractId", "versions"), setOf("providerId", "features", "optional"))
            ContractRequirement(token(string(obj, "id")), ProviderQuery(token(string(obj, "contractId")),
                range(obj["versions"]), strings(obj, "features"), obj["providerId"]?.let { token(string(obj, "providerId")) }),
                obj["optional"]?.let {
                    val primitive = it as? JsonPrimitive ?: invalid("optional must be boolean")
                    if (primitive.isString || primitive.content !in setOf("true", "false")) invalid("optional must be boolean")
                    primitive.content == "true"
                } ?: false)
        }
        unique(dependencies.map { it.id })
        val offers = array(value, "offers").map { element ->
            val obj = objectOf(element); fields(obj, setOf("providerId", "contracts"))
            val contracts = array(obj, "contracts", required = true).map { contract ->
                val offer = objectOf(contract); fields(offer, setOf("contractId", "version"), setOf("features"))
                ContractOffer(token(string(offer, "contractId")), numeric(string(offer, "version")), strings(offer, "features"))
            }
            if (contracts.isEmpty()) invalid("Empty contract offers")
            unique(contracts.map { it.contractId + "@" + it.version })
            PackageOffer(token(string(obj, "providerId")), frozenList(contracts))
        }
        unique(offers.map { it.providerId })
        if (offers.isNotEmpty()) numeric(versionValue)
        val data = value["data"]?.let { element ->
            val obj = objectOf(element); fields(obj, setOf("schema", "readableSchemas"), setOf("migrations"))
            val schema = token(string(obj, "schema"))
            val readable = strings(obj, "readableSchemas", required = true)
            if (schema !in readable) invalid("Current schema must be readable")
            val migrations = array(obj, "migrations").map { migration ->
                val step = objectOf(migration); fields(step, setOf("from", "to", "operations"))
                val operations = array(step, "operations", required = true).map { operation ->
                    val op = objectOf(operation); fields(op, setOf("namespace", "operation", "key"), setOf("destination", "value"))
                    val namespace = string(op, "namespace")
                    if (namespace !in setOf("data", "settings")) invalid("Unknown data namespace")
                    val kind = string(op, "operation")
                    val destination = op["destination"]?.let { token(string(op, "destination")) }
                    val content = op["value"]?.let { string(op, "value") }
                    if (kind !in setOf("rename", "copy", "delete", "set") ||
                        ((kind == "rename" || kind == "copy") != (destination != null)) ||
                        ((kind == "set") != (content != null)) || (content?.length ?: 0) > 16384) invalid("Invalid migration operation")
                    PackageDataOperation(namespace, kind, token(string(op, "key")), destination, content)
                }
                PackageDataMigration(token(string(step, "from")), token(string(step, "to")), frozenList(operations))
            }
            unique(migrations.map { it.from })
            if (migrations.any { it.from == it.to }) invalid("Migration must change schema")
            PackageDataDeclaration(schema, readable, frozenList(migrations))
        }
        return PackageManifest(packageId, versionValue, publisher, runtimeValue, entryPoint, hashes,
            compatibility, frozenList(dependencies), frozenList(offers), strings(value, "conflicts"), data, strings(value, "capabilities").also { requested ->
                requested.forEach { org.amalia.platform.protocol.sdk.CapabilityTokens.validate(it) }
            })
    }

    private fun <T> frozenList(values: List<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())

    private fun token(text: String): String {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,199}").matches(text)) invalid("Invalid metadata token")
        return text
    }
    private fun numeric(text: String): Version = try { Version.parse(text) }
        catch (error: IllegalArgumentException) { throw PackageVerificationException("Invalid numeric version", error) }
    private fun objectOf(value: kotlinx.serialization.json.JsonElement?): JsonObject = value as? JsonObject ?: invalid("Object required")
    private fun range(value: kotlinx.serialization.json.JsonElement?): VersionRange {
        val obj = objectOf(value); fields(obj, setOf("minimum"), setOf("maximumExclusive"))
        val minimum = numeric(string(obj, "minimum"))
        val maximum = obj["maximumExclusive"]?.let { numeric(string(obj, "maximumExclusive")) }
        if (maximum != null && maximum <= minimum) invalid("Invalid version range")
        return VersionRange(minimum, maximum)
    }
    private fun array(value: JsonObject, key: String, required: Boolean = false): List<kotlinx.serialization.json.JsonElement> {
        val element = value[key] ?: if (required) invalid("Missing $key") else return emptyList()
        val array = element as? JsonArray ?: invalid("$key must be an array")
        if (array.size > 128) invalid("Metadata array limit exceeded")
        return array.toList()
    }
    private fun strings(value: JsonObject, key: String, required: Boolean = false): Set<String> {
        val strings = array(value, key, required).map {
            token((it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("String array required"))
        }
        unique(strings)
        return java.util.Collections.unmodifiableSet(strings.toSet())
    }
    private fun unique(values: List<String>) { if (values.distinct().size != values.size) invalid("Duplicate metadata ID") }

    fun objectValue(bytes: ByteArray): JsonObject {
        if (bytes.size > 256 * 1024) invalid("JSON metadata exceeds limit")
        try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            // JsonObject parsing alone silently collapses duplicate members. Reject these first.
            DuplicateGuard(text).check()
            return json.parseToJsonElement(text) as? JsonObject ?: invalid("JSON object required")
        } catch (error: PackageVerificationException) {
            throw error
        } catch (error: Exception) {
            throw PackageVerificationException("Invalid UTF-8 JSON metadata", error)
        }
    }

    fun fields(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        if (!value.keys.containsAll(required) || (value.keys - required - optional).isNotEmpty()) {
            invalid("Missing or unknown metadata fields")
        }
    }

    fun string(value: JsonObject, name: String): String =
        (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid("$name must be a string")

    /** Bounds nesting, members and arrays BEFORE allocating a JSON tree; keys compare decoded. */
    private class DuplicateGuard(private val text: String) {
        private var at = 0
        private var nodes = 0
        fun check() {
            value(0)
            whitespace()
            if (at != text.length) invalid("Trailing JSON content")
        }
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(char: Char) {
            whitespace()
            if (at >= text.length || text[at++] != char) invalid("Malformed JSON metadata")
        }
        private fun string(): String {
            whitespace()
            val start = at
            take('"')
            while (at < text.length) {
                when (text[at++]) {
                    '\\' -> { if (at >= text.length) invalid("Truncated JSON string"); at++ }
                    '"' -> return (json.parseToJsonElement(text.substring(start, at)) as JsonPrimitive).content
                }
            }
            invalid("Truncated JSON string")
        }
        private fun value(depth: Int) {
            whitespace()
            if (at >= text.length || depth > 10 || ++nodes > 16384) invalid("Invalid metadata nesting or node count")
            if (text[at] == '[') {
                at++; whitespace()
                if (at < text.length && text[at] == ']') { at++; return }
                var count = 0
                while (true) {
                    if (++count > 128) invalid("Metadata array limit exceeded")
                    value(depth + 1); whitespace()
                    if (at < text.length && text[at] == ']') { at++; return }
                    take(',')
                }
            }
            if (text.startsWith("true", at)) { at += 4; return }
            if (text.startsWith("false", at)) { at += 5; return }
            if (text[at] == '"') { string(); return }
            take('{')
            whitespace()
            if (at < text.length && text[at] == '}') { at++; return }
            val keys = HashSet<String>()
            while (true) {
                if (!keys.add(string())) invalid("Duplicate JSON field")
                if (keys.size > 2048) invalid("Metadata object limit exceeded")
                take(':')
                value(depth + 1)
                whitespace()
                if (at < text.length && text[at] == '}') { at++; return }
                take(',')
            }
        }
    }
}
