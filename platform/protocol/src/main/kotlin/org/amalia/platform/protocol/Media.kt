package org.amalia.platform.protocol

import kotlinx.coroutines.flow.StateFlow

/** Host-owned playback, not an Android context or a sandbox for trusted in-process code.
 * Creation and every operation require host approval of background.media. Each source also
 * requires a separate network:<exact-lowercase-host> approval; declarations never grant access.
 */
interface MediaAccess {
    fun createPlayer(id: String, title: String): MediaPlayerHandle
}

interface MediaPlayerHandle : AutoCloseable {
    val state: StateFlow<MediaPlaybackState>
    /** Replaces the queue and stops playback. Queue order is always the supplied order. */
    suspend fun setQueue(tracks: List<MediaTrack>)
    /** User-initiated: foreground-service/focus failures throw and are reflected in state. */
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMillis: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun setRepeatMode(mode: MediaRepeatMode)
    suspend fun setShuffleMode(mode: MediaShuffleMode)
    /** Idempotent; revokes the handle immediately and releases host resources. */
    override fun close()
}

data class MediaTrack(val id: String, val title: String, val url: String, val artist: String? = null) {
    init {
        require(id.isNotBlank() && id.length <= 256)
        require(title.isNotBlank() && title.length <= 512)
        require(url.isNotBlank() && url.length <= 8192)
        require(artist == null || artist.length <= 512)
    }
}

enum class MediaPlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, STOPPED, COMPLETED, ERROR, CLOSED }
enum class MediaRepeatMode { OFF, ONE, ALL }
enum class MediaShuffleMode { OFF, ON }
enum class MediaPlaybackError { AUTHORIZATION, FOREGROUND_SERVICE, AUDIO_FOCUS, SOURCE, PLAYBACK, SERVICE_DESTROYED }

data class MediaPlaybackState(
    val queue: List<MediaTrack> = emptyList(),
    val currentIndex: Int = -1,
    val status: MediaPlaybackStatus = MediaPlaybackStatus.IDLE,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val repeatMode: MediaRepeatMode = MediaRepeatMode.OFF,
    val shuffleMode: MediaShuffleMode = MediaShuffleMode.OFF,
    val error: MediaPlaybackError? = null,
) {
    val currentTrack: MediaTrack? get() = queue.getOrNull(currentIndex)
}

object DeniedMediaAccess : MediaAccess {
    override fun createPlayer(id: String, title: String): MediaPlayerHandle =
        throw SecurityException("Media access has not been approved by the host")
}
