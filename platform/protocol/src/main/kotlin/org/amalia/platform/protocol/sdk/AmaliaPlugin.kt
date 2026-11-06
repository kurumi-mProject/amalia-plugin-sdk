package org.amalia.platform.protocol.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.amalia.platform.protocol.PluginContext
import org.amalia.platform.protocol.PluginEntrypoint
import org.amalia.platform.protocol.Version

/** Declarations are metadata, never host-issued permission. */
sealed interface Capability {
    data class Host(val token: String, val reason: String) : Capability {
        init { require(token in CapabilityTokens.simple); require(reason.isNotBlank() && reason.length <= 512) }
    }
    class Network(domains: Set<String>) : Capability {
        val domains: Set<String> = domains.toSet()
        init {
            require(this.domains.size in 1..128)
            this.domains.forEach(::requireNetworkDomain)
        }
        override fun equals(other: Any?): Boolean = other is Network && domains == other.domains
        override fun hashCode(): Int = domains.hashCode()
    }
}

internal fun requireNetworkDomain(domain: String) {
    require(domain.length <= 253 && Regex(
        "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"
    ).matches(domain)) { "Expected a lowercase DNS hostname without wildcard, port or path" }
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val capabilities: Set<Capability> = emptySet(),
) {
    init {
        require(id.length <= 190 && Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+").matches(id))
        require(name.isNotBlank() && name.length <= 128 && name.none { it.code < 32 })
        require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version))
        Version.parse(version)
    }
}

/** JVM plugin entrypoint. Host authority remains exclusively in PluginContext. */
abstract class AmaliaPlugin : PluginEntrypoint {
    abstract val manifest: PluginManifest
    open val capabilities: Set<Capability> get() = manifest.capabilities
    abstract suspend fun install(api: AmaliaApi)
    /** Called on stop and partially failed installation; cleanup should tolerate partial setup. */
    protected open suspend fun uninstall(api: AmaliaApi) = Unit

    private val lifecycle = Mutex()
    private var active: AmaliaApi? = null

    final override suspend fun start(context: PluginContext) = lifecycle.withLock {
        check(active == null) { "Plugin is already started" }
        val descriptor = manifest.copy(capabilities = capabilities.toSet())
        require(context.identity.packageId == descriptor.id) { "Manifest ID does not match authenticated package" }
        val api = AmaliaApi(context, descriptor)
        active = api
        try {
            install(api)
            api.publish()
        } catch (failure: Throwable) {
            active = null
            cleanup(api, failure)
            throw failure
        }
    }

    final override suspend fun stop() = lifecycle.withLock {
        val api = active ?: return@withLock
        active = null
        cleanup(api, null)?.let { throw it }
        Unit
    }

    private suspend fun cleanup(api: AmaliaApi, original: Throwable?): Throwable? = withContext(NonCancellable) {
        var failure = original
        fun record(error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
        }
        // Withdrawal always precedes user cleanup, and each registration is attempted even if another fails.
        try { api.close() } catch (error: Throwable) { record(error) }
        try { withTimeout(5_000) { uninstall(api) } } catch (error: Throwable) { record(error) }
        failure
    }
}
