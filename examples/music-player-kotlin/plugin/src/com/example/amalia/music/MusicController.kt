package com.example.amalia.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Обёртка над Android MediaSession API.
 * Управляет любым активным плеером (Spotify, VK Music, AIMP, etc.)
 */
class MusicController(private val context: Context) {

    private fun activeController(): MediaController? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? MediaSessionManager ?: return null
        return try {
            // Пробуем без NotificationListener — на Android 14+ работает
            msm.getActiveSessions(null).firstOrNull { ctrl ->
                val state = ctrl.playbackState?.state
                state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
            }
        } catch (_: SecurityException) {
            null
        }
    }

    fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // ── Транспорт ───────────────────────────────────────────────────

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

    // ── Состояние ───────────────────────────────────────────────────

    fun isPlaying(): Boolean =
        activeController()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun isActive(): Boolean = isPlaying() ||
        activeController()?.playbackState?.state == PlaybackState.STATE_PAUSED

    fun currentTrack(): TrackInfo? {
        val ctrl = activeController() ?: return null
        val meta = ctrl.metadata ?: return null
        return TrackInfo(
            title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Неизвестный трек",
            artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Неизвестный исполнитель",
            album = meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            isPlaying = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun dispatchKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        )
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        )
    }
}

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val isPlaying: Boolean,
) {
    fun shortLabel(): String = "$artist — $title"
    fun spokenLabel(): String = "играет «$title» — $artist"
}
