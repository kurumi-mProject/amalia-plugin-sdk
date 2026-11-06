package org.amalia.platform.protocol.sdk

import org.amalia.platform.protocol.MediaTrack

/** Plugin supplies resolution, host owns playback. Resolver callbacks never receive Android objects. */
fun interface MediaProvider { suspend fun search(query: String): List<MediaTrack> }
class MediaProviders internal constructor(private val active: () -> Unit) {
    private val providers = linkedMapOf<String, MediaProvider>()
    @Synchronized fun register(id: String, provider: MediaProvider) {
        active(); require(id.isNotBlank() && id.length <= 128 && providers.size < 32 && id !in providers)
        providers[id] = provider
    }
    suspend fun search(id: String, query: String): List<MediaTrack> {
        active(); require(query.isNotBlank() && query.length <= 4096)
        val provider = synchronized(this) { checkNotNull(providers[id]) { "Unknown media provider" } }
        return provider.search(query).also { require(it.size <= 256); active() }
    }
}
