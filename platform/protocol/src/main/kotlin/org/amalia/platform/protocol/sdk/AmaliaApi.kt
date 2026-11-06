package org.amalia.platform.protocol.sdk

import java.net.URI
import org.amalia.platform.protocol.*

/** Convenience over the existing host-owned data/settings namespaces. */
class AmaliaStorage internal constructor(private val delegate: PluginStorage, private val checkActive: () -> Unit) : PluginStorage {
    private fun guarded(namespace: StorageNamespace) = object : StorageNamespace {
        override fun read(key: String): String? { checkActive(); return namespace.read(key) }
        override fun write(key: String, value: String) { checkActive(); namespace.write(key, value) }
        override fun delete(key: String) { checkActive(); namespace.delete(key) }
    }
    override val data = guarded(delegate.data)
    override val settings = guarded(delegate.settings)
    fun getString(key: String, defaultValue: String? = null): String? = data.read(key) ?: defaultValue
    fun putString(key: String, value: String) = data.write(key, value)
    fun delete(key: String) = data.delete(key)
    fun <T> dataStore(key: String, serializer: kotlinx.serialization.KSerializer<T>, default: () -> T): PluginDataStore<T> =
        PluginDataStore(data, key, serializer, default)
}

class PluginDataStore<T> internal constructor(private val namespace: StorageNamespace, private val key: String,
    private val serializer: kotlinx.serialization.KSerializer<T>, private val default: () -> T) {
    fun read(): T = namespace.read(key)?.let { kotlinx.serialization.json.Json.decodeFromString(serializer, it) } ?: default()
    fun write(value: T) = namespace.write(key, kotlinx.serialization.json.Json.encodeToString(serializer, value))
    fun delete() = namespace.delete(key)
}

class AmaliaApi internal constructor(private val context: PluginContext, val manifest: PluginManifest) : AutoCloseable {
    @Volatile private var closed = false
    private val registrations = mutableListOf<Registration>()
    private fun ensureActive() { check(!closed) { "Plugin API activation is closed" } }
    val storage = AmaliaStorage(context.storage, ::ensureActive)
    val tools = Tools(manifest, ::ensureActive)
    /** Host implementations enforce independently reviewed capabilities on every operation. */
    private val players = mutableListOf<MediaPlayerHandle>()
    val media: MediaAccess = object : MediaAccess {
        override fun createPlayer(id: String, title: String): MediaPlayerHandle {
            ensureActive(); requireCapability("background.media")
            return context.media.createPlayer(id, title).also { synchronized(players) { players += it } }
        }
    }
    val notifications get() = context.services.notifications.also { ensureActive(); requireCapability("notifications") }
    val secrets get() = context.services.secrets.also { ensureActive(); requireCapability("secrets") }
    val browser get() = context.services.browser.also { ensureActive(); requireCapability("browser") }
    val device get() = context.services.device.also { ensureActive() }
    val background get() = context.services.background.also { ensureActive(); requireCapability("background.short") }
    val scheduler get() = context.services.scheduler.also { ensureActive(); requireCapability("scheduler") }
    val ui: PluginUiAccess = object : PluginUiAccess {
        override fun registerScreen(route: String, title: String, document: org.amalia.platform.protocol.ui.UiDocument): Registration {
            ensureActive(); requireCapability("ui")
            return context.ui.registerScreen(route, title, document).also { registrations += it }
        }
    }
    private fun requireCapability(token: String) {
        if (token !in CapabilityTokens.from(manifest.capabilities)) throw SecurityException("Capability not declared: $token")
    }
    val mediaProviders = MediaProviders(::ensureActive)
    val commands = Commands(tools)
    val events = PluginEvents(::ensureActive)
    /** Exact HTTPS host match; redirects remain the trusted host transport's responsibility. */
    val network: NetworkAccess = object : NetworkAccess {
        private val domains = manifest.capabilities.filterIsInstance<Capability.Network>().flatMap { it.domains }.toSet()
        override suspend fun get(url: String, headers: Map<String, String>): NetworkResponse {
            ensureActive()
            val uri = URI(url)
            val host = uri.host?.lowercase(java.util.Locale.ROOT)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.fragment != null ||
                uri.port !in setOf(-1, 443) || host == null || host !in domains) {
                throw SecurityException("URL is outside declared HTTPS network domains")
            }
            // A declaration only narrows access; the host must independently authorize each request.
            return context.network.get(url, headers).also { ensureActive() }
        }
    }

    internal suspend fun publish() {
        ensureActive()
        val contract = tools.freeze() ?: return
        registrations += context.registerContract(contract)
        val identity = context.identity
        registrations += context.registerProvider(ProviderDescriptor(
            id = "${manifest.id}.local", publisherId = identity.publisherId, packageId = identity.packageId,
            packageVersion = Version.parse(manifest.version), deviceId = identity.deviceId,
            activationGeneration = context.activationGeneration, displayName = manifest.name,
            contracts = listOf(ContractOffer(contract.id, contract.version)),
        ), tools.handler())
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        registrations.asReversed().forEach {
            try { it.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        registrations.clear()
        synchronized(players) {
            players.forEach { player -> try { player.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            } }
            players.clear()
        }
        failure?.let { throw it }
    }
}
