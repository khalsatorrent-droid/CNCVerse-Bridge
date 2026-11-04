package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.network.CfCookieStore
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The relay (Settings -> Media server -> Relay): a pass-through for links whose host wants headers (Referer, Cookie, User-Agent,
 * a solved Cloudflare check) that the player cannot send.
 *
 *  - Video files: every request of the player (with its Range, so seeking works) is sent on to the host with the extension's
 *    headers, and the answer is streamed straight back. Nothing is downloaded ahead and nothing is written to disk.
 *  - HLS: the playlists are fetched with the headers and rewritten. Segments whose host plays them without the headers (asked
 *    once per host, see [HeaderNeed.checkFileNow]) keep their real address, so the video goes from the host to the player
 *    directly; only the segments that need the headers (and encryption keys) pass through here.
 *
 * Faster to start and lighter on the device than the media server; the media server stays the better choice for hosts that are
 * slow or drop connections (it downloads ahead with several connections).
 *
 * Every relay link carries a signed token ([MediaServer.token]), so the relay cannot be used as an open proxy.
 */
object Relay {
    /** Marks a source registered for the website's player (hls.js needs CORS on every segment: they all pass through). */
    private const val WEB = "X-Cnc-Relay-Web"
    private const val BUF = 128 * 1024

    private val requests = AtomicLong(0)
    private val active = AtomicInteger(0)

    /** Requests passed through since start / streams being passed through right now. */
    fun stats(): Pair<Long, Int> = requests.get() to active.get()

    /** The stream pointed at the relay when its host needs headers; null = the relay leaves it alone. */
    fun rewrite(s: StremioStream, base: String, forWeb: Boolean): StremioStream? {
        val url = s.url ?: return null
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null || s.clearkey != null) return null
        if (s.info?.linkType == "UPSTREAM") return null
        if (!url.startsWith("http", true) || MediaServer.OWN_PATH.containsMatchIn(url)) return null
        // an extension's own local server is handled by LocalRelay / the media server
        if (MediaServer.isLoopbackUrl(url) || ServerState.isOwnUrl(url)) return null
        val path = url.substringBefore('?')
        if (path.endsWith(".mpd", true) || url.contains(".mpd?", true)) return null
        if (SeekProbe.isArchiveUrl(url)) return null
        val host = LinkUrl.host(url) ?: return null
        val headers = s.behaviorHints?.proxyHeaders?.request.orEmpty()
        val needsCf = runCatching { CfCookieStore.get(host) }.getOrNull() != null
        if (headers.isEmpty() && !needsCf) return null
        // the host answered a plain request: the player can open the link itself
        if (!needsCf && HeaderNeed.verdict(url, headers) == false) return null
        val hls = MediaServer.looksLikeHls(url, s.info?.linkType)
        val id = MediaServer.register(url, if (forWeb && hls) headers + (WEB to "1") else headers)
        val tok = MediaServer.token(id, url)
        val newUrl = if (hls) "$base/relay/$id/pl/$tok.m3u8" else "$base/relay/$id/f/$tok/${MediaServer.fileName(url)}"
        return s.copy(url = newUrl, behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
    }

    // ── Files and segments: straight pass-through ─────────────────────────────

    suspend fun serveStream(call: ApplicationCall, id: String, tok: String) {
        val src = MediaServer.sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = MediaServer.urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        requests.incrementAndGet()
        val head = call.request.httpMethod == HttpMethod.Head
        val resp = try {
            withContext(Dispatchers.IO) {
                MediaServer.clientFor(id, src).newCall(MediaServer.requestFor(src, url, call.request.header("Range"))).execute()
            }
        } catch (t: Exception) {
            return call.respondText("Upstream unavailable: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        try {
            val body = resp.body
            val ctText = resp.header("Content-Type")
            // a playlist under a name without ".m3u8" (a rendition or audio group): rewritten like any other
            if (!head && resp.isSuccessful && (ctText?.contains("mpegurl", true) == true || MediaServer.isPlaylistName(url))) {
                val text = withContext(Dispatchers.IO) { body?.string().orEmpty() }
                if (text.trimStart().startsWith("#EXTM3U")) return respondPlaylist(call, id, src, resp.request.url.toString(), text)
                val textType = runCatching { ContentType.parse(ctText ?: "text/plain") }.getOrDefault(ContentType.Text.Plain)
                return call.respondText(text, textType, HttpStatusCode.fromValue(resp.code))
            }
            val status = HttpStatusCode.fromValue(resp.code)
            val ct = runCatching { ContentType.parse(ctText ?: "application/octet-stream") }.getOrDefault(ContentType.Application.OctetStream)
            val len = body?.contentLength()?.takeIf { it >= 0 }
            resp.header("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
            call.response.header(HttpHeaders.AcceptRanges, resp.header("Accept-Ranges") ?: "bytes")
            resp.header("Last-Modified")?.let { call.response.header(HttpHeaders.LastModified, it) }
            resp.header("ETag")?.let { call.response.header(HttpHeaders.ETag, it) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (head || body == null) {
                call.respond(object : OutgoingContent.NoContent() {
                    override val contentLength: Long? = len
                    override val contentType: ContentType = ct
                    override val status: HttpStatusCode = status
                })
                return
            }
            active.incrementAndGet()
            try {
                call.respondBytesWriter(ct, status, len) {
                    val input = body.byteStream()
                    val buf = ByteArray(BUF)
                    try {
                        while (true) {
                            val n = withContext(Dispatchers.IO) { input.read(buf) }
                            if (n < 0) break
                            if (n > 0) writeFully(buf, 0, n)
                        }
                    } catch (t: Throwable) {
                        // the player closed the connection (seek / stop) or the host dropped it: end this answer
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    }
                }
            } finally {
                active.decrementAndGet()
            }
        } finally {
            runCatching { resp.close() }
        }
    }

    // ── HLS playlists ─────────────────────────────────────────────────────────

    suspend fun servePlaylist(call: ApplicationCall, id: String, tok: String) {
        val src = MediaServer.sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = MediaServer.urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        requests.incrementAndGet()
        val (finalUrl, text) = try {
            withContext(Dispatchers.IO) {
                MediaServer.clientFor(id, src).newCall(MediaServer.requestFor(src, url)).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    r.request.url.toString() to (r.body?.string().orEmpty())
                }
            }
        } catch (t: Exception) {
            return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        if (!text.trimStart().startsWith("#EXTM3U")) return call.respondText("Source did not return a playlist", status = HttpStatusCode.BadGateway)
        respondPlaylist(call, id, src, finalUrl, text)
    }

    private suspend fun respondPlaylist(call: ApplicationCall, id: String, src: MediaSource, url: String, raw: String) {
        val use = if (StremioServer.streamPrefs.bestHls) MediaServer.keepBestVariant(raw) else raw
        val out = withContext(Dispatchers.IO) { rewritePlaylist(id, src, url, use) }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(out, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    /**
     * Playlists (variants, audio / subtitle groups) point back at the relay; segments go direct when their host plays them
     * without the headers, through the relay otherwise; keys always pass through (small, and often guarded).
     * Links are relative ("../pl/…", "../s/…"), so any address of this bridge works.
     */
    private fun rewritePlaylist(id: String, src: MediaSource, playlistUrl: String, text: String): String {
        val web = src.headers.containsKey(WEB)
        val headers = src.headers.filterKeys { it != WEB }
        val decided = HashMap<String, Boolean>()
        fun direct(u: String): Boolean {
            if (web) return false
            if (MediaServer.isLoopbackUrl(u)) return false
            val host = LinkUrl.host(u) ?: return false
            if (runCatching { CfCookieStore.get(host) }.getOrNull() != null) return false
            return decided.getOrPut(host) { HeaderNeed.verdict(u, headers)?.not() ?: HeaderNeed.checkFileNow(u, headers) }
        }
        fun target(abs: String, playlist: Boolean, alwaysRelay: Boolean): String = when {
            !abs.startsWith("http", true) -> abs
            playlist -> "../pl/${MediaServer.token(id, abs)}.m3u8"
            !alwaysRelay && direct(abs) -> abs
            else -> "../s/${MediaServer.token(id, abs)}"
        }
        val out = StringBuilder(text.length + 1024)
        var nextIsVariant = false
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line)
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsVariant = true
                    val isPlTag = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    val isKey = line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")
                    out.append(if (line.contains("URI=\"")) URI_ATTR.replace(line) { m ->
                        val abs = MediaServer.resolve(playlistUrl, m.groupValues[1])
                        "URI=\"" + target(abs, isPlTag || MediaServer.isPlaylistName(abs), isKey) + "\""
                    } else line)
                }
                else -> {
                    val abs = MediaServer.resolve(playlistUrl, line)
                    val isPl = nextIsVariant || MediaServer.isPlaylistName(abs)
                    nextIsVariant = false
                    out.append(target(abs, isPl, false))
                }
            }
            out.append('\n')
        }
        return out.toString()
    }
}
