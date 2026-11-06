package com.amalia.youtube.music

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.my.amali.data.ai.AmaliaTool
import com.my.amali.data.ai.ToolArguments
import com.my.amali.data.ai.ToolDefinition
import com.my.amali.data.ai.ToolOutcome
import com.my.amali.data.ai.ToolParameter
import com.my.amali.plugin.ActionSpec
import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.AudioSessionContributor
import com.my.amali.plugin.AudioSessionHandler
import com.my.amali.plugin.AudioSessionHost
import com.my.amali.plugin.BackgroundContributor
import com.my.amali.plugin.BackgroundState
import com.my.amali.plugin.BackgroundWorkType
import com.my.amali.plugin.CapabilityContributor
import com.my.amali.plugin.CommandContributor
import com.my.amali.plugin.CommandResult
import com.my.amali.plugin.NotificationActionHandler
import com.my.amali.plugin.NotificationContributor
import com.my.amali.plugin.NotificationHost
import com.my.amali.plugin.NotificationSpec
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginContext
import com.my.amali.plugin.PluginMetadata
import com.my.amali.plugin.ToolContributor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class YouTubeMusicPlugin :
    AmaliaPlugin, CommandContributor, ToolContributor, NotificationContributor,
    NotificationActionHandler, BackgroundContributor, AudioSessionContributor, CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.amalia.youtube.music", name = "YouTube Music", version = "1.0.0",
        author = "Amalia community", description = "Поиск и фоновое воспроизведение музыки с YouTube",
        category = PluginCategory.GENERAL, iconName = "wave", minHostVersion = "2.1.0",
    )

    private lateinit var player: ExoPlayer
    private lateinit var scope: CoroutineScope
    private var current: YouTubeTrack? = null
    private var preparing = false
    private var savedVolume = 1f

    override fun onLoaded(context: PluginContext) {
        val app = context.appContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = ExoPlayer.Builder(app).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        ).build()
    }

    override fun onDisabled() { release() }
    override fun onUnloaded() { release() }

    private fun release() {
        if (::scope.isInitialized) scope.cancel()
        if (::player.isInitialized) player.release()
    }

    override fun requiredCapabilities() = listOf("system.background", "notifications.post", "notifications.actions", "audio.duck")

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val text = phrase.trim()
        val normalized = text.lowercase()
        return when {
            normalized == "пауза" || normalized == "поставь на паузу" -> { player.pause(); CommandResult.Handled("Поставила на паузу") }
            normalized == "продолжи" || normalized == "возобнови музыку" -> { player.play(); CommandResult.Handled("Продолжаю") }
            normalized == "останови музыку" || normalized == "выключи музыку" -> { player.stop(); CommandResult.Handled("Остановила музыку") }
            normalized == "что играет" -> CommandResult.Handled(current?.title ?: "Сейчас ничего не играет")
            normalized.startsWith("включи ") -> play(text.substringAfter(" ").trim())
            normalized.startsWith("поставь ") -> play(text.substringAfter(" ").trim())
            else -> CommandResult.NotMine
        }
    }

    private fun play(query: String): CommandResult {
        if (query.isBlank()) return CommandResult.Handled("Скажи название песни или исполнителя")
        preparing = true
        scope.launch {
            val track = YouTubeRepository.findAudio(query)
            preparing = false
            if (track == null) return@launch
            current = track
            player.setMediaItem(MediaItem.fromUri(track.url))
            player.prepare()
            player.play()
        }
        return CommandResult.Handled("Ищу и включаю")
    }

    override fun tools(context: PluginContext) = listOf(AmaliaTool(
        ToolDefinition(
            name = "youtube_music_play",
            description = "Найти песню или исполнителя на YouTube и включить аудио в фоне.",
            parameters = listOf(ToolParameter("query", ToolParameter.JsonType.STRING, "Название песни и исполнитель")),
        ),
    ) { args ->
        val query = args.string("query")
        if (query.isBlank()) ToolOutcome.failed("Не указано название")
        else { play(query); ToolOutcome.json("ok" to true, "message" to "Ищу и включаю") }
    })

    override suspend fun post(host: NotificationHost) {
        val track = current ?: run { host.cancel(NOTIFICATION_ID); return }
        host.notify(
            NOTIFICATION_ID,
            NotificationSpec(
                titleRes = 0,
                textRes = 0,
                onGoing = player.isPlaying,
                actions = actions(player.isPlaying),
                title = track.title,
                text = "YouTube",
            ),
        )
    }

    override suspend fun onAction(actionId: String) {
        when (actionId) { ACTION_PAUSE -> player.pause(); ACTION_PLAY -> player.play(); ACTION_STOP -> player.stop() }
    }

    override suspend fun state(): BackgroundState {
        if (!preparing && !player.isPlaying && current == null) return BackgroundState.Idle
        val track = current
        return BackgroundState.Active(0, 0, actions(player.isPlaying), track?.title ?: "YouTube Music", "Поиск или воспроизведение", BackgroundWorkType.MEDIA_PLAYBACK)
    }

    override fun session(host: AudioSessionHost) = object : AudioSessionHandler {
        override fun onAssistantSpeaking(duckVolume: Float) {
            savedVolume = player.volume
            player.volume = savedVolume * duckVolume.coerceIn(0f, 1f)
        }
        override fun onAssistantDone() { player.volume = savedVolume }
        override fun release() { player.volume = 1f }
    }

    private fun actions(playing: Boolean) = listOf(
        ActionSpec(ACTION_PLAY, android.R.drawable.ic_media_play, 0, "▶"),
        ActionSpec(ACTION_PAUSE, android.R.drawable.ic_media_pause, 0, "⏸"),
        ActionSpec(ACTION_STOP, android.R.drawable.ic_media_stop, 0, "⏹"),
    )

    private companion object {
        const val NOTIFICATION_ID = "youtube_music"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
    }
}
