package org.amalia.platform.protocol

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Immutable JSON Schema subset. Construction and decoding both reject unsupported keywords. */
@Serializable(with = JsonSchemaSerializer::class)
class JsonSchema(val definition: JsonObject) {
    init { checkDefinition(definition, "#") }

    /** An empty list means valid; paths are JSON Pointers (root is the empty string). */
    fun validate(value: JsonElement): List<SchemaViolation> = buildList {
        validateValue(definition, value, "", this)
    }

    override fun equals(other: Any?): Boolean = other is JsonSchema && definition == other.definition
    override fun hashCode(): Int = definition.hashCode()
    override fun toString(): String = definition.toString()

    companion object {
        val AnyValue: JsonSchema = JsonSchema(JsonObject(emptyMap()))
        fun parse(text: String): JsonSchema = JsonSchema(Json.parseToJsonElement(text).jsonObject)
    }
}

@Serializable
data class SchemaViolation(val path: String, val keyword: String, val message: String)

object JsonSchemaSerializer : KSerializer<JsonSchema> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: JsonSchema) =
        encoder.encodeSerializableValue(JsonObject.serializer(), value.definition)
    override fun deserialize(decoder: Decoder): JsonSchema =
        JsonSchema(decoder.decodeSerializableValue(JsonObject.serializer()))
}

private val types = setOf("null", "boolean", "object", "array", "number", "integer", "string")
private val keywords = setOf(
    "type", "properties", "required", "additionalProperties", "items", "enum", "const",
    "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
    "title", "description", "default", "examples", "\$comment",
)

private fun checkDefinition(schema: JsonObject, path: String) {
    fun fail(message: String): Nothing = throw IllegalArgumentException("$path: $message")
    schema.forEach { (key, value) ->
        if (key !in keywords && !key.startsWith("x-")) fail("Unsupported schema keyword '$key'")
        when (key) {
            "type" -> {
                val declared = when (value) {
                    is JsonPrimitive -> listOf(value)
                    is JsonArray -> value.toList().also { if (it.isEmpty()) fail("type cannot be empty") }
                    else -> fail("type must be a string or array of strings")
                }
                if (declared.any { it !is JsonPrimitive || !it.isString || it.content !in types } ||
                    declared.distinct().size != declared.size) fail("Invalid type")
            }
            "properties" -> {
                val properties = value as? JsonObject ?: fail("properties must be an object")
                properties.forEach { (name, child) ->
                    checkDefinition(child as? JsonObject ?: fail("Property schemas must be objects"), "$path/properties/${escape(name)}")
                }
            }
            "required" -> {
                val names = value as? JsonArray ?: fail("required must be an array")
                if (names.any { it !is JsonPrimitive || !it.isString } || names.distinct().size != names.size)
                    fail("required must contain unique strings")
            }
            "additionalProperties" -> if (value !is JsonPrimitive || value.booleanOrNull == null)
                fail("additionalProperties supports only boolean values")
            "items" -> checkDefinition(value as? JsonObject ?: fail("items must be a schema object"), "$path/items")
            "enum" -> if (value !is JsonArray || value.isEmpty() || value.indices.any { i ->
                    (0 until i).any { j -> jsonEqual(value[i], value[j]) }
                }) fail("enum must be a non-empty array of distinct values")
            "minimum", "maximum" -> if (number(value) == null) fail("$key must be a finite JSON number")
            "minLength", "maxLength", "minItems", "maxItems" -> {
                val numeric = number(value) ?: fail("$key must be a non-negative integer")
                if (numeric.signum() < 0 || numeric.stripTrailingZeros().scale() > 0)
                    fail("$key must be a non-negative integer")
            }
            "title", "description", "\$comment" -> if (value !is JsonPrimitive || !value.isString) fail("$key must be a string")
            "examples" -> if (value !is JsonArray) fail("examples must be an array")
        }
    }
    listOf("minimum" to "maximum", "minLength" to "maxLength", "minItems" to "maxItems").forEach { (min, max) ->
        val lower = schema[min]?.let(::number)
        val upper = schema[max]?.let(::number)
        if (lower != null && upper != null && lower > upper) fail("$min exceeds $max")
    }
}

private fun number(value: JsonElement): BigDecimal? {
    if (value !is JsonPrimitive || value.isString || value == JsonNull) return null
    // BigDecimal alone accepts non-JSON forms such as +1 and 01.
    if (!Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(value.content)) return null
    return value.content.toBigDecimalOrNull()
}

private fun jsonEqual(a: JsonElement, b: JsonElement): Boolean {
    val an = number(a)
    val bn = number(b)
    return when {
        an != null && bn != null -> an.compareTo(bn) == 0
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> jsonEqual(value, b.getValue(key)) }
        else -> a == b
    }
}

private fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

private fun validateValue(schema: JsonObject, value: JsonElement, path: String, errors: MutableList<SchemaViolation>) {
    fun error(keyword: String, message: String) { errors += SchemaViolation(path, keyword, message) }
    val numeric = number(value)
    fun matches(type: String): Boolean = when (type) {
        "null" -> value == JsonNull
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "string" -> value is JsonPrimitive && value.isString
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "number" -> numeric != null
        "integer" -> numeric != null && numeric.stripTrailingZeros().scale() <= 0
        else -> false
    }
    schema["type"]?.let { declared ->
        val names = if (declared is JsonArray) declared.map { it.jsonPrimitive.content } else listOf(declared.jsonPrimitive.content)
        if (names.none(::matches)) error("type", "Expected ${names.joinToString(" or ")}")
    }
    schema["enum"]?.jsonArray?.let { if (it.none { option -> jsonEqual(option, value) }) error("enum", "Value is not allowed") }
    schema["const"]?.let { if (!jsonEqual(it, value)) error("const", "Value differs from constant") }
    if (numeric != null) {
        schema["minimum"]?.let { if (numeric < it.jsonPrimitive.content.toBigDecimal()) error("minimum", "Value is below minimum") }
        schema["maximum"]?.let { if (numeric > it.jsonPrimitive.content.toBigDecimal()) error("maximum", "Value is above maximum") }
    }
    fun sizeBounds(size: Int, min: String, max: String) {
        schema[min]?.let { if (size.toBigDecimal() < it.jsonPrimitive.content.toBigDecimal()) error(min, "Size is below minimum") }
        schema[max]?.let { if (size.toBigDecimal() > it.jsonPrimitive.content.toBigDecimal()) error(max, "Size is above maximum") }
    }
    when (value) {
        is JsonObject -> {
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            schema["required"]?.jsonArray?.forEach { name ->
                if (name.jsonPrimitive.content !in value) error("required", "Missing property '${name.jsonPrimitive.content}'")
            }
            value.forEach { (key, child) ->
                val childSchema = properties[key]
                if (childSchema != null) validateValue(childSchema.jsonObject, child, "$path/${escape(key)}", errors)
                else if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false)
                    errors += SchemaViolation("$path/${escape(key)}", "additionalProperties", "Unknown property")
            }
        }
        is JsonArray -> {
            sizeBounds(value.size, "minItems", "maxItems")
            schema["items"]?.jsonObject?.let { itemSchema ->
                value.forEachIndexed { index, item -> validateValue(itemSchema, item, "$path/$index", errors) }
            }
        }
        is JsonPrimitive -> if (value.isString) sizeBounds(value.content.codePointCount(0, value.content.length), "minLength", "maxLength")
    }
}
