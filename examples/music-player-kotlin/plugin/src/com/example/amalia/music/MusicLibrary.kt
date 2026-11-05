package com.example.amalia.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
)

data class MusicPlaylist(val id: String, val name: String, val trackIds: List<String>)

/** Call refresh after permission changes. Saved playlists are loaded on refresh or first edit. */
class MusicLibrary(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private val preferences by lazy { this.context.getSharedPreferences("local_music_library", Context.MODE_PRIVATE) }
    private var playlistsLoaded = false
    private val mutableTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableLoading = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutablePermissionRequired = MutableStateFlow(!hasPermission())
    private val mutablePlaylists = MutableStateFlow<List<MusicPlaylist>>(emptyList())
    val tracks: StateFlow<List<Track>> = mutableTracks.asStateFlow()
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val permissionRequired: StateFlow<Boolean> = mutablePermissionRequired.asStateFlow()
    val playlists: StateFlow<List<MusicPlaylist>> = mutablePlaylists.asStateFlow()

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 23 ||
        context.checkSelfPermission(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableLoading.value = true
            mutableError.value = null
            try {
                mutablePermissionRequired.value = !hasPermission()
                if (mutablePermissionRequired.value) mutableTracks.value = emptyList()
                loadPlaylists()
                if (mutablePermissionRequired.value) {
                    mutableTracks.value = emptyList()
                    return@withLock
                }
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
                val result = mutableListOf<Track>()
                val cursor = context.contentResolver.query(collection, projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC, ${MediaStore.Audio.Media._ID} ASC")
                    ?: throw IOException("The music library could not be opened")
                cursor.use {
                    val id = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val title = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artist = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val album = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val duration = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (it.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, it.getLong(id)).toString()
                        result += Track(uri, it.getString(title).orEmpty(), it.getString(artist).orEmpty(),
                            it.getString(album).orEmpty(), it.getLong(duration).coerceAtLeast(0), uri)
                    }
                }
                mutableTracks.value = result.toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SecurityException) {
                    mutablePermissionRequired.value = true
                    mutableTracks.value = emptyList()
                }
                Log.e(TAG, "Music library refresh failed (${e.javaClass.simpleName})")
                mutableError.value = if (e is SecurityException) "Permission to read audio is required"
                    else "Could not load the music library or saved playlists"
            } finally {
                mutableLoading.value = false
            }
        }
    }

    suspend fun createPlaylist(name: String, trackIds: List<String>): MusicPlaylist = editPlaylists {
        require(name.isNotBlank()) { "Playlist name must not be empty" }
        val ids = validateTracks(trackIds)
        val playlist = MusicPlaylist(UUID.randomUUID().toString(), name.trim(), ids)
        (it + playlist) to playlist
    }

    suspend fun addToPlaylist(id: String, trackIds: List<String>) {
        editPlaylists { list ->
            val playlist = findPlaylist(list, id)
            val ids = validateTracks(trackIds)
            list.map { if (it.id == id) playlist.copy(trackIds = (playlist.trackIds + ids).distinct()) else it } to Unit
        }
    }

    suspend fun removeFromPlaylist(id: String, trackIds: List<String>) {
        editPlaylists { list ->
            val playlist = findPlaylist(list, id)
            require(trackIds.all { it.isNotBlank() && it in playlist.trackIds }) {
                "One or more tracks are not in this playlist"
            }
            val removed = trackIds.toSet()
            list.map { if (it.id == id) playlist.copy(trackIds = playlist.trackIds.filterNot(removed::contains)) else it } to Unit
        }
    }

    suspend fun deletePlaylist(id: String) {
        editPlaylists { list ->
            findPlaylist(list, id)
            list.filterNot { it.id == id } to Unit
        }
    }

    private fun findPlaylist(list: List<MusicPlaylist>, id: String): MusicPlaylist {
        require(id.isNotBlank()) { "Playlist ID must not be empty" }
        return list.find { it.id == id } ?: throw IllegalArgumentException("Playlist does not exist")
    }

    private fun validateTracks(ids: List<String>): List<String> {
        val known = mutableTracks.value.mapTo(hashSetOf()) { it.id }
        require(ids.all { it.isNotBlank() && it in known }) { "One or more tracks are not in the music library; refresh it first" }
        return ids.distinct()
    }

    private suspend fun <T> editPlaylists(change: (List<MusicPlaylist>) -> Pair<List<MusicPlaylist>, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    loadPlaylists()
                    val (updated, result) = change(mutablePlaylists.value)
                    val json = JSONArray()
                    updated.forEach { playlist ->
                        json.put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                            .put("trackIds", JSONArray(playlist.trackIds)))
                    }
                    if (!preferences.edit().putString("playlists", json.toString()).commit()) {
                        throw IOException("Could not save playlists")
                    }
                    mutablePlaylists.value = updated.toList()
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Playlist edit failed (${e.javaClass.simpleName})")
                    throw e
                }
            }
        }

    private fun loadPlaylists() {
        if (playlistsLoaded) return
        val json = JSONArray(preferences.getString("playlists", "[]") ?: "[]")
        val ids = hashSetOf<String>()
        val result = (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            require(id.isNotBlank() && ids.add(id) && name.isNotBlank()) { "Saved playlists are invalid" }
            val entries = item.getJSONArray("trackIds")
            val tracks = (0 until entries.length()).map { entries.getString(it) }
            require(tracks.all { it.isNotBlank() }) { "Saved track IDs are invalid" }
            MusicPlaylist(id, name, tracks.distinct())
        }
        mutablePlaylists.value = result
        playlistsLoaded = true
    }

    private companion object { const val TAG = "MusicLibrary" }
}
