package com.cncverse.stremiobridge.server.hls

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Cache system for segments, init segments, and MPD manifests.
 * Centralized cache management with TTL and size limits.
 */
object SegmentCache {

    // Init segment cache (raw init data)
    data class InitCacheEntry(val data: ByteArray, val timestamp: Long)
    private val initSegmentCache = ConcurrentHashMap<String, InitCacheEntry>()
    private const val INIT_CACHE_TTL_MS = 600_000L // 10 minutes
    private const val MAX_INIT_CACHE_SIZE = 100

    // Cleaned init cache (without encryption metadata)
    private val cleanedInitCache = ConcurrentHashMap<String, ByteArray>()
    private const val MAX_CLEANED_INIT_CACHE_SIZE = 100

    // Decrypted segment cache
    data class SegmentCacheEntry(val data: ByteArray, val timestamp: Long)
    private val segmentCache = ConcurrentHashMap<String, SegmentCacheEntry>()
    private const val SEGMENT_CACHE_TTL_MS = 60_000L // 60 seconds
    private const val MAX_SEGMENT_CACHE_SIZE = 150

    // MPD manifest cache
    data class MpdCacheEntry(val content: String, val timestamp: Long)
    private val mpdCache = ConcurrentHashMap<String, MpdCacheEntry>()
    private const val MPD_CACHE_TTL_MS = 1000L // 1 second for live streams
    private const val MAX_MPD_CACHE_SIZE = 50

    // Prefetch tracking
    private val prefetchingUrls = ConcurrentHashMap.newKeySet<String>()

    // ==================== Init Segment Cache ====================

    fun getInitSegment(cacheKey: String): ByteArray? {
        val now = System.currentTimeMillis()
        val cached = initSegmentCache[cacheKey] ?: return null
        return if (now - cached.timestamp < INIT_CACHE_TTL_MS) cached.data else null
    }

    fun putInitSegment(cacheKey: String, data: ByteArray) {
        initSegmentCache[cacheKey] = InitCacheEntry(data, System.currentTimeMillis())
    }

    fun hasInitSegment(cacheKey: String): Boolean {
        return initSegmentCache.containsKey(cacheKey)
    }

    // ==================== Cleaned Init Cache ====================

    fun getCleanedInit(cacheKey: String): ByteArray? {
        return cleanedInitCache[cacheKey]
    }

    fun putCleanedInit(cacheKey: String, data: ByteArray) {
        cleanedInitCache[cacheKey] = data
    }

    fun hasCleanedInit(cacheKey: String): Boolean {
        return cleanedInitCache.containsKey(cacheKey)
    }

    // ==================== Segment Cache ====================

    fun getSegment(cacheKey: String): ByteArray? {
        val now = System.currentTimeMillis()
        val cached = segmentCache[cacheKey] ?: return null
        return if (now - cached.timestamp < SEGMENT_CACHE_TTL_MS) cached.data else null
    }

    /** Decrypted segments are whole byte arrays in RAM: never more than this in total, however many entries that is. */
    private const val MAX_SEGMENT_BYTES = 96L * 1024 * 1024
    private val sweepPlanned = java.util.concurrent.atomic.AtomicBoolean(false)

    fun putSegment(cacheKey: String, data: ByteArray) {
        segmentCache[cacheKey] = SegmentCacheEntry(data, System.currentTimeMillis())
        // by size, oldest first (the limit used to be 150 entries, checked only when the next segment was asked for)
        var total = segmentCache.values.sumOf { it.data.size.toLong() }
        if (total > MAX_SEGMENT_BYTES) {
            for (e in segmentCache.entries.sortedBy { it.value.timestamp }) {
                if (total <= MAX_SEGMENT_BYTES * 3 / 4 || e.key == cacheKey) break
                if (segmentCache.remove(e.key) != null) total -= e.value.data.size
            }
        }
        planSweep()
    }

    /**
     * Cleaning ran only at the start of the next segment request, so when playback stopped the last minute of decrypted video
     * (tens to hundreds of MB) stayed in memory until something was played again. Now a sweep follows every write once the
     * entries have expired, and repeats while anything is left.
     */
    private fun planSweep() {
        if (!sweepPlanned.compareAndSet(false, true)) return
        flightScope.launch {
            kotlinx.coroutines.delay(SEGMENT_CACHE_TTL_MS + 5_000L)
            sweepPlanned.set(false)
            runCatching { cleanup() }
            if (segmentCache.isNotEmpty() || initSegmentCache.isNotEmpty()) planSweep()
        }
    }

    fun hasSegment(cacheKey: String): Boolean {
        return segmentCache.containsKey(cacheKey)
    }

    // ==================== MPD Cache ====================

    fun getMpd(url: String): String? {
        val now = System.currentTimeMillis()
        val cached = mpdCache[url] ?: return null
        return if (now - cached.timestamp < MPD_CACHE_TTL_MS) cached.content else null
    }

    fun putMpd(url: String, content: String) {
        // Limit cache size
        if (mpdCache.size > MAX_MPD_CACHE_SIZE) {
            val oldestKey = mpdCache.entries.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { mpdCache.remove(it) }
        }
        mpdCache[url] = MpdCacheEntry(content, System.currentTimeMillis())
    }

    fun invalidateMpd(url: String) {
        mpdCache.remove(url)
    }

    // ==================== Prefetch Tracking ====================

    fun isPrefetching(key: String): Boolean = prefetchingUrls.contains(key)

    fun markPrefetching(key: String): Boolean = prefetchingUrls.add(key)

    fun unmarkPrefetching(key: String) = prefetchingUrls.remove(key)

    /** Live segment URL (without query) → the segments listed after it in the last playlist. */
    private class NextSegs(val at: Long, val urls: List<String>)
    private val nextSegments = ConcurrentHashMap<String, NextSegs>()

    fun rememberNextSegments(ordered: List<String>, ahead: Int) {
        val now = System.currentTimeMillis()
        // Only the live edge matters: players sit ~20 s behind it
        val tail = ordered.takeLast(20)
        for (i in 0 until tail.size - 1) {
            nextSegments[tail[i].substringBefore("?")] = NextSegs(now, tail.subList(i + 1, minOf(tail.size, i + 1 + ahead)).toList())
        }
    }

    fun nextSegments(url: String): List<String>? = nextSegments[url.substringBefore("?")]?.urls

    // ==================== Single-flight ====================

    /**
     * Upstream fetches in progress, by cache key. When many viewers watch the
     * same live channel they ask for the same segment within milliseconds;
     * only the first triggers an upstream fetch (through the channel's proxy),
     * the rest await its result — upstream/proxy bandwidth stays one copy per
     * segment however many people watch. The fetch runs in its own scope so a
     * viewer disconnecting never cancels it for the others.
     */
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Any>>()
    private val flightScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> singleFlight(key: String, load: suspend () -> T): T {
        val deferred = inFlight.computeIfAbsent(key) {
            flightScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { load() as Any }.also { d ->
                d.invokeOnCompletion { inFlight.remove(key, d) }
            }
        }
        deferred.start()
        return deferred.await() as T
    }

    // ==================== Cleanup ====================

    fun cleanup() {
        val now = System.currentTimeMillis()

        // Clean segment cache
        segmentCache.entries.removeIf { now - it.value.timestamp > SEGMENT_CACHE_TTL_MS }
        if (segmentCache.size > MAX_SEGMENT_CACHE_SIZE) {
            val oldest = segmentCache.entries.sortedBy { it.value.timestamp }
                .take(segmentCache.size - MAX_SEGMENT_CACHE_SIZE / 2)
            oldest.forEach { segmentCache.remove(it.key) }
        }

        // Clean init cache
        initSegmentCache.entries.removeIf { now - it.value.timestamp > INIT_CACHE_TTL_MS }
        if (initSegmentCache.size > MAX_INIT_CACHE_SIZE) {
            val oldest = initSegmentCache.entries.sortedBy { it.value.timestamp }
                .take(initSegmentCache.size - MAX_INIT_CACHE_SIZE / 2)
            oldest.forEach { initSegmentCache.remove(it.key) }
        }

        // the cleaned copies belong to the raw ones: when those have all expired nothing is playing any more
        if (initSegmentCache.isEmpty() && segmentCache.isEmpty()) cleanedInitCache.clear()
        // Clean cleaned init cache
        if (cleanedInitCache.size > MAX_CLEANED_INIT_CACHE_SIZE) {
            cleanedInitCache.keys.take(cleanedInitCache.size - MAX_CLEANED_INIT_CACHE_SIZE / 2)
                .forEach { cleanedInitCache.remove(it) }
        }

        nextSegments.entries.removeIf { now - it.value.at > 120_000L }

        // Clean MPD cache
        mpdCache.entries.removeIf { now - it.value.timestamp > MPD_CACHE_TTL_MS * 5 }
        if (mpdCache.size > MAX_MPD_CACHE_SIZE) {
            val oldest = mpdCache.entries.sortedBy { it.value.timestamp }
                .take(mpdCache.size - MAX_MPD_CACHE_SIZE / 2)
            oldest.forEach { mpdCache.remove(it.key) }
        }
    }

    fun clear() {
        initSegmentCache.clear()
        cleanedInitCache.clear()
        segmentCache.clear()
        mpdCache.clear()
        prefetchingUrls.clear()
        nextSegments.clear()
    }
}
