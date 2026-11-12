package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.writeTextAtomic
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /**
     * Relay: links that need headers the player cannot send go through this bridge as a pass-through (the headers are added on the
     * way, nothing is downloaded ahead or kept on disk). HLS: only the playlists pass through, segments that play without the
     * headers go straight from the host to the player. Works with the media server on or off; it takes those links before it.
     */
    val relay: Boolean = false,
    /** Settings generation (older files get the newer defaults once). */
    val ver: Int = 5,
)

@Serializable
internal class MediaSource(val url: String, val headers: Map<String, String> = emptyMap())

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
    /**
     * HLS segment downloads in flight at once. This followed a fixed 12 whatever "Connections" said, so raising that setting to
     * 16 or 20 changed nothing for an HLS link - and HLS is what almost every source hands out. It follows the setting now.
     */
    @Volatile private var prefetchLimit = Semaphore(12)
    private fun segmentLimit(n: Int) = Semaphore(n.coerceIn(4, 20))
    /** A segment download that gets no data for this long is dropped and started again (a dead connection used to hold playback for up to 45 s). */
    private const val SEG_STALL_S = 8
    /** segments being fetched ahead right now (a second request for the same one must not take a download slot while it waits) */
    private val prefetching = ConcurrentHashMap.newKeySet<String>()

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
        prefetchLimit = segmentLimit(prefs.connections)
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
    /** How often the read-ahead is checked and topped up. It was 5 s, which is a long time to notice that a downloader has died. */
    private const val MONITOR_MS = 2_000L
    /**
     * How long a file is kept warm after the last read from a player. The read-ahead is topped up for this long, so a pause, a
     * seek that takes a while, or a player that went quiet for a moment finds the window already filled when it comes back. After
     * it, the file is left alone (its downloader stops, and the cache is trimmed as usual).
     */
    private const val IDLE_STOP_MS = 30 * 60_000L

    /**
     * Every 2 seconds: where is each player now? Files - the lowest position of the connections reading it; HLS - the last
     * segment asked for. The read-ahead window after that position is topped up (a stopped downloader is started again, the
     * download is pointed at the first missing piece after the position, the next segments are queued). This runs for
     * [IDLE_STOP_MS] after the last activity on a file.
     */
    private fun checkPlayback() {
        if (!prefs.enabled) return
        val now = System.currentTimeMillis()
        for (e in files.values) {
            if (e.warmOnly || now - e.lastAccess > IDLE_STOP_MS || e.passthrough || !e.ready || e.complete || e.total <= 0 || !e.ranges || e.isPlaylist) continue
            val pos = playPos(e)
            // speeds over the last check (2 s), smoothed (the player reads in bursts: its average is the video's bitrate)
            val dlNow = e.dlBytes.get(); val outNow = e.outBytes.get()
            val sec = MONITOR_MS / 1000.0
            e.dlRate = e.dlRate * 0.6 + ((dlNow - e.lastDl) / sec) * 0.4
            e.outRate = e.outRate * 0.85 + ((outNow - e.lastOut) / sec) * 0.15
            e.lastDl = dlNow; e.lastOut = outNow
            // The part kept behind the player is cleaned up here too (it used to happen only when the main download wrote, which
            // a window filled by the extra connections may not do for a long time - and the window can now be bigger)
            runCatching { trimBehind(e) }
            val ahead = aheadFor(e).takeIf { it > 0 } ?: e.total
            val want = minOf(e.total, pos + ahead)
            val contiguous = e.have.contiguousEnd(pos) - pos
            logHealth(e, contiguous, now)
            if (e.have.firstGapFrom(maxOf(pos, e.floor)) >= want) continue // the window is full
            if (e.error != null && e.worker == null) e.error = null
            if (e.worker == null) { e.stop = false; ensureWorker(e); continue }
            startBoosters(e)
            // Running low (under 24 MB or ~10 s of the video ready): the first missing part goes to the main download - but only
            // when no connection is already on it, and not more often than every 15 s (each move is a new request to the host;
            // moving it every 5 s while the window refilled made it fetch the same parts as the extra connections, over and over)
            val low = maxOf(24L * 1_048_576L, (e.outRate * 10).toLong())
            val gap = e.have.firstGapFrom(maxOf(pos, e.floor))
            if (contiguous < low && gap < want && !isClaimed(e, gap) && e.writePos !in (gap - 1)..(gap + NEAR_SEG) &&
                (now - e.lastFocusAt > 15_000 || contiguous < 2L * ChunkStore.CHUNK) && (!isHeavy(e) || contiguous < 16L * 1_048_576L)) {
                e.lastFocusAt = now
                e.focus = gap
            }
        }
        for ((pk, v) in hlsPlay) {
            val quiet = now - v.third
            if (quiet > PAUSE_DEEP_MS) { hlsPlay.remove(pk); continue }
            val src = sourceFor(v.first) ?: continue
            // paused (no segment asked for lately): fetch more segments ahead than while playing
            prefetch(v.first, src, pk, v.second + 1, deep = quiet >= PAUSE_QUIET_MS)
        }
    }
    @Volatile private var cleaner: kotlinx.coroutines.Job? = null

    /**
     * Every 15 s while a file plays: what is ready after the reading position, how fast the host sends and how fast the player
     * reads (its average = the video's bitrate). A warning when the player is about to run dry and the host is the reason.
     */
    private fun logHealth(e: FileEntry, contiguous: Long, now: Long) {
        if (e.readers.isEmpty() || now - e.lastHealthLog < 15_000) return
        e.lastHealthLog = now
        val mb = 1_048_576.0
        val secs = if (e.outRate > 64 * 1024) contiguous / e.outRate else Double.NaN
        val conns = 1 + e.boosters.get()
        val line = "[MediaServer] ${hostOf(e.url)}: ${"%.0f".format(contiguous / mb)} MB ready ahead" +
            (if (!secs.isNaN()) " (~${"%.0f".format(secs)} s)" else "") +
            ", host ${"%.1f".format(e.dlRate / mb)} MB/s on $conns connection(s), player reads ${"%.1f".format(e.outRate / mb)} MB/s"+
            (aheadFor(e).takeIf { it > 0 }?.let { ", window ${"%.0f".format(it / mb)} MB" } ?: "")
        if (!secs.isNaN() && secs < 10 && e.dlRate < e.outRate * 1.1 && !e.complete)
            ServerState.warn("$line - the host is slower than the video: it will buffer (try fewer apps on the network, more connections, or another link)")
        else ServerState.info(line)
    }

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
        prefetchLimit = segmentLimit(clean.connections)
        runCatching { prefsFile?.writeTextAtomic(json.encodeToString(clean)) }
            .onFailure { ServerState.warn("Failed to save media_server.json: ${it.message}") }
    }

    fun shutdown() {
        files.values.forEach { it.stop = true }
    }

    fun stats(): MediaServerStats {
        val dir = root
        val size = dir?.let { dirSize(it) } ?: 0L
        val entries = dir?.listFiles()?.count { it.isDirectory && it.name != SOURCES_DIR } ?: 0
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
        // (walking the whole folder is not free: the answer is reused for a few seconds, not one)
        var total = if (extra <= 0 && nowMs - sizeAt < 4_000L) sizeCache else dirSize(dir).also { sizeCache = it; sizeAt = nowMs }
        if (extra > 0 && keepId != null) total -= dirSize(File(dir, keepId))
        if (total + extra <= cap) return true
        val victims = dir.listFiles()?.filter { it.isDirectory && it.name != keepId && it.name != SOURCES_DIR }?.sortedBy { lastUsed(it) } ?: emptyList()
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
        val entries = dir.listFiles()?.filter { it.isDirectory && it.name != SOURCES_DIR } ?: return
        // link records nobody asked for in two days (or the keep time, when that is longer)
        runCatching {
            val old = now - maxOf(ttl, 48 * 3_600_000L)
            File(dir, SOURCES_DIR).listFiles()?.forEach { if (it.isFile && it.lastModified() < old) it.delete() }
        }
        entries.filter { now - lastUsed(it) > busyMs && now - lastUsed(it) > ttl }
            .forEach { files.remove(it.name)?.stop = true; it.deleteRecursively() }
        makeRoom(0, null)
    }

    // ── Rewriting streams ─────────────────────────────────────────────────────

    internal val OWN_PATH = Regex("^https?://[^/]+/(proxy/|decrypt|init_decrypt|media/|relay/)", RegexOption.IGNORE_CASE)

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
        if (hostTtfb.size > 2_000) hostTtfb.clear()
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

    /**
     * host -> [when it last refused, connections allowed now, when that was last raised, when it was last logged].
     * A host that refuses (429 / 503 / a plain 200 to a range request) gets one connection fewer than were open, never fewer than
     * [REFUSED_MIN]; while it stays quiet the allowance grows by one every [REFUSED_PROBE_MS] until the setting is reached again.
     * (It used to be a fixed 3 connections for two minutes after a single refusal, whatever the setting.)
     */
    private val hostRefused = ConcurrentHashMap<String, LongArray>()
    private const val REFUSED_MIN = 2
    private const val REFUSED_PROBE_MS = 15_000L

    /** A host answered a download with something other than 206 (rate limit, too many connections, blocked, ranges ignored). */
    private fun noteRefused(url: String, code: Int, open: Int) {
        if (hostRefused.size > 500) hostRefused.clear()
        val h = hostOf(url)
        val t = hostRefused.getOrPut(h) { longArrayOf(0L, Long.MAX_VALUE, 0L, 0L) }
        val now = System.currentTimeMillis()
        synchronized(t) {
            // a burst of refusals from connections opened before the first one counts once
            if (now - t[0] > 3_000L) {
                val base = minOf(t[1], open.toLong().coerceAtLeast(1L))
                t[1] = maxOf(REFUSED_MIN.toLong(), base - 1)
                t[2] = now
            }
            t[0] = now
            if (now - t[3] > 30_000L) {
                t[3] = now
                ServerState.warn("[MediaServer] $h answered HTTP $code to a download request - using ${t[1]} connections to it for now, trying more later")
            }
        }
    }

    /** Connections this host may have right now, or [limit] when it never refused (or has been quiet long enough to be trusted again). */
    private fun refusedCap(url: String, limit: Int): Int {
        val h = hostOf(url)
        val t = hostRefused[h] ?: return limit
        return synchronized(t) {
            val now = System.currentTimeMillis()
            while (t[1] < limit && now - maxOf(t[0], t[2]) >= REFUSED_PROBE_MS) { t[1]++; t[2] = now }
            if (t[1] >= limit) { hostRefused.remove(h); limit } else t[1].toInt()
        }
    }

    /** Connections allowed for this file (main download included). */
    private fun connectionsFor(e: FileEntry): Int {
        // A heavy source gets fewer connections, but never fewer than half of what was asked for (3 at the floor): each connection
        // now streams for a long stretch instead of making a request per piece, so the load a heavy origin sees is a fraction of
        // what it was, and pinning the whole file to 3 connections made a 16-connection setting worth nothing on a host that was
        // merely slow to answer its first byte.
        val n = if (isHeavy(e)) minOf(prefs.connections, maxOf(HEAVY_CONNECTIONS, prefs.connections / 2)) else prefs.connections
        return refusedCap(e.url, stalledCap(e.url, n))
    }

    /**
     * host -> [connections allowed, until when]. Extra connections that kept missing [SLOW_PIECE_MS] on their piece say the host
     * cannot carry them: the allowance drops (never below [REFUSED_MIN]) for a couple of minutes instead of every connection being
     * thrown at a host that cannot use them and the player waiting for a piece none of them finishes.
     */
    private val hostStalled = ConcurrentHashMap<String, LongArray>()
    private const val STALL_CAP_MS = 120_000L

    private fun noteStall(url: String) {
        if (hostStalled.size > 500) hostStalled.clear()
        val t = hostStalled.getOrPut(hostOf(url)) { longArrayOf(Long.MAX_VALUE, 0L) }
        val now = System.currentTimeMillis()
        var was = Long.MAX_VALUE
        var nowCap = 0L
        synchronized(t) {
            if (now > t[1]) t[0] = Long.MAX_VALUE
            was = t[0]
            t[0] = maxOf(REFUSED_MIN.toLong(), minOf(t[0], prefs.connections.toLong()) - 1)
            t[1] = now + STALL_CAP_MS
            nowCap = t[0]
        }
        // once per change, not once per missed piece
        if (was != nowCap) {
            ServerState.warn("[MediaServer] ${hostOf(url)} is not delivering 512 KB in ${SLOW_PIECE_MS / 1000} s - using $nowCap connection(s) to it for a while")
        }
    }

    private fun stalledCap(url: String, limit: Int): Int {
        val t = hostStalled[hostOf(url)] ?: return limit
        return synchronized(t) {
            val now = System.currentTimeMillis()
            if (now > t[1]) { hostStalled.remove(hostOf(url)); limit } else minOf(limit, t[0].toInt())
        }
    }

    /**
     * A playlist, also when it starts with a byte-order mark: some servers write one before "#EXTM3U", and such a playlist was
     * refused as "not a playlist" (players skip the mark).
     */
    internal fun isM3u(text: String?): Boolean = text != null && text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    // ── Playlists read while the list was prepared (the quality check), handed to the player's first request ──
    // A signed playlist link can be good for a single read, or for a very short time: read by the quality check, the same link
    // was refused when the player then asked for it, and the bridge answered "502". The first request of the player now gets the
    // copy that was just read (once, within 3 minutes); every later request reads the link again as before.
    private class ReadPlaylist(val at: Long, val finalUrl: String, val text: String)
    private val readPlaylists = ConcurrentHashMap<String, ReadPlaylist>()

    internal fun rememberPlaylist(url: String, finalUrl: String, text: String) {
        if (text.length > 2_000_000) return
        // a live playlist moves on every few seconds: only lists of renditions and finished videos are kept
        if (!text.contains("#EXT-X-STREAM-INF") && !text.contains("#EXT-X-ENDLIST")) return
        if (readPlaylists.size > 200) readPlaylists.clear()
        readPlaylists[url] = ReadPlaylist(System.currentTimeMillis(), finalUrl, noBom(text))
    }

    /** The copy read a moment ago (final address, text), taken once. */
    internal fun takeReadPlaylist(url: String): Pair<String, String>? =
        readPlaylists.remove(url)?.takeIf { System.currentTimeMillis() - it.at < 180_000L }?.let { it.finalUrl to it.text }

    /** The playlist text without a leading byte-order mark. */
    internal fun noBom(text: String): String = text.removePrefix("\uFEFF")

    /** A short, single-line sample of an answer for the log. */
    internal fun sample(text: String): String = text.take(120).replace(Regex("\\s+"), " ").trim()

    internal fun looksLikeHls(url: String, linkType: String?): Boolean =
        linkType.equals("M3U8", true) || url.substringBefore('?').endsWith(".m3u8", true) || url.contains(".m3u8", true)

    /**
     * An HLS link whose address does not say so: the extension marks it as a playlist (CloudStream's link type M3U8), but there
     * is no ".m3u8" anywhere in it - AniPM's signed https://media.settlar.io/v1/object/… links, for one. CloudStream's player is
     * told the type; a Stremio player is not (an answer has no field for it) and goes by the address, takes it for a video file
     * and fails. Such a link is never handed out as it is.
     */
    internal fun isHiddenHls(s: StremioStream): Boolean {
        val url = s.url ?: return false
        return s.info?.linkType.equals("M3U8", true) && url.startsWith("http", true) && !url.contains(".m3u8", true) &&
            s.externalUrl == null && s.infoHash == null && s.ytId == null && s.clearkey == null
    }

    /** The stream pointed at this bridge's media server when it qualifies; otherwise unchanged. */
    fun rewrite(s: StremioStream, base: String, forWeb: Boolean = false, runtimeMin: Int = 0): StremioStream {
        val out = rewriteFor(s, base, forWeb, runtimeMin)
        // left as it was, though the player could not tell it is a playlist: it goes out through the relay, whose address
        // ends in ".m3u8" (whatever the relay / media server settings say; the relay passes it through, nothing is stored)
        if (out.url == s.url && isHiddenHls(s)) Relay.rewrite(s, base, forWeb, runtimeMin, force = true)?.let { return it }
        return out
    }

    private fun rewriteFor(s: StremioStream, base: String, forWeb: Boolean, runtimeMin: Int): StremioStream {
        val p = prefs
        // Relay on: links that need headers go to it (with the media server on too, it picks one of the two when playback starts)
        if (p.relay) Relay.rewrite(s, base, forWeb, runtimeMin)?.let { return it }
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
        // an extension's local link is always kept here (its server forgets links quickly), whatever the HLS-cache switch says
        if (hls && !p.cacheHls && !lanUpstream && !webHls && !loop) return s
        if (!hls && !p.cacheFiles && !lanUpstream) return s
        if (!hls && SeekProbe.isArchiveUrl(url)) return s
        // The extension declares headers, but the host answered a plain request (see HeaderNeed): the link needs no help from this server.
        //
        // Not for a playlist. A playlist is a tree: the master is on one host and its variants and segments can be on others (AniPM:
        // the master on media.settlar.io, the variants on fetch.nexabloom.top with their own token), and the extension's headers
        // have to reach every request in it. The verdict comes from one probe of the first branch, made without those headers - so
        // for a playlist it proves nothing about the rest, and acting on it handed the player a bare URL whose children then
        // refused it. A playlist with declared headers is always served by this bridge now.
        if (!hls && !loop && !lanUpstream && !webHls && !needsCf && headers.isNotEmpty() && (p.mode == "headers" || p.mode == "hls") &&
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
        if (!prefs.enabled) return false
        val sorted = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { it.key.lowercase() + ":" + it.value }
        val id = sha256(url + "\n" + sorted).take(20)
        return playlistCache.containsKey("$id|$url") || File(root ?: return false, "$id/playlist.m3u8").exists()
    }

    /**
     * Saves an extension-local HLS link the moment a search finds it (not when a player first asks): the extension's server keeps
     * only its last few streams and its token lives minutes, so the copy has to be made while the link is fresh. Safe to call
     * again for the same link; runs on the caller's thread, so call it from a background one.
     */
    fun warmLocalNow(url: String, headers: Map<String, String>): Boolean {
        if (!prefs.enabled || root == null) return false
        if (!isLoopbackUrl(url) || LinkUrl.port(url) == ServerState.serverPort || !looksLikeHls(url, null)) return false
        return warmLocal(register(url, headers), url)
    }

    internal fun isPrivateHostOf(url: String): Boolean = isPrivateHost(LinkUrl.host(url))

    /** 10.x, 172.16-31.x, 192.168.x, link-local and *.local names: reachable only from the home network. */
    private fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (h.endsWith(".local")) return true
        val o = h.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
    }

    internal fun isLoopbackUrl(url: String): Boolean =
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
            val fin = arrayOfNulls<String>(1)
            val raw = String(fetchRemote(id, src, url, finalUrl = fin, maxBytes = MAX_PLAYLIST_BYTES).first)
            if (!isM3u(raw)) throw IOException("not a playlist")
            runCatching { File(File(root ?: return@runCatching, id).also { it.mkdirs() }, "playlist.m3u8").writeText(raw) }
            val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(raw) else raw
            val base = fin[0] ?: url
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritePlaylist(id, base, useRaw, key))
            if (raw.contains("#EXTINF")) prefetch(id, src, key, firstWanted(key, raw))
            if (useRaw.contains("#EXT-X-STREAM-INF")) warmVariants(id, src, base, useRaw)
            true
        } catch (t: Exception) {
            // the extension's server is gone but an earlier run saved the playlist: that copy is used
            val saved = runCatching { File(root ?: return@runCatching null, "$id/playlist.m3u8").takeIf { it.exists() }?.readText() }.getOrNull()
            if (saved != null && isM3u(saved)) {
                val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(saved) else saved
                playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritePlaylist(id, url, useRaw, key))
                return true
            }
            ServerState.warn("[MediaServer] extension link $url could not be cached (${t.message}); it goes out through the plain relay")
            false
        }
    }

    internal fun fileName(url: String): String {
        val last = LinkUrl.lastSegment(url)
        val clean = last.filter { it.isLetterOrDigit() || it in "._-" }.takeLast(60)
        return if (clean.contains('.')) clean else "video.mp4"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun register(url: String, headers: Map<String, String>): String {
        val sorted = headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { it.key.lowercase() + ":" + it.value }
        val id = sha256(url + "\n" + sorted).take(20)
        // Every link of every result list is registered here (hundreds per search) and nothing ever left the map: it grew for as
        // long as the app ran. Each source is also saved as source.json and read back by sourceFor, so the map can be emptied.
        // (not while a record is still waiting to be written: it could not be read back yet)
        if (sources.size > (if (root != null) 3_000 else 30_000) && pendingSourceWrites.get() == 0) sources.clear()
        pendingSourceWrites.incrementAndGet()
        if (sources.putIfAbsent(id, MediaSource(url, headers)) != null) { pendingSourceWrites.decrementAndGet(); return id }
        val dir = root ?: run { pendingSourceWrites.decrementAndGet(); return id }
        // One small file per link in one folder, written in the background. It used to be a folder per link, made and written
        // while the list was being answered: a 500-link list meant 500 folders and 500 file writes before the player got its
        // answer, and every one of those folders was then walked again and again by the cache-size check during playback.
        val src = MediaSource(url, headers)
        runCatching {
            sourceWriter.execute {
                try {
                    val f = File(File(dir, SOURCES_DIR).also { it.mkdirs() }, "$id.json")
                    if (!f.exists()) f.writeText(json.encodeToString(src))
                } catch (_: Exception) {
                } finally { pendingSourceWrites.decrementAndGet() }
            }
        }.onFailure { pendingSourceWrites.decrementAndGet() }
        return id
    }

    private const val SOURCES_DIR = "sources"
    private val pendingSourceWrites = java.util.concurrent.atomic.AtomicInteger(0)
    private val sourceWriter by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "media-sources").apply { isDaemon = true } }
    }

    internal fun sourceFor(id: String): MediaSource? {
        if (!id.matches(Regex("[0-9a-f]{20}"))) return null
        sources[id]?.let { return it }
        val dir = root ?: return null
        // the shared folder first, then the place older versions used (a folder per link)
        val f = File(dir, "$SOURCES_DIR/$id.json").takeIf { it.exists() } ?: File(dir, "$id/source.json")
        val loaded = runCatching { if (f.exists()) json.decodeFromString<MediaSource>(f.readText()) else null }.getOrNull() ?: return null
        // in use: it stays (records nobody asked for in two days are removed)
        runCatching { f.setLastModified(System.currentTimeMillis()) }
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
    internal fun token(id: String, url: String): String = b64(url) + "." + sign(id, url)

    /** The remote URL inside [tok] ("b64.sig" with an optional extension), or null when it is forged. */
    internal fun urlOfToken(id: String, tok: String): String? {
        val parts = tok.split('.')
        if (parts.size < 2) return null
        val url = unb64(parts[0]) ?: return null
        if (!java.security.MessageDigest.isEqual(sign(id, url).toByteArray(), parts[1].toByteArray())) return null
        return url
    }

    // ── HTTP clients ──────────────────────────────────────────────────────────

    private val baseClient by lazy {
        rawClientBuilder()
            // (a signed link refused here is asked for again over the other kind of address, see FamilyRetry)
            .addInterceptor(FamilyRetry)
            .build()
    }

    private fun rawClientBuilder() = okhttp3.OkHttpClient.Builder().dns(FamilyDns(null))
        // Several connections per file / many HLS segments: keep them open for the next piece instead of
        // closing all but five and paying a new TCP + TLS handshake for every segment
        .connectionPool(okhttp3.ConnectionPool(48, 5, TimeUnit.MINUTES))
        // HTTP/1.1 only: over HTTP/2 OkHttp puts all the "parallel" connections of a file on ONE TCP connection
        // (multiplexed), so 8 connections downloaded no faster than one and a slow piece held up the others
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        // The extensions' own client connects the same way (IPv6 first where the network has it). This one used to go out
        // over IPv4: on a network where the two have different public addresses (mobile networks such as Jio) a link signed
        // for the address the extension asked from was refused here ("HTTP 403") - AniPM's media.settlar.io links, for one.
        .fastFallback(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)

    /** Hosts that answered only over one kind of address: true = IPv6, false = IPv4. */
    private val hostFamily = ConcurrentHashMap<String, Boolean>()

    /** The app's DNS, limited to one kind of address when [only] is set, or when [hostFamily] knows the host. */
    private class FamilyDns(private val only: Boolean?) : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = com.cncverse.stremiobridge.network.AppDns.lookup(hostname)
            val want = only ?: hostFamily[hostname] ?: return all
            return all.filter { (it is java.net.Inet6Address) == want }.ifEmpty {
                if (only != null) throw java.net.UnknownHostException("$hostname has no ${if (want) "IPv6" else "IPv4"} address") else all
            }
        }
    }

    private val v6Client by lazy { rawClientBuilder().dns(FamilyDns(true)).build() }
    private val v4Client by lazy { rawClientBuilder().dns(FamilyDns(false)).build() }

    /**
     * A refused request (403) is asked again over IPv6 only, then IPv4 only. A link signed for the public address of whoever
     * asked for it (the extension) works only from that same address, and on many mobile networks IPv6 and IPv4 leave from
     * different ones. The kind that worked is kept for that host, so the segments that follow go the same way at once.
     */
    private object FamilyRetry : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            val first = chain.proceed(req)
            if (first.code != 403) return first
            val host = req.url.host
            if (hostFamily.containsKey(host)) return first
            for (v6 in listOf(true, false)) {
                val alt = runCatching { (if (v6) v6Client else v4Client).newCall(req).execute() }.getOrNull() ?: continue
                if (alt.code != 403) {
                    first.close()
                    if (hostFamily.size > 500) hostFamily.clear()
                    hostFamily[host] = v6
                    ServerState.info("[Relay] $host refused the request over one kind of address and answered over ${if (v6) "IPv6" else "IPv4"} - using that for it from now on")
                    return alt
                }
                alt.close()
            }
            return first
        }
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
    private val clientUsed = ConcurrentHashMap<String, Long>()

    internal fun clientFor(id: String, src: MediaSource): okhttp3.OkHttpClient {
        val hasCookie = src.headers.keys.any { it.equals("cookie", true) }
        if (hasCookie) return baseClient // the extension's own Cookie header must not be replaced
        // Each client carries the cookies its host set for this link. Emptying the whole map when it filled up also threw away
        // the cookies of the video that was playing (its next request then went out without them: refused by hosts that
        // hand out a session cookie). Only the ones unused for longest go.
        val now = System.currentTimeMillis()
        if (clients.size > 200) {
            clientUsed.entries.sortedBy { it.value }.take(100).forEach { clients.remove(it.key); clientUsed.remove(it.key) }
            clients.keys.filter { !clientUsed.containsKey(it) }.forEach { clients.remove(it) }
        }
        clientUsed[id] = now
        return clients.getOrPut(id) { baseClient.newBuilder().cookieJar(SessionCookies()).build() }
    }

    internal fun requestFor(src: MediaSource, url: String, range: String? = null): okhttp3.Request {
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

    /**
     * Pieces kept behind the reading position before they are deleted. TV players (Nuvio TV: up to 45 s / 200 MB) read far ahead
     * of what is on screen, so a short seek back lands up to ~200 MB behind the reading position: that part must still be here.
     */
    private const val KEEP_BEHIND = 320L * 1024 * 1024
    /** Read-ahead never below this (a player that buffers 200 MB itself needs the bridge to be further ahead than that). */
    private const val MIN_READ_AHEAD = 256L * 1024 * 1024
    /** Within this much of the reading position a piece is fetched the moment it is claimed (see [NEAR_SEG]). */
    private const val NEAR_BYTES = 48L * 1024 * 1024
    /** One extra connection's request covers this much of the file; it keeps extending its claim inside it instead of asking again. */
    private const val REQUEST_SPAN = 64L * 1024 * 1024
    /**
     * One piece of the file claimed by one connection: 512 KB. Small pieces are what keeps a slow connection from standing between
     * the player and its next bytes - a stalled 512 KB costs the player half a second of video, where an 8 MB piece cost it sixteen.
     * They add no requests at all: a connection keeps streaming across pieces inside one [REQUEST_SPAN] request.
     */
    private const val NEAR_SEG = 512L * 1024

    /** The read-ahead window in bytes (0 in the settings = no limit). */
    private fun aheadBytes(): Long = if (prefs.readAheadMb > 0) maxOf(MIN_READ_AHEAD, prefs.readAheadMb * 1024L * 1024L) else 0L

    /** A player that has not read for this long is paused (or has its own buffer full): the window grows, see [aheadFor]. */
    private const val PAUSE_QUIET_MS = 15_000L
    /** The window stays grown until this long after the last read (a pause); after that the file is back to the normal window. */
    private const val PAUSE_DEEP_MS = 10 * 60_000L
    /** While paused the window is this many times the normal one... */
    private const val DEEP_FACTOR = 3
    /** ...but always leaves room in the cache for the part kept behind the player plus this margin. */
    private const val CACHE_MARGIN = 256L * 1024 * 1024

    /**
     * The read-ahead window of one file. Normally [aheadBytes]. While the player is paused - or has filled its own buffer and
     * stopped reading - for [PAUSE_QUIET_MS] the window is [DEEP_FACTOR] times as big (kept inside the cache limit), so the
     * time of a pause is used to bank much more on disk and the player does not run dry soon after it carries on. A player
     * that is waiting for data is not quiet (it is hungry, not paused): its window stays the normal one.
     */
    private fun aheadFor(e: FileEntry): Long {
        val base = aheadBytes()
        if (base <= 0) return 0L
        val quiet = System.currentTimeMillis() - e.lastAccess
        if (e.outBytes.get() == 0L || quiet < PAUSE_QUIET_MS || quiet > PAUSE_DEEP_MS) return base
        val room = capBytes() - KEEP_BEHIND - CACHE_MARGIN
        return maxOf(base, minOf(base * DEEP_FACTOR, room))
    }

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
        /** Parts the extra connections are downloading right now: start -> end (exclusive). */
        val claims = java.util.TreeMap<Long, Long>()
        /** Bytes downloaded / sent to players (for the speeds in the log) and the last values the monitor saw. */
        val dlBytes = AtomicLong()
        val outBytes = AtomicLong()
        @Volatile var lastDl = 0L
        @Volatile var lastOut = 0L
        @Volatile var dlRate = 0.0
        @Volatile var outRate = 0.0
        @Volatile var lastHealthLog = 0L
        @Volatile var lastFocusAt = 0L
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
        /** Last time the read-ahead was paused because the cache folder is full (so the warning is not repeated every piece). */
        @Volatile var lastFullLog = 0L
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
     * Piece size further from the reading position. It used to be 8 MB because every piece was its own request, and a request
     * costs a round trip plus the server's start-up time; the request is now one long stream ([REQUEST_SPAN]) and a piece only
     * says how much one connection holds at a time, so the whole file is claimed in the same small steps.
     */
    private const val SEG = 512L * 1024
    /**
     * A connection that has not finished its 512 KB piece within this long is standing between the player and its next bytes: the
     * piece is given up (the connection stays open and takes the next free one, so its socket and its already-sent bytes are not
     * wasted) and the region ahead is left to the other connections.
     */
    private const val SLOW_PIECE_MS = 4_000L
    /** No byte at all for this long on a download connection: it is dropped and asked again (dead socket, host gone). */
    private const val STALL_READ_S = 4L

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
            // No byte for [STALL_READ_S] and the read throws: a dead socket is noticed in seconds instead of the 45 s the shared
            // client waits, which used to hold a piece the player needed for most of a minute.
            val client = clientFor(e.id, e.src).newBuilder().readTimeout(STALL_READ_S, TimeUnit.SECONDS).build()
            val buf = ByteArray(128 * 1024)
            var slowStreak = 0
            while (!e.stop && !e.passthrough) {
                val now = System.currentTimeMillis()
                if (!e.ready) {
                    if (now - idleSince > 60_000) break
                    Thread.sleep(200); continue
                }
                if (!e.ranges || e.total <= 0 || e.isPlaylist) break
                // The source turned out to be a heavy one: surplus extra connections leave (a missing one is started again if needed)
                if (e.boosters.get() > (connectionsFor(e) - 1).coerceIn(0, 19)) break
                val ahead = aheadFor(e)
                val from = maxOf(playPos(e), e.floor)
                // Start-up ONLY: until the player has been sent its first byte, the main connection gets the bandwidth on its own
                // so playback can begin; from then on every connection works, however thin the buffer is.
                //
                // It used to re-arm whenever the playing position had moved 8 MB - which is ordinary playback - and then park all
                // the extra connections for 3 s (10 s on a heavy source) exactly while the buffer was running dry. A file that fell
                // behind then starved itself: the connections that would have refilled it were asleep, so 16 connections behaved
                // like one and the player buffered. Now the gate cannot come back after playback has started.
                if (e.gatePos < 0 || kotlin.math.abs(from - e.gatePos) > SEG) { e.gatePos = from; e.gateAt = now }
                if (e.outBytes.get() == 0L && e.have.contiguousEnd(from) - from < STARTUP_BYTES &&
                    now - e.gateAt < (if (isHeavy(e)) HEAVY_STARTUP_WAIT_MS else STARTUP_WAIT_MS)) { Thread.sleep(100); continue }
                val to = if (ahead > 0) minOf(e.total, from + ahead) else e.total
                val piece = claimNext(e, from, to)
                val did = piece != null
                if (piece != null) {
                    try {
                        val w = wr ?: e.store.rw(true).also { wr = it }
                        val slow = downloadSegment(e, client, piece.first, piece.second, buf, w) == 1
                        failures = 0
                        if (slow) {
                            // This host cannot carry this many connections: after three missed pieces in a row this one leaves and
                            // the host is asked for fewer for a while, instead of every connection being thrown at it again.
                            slowStreak++
                            noteStall(e.url)
                            if (slowStreak >= 3) break
                        } else slowStreak = 0
                    } catch (t: IOException) {
                        // a hiccup on one piece: this connection waits a moment and goes on (gives up after ten in a row)
                        if (++failures > 10) throw t
                        Thread.sleep(500L * failures)
                    } finally { /* the piece claim is released by downloadSegment itself */ }
                    idleSince = System.currentTimeMillis()
                }
                if (!did) {
                    // nothing to fetch right now (the window is full): keep the connection alive for [IDLE_STOP_MS] after the last
                    // read, so it is still here when the player asks for more. checkPlayback (every 2 s) restarts one that left.
                    if (e.readers.isEmpty() && System.currentTimeMillis() - idleSince > IDLE_STOP_MS) break
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

    /**
     * The next missing part after [from] that nobody is downloading, claimed for the caller: 2 MB pieces close to the reading
     * position (so all the connections work on what is needed next and none of them holds the player up with a big piece),
     * 8 MB further on, several 8 MB at once for a heavy source. Never overlaps another connection's part or the spot the main
     * download is writing.
     */
    private fun claimNext(e: FileEntry, from: Long, to: Long): Pair<Long, Long>? = synchronized(e.claims) {
        val mainAt = if (e.worker != null) e.writePos else -1L
        var off = e.have.firstGapFrom(from)
        var guard = 0
        while (off < to && guard++ < 10_000) {
            // the main download is writing here: leave it the next 2 MB
            if (mainAt >= 0 && off >= mainAt && off < mainAt + NEAR_SEG) { off = e.have.firstGapFrom(mainAt + NEAR_SEG); continue }
            val covering = e.claims.floorEntry(off)
            if (covering != null && covering.value > off) { off = e.have.firstGapFrom(covering.value); continue }
            val near = off < from + NEAR_BYTES
            val unit = if (near) NEAR_SEG else SEG
            var end = minOf(to, e.total, (off / unit + 1) * unit)
            if (!near && isHeavy(e)) end = minOf(to, e.total, (off / SEG + HEAVY_SEGMENTS) * SEG)
            e.claims.ceilingEntry(off + 1)?.let { if (it.key < end) end = it.key }
            if (mainAt > off && mainAt < end) end = mainAt
            // stop at data already on disk inside the piece (the rest of it is fetched by the next claim)
            end = minOf(end, maxOf(off + 1, nextHave(e, off, end)))
            if (end <= off) { off++; continue }
            e.claims[off] = end
            return off to end
        }
        null
    }

    /** First byte in [from, to) that is already on disk, or [to]. */
    private fun nextHave(e: FileEntry, from: Long, to: Long): Long {
        var p = from
        while (p < to) {
            if (e.have.contiguousEnd(p) > p) return p
            p = minOf(to, (p / ChunkStore.CHUNK + 1) * ChunkStore.CHUNK)
        }
        return to
    }

    private fun isClaimed(e: FileEntry, off: Long): Boolean = synchronized(e.claims) {
        val c = e.claims.floorEntry(off); c != null && c.value > off
    }

    /**
     * One extra connection's download. The request covers a long span of the file and the connection keeps streaming while it can
     * claim the piece after the one it is on, so a connection makes ONE request for up to [REQUEST_SPAN] of the file instead of
     * one per piece.
     *
     * This is how the player itself reads a file, and it is what a host with a slow first byte needs (a redirect chain, a cold CDN
     * node, a host that queues requests). At one request per 2 MB piece, a host that answers after a second spent a third of every
     * connection's life waiting to start: sixteen connections delivered less than one direct stream, and the extra requests are
     * also what makes a host start refusing (429). The claim still moves in 2 MB steps, so the pieces next to this one stay free
     * for the other connections and none of them holds a big piece while the player waits.
     */
    private fun downloadSegment(e: FileEntry, client: okhttp3.OkHttpClient, s0: Long, s1: Long, buf: ByteArray, wr: ChunkStore.Rw): Int {
        // the piece this connection holds right now: it moves forward as the claim is extended
        var claimStart = s0
        var claimEnd = s1
        // 1 = this connection could not finish its piece in time and gave it up (the caller counts it)
        var slow = 0
        try {
            val pos = e.have.firstGapFrom(s0)
            if (pos >= s1 || e.total <= 0) return 0
            val t0 = System.currentTimeMillis()
            // A closed range of one long span rather than an open-ended one: a host that answers 200 (not 206) to "bytes=N-" would
            // count as a refusal here, and 64 MB per request is already 32x fewer requests than one per piece. The connection may
            // extend its claim several times inside this span; when the span is used up the caller asks for the next one.
            val hardEnd = if (e.closedTotal > 0) minOf(e.total, e.closedTotal) else e.total
            // ...unless the piece after this one is already taken (another connection is on it, or it is on disk): then this
            // connection can never go past its own piece, and the request asks for exactly that piece. It used to ask for the long
            // span anyway and drop the answer after one piece - a dropped answer cannot be reused, so every piece cost a new
            // connection to the host (a new handshake each 512 KB) exactly when the buffer was thin and many connections sat side by side.
            val nextFree = e.have.contiguousEnd(s1) == s1 && synchronized(e.claims) {
                val c = e.claims.floorEntry(s1)
                val mainAt = if (e.worker != null) e.writePos else -1L
                !(c != null && c.value > s1) && !(mainAt >= 0 && s1 >= mainAt && s1 < mainAt + NEAR_SEG)
            }
            val reqEnd = if (nextFree) minOf(hardEnd, pos + REQUEST_SPAN) else minOf(hardEnd, maxOf(s1, pos + 1))
            val range = "bytes=$pos-${reqEnd - 1}"
            client.newCall(requestFor(e.src, e.url, range)).execute().use { r ->
                noteTtfb(e.url, System.currentTimeMillis() - t0)
                // The whole file from its start answers 200 to a range that begins at 0 (some hosts do): that is usable as it is
                val whole = r.code == 200 && pos == 0L
                if (r.code != 206 && !whole) {
                    // Refused (429 / 503 / 403...) or ranges not honoured. This used to return silently, so the connection asked for the
                    // same piece again at once, over and over - which only made a host that limits connections or requests worse.
                    // Now it counts as a failure (the connection waits longer each time) and the host gets fewer connections for a while.
                    noteRefused(e.url, r.code, 1 + e.boosters.get())
                    throw IOException("HTTP ${r.code} from the source")
                }
                val input = r.body?.byteStream() ?: return@use
                var off = if (whole) 0L else (parseStart(r.header("Content-Range")) ?: pos)
                var claimAt = System.currentTimeMillis()
                while (!e.stop) {
                    if (off >= claimEnd) {
                        // This piece is done: stay on the same connection if the next gap can be claimed for it
                        if (claimEnd >= reqEnd) break
                        val next = extendClaim(e, claimStart, claimEnd) ?: break
                        claimStart = claimEnd
                        claimEnd = minOf(next, reqEnd)
                        claimAt = System.currentTimeMillis()
                    }
                    val want = minOf(buf.size.toLong(), claimEnd - off).toInt()
                    if (want <= 0) break
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    wr.write(off, buf, n)
                    e.have.add(off, off + n)
                    e.dlBytes.addAndGet(n.toLong())
                    off += n
                    // The part this connection is writing was deleted behind the player meanwhile (a jump ahead). Its open file is
                    // gone from the folder, so these bytes are on no disk: they must not count as stored (a later jump back here
                    // found "data" it could not read, and the stream was cut), and the connection is of no use here any more.
                    val fl = e.floor
                    if (off <= fl || off - n < fl) { synchronized(e.store) { e.have.remove(0, minOf(off, e.floor)) }; wr.close(); if (off <= fl) break }
                    // Still inside its 512 KB piece after 4 s: this connection is standing between the player and its next bytes.
                    // The piece is released (its bytes are already on disk, so nothing is lost) and the region ahead is left to
                    // the other connections. The connection itself stays open and takes the next free piece - a socket that is
                    // only slow, not dead, is still worth keeping.
                    if (off < claimEnd && System.currentTimeMillis() - claimAt > SLOW_PIECE_MS) { slow = 1; break }
                }
            }
        } finally {
            // whatever piece this connection still holds (it may have moved several times)
            synchronized(e.claims) { e.claims.remove(claimStart) }
            trimBehind(e)
            val nowMs = System.currentTimeMillis()
            if (nowMs - e.lastSaved > 2_000) synchronized(e.metaFile) { e.lastSaved = nowMs; saveMeta(e) }
            // A full cache means "fetch less ahead", not "give up on this file": setting [FileEntry.stop] here also stopped the main
            // download, so a file that was playing fine stalled for good the moment the cache filled. This connection now waits and
            // tries again; what is already on disk keeps serving the player.
            if (!makeRoom(0, e.id)) {
                if (nowMs - e.lastFullLog > 30_000L) {
                    e.lastFullLog = nowMs
                    ServerState.warn("[MediaServer] the media cache is full (limit ${prefs.maxCacheMb} MB) - pausing the read-ahead for ${hostOf(e.url)}")
                }
                Thread.sleep(1_000)
            }
        }
        return slow
    }

    /**
     * Hands the connection that just finished [from, end) the next piece after it, when it is still free: the new end, or null.
     * The old claim is replaced by the longer one, so the other connections see one continuous region and never a hole.
     */
    private fun extendClaim(e: FileEntry, from: Long, end: Long): Long? = synchronized(e.claims) {
        if (e.total <= end) return null
        val ahead = aheadFor(e)
        val pp = playPos(e)
        // The piece lies behind the player (it jumped ahead): this connection must not go on fetching what was left behind - it
        // used to carry on to the end of its 64 MB request, sixteen of them at once, while the new position waited for data.
        if (end < maxOf(pp, e.floor)) return null
        val to = if (ahead > 0) minOf(e.total, pp + ahead) else e.total
        if (to <= end) return null
        // the next bytes are already taken (another connection, the main download, or they are on disk)
        val covering = e.claims.floorEntry(end)
        if (covering != null && covering.value > end) return null
        val mainAt = if (e.worker != null) e.writePos else -1L
        if (mainAt >= 0 && end >= mainAt && end < mainAt + NEAR_SEG) return null
        if (e.have.contiguousEnd(end) > end) return null
        val near = end < playPos(e) + NEAR_BYTES
        val unit = if (near) NEAR_SEG else SEG
        var newEnd = minOf(to, e.total, (end / unit + 1) * unit)
        e.claims.ceilingEntry(end + 1)?.let { if (it.key < newEnd) newEnd = it.key }
        if (mainAt > end && mainAt < newEnd) newEnd = mainAt
        newEnd = minOf(newEnd, maxOf(end + 1, nextHave(e, end, newEnd)))
        if (newEnd <= end) return null
        e.claims.remove(from)
        e.claims[end] = newEnd
        newEnd
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
        var open = Long.MAX_VALUE
        for (r in e.readers.values) {
            if (r[0] < open) open = r[0]
            if (now - r[1] < 60_000L && r[0] < low) low = r[0]
        }
        // None read lately but a connection is still open: a paused player (or one whose own buffer is full). Its position is
        // the playing one - "the last position anyone read" could be a short read elsewhere in the file (its end, for the index),
        // and then the part buffered around the real position was deleted and the download went to the wrong place.
        return if (low != Long.MAX_VALUE) low else if (open != Long.MAX_VALUE) open else e.lastRead
    }

    /** Deletes the 2 MB pieces that lie more than KEEP_BEHIND behind the playing position (only for sources that can seek). */
    private fun trimBehind(e: FileEntry) = synchronized(e.store) {
        val pp = playPos(e)
        if (!e.ranges || e.total < 0 || pp <= 0) return@synchronized
        val keepFrom = pp - KEEP_BEHIND
        val firstKept = keepFrom / ChunkStore.CHUNK
        if (keepFrom <= 0 || firstKept <= e.floor / ChunkStore.CHUNK) return@synchronized
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
                    // not where an extra connection is already busy
                    if (e.ranges) {
                        var g = 0
                        while (pos < e.total && g++ < 64) {
                            val c = synchronized(e.claims) { e.claims.floorEntry(pos)?.takeIf { it.value > pos } } ?: break
                            pos = e.have.firstGapFrom(c.value)
                        }
                    }
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
                    if (r.code != 200 && r.code != 206) { noteRefused(e.url, r.code, 1 + e.boosters.get()); throw IOException("HTTP ${r.code} from the source") }
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
                        val ahead = aheadFor(e)
                        // An origin that cannot seek is downloaded completely, at full speed: stopping would mean starting over
                        if (ahead > 0 && e.ranges && off - playPos(e) > ahead) {
                            // The window is full. With a player reading, keep the connection and wait for room (a player whose own
                            // buffer is full stops reading for a while but still has its connection open: that is not idle).
                            // With nobody reading, stop the download instead of holding a socket to the host open for up to
                            // [IDLE_STOP_MS]: checkPlayback (every 2 s) starts it again the moment the window needs more.
                            // (stop = true is what ends this worker; without it the outer loop would open a new request at once,
                            // hit the same full window and open another, over and over.)
                            if (e.readers.isEmpty()) { e.stop = true; break }
                            Thread.sleep(400)
                            continue
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(off, buf, n)
                        e.have.add(off, off + n)
                        e.dlBytes.addAndGet(n.toLong())
                        off += n
                        e.writePos = off
                        sinceSave += n
                        if (sinceSave > 32L * 1024 * 1024) {
                            sinceSave = 0; trimBehind(e); saveMeta(e)
                            // Hard limit: never let the folder pass the cap
                            if (!makeRoom(0, e.id)) throw IOException("the media cache is full (limit ${prefs.maxCacheMb} MB)")
                        }
                        if (e.have.contiguousEnd(off) > off) break // ran into data that is already on disk
                        if (e.ranges && isClaimed(e, off)) break // ran into a part an extra connection is fetching: go to the next free one
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
        Relay.logPlay(id, e.src.url, "media server")
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
                val buf = ByteArray(512 * 1024)
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
                        // nobody is fetching this spot (the main download is elsewhere and no extra connection has it): send the main one here
                        val near = e.writePos in (pos - 1)..(pos + 8L * 1024 * 1024)
                        if (e.ranges && !near && e.focus < 0 && !isClaimed(e, pos)) e.focus = pos
                        if (e.error != null && e.worker == null) throw IOException(e.error)
                        if (System.currentTimeMillis() - lastProgress > 90_000) throw IOException("download stalled")
                        // Short poll: the download writes in 128 KB steps, and 15 ms of sleep per step capped what one response could
                        // hand a player at about 8 MB/s however fast the host and the disk were - a ceiling a direct link never has.
                        // ...but only while data is flowing: when nothing has arrived for a moment (the host is slow, the player is
                        // buffering) checking 330 times a second per connection just burns CPU and battery
                        delay(if (System.currentTimeMillis() - lastProgress > 300L) 25 else 3)
                        continue
                    }
                    val n = minOf(avail - pos, end - pos + 1, buf.size.toLong()).toInt()
                    reader.read(pos, buf, n)
                    writeFully(buf, 0, n)
                    e.outBytes.addAndGet(n.toLong())
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
    /**
     * playlist key -> (source id, index of the segment the player asked for last, when). Per playlist, not per link: a link with
     * separate audio and video playlists plays both at once, and each needs its own read-ahead.
     */
    private val hlsPlay = ConcurrentHashMap<String, Triple<String, Int, Long>>()
    private val segWrites = AtomicLong(0)

    private fun segFile(id: String, url: String): File =
        File(File(root!!, "$id/hls").also { it.mkdirs() }, sha256(url).take(32))

    /** Downloads one playlist / segment, trying up to three times (hosts that hiccup must not stall the player). */
    private fun fetchRemote(id: String, src: MediaSource, url: String, stallS: Int = 0, attempts: Int = 3, finalUrl: Array<String?>? = null, maxBytes: Long = MAX_SEGMENT_BYTES): Pair<ByteArray, String?> {
        // stallS > 0: a connection that sends nothing for that long is dropped and retried on a new one (video segments)
        val client = clientFor(id, src).let { if (stallS > 0) it.newBuilder().readTimeout(stallS.toLong(), TimeUnit.SECONDS).build() else it }
        var last: Exception? = null
        for (attempt in 1..attempts) {
            try {
                client.newCall(requestFor(src, url)).execute().use { r ->
                    if (!r.isSuccessful) {
                        if (r.code in 400..499 && r.code != 408 && r.code != 429) throw IOException("HTTP ${r.code}")
                        throw IllegalStateException("HTTP ${r.code}")
                    }
                    // where the answer really came from (after redirects): relative links inside a playlist start there
                    finalUrl?.set(0, r.request.url.toString())
                    return (r.body?.let { readCapped(it, maxBytes) } ?: ByteArray(0)) to r.header("Content-Type")?.substringBefore(';')?.trim()
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true || e.message?.startsWith("too big") == true) throw e
                last = e
            } catch (e: IllegalStateException) {
                last = IOException(e.message)
            }
            if (attempt < attempts) Thread.sleep(250L * attempt)
        }
        throw last ?: IOException("download failed")
    }

    /**
     * A whole answer in memory, but never more than [max] bytes of it. A link labelled HLS that is really a video file (or a
     * "segment" that is the whole film) used to be read into memory completely - gigabytes - and the app died out of memory.
     */
    internal fun readCapped(body: okhttp3.ResponseBody, max: Long): ByteArray {
        val len = body.contentLength()
        if (len > max) throw IOException("too big (${len / 1_048_576} MB)")
        val source = body.source()
        source.request(max + 1)
        if (source.buffer.size > max) throw IOException("too big (over ${max / 1_048_576} MB)")
        return source.readByteArray()
    }
    /** A playlist is text: a few MB at the very most. */
    internal const val MAX_PLAYLIST_BYTES = 16L * 1024 * 1024
    private const val MAX_SEGMENT_BYTES = 128L * 1024 * 1024

    /** One byte range of a file (a segment of a playlist that cuts one big file into #EXT-X-BYTERANGE pieces). */
    private fun fetchRange(id: String, src: MediaSource, url: String, range: String, crFile: File): Pair<ByteArray, String?> {
        val client = clientFor(id, src).newBuilder().readTimeout(SEG_STALL_S.toLong(), TimeUnit.SECONDS).build()
        var last: IOException? = null
        for (attempt in 1..3) {
            var fatal = false
            try {
                client.newCall(requestFor(src, url, range)).execute().use { r ->
                    if (r.code != 206) {
                        // 200 = the host ignores ranges and would send the whole file: never read that into memory
                        fatal = r.code == 200 || (r.code in 400..499 && r.code != 408 && r.code != 429)
                        throw IOException(if (r.code == 200) "range ignored" else "HTTP ${r.code}")
                    }
                    if ((r.body?.contentLength() ?: -1L) > MAX_RANGE_BYTES) { fatal = true; throw IOException("range too big") }
                    // (capped also when the host does not say how long the answer is)
                    val bytes = r.body?.let { b -> runCatching { readCapped(b, MAX_RANGE_BYTES) }.getOrElse { fatal = true; throw it } } ?: ByteArray(0)
                    r.header("Content-Range")?.let { cr -> runCatching { crFile.writeText(cr) } }
                    return bytes to r.header("Content-Type")?.substringBefore(';')?.trim()
                }
            } catch (e: IOException) {
                if (fatal) throw e
                last = e
            }
            if (attempt < 3) Thread.sleep(250L * attempt)
        }
        throw last ?: IOException("download failed")
    }

    /** A byte-range piece bigger than this is passed through instead of being kept in memory and on disk. */
    private const val MAX_RANGE_BYTES = 64L * 1024 * 1024
    /** Separates a segment's url from its byte range in a segment reference ("url" or "url<SEP>bytes=a-b"). */
    private const val SEP = '\u0000'
    private val RANGE_EXACT = Regex("bytes=\\d+-\\d+")
    /** "id|url" of files that playlists cut into byte ranges */
    private val rangedUrls = ConcurrentHashMap.newKeySet<String>()
    /** "id|segment reference" -> the reference that replaced it when its signed link expired and the playlist was loaded again */
    private val segSwap = ConcurrentHashMap<String, String>()
    private val refreshedAt = ConcurrentHashMap<String, Long>()

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    private fun isPlaylistUrl(u: String) = u.substringBefore('?').endsWith(".m3u8", true)
    internal fun isPlaylistName(u: String) = isPlaylistUrl(u)

    /**
     * A link inside a playlist, made absolute. java.net.URI alone refused signed links with characters like | { } ^ or a space
     * in them (the relative link was then left as it was and the player asked this bridge for it: 404), and it resolved "?token=…"
     * and hosts without a path wrongly. OkHttp's resolver (the one the download uses anyway) takes all of those.
     */
    internal fun resolve(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        runCatching { base.toHttpUrlOrNull()?.resolve(r)?.toString() }.getOrNull()?.let { return it }
        return runCatching { java.net.URI(base).resolve(r).toString() }.getOrDefault(r)
    }

    private fun relTarget(id: String, url: String, playlist: Boolean): String =
        if (playlist) "../pl/${token(id, url)}.m3u8" else "../seg/${token(id, url)}"

    private val ATTRS = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    /**
     * A master playlist cut down to its highest rendition (tallest, then highest bitrate); audio / subtitle groups stay.
     * The same picture is often listed once per audio group (AAC stereo, Dolby 5.1…): one entry per group is kept, so the player
     * can still take the audio its device can decode - keeping a single entry could leave only a group it cannot play.
     */
    internal fun keepBestVariant(text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return text
        val lines = text.lines()
        class V(val infoIdx: Int, val urlIdx: Int, val height: Int, val bw: Long, val audio: String?)
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
                if (j < lines.size) vs += V(i, j, h, bw, a["AUDIO"])
                i = j
            }
            i++
        }
        if (vs.size < 2) return text
        val top = vs.maxWithOrNull(compareBy<V>({ it.height }, { it.bw })) ?: return text
        val drop = HashSet<Int>()
        val keep = ArrayList<V>()
        keep += top
        if (top.height > 0) vs.filter { it.height == top.height && it.audio != top.audio }.groupBy { it.audio }.values
            .forEach { g -> g.maxByOrNull { it.bw }?.let { keep += it } }
        for (v in vs) if (keep.none { it === v }) { drop += v.infoIdx; for (k in v.infoIdx..v.urlIdx) drop += k }
        return lines.filterIndexed { idx, _ -> idx !in drop }.joinToString("\n")
    }

    /**
     * Rewrites every URI of a playlist to this server (relative links, so any host/port works).
     * Audio / subtitle groups (#EXT-X-MEDIA) and I-frame lists are playlists whatever their name ends in; keys given as data: or
     * skd: stay as they are; a file cut into #EXT-X-BYTERANGE pieces is fetched piece by piece, never as a whole.
     */
    private fun rewritePlaylist(id: String, playlistUrl: String, text: String, playlistKey: String): String {
        val out = StringBuilder()
        val segments = ArrayList<String>()
        var nextIsVariant = false
        var pendingRange: String? = null
        val lastEnd = HashMap<String, Long>()
        fun rangeOf(spec: String, abs: String): String? {
            val n = spec.substringBefore('@').trim().toLongOrNull() ?: return null
            val o = spec.substringAfter('@', "").trim().toLongOrNull() ?: lastEnd[abs] ?: 0L
            lastEnd[abs] = o + n
            return "bytes=$o-${o + n - 1}"
        }
        fun markRanged(abs: String) { if (rangedUrls.size > 20_000) rangedUrls.clear(); rangedUrls.add("$id|$abs") }
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line).append('\n')
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsVariant = true
                    if (line.startsWith("#EXT-X-BYTERANGE:")) pendingRange = line.substringAfter(':').trim()
                    val isPlTag = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    val hasRange = line.contains("BYTERANGE=")
                    val fixed = if (line.contains("URI=\"")) URI_ATTR.replace(line) { m ->
                        val abs = resolve(playlistUrl, m.groupValues[1])
                        if (!abs.startsWith("http", true)) m.value else {
                            if (hasRange) markRanged(abs)
                            "URI=\"" + relTarget(id, abs, isPlTag || isPlaylistUrl(abs)) + "\""
                        }
                    } else line
                    out.append(fixed).append('\n')
                }
                else -> {
                    val abs = resolve(playlistUrl, line)
                    val isPl = nextIsVariant || isPlaylistUrl(abs)
                    if (!isPl) {
                        val range = pendingRange?.let { rangeOf(it, abs) }
                        if (range != null) { markRanged(abs); segments += abs + SEP + range } else segments += abs
                    }
                    pendingRange = null
                    nextIsVariant = false
                    out.append(if (abs.startsWith("http", true)) relTarget(id, abs, isPl) else line).append('\n')
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
        Relay.logPlay(id, url, "media server")
        touchDir(id)
        val key = "$id|$url"
        val cached = playlistCache[key]
        val local = isLoopbackUrl(url)
        val ttl = { t: String -> if (local && (t.contains("#EXT-X-ENDLIST") || t.contains("#EXT-X-STREAM-INF"))) 6 * 3_600_000L else playlistTtl(t) }
        val text = if (cached != null && System.currentTimeMillis() - cached.at < ttl(cached.text)) cached.text else {
            val fin = arrayOfNulls<String>(1)
            val early = takeReadPlaylist(url)?.also { fin[0] = it.first }?.second
            val raw = noBom(early ?: try {
                withContext(Dispatchers.IO) { String(fetchRemote(id, src, url, finalUrl = fin, maxBytes = MAX_PLAYLIST_BYTES).first) }
            } catch (t: Exception) {
                // the extension's server is gone: the copy saved on disk still works
                val saved = if (url == src.url) withContext(Dispatchers.IO) {
                    runCatching { File(root ?: return@runCatching null, "$id/playlist.m3u8").takeIf { it.exists() }?.readText() }.getOrNull()
                } else null
                saved ?: run {
                    ServerState.warn("[Media] playlist from ${LinkUrl.host(url)} failed: ${t.message}")
                    return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
                }
            })
            if (!isM3u(raw)) {
                ServerState.warn("[Media] ${LinkUrl.host(url)} did not answer with a playlist: \"${sample(raw)}\"")
                return call.respondText("Source did not return a playlist", status = HttpStatusCode.BadGateway)
            }
            val useRaw = if (StremioServer.streamPrefs.bestHls) keepBestVariant(raw) else raw
            // relative links start at the address the playlist really came from (a signed link often redirects)
            val base = fin[0] ?: url
            val rewritten = rewritePlaylist(id, base, useRaw, key)
            if (playlistCache.size > 500) playlistCache.clear()
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            // Start fetching the first segments right away
            if (raw.contains("#EXTINF")) prefetch(id, src, key, firstWanted(key, raw))
            if (useRaw.contains("#EXT-X-STREAM-INF")) warmVariants(id, src, base, useRaw)
            rewritten
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(text, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** A master playlist was opened: load its rendition playlists now, so the player's next request is instant. */
    private fun warmVariants(id: String, src: MediaSource, masterUrl: String, raw: String) {
        val lines = raw.lines().map { it.trim() }
        val urls = ArrayList<String>()
        val audio = ArrayList<Pair<String, Boolean>>()
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) urls += resolve(masterUrl, lines[j])
            } else if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                // separate audio: the player opens it together with the video, so it is loaded ahead as well (default language first)
                URI_ATTR.find(l)?.let { audio += resolve(masterUrl, it.groupValues[1]) to l.contains("DEFAULT=YES") }
            }
        }
        val all = urls.distinct().take(6) + audio.sortedByDescending { it.second }.map { it.first }.distinct().take(3)
        for (u in all.distinct().filter { it.startsWith("http", true) }) {
            val key = "$id|$u"
            if (playlistCache.containsKey(key)) continue
            scope.launch {
                prefetchLimit.withPermit {
                    runCatching {
                        val fin = arrayOfNulls<String>(1)
                        val text = String(fetchRemote(id, src, u, finalUrl = fin, maxBytes = MAX_PLAYLIST_BYTES).first)
                        if (isM3u(text)) {
                            val rewritten = rewritePlaylist(id, fin[0] ?: u, text, key)
                            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
                            if (isLoopbackUrl(u) && text.contains("#EXTINF")) prefetch(id, src, key, 0)
                        }
                    }
                }
            }
        }
    }

    /**
     * Where fetching ahead starts when a playlist is opened: the first segment of a finished video, the live edge of a live one.
     * A live playlist lists what was already broadcast, oldest first, and a player starts three segments from its end. Fetching
     * from the top downloaded up to 30 old segments nobody would ask for - at the moment the channel was starting, in front of
     * the segments the player wanted - and on a channel with a long rewind window one more old segment for every new one.
     */
    private fun firstWanted(key: String, text: String): Int =
        if (text.contains("#EXT-X-ENDLIST")) 0 else maxOf(0, (playlistSegs[key]?.size ?: 0) - 4)

    /** Finished (VOD) playlists keep for ten minutes, live ones for two seconds. */
    private fun playlistTtl(text: String): Long = if (text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) 600_000L else 2_000L

    private fun touchDir(id: String) {
        runCatching { File(root ?: return, id).setLastModified(System.currentTimeMillis()) }
    }

    private fun prefetch(id: String, src: MediaSource, playlistKey: String, fromIndex: Int, deep: Boolean = false) {
        val n = prefs.prefetchSegments.let { if (deep) minOf(it * DEEP_FACTOR, 120) else it }
        if (n <= 0) return
        val list = playlistSegs[playlistKey] ?: return
        for (i in fromIndex until minOf(list.size, fromIndex + n)) {
            val url = list[i]
            val f = segFile(id, url)
            if (f.exists()) continue
            val key = "$id|$url"
            if (!prefetching.add(key)) continue
            scope.launch {
                try {
                    prefetchLimit.withPermit { runCatching { ensureSegment(id, src, url) } }
                } finally { prefetching.remove(key) }
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
                // "url<SEP>bytes=a-b": one piece of a file that the playlist cuts into byte ranges
                val range = url.substringAfter(SEP, "")
                val (bytes, mime) = if (range.isEmpty()) fetchRemote(id, src, url, stallS = SEG_STALL_S, attempts = 5)
                    else fetchRange(id, src, url.substringBefore(SEP), range, File(f.parentFile, f.name + ".cr"))
                // its own temporary file: after a failed download a waiting request and a new one could both be here at once
                // (the lock is dropped on failure), and two writers on one ".tmp" left a mixed-up segment on disk
                val tmp = File(f.parentFile, f.name + "." + System.nanoTime() + ".tmp")
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

    /**
     * A segment answered 403 / 404 / 410: on a signed link that is what an expired signature looks like. The player keeps the
     * playlist it got at the start (a finished video's playlist is never asked for again), so every later segment would fail the
     * same way. The playlist is loaded again and the segment at the same place in it is used instead - for this one and, through
     * [segSwap], for all that follow. Only for finished (VOD) playlists whose segment count did not change.
     */
    private fun refreshed(id: String, src: MediaSource, ref: String): String? {
        val (pk, i) = segPos["$id|$ref"] ?: return null
        return synchronized(refreshedAt) {
            val old = playlistSegs[pk] ?: return null
            // another request already loaded it again
            if (old.getOrNull(i) != ref) return segSwap["$id|$ref"]
            val now = System.currentTimeMillis()
            if (now - (refreshedAt[pk] ?: 0L) < 10_000L) return null
            if (refreshedAt.size > 2_000) refreshedAt.clear()
            refreshedAt[pk] = now
            val plUrl = pk.substringAfter('|')
            val fin = arrayOfNulls<String>(1)
            val text = String(fetchRemote(id, src, plUrl, attempts = 1, finalUrl = fin, maxBytes = MAX_PLAYLIST_BYTES).first)
            if (!isM3u(text) || !text.contains("#EXT-X-ENDLIST") || !text.contains("#EXTINF")) return null
            val rewritten = rewritePlaylist(id, fin[0] ?: plUrl, text, pk)
            val fresh = playlistSegs[pk] ?: return null
            if (fresh.size != old.size) return null
            playlistCache[pk] = CachedPlaylist(now, rewritten)
            if (segSwap.size > 60_000) segSwap.clear()
            var changed = 0
            old.forEachIndexed { k, o -> if (o != fresh[k]) { segSwap["$id|$o"] = fresh[k]; changed++ } }
            if (changed > 0) ServerState.info("[MediaServer] ${LinkUrl.host(plUrl) ?: "?"}: the segment links had expired - the playlist was loaded again ($changed new links)")
            fresh[i].takeIf { it != ref }
        }
    }

    suspend fun serveSegment(call: ApplicationCall, id: String, tok: String) {
        val src = sourceFor(id) ?: return call.respondText("Not found", status = HttpStatusCode.NotFound)
        val url = urlOfToken(id, tok) ?: return call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
        served.incrementAndGet()
        touchDir(id)
        // a file the playlist cuts into byte ranges: only the piece asked for is fetched (the whole file could be gigabytes)
        val reqRange = call.request.header("Range")?.replace(" ", "")
        val ranged = reqRange != null && rangedUrls.contains("$id|$url") && RANGE_EXACT.matches(reqRange)
        var ref = if (ranged) url + SEP + reqRange else url
        // its signed link expired earlier and the playlist was loaded again: the new link for the same segment
        var hops = 0
        while (hops++ < 4 && !segFile(id, ref).exists()) ref = segSwap["$id|$ref"] ?: break
        val file = try {
            withContext(Dispatchers.IO) { ensureSegment(id, src, ref) }
        } catch (t: Exception) {
            val first = ref
            val alt = if (t.message?.startsWith("HTTP 4") == true) withContext(Dispatchers.IO) { runCatching { refreshed(id, src, first) }.getOrNull() } else null
            val again = if (alt != null) withContext(Dispatchers.IO) { runCatching { ensureSegment(id, src, alt) }.getOrNull() } else null
            if (again == null || alt == null) {
                // a piece too big to keep, or a host that ignores the range: passed straight through instead
                if ((ranged && t.message?.startsWith("HTTP 4") != true) || t.message?.startsWith("too big") == true) return Relay.serveStream(call, id, tok)
                return call.respondText("Source error: ${t.message}", status = HttpStatusCode.BadGateway)
            }
            ref = alt
            again
        }
        segPos["$id|$ref"]?.let { (pk, i) ->
            if (hlsPlay.size > 2_000) hlsPlay.clear()
            hlsPlay[pk] = Triple(id, i, System.currentTimeMillis())
            prefetch(id, src, pk, i + 1)
        }
        if (ranged) {
            val piece = withContext(Dispatchers.IO) { file.readBytes() }
            val cr = runCatching { File(file.parentFile, file.name + ".cr").takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
                ?: ("bytes " + reqRange!!.removePrefix("bytes=") + "/*")
            val pct = runCatching { ContentType.parse(guessSegmentType(url)) }.getOrDefault(ContentType.Application.OctetStream)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.response.header("Content-Range", cr)
            return call.respondBytes(piece, pct, HttpStatusCode.PartialContent)
        }
        // Only the first bytes are looked at to tell a playlist from a segment. The whole segment used to be read into memory for
        // that - and again for every request of it (tens of MB for a 4K segment, several at a time) - before being sent.
        val size = file.length()
        val isPlaylist = size in 8..2_000_000 && withContext(Dispatchers.IO) {
            runCatching { file.inputStream().use { ins -> val h = ByteArray(7); ins.read(h) == 7 && String(h) == "#EXTM3U" } }.getOrDefault(false)
        }
        // Some hosts hand out playlists under names without ".m3u8": recognise them by content and rewrite them too
        if (isPlaylist) {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val key = "$id|$url"
            // a playlist must not stay on disk as if it were a segment: a live one would never move on, a signed one never renew
            runCatching { file.delete() }
            val rewritten = rewritePlaylist(id, url, String(bytes).let { if (StremioServer.streamPrefs.bestHls) keepBestVariant(it) else it }, key)
            playlistCache[key] = CachedPlaylist(System.currentTimeMillis(), rewritten)
            if (rewritten.contains("#EXTINF")) prefetch(id, src, key, firstWanted(key, rewritten))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            return call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        }
        val mime = runCatching { File(file.parentFile, file.name + ".ct").takeIf { it.exists() }?.readText() }.getOrNull()
            ?: guessSegmentType(url)
        val ct = runCatching { ContentType.parse(mime) }.getOrDefault(ContentType.Application.OctetStream)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val range = parseRange(call.request.header("Range"), size)
        val start = range?.first ?: 0L
        val len = if (range != null) range.second - range.first + 1 else size
        if (range != null) call.response.header("Content-Range", "bytes ${range.first}-${range.second}/$size")
        // sent from the file in steps, never held in memory whole
        call.respondBytesWriter(ct, if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK, len) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(256 * 1024)
                var left = len
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    writeFully(buf, 0, n)
                    left -= n
                }
            }
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
