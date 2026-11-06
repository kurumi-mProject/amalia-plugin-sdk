package org.amalia.platform.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** All timestamps in this API are UTC Unix epoch milliseconds. No caller authority is carried here. */
@Serializable
data class InvocationRequest(
    val requestId: String,
    val contractId: String,
    val contractVersion: Version,
    val providerId: String,
    val operationId: String,
    val input: JsonElement = JsonNull,
    val protocolVersion: Version = Protocol.version,
    val deadlineEpochMillis: Long? = null,
    val idempotency: IdempotencyKey? = null,
    val traceId: String? = null,
    val requiredFeatures: Set<String> = emptySet(),
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(listOf(requestId, contractId, providerId, operationId).none(String::isBlank))
        require(traceId == null || traceId.isNotBlank())
    }
}

/** Host validates scope and expiry; this does not promise exactly-once execution. */
@Serializable
data class IdempotencyKey(val value: String, val scope: String, val expiresAtEpochMillis: Long) {
    init { require(value.isNotBlank() && scope.isNotBlank()) }
}

@Serializable
data class InvocationResponse(val requestId: String, val result: InvocationResult) {
    init { require(requestId.isNotBlank()) }
}

@Serializable
sealed interface InvocationResult {
    @Serializable @SerialName("success")
    data class Success(val output: JsonElement = JsonNull, val evidence: List<EvidenceRef> = emptyList()) : InvocationResult
    @Serializable @SerialName("accepted")
    data class Accepted(val task: TaskRef) : InvocationResult
    @Serializable @SerialName("interaction_required")
    data class InteractionRequired(val interaction: InteractionRequest) : InvocationResult
    @Serializable @SerialName("failure")
    data class Failure(val error: ProtocolError) : InvocationResult
}

/** Open namespaced codes permit domain-specific errors without a protocol SDK release. */
@Serializable
data class ProtocolError(
    val code: String,
    val explanation: LocalizedText,
    val retry: RetryAdvice = RetryAdvice.NEVER,
    val details: JsonElement = JsonNull,
    val evidence: List<EvidenceRef> = emptyList(),
    val retryAfterEpochMillis: Long? = null,
) {
    init {
        require(code.isNotBlank())
        require(retryAfterEpochMillis == null || retry == RetryAdvice.AFTER_DELAY)
        require(code != ErrorCodes.UNKNOWN_OUTCOME || retry == RetryAdvice.VERIFY_OUTCOME)
    }
}

@Serializable enum class RetryAdvice { NEVER, SAFE, AFTER_DELAY, AFTER_INTERVENTION, VERIFY_OUTCOME }

object ErrorCodes {
    const val UNSUPPORTED_PROTOCOL = "protocol.unsupported_protocol"
    const val UNSUPPORTED_VERSION = "protocol.unsupported_version"
    const val UNSUPPORTED_OPERATION = "protocol.unsupported_operation"
    const val UNSUPPORTED_FEATURE = "protocol.unsupported_feature"
    const val INVALID_INPUT = "protocol.invalid_input"
    const val INVALID_OUTPUT = "protocol.invalid_output"
    const val ACCESS_DENIED = "protocol.access_denied"
    const val PROVIDER_UNAVAILABLE = "protocol.provider_unavailable"
    const val BUDGET_EXCEEDED = "protocol.budget_exceeded"
    const val CANCELLED = "protocol.cancelled"
    const val DEADLINE_EXCEEDED = "protocol.deadline_exceeded"
    const val EXECUTION_FAILED = "protocol.execution_failed"
    const val UNKNOWN_OUTCOME = "protocol.unknown_outcome"
    const val STREAM_OVERFLOW = "protocol.stream_overflow"
}

@Serializable
data class LocalizedText(
    val key: String,
    val fallback: String,
    val arguments: Map<String, String> = emptyMap(),
) { init { require(key.isNotBlank() && fallback.isNotBlank()) } }

@Serializable
data class TaskRef(val id: String, val providerId: String, val attemptId: String? = null) {
    init { require(id.isNotBlank() && providerId.isNotBlank()); require(attemptId == null || attemptId.isNotBlank()) }
}

@Serializable
data class InteractionRequest(
    val id: String,
    val prompt: LocalizedText,
    val responseSchema: JsonSchema,
    val task: TaskRef? = null,
    val expiresAtEpochMillis: Long? = null,
) { init { require(id.isNotBlank()) } }

@Serializable
data class EvidenceRef(val resource: ResourceRef, val description: LocalizedText? = null)

@Serializable
data class ResourceRef(
    val id: String,
    val owner: ResourceOwner,
    val mimeType: String,
    val version: String,
    val rights: Set<ResourceRight>,
    val sizeBytes: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val transfer: ResourceTransfer? = null,
) {
    init {
        require(listOf(id, mimeType, version).none(String::isBlank))
        require(sizeBytes == null || sizeBytes >= 0)
    }
}
@Serializable
data class ResourceOwner(val publisherId: String, val packageId: String, val providerId: String, val deviceId: String) {
    init { require(listOf(publisherId, packageId, providerId, deviceId).none(String::isBlank)) }
}
@Serializable enum class ResourceRight { READ, WRITE, DELETE, SHARE }
/** Transport-specific opaque locator; not an automatically accessible URI or a bearer grant. */
@Serializable
data class ResourceTransfer(val transport: String, val locator: String) {
    init { require(transport.isNotBlank() && locator.isNotBlank()) }
}
