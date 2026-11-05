package com.example.amalia.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Repeat values: off, all, one. All times are milliseconds. */
data class PlaybackSnapshot(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val error: String? = null,
)

/** Public commands may be called from any thread; effects are serialized on Main. */
class LocalPlayback(
    context: Context,
    private val beforeStart: suspend () -> Boolean,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    // Some plugin loaders expose coroutines-core but not its Android dispatcher provider.
    private val mainDispatcher: MainCoroutineDispatcher = try {
        Dispatchers.Main.immediate.also { it.isDispatchNeeded(EmptyCoroutineContext) }
    } catch (_: IllegalStateException) {
        object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != handler.looper
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                check(handler.post(block)) { "The main thread is unavailable" }
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var registered = false
    private var released = false
    private var prepared = false
    private var playWhenReady = false
    private var resumeOnGain = false
    private var ended = false
    private var pendingSeek = 0L
    private var pollJob: Job? = null
    private var order: List<Track> = emptyList()
    private var metadataTrack: Track? = null
    private var metadataDuration = -1L
    private var speechVolume = 1f

    /** Applies to current and future tracks without changing user playback intent. */
    fun setSpeechDucking(volume: Float) = command {
        speechVolume = volume.coerceIn(0f, 1f)
        player?.setVolume(speechVolume, speechVolume)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        command {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (!resumeOnGain && !playWhenReady && !state.value.playing) return@command
                    hasFocus = true
                    if (resumeOnGain) {
                        resumeOnGain = false
                        resumeInternal()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    pauseInternal(abandon = true)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Pausing also handles spoken audio and avoids unexpected volume changes.
                    val shouldResume = resumeOnGain || playWhenReady || mutableState.value.playing
                    hasFocus = false
                    pauseInternal(abandon = false)
                    resumeOnGain = shouldResume
                    // Keep listening while awaiting focus so unplugging cancels automatic resume.
                    if (shouldResume) registerNoisy()
                }
            }
        }
    }

    init {
        command {
            session = MediaSession(this.context, "LocalMusicPlayback").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setPlaybackToLocal(attributes)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = resume()
                    override fun onPause() = pause()
                    override fun onStop() = stop()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = previous()
                    override fun onSeekTo(pos: Long) = seekTo(pos)
                    override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                        mediaId?.let { play(state.value.queue, it) }
                    }
                }, handler)
            }
            publish(mutableState.value)
        }
    }

    fun play(tracks: List<Track>, startId: String) {
        val copy = tracks.toList()
        command {
            require(copy.isNotEmpty()) { "The playback queue is empty" }
            require(copy.map { it.id }.distinct().size == copy.size) { "The playback queue contains duplicate track IDs" }
            require(copy.all { it.id.isNotBlank() && it.uri.isNotBlank() }) { "A track has an invalid ID or URI" }
            val start = copy.find { it.id == startId }
                ?: throw IllegalArgumentException("The selected track is not in the queue")
            order = if (state.value.shuffle) listOf(start) + copy.filterNot { it.id == startId }.shuffled() else copy
            publish(state.value.copy(queue = copy))
            load(start)
        }
    }

    fun toggle() = command {
        if (playWhenReady || state.value.playing) pauseInternal() else resumeInternal()
    }

    fun pause() = command { pauseInternal() }
    fun resume() = command { resumeInternal() }
    fun next() = command { advance(completed = false) }
    fun previous() = command {
        resumeOnGain = false
        if (state.value.positionMs > 3_000) seekInternal(0)
        else {
            val index = order.indexOfFirst { it.id == state.value.current?.id }
            if (index > 0) load(order[index - 1])
            else if (state.value.repeat == "all" && order.isNotEmpty()) load(order.last())
            else seekInternal(0)
        }
    }
    fun seekTo(positionMs: Long) = command { seekInternal(positionMs) }
    fun shuffle(enabled: Boolean = !state.value.shuffle) = command {
        val current = state.value.current
        order = if (enabled && current != null) listOf(current) + state.value.queue.filterNot { it.id == current.id }.shuffled()
            else state.value.queue
        publish(state.value.copy(shuffle = enabled))
    }
    fun repeat(mode: String) = command {
        require(mode in setOf("off", "all", "one")) { "Repeat mode must be off, all, or one" }
        publish(state.value.copy(repeat = mode))
    }
    fun setShuffle(enabled: Boolean) = shuffle(enabled)
    fun setRepeat(mode: String) = repeat(mode)
    fun stop() = command { stopInternal() }

    /** Terminal and idempotent. A released instance ignores subsequent commands. */
    fun release() = command {
        try {
            stopInternal()
        } finally {
            released = true
            session?.release()
            session = null
            scope.cancel()
        }
    }

    private fun command(action: () -> Unit) {
        scope.launch {
            if (!released) {
                try { action() }
                catch (e: Exception) { fail("Could not complete the playback command", e) }
            }
        }
    }

    private fun load(track: Track) {
        disposePlayer()
        resumeOnGain = false
        ended = false
        pendingSeek = 0
        playWhenReady = true
        publish(state.value.copy(current = track, playing = false, preparing = true,
            positionMs = 0, durationMs = track.durationMs.coerceAtLeast(0), error = null))
        registerNoisy()
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(attributes)
        media.setVolume(speechVolume, speechVolume)
        media.setOnPreparedListener { ready ->
            if (player !== ready || released) return@setOnPreparedListener
            try {
                prepared = true
                val duration = ready.duration.toLong().coerceAtLeast(0)
                publish(state.value.copy(preparing = false, durationMs = duration))
                if (pendingSeek > 0) seekInternal(pendingSeek)
                if (playWhenReady) startPrepared()
            } catch (e: Exception) { fail("Could not start the selected track", e) }
        }
        media.setOnCompletionListener { completed ->
            if (player === completed && !released) {
                try { advance(completed = true) }
                catch (e: Exception) { fail("Could not play the next track", e) }
            }
        }
        media.setOnErrorListener { failed, what, extra ->
            if (player === failed && !released) {
                Log.e(TAG, "MediaPlayer failed (what=$what, extra=$extra)")
                fail("The selected track could not be played")
            }
            true
        }
        media.setOnSeekCompleteListener { sought ->
            if (player === sought && prepared && !released) {
                try { publish(state.value.copy(positionMs = sought.currentPosition.toLong().coerceAtLeast(0))) }
                catch (e: Exception) { fail("Could not seek in this track", e) }
            }
        }
        media.setDataSource(context, Uri.parse(track.uri))
        media.prepareAsync()
    }

    private fun resumeInternal() {
        resumeOnGain = false
        if (state.value.current == null) return
        playWhenReady = true
        if (state.value.preparing) {
            registerNoisy()
            return
        }
        if (!prepared || player == null) {
            load(state.value.current ?: return)
            return
        }
        if (ended) seekInternal(0)
        startPrepared()
    }

    private var startJob: Job? = null

    private fun startPrepared() {
        if (!prepared || player == null) return
        startJob?.cancel()
        val expected = player
        publish(state.value.copy(preparing = true))
        startJob = scope.launch {
            try {
                val ready = beforeStart()
                if (released || player !== expected || !playWhenReady) return@launch
                if (!ready) {
                    fail("Foreground playback is unavailable; open the app and retry")
                    return@launch
                }
                startWithFocus()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("Could not start background playback", error)
            }
        }
    }

    private fun startWithFocus() {
        if (!requestFocus()) {
            playWhenReady = false
            unregisterNoisy()
            publish(state.value.copy(playing = false, preparing = false, error = "Audio focus is unavailable; try playing again"))
            return
        }
        registerNoisy()
        player?.start()
        playWhenReady = true
        ended = false
        publish(state.value.copy(playing = true, preparing = false, error = null))
        startPolling()
    }

    private fun pauseInternal(abandon: Boolean = true) {
        startJob?.cancel()
        startJob = null
        resumeOnGain = false
        playWhenReady = false
        pollJob?.cancel()
        pollJob = null
        var position = state.value.positionMs
        if (prepared && player != null) {
            if (state.value.playing) player?.pause()
            position = player?.currentPosition?.toLong()?.coerceAtLeast(0) ?: position
        }
        unregisterNoisy()
        if (abandon) abandonFocus()
        publish(state.value.copy(playing = false, preparing = state.value.preparing && !prepared, positionMs = position))
    }

    private fun seekInternal(position: Long) {
        if (state.value.current == null) return
        val target = position.coerceIn(0L, state.value.durationMs.coerceAtLeast(0))
        pendingSeek = target
        ended = false
        if (prepared) {
            val media = player ?: return
            if (Build.VERSION.SDK_INT >= 26) media.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            else {
                @Suppress("DEPRECATION")
                media.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        publish(state.value.copy(positionMs = target))
    }

    private fun advance(completed: Boolean) {
        resumeOnGain = false
        val current = state.value.current ?: return
        val index = order.indexOfFirst { it.id == current.id }
        if (completed && state.value.repeat == "one") load(current)
        else if (index >= 0 && index + 1 < order.size) load(order[index + 1])
        else if (state.value.repeat == "all" && order.isNotEmpty()) {
            if (state.value.shuffle && order.size > 1) {
                order = order.filterNot { it.id == current.id }.shuffled() + current
            }
            load(order.first())
        } else {
            pauseInternal()
            ended = true
            publish(state.value.copy(positionMs = if (completed) state.value.durationMs else state.value.positionMs))
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (state.value.playing && !released) {
                delay(500)
                try {
                    val position = player?.currentPosition?.toLong()?.coerceAtLeast(0) ?: break
                    publish(state.value.copy(positionMs = position))
                } catch (e: Exception) {
                    fail("Could not read playback position", e)
                    break
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener, handler).build().also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        hasFocus = false
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun registerNoisy() {
        if (registered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisyReceiver, filter)
        }
        registered = true
    }

    private fun unregisterNoisy() {
        if (!registered) return
        try { context.unregisterReceiver(noisyReceiver) }
        catch (e: IllegalArgumentException) { Log.w(TAG, "Audio receiver was already unregistered") }
        finally { registered = false }
    }

    private fun disposePlayer() {
        startJob?.cancel()
        startJob = null
        pollJob?.cancel()
        pollJob = null
        prepared = false
        val old = player
        player = null
        old?.release()
    }

    private fun stopInternal() {
        playWhenReady = false
        resumeOnGain = false
        ended = false
        disposePlayer()
        unregisterNoisy()
        abandonFocus()
        order = emptyList()
        publish(PlaybackSnapshot(shuffle = state.value.shuffle, repeat = state.value.repeat))
    }

    private fun fail(message: String, cause: Exception? = null) {
        Log.e(TAG, "$message (${cause?.javaClass?.simpleName ?: "MediaPlayer error"})")
        playWhenReady = false
        resumeOnGain = false
        disposePlayer()
        unregisterNoisy()
        try { abandonFocus() }
        catch (e: Exception) { Log.w(TAG, "Could not abandon audio focus (${e.javaClass.simpleName})") }
        publish(state.value.copy(playing = false, preparing = false, error = cause?.let {
            if (it is IllegalArgumentException) it.message else null
        } ?: message))
    }

    private fun publish(snapshot: PlaybackSnapshot) {
        mutableState.value = snapshot
        val mediaSession = session ?: return
        mediaSession.isActive = snapshot.current != null
        if (metadataTrack != snapshot.current || metadataDuration != snapshot.durationMs) {
            metadataTrack = snapshot.current
            metadataDuration = snapshot.durationMs
            mediaSession.setMetadata(snapshot.current?.let {
                MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, it.id)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, it.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, it.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs).build()
            })
        }
        val playback = when {
            snapshot.error != null -> PlaybackState.STATE_ERROR
            snapshot.preparing -> PlaybackState.STATE_BUFFERING
            snapshot.playing -> PlaybackState.STATE_PLAYING
            snapshot.current != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val builder = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(playback, snapshot.positionMs, if (snapshot.playing) 1f else 0f)
        snapshot.error?.let { builder.setErrorMessage(it) }
        mediaSession.setPlaybackState(builder.build())
    }

    private companion object { const val TAG = "LocalPlayback" }
}
