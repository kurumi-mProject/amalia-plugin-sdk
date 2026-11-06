package com.example.amalia.music

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.GlassCard
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The host owns playback; leaving this screen must not release or stop it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(
    library: MusicLibrary,
    playback: LocalPlayback,
    strings: PlayerStrings,
    host: ScreenHost,
) {
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val loading by library.loading.collectAsStateWithLifecycle()
    val libraryError by library.error.collectAsStateWithLifecycle()
    val permissionRequired by library.permissionRequired.collectAsStateWithLifecycle()
    val playlists by library.playlists.collectAsStateWithLifecycle()
    val player by playback.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var expandedPlayer by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("library") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    var retryAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }

    // All callbacks run in the composition's main-thread scope. Preserve cancellation.
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionFailed = false
        retryAction = action
        scope.launch {
            try {
                action()
                retryAction = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionFailed = true
            } finally {
                busy = false
            }
        }
    }

    fun refresh() {
        scope.launch {
            try {
                library.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionFailed = true
                retryAction = { library.refresh() }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        refresh()
    }
    DisposableEffect(lifecycleOwner, library) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(library) { refresh() }
    LaunchedEffect(permissionRequired) {
        if (!permissionRequired) permissionDenied = false
    }
    LaunchedEffect(tracks, loading, permissionRequired) {
        if (!loading && !permissionRequired) {
            val available = tracks.mapTo(hashSetOf()) { it.id }
            selectedIds = selectedIds.filter { it in available }
        }
    }

    val selected = remember(selectedIds) { selectedIds.toSet() }
    val activePlaylist = playlists.firstOrNull { it.id == playlistId }
    val tracksById = remember(tracks) { tracks.associateBy { it.id } }
    val playlistTracks = remember(activePlaylist, tracksById) {
        activePlaylist?.trackIds?.mapNotNull(tracksById::get).orEmpty()
    }
    val sourceTracks = when (tab) {
        "queue" -> player.queue
        "playlists" -> if (activePlaylist != null) playlistTracks else emptyList()
        else -> tracks
    }
    val visibleEntries = remember(sourceTracks, query, selectedOnly, selectedIds) {
        val search = query.trim()
        sourceTracks.withIndex().filter { entry ->
            val track = entry.value
            (!selectedOnly || track.id in selected) &&
                (search.isEmpty() || track.title.contains(search, ignoreCase = true) ||
                    track.artist.contains(search, ignoreCase = true) ||
                    track.album.contains(search, ignoreCase = true))
        }
    }
    val matchingPlaylists = remember(playlists, query) {
        playlists.filter { query.isBlank() || it.name.contains(query.trim(), true) }
    }

    val playerContent: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(vertical = 24.dp), Arrangement.spacedBy(16.dp)) {
            Text(strings[if (player.playing) "now_playing" else "paused"],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (player.preparing) Text(strings["preparing"], style = MaterialTheme.typography.bodyMedium)
            if (player.current == null) {
                Text(strings["nothing_playing"], style = MaterialTheme.typography.bodyMedium)
            } else {
                MusicArtwork(player.current)
                Text(player.current?.title?.ifBlank { strings["unknown_title"] }.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge)
                Text(player.current?.artist?.ifBlank { strings["unknown_artist"] }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium)
                player.current?.album?.takeIf { it.isNotBlank() && it != "<unknown>" }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val duration = player.durationMs.coerceAtLeast(0)
            var scrub by remember(player.current?.id) { mutableStateOf<Float?>(null) }
            val position = player.positionMs.coerceIn(0L, duration)
            Slider(
                value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    val fraction = scrub
                    scrub = null
                    if (fraction != null) perform {
                        playback.seekTo((fraction * duration).toLong().coerceIn(0L, duration))
                    }
                },
                enabled = duration > 0 && !player.preparing && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = strings["seek"] },
            )
            Text("${musicTime(((scrub ?: (if (duration > 0) position.toFloat() / duration else 0f)) * duration).toLong())} / ${musicTime(duration)}",
                style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { perform { playback.previous() } },
                    enabled = !busy && !player.preparing && player.queue.isNotEmpty()) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = strings["previous"])
                }
                Button(onClick = { perform { playback.toggle() } },
                    enabled = !busy && !player.preparing && player.current != null) {
                    Text(strings[if (player.playing) "pause" else "play"])
                }
                OutlinedButton(onClick = { perform { playback.next() } },
                    enabled = !busy && !player.preparing && player.queue.isNotEmpty()) {
                    Icon(Icons.Default.SkipNext, contentDescription = strings["next"])
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = player.shuffle,
                    onClick = { perform { playback.setShuffle(!player.shuffle) } },
                    enabled = !busy, label = { Text(strings["shuffle"]) },
                    modifier = Modifier.heightIn(min = 48.dp))
                val repeatKey = when (player.repeat) {
                    "all" -> "repeat_all"
                    "one" -> "repeat_one"
                    else -> "repeat_off"
                }
                OutlinedButton(onClick = {
                    val nextRepeat = when (player.repeat) {
                        "off" -> "all"
                        "all" -> "one"
                        else -> "off"
                    }
                    perform { playback.setRepeat(nextRepeat) }
                }, enabled = !busy) { Text(strings[repeatKey]) }
            }
            if (player.error != null) {
                MusicError(strings["playback_error"], strings, !busy) {
                    val queue = player.queue
                    val currentId = player.current?.id
                    if (queue.isNotEmpty()) perform {
                        playback.play(queue, currentId ?: queue.first().id)
                    } else refresh()
                }
            }
        }
    }

    AmaliaScreen(
        title = strings["music_title"],
        onBack = { perform { host.goBack() } },
        backLabel = strings["back"],
    ) { screenPadding ->
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = screenPadding,
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("library", "playlists", "queue").forEach { value ->
                                FilterChip(
                                    selected = tab == value,
                                    onClick = { tab = value; query = ""; selectedOnly = false },
                                    label = { Text(strings["tab_$value"]) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }
                if (actionFailed) {
                    item(key = "action_error") {
                        MusicError(strings["action_error"], strings, !busy) {
                            retryAction?.let { perform(it) } ?: refresh()
                        }
                    }
                }
                if (permissionRequired) {
                    item(key = "permission") {
                        GlassCard {
                            Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(16.dp)) {
                                Text(strings["permission_title"], style = MaterialTheme.typography.headlineSmall)
                                Text(strings[if (permissionDenied) "permission_denied" else "permission_message"])
                                Button(onClick = {
                                    perform {
                                        permissionLauncher.launch(if (Build.VERSION.SDK_INT >= 33)
                                            Manifest.permission.READ_MEDIA_AUDIO
                                        else Manifest.permission.READ_EXTERNAL_STORAGE)
                                    }
                                }, enabled = !busy) { Text(strings["grant_permission"]) }
                                if (permissionDenied) {
                                    OutlinedButton(onClick = {
                                        perform {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    }, enabled = !busy) { Text(strings["open_settings"]) }
                                }
                            }
                        }
                    }
                }
                if (libraryError != null) {
                    item(key = "library_error") {
                        MusicError(strings["library_error"], strings, !loading && !busy) { refresh() }
                    }
                }
                if (!permissionRequired) item(key = "search") {
                    Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it },
                            label = { Text(strings[if (tab == "playlists" && activePlaylist == null) "search_playlists" else "search"]) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = strings["clear_search"])
                            } }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectionMode) FilterChip(selected = selectedOnly,
                                onClick = { selectedOnly = !selectedOnly },
                                label = { Text(strings["selected_only"]) },
                                modifier = Modifier.heightIn(min = 48.dp))
                            OutlinedButton(onClick = { refresh() }, enabled = !loading && !busy) {
                                Text(strings["refresh"])
                            }

                        }
                    }
                }
                if (!permissionRequired) {
                    item(key = "selection") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectionMode) Text("${strings["selected_count"]}: ${selectedIds.size}",
                                style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (tab != "playlists" || activePlaylist != null) TextButton(onClick = {
                                    selectionMode = !selectionMode
                                    if (!selectionMode) { selectedIds = emptyList(); selectedOnly = false }
                                }) { Text(strings[if (selectionMode) "done" else "select_mode"]) }
                                if (tab == "playlists" || selectedIds.isNotEmpty()) OutlinedButton(onClick = { playlistName = ""; dialog = "create" }, enabled = !busy) {
                                    Text(strings["create_playlist"])
                                }
                                if (selectedIds.isNotEmpty()) {
                                    OutlinedButton(onClick = { dialog = "add" },
                                        enabled = !busy && playlists.isNotEmpty()) {
                                        Text(strings["add_to_playlist"])
                                    }
                                    TextButton(onClick = { selectedIds = emptyList() }, enabled = !busy) {
                                        Text(strings["clear_selection"])
                                    }
                                }
                            }
                        }
                    }
                }
                if (tab == "playlists" && activePlaylist != null) {
                    item(key = "playlist_header") {
                        GlassCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(8.dp)) {
                                Text(activePlaylist.name, style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { heading() })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { playlistId = null }) { Text(strings["all_playlists"]) }
                                    OutlinedButton(onClick = {
                                        val queue = visibleEntries.map { it.value }
                                        perform { playback.play(queue, queue.first().id) }
                                    }, enabled = !busy && !permissionRequired && visibleEntries.isNotEmpty()) {
                                        Text(strings["play_all"])
                                    }
                                    if (selectionMode) OutlinedButton(onClick = { dialog = "remove" },
                                        enabled = !busy && activePlaylist.trackIds.any { it in selected }) {
                                        Text(strings["remove_selected"])
                                    }
                                    TextButton(onClick = { dialog = "delete" }, enabled = !busy) {
                                        Text(strings["delete_playlist"])
                                    }
                                }
                            }
                        }
                    }
                } else if (tab != "playlists" && sourceTracks.isNotEmpty() && !permissionRequired) {
                    item(key = "play_all") {
                        OutlinedButton(onClick = {
                            val queue = visibleEntries.map { it.value }
                            perform { playback.play(queue, queue.first().id) }
                        }, enabled = !busy && visibleEntries.isNotEmpty()) { Text(strings["play_all"]) }
                    }
                }
                if (loading && !permissionRequired) {
                    item(key = "loading") {
                        GlassCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(12.dp)) {
                                MusicSkeleton(strings["loading"])
                                MusicSkeleton(null)
                                MusicSkeleton(null)
                            }
                        }
                    }
                }
                if (tab == "playlists" && activePlaylist == null) {
                    if (matchingPlaylists.isEmpty() && !loading) {
                        item(key = "empty_playlists") {
                            MusicEmpty(strings[if (playlists.isEmpty()) "empty_playlists" else "no_results"], strings[if (query.isNotBlank()) "clear_search" else "create_playlist"]) {
                                if (query.isNotBlank()) query = "" else { playlistName = ""; dialog = "create" }
                            }
                        }
                    }
                    items(matchingPlaylists, key = { "playlist:${it.id}" }) { playlist ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                            onClickLabel = strings["open_playlist"]) { playlistId = playlist.id; query = "" }
                            .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                Text("${strings["track_count"]}: ${playlist.trackIds.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    }
                } else if (!permissionRequired) {
                    if (visibleEntries.isEmpty() && !loading && libraryError == null) {
                        item(key = "empty_tracks") {
                            val emptyKey = when {
                                query.isNotBlank() || selectedOnly -> "no_results"
                                tab == "queue" -> "empty_queue"
                                tab == "playlists" -> "empty_playlist"
                                else -> "empty_library"
                            }
                            MusicEmpty(strings[emptyKey], strings[when {
                                query.isNotBlank() -> "clear_search"
                                selectedOnly -> "clear_selection"
                                tab == "queue" || tab == "playlists" -> "library_action"
                                else -> "refresh"
                            }]) {
                                if (query.isNotBlank() || selectedOnly) { query = ""; selectedOnly = false }
                                else if (tab != "library") { tab = "library"; playlistId = null }
                                else refresh()
                            }
                        }
                    }
                    // Queue entries may contain the same track more than once.
                    items(visibleEntries, key = { "track:${tab}:${it.value.id}:${it.index}" }) { entry ->
                        val track = entry.value
                        MusicTrackRow(track, track.id in selected, player.current?.id == track.id,
                            !busy, selectionMode, strings,
                            onSelect = { checked ->
                                selectedIds = if (checked) (selectedIds + track.id).distinct()
                                else selectedIds.filterNot { it == track.id }
                            },
                            onPlay = {
                                val queue = sourceTracks.toList()
                                perform { playback.play(queue, track.id) }
                            })
                    }
                }
            }
            if (player.current != null) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
                            onClickLabel = strings["expand_player"]) { expandedPlayer = true }
                            .padding(8.dp), Arrangement.spacedBy(4.dp)) {
                            Text(player.current?.title?.ifBlank { strings["unknown_title"] }.orEmpty(),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (player.preparing) strings["preparing"] else
                                player.current?.artist?.ifBlank { strings["unknown_artist"] }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { perform { playback.toggle() } },
                            enabled = !busy && !player.preparing, modifier = Modifier.size(48.dp)) {
                            if (player.preparing) CircularProgressIndicator(Modifier.size(24.dp))
                            else Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = strings[if (player.playing) "pause" else "play"])
                        }
                        IconButton(onClick = { perform { playback.next() } },
                            enabled = !busy && !player.preparing, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = strings["next"])
                        }
                    }
                    if (player.error != null) MusicError(strings["playback_error"], strings, !busy) {
                        expandedPlayer = true
                    }
                }
            }
        }
    }

    if (expandedPlayer) {
        Dialog(onDismissRequest = { expandedPlayer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AmaliaScreen(title = strings["now_playing"],
                onBack = { expandedPlayer = false }, backLabel = strings["collapse_player"]) { padding ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(padding).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                    playerContent()
                    TextButton(onClick = { expandedPlayer = false; tab = "queue"; query = ""; selectedOnly = false }) {
                        Text(strings["tab_queue"])
                    }
                }
            }
        }
    }

    if (dialog != null) {
        Dialog(onDismissRequest = { if (!busy) dialog = null }) {
            GlassCard {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().imePadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "dialog_title") {
                        val titleKey = when (dialog) {
                            "create" -> "create_playlist"
                            "add" -> "add_to_playlist"
                            "remove" -> "remove_confirm"
                            else -> "delete_confirm"
                        }
                        Text(strings[titleKey], style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                    }
                    if (dialog == "create") {
                        item(key = "playlist_name") {
                            OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                                label = { Text(strings["playlist_name"]) }, singleLine = true,
                                enabled = !busy, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (dialog == "add") {
                        items(playlists, key = { "target:${it.id}" }) { playlist ->
                            OutlinedButton(onClick = {
                                val ids = selectedIds.toList()
                                perform { library.addToPlaylist(playlist.id, ids); dialog = null }
                            }, enabled = !busy && selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                Text(playlist.name)
                            }
                        }
                        if (playlists.isEmpty()) item(key = "no_targets") { Text(strings["empty_playlists"]) }
                    }
                    if (dialog == "remove" || dialog == "delete") {
                        item(key = "confirmation_target") { Text(activePlaylist?.name.orEmpty()) }
                    }
                    if (actionFailed) {
                        item(key = "dialog_error") {
                            MusicError(strings["action_error"], strings, !busy) {
                                retryAction?.let { perform(it) }
                            }
                        }
                    }
                    if (busy) item(key = "dialog_busy") { MusicSkeleton(strings["saving"]) }
                    item(key = "dialog_actions") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { dialog = null }, enabled = !busy) { Text(strings["cancel"]) }
                            if (dialog != "add") {
                                Button(onClick = {
                                    when (dialog) {
                                        "create" -> {
                                            val name = playlistName.trim()
                                            val ids = selectedIds.toList()
                                            perform {
                                                val created = library.createPlaylist(name, ids)
                                                playlistId = created.id
                                                tab = "playlists"
                                                query = ""
                                                selectedOnly = false
                                                dialog = null
                                            }
                                        }
                                        "remove" -> activePlaylist?.let { playlist ->
                                            val ids = playlist.trackIds.filter { it in selected }
                                            perform {
                                                library.removeFromPlaylist(playlist.id, ids)
                                                selectedIds = selectedIds.filterNot { it in ids }
                                                dialog = null
                                            }
                                        }
                                        "delete" -> activePlaylist?.let { playlist ->
                                            perform {
                                                library.deletePlaylist(playlist.id)
                                                playlistId = null
                                                dialog = null
                                            }
                                        }
                                    }
                                }, enabled = !busy && when (dialog) {
                                    "create" -> playlistName.isNotBlank()
                                    "remove" -> activePlaylist?.trackIds?.any { it in selected } == true
                                    else -> activePlaylist != null
                                }) { Text(strings[if (dialog == "create") "create" else "confirm"]) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Decode only the current song's embedded artwork, off the UI thread and at a bounded size. */
@Composable
private fun MusicArtwork(track: Track?) {
    val context = LocalContext.current
    val artwork by produceState<ImageBitmap?>(null, track?.uri) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (track == null) return@withContext null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(track.uri))
                val bytes = retriever.embeddedPicture ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) { /* No resource remains usable. */ }
            }
        }
    }
    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
        .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val image = artwork
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MusicTrackRow(
    track: Track,
    selected: Boolean,
    current: Boolean,
    enabled: Boolean,
    selectionMode: Boolean,
    strings: PlayerStrings,
    onSelect: (Boolean) -> Unit,
    onPlay: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().semantics {
            if (selectionMode) this.selected = selected
        }
        .background(if (current) MaterialTheme.colorScheme.secondaryContainer
            else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = if (selectionMode) Role.Checkbox else Role.Button,
            onClickLabel = strings[if (selectionMode) "select_track" else "play"]) {
            if (selectionMode) onSelect(!selected) else onPlay()
        }.padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = null,
            enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
        Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
            Text(track.title.ifBlank { strings["unknown_title"] }, style = MaterialTheme.typography.titleMedium,
                color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(track.artist.ifBlank { strings["unknown_artist"] }, style = MaterialTheme.typography.bodyMedium,
                color = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            if (current) Text(strings["now_playing"], style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(musicTime(track.durationMs), style = MaterialTheme.typography.labelMedium,
            color = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MusicError(message: String, strings: PlayerStrings, enabled: Boolean, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = retry, enabled = enabled) { Text(strings["retry"]) }
    }
}

@Composable
private fun MusicSkeleton(label: String?) {
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Box(Modifier.fillMaxWidth(0.8f).height(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
        Box(Modifier.fillMaxWidth(0.55f).height(12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
    }
}

private fun musicTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun MusicEmpty(message: String, actionLabel: String, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = action) { Text(actionLabel) }
    }
}

// Material buttons default to 40dp visually; keep every music control at least 48dp.
@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialButton(onClick, modifier.heightIn(min = 48.dp), enabled, content = content)
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialOutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, content = content)
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialTextButton(onClick, modifier.heightIn(min = 48.dp), enabled, content = content)
}
