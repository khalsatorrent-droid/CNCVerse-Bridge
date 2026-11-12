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
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

    // ── Smart switch (relay and media server both on) ─────────────────────────
    //
    // When playback starts, the host's speed is compared with the video's bitrate, within DECIDE_MS:
    //  - files: the first answer is read for up to ~2 s (those bytes go on to the player, nothing is wasted); the bitrate is the
    //    file size over its running time (read from an MKV's header, else the usual running time of a movie / episode)
    //  - HLS: the first segment of the media playlist is fetched (at most 2 s); its size over its #EXTINF length is the bitrate
    // Host faster than the bitrate x MARGIN -> the relay passes it through; slower (or it stalls) -> the media server takes the
    // link over (it downloads ahead with several connections). Host speeds are kept for a while, so the next link of a host
    // that is known is decided at once.
    //
    // Two numbers here were what made both paths feel broken.
    //  - The measurement held the player's first byte: nothing was written until it finished, so every link started with up to
    //    4 s of nothing and then a burst. The window is halved (2 s, 1.2 s after the first byte, 3 MB) - still enough to tell a
    //    fast host from a slow one, without a stall the player shows as buffering.
    //  - MARGIN was 1.25, so a host measured at 25% above the video's bitrate was handed to the RELAY, which has no read-ahead
    //    at all: a live pipe with a few MB of slack. At that margin the smallest dip in the host's speed emptied the player and it
    //    buffered - and it was sent there precisely because it was "fast enough". A pass-through needs real headroom; below 2x the
    //    bitrate the disk read-ahead of the media server is the better answer.

    private const val DECIDE_MS = 2_000L
    private const val MEASURE_AFTER_FIRST_BYTE_MS = 1_200L
    private const val MEASURE_MAX_BYTES = 3 * 1024 * 1024
    private const val MARGIN = 2.0
    private const val SPEED_TTL_MS = 15 * 60_000L
    private const val RELAY = 1
    private const val MEDIA = 2

    private class Speed(val bps: Double, val at: Long)
    private val decisions = ConcurrentHashMap<String, Int>()
    private val hostSpeed = ConcurrentHashMap<String, Speed>()
    private val runtimeMin = ConcurrentHashMap<String, Int>()
    /** variant playlist url -> BANDWIDTH of its master entry (bits/s) */
    private val variantBw = ConcurrentHashMap<String, Long>()
    private val logged = ConcurrentHashMap.newKeySet<String>()
    /** audio / subtitle playlists of a master (#EXT-X-MEDIA): they say nothing about the video's bitrate, so they never decide */
    private val sidePlaylists = ConcurrentHashMap.newKeySet<String>()

    private fun mediaCan(hls: Boolean): Boolean {
        val p = MediaServer.prefs
        return p.relay && p.enabled && (if (hls) p.cacheHls else p.cacheFiles)
    }

    private fun knownSpeed(host: String?): Double? {
        val v = hostSpeed[host ?: return null] ?: return null
        return if (System.currentTimeMillis() - v.at <= SPEED_TTL_MS) v.bps else null
    }

    private fun noteSpeed(host: String?, bps: Double) {
        if (host == null) return
        if (hostSpeed.size > 2_000) hostSpeed.clear()
        hostSpeed[host] = Speed(bps, System.currentTimeMillis())
    }

    private fun mbit(bps: Double) = "%.1f Mbit/s".format(bps / 1_000_000.0)

    private fun linkName(url: String) = (LinkUrl.host(url) ?: "?") + " / " + LinkUrl.lastSegment(url).take(60)

    /** "[Play] <link>: relay | media server (why)", once per link. */
    internal fun logPlay(id: String, url: String, how: String, why: String = "") {
        if (logged.size > 5_000) logged.clear()
        if (!logged.add(id)) return
        ServerState.info("[Play] ${linkName(url)}: playing via $how" + if (why.isNotEmpty()) " - $why" else "")
    }

    private fun decide(id: String, url: String, kind: String, speedBps: Double?, bitrate: Double?, tookMs: Long, how: String): Int {
        val choice = when {
            speedBps == null || speedBps <= 0.0 -> MEDIA // nothing came in time: the host is too slow or stalls
            bitrate == null || bitrate <= 0.0 -> RELAY // bitrate unknown: the lighter path
            speedBps >= bitrate * MARGIN -> RELAY
            else -> MEDIA
        }
        if (decisions.size > 5_000) decisions.clear()
        decisions[id] = choice
        val sp = speedBps?.takeIf { it > 0 }?.let { mbit(it) } ?: "no data in time"
        val br = bitrate?.let { "~" + mbit(it) } ?: "unknown"
        val why = "$kind, host $sp ($how) vs video $br, decided in $tookMs ms"
        logPlay(id, url, if (choice == RELAY) "relay" else "media server", why)
        return choice
    }

    private fun relayNow(id: String, url: String, why: String): Int {
        decisions[id] = RELAY
        logPlay(id, url, "relay", why)
        return RELAY
    }

    /**
     * The media server's client with a shorter wait for data: a host that goes quiet for 15 s mid-video is asked again from the
     * byte reached (the player usually has more than that buffered), instead of 45 s of nothing.
     */
    private fun relayClient(id: String, src: MediaSource) =
        MediaServer.clientFor(id, src).newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /**
     * The stream pointed at the relay when the "Which links" setting (shared with the media server) picks it; null = the relay
     * leaves it alone. "Only links that need headers": links whose host refuses them without the extension's headers;
     * "Those + all HLS": every HLS link as well; "Every link": every http(s) link (other add-ons' links too).
     */
    fun rewrite(s: StremioStream, base: String, forWeb: Boolean, runtimeMinutes: Int = 0, force: Boolean = false): StremioStream? {
        val url = s.url ?: return null
        val mode = MediaServer.prefs.mode
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null || s.clearkey != null) return null
        // another add-on's link: only in "Every link" mode, or when it points at a home-network address the player may not reach
        val upstream = s.info?.linkType == "UPSTREAM"
        if (upstream && mode != "all" && !MediaServer.isPrivateHostOf(url)) return null
        if (!url.startsWith("http", true) || MediaServer.OWN_PATH.containsMatchIn(url)) return null
        // an extension's own local server is handled by LocalRelay / the media server
        if (MediaServer.isLoopbackUrl(url) || ServerState.isOwnUrl(url)) return null
        val path = url.substringBefore('?')
        if (path.endsWith(".mpd", true) || url.contains(".mpd?", true)) return null
        if (SeekProbe.isArchiveUrl(url)) return null
        val host = LinkUrl.host(url) ?: return null
        val headers = s.behaviorHints?.proxyHeaders?.request.orEmpty()
        val needsCf = runCatching { CfCookieStore.get(host) }.getOrNull() != null
        val hls = MediaServer.looksLikeHls(url, s.info?.linkType)
        // the host answered a plain request: the player could open the link itself
        val needsHeaders = needsCf || (headers.isNotEmpty() && HeaderNeed.verdict(url, headers) != false)
        val picked = when {
            force -> true
            needsHeaders -> true
            mode == "all" -> true
            mode == "hls" -> hls
            else -> forWeb && hls // the website's player needs CORS on playlists, whatever the mode
        }
        if (!picked) return null
        val id = MediaServer.register(url, if (forWeb && hls) headers + (WEB to "1") else headers)
        val tok = MediaServer.token(id, url)
        if (runtimeMinutes > 0) { if (runtimeMin.size > 5_000) runtimeMin.clear(); runtimeMin[id] = runtimeMinutes }
        val newUrl = if (hls) "$base/relay/$id/pl/$tok.m3u8" else "$base/relay/$id/f/$tok/${MediaServer.fileName(url)}"
        return s.copy(url = newUrl, behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
    }

    // ── Files and segments: straight pass-through ─────────────────────────────

    /** A direct file: passed through, or handed to the media server when the smart switch picked it. */
    suspend fun serveFile(call: ApplicationCall, id: String, tok: String, name: String) {
        if (decisions[id] == MEDIA && mediaCan(false)) {
            if (MediaServer.urlOfToken(id, tok) == null) return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return MediaServer.serveFile(call, id, name)
        }
        serveStream(call, id, tok, fileName = name)
    }

    private val CR_TOTAL = Regex("/(\\d+)\\s*$")
    private val CR_START = Regex("bytes\\s+(\\d+)-")
    private val RANGE_END = Regex("bytes=\\d*-(\\d+)")
    /** chunks of 128 KB the host may be ahead of the player (8 MB): the pass-through has no disk buffer, so this slack is what
     *  absorbs a host that hiccups mid-stream instead of leaving the player empty. */
    private const val PIPE_CHUNKS = 64
    /** times a dropped connection is asked again in a row (each wait a little longer) */
    private const val MAX_RESUMES = 6

    private val refusedLogged = ConcurrentHashMap.newKeySet<String>()

    suspend fun serveStream(call: ApplicationCall, id: String, tok: String, fileName: String? = null) {
        val src = MediaServer.sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = MediaServer.urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        requests.incrementAndGet()
        val head = call.request.httpMethod == HttpMethod.Head
        val t0 = System.currentTimeMillis()
        val okCall = relayClient(id, src).newCall(MediaServer.requestFor(src, url, call.request.header("Range")))
        // smart switch: the 4 s limit covers the whole decision, the host's first answer included
        val mayDecide = fileName != null && !head && decisions[id] == null && mediaCan(false) && knownSpeed(LinkUrl.host(url)) == null
        val measuring = java.util.concurrent.atomic.AtomicBoolean(mayDecide)
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = if (mayDecide) scope.launch {
            kotlinx.coroutines.delay(DECIDE_MS)
            if (measuring.get()) { stalled.set(true); okCall.cancel() }
        } else null
        val resp = try {
            withContext(Dispatchers.IO) { okCall.execute() }
        } catch (t: Exception) {
            watchdog?.cancel()
            if (stalled.get() && fileName != null) {
                // no answer within 4 s: the host is too slow for the relay
                decide(id, url, "file", null, null, System.currentTimeMillis() - t0, "no answer in time")
                return MediaServer.serveFile(call, id, fileName)
            }
            return call.respondText("Upstream unavailable: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        try {
            // a refused segment or file shows in the player only as "loading failed": the reason goes to the log (once per link)
            if (!resp.isSuccessful && !head) {
                if (refusedLogged.size > 500) refusedLogged.clear()
                if (refusedLogged.add(id)) ServerState.warn("[Relay] ${LinkUrl.host(url)} refused the request: HTTP ${resp.code}")
            }
            val body = resp.body
            val ctText = resp.header("Content-Type")
            // a playlist under a name without ".m3u8" (a rendition or audio group): rewritten like any other
            if (!head && resp.isSuccessful && (ctText?.contains("mpegurl", true) == true || MediaServer.isPlaylistName(url))) {
                val text = withContext(Dispatchers.IO) { body?.let { MediaServer.noBom(String(MediaServer.readCapped(it, MediaServer.MAX_PLAYLIST_BYTES))) }.orEmpty() }
                if (MediaServer.isM3u(text)) return respondPlaylist(call, id, src, resp.request.url.toString(), text)
                val textType = runCatching { ContentType.parse(ctText ?: "text/plain") }.getOrDefault(ContentType.Text.Plain)
                return call.respondText(text, textType, HttpStatusCode.fromValue(resp.code))
            }
            val status = HttpStatusCode.fromValue(resp.code)
            val ct = runCatching { ContentType.parse(ctText ?: "application/octet-stream") }.getOrDefault(ContentType.Application.OctetStream)
            val len = body?.contentLength()?.takeIf { it >= 0 }
            // ── smart switch: the first real read of a file (not a HEAD, not a small probe of its end) ──
            var pre: ByteArray? = null
            var preLen = 0
            var input: java.io.InputStream? = null
            if (fileName != null && !head && body != null && resp.isSuccessful) {
                if (decisions[id] == null && mediaCan(false) && (len == null || len >= 4L * 1024 * 1024)) {
                    if (!mayDecide) measuring.set(false)
                    val contentRange = resp.header("Content-Range")
                    val total = contentRange?.let { CR_TOTAL.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: len?.takeIf { resp.code == 200 }
                    val start = contentRange?.let { CR_START.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
                    val host = LinkUrl.host(url)
                    val stream = body.byteStream()
                    input = stream
                    var speed = knownSpeed(host)
                    var how = "known"
                    if (speed == null) {
                        // read for a moment: at most 4 s since the request went out, 2.5 s after the first byte, 6 MB
                        val buf = ByteArray(MEASURE_MAX_BYTES)
                        var firstByteAt = 0L
                        try {
                            withContext(Dispatchers.IO) {
                                while (preLen < buf.size) {
                                    val now = System.currentTimeMillis()
                                    if (now - t0 >= DECIDE_MS - 150 || (firstByteAt > 0 && now - firstByteAt >= MEASURE_AFTER_FIRST_BYTE_MS)) break
                                    val n = stream.read(buf, preLen, minOf(BUF, buf.size - preLen))
                                    if (n < 0) break
                                    if (n > 0 && firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
                                    preLen += n
                                }
                            }
                        } catch (_: Exception) {
                            // cancelled by the watchdog (the host stalled) or dropped: whatever came in counts
                        } finally {
                            measuring.set(false)
                            watchdog?.cancel()
                        }
                        val window = if (firstByteAt > 0) maxOf(100L, System.currentTimeMillis() - firstByteAt) else 0L
                        val measured = if (window == 0L || preLen == 0) 0.0 else preLen * 8_000.0 / window
                        if (measured > 0) noteSpeed(host, measured)
                        speed = measured
                        how = if (stalled.get()) "stalled" else "measured"
                        // a stalled connection was cancelled: its bytes cannot go on to the player
                        if (stalled.get()) preLen = 0 else pre = buf
                    }
                    val head0 = pre
                    val dur = (if (start == 0L && head0 != null && preLen > 0) runCatching { MkvProbe.parse(head0.copyOf(minOf(preLen, 512 * 1024)))?.durationSec }.getOrNull() else null)
                        ?: MkvProbe.knownDuration(url)
                        ?: ((runtimeMin[id] ?: 45) * 60.0)
                    val bitrate = total?.let { it * 8.0 / dur }
                    val choice = decide(id, url, "file", speed, bitrate, System.currentTimeMillis() - t0, how)
                    if (choice == MEDIA || how == "stalled") {
                        runCatching { resp.close() }
                        if (choice == MEDIA) return MediaServer.serveFile(call, id, fileName)
                        // the relay was chosen but the measuring connection is gone: one new request for the player
                        return serveStream(call, id, tok)
                    }
                } else if (decisions[id] == null && !mediaCan(false)) {
                    logPlay(id, url, "relay")
                }
            }
            // not measured here (a HEAD, a small probe, an error): the watchdog must not cut this answer
            if (measuring.getAndSet(false)) watchdog?.cancel()
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
                // the measured start goes out first; the 6 MB buffer is let go right after
                val firstBytes = arrayOf(pre)
                val firstLen = preLen
                pre = null
                val stream = input ?: body.byteStream()
                // where this answer starts in the file, and where the player's range ends (for picking up after a drop)
                val startAt = resp.header("Content-Range")?.let { CR_START.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
                val rangeEnd = call.request.header("Range")?.let { RANGE_END.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                val resumable = resp.code == 206 || resp.header("Accept-Ranges")?.contains("bytes", true) == true
                val name = linkName(url)
                call.respondBytesWriter(ct, status, len) {
                    var written = 0L
                    firstBytes[0]?.let { if (firstLen > 0) { writeFully(it, 0, firstLen); written += firstLen } }
                    firstBytes[0] = null
                    // The host is read by its own coroutine into a small pipe (up to PIPE_CHUNKS x 128 KB), so it keeps
                    // sending while the player is busy; a dropped or stalled connection is picked up again from the
                    // byte reached (a new request with Range), so the player never sees the answer cut short.
                    val pipe = kotlinx.coroutines.channels.Channel<ByteArray>(PIPE_CHUNKS)
                    // the connection being read right now: closing it is what frees a read that is waiting on the host
                    val live = java.util.concurrent.atomic.AtomicReference<okhttp3.Response?>(null)
                    val reader = scope.launch(Dispatchers.IO) {
                        var cur = stream
                        var curResp: okhttp3.Response? = null
                        var pos = startAt + firstLen
                        var retries = 0
                        try {
                            while (true) {
                                val buf = ByteArray(BUF)
                                val n = try { cur.read(buf) } catch (e: java.io.IOException) { -2 }
                                if (n > 0) {
                                    pipe.send(if (n == buf.size) buf else buf.copyOf(n))
                                    pos += n
                                    retries = 0
                                    continue
                                }
                                if (n == 0) continue
                                val expectedEnd = len?.let { startAt + it }
                                if (n == -1 && (expectedEnd == null || pos >= expectedEnd)) break // the whole answer is out
                                // the host dropped the connection (or ended early): ask again from where it stopped
                                if (!resumable || retries >= MAX_RESUMES) {
                                    ServerState.warn("[Relay] $name: the host stopped sending at ${pos / 1_048_576} MB and could not be resumed")
                                    break
                                }
                                retries++
                                runCatching { curResp?.close() }
                                kotlinx.coroutines.delay(300L * retries)
                                val again = runCatching {
                                    relayClient(id, src).newCall(
                                        MediaServer.requestFor(src, url, "bytes=$pos-" + (rangeEnd?.toString() ?: ""))
                                    ).execute()
                                }.getOrNull()
                                val from = again?.header("Content-Range")?.let { CR_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                                if (again == null || again.code != 206 || from != pos || again.body == null) {
                                    runCatching { again?.close() }
                                    continue // counted as a try; the next one waits a little longer
                                }
                                ServerState.info("[Relay] $name: the host dropped the connection at ${pos / 1_048_576} MB, picked up again (try $retries)")
                                curResp = again
                                live.set(again)
                                cur = again.body!!.byteStream()
                            }
                        } finally {
                            runCatching { curResp?.close() }
                            pipe.close()
                        }
                    }
                    try {
                        for (chunk in pipe) { writeFully(chunk, 0, chunk.size); written += chunk.size }
                    } catch (t: Throwable) {
                        // the player closed the connection (seek / stop): the host is let go too
                        if (t is kotlinx.coroutines.CancellationException) { reader.cancel(); throw t }
                    } finally {
                        reader.cancel()
                        pipe.cancel()
                        runCatching { live.get()?.close() }
                        runCatching { resp.close() }
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
        // the smart switch gave this link to the media server: all its playlists come from there
        if (decisions[id] == MEDIA && mediaCan(true)) return MediaServer.servePlaylist(call, id, tok)
        requests.incrementAndGet()
        val t0 = System.currentTimeMillis()
        val (finalUrl, text) = MediaServer.takeReadPlaylist(url) ?: try {
            withContext(Dispatchers.IO) {
                MediaServer.clientFor(id, src).newCall(MediaServer.requestFor(src, url)).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    // capped: a link labelled HLS that is really a video file must not be read into memory
                    r.request.url.toString() to (r.body?.let { MediaServer.noBom(String(MediaServer.readCapped(it, MediaServer.MAX_PLAYLIST_BYTES))) }.orEmpty())
                }
            }
        } catch (t: Exception) {
            // (written to the log: the player only shows "loading failed", and this is the only place the reason is known)
            ServerState.warn("[Relay] playlist from ${LinkUrl.host(url)} failed: ${t.message}")
            return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        if (!MediaServer.isM3u(text)) {
            ServerState.warn("[Relay] ${LinkUrl.host(finalUrl)} did not answer with a playlist: \"${MediaServer.sample(text)}\"")
            return call.respondText("Source did not return a playlist", status = HttpStatusCode.BadGateway)
        }
        if (text.contains("#EXTINF")) {
            // An audio or subtitle playlist is not what the link is judged by. The player opens it together with the video's
            // playlist, and when it came first the whole link was decided on a few hundred kbit/s of audio (or a subtitle
            // file): "fast enough", relay - however heavy the video was. The video's playlist decides; this one is just served.
            if (decisions[id] == null && mediaCan(true) && sidePlaylists.contains("$id|$url")) {
                return respondPlaylist(call, id, src, finalUrl, text)
            }
            if (decisions[id] == null && mediaCan(true)) {
                val choice = withContext(Dispatchers.IO) { decideHls(id, src, url, finalUrl, text, t0) }
                if (choice == MEDIA) return MediaServer.servePlaylist(call, id, tok)
            } else if (decisions[id] == null) logPlay(id, url, "relay")
        }
        respondPlaylist(call, id, src, finalUrl, text)
    }

    /** First segment of a media playlist: its size over its length is the bitrate, its download time the host's speed. */
    private fun decideHls(id: String, src: MediaSource, url: String, finalUrl: String, text: String, t0: Long): Int {
        var dur = 0.0
        var seg: String? = null
        // a playlist that cuts one big file into byte ranges: only the first piece is measured, never the whole file
        var range: String? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF:")) dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
            else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                val spec = l.substringAfter(':').trim()
                val n = spec.substringBefore('@').trim().toLongOrNull()
                val o = spec.substringAfter('@', "").trim().toLongOrNull() ?: 0L
                if (n != null && n > 0) range = "bytes=$o-${o + n - 1}"
            }
            else if (l.isNotEmpty() && !l.startsWith("#") && dur > 0) { seg = MediaServer.resolve(finalUrl, l); break }
        }
        val segUrl = seg ?: return relayNow(id, url, "HLS, no segment to measure")
        val host = LinkUrl.host(segUrl)
        val known = knownSpeed(host)
        val bw = variantBw[url]?.toDouble()
        if (known != null && bw != null) return decide(id, url, "HLS", known, bw, System.currentTimeMillis() - t0, "known")
        val left = maxOf(500L, DECIDE_MS - (System.currentTimeMillis() - t0))
        val client = MediaServer.clientFor(id, src).newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build()
        val s0 = System.currentTimeMillis()
        // Read as a stream and counted as it arrives. It used to be read whole or not at all: a segment that did not finish inside
        // the 2 s (a 4K segment is 10 MB and more) counted as "no data in time", so a perfectly fast host was judged too slow
        // and the link handed to the media server.
        var got = 0L
        var full = -1L
        var refused = false
        runCatching {
            client.newCall(MediaServer.requestFor(src, segUrl, range)).execute().use { r ->
                if (!r.isSuccessful) { refused = true; return@use }
                val body = r.body ?: return@use
                full = body.contentLength()
                val ins = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) { if (full < 0) full = got; break }
                    got += n
                }
            }
        }
        val took = maxOf(50L, System.currentTimeMillis() - s0)
        if (refused) return relayNow(id, url, "HLS, the first segment was refused")
        val speed = if (got > 0) got * 8_000.0 / took else 0.0
        if (speed > 0) noteSpeed(host, speed)
        val bitrate = bw ?: if (full > 0 && dur > 0) full * 8.0 / dur else null
        return decide(id, url, "HLS", speed, bitrate, System.currentTimeMillis() - t0, "first segment")
    }

    private suspend fun respondPlaylist(call: ApplicationCall, id: String, src: MediaSource, url: String, raw: String) {
        val use = if (StremioServer.streamPrefs.bestHls) MediaServer.keepBestVariant(raw) else raw
        val out = withContext(Dispatchers.IO) { rewritePlaylist(id, src, url, use) }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(out, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")
    private val BW = Regex("[:,]BANDWIDTH=(\\d+)")

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
        var pendingBw: Long? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line)
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        nextIsVariant = true
                        pendingBw = BW.find(line)?.groupValues?.get(1)?.toLongOrNull()
                    }
                    val isPlTag = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    val isSide = line.startsWith("#EXT-X-MEDIA")
                    val isKey = line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")
                    out.append(if (line.contains("URI=\"")) URI_ATTR.replace(line) { m ->
                        val abs = MediaServer.resolve(playlistUrl, m.groupValues[1])
                        if (isSide) { if (sidePlaylists.size > 5_000) sidePlaylists.clear(); sidePlaylists.add("$id|$abs") }
                        "URI=\"" + target(abs, isPlTag || MediaServer.isPlaylistName(abs), isKey) + "\""
                    } else line)
                }
                else -> {
                    val abs = MediaServer.resolve(playlistUrl, line)
                    val isPl = nextIsVariant || MediaServer.isPlaylistName(abs)
                    if (nextIsVariant) pendingBw?.let { if (variantBw.size > 5_000) variantBw.clear(); variantBw[abs] = it }
                    nextIsVariant = false
                    pendingBw = null
                    out.append(target(abs, isPl, false))
                }
            }
            out.append('\n')
        }
        return out.toString()
    }
}
