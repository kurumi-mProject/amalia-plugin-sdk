package com.example.amalia.music

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * MusicController — тонкая обёртка над Android MediaSession API.
 *
 * Мы не создаём свой медиаплеер — мы управляем тем, который уже играет.
 * Android позволяет это через MediaSessionManager: он знает обо всех
 * активных сессиях и даёт нам контроль над ними при наличии
 * MEDIA_CONTENT_CONTROL или Notification Listener Service.
 *
 * Для плагина мы идём по более простому пути: используем AudioManager
 * dispatchMediaKeyEvent — это транспортные клавиши, не требующие
 * специальных разрешений. Плюс медиа-кнопки на Bluetooth-гарнитуре
 * работают ровно так же, так что поведение будет ожидаемым.
 *
 * Текущий трек берётся из активных MediaSession-ов — это то, что
 * медиаплеер публикует сам (Spotify, YouTube Music, VK Music, AIMP, etc.)
 */
class MusicController(private val context: Context) {

    // ── Получение активной сессии ─────────────────────────────────────

    /**
     * Находит первую активную медиасессию, у которой есть воспроизведение.
     * Возвращает null, если ничего не играет и не стоит на паузе.
     */
    private fun activeController(): MediaController? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager ?: return null
        val notifListenerComponent = ComponentName(context, MusicNotifListenerShim::class.java)
        return try {
            msm.getActiveSessions(notifListenerComponent).firstOrNull { ctrl ->
                val state = ctrl.playbackState?.state
                state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
            }
        } catch (_: SecurityException) {
            // Нет разрешения NotificationListenerService — тихо возвращаем null
            null
        }
    }

    // ── Транспортные команды ──────────────────────────────────────────

    fun play() {
        activeController()?.transportControls?.play()
            ?: dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() {
        activeController()?.transportControls?.pause()
            ?: dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun playPause() {
        val ctrl = activeController()
        if (ctrl != null) {
            when (ctrl.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> ctrl.transportControls.pause()
                else -> ctrl.transportControls.play()
            }
        } else {
            dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun next() {
        activeController()?.transportControls?.skipToNext()
            ?: dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        activeController()?.transportControls?.skipToPrevious()
            ?: dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun stop() {
        activeController()?.transportControls?.stop()
            ?: dispatchKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
    }

    // ── Состояние ─────────────────────────────────────────────────────

    fun isPlaying(): Boolean =
        activeController()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun isPaused(): Boolean =
        activeController()?.playbackState?.state == PlaybackState.STATE_PAUSED

    fun isActive(): Boolean = isPlaying() || isPaused()

    /**
     * Информация о текущем треке из метаданных активной сессии.
     * Возвращает null если ничего не играет.
     */
    fun currentTrack(): TrackInfo? {
        val ctrl = activeController() ?: return null
        val meta = ctrl.metadata ?: return null
        return TrackInfo(
            title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?: "Неизвестный трек",
            artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Неизвестный исполнитель",
            album = meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            isPlaying = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING,
            appLabel = ctrl.packageName?.let { pkg ->
                runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                }.getOrNull()
            },
        )
    }

    // ── Утилиты ───────────────────────────────────────────────────────

    private fun dispatchKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        )
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        )
    }
}

/** Снимок состояния текущего трека. */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val isPlaying: Boolean,
    /** Имя приложения-плеера (Spotify, VK Music и т.д.) */
    val appLabel: String?,
) {
    /** Однострочное описание для уведомления: «Artist — Title». */
    fun shortLabel(): String = "$artist — $title"

    /** Строка для озвучки Амалией: «играет X от Y». */
    fun spokenLabel(): String = "играет «$title» — $artist"
}
