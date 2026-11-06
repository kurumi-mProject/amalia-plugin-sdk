package org.amalia.platform.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.amalia.platform.protocol.*

/** Exact scopes only. Null operations/provider means all operations/providers of this contract. */
data class RuntimeGrant(
    override val id: String,
    val caller: CallerIdentity,
    val contractId: String,
    val operations: Set<String>? = null,
    val providerId: String? = null,
    override val expiresAtEpochMillis: Long,
) : EffectiveGrant {
    init {
        require(id.isNotBlank() && contractId.isNotBlank())
        require(operations == null || operations.isNotEmpty() && operations.none(String::isBlank))
        require(providerId == null || providerId.isNotBlank())
    }
    override val capability: String get() = contractId
    override val scope: JsonElement get() = JsonPrimitive(contractId)
    internal fun permits(identity: CallerIdentity, contract: String, operation: String, provider: String, now: Long) =
        caller == identity && contractId == contract && (operations == null || operation in operations) &&
            (providerId == null || providerId == provider) && now < expiresAtEpochMillis
}

/** Bindings are exact and visible; several compatible providers without a binding are ambiguous. */
data class SelectionProfile(val bindings: Map<String, String> = emptyMap())
sealed interface ProviderSelection {
    data class Selected(val provider: ProviderDescriptor) : ProviderSelection
    data class Unavailable(val reason: String) : ProviderSelection
    data class Ambiguous(val candidates: List<ProviderDescriptor>) : ProviderSelection
}

data class RuntimeLimits(
    val maxInvocationMillis: Long = 30_000,
    val maxStartupMillis: Long = 15_000,
    val maxStopMillis: Long = 5_000,
    val maxConcurrentCallsPerProvider: Int = 64,
    val maxSubscriptionMillis: Long = 300_000,
    val maxStreamBuffer: Int = 256,
) {
    init {
        require(maxInvocationMillis in 1..86_400_000 && maxStartupMillis in 1..86_400_000)
        require(maxStopMillis in 1..86_400_000 && maxSubscriptionMillis in 1..86_400_000)
        require(maxConcurrentCallsPerProvider > 0 && maxStreamBuffer in 1..65536)
    }
}

internal fun runtimeError(code: String, message: String) = ProtocolError(code, LocalizedText(code, message))
internal fun failure(code: String, message: String): InvocationResult = InvocationResult.Failure(runtimeError(code, message))

/** Host supplies actual storage; the default denies every resource operation. */
object DeniedResourceAccess : ResourceAccess {
    override fun read(resource: ResourceRef): kotlinx.coroutines.flow.Flow<ByteArray> =
        kotlinx.coroutines.flow.flow { throw SecurityException("Resource access is not configured") }
    override suspend fun write(request: ResourceWriteRequest, chunks: kotlinx.coroutines.flow.Flow<ByteArray>): ResourceRef =
        throw SecurityException("Resource access is not configured")
    override suspend fun delete(resource: ResourceRef): Unit = throw SecurityException("Resource access is not configured")
}

/** Runtime-specific lifecycle surface; plugins can launch work without detached scopes. */
interface ManagedPluginContext : PluginContext {
    val scope: kotlinx.coroutines.CoroutineScope
}
