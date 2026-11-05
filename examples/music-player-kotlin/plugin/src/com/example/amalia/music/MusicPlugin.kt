package com.example.amalia.music

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.my.amali.data.ai.AmaliaTool
import com.my.amali.data.ai.ToolArguments
import com.my.amali.data.ai.ToolDefinition
import com.my.amali.data.ai.ToolHandler
import com.my.amali.data.ai.ToolOutcome
import com.my.amali.data.ai.ToolParameter
import com.my.amali.data.ai.ToolParameter.JsonType
import com.my.amali.plugin.ActionSpec
import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.AudioSessionContributor
import com.my.amali.plugin.AudioSessionHandler
import com.my.amali.plugin.AudioSessionHost
import com.my.amali.plugin.BackgroundContributor
import com.my.amali.plugin.BackgroundWorkType
import com.my.amali.plugin.HostServices
import com.my.amali.plugin.BackgroundState
import com.my.amali.plugin.CapabilityContributor
import com.my.amali.plugin.NotificationActionHandler
import com.my.amali.plugin.NotificationContributor
import com.my.amali.plugin.NotificationHost
import com.my.amali.plugin.NotificationSpec
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginContext
import com.my.amali.plugin.PluginMetadata
import com.my.amali.plugin.ScreenContributor
import com.my.amali.plugin.ScreenSpec
import com.my.amali.plugin.ToolContributor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Owns the local library and playback; never controls another application's player. */
class MusicPlugin : AmaliaPlugin, ScreenContributor, ToolContributor,
    NotificationContributor, NotificationActionHandler, BackgroundContributor,
    AudioSessionContributor, CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.example.amalia.music",
        name = "Music Player",
        version = "2.0.0",
        author = "example",
        description = "Local music library, playlists and playback",
        category = PluginCategory.GENERAL,
        iconName = "wave",
    )

    private val toolMutex = Mutex()
    private var scope: CoroutineScope? = null
    private lateinit var appContext: Context
    private lateinit var library: MusicLibrary
    private lateinit var services: HostServices
    private lateinit var strings: PlayerStrings
    // Reading this in the screen closure also observes replacement after re-enabling.
    private val currentPlayback = mutableStateOf<LocalPlayback?>(null)
    @Volatile private var playback: LocalPlayback? = null
    @Volatile private var enabled = false
    private var assistantPause: PlaybackSnapshot? = null

    override fun onLoaded(context: PluginContext) {
        checkMain()
        if (scope != null) return
        services = context.services
        appContext = context.appContext.applicationContext
        library = MusicLibrary(appContext)
        strings = PlayerStrings(appContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        replacePlayback()
    }

    override fun onEnabled() {
        checkMain()
        checkNotNull(scope) { "Music plugin has not been loaded" }
        if (playback == null) replacePlayback()
        enabled = true
        scope?.launch {
            try {
                library.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Initial library refresh failed (${e.javaClass.simpleName})")
            }
        }
    }

    override fun onDisabled() {
        checkMain()
        enabled = false
        assistantPause = null
        scope?.coroutineContext?.cancelChildren()
        playback?.let { it.stop(); it.release() }
        playback = null
        currentPlayback.value = null
    }

    override fun onUnloaded() {
        checkMain()
        onDisabled()
        scope?.cancel()
        scope = null
    }

    private fun replacePlayback() {
        val replacement = LocalPlayback(appContext) { services.ensureForeground(metadata.id) }
        playback = replacement
        currentPlayback.value = replacement
    }

    override fun screens(): List<ScreenSpec> = listOf(
        ScreenSpec(
            route = "plugin/${metadata.id}/library",
            titleRes = 0,
            title = strings["music_title"],
            inNavigation = true,
            iconName = "wave",
            content = { host ->
                currentPlayback.value?.let { player ->
                    MusicScreen(library, player, strings, host)
                }
            },
        ),
    )

    override fun requiredCapabilities(): List<String> = listOf(
        "media.library_audio", "audio.play", "notifications.post", "notifications.actions", "system.background", "audio.duck",
    )

    override fun tools(context: PluginContext): List<AmaliaTool> = listOf(
        tool("music_library", "Browse or search actual local tracks by title, artist or album. Results are paginated; use returned IDs.",
            listOf(text("query", "Optional case-insensitive search", false)) + pageParameters(), refresh = true) { args ->
            trackPage(search(args.string("query")), args)
        },
        tool("music_status", "Read the current local playback state and current track.") { _ -> status() },
        tool("music_queue", "Read a page of the current playback queue.", pageParameters()) { args ->
            trackPage(requirePlayer().state.value.queue, args)
        },
        tool("music_play", "Play explicit track IDs in the supplied order, or a search matching exactly one track. Ambiguous searches return candidates without playing. Supply exactly one of track_ids or query.",
            listOf(text("track_ids", "JSON array encoded as a string of exact track IDs, at most 100", false),
                text("query", "Title, artist or album search; must resolve to exactly one track", false),
                text("start_id", "Optional starting ID within track_ids", false)), refresh = true) { args ->
            val ids = trackIds(args)
            val query = args.string("query")
            require(ids.isNotEmpty() xor query.isNotEmpty()) { "Supply exactly one of track_ids or query" }
            val selected = if (ids.isNotEmpty()) resolve(ids) else search(query)
            require(selected.isNotEmpty()) { "No matching tracks" }
            if (query.isNotEmpty() && selected.size > 1) {
                ToolOutcome.json("status" to "ambiguous", "total" to selected.size,
                    "candidates" to tracksJson(selected.take(MAX_PAGE)),
                    "instruction" to "Ask the user to choose a track, then call music_play with its ID")
            } else {
                val start = args.string("start_id", selected.first().id)
                require(selected.any { it.id == start }) { "start_id must be in the selected queue" }
                manual { it.play(selected, start) }
                status()
            }
        },
        tool("music_control", "Control existing playback. Resume does not choose a song when no track is loaded.",
            listOf(ToolParameter("action", JsonType.STRING, "Playback action", enumValues = ACTIONS))) { args ->
            val action = args.string("action")
            require(action in ACTIONS) { "Unknown playback action" }
            if (action != "stop") require(requirePlayer().state.value.current != null) { "No track is loaded; select one with music_play" }
            manual { player ->
                when (action) {
                    "toggle" -> player.toggle()
                    "pause" -> player.pause()
                    "resume" -> player.resume()
                    "next" -> player.next()
                    "previous" -> player.previous()
                    "stop" -> player.stop()
                }
            }
            status()
        },
        tool("music_seek", "Seek within the current track using milliseconds.",
            listOf(ToolParameter("position_ms", JsonType.INTEGER, "Nonnegative position in milliseconds", min = 0.0))) { args ->
            val position = args.string("position_ms").toLongOrNull()
                ?: throw IllegalArgumentException("position_ms must be an integer")
            require(position >= 0) { "position_ms must be nonnegative" }
            require(requirePlayer().state.value.current != null) { "No track is loaded" }
            manual { it.seekTo(position) }
            status()
        },
        tool("music_shuffle", "Enable or disable shuffle for local playback.",
            listOf(ToolParameter("enabled", JsonType.BOOLEAN, "Whether shuffle is enabled"))) { args ->
            val value = args.string("enabled").lowercase()
            require(value == "true" || value == "false") { "enabled must be a boolean" }
            manual { it.setShuffle(value == "true") }
            status()
        },
        tool("music_repeat", "Set repeat mode: off, all tracks, or one track.",
            listOf(ToolParameter("mode", JsonType.STRING, "Repeat mode", enumValues = REPEAT_MODES))) { args ->
            val mode = args.string("mode")
            require(mode in REPEAT_MODES) { "mode must be off, all or one" }
            manual { it.setRepeat(mode) }
            status()
        },
        tool("music_playlists", "List saved playlists with IDs and track counts, one page at a time.",
            pageParameters(), refresh = true) { args ->
            val all = library.playlists.value
            val (offset, limit) = page(args)
            val items = JSONArray()
            all.drop(offset).take(limit).forEach { items.put(playlistJson(it)) }
            paged("playlists", items, all.size, offset, limit)
        },
        tool("music_playlist_create", "Create a saved playlist, optionally with exact local track IDs.",
            listOf(text("name", "Nonempty playlist name"),
                text("track_ids", "JSON array encoded as a string of exact track IDs, at most 100", false)), refresh = true) { args ->
            val name = args.string("name")
            require(name.isNotBlank()) { "name must not be empty" }
            val ids = trackIds(args)
            resolve(ids)
            val created = library.createPlaylist(name, ids)
            ToolOutcome.json("playlist" to playlistJson(created))
        },
        tool("music_playlist_add", "Add exact local track IDs to an existing saved playlist.",
            playlistEditParameters(), refresh = true) { args ->
            val ids = trackIds(args, required = true)
            resolve(ids)
            library.addToPlaylist(playlist(args).id, ids)
            ToolOutcome.done()
        },
        tool("music_playlist_remove", "Remove track IDs from a playlist, including IDs of unavailable files.",
            playlistEditParameters(), refresh = true) { args ->
            library.removeFromPlaylist(playlist(args).id, trackIds(args, required = true))
            ToolOutcome.done()
        },
        tool("music_playlist_delete", "Delete the saved playlist identified by playlist_id.",
            listOf(text("playlist_id", "Exact playlist ID")), refresh = true) { args ->
            library.deletePlaylist(playlist(args).id)
            ToolOutcome.done()
        },
        tool("music_playlist_play", "Play a saved playlist in its stored order. Unavailable tracks are skipped and reported; an empty playlist fails.",
            listOf(text("playlist_id", "Exact playlist ID"),
                text("start_id", "Optional available track ID in this playlist", false)), refresh = true) { args ->
            val saved = playlist(args)
            val known = library.tracks.value.associateBy { it.id }
            val available = saved.trackIds.mapNotNull(known::get)
            require(available.isNotEmpty()) { "Playlist has no available tracks" }
            val start = args.string("start_id", available.first().id)
            require(available.any { it.id == start }) { "start_id is not an available track in this playlist" }
            manual { it.play(available, start) }
            ToolOutcome.json("status" to "ok", "playlist_id" to saved.id,
                "queue_count" to available.size, "skipped_count" to saved.trackIds.size - available.size)
        },
    )

    private fun tool(
        name: String,
        description: String,
        parameters: List<ToolParameter> = emptyList(),
        refresh: Boolean = false,
        action: suspend (ToolArguments) -> ToolOutcome,
    ): AmaliaTool = AmaliaTool(ToolDefinition(name, description, parameters), ToolHandler { args ->
        try {
            val owner = scope
            if (owner == null) ToolOutcome.failed("Music plugin is not loaded")
            else {
                val pending = owner.async {
                    toolMutex.withLock {
                    requirePlayer()
                    val unknown = args.raw.keys - parameters.map { it.name }.toSet()
                    require(unknown.isEmpty()) { "Unknown arguments: ${unknown.joinToString()}" }
                    parameters.filter { it.required }.forEach {
                        require(args.has(it.name)) { "Missing argument: ${it.name}" }
                    }
                    // Every tool explicitly checks media access, including status and transport tools.
                    if (!library.hasPermission()) {
                        ToolOutcome.failed("Audio library permission is required. Open the music library screen and grant access to audio files, then retry.")
                    } else {
                        if (refresh) {
                            library.refresh()
                            require(library.hasPermission() && !library.permissionRequired.value) { "Audio library permission is required; grant access on the music library screen" }
                            library.error.value?.let { throw IllegalStateException(it) }
                        }
                        requirePlayer()
                        action(args)
                    }
                    }
                }
                try {
                    pending.await()
                } finally {
                    pending.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Music tool $name failed (${e.javaClass.simpleName})")
            ToolOutcome.failed(if (e is IllegalArgumentException || e is IllegalStateException)
                e.message ?: "Music operation failed" else "Music operation failed; please retry")
        }
    })

    private fun requirePlayer(): LocalPlayback {
        check(enabled) { "Music plugin is disabled" }
        return checkNotNull(playback) { "Music player is unavailable" }
    }

    private suspend fun manual(action: (LocalPlayback) -> Unit) = withContext(Dispatchers.Main.immediate) {
        assistantPause = null
        val player = requirePlayer()
        action(player)
        player.state.value.error?.let { throw IllegalStateException(it) }
    }

    private fun status(): ToolOutcome {
        val state = requirePlayer().state.value
        return ToolOutcome.json("current" to state.current?.let(::trackJson),
            "playing" to state.playing, "preparing" to state.preparing,
            "position_ms" to state.positionMs, "duration_ms" to state.durationMs,
            "shuffle" to state.shuffle, "repeat" to state.repeat,
            "queue_count" to state.queue.size, "error" to state.error)
    }

    private fun search(query: String): List<Track> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return library.tracks.value.filter { track ->
            val text = "${track.title} ${track.artist} ${track.album}"
            words.all { text.contains(it, ignoreCase = true) }
        }
    }

    private fun trackIds(args: ToolArguments, required: Boolean = false): List<String> {
        if (!args.has("track_ids")) {
            require(!required) { "track_ids is required" }
            return emptyList()
        }
        val array = JSONArray(args.string("track_ids"))
        require(array.length() <= MAX_PAGE) { "At most 100 track IDs are accepted per call" }
        val ids = (0 until array.length()).map {
            val value = array.get(it)
            require(value is String && value.isNotBlank()) { "track_ids must contain nonempty strings" }
            value
        }
        require(!required || ids.isNotEmpty()) { "track_ids must not be empty" }
        require(ids.distinct().size == ids.size) { "Duplicate track IDs are not allowed" }
        return ids
    }

    private fun resolve(ids: List<String>): List<Track> {
        val known = library.tracks.value.associateBy { it.id }
        return ids.map { known[it] ?: throw IllegalArgumentException("Track ID is not in the current library; search again") }
    }

    private fun playlist(args: ToolArguments): MusicPlaylist = library.playlists.value
        .firstOrNull { it.id == args.string("playlist_id") }
        ?: throw IllegalArgumentException("Playlist ID was not found; list playlists first")

    private fun trackJson(track: Track): JSONObject = JSONObject()
        .put("id", track.id).put("title", track.title).put("artist", track.artist)
        .put("album", track.album).put("duration_ms", track.durationMs)

    private fun tracksJson(tracks: List<Track>): JSONArray = JSONArray().apply {
        tracks.forEach { put(trackJson(it)) }
    }

    private fun playlistJson(playlist: MusicPlaylist): JSONObject = JSONObject()
        .put("id", playlist.id).put("name", playlist.name).put("track_count", playlist.trackIds.size)

    private fun trackPage(tracks: List<Track>, args: ToolArguments): ToolOutcome {
        val (offset, limit) = page(args)
        return paged("tracks", tracksJson(tracks.drop(offset).take(limit)), tracks.size, offset, limit)
    }

    private fun page(args: ToolArguments): Pair<Int, Int> {
        val offset = if (args.has("offset")) args.string("offset").toIntOrNull() else 0
        val limit = if (args.has("limit")) args.string("limit").toIntOrNull() else 25
        require(offset != null && offset >= 0) { "offset must be a nonnegative integer" }
        require(limit != null && limit in 1..MAX_PAGE) { "limit must be an integer from 1 to 100" }
        return offset to limit
    }

    private fun paged(key: String, items: JSONArray, total: Int, offset: Int, limit: Int): ToolOutcome {
        val end = offset.toLong() + limit
        return ToolOutcome.json(key to items, "total" to total, "offset" to offset,
            "limit" to limit, "next_offset" to if (end < total) end else null)
    }

    override suspend fun post(host: NotificationHost) {
        val snapshot = playback?.state?.value
        val track = snapshot?.current
        if (!enabled || snapshot == null || track == null) {
            host.cancel(NOTIFICATION_ID)
            return
        }
        host.notify(NOTIFICATION_ID, NotificationSpec(
            titleRes = 0, textRes = 0, title = track.title, text = track.artist,
            onGoing = snapshot.playing || snapshot.preparing, actions = actions(snapshot.playing),
        ))
    }

    private fun actions(playing: Boolean): List<ActionSpec> = listOf(
        ActionSpec("previous", android.R.drawable.ic_media_previous, 0, strings["previous"]),
        ActionSpec("toggle", if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            0, strings[if (playing) "pause" else "play"]),
        ActionSpec("next", android.R.drawable.ic_media_next, 0, strings["next"]),
    )

    override suspend fun onAction(actionId: String) {
        try {
            val owner = scope ?: return
            owner.async {
                if (!enabled || !library.hasPermission()) return@async
                when (actionId) {
                    "previous" -> manual { it.previous() }
                    "toggle" -> manual { it.toggle() }
                    "next" -> manual { it.next() }
                }
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Notification action failed (${e.javaClass.simpleName})")
        }
    }

    override suspend fun state(): BackgroundState {
        val snapshot = playback?.state?.value ?: return BackgroundState.Idle
        if (!enabled || (!snapshot.playing && !snapshot.preparing)) return BackgroundState.Idle
        return BackgroundState.Active(titleRes = 0, textRes = 0,
            title = snapshot.current?.title.orEmpty(), text = snapshot.current?.artist.orEmpty(),
            actions = actions(snapshot.playing), foregroundType = BackgroundWorkType.MEDIA_PLAYBACK)
    }

    override fun session(host: AudioSessionHost): AudioSessionHandler = object : AudioSessionHandler {
        private var closed = false
        override fun onAssistantSpeaking(duckVolume: Float) {
            dispatchAudio {
                if (!closed && enabled) playback?.setSpeechDucking(duckVolume)
            }
        }
        override fun onAssistantDone() {
            dispatchAudio { if (!closed) playback?.setSpeechDucking(1f) }
        }
        override fun release() {
            dispatchAudio {
                closed = true
                playback?.setSpeechDucking(1f)
            }
        }
    }

    private fun dispatchAudio(action: () -> Unit) {
        scope?.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assistantPause = null
                Log.e(TAG, "Audio session operation failed (${e.javaClass.simpleName})")
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Music lifecycle must run on the main thread"
    }

    private fun text(name: String, description: String, required: Boolean = true) =
        ToolParameter(name, JsonType.STRING, description, required)

    private fun pageParameters() = listOf(
        ToolParameter("offset", JsonType.INTEGER, "Zero-based offset (default 0)", false, min = 0.0),
        ToolParameter("limit", JsonType.INTEGER, "Page size (default 25, maximum 100)", false, min = 1.0, max = 100.0),
    )

    private fun playlistEditParameters() = listOf(
        text("playlist_id", "Exact saved playlist ID"),
        text("track_ids", "JSON array encoded as a string of exact track IDs, at most 100"),
    )

    private companion object {
        const val TAG = "MusicPlugin"
        const val MAX_PAGE = 100
        const val NOTIFICATION_ID = "music_player"
        val ACTIONS = listOf("toggle", "pause", "resume", "next", "previous", "stop")
        val REPEAT_MODES = listOf("off", "all", "one")
    }
}
