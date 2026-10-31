package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Settings of the caching media server (Settings -> Media server).
 */
@Serializable
data class MediaServerPrefs(
    /** Master switch. Off = streams go out exactly as the extension produced them. */
    val enabled: Boolean = false,
    /**
     * "headers": only streams that need a Referer / Cookie / custom header.
     * "hls": those plus every HLS stream (smooth playback of slow hosts).
     * "all": every http(s) stream.
     */
    val mode: String = "all",
    /** Download direct video files (mp4/mkv/...) to this device first, then serve them from disk. */
    val cacheFiles: Boolean = true,
    /** Open the first 2 direct-file links of every result list ahead of time (first 8 MB each). Off by default: it costs data and connections. */
    val prewarm: Boolean = false,
    /** Cache HLS playlists and segments on this device. */
    val cacheHls: Boolean = true,
    /** HLS segments fetched ahead of the player. */
    val prefetchSegments: Int = 30,
    /** How far (MB) past the playing position a file download may run (0 = download the whole file). */
    val readAheadMb: Int = 512,
    /** Connections used per file at once (the main one plus the rest). Pixeldrain-bypass sites suggest 8 or 16; 1-20 works. */
    val connections: Int = 8,
    /** Upper limit of the cache folder, in MB (never more than 2048). Oldest entries are deleted first. */
    val maxCacheMb: Int = 2048,
    /** Entries not used for this many hours are deleted. */
    val keepHours: Int = 24,
    /** Settings generation (older files get the newer defaults once). */
    val ver: Int = 5,
)

@Serializable
private class MediaSource(val url: String, val headers: Map<String, String> = emptyMap())

@Serializable
private class FileMeta(
    val total: Long,
    val ranges: Boolean,
    val mime: String,
    val have: List<List<Long>> = emptyList(),
)

data class MediaServerStats(val entries: Int, val bytes: Long, val activeDownloads: Int, val served: Long)

/**
 * A small caching media server inside the bridge (the idea of pencarimovie-server).
 *
 * Some links only play when the request carries a Referer, Cookie or User-Agent that the extension
 * knows. Players often cannot send those. With the media server on, such a stream is rewritten to
 * `/media/...` on this bridge. The bridge then
 *  - downloads the file (or the HLS playlist and its segments) itself, with the headers and cookies
 *    the extension asked for, onto this device's storage,
 *  - and serves the requesting device from that copy, with full range / seek support, while the
 *    download is still running.
 *
 * All paths that carry a remote URL are signed (HMAC), so the server cannot be used as an open proxy.
 */
object MediaServer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** The cache folder never grows past this, whatever the settings file says. */
    const val HARD_MAX_MB = 5120
    /** Largest read-ahead window the settings accept (0 is no longer "the whole file"). */
    const val MAX_READ_AHEAD_MB = 2048
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile var prefs: MediaServerPrefs = MediaServerPrefs()
        private set
    private var prefsFile: File? = null
    @Volatile private var root: File? = null
    @Volatile private var secret: ByteArray = ByteArray(32)
    private val served = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetchLimit = Semaphore(12)

    private val sources = ConcurrentHashMap<String, MediaSource>()
    private val files = ConcurrentHashMap<String, FileEntry>()

    // ── Setup / settings ──────────────────────────────────────────────────────

    fun init(cacheDir: String) {
        val base = File(cacheDir)
        prefsFile = File(base, "media_server.json")
        prefs = (runCatching { prefsFile?.takeIf { it.exists() }?.let { json.decodeFromString<MediaServerPrefs>(it.readText()) } }
            .getOrNull() ?: MediaServerPrefs()).let {
            var q = it.copy(maxCacheMb = it.maxCacheMb.coerceIn(256, HARD_MAX_MB))
            if (q.ver < 2) q = q.copy(ver = 2, mode = if (q.mode == "headers") "hls" else q.mode, prefetchSegments = maxOf(q.prefetchSegments, 30))
            // v3: files are kept in 8 MB pieces and only a window around the playing position is kept on disk
            if (q.ver < 3) q = q.copy(ver = 3, readAheadMb = if (q.readAheadMb >= 1024 || q.readAheadMb == 0) 256 else q.readAheadMb)
            // v4: aggressive read-ahead (512 MB) - a value that was still the old default (256 MB or less) is raised once
            if (q.ver < 4) q = q.copy(ver = 4, readAheadMb = if (q.readAheadMb in 1..256) 512 else q.readAheadMb)
            // v5: the 1 GB read-ahead of 1.5.16/1.5.17 goes back to the 512 MB default
            if (q.ver < 5) q = q.copy(ver = 5, readAheadMb = if (q.readAheadMb == 1024) 512 else q.readAheadMb)
            q
        }
        val dir = File(base, "media_cache")
        dir.mkdirs()
        root = dir
        val sf = File(dir, "secret.key")
        secret = runCatching { if (sf.exists() && sf.length() >= 16) sf.readBytes() else null }.getOrNull()
            ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it); runCatching { sf.writeBytes(it) } }
        scope.launch { runCatching { trim() } }
        // Old entries go after "Keep hours" even if nothing is being played
        if (cleaner?.isActive != true) cleaner = scope.launch { while (true) { delay(10 * 60_000L); runCatching { trim() } } }
        if (monitor?.isActive != true) monitor = scope.launch { while (true) { delay(MONITOR_MS); runCatching { checkPlayback() } } }
    }
    @Volatile private var monitor: kotlinx.coroutines.Job? = null
    private const val MONITOR_MS = 5_000L

    /**
     * Every 5 seconds: where is each player now? Files - the lowest position of the connections reading it; HLS - the last
     * segment asked for. The read-ahead window after that position is topped up (a stopped downloader is started again, the
     * download is pointed at the first missing piece after the position, the next segments are queued).
     */
    private fun checkPlayback() {
        if (!prefs.enabled) return
        val now = System.currentTimeMillis()
        for (e in files.values) {
            if (e.warmOnly || now - e.lastAccess > 3 * 60_000L || e.passthrough || !e.ready || e.complete || e.total <= 0 || !e.ranges || e.isPlaylist) continue
            val pos = playPos(e)
            val ahead = if (prefs.readAheadMb > 0) prefs.readAheadMb * 1024L * 1024L else e.total
            val want = minOf(e.total, pos + ahead)
            if (e.have.firstGapFrom(maxOf(pos, e.floor)) >= want) continue // the window is full
            if (e.error != null && e.worker == null) e.error = null
            // How much is already on disk, in one piece, right after the player? When HALF of the read-ahead window (at least 8 MB)
            // or less is left, the refill starts at full strength: every extra connection is brought back and the main download is
            // pointed at the first missing piece after the ready data, so the window is filled again long before the player gets to it.
            val contiguous = e.have.contiguousEnd(pos) - pos
            val urgent = contiguous < maxOf(8L * 1_048_576L, minOf(ahead, 1L shl 30) / 2)
            if (e.error != null && e.worker == null) e.error = null
            if (e.worker == null) { e.stop = false; ensureWorker(e) }
            else {
                startBoosters(e)
                // a heavy source takes seconds to start a request: it is restarted only when the player is about to run dry
                if (urgent && (!isHeavy(e) || contiguous < 16L * 1_048_576L)) e.focus = e.have.firstGapFrom(maxOf(pos, e.floor)).takeIf { it < want } ?: pos
                else if (contiguous < 2L * ChunkStore.CHUNK) e.focus = pos
            }
            val line = "[MediaServer] playback at ${pos / 1_048_576} MB of ${e.total / 1_048_576} MB, ${contiguous / 1_048_576} MB ready ahead" +
                if (urgent) " - half or less left, refilling" else " - read-ahead topped up"
            if (urgent) ServerState.info(line) else ServerState.debug(line)
        }
        for ((id, v) in hlsPlay) {
            if (now - v.third > 3 * 60_000L) { hlsPlay.remove(id); continue }
            val src = sourceFor(id) ?: continue
            prefetch(id, src, v.first, v.second + 1)
        }
    }
    @Volatile private var cleaner: kotlinx.coroutines.Job? = null

    /** Reloads the saved settings (after a backup import). */
    fun reload(cacheDir: String) = init(cacheDir)

    fun updatePrefs(p: MediaServerPrefs) {
        val clean = p.copy(
            mode = if (p.mode in setOf("headers", "hls", "all")) p.mode else "hls",
            prefetchSegments = p.prefetchSegments.coerceIn(0, 60),
            readAheadMb = p.readAheadMb.coerceIn(0, MAX_READ_AHEAD_MB),
            connections = p.connections.coerceIn(1, 20),
            maxCacheMb = p.maxCacheMb.coerceIn(256, HARD_MAX_MB),
            keepHours = p.keepHours.coerceIn(1, 24 * 365),
        )
        prefs = clean
        runCatching { prefsFile?.writeText(json.encodeToString(clean)) }
            .onFailure { ServerState.warn("Failed to save media_server.json: ${it.message}") }
    }

    fun shutdown() {
        files.values.forEach { it.stop = true }
    }

    fun stats(): MediaServerStats {
        val dir = root
        val size = dir?.let { dirSize(it) } ?: 0L
        val entries = dir?.listFiles()?.count { it.isDirectory } ?: 0
        return MediaServerStats(entries, size, files.values.count { it.worker != null }, served.get())
    }

    fun clearCache(): Int {
        val dir = root ?: return 0
        files.values.forEach { it.stop = true }
        files.clear()
        var n = 0
        dir.listFiles()?.forEach { if (it.isDirectory) { it.deleteRecursively(); n++ } }
        segLocks.clear(); playlistCache.clear(); playlistSegs.clear(); segPos.clear(); sources.clear()
        return n
    }

    private fun capBytes(): Long = prefs.maxCacheMb.coerceIn(256, HARD_MAX_MB) * 1024L * 1024L

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun lastUsed(d: File): Long = files[d.name]?.lastAccess?.takeIf { it > 0 } ?: d.lastModified()

    private val evictLock = Any()
    @Volatile private var sizeCache = 0L
    @Volatile private var sizeAt = 0L

    /**
     * Keeps the cache folder under its limit by deleting previously cached items, least recently used
     * first, until [extra] more bytes fit. The entry [keepId] (the one being written) is never deleted
     * as a whole; if it is still too big, its oldest HLS segments go. [extra] > 0 means that entry is
     * about to be (re)allocated at that size, so its current size is not counted.
     * Returns true when [extra] bytes fit afterwards.
     */
    private fun makeRoom(extra: Long, keepId: String?): Boolean = synchronized(evictLock) {
        val dir = root ?: return false
        val cap = capBytes()
        val nowMs = System.currentTimeMillis()
        var total = if (extra <= 0 && nowMs - sizeAt < 1_000L) sizeCache else dirSize(dir).also { sizeCache = it; sizeAt = nowMs }
        if (extra > 0 && keepId != null) total -= dirSize(File(dir, keepId))
        if (total + extra <= cap) return true
        val victims = dir.listFiles()?.filter { it.isDirectory && it.name != keepId }?.sortedBy { lastUsed(it) } ?: emptyList()
        for (d in victims) {
            if (total + extra <= cap) break
            val sz = dirSize(d)
            files.remove(d.name)?.stop = true
            d.deleteRecursively()
            total -= sz
        }
        if (total + extra > cap && keepId != null && extra <= 0) {
            val segs = File(dir, "$keepId/hls").listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
            for (f in segs) {
                if (total <= cap) break
                total -= f.length(); f.delete()
            }
        }
        total + extra <= cap
    }

    /** Deletes expired entries, then the least recently used ones while the folder is over its limit. */
    private fun trim() {
        val dir = root ?: return
        val now = System.currentTimeMillis()
        val ttl = prefs.keepHours * 3_600_000L
        val busyMs = 3 * 60_000L
        val entries = dir.listFiles()?.filter { it.isDirectory } ?: return
        entries.filter { now - lastUsed(it) > busyMs && now - lastUsed(it) > ttl }
            .forEach { files.remove(it.name)?.stop = true; it.deleteRecursively() }
        makeRoom(0, null)
    }

    // ── Rewriting streams ─────────────────────────────────────────────────────

    private val OWN_PATH = Regex("^https?://[^/]+/(proxy/|decrypt|init_decrypt|media/)", RegexOption.IGNORE_CASE)

    /**
     * Other addresses of the same file, tried when the given one answers a range request with the whole file:
     * the fast Pixeldrain mirror (cdn.pixeldrain.eu.cc, no download limit) has Pixeldrain's own API address behind
     * it, which honours range requests.
     */
    internal fun alternatesOf(url: String): List<String> {
        val m = Regex("^https?://cdn\\.pixeldrain\\.eu\\.cc/([A-Za-z0-9_-]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE).find(url) ?: return emptyList()
        val id = m.groupValues[1]
        return listOf("pixeldrain.com", "pixeldrain.dev", "pixeldrain.net").map { "https://$it/api/file/$id" }
    }

    /** Result of [probeRanges]: [url] answers a range request with 206; [total] is the file size, -1 when unknown. */
    private class RangeProbe(val url: String, val total: Long)

    /**
     * Finds out whether a file that answered the open-ended "bytes=0-" with a plain 200 can seek after all. Tried, in order,
     * on the address itself, on the file its redirects end at ([finalUrl], e.g. a CDN node with its own address) and on the
     * known alternates: a closed range at the start ("bytes=0-1"), then a closed range in the middle of the file (some
     * servers only ignore ranges that start at 0). The first address that answers 206 with the bytes asked for wins.
     * [length] is the size the 200 response announced (-1 unknown): it places the mid-file probe.
     */
    private fun probeRanges(e: FileEntry, client: okhttp3.OkHttpClient, finalUrl: String?, length: Long): RangeProbe? {
        val candidates = (listOf(e.url) + listOfNotNull(finalUrl) + alternatesOf(e.url)).distinct()
        for (u in candidates) {
            val starts = if (length > 4_000_000L) listOf(0L, (length / 2)) else listOf(0L)
            for (start in starts) {
                val total = runCatching {
                    client.newCall(requestFor(e.src, u, "bytes=$start-${start + 1}")).execute().use { r ->
                        val cr = r.header("Content-Range")
                        if (r.code == 206 && (parseStart(cr) ?: start) == start) (parseTotal(cr) ?: length) else null
                    }
                }.getOrNull()
                if (total != null) return RangeProbe(u, total)
            }
        }
        return null
    }

    /** True while a player is reading a file that is not fully downloaded yet (read within the last 20 s): background work should wait. */
    fun isStreaming(): Boolean {
        val now = System.currentTimeMillis()
        return files.values.any { !it.stop && !it.complete && now - it.lastAccess < 20_000L }
    }

    private fun hostOf(url: String): String = LinkUrl.host(url) ?: url

    /**
     * "Heavy" sources: a request costs the origin a lot (a self-hosted add-on that pulls the file from Telegram, a NAS...).
     * Every request holds a worker of the origin for as long as it is open and takes seconds to start, so eight connections plus
     * the restarts of a seek used up its workers and the playback stalled. Such a source gets few connections, long requests that
     * end by themselves and fewer restarts. A host is heavy when it is on the local network, or when its requests take over
     * [HEAVY_TTFB_MS] to start answering.
     */
    private class Ttfb { var avg = 0L; var seen = 0; var at = 0L }
    private val hostTtfb = ConcurrentHashMap<String, Ttfb>()
    private const val HEAVY_TTFB_MS = 1_500L
    private const val HEAVY_CONNECTIONS = 3
    /** Length of one request to a heavy source, and how many 8 MB segments an extra connection takes in one request. */
    private const val HEAVY_REQUEST = 64L * 1024 * 1024
    private const val HEAVY_SEGMENTS = 4
    /** The first requests to a host include DNS, TCP and TLS set-up (eight at once on a phone): they say nothing about the host, so they are not counted. */
    private const val TTFB_WARMUP = 8
    private const val TTFB_MIN_SAMPLES = 4
    /** A host that has not been asked for this long is judged again from scratch. */
    private const val TTFB_FORGET_MS = 5 * 60_000L

    private fun noteTtfb(url: String, ms: Long) {
        val t = hostTtfb.getOrPut(hostOf(url)) { Ttfb() }
        synchronized(t) {
            val now = System.currentTimeMillis()
            if (now - t.at > TTFB_FORGET_MS) { t.avg = 0L; t.seen = 0 }
            t.at = now
            t.seen++
            if (t.seen <= TTFB_WARMUP) return
            // a single slow answer moves the average only a little (and is capped), so one hiccup cannot make a host heavy
            val m = minOf(ms, 6_000L)
            t.avg = if (t.avg == 0L) m else (t.avg * 3 + m) / 4
        }
    }

    private fun isHeavy(e: FileEntry): Boolean {
        val h = hostOf(e.url)
        if (isPrivateHost(h)) return true
        val t = hostTtfb[h] ?: return false
        return synchronized(t) {
            System.currentTimeMillis() - t.at <= TTFB_FORGET_MS && t.seen >= TTFB_WARMUP + TTFB_MIN_SAMPLES && t.avg > HEAVY_TTFB_MS
        }
    }

    /** Connections allowed for this file (main download included). */
    private fun connectionsFor(e: FileEntry): Int =
        if (isHeavy(e)) minOf(prefs.connections, HEAVY_CONNECTIONS) else prefs.connections

    private fun looksLikeHls(url: String, linkType: String?): Boolean =
        linkType.equals("M3U8", true) || url.substringBefore('?').endsWith(".m3u8", true) || url.contains(".m3u8", true)

    /** The stream pointed at this bridge's media server when it qualifies; otherwise unchanged. */
    fun rewrite(s: StremioStream, base: String, forWeb: Boolean = false): StremioStream {
        val p = prefs
        if (!p.enabled || root == null) return s
        val url = s.url ?: return s
        // "Every link": this bridge's own DASH -> HLS converter (clear-key channels) is served by the media server too: it reads the
        // playlists from this device (never through the internet tunnel) and keeps the segments it hands out
        if (p.mode == "all" && p.cacheHls && s.externalUrl == null && s.infoHash == null && s.ytId == null &&
            url.startsWith("http", true) && url.contains("/proxy/mpd/manifest.m3u8")) {
            val local = "http://127.0.0.1:${ServerState.serverPort}/proxy/mpd/manifest.m3u8" + url.substringAfter("/proxy/mpd/manifest.m3u8")
            val lid = register(local, emptyMap())
            return s.copy(url = "$base/media/$lid/pl/${token(lid, local)}.m3u8", behaviorHints = s.behaviorHints?.copy(proxyHeaders = null))
        }
        // The website's player (hls.js) reads playlists with XHR, which a browser only allows from a server that sends CORS headers:
        // every HLS link goes through this bridge for it, whatever the mode, and the upstream rule below does not apply
        val webHls = forWeb && looksLikeHls(url, s.info?.linkType)
        // An upstream addon's links are left alone - except those pointing at a private home-network address (a self-hosted addon on
        // 192.168.x.x): a phone away from home cannot open them, so this bridge (which can reach them) serves them
        val lanUpstream = s.info?.linkType == "UPSTREAM" && isPrivateHost(LinkUrl.host(s.url))
        // "Every link" mode serves the stremio add-ons' links as well; the other modes leave them alone
        if (s.info?.linkType == "UPSTREAM" && !lanUpstream && !webHls && p.mode != "all") return s
        if (s.externalUrl != null || s.infoHash != null || s.ytId != null || s.clearkey != null) return s
        if (!url.startsWith("http", true) || OWN_PATH.containsMatchIn(url)) return s
        val host = LinkUrl.host(url) ?: return s
        val loop = isLoopbackUrl(url)
        // An extension's own local server (127.0.0.1:port): only its playlists are taken over (below), and never this server itself
        if (loop && (LinkUrl.port(url) == ServerState.serverPort || !looksLikeHls(url, s.info?.linkType))) return s
        if (!loop && ServerState.isOwnUrl(url)) return s
        if (url.substringBefore('?').endsWith(".mpd", true) || url.contains(".mpd?", true)) return s // own DASH proxy
        val headers = s.behaviorHints?.proxyHeaders?.request.orEmpty()
        val hls = looksLikeHls(url, s.info?.linkType)
        // A site whose Cloudflare check was solved: only this server can send the saved cookie + browser User-Agent, so its links are taken over in every mode
        val needsCf = !loop && runCatching { com.cncverse.stremiobridge.network.CfCookieStore.get(host) }.getOrNull() != null
        if (!loop && !lanUpstream && !webHls && p.mode == "headers" && headers.isEmpty() && !needsCf) return s
        if (!loop && !lanUpstream && p.mode == "hls" && headers.isEmpty() && !hls && !needsCf) return s
        if (hls && !p.cacheHls && !lanUpstream && !webHls) return s
        if (!hls && !p.cacheFiles && !lanUpstream) return s
        if (!hls && SeekProbe.isArchiveUrl(url)) return s
        // The extension declares headers, but the host answered a plain request (see HeaderNeed): the link needs no help from this server
        if (!loop && !lanUpstream && !webHls && !needsCf && headers.isNotEmpty() && (p.mode == "headers" || (p.mode == "hls" && !hls)) &&
            HeaderNeed.verdict(url, headers) == false) return s
        val id = register(url, headers)
        // The extension's server may stop at any time: its playlists (and the first segments) are fetched and kept now
        if (loop && !warmLocal(id, url)) return s
        val newUrl = if (hls) "$base/media/$id/pl/${token(id, url)}.m3u8" else "$base/media/$id/file/${fileName(url)}"
        return s.copy(
            url = newUrl,
            behaviorHints = s.behaviorHints?.copy(proxyHeaders = null),
        )
    }

    /** True when the playlist of this extension-local link is already saved on this device (it plays without the extension). */
    fun hasLocalCopy(url: String, headers: Map<String, String>): Boolean {
        if (!prefs.enabled || !prefs.cacheHls) return false
        val sorted = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { it.key.lowercase() + ":" + it.value }
        val id = sha256(url + "\n" + sorted).take(20)
        return playlistCache.containsKey("$id|$url") || File(root ?: return false, "$id/playlist.m3u8").exists()
    }

    /** 10.x, 172.16-31.x, 192.168.x, link-local and *.local names: reachable only from the home network. */
    private fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (h.endsWith(".local")) return true
        val o = h.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
    }

    private fun isLoopbackUrl(url: String): Boolean =
        LinkUrl.host(url).let { it == "127.0.0.1" || it == "localhost" || it == "::1" }

    /**
     * An extension's local HLS link (http://127.0.0.1:port/…/master.m3u8): its playlist is downloaded right away, while the
     * extension's server is still running, saved on disk and cached in memory for hours; the rendition playlists and the first
     * segments follow. The player then gets everything from this bridge instead of through the extension's proxy.
     */
    private fun warmLocal(id: String, url: String): Boolean {
        val src = sourceFor(id) ?: return false
        val key = "$id|$url"
        if (playlistCache.containsKey(key)) return true
        return try {
            val raw = String(fetchRemote(id, src, url).first)
            if (!raw.trimStart().startsWith("#EXTM3U")) throw IOException("not a playlist")
            runCatching { File(root ?: return@runCatching, "$id/playlist.m3u8").writeText(raw) }
            val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(raw) else raw
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritePlaylist(id, url, useRaw, key))
            if (raw.contains("#EXTINF")) prefetch(id, src, key, 0)
            if (useRaw.contains("#EXT-X-STREAM-INF")) warmVariants(id, src, url, useRaw)
            true
        } catch (t: Exception) {
            // the extension's server is gone but an earlier run saved the playlist: that copy is used
            val saved = runCatching { File(root ?: return@runCatching null, "$id/playlist.m3u8").takeIf { it.exists() }?.readText() }.getOrNull()
            if (saved != null && saved.trimStart().startsWith("#EXTM3U")) {
                val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(saved) else saved
                playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritePlaylist(id, url, useRaw, key))
                return true
            }
            ServerState.warn("[MediaServer] extension link $url could not be cached (${t.message}); it goes out through the plain relay")
            false
        }
    }

    private fun fileName(url: String): String {
        val last = LinkUrl.lastSegment(url)
        val clean = last.filter { it.isLetterOrDigit() || it in "._-" }.takeLast(60)
        return if (clean.contains('.')) clean else "video.mp4"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun register(url: String, headers: Map<String, String>): String {
        val sorted = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { it.key.lowercase() + ":" + it.value }
        val id = sha256(url + "\n" + sorted).take(20)
        if (sources.putIfAbsent(id, MediaSource(url, headers)) == null) {
            val dir = File(root ?: return id, id).also { it.mkdirs() }
            runCatching { File(dir, "source.json").writeText(json.encodeToString(MediaSource(url, headers))) }
        }
        return id
    }

    private fun sourceFor(id: String): MediaSource? {
        if (!id.matches(Regex("[0-9a-f]{20}"))) return null
        sources[id]?.let { return it }
        val f = File(root ?: return null, "$id/source.json")
        val loaded = runCatching { if (f.exists()) json.decodeFromString<MediaSource>(f.readText()) else null }.getOrNull() ?: return null
        sources[id] = loaded
        return loaded
    }

    // ── Signed URL tokens ─────────────────────────────────────────────────────

    private fun b64(text: String): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun unb64(text: String): String? = runCatching { String(java.util.Base64.getUrlDecoder().decode(text)) }.getOrNull()

    private fun sign(id: String, url: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal((id + "\n" + url).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** "<base64 url>.<signature>" */
    private fun token(id: String, url: String): String = b64(url) + "." + sign(id, url)

    /** The remote URL inside [tok] ("b64.sig" with an optional extension), or null when it is forged. */
    private fun urlOfToken(id: String, tok: String): String? {
        val parts = tok.split('.')
        if (parts.size < 2) return null
        val url = unb64(parts[0]) ?: return null
        if (!java.security.MessageDigest.isEqual(sign(id, url).toByteArray(), parts[1].toByteArray())) return null
        return url
    }

    // ── HTTP clients ──────────────────────────────────────────────────────────

    private val baseClient by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            // Several connections per file / many HLS segments: keep them open for the next piece instead of
            // closing all but five and paying a new TCP + TLS handshake for every segment
            .connectionPool(okhttp3.ConnectionPool(48, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Remembers cookies a site sets while its playlist / file is fetched, and sends them back to it. */
    private class SessionCookies : okhttp3.CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            val list = store.getOrPut(url.host) { java.util.concurrent.CopyOnWriteArrayList() }
            cookies.forEach { c -> list.removeAll { it.name == c.name && it.path == c.path }; list.add(c) }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty().filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private val clients = ConcurrentHashMap<String, okhttp3.OkHttpClient>()

    private fun clientFor(id: String, src: MediaSource): okhttp3.OkHttpClient {
        val hasCookie = src.headers.keys.any { it.equals("cookie", true) }
        if (hasCookie) return baseClient // the extension's own Cookie header must not be replaced
        if (clients.size > 200) clients.clear()
        return clients.getOrPut(id) { baseClient.newBuilder().cookieJar(SessionCookies()).build() }
    }

    private fun requestFor(src: MediaSource, url: String, range: String? = null): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url)
        var hasUa = false
        src.headers.forEach { (k, v) ->
            if (k.equals("user-agent", true)) hasUa = true
            if (!k.equals("host", true) && !k.equals("range", true) && !k.startsWith("X-Cnc-", true)) runCatching { b.header(k, v) }
        }
        // A site whose Cloudflare check was solved before: its cookie + the browser User-Agent it was solved with
        val cf = if (src.headers.keys.none { it.equals("cookie", true) })
            runCatching { com.cncverse.stremiobridge.network.CfCookieStore.get(LinkUrl.host(url).orEmpty()) }.getOrNull() else null
        if (cf != null) { b.header("Cookie", cf.cookie); b.header("User-Agent", cf.userAgent); hasUa = true }
        if (!hasUa) b.header("User-Agent", UA)
        if (range != null) b.header("Range", range)
        return b.build()
    }

    // ── Direct files: download to disk, serve from disk ───────────────────────

    /** Sorted, merged byte intervals [start, end) that are on disk. */
    private class RangeSet {
        private val m = java.util.TreeMap<Long, Long>()
        @Synchronized fun add(start: Long, end: Long) {
            if (end <= start) return
            var ns = start
            var ne = end
            val lower = m.floorEntry(ns)
            if (lower != null && lower.value >= ns) { ns = lower.key; ne = maxOf(ne, lower.value) }
            var next = m.ceilingEntry(ns)
            while (next != null && next.key <= ne) {
                ne = maxOf(ne, next.value)
                m.remove(next.key)
                next = m.ceilingEntry(ns)
            }
            m[ns] = ne
        }
        @Synchronized fun contiguousEnd(pos: Long): Long {
            val f = m.floorEntry(pos) ?: return pos
            return if (f.value > pos) f.value else pos
        }
        @Synchronized fun firstGapFrom(pos: Long): Long {
            var p = pos
            while (true) {
                val f = m.floorEntry(p) ?: return p
                if (f.value > p) p = f.value else return p
            }
        }
        @Synchronized fun remove(start: Long, end: Long) {
            if (end <= start) return
            val hit = m.subMap(start - Long.MAX_VALUE / 2, true, end, false).entries.filter { it.value > start }.map { it.key to it.value }
            for ((s0, e0) in hit) {
                m.remove(s0)
                if (s0 < start) m[s0] = start
                if (e0 > end) m[end] = e0
            }
        }
        @Synchronized fun bytes(): Long = m.entries.sumOf { it.value - it.key }
        @Synchronized fun snapshot(): List<List<Long>> = m.entries.map { listOf(it.key, it.value) }
        @Synchronized fun restore(list: List<List<Long>>) { list.forEach { if (it.size == 2) add(it[0], it[1]) } }
    }

    /** Pieces kept behind the playing position before they are deleted. */
    private const val KEEP_BEHIND = 128L * 1024 * 1024

    /** A downloaded file is stored as 2 MB pieces (chunks/0, chunks/1, ...) so pieces already watched can be deleted. */
    private class ChunkStore(val dir: File) {
        companion object { const val CHUNK = 2L * 1024 * 1024 }
        fun exists(): Boolean = dir.isDirectory
        fun file(i: Long) = File(dir, i.toString())
        fun deleteBefore(chunkIndex: Long) {
            dir.listFiles()?.forEach { f -> val i = f.name.toLongOrNull(); if (i != null && i < chunkIndex) f.delete() }
        }
        fun deleteAll() { dir.deleteRecursively() }
        fun rw(write: Boolean) = Rw(write)
        inner class Rw(private val write: Boolean) : java.io.Closeable {
            private var idx = -1L
            private var raf: RandomAccessFile? = null
            private fun open(i: Long): RandomAccessFile {
                if (i != idx) {
                    raf?.close()
                    if (write) dir.mkdirs()
                    raf = RandomAccessFile(file(i), if (write) "rw" else "r")
                    idx = i
                }
                return raf!!
            }
            fun write(off: Long, buf: ByteArray, len: Int) {
                var o = off; var p = 0
                while (p < len) {
                    val inChunk = o % CHUNK
                    val n = minOf((len - p).toLong(), CHUNK - inChunk).toInt()
                    val r = open(o / CHUNK)
                    r.seek(inChunk); r.write(buf, p, n)
                    o += n; p += n
                }
            }
            fun read(off: Long, buf: ByteArray, len: Int) {
                var o = off; var p = 0
                while (p < len) {
                    val inChunk = o % CHUNK
                    val n = minOf((len - p).toLong(), CHUNK - inChunk).toInt()
                    val r = open(o / CHUNK)
                    r.seek(inChunk); r.readFully(buf, p, n)
                    o += n; p += n
                }
            }
            override fun close() { runCatching { raf?.close() }; raf = null; idx = -1L }
        }
    }

    private class FileEntry(val id: String, val src: MediaSource, val dir: File) {
        /** The address downloads use: the registered one, or an alternate address of the same file that can seek. */
        @Volatile var url: String = src.url
        val have = RangeSet()
        val store = ChunkStore(File(dir, "pieces2"))
        /** Bytes before this offset were watched and deleted (never counted as missing). */
        @Volatile var floor = 0L
        /** Parallel download threads running for this file, and the segments they are busy with. */
        val boosters = java.util.concurrent.atomic.AtomicInteger(0)
        val claimed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val metaFile = File(dir, "meta.json")
        @Volatile var total = -1L
        /** Size of a file whose server answers the open-ended "bytes=N-" with the whole file but honours closed ranges: downloads then ask "bytes=N-(size-1)". -1 = not needed. */
        @Volatile var closedTotal = -1L
        @Volatile var ranges = false
        @Volatile var mime = "application/octet-stream"
        @Volatile var ready = false
        @Volatile var passthrough = false
        @Volatile var isPlaylist = false
        @Volatile var error: String? = null
        @Volatile var focus = -1L
        @Volatile var stop = false
        /** Opened ahead of time ([prewarm]): only the first [PREWARM_BYTES] are fetched until a player asks for the file. */
        @Volatile var warmOnly = false
        @Volatile var lastAccess = System.currentTimeMillis()
        @Volatile var lastSaved = 0L
        @Volatile var lastRead = 0L
        /** Where the extra connections last waited for the player to get going, and since when (see [STARTUP_BYTES]). */
        @Volatile var gatePos = -1L
        @Volatile var gateAt = 0L
        /**
         * Players read a file over several connections at once (a TV player: the video at the playing position, another at
         * the end of the file for the index, sometimes a third). Each response being written is a reader: [id] -> [position,
         * time of its last activity]. The playing position is the lowest of the recently active ones - a single "last read"
         * value jumped between connections, so the data behind the real position was deleted and downloaded again, over and over.
         */
        val readers = ConcurrentHashMap<Long, LongArray>()
        @Volatile var writePos = 0L
        @Volatile var worker: Thread? = null
        val lock = Any()
        val complete: Boolean get() = total >= 0 && have.firstGapFrom(floor) >= total
    }

    private fun fileEntry(id: String): FileEntry? {
        files[id]?.let { return it }
        val src = sourceFor(id) ?: return null
        val dir = File(root ?: return null, id).also { it.mkdirs() }
        val e = FileEntry(id, src, dir)
        // Resume what an earlier run already downloaded
        runCatching {
            // pieces of the old single-file format are dropped
            File(dir, "data.bin").takeIf { it.exists() }?.delete()
            if (e.metaFile.exists() && e.store.exists()) {
                val m = json.decodeFromString<FileMeta>(e.metaFile.readText())
                e.total = m.total; e.ranges = m.ranges; e.mime = m.mime
                e.have.restore(m.have)
                e.ready = true
            }
        }
        return files.putIfAbsent(id, e) ?: e
    }

    private fun saveMeta(e: FileEntry) {
        if (e.total < 0) return
        runCatching { e.metaFile.writeText(json.encodeToString(FileMeta(e.total, e.ranges, e.mime, e.have.snapshot()))) }
    }

    private fun ensureWorker(e: FileEntry, warm: Boolean = false) {
        if (e.passthrough || e.error != null && e.ready && e.total < 0) return
        synchronized(e.lock) {
            if (!warm) e.warmOnly = false
            if (e.worker != null || e.complete) {
                if (!e.complete && !warm) startBoosters(e)
                return
            }
            e.stop = false
            e.error = null
            val t = Thread({ runWorker(e) }, "media-dl-" + e.id.take(6))
            t.isDaemon = true
            e.worker = t
            t.start()
            if (!warm) startBoosters(e)
        }
    }

    /** What an ahead-of-time open fetches: enough for playback to begin from disk while the real download reconnects. */
    private const val PREWARM_BYTES = 8L * 1024 * 1024
    private val PREWARM_PATH = Regex("/media/([0-9a-f]{20})/file/")

    /**
     * Opens the first [max] direct-file links of a list that was just built (best links come first), so the slow part of a start -
     * the source resolving the file (a Telegram-backed server, a cold CDN node), the redirects, the TLS handshake - is over by the
     * time one is chosen. Each one downloads its first [PREWARM_BYTES] only, then lets go of the connection. Does nothing while a
     * video is being streamed or when the file is already on disk.
     */
    fun prewarm(streams: List<StremioStream>, max: Int = 2) {
        if (!prefs.enabled || !prefs.prewarm || root == null || max <= 0 || isStreaming()) return
        var n = 0
        for (s in streams) {
            if (n >= max) break
            val id = PREWARM_PATH.find(s.url ?: continue)?.groupValues?.get(1) ?: continue
            n++
            runCatching {
                val e = fileEntry(id) ?: return@runCatching
                if (e.worker != null || e.complete || e.passthrough || e.error != null) return@runCatching
                if (e.ready && e.total > 0 && e.have.contiguousEnd(e.floor) >= minOf(e.total, PREWARM_BYTES)) return@runCatching
                e.warmOnly = true
                ensureWorker(e, warm = true)
            }
        }
    }

    /**
     * Size of one request an extra connection makes. Every request costs a round trip and the server's start-up time, so
     * 2 MB pieces left much of each connection idle (about a quarter of the time at 5 MB/s and 150 ms latency); 8 MB cuts that
     * to a few percent while eight connections still share the window evenly. A whole number of [ChunkStore.CHUNK]s, so
     * segments never share a piece file (the on-disk piece size stays 2 MB: changing it would orphan files already cached).
     */
    private const val SEG = 8L * 1024 * 1024

    /** Playback may start with this much on disk; the read-ahead window is filled while it plays. */
    private const val STARTUP_BYTES = 6L * 1024 * 1024
    /** The extra connections wait at most this long for [STARTUP_BYTES] (a slow main connection must not hold them back). */
    private const val STARTUP_WAIT_MS = 3_000L
    /** A heavy source answers slowly: the extra connections wait longer for the main one so a cold file is not asked for by several at once. */
    private const val HEAVY_STARTUP_WAIT_MS = 10_000L
    /** How long the first request of a file may wait for the source's first byte, and how long a player is kept waiting for that. */
    private const val FIRST_ANSWER_S = 100L

    private fun startBoosters(e: FileEntry) {
        val extra = (connectionsFor(e) - 1).coerceIn(0, 19)
        // also replaces single extra connections that ended after an error (not only when all of them are gone)
        while (true) {
            val cur = e.boosters.get()
            if (cur >= extra) return
            if (!e.boosters.compareAndSet(cur, cur + 1)) continue
            val t = Thread({ boost(e) }, "media-dl-b$cur-" + e.id.take(6))
            t.isDaemon = true
            t.start()
        }
    }

    /**
     * An extra download connection: takes the next missing 8 MB segment inside the read-ahead window
     * (from the playing position on) that no other connection is busy with. With the main connection that
     * makes up to [MediaServerPrefs.connections] downloads at once for a source that can seek.
     */
    private fun boost(e: FileEntry) {
        var wr: ChunkStore.Rw? = null
        var idleSince = System.currentTimeMillis()
        var failures = 0
        try {
            val client = clientFor(e.id, e.src)
            val buf = ByteArray(128 * 1024)
            while (!e.stop && !e.passthrough) {
                val now = System.currentTimeMillis()
                if (!e.ready) {
                    if (now - idleSince > 60_000) break
                    Thread.sleep(200); continue
                }
                if (!e.ranges || e.total <= 0 || e.isPlaylist) break
                // The source turned out to be a heavy one: surplus extra connections leave (a missing one is started again if needed)
                if (e.boosters.get() > (connectionsFor(e) - 1).coerceIn(0, 19)) break
                val ahead = prefs.readAheadMb * 1024L * 1024L
                val from = maxOf(playPos(e), e.floor)
                // Start-up (or after a seek): the main connection alone gets the whole bandwidth until a first piece is on disk,
                // so playback starts at once; only then do the extra connections fill the read-ahead window
                if (e.gatePos < 0 || kotlin.math.abs(from - e.gatePos) > SEG) { e.gatePos = from; e.gateAt = now }
                if (e.have.contiguousEnd(from) - from < STARTUP_BYTES && now - e.gateAt < (if (isHeavy(e)) HEAVY_STARTUP_WAIT_MS else STARTUP_WAIT_MS)) { Thread.sleep(100); continue }
                val to = if (ahead > 0) minOf(e.total, from + ahead) else e.total
                var did = false
                var i = from / SEG
                while (i * SEG < to && !e.stop) {
                    val s0 = i * SEG
                    val s1 = minOf(e.total, s0 + SEG)
                    if (e.have.contiguousEnd(s0) < s1 && e.claimed.add(i)) {
                        did = true
                        // A heavy source starts slowly: take several neighbouring segments in ONE request
                        val mine = mutableListOf(i)
                        if (isHeavy(e)) {
                            while (mine.size < HEAVY_SEGMENTS) {
                                val k = mine.last() + 1
                                if (k * SEG >= to || e.have.contiguousEnd(k * SEG) >= minOf(e.total, (k + 1) * SEG) || !e.claimed.add(k)) break
                                mine.add(k)
                            }
                        }
                        val end = minOf(e.total, (mine.last() + 1) * SEG)
                        try {
                            val w = wr ?: e.store.rw(true).also { wr = it }
                            downloadSegment(e, client, s0, end, buf, w)
                            failures = 0
                        } catch (t: IOException) {
                            // a hiccup on one segment: this connection waits a moment and goes on (gives up after ten in a row)
                            if (++failures > 10) throw t
                            Thread.sleep(500L * failures)
                        } finally { e.claimed.removeAll(mine.toSet()) }
                        idleSince = System.currentTimeMillis()
                        break
                    }
                    i++
                }
                if (!did) {
                    if (e.readers.isEmpty() && System.currentTimeMillis() - idleSince > 180_000) break
                    Thread.sleep(300)
                }
            }
        } catch (_: Throwable) {
            // an extra connection never fails the playback; the main download and the player report real errors
        } finally {
            runCatching { wr?.close() }
            e.boosters.decrementAndGet()
        }
    }

    private fun downloadSegment(e: FileEntry, client: okhttp3.OkHttpClient, s0: Long, s1: Long, buf: ByteArray, wr: ChunkStore.Rw) {
        val pos = e.have.firstGapFrom(s0)
        if (pos >= s1) return
        val t0 = System.currentTimeMillis()
        client.newCall(requestFor(e.src, e.url, "bytes=$pos-${s1 - 1}")).execute().use { r ->
            noteTtfb(e.url, System.currentTimeMillis() - t0)
            if (r.code != 206) return@use // ranges not honoured: the main connection copes
            val input = r.body?.byteStream() ?: return@use
            var off = parseStart(r.header("Content-Range")) ?: pos
            while (!e.stop && off < s1) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), s1 - off).toInt())
                if (n < 0) break
                wr.write(off, buf, n)
                e.have.add(off, off + n)
                off += n
            }
        }
        trimBehind(e)
        val nowMs = System.currentTimeMillis()
        if (nowMs - e.lastSaved > 2_000) synchronized(e.metaFile) { e.lastSaved = nowMs; saveMeta(e) }
        if (!makeRoom(0, e.id)) { e.error = "the media cache is full (limit ${prefs.maxCacheMb} MB)"; e.stop = true }
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private fun parseStart(contentRange: String?): Long? =
        contentRange?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    private val readerSeq = java.util.concurrent.atomic.AtomicLong()

    /** The position being played: the lowest position among the readers active in the last minute, else the last one read. */
    private fun playPos(e: FileEntry): Long {
        val now = System.currentTimeMillis()
        var low = Long.MAX_VALUE
        for (r in e.readers.values) if (now - r[1] < 60_000L && r[0] < low) low = r[0]
        return if (low == Long.MAX_VALUE) e.lastRead else low
    }

    /** Deletes the 2 MB pieces that lie more than KEEP_BEHIND behind the playing position (only for sources that can seek). */
    private fun trimBehind(e: FileEntry) {
        val pp = playPos(e)
        if (!e.ranges || e.total < 0 || pp <= 0) return
        val keepFrom = pp - KEEP_BEHIND
        val firstKept = keepFrom / ChunkStore.CHUNK
        if (keepFrom <= 0 || firstKept <= e.floor / ChunkStore.CHUNK) return
        e.store.deleteBefore(firstKept)
        e.have.remove(0, firstKept * ChunkStore.CHUNK)
        e.floor = firstKept * ChunkStore.CHUNK
    }

    private fun runWorker(e: FileEntry) {
        var raf: ChunkStore.Rw? = null
        try {
            val client = clientFor(e.id, e.src).let { c ->
                // The first answer of a source that resolves the file first (a Telegram-backed server) can take longer than 45 s on a cold file
                if (e.ready) c else c.newBuilder().readTimeout(FIRST_ANSWER_S, TimeUnit.SECONDS).build()
            }
            raf = e.store.rw(true)
            var warmDone = false
            var cursor = 0L
            var sinceSave = 0L
            var probed = false
            while (!e.stop) {
                val want = e.focus
                if (want >= 0) e.focus = -1
                // A seek back into a part that was already deleted: it is fetched again
                if (want >= 0 && want < e.floor) e.floor = (want / ChunkStore.CHUNK) * ChunkStore.CHUNK
                var pos = if (want >= 0) want else cursor
                if (e.total >= 0) {
                    pos = e.have.firstGapFrom(pos)
                    if (pos >= e.total) pos = e.have.firstGapFrom(e.floor)
                    if (pos >= e.total) break // everything that is still needed is on disk
                }
                val heavyEnd = if (e.ready && e.ranges && e.total > 0 && isHeavy(e)) minOf(e.total, pos + HEAVY_REQUEST) - 1 else -1L
                val range = if (!e.ready || e.ranges) (when {
                    heavyEnd >= pos -> "bytes=$pos-$heavyEnd"
                    e.closedTotal > 0 -> "bytes=$pos-${e.closedTotal - 1}"
                    else -> "bytes=$pos-"
                }) else null
                if (e.ready && !e.ranges && pos != 0L) { pos = 0L } // origin cannot seek: only a straight download works
                val t0 = System.currentTimeMillis()
                client.newCall(requestFor(e.src, e.url, range)).execute().use { r ->
                    noteTtfb(e.url, System.currentTimeMillis() - t0)
                    if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code} from the source")
                    if (!e.ready) {
                        // A 200 to a range request: the origin may still seek (it only answered "bytes=0-" with the whole
                        // file), or another address of the same file may. Only when neither does is it downloaded in order.
                        var probeTotal = -1L
                        var seeks = r.code == 206
                        if (r.code == 200 && !probed) {
                            probed = true
                            val hit = probeRanges(e, client, r.request.url.toString(), r.body?.contentLength() ?: -1L)
                            if (hit != null && hit.total > 0) e.closedTotal = hit.total
                            if (hit != null && hit.url != e.url) {
                                ServerState.info("[MediaServer] ${hostOf(e.url)} cannot seek - using ${hostOf(hit.url)} instead")
                                e.url = hit.url
                                return@use // start again on the address that can seek
                            }
                            if (hit != null) { seeks = true; probeTotal = hit.total }
                        }
                        if (seeks) {
                            e.ranges = true
                            e.total = (if (r.code == 206) parseTotal(r.header("Content-Range")) else null)
                                ?: probeTotal.takeIf { it > 0 }
                                ?: r.body?.contentLength()
                                ?: -1L
                        } else {
                            e.ranges = false
                            e.total = r.body?.contentLength() ?: -1L
                            ServerState.warn("[MediaServer] NOT SEEKABLE: ${e.url} ignores range requests (also tried its redirect target and mid-file ranges), so the file is downloaded in order and jumping ahead waits for the download")
                        }
                        r.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }?.let { e.mime = it }
                        // The link is really an HLS playlist: hand over to the playlist route
                        if (e.mime.contains("mpegurl", true)) {
                            e.isPlaylist = true
                            e.ready = true
                            e.stop = true
                            return@use
                        }
                        // Bigger than the whole cache, or no room even after deleting old entries: relay live instead
                        // A source that can seek is only kept as a moving window, so its size does not have to fit; one that
                        // cannot seek is downloaded completely and must fit
                        val tooBig = !e.ranges && (e.total > capBytes() || (e.total > 0 && !makeRoom(e.total, e.id)))
                        if (e.total <= 0 || tooBig) {
                            // Unknown length (live / chunked) or bigger than the whole cache: relay it live instead
                            e.passthrough = true
                            e.ready = true
                            e.stop = true
                            return@use
                        }
                        e.ready = true
                        ServerState.info("[MediaServer] ${hostOf(e.url)} answered HTTP ${r.code} after ${(System.currentTimeMillis() - t0) / 1000.0} s: " +
                            "${e.total / 1_048_576} MB, ${if (e.ranges) "seekable" else "not seekable"}${if (isHeavy(e)) ", heavy source" else ""}")
                    }
                    val body = r.body ?: throw IOException("empty response")
                    val startAt = if (r.code == 206) (parseStart(r.header("Content-Range")) ?: pos) else 0L
                    val input = body.byteStream()
                    val buf = ByteArray(128 * 1024)
                    var off = startAt
                    while (!e.stop) {
                        val f = e.focus
                        if (f >= 0 && e.ranges && f != off && e.have.contiguousEnd(f) == f) break // a seek: reconnect there
                        // A heavy source's request ends by itself after HEAVY_REQUEST (the first one is open-ended: it is cut here)
                        if (e.ranges && off - startAt >= HEAVY_REQUEST && isHeavy(e)) break
                        // Do not run far past what is being watched
                        val ahead = prefs.readAheadMb * 1024L * 1024L
                        // An origin that cannot seek is downloaded completely, at full speed: stopping would mean starting over
                        if (ahead > 0 && e.ranges && off - playPos(e) > ahead) {
                            // (a player whose own buffer is full stops reading for a while but still has its connection open: that is not idle)
                            if (e.readers.isEmpty() && System.currentTimeMillis() - e.lastAccess > 180_000) { e.stop = true; break }
                            Thread.sleep(400)
                            continue
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(off, buf, n)
                        e.have.add(off, off + n)
                        off += n
                        e.writePos = off
                        sinceSave += n
                        if (sinceSave > 32L * 1024 * 1024) {
                            sinceSave = 0; trimBehind(e); saveMeta(e)
                            // Hard limit: never let the folder pass the cap
                            if (!makeRoom(0, e.id)) throw IOException("the media cache is full (limit ${prefs.maxCacheMb} MB)")
                        }
                        if (e.have.contiguousEnd(off) > off) break // ran into data that is already on disk
                        // Opened ahead of time and nobody is playing it: stop after the first piece (a seekable source reconnects when asked for)
                        if (e.warmOnly && e.ranges && e.readers.isEmpty() && off - startAt >= PREWARM_BYTES) { warmDone = true; break }
                    }
                    cursor = off
                }
                if (warmDone && e.warmOnly) break
                if (e.passthrough) break
                if (!e.stop && e.total >= 0 && !e.ranges && e.have.firstGapFrom(0) < e.total) {
                    throw IOException("the source closed the connection early")
                }
            }
            saveMeta(e)
            if (e.complete) scope.launch { runCatching { trim() } }
        } catch (t: Throwable) {
            e.error = t.message ?: t.javaClass.simpleName
            e.ready = true
            ServerState.warn("[MediaServer] download failed: ${e.error}")
            saveMeta(e)
        } finally {
            runCatching { raf?.close() }
            e.worker = null
        }
    }

    private val CT_BY_EXT = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t",
    )

    private fun contentTypeOf(mime: String, name: String): ContentType {
        val m = if (mime.startsWith("video/") || mime.startsWith("audio/")) mime
        else CT_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "video/mp4"
        return runCatching { ContentType.parse(m) }.getOrDefault(ContentType.Video.MP4)
    }

    /** "bytes=a-b" / "a-" / "-n" -> inclusive (start, end); null = no/unsatisfiable range header. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        val spec = header?.trim()?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-', "").trim()
        val start: Long
        val end: Long
        if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return null
            start = (total - n).coerceAtLeast(0); end = total - 1
        } else {
            start = a.toLongOrNull() ?: return null
            end = b.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        }
        return if (start in 0 until total && end >= start) start to end else null
    }

    suspend fun serveFile(call: ApplicationCall, id: String, name: String) {
        val e = fileEntry(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        e.lastAccess = System.currentTimeMillis()
        served.incrementAndGet()
        ensureWorker(e)
        withTimeoutOrNull((FIRST_ANSWER_S + 10) * 1_000) { while (!e.ready) delay(10) }
        if (!e.ready) return call.respondText("The source did not answer in time", status = HttpStatusCode.GatewayTimeout)
        if (e.isPlaylist) return call.respondRedirect("../pl/${token(id, e.url)}.m3u8")
        if (e.passthrough) return passthrough(call, e)
        if (e.total <= 0) {
            return call.respondText("Source error: ${e.error ?: "unknown"}", status = HttpStatusCode.BadGateway)
        }
        val total = e.total
        val rangeHeader = call.request.header("Range")
        val range = parseRange(rangeHeader, total)
        if (rangeHeader != null && range == null) {
            call.response.header("Content-Range", "bytes */$total")
            return call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1)
        val len = end - start + 1
        val httpStatus = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        val ct = contentTypeOf(e.mime, name)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (range != null) call.response.header("Content-Range", "bytes $start-$end/$total")

        if (call.request.httpMethod == HttpMethod.Head) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength: Long = len
                override val contentType: ContentType = ct
                override val status: HttpStatusCode = httpStatus
            })
            return
        }

        // a second connection further on (a probe of the end of the file) must not pull the download away from the playing part
        val t0 = System.currentTimeMillis()
        val playingBefore = e.readers.values.any { t0 - it[1] < 20_000L && it[0] < start }
        if (e.ranges && !playingBefore && e.have.contiguousEnd(start) == start) e.focus = start
        call.respondBytesWriter(ct, httpStatus, len) {
            val reader = e.store.rw(false)
            val me = readerSeq.incrementAndGet()
            val slot = longArrayOf(start, System.currentTimeMillis())
            e.readers[me] = slot
            try {
                var pos = start
                val buf = ByteArray(256 * 1024)
                var lastProgress = System.currentTimeMillis()
                var lastEnsure = 0L
                while (pos <= end) {
                    e.lastAccess = System.currentTimeMillis()
                    e.lastRead = pos
                    slot[0] = pos; slot[1] = e.lastAccess
                    val avail = e.have.contiguousEnd(pos)
                    if (avail <= pos) {
                        // Not on disk yet: make sure the downloader is heading here, then wait
                        // (also when the main download is alive: the extra connections may have left while the window was full)
                        if (e.worker == null || System.currentTimeMillis() - lastEnsure > 1_000L) { lastEnsure = System.currentTimeMillis(); ensureWorker(e) }
                        val near = e.writePos in (pos - 1)..(pos + 8L * 1024 * 1024)
                        if (e.ranges && !near && e.focus < 0) e.focus = pos
                        if (e.error != null && e.worker == null) throw IOException(e.error)
                        if (System.currentTimeMillis() - lastProgress > 90_000) throw IOException("download stalled")
                        delay(15)
                        continue
                    }
                    val n = minOf(avail - pos, end - pos + 1, buf.size.toLong()).toInt()
                    reader.read(pos, buf, n)
                    writeFully(buf, 0, n)
                    pos += n
                    lastProgress = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                // Player closed the connection (seek / stop) or the source failed: end this response
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                e.readers.remove(me)
                runCatching { reader.close() }
            }
        }
    }

    /** Streams the source straight through (no cache): unknown length or bigger than the cache limit. */
    private suspend fun passthrough(call: ApplicationCall, e: FileEntry) {
        val rangeHeader = call.request.header("Range")
        val resp = try {
            withContext(Dispatchers.IO) { clientFor(e.id, e.src).newCall(requestFor(e.src, e.url, rangeHeader)).execute() }
        } catch (t: Exception) {
            return call.respondText("Upstream unavailable", status = HttpStatusCode.BadGateway)
        }
        resp.use { r ->
            r.header("Content-Range")?.let { call.response.header("Content-Range", it) }
            r.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }
            val body = r.body ?: return call.respondText("", status = HttpStatusCode.fromValue(r.code))
            val ct = runCatching { ContentType.parse(r.header("Content-Type") ?: "video/mp4") }.getOrDefault(ContentType.Video.MP4)
            call.respondOutputStream(ct, HttpStatusCode.fromValue(r.code)) { body.byteStream().use { it.copyTo(this) } }
        }
    }

    // ── HLS: playlists and segments ───────────────────────────────────────────

    private class CachedPlaylist(val at: Long, val text: String)
    private val playlistCache = ConcurrentHashMap<String, CachedPlaylist>()
    /** playlist key -> its segment urls, in order (for read-ahead) */
    private val playlistSegs = ConcurrentHashMap<String, List<String>>()
    /** "id|segment url" -> (playlist key, index) */
    private val segPos = ConcurrentHashMap<String, Pair<String, Int>>()
    private val segLocks = ConcurrentHashMap<String, Any>()
    /** source id -> (playlist key, index of the segment the player asked for last, when) */
    private val hlsPlay = ConcurrentHashMap<String, Triple<String, Int, Long>>()
    private val segWrites = AtomicLong(0)

    private fun segFile(id: String, url: String): File =
        File(File(root!!, "$id/hls").also { it.mkdirs() }, sha256(url).take(32))

    /** Downloads one playlist / segment, trying up to three times (hosts that hiccup must not stall the player). */
    private fun fetchRemote(id: String, src: MediaSource, url: String): Pair<ByteArray, String?> {
        val client = clientFor(id, src)
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                client.newCall(requestFor(src, url)).execute().use { r ->
                    if (!r.isSuccessful) {
                        if (r.code in 400..499 && r.code != 408 && r.code != 429) throw IOException("HTTP ${r.code}")
                        throw IllegalStateException("HTTP ${r.code}")
                    }
                    return (r.body?.bytes() ?: ByteArray(0)) to r.header("Content-Type")?.substringBefore(';')?.trim()
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true) throw e
                last = e
            } catch (e: IllegalStateException) {
                last = IOException(e.message)
            }
            if (attempt < 3) Thread.sleep(250L * attempt)
        }
        throw last ?: IOException("download failed")
    }

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    private fun isPlaylistUrl(u: String) = u.substringBefore('?').endsWith(".m3u8", true)

    private fun resolve(base: String, ref: String): String =
        runCatching { java.net.URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    private fun relTarget(id: String, url: String, playlist: Boolean): String =
        if (playlist) "../pl/${token(id, url)}.m3u8" else "../seg/${token(id, url)}"

    private val ATTRS = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    /** A master playlist cut down to its highest rendition (tallest, then highest bitrate); audio / subtitle groups stay. */
    private fun keepBestVariant(text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return text
        val lines = text.lines()
        class V(val infoIdx: Int, val urlIdx: Int, val height: Int, val bw: Long)
        val vs = ArrayList<V>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = ATTRS.findAll(l.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                val h = a["RESOLUTION"]?.lowercase()?.split('x')?.getOrNull(1)?.toIntOrNull() ?: 0
                val bw = a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: a["BANDWIDTH"]?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) vs += V(i, j, h, bw)
                i = j
            }
            i++
        }
        if (vs.size < 2) return text
        val top = vs.maxWithOrNull(compareBy<V>({ it.height }, { it.bw })) ?: return text
        val drop = HashSet<Int>()
        for (v in vs) if (v !== top) { drop += v.infoIdx; for (k in v.infoIdx..v.urlIdx) drop += k }
        return lines.filterIndexed { idx, _ -> idx !in drop }.joinToString("\n")
    }

    /** Rewrites every URI of a playlist to this server (relative links, so any host/port works). */
    private fun rewritePlaylist(id: String, playlistUrl: String, text: String, playlistKey: String): String {
        val out = StringBuilder()
        val segments = ArrayList<String>()
        var nextIsVariant = false
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line).append('\n')
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsVariant = true
                    val fixed = if (line.contains("URI=\"")) URI_ATTR.replace(line) { m ->
                        val abs = resolve(playlistUrl, m.groupValues[1])
                        "URI=\"" + relTarget(id, abs, isPlaylistUrl(abs)) + "\""
                    } else line
                    out.append(fixed).append('\n')
                }
                else -> {
                    val abs = resolve(playlistUrl, line)
                    val isPl = nextIsVariant || isPlaylistUrl(abs)
                    if (!isPl) segments += abs
                    nextIsVariant = false
                    out.append(relTarget(id, abs, isPl)).append('\n')
                }
            }
        }
        if (segments.isNotEmpty()) {
            if (segPos.size > 60_000) segPos.clear()
            playlistSegs[playlistKey] = segments
            segments.forEachIndexed { i, s -> segPos["$id|$s"] = playlistKey to i }
        }
        return out.toString()
    }

    suspend fun servePlaylist(call: ApplicationCall, id: String, tok: String) {
        val src = sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        served.incrementAndGet()
        touchDir(id)
        val key = "$id|$url"
        val cached = playlistCache[key]
        val local = isLoopbackUrl(url)
        val ttl = { t: String -> if (local && (t.contains("#EXT-X-ENDLIST") || t.contains("#EXT-X-STREAM-INF"))) 6 * 3_600_000L else playlistTtl(t) }
        val text = if (cached != null && System.currentTimeMillis() - cached.at < ttl(cached.text)) cached.text else {
            val raw = try {
                withContext(Dispatchers.IO) { String(fetchRemote(id, src, url).first) }
            } catch (t: Exception) {
                // the extension's server is gone: the copy saved on disk still works
                val saved = if (url == src.url) withContext(Dispatchers.IO) {
                    runCatching { File(root ?: return@runCatching null, "$id/playlist.m3u8").takeIf { it.exists() }?.readText() }.getOrNull()
                } else null
                saved ?: return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
            }
            if (!raw.trimStart().startsWith("#EXTM3U")) {
                return call.respondText("Source did not return a playlist", status = HttpStatusCode.BadGateway)
            }
            val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(raw) else raw
            val rewritten = rewritePlaylist(id, url, useRaw, key)
            if (playlistCache.size > 500) playlistCache.clear()
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            // Start fetching the first segments right away
            if (raw.contains("#EXTINF")) prefetch(id, src, key, 0)
            if (useRaw.contains("#EXT-X-STREAM-INF")) warmVariants(id, src, url, useRaw)
            rewritten
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(text, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** A master playlist was opened: load its rendition playlists now, so the player's next request is instant. */
    private fun warmVariants(id: String, src: MediaSource, masterUrl: String, raw: String) {
        val lines = raw.lines().map { it.trim() }
        val urls = ArrayList<String>()
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) urls += resolve(masterUrl, lines[j])
            }
        }
        for (u in urls.distinct().take(6)) {
            val key = "$id|$u"
            if (playlistCache.containsKey(key)) continue
            scope.launch {
                prefetchLimit.withPermit {
                    runCatching {
                        val text = String(fetchRemote(id, src, u).first)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val rewritten = rewritePlaylist(id, u, text, key)
                            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
                            if (isLoopbackUrl(u) && text.contains("#EXTINF")) prefetch(id, src, key, 0)
                        }
                    }
                }
            }
        }
    }

    /** Finished (VOD) playlists keep for ten minutes, live ones for two seconds. */
    private fun playlistTtl(text: String): Long = if (text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) 600_000L else 2_000L

    private fun touchDir(id: String) {
        runCatching { File(root ?: return, id).setLastModified(System.currentTimeMillis()) }
    }

    private fun prefetch(id: String, src: MediaSource, playlistKey: String, fromIndex: Int) {
        val n = prefs.prefetchSegments
        if (n <= 0) return
        val list = playlistSegs[playlistKey] ?: return
        for (i in fromIndex until minOf(list.size, fromIndex + n)) {
            val url = list[i]
            val f = segFile(id, url)
            if (f.exists()) continue
            scope.launch {
                prefetchLimit.withPermit { runCatching { ensureSegment(id, src, url) } }
            }
        }
    }

    /** Segment bytes on disk (downloaded once, even when several requests ask at the same time). */
    private fun ensureSegment(id: String, src: MediaSource, url: String): File {
        val f = segFile(id, url)
        if (f.exists()) return f
        val lock = segLocks.getOrPut("$id|$url") { Any() }
        try {
            synchronized(lock) {
                if (f.exists()) return f
                val (bytes, mime) = fetchRemote(id, src, url)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeBytes(bytes)
                mime?.let { runCatching { File(f.parentFile, f.name + ".ct").writeText(it) } }
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                if (segWrites.incrementAndGet() % 20L == 0L) makeRoom(0, id)
            }
        } finally {
            // also when the download failed or the file was already there: the lock map must not grow for ever
            segLocks.remove("$id|$url")
        }
        return f
    }

    suspend fun serveSegment(call: ApplicationCall, id: String, tok: String) {
        val src = sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        served.incrementAndGet()
        touchDir(id)
        val file = try {
            withContext(Dispatchers.IO) { ensureSegment(id, src, url) }
        } catch (t: Exception) {
            return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
        }
        segPos["$id|$url"]?.let { (pk, i) ->
            hlsPlay[id] = Triple(pk, i, System.currentTimeMillis())
            prefetch(id, src, pk, i + 1)
        }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        // Some hosts hand out playlists under names without ".m3u8": recognise them by content and rewrite them too
        if (bytes.size in 8..2_000_000 && bytes[0] == '#'.code.toByte() && String(bytes, 0, 7) == "#EXTM3U") {
            val key = "$id|$url"
            val rewritten = rewritePlaylist(id, url, String(bytes).let { if (StremioServer.streamPrefs.bestHls) keepBestVariant(it) else it }, key)
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            if (rewritten.contains("#EXTINF")) prefetch(id, src, key, 0)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            return call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        }
        val mime = runCatching { File(file.parentFile, file.name + ".ct").takeIf { it.exists() }?.readText() }.getOrNull()
            ?: guessSegmentType(url)
        val ct = runCatching { ContentType.parse(mime) }.getOrDefault(ContentType.Application.OctetStream)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val range = parseRange(call.request.header("Range"), bytes.size.toLong())
        if (range != null) {
            call.response.header("Content-Range", "bytes ${range.first}-${range.second}/${bytes.size}")
            call.respondBytes(bytes.copyOfRange(range.first.toInt(), range.second.toInt() + 1), ct, HttpStatusCode.PartialContent)
        } else {
            call.respondBytes(bytes, ct)
        }
    }

    private fun guessSegmentType(url: String): String =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "ts" -> "video/mp2t"
            "m4s", "mp4", "m4v" -> "video/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "vtt" -> "text/vtt"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
}
