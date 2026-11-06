package org.amalia.platform.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** IDs are opaque namespaced strings. They do not prove publisher identity. */
@Serializable
data class ContractDescriptor(
    val id: String,
    val version: Version,
    val description: String,
    val operations: List<OperationDescriptor>,
    val events: List<EventDescriptor> = emptyList(),
    val states: List<StateDescriptor> = emptyList(),
    val resourceTypes: List<ResourceTypeDescriptor> = emptyList(),
    val transports: Set<String> = setOf("in-process"),
    val features: Set<String> = emptySet(),
    val compatibility: String = "",
    val documentation: List<String> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(id.isNotBlank() && description.isNotBlank())
        require(operations.map { it.id }.distinct().size == operations.size)
        require(events.map { it.id }.distinct().size == events.size)
        require(states.map { it.id }.distinct().size == states.size)
        require(resourceTypes.map { it.id }.distinct().size == resourceTypes.size)
        require(transports.isNotEmpty() && transports.none(String::isBlank))
    }
}

@Serializable
data class OperationDescriptor(
    val id: String,
    val description: String,
    val input: JsonSchema,
    val output: JsonSchema,
    val errors: Map<String, JsonSchema> = emptyMap(),
    val effect: OperationEffect = OperationEffect.READ,
    val idempotency: Idempotency = Idempotency.NONE,
    val execution: ExecutionKind = ExecutionKind.IMMEDIATE,
    val cancellable: Boolean = true,
    val requiredFeatures: Set<String> = emptySet(),
) { init { require(id.isNotBlank() && description.isNotBlank()) } }

@Serializable enum class OperationEffect { READ, WRITE }
@Serializable enum class Idempotency { NONE, INHERENT, KEYED }
@Serializable enum class ExecutionKind { IMMEDIATE, TASK }

@Serializable
data class EventDescriptor(
    val id: String,
    val payload: JsonSchema,
    val description: String,
    val allowLoss: Boolean = false,
) {
    init { require(id.isNotBlank() && description.isNotBlank()) }
}
@Serializable
data class StateDescriptor(val id: String, val snapshot: JsonSchema, val description: String) {
    init { require(id.isNotBlank() && description.isNotBlank()) }
}
@Serializable
data class ResourceTypeDescriptor(val id: String, val description: String, val ownership: String) {
    init { require(id.isNotBlank() && description.isNotBlank() && ownership.isNotBlank()) }
}

@Serializable
data class ContractOffer(val contractId: String, val version: Version, val features: Set<String> = emptySet()) {
    init { require(contractId.isNotBlank()) }
}

@Serializable
data class ProviderDescriptor(
    val id: String,
    val publisherId: String,
    val packageId: String,
    val packageVersion: Version,
    val deviceId: String,
    val activationGeneration: Long,
    val displayName: String,
    val contracts: List<ContractOffer>,
    val transports: Set<String> = setOf("in-process"),
) {
    init {
        require(listOf(id, publisherId, packageId, deviceId, displayName).none(String::isBlank))
        require(activationGeneration >= 0 && contracts.isNotEmpty())
        require(contracts.map { it.contractId to it.version }.distinct().size == contracts.size)
        require(transports.isNotEmpty() && transports.none(String::isBlank))
    }
    fun supports(query: ProviderQuery): Boolean = contracts.any {
        it.contractId == query.contractId && it.version in query.versions && it.features.containsAll(query.requiredFeatures)
    } && (query.providerId == null || query.providerId == id)
}

@Serializable
data class ProviderQuery(
    val contractId: String,
    val versions: VersionRange,
    val requiredFeatures: Set<String> = emptySet(),
    val providerId: String? = null,
) { init { require(contractId.isNotBlank()); require(providerId == null || providerId.isNotBlank()) } }

@Serializable
data class DiscoveryResult(val available: List<ProviderDescriptor>, val unavailable: List<UnavailableProvider> = emptyList())
@Serializable
data class UnavailableProvider(val providerId: String?, val error: ProtocolError)
