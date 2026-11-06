package com.amalia.youtube.music

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

internal class YouTubeDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: ExtractorRequest): Response {
        val builder = Request.Builder().url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("YouTube rate limit", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36 Chrome/120 Safari/537.36"
    }
}
