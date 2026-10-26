package com.cncverse.stremiobridge.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** What happened to the links of one extension in one search: how many it found, why some were dropped, how many were the same as another link. */
@Serializable
data class ExtReport(
    val name: String,
    val found: Int,
    /** Links of this extension in the list that was sent */
    val shown: Int = 0,
    /** "Hide HLS links is on: 3" */
    val rejected: List<String> = emptyList(),
    /** Links dropped because another link had exactly the same address */
    val merged: Int = 0,
)

/** One answered stream request: what was asked, what each extension found, and the exact list that was sent. */
@Serializable
data class HistoryEntry(
    val time: Long,
    val type: String,
    val id: String,
    val title: String,
    /** "S2 E2" for series / anime, "" for movies */
    val episode: String = "",
    /** All links found before filtering */
    val found: Int,
    /** Links sent to the client */
    val shown: Int,
    val tookMs: Long = 0,
    /** "AnimePahe: 4" */
    val providers: List<String> = emptyList(),
    /** Exactly as the client received them: name, a " | " and the description (new lines shown as " / ") */
    val lines: List<String> = emptyList(),
    /** The link of each line in [lines] (same order), "" when a line has none. Only kept for the newest entries. */
    val urls: List<String> = emptyList(),
    /** The search report: one row per extension that found links (empty in entries written by older versions) */
    val ext: List<ExtReport> = emptyList(),
)

object StreamHistory {
    private const val MAX = 150
    private const val KEEP_LINKS = 12
    /** Most results (and links) kept per entry, and the longest link kept: a request with hundreds of long links must stay light */
    private const val MAX_LINES = 120
    private const val MAX_URL = 700
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries
    @Volatile private var file: File? = null
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(cacheDir: String) {
        if (file != null) return
        file = File(cacheDir, "stream_history.json")
        runCatching {
            val f = file ?: return
            if (f.exists()) _entries.value = json.decodeFromString<List<HistoryEntry>>(f.readText())
        }
    }

    /** The resolved title of [id] (so the history shows names, not ids). */
    fun noteTitle(id: String, title: String) {
        if (titles.size > 500) titles.clear()
        titles[id] = title
    }

    fun titleFor(id: String): String? = titles[id]

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "history-writer").apply { isDaemon = true } }
    private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun add(e0: HistoryEntry) {
        val e = e0.copy(
            lines = e0.lines.take(MAX_LINES),
            urls = e0.urls.take(MAX_LINES).map { if (it.length > MAX_URL) "" else it },
        )
        synchronized(this) {
            // Links are long (some are over a kilobyte): only the newest entries keep them, so the file stays small
            val older = _entries.value.mapIndexed { i, old -> if (i + 1 >= KEEP_LINKS && old.urls.isNotEmpty()) old.copy(urls = emptyList()) else old }
            _entries.value = (listOf(e) + older).take(MAX)
        }
        save()
    }

    /** The file is written on its own thread (and once for a burst of requests), never while a request is being answered */
    private fun save() {
        if (!writePending.compareAndSet(false, true)) return
        writer.execute {
            try {
                Thread.sleep(500)
                writePending.set(false)
                val text = json.encodeToString(_entries.value)
                file?.writeText(text)
            } catch (_: Throwable) { writePending.set(false) }
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.delete() }
    }
}
