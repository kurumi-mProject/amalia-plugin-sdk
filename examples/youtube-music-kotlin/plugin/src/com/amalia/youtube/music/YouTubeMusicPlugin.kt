package com.amalia.youtube.music

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.my.amali.data.ai.AmaliaTool
import com.my.amali.data.ai.ToolDefinition
import com.my.amali.data.ai.ToolOutcome
import com.my.amali.data.ai.ToolParameter
import com.my.amali.plugin.*
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Uses the host's mediaPlayback foreground service; ZIP plugins cannot register services. */
class YouTubeMusicPlugin :
    AmaliaPlugin, CommandContributor, ToolContributor, NotificationContributor,
    NotificationActionHandler, BackgroundContributor, AudioSessionContributor, CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.amalia.youtube.music", name = "YouTube Music", version = "1.0.0",
        author = "Amalia community", description = "Поиск и фоновое воспроизведение музыки с YouTube",
        category = PluginCategory.GENERAL, iconName = "wave", minHostVersion = "2.1.0",
    )

    private lateinit var context: PluginContext
    private var player: ExoPlayer? = null
    private var scope: CoroutineScope? = null
    private var pending: Deferred<String>? = null
    private var current: YouTubeTrack? = null
    private var preparing = false
    private var enabled = false
    private var requestId = 0L
    private var resolvedAt = 0L
    private var error: String? = null
    private var ducked = false
    private var duckVolume = 0.25f
    private var savedVolume = 1f
    private var notificationHost: NotificationHost? = null

    override fun onLoaded(context: PluginContext) { this.context = context }

    override fun onEnabled() {
        enabled = true
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDisabled() = release()
    override fun onUnloaded() = release()

    private fun release() {
        enabled = false
        requestId++
        pending?.cancel()
        pending = null
        scope?.cancel()
        scope = null
        player?.release()
        player = null
        current = null
        preparing = false
        error = null
        ducked = false
        savedVolume = 1f
        notificationHost?.cancel(NOTIFICATION_ID)
        notificationHost = null
    }

    private fun getPlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context.appContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build().also { created ->
            player = created
            created.volume = if (ducked) savedVolume * duckVolume else savedVolume
            created.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publishNotification() }
                override fun onPlayerError(playbackError: PlaybackException) {
                    // Never log exception messages: they may contain signed stream URLs.
                    Log.w(TAG, "Playback failed: ${playbackError.errorCode}")
                    error = "Не удалось воспроизвести трек. Скажи «продолжи музыку», чтобы обновить ссылку."
                    preparing = false
                    publishNotification()
                }
            })
        }

    override fun requiredCapabilities() = listOf(
        "system.background", "notifications.post", "notifications.actions", "audio.duck",
    )

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult =
        withContext(Dispatchers.Main.immediate) {
            if (!enabled) return@withContext CommandResult.NotMine
            val text = phrase.trim().trimEnd('.', '!', '?')
            val normalized = text.lowercase(Locale.ROOT)
            val hasSession = preparing || current != null
            when {
                normalized in STOP_PHRASES && hasSession -> CommandResult.Handled(stop())
                normalized in PAUSE_PHRASES && hasSession -> CommandResult.Handled(pause())
                normalized in RESUME_PHRASES && hasSession -> CommandResult.Handled(resume())
                normalized in STATUS_PHRASES -> CommandResult.Handled(status())
                MUSIC_QUERY.containsMatchIn(text) -> {
                    val query = MUSIC_QUERY.replaceFirst(text, "").trim()
                    CommandResult.Handled(play(query))
                }
                // Do not swallow «включи фонарик», «поставь таймер» and other device commands.
                else -> CommandResult.NotMine
            }
        }

    private suspend fun play(query: String): String {
        if (query.isBlank()) return "Скажи название песни или исполнителя"
        if (query.length > 500) return "Слишком длинный запрос. Назови песню и исполнителя"
        return resolve { YouTubeRepository.findAudio(query) }
    }

    private suspend fun resolve(fetch: suspend () -> YouTubeTrack): String {
        val activeScope = scope ?: return "Плагин выключен"
        pending?.cancel()
        val id = ++requestId
        preparing = true
        error = null
        player?.stop()
        current = null
        publishNotification()
        val task = activeScope.async {
            try {
                // State must be Active before requesting foreground, especially on Android 15+.
                if (!context.services.ensureForeground(metadata.id)) {
                    error = "Не удалось запустить фоновое воспроизведение. Открой Амалию и повтори запрос."
                    return@async error.orEmpty()
                }
                val track = fetch()
                if (id != requestId || !enabled) throw CancellationException()
                current = track
                resolvedAt = SystemClock.elapsedRealtime()
                val mediaItem = MediaItem.Builder().setUri(track.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
                    .build()
                getPlayer().apply {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
                "Включаю: ${track.title}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Track resolution failed: ${failure.javaClass.simpleName}")
                error = when (failure) {
                    is TrackNotFoundException -> "Не нашла трек. Попробуй уточнить песню и исполнителя."
                    is AudioUnavailableException -> "У этого видео нет доступной аудиодорожки. Попробуй другой трек."
                    is ReCaptchaException -> "YouTube временно ограничил запросы. Попробуй позже."
                    is IOException -> "Не удалось подключиться к YouTube. Проверь интернет и повтори запрос."
                    else -> "YouTube не отдал аудио. Попробуй другой трек или обнови плагин."
                }
                current = null
                player?.stop()
                error.orEmpty()
            } finally {
                if (id == requestId) {
                    preparing = false
                    publishNotification()
                }
            }
        }
        pending = task
        return try {
            task.await()
        } finally {
            // A cancelled assistant turn must not leave a detached search playing later.
            if (!task.isCompleted) task.cancel()
            if (pending === task) pending = null
        }
    }

    private fun pause(): String {
        if (preparing) {
            stop()
            return "Поиск отменён"
        }
        val active = player ?: return "Сейчас ничего не играет"
        active.pause()
        publishNotification()
        return "Поставила на паузу"
    }

    private suspend fun resume(): String {
        val track = current ?: return "Сначала назови песню или исполнителя"
        if (preparing) return "Ещё ищу трек"
        val active = player
        if (error != null || active == null ||
            SystemClock.elapsedRealtime() - resolvedAt >= URL_REFRESH_AGE_MS) {
            return resolve { YouTubeRepository.refresh(track) }
        }
        preparing = true
        return try {
            if (!context.services.ensureForeground(metadata.id)) {
                error = "Не удалось запустить фоновое воспроизведение. Открой Амалию и повтори запрос."
                error.orEmpty()
            } else {
                if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
                active.play()
                "Продолжаю"
            }
        } finally {
            preparing = false
            publishNotification()
        }
    }

    private fun stop(): String {
        requestId++
        pending?.cancel()
        pending = null
        preparing = false
        current = null
        error = null
        player?.apply { stop(); clearMediaItems() }
        publishNotification()
        return "Остановила музыку"
    }

    private fun status(): String = when {
        preparing -> "Ищу музыку"
        error != null -> error.orEmpty()
        current == null -> "Сейчас ничего не играет"
        player?.playbackState == Player.STATE_ENDED -> "Трек закончился: ${current?.title}"
        player?.playWhenReady == true -> "Играет: ${current?.title}"
        else -> "На паузе: ${current?.title}"
    }

    override fun tools(context: PluginContext) = listOf(
        AmaliaTool(ToolDefinition(
            name = "youtube_music_play",
            description = "Найти песню или исполнителя на YouTube и включить аудио в фоне.",
            parameters = listOf(ToolParameter("query", ToolParameter.JsonType.STRING, "Песня и исполнитель")),
        )) { args -> withContext(Dispatchers.Main.immediate) {
            if (!enabled) ToolOutcome.failed("Плагин выключен")
            else {
                val query = args.string("query").trim()
                if (query.isBlank() || query.length > 500) {
                    return@withContext ToolOutcome.failed("Назови песню и исполнителя: от 1 до 500 символов")
                }
                val message = play(query)
                if (current == null || error != null) ToolOutcome.failed(message)
                else ToolOutcome.json("status" to "buffering", "title" to current?.title, "message" to message)
            }
        } },
        AmaliaTool(ToolDefinition(
            name = "youtube_music_control",
            description = "Пауза, продолжение, остановка или состояние YouTube-плеера.",
            parameters = listOf(ToolParameter("action", ToolParameter.JsonType.STRING, "Действие",
                enumValues = listOf("pause", "resume", "stop", "status"))),
        )) { args -> withContext(Dispatchers.Main.immediate) {
            if (!enabled) return@withContext ToolOutcome.failed("Плагин выключен")
            val message = when (args.string("action")) {
                "pause" -> pause()
                "resume" -> resume()
                "stop" -> stop()
                "status" -> status()
                else -> return@withContext ToolOutcome.failed("Неизвестное действие")
            }
            ToolOutcome.json("message" to message)
        } },
    )

    override suspend fun post(host: NotificationHost) = withContext(Dispatchers.Main.immediate) {
        notificationHost = host
        publishNotification()
    }

    private fun publishNotification() {
        val host = notificationHost ?: return
        if (!enabled || (!preparing && current == null && error == null)) {
            host.cancel(NOTIFICATION_ID)
            return
        }
        host.notify(NOTIFICATION_ID, NotificationSpec(
            titleRes = 0, textRes = 0,
            onGoing = preparing || (player?.playWhenReady == true &&
                player?.playbackState != Player.STATE_ENDED && error == null),
            actions = actions(),
            title = current?.title ?: "YouTube Music",
            text = status(),
        ))
    }

    override suspend fun onAction(actionId: String) = withContext(Dispatchers.Main.immediate) {
        if (!enabled) return@withContext
        when (actionId) {
            ACTION_PAUSE -> pause()
            ACTION_PLAY -> resume()
            ACTION_STOP -> stop()
        }
        Unit
    }

    override suspend fun state(): BackgroundState = withContext(Dispatchers.Main.immediate) {
        val active = player
        if (!enabled || (!preparing && (current == null || active == null ||
                active.playerError != null || active.playbackState == Player.STATE_ENDED))) {
            BackgroundState.Idle
        } else {
            BackgroundState.Active(
                titleRes = 0, textRes = 0, actions = actions(),
                title = current?.title ?: "YouTube Music", text = status(),
                foregroundType = BackgroundWorkType.MEDIA_PLAYBACK,
            )
        }
    }

    override fun session(host: AudioSessionHost): AudioSessionHandler = object : AudioSessionHandler {
        init { if (host.isSpeaking) onAssistantSpeaking() }
        override fun onAssistantSpeaking(duckVolume: Float) {
            if (!ducked) savedVolume = player?.volume ?: savedVolume
            ducked = true
            this@YouTubeMusicPlugin.duckVolume = duckVolume.coerceIn(0f, 1f)
            player?.volume = savedVolume * this@YouTubeMusicPlugin.duckVolume
        }
        override fun onAssistantDone() {
            if (ducked) player?.volume = savedVolume
            ducked = false
        }
        override fun release() = onAssistantDone()
    }

    private fun actions(): List<ActionSpec> = buildList {
        if (!preparing && current != null) {
            if (player?.playWhenReady == true && error == null) {
                add(ActionSpec(ACTION_PAUSE, android.R.drawable.ic_media_pause, 0, "Пауза"))
            } else {
                add(ActionSpec(ACTION_PLAY, android.R.drawable.ic_media_play, 0, "Продолжить"))
            }
        }
        add(ActionSpec(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, 0, "Остановить"))
    }

    private companion object {
        const val TAG = "YouTubeMusicPlugin"
        const val NOTIFICATION_ID = "youtube_music"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
        const val URL_REFRESH_AGE_MS = 4 * 60 * 60 * 1000L
        val PAUSE_PHRASES = setOf("пауза", "поставь на паузу", "приостанови музыку")
        val RESUME_PHRASES = setOf("продолжи", "продолжи музыку", "возобнови музыку")
        val STOP_PHRASES = setOf("останови музыку", "выключи музыку")
        val STATUS_PHRASES = setOf("что играет", "что сейчас играет")
        val MUSIC_QUERY = Regex("^(?:включи|поставь|сыграй)\\s+(?:песню|трек|музыку)(?:\\s+|$)", RegexOption.IGNORE_CASE)
    }
}
