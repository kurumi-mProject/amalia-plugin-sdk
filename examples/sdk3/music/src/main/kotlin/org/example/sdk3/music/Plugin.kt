package org.example.sdk3.music

import amalia.generated.GeneratedManifest
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*
import org.amalia.platform.protocol.sdk.*

class Plugin : AmaliaPlugin() {
    override val manifest = GeneratedManifest.value
    override suspend fun install(api: AmaliaApi) {
        val player = api.media.createPlayer("music", manifest.name)
        api.tools.register("select", "Play a reviewed HTTPS audio track using host playback") {
            string("url", "Direct MP3, Ogg, FLAC or WAV URL on a reviewed domain")
            string("title", "Track title")
            execute {
                player.setQueue(listOf(MediaTrack("selection", string("title"), string("url"))))
                player.play()
                buildJsonObject { put("status", player.state.value.status.name) }
            }
        }
        api.tools.register("pause", "Pause host playback") { execute { player.pause(); JsonPrimitive("paused") } }
        api.tools.register("selection", "Read host playback state") {
            effect = OperationEffect.READ
            execute { buildJsonObject {
                put("status", player.state.value.status.name)
                put("title", player.state.value.currentTrack?.title)
                put("positionMillis", player.state.value.positionMillis)
            } }
        }
    }
}
