package com.amalia.youtube.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.coroutines.coroutineContext

internal data class YouTubeTrack(
    val title: String,
    val url: String,
    val pageUrl: String,
)

internal class TrackNotFoundException : Exception()
internal class AudioUnavailableException : Exception()

internal object YouTubeRepository {
    @Volatile private var initialized = false

    private fun initExtractor() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(YouTubeDownloader(), Localization("ru", "RU"))
                initialized = true
            }
        }
    }

    suspend fun findAudio(query: String): YouTubeTrack = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        initExtractor()
        val service = ServiceList.YouTube
        val search = service.getSearchExtractor(query.trim(), listOf("videos"), "")
        search.fetchPage()
        coroutineContext.ensureActive()
        val item = search.initialPage.items.filterIsInstance<StreamInfoItem>().firstOrNull()
            ?: throw TrackNotFoundException()
        extract(item.url, item.name)
    }

    suspend fun refresh(track: YouTubeTrack): YouTubeTrack = withContext(Dispatchers.IO) {
        initExtractor()
        extract(track.pageUrl, track.title)
    }

    private suspend fun extract(pageUrl: String, title: String): YouTubeTrack {
        coroutineContext.ensureActive()
        val stream = ServiceList.YouTube.getStreamExtractor(pageUrl)
        stream.fetchPage()
        coroutineContext.ensureActive()
        // ExoPlayer receives a direct HTTP stream, not an inline DASH manifest.
        val audio = stream.audioStreams
            .filter { it.content.startsWith("https://") }
            .maxByOrNull { it.averageBitrate }
            ?: throw AudioUnavailableException()
        return YouTubeTrack(title, audio.content, pageUrl)
    }
}
