package org.amalia.platform.runtime.testing

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.amalia.platform.protocol.*

/** Deterministic host fixture: no network/audio/timers. Authority defaults to denied.
 * advanceBy drives playback and real completion/repeat/queue transitions explicitly.
 */
class FakeMediaAccess(private val approved: (String) -> Boolean = { false }) : MediaAccess, AutoCloseable {
    private val players = mutableMapOf<String, FakeMediaPlayer>()
    private var closed = false
    @Synchronized override fun createPlayer(id: String, title: String): FakeMediaPlayer {
        authorize()
        require(id.isNotBlank() && id.length <= 128 && title.isNotBlank() && title.length <= 512)
        require(id !in players && players.size < 8)
        return FakeMediaPlayer { players.remove(id) }.also { players[id] = it }
    }
    private fun authorize() {
        check(!closed) { "Fake media access closed" }
        if (!approved("background.media")) throw SecurityException("Media access not approved")
    }
    private fun authorize(track: MediaTrack) {
        val uri = URI(track.url)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null && uri.port in setOf(-1, 443))
        val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
        if (!approved("network:$host")) throw SecurityException("Media network domain not approved")
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        players.values.toList().forEach { it.close() }
    }

    inner class FakeMediaPlayer internal constructor(private val removed: () -> Unit) : MediaPlayerHandle {
        private val mutable = MutableStateFlow(MediaPlaybackState())
        override val state = mutable.asStateFlow()
        private var closed = false
        private val durations = mutableMapOf<String, Long>()
        private var order = emptyList<Int>()
        private fun checkAccess() {
            check(!closed) { "Fake player closed" }
            authorize()
            state.value.currentTrack?.let { authorize(it) }
        }
        /** Host fixture, not plugin authority. */
        fun setDuration(trackId: String, durationMillis: Long) = synchronized(this@FakeMediaAccess) {
            require(durationMillis > 0)
            durations[trackId] = durationMillis
        }
        private fun duration(index: Int) = state.value.queue.getOrNull(index)?.let { durations[it.id] } ?: 60_000L
        override suspend fun setQueue(tracks: List<MediaTrack>) = synchronized(this@FakeMediaAccess) {
            checkAccess()
            require(tracks.size <= 256 && tracks.map { it.id }.toSet().size == tracks.size)
            tracks.forEach { authorize(it) }
            mutable.value = state.value.copy(queue = tracks.toList(), currentIndex = if (tracks.isEmpty()) -1 else 0,
                status = MediaPlaybackStatus.IDLE, positionMillis = 0, durationMillis = 0, error = null)
            rebuild()
        }
        override suspend fun play() = synchronized(this@FakeMediaAccess) {
            checkAccess()
            check(state.value.currentTrack != null) { "Media queue is empty" }
            players.values.filter { it !== this && it.state.value.status == MediaPlaybackStatus.PLAYING }.forEach {
                it.mutable.value = it.state.value.copy(status = MediaPlaybackStatus.PAUSED)
            }
            mutable.value = state.value.copy(status = MediaPlaybackStatus.PLAYING, durationMillis = duration(state.value.currentIndex), error = null)
        }
        override suspend fun pause() = synchronized(this@FakeMediaAccess) {
            checkAccess(); mutable.value = state.value.copy(status = MediaPlaybackStatus.PAUSED)
        }
        override suspend fun stop() = synchronized(this@FakeMediaAccess) {
            checkAccess(); mutable.value = state.value.copy(status = MediaPlaybackStatus.STOPPED, positionMillis = 0, durationMillis = 0, error = null)
        }
        override suspend fun seekTo(positionMillis: Long) = synchronized(this@FakeMediaAccess) {
            checkAccess(); require(positionMillis >= 0)
            mutable.value = state.value.copy(positionMillis = if (state.value.durationMillis > 0)
                positionMillis.coerceAtMost(state.value.durationMillis) else positionMillis)
        }
        override suspend fun next() = synchronized(this@FakeMediaAccess) { checkAccess(); move(1, false) }
        override suspend fun previous() = synchronized(this@FakeMediaAccess) { checkAccess(); move(-1, false) }
        override suspend fun setRepeatMode(mode: MediaRepeatMode) = synchronized(this@FakeMediaAccess) {
            checkAccess(); mutable.value = state.value.copy(repeatMode = mode)
        }
        override suspend fun setShuffleMode(mode: MediaShuffleMode) = synchronized(this@FakeMediaAccess) {
            checkAccess(); mutable.value = state.value.copy(shuffleMode = mode); rebuild()
        }
        private fun rebuild() {
            // Seeded permutation for reproducible tests; current track always remains first.
            order = if (state.value.shuffleMode == MediaShuffleMode.OFF) state.value.queue.indices.toList()
            else listOfNotNull(state.value.currentIndex.takeIf { it >= 0 }) + state.value.queue.indices
                .filter { it != state.value.currentIndex }.shuffled(kotlin.random.Random(0))
        }
        private fun move(direction: Int, completion: Boolean) {
            val value = state.value
            if (order.isEmpty()) return
            val cursor = order.indexOf(value.currentIndex)
            val target = when {
                completion && value.repeatMode == MediaRepeatMode.ONE -> value.currentIndex
                cursor + direction in order.indices -> order[cursor + direction]
                value.repeatMode == MediaRepeatMode.ALL -> if (direction > 0) order.first() else order.last()
                else -> -1
            }
            if (target < 0) {
                if (completion) mutable.value = value.copy(status = MediaPlaybackStatus.COMPLETED, positionMillis = value.durationMillis)
                return
            }
            authorize(value.queue[target])
            mutable.value = value.copy(currentIndex = target, positionMillis = 0,
                durationMillis = duration(target), status = if (completion || value.status == MediaPlaybackStatus.PLAYING)
                    MediaPlaybackStatus.PLAYING else MediaPlaybackStatus.PAUSED)
        }
        fun advanceBy(millis: Long) = synchronized(this@FakeMediaAccess) {
            checkAccess(); require(millis in 0..86_400_000L)
            var remaining = millis
            while (remaining > 0 && state.value.status == MediaPlaybackStatus.PLAYING) {
                val value = state.value
                val distance = (value.durationMillis - value.positionMillis).coerceAtLeast(0)
                if (remaining < distance) {
                    mutable.value = value.copy(positionMillis = value.positionMillis + remaining)
                    break
                }
                remaining -= distance
                move(1, true)
            }
        }
        override fun close() = synchronized(this@FakeMediaAccess) {
            if (!closed) {
                closed = true
                mutable.value = state.value.copy(status = MediaPlaybackStatus.CLOSED)
                removed()
            }
        }
    }
}
