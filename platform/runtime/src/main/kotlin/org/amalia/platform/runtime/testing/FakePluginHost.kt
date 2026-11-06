package org.amalia.platform.runtime.testing

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.amalia.platform.protocol.*
import org.amalia.platform.protocol.sdk.AmaliaPlugin
import org.amalia.platform.runtime.CoreRuntime
import org.amalia.platform.runtime.RuntimeGrant
import org.amalia.platform.runtime.RuntimeLimits

/** JVM-only trusted host. Declarations never create storage, network or invocation grants.
 * Call grantStorage/grantNetwork/grant explicitly before the corresponding use.
 * stop/stopAll run plugin cleanup; close only withdraws authority, like CoreRuntime.close.
 */
class FakePluginHost(
    val deviceId: String = "fake-device",
    limits: RuntimeLimits = RuntimeLimits(),
) : AutoCloseable {
    private val lifecycle = Mutex()
    private val storageGrants = ConcurrentHashMap.newKeySet<String>()
    private val stores = ConcurrentHashMap<String, FakePluginStorage>()
    private val networks = ConcurrentHashMap<String, FakeNetworkAccess>()
    private val featureGrants = ConcurrentHashMap<String, MutableSet<String>>()
    fun grantFeature(packageId: String, capability: String) {
        check(!closed)
        featureGrants.computeIfAbsent(packageId) { ConcurrentHashMap.newKeySet() }.add(capability)
        features.filterKeys { it.first.packageId == packageId }.values.forEach { it.grant(capability) }
    }
    fun featuresFor(identity: CallerIdentity, generation: Long): FakeHostFeatures = checkNotNull(features[identity to generation])
    private val features = ConcurrentHashMap<Pair<CallerIdentity, Long>, FakeHostFeatures>()
    private val screens = ConcurrentHashMap<Pair<CallerIdentity, Long>, FakePluginUi>()
    private val media = ConcurrentHashMap<Pair<CallerIdentity, Long>, FakeMediaAccess>()
    private val activations = ConcurrentHashMap<CallerIdentity, CoreRuntime.Activation>()
    @Volatile private var closed = false
    val runtime: CoreRuntime = CoreRuntime(deviceId, limits, storage = { identity, _ ->
        val store = stores.computeIfAbsent(identity.packageId) { FakePluginStorage() }
        fun namespace(delegate: StorageNamespace) = object : StorageNamespace {
            private fun checkGrant() {
                if (closed || identity.packageId !in storageGrants) throw SecurityException("Fake storage has not been granted")
            }
            override fun read(key: String): String? { checkGrant(); return delegate.read(key) }
            override fun write(key: String, value: String) { checkGrant(); delegate.write(key, value) }
            override fun delete(key: String) { checkGrant(); delegate.delete(key) }
        }
        object : PluginStorage {
            override val data = namespace(store.data)
            override val settings = namespace(store.settings)
        }
    }, network = { identity, _ -> networkFor(identity.packageId) },
        services = { identity, generation -> FakeHostFeatures(identity, generation, dispatch = { operation, input ->
            val provider = runtime.providerInventory().singleOrNull { it.packageId == identity.packageId && it.activationGeneration == generation }
                ?: error("Fake background provider unavailable")
            val contract = provider.contracts.single { it.contractId == "${identity.packageId}.tools" }
            val grantId = "fake-background:${UUID.randomUUID()}"
            runtime.grant(RuntimeGrant(grantId, identity, contract.contractId, setOf(operation), provider.id, System.currentTimeMillis() + 30_000))
            try {
                val result = runtime.clientFor(identity).invoke(InvocationRequest(UUID.randomUUID().toString(), contract.contractId,
                    contract.version, provider.id, operation, input)).result
                check(result is InvocationResult.Success) { "Fake background operation failed" }
            } finally { runtime.revoke(grantId) }
        }).also { feature ->
            featureGrants[identity.packageId]?.forEach { feature.grant(it) }
            features[identity to generation] = feature
        } },
        media = { identity, generation -> FakeMediaAccess { token ->
            token in (featureGrants[identity.packageId] ?: emptySet())
        }.also { media[identity to generation] = it } },
        ui = { identity, generation -> FakePluginUi(identity.packageId) { "ui" in (featureGrants[identity.packageId] ?: emptySet()) }.also { screens[identity to generation] = it } },
        withdrawn = { identity, generation ->
            screens.remove(identity to generation)?.close()
            features.remove(identity to generation)?.close()
            media.remove(identity to generation)?.close()
        })
    val caller = CallerIdentity("fake.host", "fake.host.client", "client", deviceId)
    val client: ProtocolClient get() = runtime.clientFor(caller)

    fun grantStorage(packageId: String) { check(!closed); require(packageId.isNotBlank()); storageGrants += packageId }
    fun revokeStorage(packageId: String) { storageGrants -= packageId }
    fun networkFor(packageId: String): FakeNetworkAccess {
        check(!closed); require(packageId.isNotBlank())
        return networks.computeIfAbsent(packageId) { FakeNetworkAccess() }
    }
    fun grantNetwork(packageId: String, domain: String) { networkFor(packageId).grant(domain); grantFeature(packageId, "network:$domain") }
    fun revokeNetwork(packageId: String, domain: String) { networkFor(packageId).revoke(domain); featureGrants[packageId]?.remove("network:$domain") }
    fun grant(grant: RuntimeGrant) = runtime.grant(grant)
    fun revoke(grantId: String) = runtime.revoke(grantId)

    suspend fun activate(
        plugin: AmaliaPlugin,
        identity: CallerIdentity = CallerIdentity(plugin.manifest.id.substringBeforeLast('.'), plugin.manifest.id, "plugin", deviceId),
    ): CoreRuntime.Activation = lifecycle.withLock {
        check(!closed)
        runtime.activate(identity, plugin).also { activations[identity] = it }
    }

    /** Construct an explicit RuntimeGrant for caller + contract/provider before invoking. */
    suspend fun invoke(
        plugin: AmaliaPlugin,
        name: String,
        input: JsonElement = JsonObject(emptyMap()),
        deadlineEpochMillis: Long? = null,
    ): InvocationResponse = client.invoke(InvocationRequest(
        requestId = UUID.randomUUID().toString(), contractId = "${plugin.manifest.id}.tools",
        contractVersion = Version.parse(plugin.manifest.version), providerId = "${plugin.manifest.id}.local",
        operationId = name, input = input, deadlineEpochMillis = deadlineEpochMillis,
    ))

    suspend fun stop(identity: CallerIdentity) = lifecycle.withLock {
        activations.remove(identity)?.stop()
        Unit
    }
    suspend fun stopAll() = lifecycle.withLock {
        var failure: Throwable? = null
        val pending = activations.values.toList()
        activations.clear()
        pending.asReversed().forEach {
            try { it.stop() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    override fun close() {
        closed = true
        runtime.close()
        activations.clear()
        storageGrants.clear()
    }
}
