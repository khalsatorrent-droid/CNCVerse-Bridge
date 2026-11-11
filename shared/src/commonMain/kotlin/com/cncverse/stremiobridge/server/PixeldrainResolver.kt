package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * https://cdn.pixeldrain.eu.cc/<id> is only a doorway: it answers with a redirect to the server that really holds the file
 * (https://cdn33.pixeldrain.eu.cc/api/file/<id>, a different server per file). Players and the media server follow that
 * redirect themselves, but a link that went through a redirect is judged badly (seek check, "cannot seek" on the doorway).
 * So the redirect is resolved here, once, and the final address goes into the result list.
 */
object PixeldrainResolver {
    private val MIRROR = Regex("^https://cdn\\.pixeldrain\\.eu\\.cc/([A-Za-z0-9_-]+)/?$", RegexOption.IGNORE_CASE)
    private val FINAL_HOST = Regex("^[a-z0-9-]+\\.pixeldrain\\.eu\\.cc$", RegexOption.IGNORE_CASE)
    private const val TTL_MS = 20L * 60 * 1000

    private class Entry(val url: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    private val client by lazy {
        val dns = runCatching { Class.forName("com.cncverse.stremiobridge.network.AdaptiveHostDns").getField("INSTANCE").get(null) as okhttp3.Dns }
            .getOrDefault(okhttp3.Dns.SYSTEM)
        okhttp3.OkHttpClient.Builder().dns(dns).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    /** The address the doorway link redirects to, or null when it does not (or cannot be reached): the doorway stays. */
    private fun resolve(id: String, doorway: String): String? {
        cache[id]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.url }
        return try {
            // a one-byte range request: the redirect comes back at once and no file data is downloaded
            val req = okhttp3.Request.Builder().url(doorway).header("Range", "bytes=0-0")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36").build()
            client.newCall(req).execute().use { r ->
                val loc = r.header("Location")
                if (r.code in 300..399 && loc != null) {
                    val target = r.request.url.resolve(loc)?.toString()
                    val host = r.request.url.resolve(loc)?.host.orEmpty()
                    if (target != null && target.startsWith("https://") && FINAL_HOST.matches(host)) {
                        if (cache.size > 5_000) cache.clear()
                        cache[id] = Entry(target, System.currentTimeMillis())
                        target
                    } else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    /** Replaces every doorway link in [streams] by the address it redirects to (all at once, ~4 s at most). */
    suspend fun resolveAll(streams: List<StremioStream>): List<StremioStream> {
        val ids = streams.mapNotNull { s -> s.url?.let { MIRROR.matchEntire(it)?.groupValues?.get(1) } }.distinct()
        if (ids.isEmpty()) return streams
        // Two things were wrong here. Every link was asked for at the same moment - thirty or more requests to one host at
        // once, which is how a host starts refusing - so at most 8 run together now. And when the 5 seconds ran out, the
        // answers that had already arrived were thrown away with the rest; they are kept.
        val resolved = ConcurrentHashMap<String, String>()
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        withTimeoutOrNull(5_000L) {
            coroutineScope {
                ids.map { id -> async(Dispatchers.IO) {
                    gate.acquire()
                    try { resolve(id, "https://cdn.pixeldrain.eu.cc/$id")?.let { resolved[id] = it } } finally { gate.release() }
                } }.awaitAll()
            }
        }
        if (resolved.isEmpty()) return streams
        val ok = resolved.size
        ServerState.debug("[Pixeldrain] resolved $ok of ${ids.size} mirror link(s) to their final address")
        return streams.map { s ->
            val id = s.url?.let { MIRROR.matchEntire(it)?.groupValues?.get(1) } ?: return@map s
            val target = resolved[id] ?: return@map s
            s.copy(url = target)
        }
    }
}
