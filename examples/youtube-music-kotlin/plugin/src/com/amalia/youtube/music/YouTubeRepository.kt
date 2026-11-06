package com.amalia.youtube.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem

internal data class YouTubeTrack(val title: String, val url: String)

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

    suspend fun findAudio(query: String): YouTubeTrack? = withContext(Dispatchers.IO) {
        initExtractor()
        runCatching {
            val service: StreamingService = ServiceList.YouTube
            val search = service.getSearchExtractor(query, listOf("videos"), "")
            search.fetchPage()
            val item = search.initialPage.items.filterIsInstance<StreamInfoItem>().firstOrNull()
                ?: return@runCatching null
            val stream = service.getStreamExtractor(item.url)
            stream.fetchPage()
            val audio = stream.audioStreams.maxByOrNull { it.averageBitrate }
                ?: return@runCatching null
            YouTubeTrack(item.name, audio.content)
        }.getOrNull()
    }
}
