package org.amalia.platform.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Implemented only by the trusted host; never decoded from plugin input. Not a sandbox boundary. */
interface InvocationContext {
    val caller: CallerIdentity
    val effectiveGrants: Set<EffectiveGrant>
    val delegation: List<DelegationHop>
    val deviceId: String
    val activationGeneration: Long
    val deadlineEpochMillis: Long?
    val budget: InvocationBudget
    /** Checks host revocation, deadline and cancellation, throwing CancellationException when cancelled. */
    suspend fun ensureActive()
}

@Serializable
data class CallerIdentity(val publisherId: String, val packageId: String, val instanceId: String, val deviceId: String) {
    init { require(listOf(publisherId, packageId, instanceId, deviceId).none(String::isBlank)) }
}

/** A host-issued observation of authority, not a serializable credential. IDs and scopes are opaque. */
interface EffectiveGrant {
    val id: String
    val capability: String
    val scope: JsonElement
    val expiresAtEpochMillis: Long?
}
interface DelegationHop {
    val from: CallerIdentity
    val to: CallerIdentity
    val grantIds: Set<String>
}
interface InvocationBudget {
    val remainingCalls: Long?
    val remainingBytes: Long?
    val remainingExecutionMillis: Long?
}

/** Provider code receives trusted context separately from the untrusted wire request. */
interface OperationHandler {
    suspend fun invoke(request: InvocationRequest, context: InvocationContext): InvocationResult
}

/** No implicit selection: discovery returns candidates and call requests identify the chosen provider. */
interface ProtocolClient {
    suspend fun discover(query: ProviderQuery): DiscoveryResult
    suspend fun invoke(request: InvocationRequest): InvocationResponse
    /** Collection and its coroutine own the subscription; cancellation must release it. */
    fun subscribe(request: SubscriptionRequest): Flow<StreamMessage>
    suspend fun respond(interactionId: String, response: JsonElement): InvocationResult
    suspend fun cancel(task: TaskRef): InvocationResult
}

interface PluginEntrypoint {
    /** Host calls once per activation. An exception aborts activation and closes host-owned registrations. */
    suspend fun start(context: PluginContext)
    /** Host cancels plugin work and closes all registrations even when this hook fails. */
    suspend fun stop()
}

interface PluginContext {
    val identity: CallerIdentity
    val activationGeneration: Long
    val client: ProtocolClient
    val resources: ResourceAccess
    /** Own package KV storage; unavailable unless the host explicitly supplies authority. */
    val storage: PluginStorage get() = DeniedPluginStorage
    /** Host-issued transport authority, denied unless explicitly configured. */
    val network: NetworkAccess get() = DeniedNetworkAccess
    val media: MediaAccess get() = DeniedMediaAccess
    val services: HostFeatures get() = DeniedHostFeatures
    val ui: org.amalia.platform.protocol.sdk.PluginUiAccess get() = org.amalia.platform.protocol.sdk.DeniedPluginUiAccess
    /** Host verifies ownership/authorization, validates schema and rejects duplicate registrations. */
    suspend fun registerContract(contract: ContractDescriptor): Registration
    suspend fun registerProvider(provider: ProviderDescriptor, handler: OperationHandler): Registration
    suspend fun registerStreamProvider(providerId: String, handler: StreamHandler): Registration
}

/** Idempotent close; provider closure must make discovery and active clients observe unavailability. */
interface Registration : AutoCloseable {
    override fun close()
}

/** Chunked transport avoids embedding unbounded byte arrays in JSON. Every operation rechecks access. */
interface ResourceAccess {
    fun read(resource: ResourceRef): Flow<ByteArray>
    /** The host consumes chunks with backpressure; failed writes do not publish a partial resource. */
    suspend fun write(request: ResourceWriteRequest, chunks: Flow<ByteArray>): ResourceRef
    suspend fun delete(resource: ResourceRef)
}

@Serializable
data class ResourceWriteRequest(val mimeType: String, val expectedSizeBytes: Long? = null) {
    init { require(mimeType.isNotBlank()); require(expectedSizeBytes == null || expectedSizeBytes >= 0) }
}

interface StreamHandler {
    fun subscribe(request: SubscriptionRequest, context: InvocationContext): Flow<StreamMessage>
}

@Serializable
data class SubscriptionRequest(
    val id: String,
    val contractId: String,
    val contractVersion: Version,
    val providerId: String,
    val topicId: String,
    val kind: SubscriptionKind,
    val filter: JsonElement = JsonNull,
    val cursor: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val bufferCapacity: Int = 64,
    val overflow: OverflowPolicy = OverflowPolicy.SUSPEND,
    val protocolVersion: Version = Protocol.version,
) {
    init {
        require(listOf(id, contractId, providerId, topicId).none(String::isBlank))
        require(bufferCapacity in 1..65536)
    }
}

@Serializable enum class SubscriptionKind { EVENTS, STATE }
/** Dropping requires explicit contract permission. Otherwise suspend producer or fail the stream. */
@Serializable enum class OverflowPolicy { SUSPEND, FAIL, DROP_OLDEST, DROP_LATEST }

@Serializable
sealed interface StreamMessage {
    @Serializable @SerialName("event")
    data class Event(
        val eventId: String,
        val topicId: String,
        val payload: JsonElement,
        val orderingScope: String,
        val sequence: Long,
        val cursor: String? = null,
    ) : StreamMessage {
        init { require(listOf(eventId, topicId, orderingScope).none(String::isBlank)); require(sequence >= 0) }
    }
    @Serializable @SerialName("snapshot")
    data class Snapshot(val topicId: String, val revision: String, val value: JsonElement, val cursor: String? = null) : StreamMessage {
        init { require(topicId.isNotBlank() && revision.isNotBlank()) }
    }
    @Serializable @SerialName("closed")
    data class Closed(val error: ProtocolError? = null) : StreamMessage
}
