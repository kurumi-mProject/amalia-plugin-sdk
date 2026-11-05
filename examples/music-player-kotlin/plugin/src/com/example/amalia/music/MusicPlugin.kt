package com.example.amalia.music

import android.content.Context
import android.media.AudioManager
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

/**
 * Music Player — управление системным медиаплеером через Амалию.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧТО УМЕЕТ ПЛАГИН
 * ════════════════════════════════════════════════════════════════════
 *
 *  • Голосовые команды — «включи музыку», «пауза», «следующий трек»,
 *    «что играет» и т.д. Обрабатываются до LLM: мгновенно и без сети.
 *
 *  • Инструмент для модели (tool calling) — если пользователь говорит
 *    «включи что-нибудь спокойное», модель сама вызовет tool music_control.
 *    Этот путь не перехватывает фразу, а только даёт модели возможность.
 *
 *  • Красивое уведомление — «Artist — Title» с кнопками ▶/⏸/⏭/⏮.
 *    Показывается пока есть активный медиаплеер.
 *
 *  • BackgroundContributor — держит foreground service, пока музыка идёт,
 *    чтобы уведомление жило.
 *
 *  • AudioSessionContributor — приглушает музыку пока Амалия говорит.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧТО НЕ УМЕЕТ
 * ════════════════════════════════════════════════════════════════════
 *
 *  • Запустить конкретное приложение или открыть Spotify по названию —
 *    для этого нужен Intent + capability "system.launch_app".
 *  • Управлять громкостью — вне контракта, используй системные ползунки.
 *
 * ════════════════════════════════════════════════════════════════════
 *  РАЗРЕШЕНИЯ
 * ════════════════════════════════════════════════════════════════════
 *
 *  audio.music                   — обязательное: play/pause/next/prev
 *  system.notification_listener  — опциональное: чтение метаданных трека
 *                                  (название, исполнитель)
 *  notifications.post            — для уведомления
 *  notifications.actions         — для кнопок в уведомлении
 *  system.background             — чтобы держать foreground service
 *  audio.duck                    — приглушение при речи Амалии
 */
class MusicPlugin :
    AmaliaPlugin,
    CommandContributor,
    ToolContributor,
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

    // ── Жизненный цикл ───────────────────────────────────────────────

    override fun onLoaded(context: PluginContext) {
        player = MusicController(context.appContext)
    }

    override fun onDisabled() {
        // AudioSessionContributor.release() вызывается хостом автоматически.
        // Уведомление исчезнет само, когда BackgroundContributor вернёт Idle.
    }

    // ── Объявляем нужные возможности ─────────────────────────────────

    override fun requiredCapabilities(): List<String> = listOf(
        "audio.music",               // транспортные команды MediaSession
        "system.notification_listener", // чтение метаданных трека (опционально)
        "notifications.post",        // уведомление
        "notifications.actions",     // кнопки в уведомлении
        "system.background",         // foreground service
        "audio.duck",                // приглушение при речи
    )

    // ════════════════════════════════════════════════════════════════
    //  ГОЛОСОВЫЕ КОМАНДЫ (до LLM — быстро, без сети)
    // ════════════════════════════════════════════════════════════════

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val p = normalize(phrase)

        // Воспроизведение
        if (p in PLAY_PHRASES) {
            player.play()
            val track = player.currentTrack()
            return CommandResult.Handled(
                spoken = track?.spokenLabel() ?: "Включаю музыку"
            )
        }

        // Пауза
        if (p in PAUSE_PHRASES) {
            player.pause()
            return CommandResult.Handled(spoken = "Поставила на паузу")
        }

        // Play/pause toggle
        if (p in TOGGLE_PHRASES) {
            player.playPause()
            return CommandResult.Handled(
                spoken = if (player.isPlaying()) "Возобновила" else "Пауза"
            )
        }

        // Следующий трек
        if (p in NEXT_PHRASES) {
            player.next()
            return CommandResult.Handled(spoken = "Следующий")
        }

        // Предыдущий трек
        if (p in PREV_PHRASES) {
            player.previous()
            return CommandResult.Handled(spoken = "Предыдущий")
        }

        // Стоп
        if (p in STOP_PHRASES) {
            player.stop()
            return CommandResult.Handled(spoken = "Остановила")
        }

        // Что сейчас играет
        if (p in NOW_PLAYING_PHRASES) {
            val track = player.currentTrack()
            return CommandResult.Handled(
                spoken = track?.spokenLabel() ?: "Сейчас ничего не играет"
            )
        }

        return CommandResult.NotMine
    }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(TRAILING_PUNCT, "").trim()

    // ════════════════════════════════════════════════════════════════
    //  ИНСТРУМЕНТ ДЛЯ МОДЕЛИ (tool calling)
    // ════════════════════════════════════════════════════════════════

    override fun tools(context: PluginContext): List<AmaliaTool> = listOf(musicControl())

    private fun musicControl() = AmaliaTool(
        definition = ToolDefinition(
            name = "music_control",
            description = "Управляет системным медиаплеером. " +
                "Используй когда пользователь хочет включить/выключить/переключить музыку, " +
                "но фраза не попала в голосовые команды — например «включи что-нибудь» или " +
                "«поставь на паузу». " +
                "Действия: play, pause, next, previous, stop, status.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = ToolParameter.JsonType.STRING,
                    description = "Что сделать с плеером",
                    enumValues = listOf("play", "pause", "next", "previous", "stop", "status"),
                ),
            ),
        ),
        handler = { args -> handleTool(args) },
    )

    private fun handleTool(args: ToolArguments): ToolOutcome {
        return when (args.string("action")) {
            "play" -> {
                player.play()
                val t = player.currentTrack()
                ToolOutcome.json(
                    "ok" to true,
                    "track" to (t?.shortLabel() ?: ""),
                    "message" to (t?.spokenLabel() ?: "Музыка включена"),
                )
            }
            "pause" -> {
                player.pause()
                ToolOutcome.json("ok" to true, "message" to "Поставила на паузу")
            }
            "next" -> {
                player.next()
                ToolOutcome.json("ok" to true, "message" to "Следующий трек")
            }
            "previous" -> {
                player.previous()
                ToolOutcome.json("ok" to true, "message" to "Предыдущий трек")
            }
            "stop" -> {
                player.stop()
                ToolOutcome.json("ok" to true, "message" to "Остановлено")
            }
            "status" -> {
                val t = player.currentTrack()
                if (t != null) {
                    ToolOutcome.json(
                        "playing" to t.isPlaying,
                        "title" to t.title,
                        "artist" to t.artist,
                        "album" to (t.album ?: ""),
                        "app" to (t.appLabel ?: ""),
                        "message" to t.spokenLabel(),
                    )
                } else {
                    ToolOutcome.json(
                        "playing" to false,
                        "message" to "Сейчас ничего не играет",
                    )
                }
            }
            else -> ToolOutcome.failed("Неизвестное действие")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  УВЕДОМЛЕНИЕ — «Artist — Title» с кнопками ▶ ⏸ ⏭ ⏮
    // ════════════════════════════════════════════════════════════════

    override suspend fun post(host: NotificationHost) {
        val track = player.currentTrack()

        if (track == null) {
            host.cancel(NOTIF_ID)
            return
        }

        host.notify(
            NOTIF_ID,
            NotificationSpec(
                // titleRes и textRes = 0, потому что мы передаём готовые строки:
                // название трека динамическое, ресурсом не сделаешь.
                titleRes = 0,
                textRes = 0,
                title = track.title,
                text = track.artist + if (track.album != null) " · ${track.album}" else "",
                onGoing = track.isPlaying,
                actions = buildActions(track.isPlaying),
            ),
        )
    }

    private fun buildActions(isPlaying: Boolean): List<ActionSpec> = listOf(
        ActionSpec(
            id = ACTION_PREV,
            iconRes = android.R.drawable.ic_media_previous,
            titleRes = 0,
            label = "⏮",
        ),
        ActionSpec(
            id = ACTION_PLAY_PAUSE,
            iconRes = if (isPlaying)
                android.R.drawable.ic_media_pause
            else
                android.R.drawable.ic_media_play,
            titleRes = 0,
            label = if (isPlaying) "⏸" else "▶",
        ),
        ActionSpec(
            id = ACTION_NEXT,
            iconRes = android.R.drawable.ic_media_next,
            titleRes = 0,
            label = "⏭",
        ),
    )

    // ════════════════════════════════════════════════════════════════
    //  НАЖАТИЯ КНОПОК В УВЕДОМЛЕНИИ
    // ════════════════════════════════════════════════════════════════

    override suspend fun onAction(actionId: String) {
        when (actionId) {
            ACTION_PLAY_PAUSE -> player.playPause()
            ACTION_NEXT       -> player.next()
            ACTION_PREV       -> player.previous()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ФОНОВОЕ СОСТОЯНИЕ — держит foreground service пока музыка идёт
    // ════════════════════════════════════════════════════════════════

    override suspend fun state(): BackgroundState {
        if (!player.isActive()) return BackgroundState.Idle
        val track = player.currentTrack()
        return BackgroundState.Active(
            titleRes = 0,
            textRes = 0,
            title = track?.title ?: "Музыка",
            text = track?.artist ?: "",
            actions = buildActions(player.isPlaying()),
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  ПРИГЛУШЕНИЕ МУЗЫКИ ПОКА АМАЛИЯ ГОВОРИТ
    // ════════════════════════════════════════════════════════════════

    override fun session(host: AudioSessionHost): AudioSessionHandler {
        val am = (host as? Context)?.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager

        return object : AudioSessionHandler {
            override fun onAssistantSpeaking(duckVolume: Float) {
                // Если есть доступ к AudioManager — запрашиваем duck-фокус.
                // Система сама снизит громкость играющего плеера.
                // Если нет — ставим паузу, чтобы Амалию было слышно.
                if (am != null) {
                    am.requestAudioFocus(
                        null,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    )
                } else if (player.isPlaying()) {
                    player.pause()
                }
            }

            override fun onAssistantDone() {
                // Отпускаем фокус — плеер сам вернётся к обычной громкости.
                am?.abandonAudioFocus(null) ?: player.play()
            }

            override fun release() {
                am?.abandonAudioFocus(null)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  КОНСТАНТЫ
    // ════════════════════════════════════════════════════════════════

    private companion object {
        const val NOTIF_ID = "music_player"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"

        val TRAILING_PUNCT = Regex("[.,!?…]+$")

        val PLAY_PHRASES = setOf(
            "включи музыку", "включи музыку пожалуйста",
            "играй музыку", "воспроизведи",
            "play", "play music",
        )
        val PAUSE_PHRASES = setOf(
            "пауза", "поставь на паузу",
            "поставь паузу", "приостанови",
            "pause",
        )
        val TOGGLE_PHRASES = setOf(
            "музыка", "стоп музыка",
        )
        val NEXT_PHRASES = setOf(
            "следующий", "следующий трек",
            "дальше", "следующая песня",
            "next", "next track",
        )
        val PREV_PHRASES = setOf(
            "предыдущий", "предыдущий трек",
            "назад", "предыдущая песня",
            "previous", "prev",
        )
        val STOP_PHRASES = setOf(
            "стоп", "останови музыку",
            "выключи музыку", "stop",
        )
        val NOW_PLAYING_PHRASES = setOf(
            "что играет", "что сейчас играет",
            "какая песня", "какой трек",
            "что это за песня", "кто поёт",
            "what's playing", "what is playing",
        )
    }
}
