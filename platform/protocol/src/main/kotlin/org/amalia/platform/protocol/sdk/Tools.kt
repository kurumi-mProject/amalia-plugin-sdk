package org.amalia.platform.protocol.sdk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*

class ToolCall internal constructor(val input: JsonObject, val context: InvocationContext) {
    fun string(name: String): String = input.getValue(name).jsonPrimitive.content
    fun stringOrNull(name: String): String? = input[name]?.jsonPrimitive?.contentOrNull
    fun integer(name: String): Long = input.getValue(name).jsonPrimitive.long
    fun boolean(name: String): Boolean = input.getValue(name).jsonPrimitive.boolean
    fun number(name: String): Double = input.getValue(name).jsonPrimitive.double
}

class ToolBuilder internal constructor() {
    private val properties = linkedMapOf<String, JsonElement>()
    private val required = mutableListOf<JsonElement>()
    private var execution: (suspend ToolCall.() -> JsonElement)? = null
    var timeoutMillis: Long = 30_000
        set(value) { require(value in 1..86_400_000); field = value }
    var outputSchema: JsonSchema = JsonSchema.AnyValue
    /** Conservative default: tools may have external effects. Authors must opt into read-only. */
    var effect: OperationEffect = OperationEffect.WRITE
    var idempotency: Idempotency = Idempotency.NONE

    fun string(name: String, description: String = "", required: Boolean = true) {
        require(name.isNotBlank() && name !in properties) { "Duplicate or blank argument name" }
        properties[name] = buildJsonObject {
            put("type", "string")
            if (description.isNotBlank()) put("description", description)
        }
        if (required) this.required += JsonPrimitive(name)
    }

    fun integer(name: String, description: String = "", required: Boolean = true) = primitive(name, "integer", description, required)
    fun boolean(name: String, description: String = "", required: Boolean = true) = primitive(name, "boolean", description, required)
    fun number(name: String, description: String = "", required: Boolean = true) = primitive(name, "number", description, required)
    private fun primitive(name: String, type: String, description: String, required: Boolean) {
        require(name.isNotBlank() && name !in properties)
        properties[name] = buildJsonObject { put("type", type); if (description.isNotBlank()) put("description", description) }
        if (required) this.required += JsonPrimitive(name)
    }

    fun execute(block: suspend ToolCall.() -> JsonElement) {
        check(execution == null) { "execute may only be specified once" }
        execution = block
    }

    internal fun build(name: String, description: String): ToolDefinition {
        val input = JsonSchema(buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.toMap()))
            put("required", JsonArray(required.toList()))
            put("additionalProperties", false)
        })
        return ToolDefinition(OperationDescriptor(name, description, input, outputSchema, effect = effect, idempotency = idempotency), timeoutMillis,
            checkNotNull(execution) { "Tool requires execute" })
    }
}

internal data class ToolDefinition(
    val descriptor: OperationDescriptor,
    val timeoutMillis: Long,
    val execute: suspend ToolCall.() -> JsonElement,
)

class Tools internal constructor(private val manifest: PluginManifest, private val checkActive: () -> Unit) {
    private val definitions = linkedMapOf<String, ToolDefinition>()
    private var frozen = false
    fun register(name: String, description: String, block: ToolBuilder.() -> Unit) {
        checkActive()
        check(!frozen) { "Tools may only be registered during install" }
        require(name.isNotBlank() && name !in definitions) { "Duplicate or blank tool name" }
        require(description.isNotBlank())
        definitions[name] = ToolBuilder().apply(block).build(name, description)
    }

    internal fun freeze(): ContractDescriptor? {
        frozen = true
        if (definitions.isEmpty()) return null
        return ContractDescriptor("${manifest.id}.tools", Version.parse(manifest.version),
            "${manifest.name} tools", definitions.values.map { it.descriptor })
    }

    internal fun handler(): OperationHandler = object : OperationHandler {
        override suspend fun invoke(request: InvocationRequest, context: InvocationContext): InvocationResult {
            checkActive()
            currentCoroutineContext().ensureActive()
            context.ensureActive()
            if (request.contractId != "${manifest.id}.tools" || request.contractVersion != Version.parse(manifest.version) ||
                request.providerId != "${manifest.id}.local") return failure(ErrorCodes.UNSUPPORTED_VERSION, "Tool contract mismatch")
            val tool = definitions[request.operationId]
                ?: return failure(ErrorCodes.UNSUPPORTED_OPERATION, "Unknown tool")
            if (tool.descriptor.input.validate(request.input).isNotEmpty())
                return failure(ErrorCodes.INVALID_INPUT, "Tool input violates its schema")
            val deadlineRemaining = context.deadlineEpochMillis?.let { it - System.currentTimeMillis() } ?: Long.MAX_VALUE
            val remaining = minOf(tool.timeoutMillis, deadlineRemaining, context.budget.remainingExecutionMillis ?: Long.MAX_VALUE)
            if (remaining <= 0) return failure(ErrorCodes.DEADLINE_EXCEEDED, "Tool deadline expired")
            // Do not catch cancellation: host and caller cancellation must retain coroutine semantics.
            val output = withTimeout(remaining) {
                tool.execute(ToolCall(request.input.jsonObject, context)).also {
                    currentCoroutineContext().ensureActive()
                    context.ensureActive()
                    checkActive()
                }
            }
            return if (tool.descriptor.output.validate(output).isEmpty()) InvocationResult.Success(output)
            else failure(ErrorCodes.INVALID_OUTPUT, "Tool output violates its schema")
        }
    }

    private fun failure(code: String, message: String) = InvocationResult.Failure(ProtocolError(code, LocalizedText(code, message)))
}
