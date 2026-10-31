package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * File sizes for the bitrate shown in the results. Most links do not say their size in their name, but the server
 * of a direct file always knows it: a one-byte range request answers with the whole size (Content-Range: bytes 0-0/SIZE).
 * Sizes are remembered per link, so a lookup that did not finish in time is there for the next request.
 */
object SizeProbe {
    private val sizes = ConcurrentHashMap<String, Long>()
    private val limit = Semaphore(8)
    private val SIZE_IN_TEXT = Regex("\\d\\s?(?:GB|MB|GiB|MiB)\\b", RegexOption.IGNORE_CASE)

    private val client by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(com.cncverse.stremiobridge.network.CfCookieInterceptor)
            .build()
    }

    fun known(url: String?): Long? = url?.let { sizes[it] }

    /** Sizes (link -> bytes) of the direct files in [streams] whose text gives none, as far as they are known within [budgetMs]. */
    suspend fun fill(streams: List<StremioStream>, budgetMs: Long): Map<String, Long> {
        val wanted = streams.filter { s ->
            val url = s.url ?: return@filter false
            sizes[url] == null && SeekProbe.isDirectFile(s) && !SeekProbe.isDownloadOnlyUrl(url) &&
                !SIZE_IN_TEXT.containsMatchIn(listOf(s.name, s.description, s.info?.linkName).joinToString(" "))
        }.distinctBy { it.url }.take(60)
        if (wanted.isNotEmpty()) withTimeoutOrNull(budgetMs) {
            coroutineScope { wanted.map { s -> async { probe(s) } }.awaitAll() }
        }
        return streams.mapNotNull { s -> s.url?.let { u -> sizes[u]?.let { u to it } } }.toMap()
    }

    private suspend fun probe(s: StremioStream) = limit.withPermit {
        withContext(Dispatchers.IO) {
            val req = okhttp3.Request.Builder().url(s.url!!).header("Range", "bytes=0-0").header("User-Agent", "ExoPlayerLib/2.19.1")
            s.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> runCatching { req.header(k, v) } }
            runCatching {
                client.newCall(req.build()).execute().use { r ->
                    val type = r.header("Content-Type").orEmpty().lowercase()
                    if (r.code !in 200..299 || type.startsWith("text/") || "mpegurl" in type || "dash+xml" in type) return@use
                    val total = if (r.code == 206) r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                                else r.header("Content-Length")?.toLongOrNull()
                    if (total != null && total >= 2_000_000L) {
                        if (sizes.size > 5_000) sizes.clear()
                        sizes[s.url!!] = total
                    }
                }
            }
        }
    }
}
