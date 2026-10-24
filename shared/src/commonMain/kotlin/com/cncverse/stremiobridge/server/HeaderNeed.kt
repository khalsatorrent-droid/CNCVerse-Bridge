package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Does a link really need the headers its extension attaches to it? Almost every extension declares a Referer / User-Agent for
 * its links, whether or not the host checks them, so "only links that need headers" sent most of the list through the media
 * server anyway. This asks the host: the link is requested the way a plain player would (no headers of the extension), and the
 * answer decides:
 *
 *  - a playlist that is a real playlist, whose first segment (after the variant playlist of a master) can be read   -> not needed
 *  - a file that answers a one-byte range request with video                                                         -> not needed
 *  - anything else (403, an HTML page, a timeout, a segment that is refused)                                         -> needed, as before
 *
 * The answer is kept per host and set of header names (two links of one host are tried, both must pass), for 2 hours when
 * headers are not needed and 20 minutes when they are, so the cost is a few small requests per host, not per link.
 */
object HeaderNeed {
    private class Verdict(val needed: Boolean, val at: Long)

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val running = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(10)

    private const val NOT_NEEDED_TTL_MS = 2 * 3_600_000L
    private const val NEEDED_TTL_MS = 20 * 60_000L
    private const val SAMPLES = 2
    /** What a media player sends when it plays the link itself (the extension's headers are not sent). */
    private const val PROBE_UA = "ExoPlayerLib/2.19.1"

    private val client by lazy {
        OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** One host with one set of header names: "host|accept,referer,user-agent" (the values differ per link, the host's policy does not). */
    internal fun keyOf(host: String, headers: Map<String, String>): String =
        host.lowercase() + "|" + headers.keys.map { it.lowercase() }.sorted().joinToString(",")

    private fun keyOfUrl(url: String, headers: Map<String, String>): String? = LinkUrl.host(url)?.let { keyOf(it, headers) }

    /** true = the host refused the link without the extension's headers, false = it plays without them, null = not known (yet). */
    fun verdict(url: String, headers: Map<String, String>): Boolean? {
        val key = keyOfUrl(url, headers) ?: return null
        val v = verdicts[key] ?: return null
        val ttl = if (v.needed) NEEDED_TTL_MS else NOT_NEEDED_TTL_MS
        return if (System.currentTimeMillis() - v.at <= ttl) v.needed else null
    }

    internal fun record(key: String, needed: Boolean) { verdicts[key] = Verdict(needed, System.currentTimeMillis()) }

    /**
     * Finds out, for the hosts of [streams] that are not known yet, whether their links need the extension's headers, and waits up
     * to [waitMs] for the answers (0 = do not wait: the answers are kept for the next list). Links whose answer did not come in time
     * are treated as "needed" by [MediaServer.rewrite], so a slow check can only cost speed, never a working link.
     */
    suspend fun precheck(streams: List<StremioStream>, waitMs: Long = 3_500L) {
        val p = MediaServer.prefs
        if (!p.enabled || (p.mode != "headers" && p.mode != "hls")) return
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (s in streams) {
            val url = s.url ?: continue
            val headers = s.behaviorHints?.proxyHeaders?.request.orEmpty()
            if (headers.isEmpty() || s.externalUrl != null || s.infoHash != null || s.ytId != null || s.clearkey != null) continue
            if (s.info?.linkType == "UPSTREAM" || !url.startsWith("http", true)) continue
            val path = url.substringBefore('?')
            if (path.endsWith(".mpd", true)) continue
            // in "hls" mode every playlist goes through the media server anyway: only files are judged
            if (p.mode == "hls" && (path.endsWith(".m3u8", true) || url.contains(".m3u8", true) || s.info?.linkType.equals("M3U8", true))) continue
            val host = LinkUrl.host(url) ?: continue
            if (host == "127.0.0.1" || host == "localhost" || host == "::1" || ServerState.isOwnUrl(url)) continue
            val key = keyOf(host, headers)
            if (verdict(url, headers) != null) continue
            val urls = groups.getOrPut(key) { ArrayList() }
            if (urls.size < SAMPLES && url !in urls) urls.add(url)
        }
        if (groups.isEmpty()) return
        val jobs = groups.map { (key, urls) ->
            synchronized(running) {
                running[key]?.takeIf { it.isActive } ?: scope.launch { try { judge(key, urls) } finally { running.remove(key) } }.also { running[key] = it }
            }
        }
        withTimeoutOrNull(waitMs) { jobs.joinAll() }
    }

    private suspend fun judge(key: String, urls: List<String>) {
        var needed = false
        val t0 = System.currentTimeMillis()
        for (u in urls) {
            val ok = gate.withPermit { withContext(Dispatchers.IO) { runCatching { plays(u) }.getOrDefault(false) } }
            if (!ok) { needed = true; break }
        }
        record(key, needed)
        val verdict = if (needed) "headers needed (a plain request was refused)" else "no headers needed (plays without them)"
        ServerState.info("[Headers] ${key.substringBefore('|')}: $verdict - checked in ${System.currentTimeMillis() - t0} ms")
    }

    // ── the requests ─────────────────────────────────────────────────────────

    internal fun plays(url: String): Boolean {
        val hls = url.substringBefore('?').endsWith(".m3u8", true) || url.contains(".m3u8", true)
        return if (hls) playlistPlays(url) else filePlays(url)
    }

    private fun get(url: String, range: String?) = client.newCall(
        Request.Builder().url(url).header("User-Agent", PROBE_UA).apply { if (range != null) header("Range", range) }.build()
    ).execute()

    private fun notMedia(contentType: String?): Boolean =
        contentType != null && (contentType.startsWith("text/html", true) || contentType.contains("json", true) || contentType.contains("xml", true))

    private fun filePlays(url: String): Boolean = get(url, "bytes=0-1").use { r ->
        (r.code == 200 || r.code == 206) && !notMedia(r.header("Content-Type")) && r.body?.contentLength() != 0L
    }

    /** The first line of a playlist that is not a tag: the first variant playlist or the first segment. */
    internal fun firstUri(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun playlistPlays(url: String): Boolean {
        var cur = url
        repeat(3) {
            val text = get(cur, null).use { r ->
                if (r.code != 200) return false
                val stream = r.body?.byteStream() ?: return false
                val buf = ByteArray(256 * 1024)
                var n = 0
                while (n < buf.size) { val k = stream.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                String(buf, 0, n, Charsets.UTF_8)
            }
            if (!text.trimStart().startsWith("#EXTM3U")) return false
            val ref = firstUri(text) ?: return false
            val next = cur.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: return false
            val isPlaylist = text.contains("#EXT-X-STREAM-INF") || (!text.contains("#EXTINF") && ref.substringBefore('?').endsWith(".m3u8", true))
            if (!isPlaylist) {
                return get(next, "bytes=0-2047").use { r -> (r.code == 200 || r.code == 206) && !notMedia(r.header("Content-Type")) }
            }
            cur = next
        }
        return false
    }
}
