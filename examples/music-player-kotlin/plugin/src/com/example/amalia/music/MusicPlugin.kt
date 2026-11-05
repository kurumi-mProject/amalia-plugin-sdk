package com.example.amalia.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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

/**
 * Music Player — плагин управления системным медиаплеером.
 *
 * Включается/выключается как обычный плагин в Настройках.
 * Управляет любым плеером на телефоне (Spotify, VK Music, AIMP, etc.)
 * через MediaSession API + dispatchMediaKeyEvent.
 *
 * Голосовые команды: «включи музыку», «пауза», «следующий», «что играет».
 * Уведомление с кнопками ▶ ⏸ ⏭ ⏮.
 * Приглушение при речи Амалии.
 */
class MusicPlugin :
    AmaliaPlugin,
    CommandContributor,
    NotificationContributor,
    NotificationActionHandler,
    BackgroundContributor,
    AudioSessionContributor,
    CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.example.amalia.music",
        name = "Music Player",
        version = "1.0.0",
        author = "example",
        description = "Управление музыкой голосом и через уведомление",
        category = PluginCategory.GENERAL,
        iconName = "wave",
    )

    private lateinit var player: MusicController

    override fun onLoaded(context: PluginContext) {
        player = MusicController(context.appContext)
    }

    override fun requiredCapabilities(): List<String> = listOf(
        "audio.music",
        "notifications.post",
        "notifications.actions",
        "system.background",
        "audio.duck",
    )

    // ════════════════════════════════════════════════════════════════
    //  ГОЛОСОВЫЕ КОМАНДЫ
    // ════════════════════════════════════════════════════════════════

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val p = normalize(phrase)

        if (p in PLAY_PHRASES) {
            player.play()
            val track = player.currentTrack()
            return CommandResult.Handled(track?.spokenLabel() ?: "Включаю музыку")
        }

        if (p in PAUSE_PHRASES) {
            player.pause()
            return CommandResult.Handled("Поставила на паузу")
        }

        if (p in TOGGLE_PHRASES) {
            player.playPause()
            return CommandResult.Handled(if (player.isPlaying()) "Возобновила" else "Пауза")
        }

        if (p in NEXT_PHRASES) {
            player.next()
            return CommandResult.Handled("Следующий")
        }

        if (p in PREV_PHRASES) {
            player.previous()
            return CommandResult.Handled("Предыдущий")
        }

        if (p in STOP_PHRASES) {
            player.stop()
            return CommandResult.Handled("Остановила")
        }

        if (p in NOW_PLAYING_PHRASES) {
            val track = player.currentTrack()
            return CommandResult.Handled(track?.spokenLabel() ?: "Сейчас ничего не играет")
        }

        return CommandResult.NotMine
    }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(TRAILING_PUNCT, "").trim()

    // ════════════════════════════════════════════════════════════════
    //  УВЕДОМЛЕНИЕ
    // ════════════════════════════════════════════════════════════════

    override suspend fun post(host: NotificationHost) {
        val track = player.currentTrack()
        if (track == null) {
            host.cancel(NOTIF_ID)
            return
        }
        host.notify(NOTIF_ID, NotificationSpec(
            titleRes = 0, textRes = 0,
            title = track.title,
            text = track.artist + if (track.album != null) " · ${track.album}" else "",
            onGoing = track.isPlaying,
            actions = buildActions(track.isPlaying),
        ))
    }

    private fun buildActions(isPlaying: Boolean): List<ActionSpec> = listOf(
        ActionSpec("prev", android.R.drawable.ic_media_previous, 0, "⏮"),
        ActionSpec("toggle", if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, 0,
            if (isPlaying) "⏸" else "▶"),
        ActionSpec("next", android.R.drawable.ic_media_next, 0, "⏭"),
    )

    override suspend fun onAction(actionId: String) {
        when (actionId) {
            "toggle" -> player.playPause()
            "next" -> player.next()
            "prev" -> player.previous()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ФОНОВОЕ СОСТОЯНИЕ
    // ════════════════════════════════════════════════════════════════

    override suspend fun state(): BackgroundState {
        if (!player.isActive()) return BackgroundState.Idle
        val track = player.currentTrack()
        return BackgroundState.Active(
            titleRes = 0, textRes = 0,
            title = track?.title ?: "Музыка",
            text = track?.artist ?: "",
            actions = buildActions(player.isPlaying()),
            foregroundType = BackgroundWorkType.MEDIA_PLAYBACK,
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  ПРИГЛУШЕНИЕ
    // ════════════════════════════════════════════════════════════════

    override fun session(host: AudioSessionHost): AudioSessionHandler {
        val am = player.audioManager()
        return object : AudioSessionHandler {
            override fun onAssistantSpeaking(duckVolume: Float) {
                if (player.isPlaying()) player.pause()
            }
            override fun onAssistantDone() {
                player.play()
            }
            override fun release() {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  КОНСТАНТЫ
    // ════════════════════════════════════════════════════════════════

    private companion object {
        const val NOTIF_ID = "music_player"
        val TRAILING_PUNCT = Regex("[.,!?…]+$")

        val PLAY_PHRASES = setOf(
            "включи музыку", "включи музыку пожалуйста",
            "играй музыку", "воспроизведи", "play", "play music",
        )
        val PAUSE_PHRASES = setOf(
            "пауза", "поставь на паузу", "поставь паузу", "приостанови", "pause",
        )
        val TOGGLE_PHRASES = setOf("музыка", "стоп музыка")
        val NEXT_PHRASES = setOf(
            "следующий", "следующий трек", "дальше", "следующая песня", "next", "next track",
        )
        val PREV_PHRASES = setOf(
            "предыдущий", "предыдущий трек", "назад", "предыдущая песня", "previous", "prev",
        )
        val STOP_PHRASES = setOf("стоп", "останови музыку", "выключи музыку", "stop")
        val NOW_PLAYING_PHRASES = setOf(
            "что играет", "что сейчас играет", "какая песня", "какой трек",
            "что это за песня", "кто поёт", "what's playing", "what is playing",
        )
    }
}
