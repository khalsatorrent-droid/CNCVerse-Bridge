package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.StreamTracker
import com.cncverse.stremiobridge.state.currentTimeMillis
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.put
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val serverJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val httpClient by lazy {
    try {
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    } catch (_: Throwable) {}
    HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(serverJson)
        }
        try {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        } catch (e: Throwable) {}
    }
}

/**
 * Thread-safe list of loaded APIs that bumps [version] on every mutation, so
 * derived lookups (display-name slug counts) can be cached and invalidated
 * cheaply. The plugin loader mutates it while request threads iterate it.
 */
class LoadedApiList : java.util.concurrent.CopyOnWriteArrayList<MainApiWrapper>() {
    @Volatile var version: Long = 0L
        private set

    private fun bump() { version++ }

    override fun add(element: MainApiWrapper): Boolean = super.add(element).also { bump() }
    override fun add(index: Int, element: MainApiWrapper) { super.add(index, element); bump() }
    override fun addAll(elements: Collection<MainApiWrapper>): Boolean = super.addAll(elements).also { bump() }
    override fun addAll(index: Int, elements: Collection<MainApiWrapper>): Boolean = super.addAll(index, elements).also { bump() }
    override fun set(index: Int, element: MainApiWrapper): MainApiWrapper = super.set(index, element).also { bump() }
    override fun remove(element: MainApiWrapper): Boolean = super.remove(element).also { bump() }
    override fun removeAt(index: Int): MainApiWrapper = super.removeAt(index).also { bump() }
    override fun removeAll(elements: Collection<MainApiWrapper>): Boolean = super.removeAll(elements).also { bump() }
    override fun retainAll(elements: Collection<MainApiWrapper>): Boolean = super.retainAll(elements).also { bump() }
    override fun clear() { super.clear(); bump() }
}

/**
 * Manages the Ktor-based embedded HTTP server exposing the Stremio addon protocol.
 *
 * Routes:
 *  GET /manifest.json
 *  GET /catalog/{type}/{id}.json[?search=query]
 *  GET /meta/{type}/{id}.json
 *  GET /stream/{type}/{id}.json
 *  GET /                         (status HTML page)
 */
object StremioServer {

    private var engine: EmbeddedServer<*, *>? = null
    private var activePort: Int = 8080
    val isRunning: Boolean get() = engine != null

    /**
     * Holds live references to loaded [MainApiWrapper] instances.
     * Populated by the platform-specific [PluginLoader] after loading.
     */
    private val NON_ALNUM = Regex("[^a-zA-Z0-9]")
    private val slugMemo = ConcurrentHashMap<String, String>()

    private val apiList = LoadedApiList()
    val loadedApis: MutableList<MainApiWrapper> = apiList

    /**
     * Bounded pool for plugin work (catalog/meta/stream/pre-warm). Extensions
     * can be CPU-heavy (HTML parsing, extractor fuzzy matching); capping how
     * many run at once keeps the CPU from being oversubscribed so the HTTP
     * server, manifest/admin requests and the local proxies stay responsive
     * even under load.
     *
     * Sized for blocking I/O, not CPU: plugins mostly make blocking OkHttp calls
     * that hold their thread while waiting on the network. Sized by CPU count
     * (2 vCPU → 8 threads) the whole server could only have 8 plugin requests on
     * the wire, so stream searches (20 providers each), home pages and sweeps
     * queued behind each other and most providers hit their 38 s timeout.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pluginDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(64)

    /**
     * Shared provider catalog cache: provider internalName -> list of StremioCatalogDef.
     * Kept between refreshes so manifests never block on re-fetching sections.
     */
    val providerCatalogCache: MutableMap<String, List<StremioCatalogDef>> = ConcurrentHashMap()

    /**
     * Cached home page catalog results: "$type:$id:$genre" -> list of StremioMeta.
     */
    val homePageCatalogCache: MutableMap<String, List<StremioMeta>> = ConcurrentHashMap()
    /** When each home-page cache entry was filled (live-only providers refresh after [LIVE_HOME_TTL_MS]). */
    private val homePageCachedAt = ConcurrentHashMap<String, Long>()
    private val homePageRefreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val LIVE_HOME_TTL_MS = 2 * 60_000L
    private const val LIVE_PLAYLIST_TTL_MS = 15 * 60_000L
    /** Non-live home pages: refreshed on request once older than this. */
    private const val HOME_PAGE_TTL_MS = 30 * 60_000L

    /**
     * Cached binary bytes for the official CNCVerse logo / favicon.
     */
    val logoBytes: ByteArray? by lazy {
        runCatching {
            Thread.currentThread().contextClassLoader?.getResourceAsStream("logo.png")?.readBytes()
                ?: File("logo.png").takeIf { it.exists() }?.readBytes()
                ?: File("shared/src/commonMain/resources/logo.png").takeIf { it.exists() }?.readBytes()
        }.getOrNull()
    }

    private val manifestRefreshScope = CoroutineScope(pluginDispatcher + SupervisorJob())
    private var periodicRefreshJob: kotlinx.coroutines.Job? = null
    private val isRefreshing = AtomicBoolean(false)
    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes

    val disabledPlugins: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Sources (multi-source plugins) switched on individually, even while their plugin is off. */
    val enabledSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val enabledSourcesFile: java.io.File? get() = disabledPluginsFile?.let { java.io.File(it.parentFile, "enabled_sources.json") }
    private var disabledPluginsFile: File? = null

    /**
     * Stream preferences for this bridge: filtering, sorting and catalog choices,
     * edited in the app (Settings → Streams) and applied to every request.
     */
    @Serializable
    data class StreamPrefs(
        /** Catalog ids (`<type>/<id>`) hidden from the manifest. */
        val disabledCatalogs: Set<String> = emptySet(),
        /** Allowed resolutions (e.g. ["2160p", "1080p", "720p"]). Empty = all allowed. */
        val allowedResolutions: Set<String> = emptySet(),
        /** Exclude CAM / TeleSync / Screener recordings. */
        val excludeCam: Boolean = false,
        /** Maximum streams per quality tier (0 = unlimited). */
        val maxStreamsPerResolution: Int = 0,
        /** Streams go out without subtitle tracks and /subtitles answers empty. */
        val hideSubtitles: Boolean = false,
        /** Subtitle languages in order of preference ("English, Hindi"); empty = leave the order as it is. */
        val subtitleLangs: String = "",
        /** Only those languages (unless none of the tracks match). */
        val subtitleOnlyPreferred: Boolean = false,
        /** Moves every subtitle cue by this many milliseconds (positive = later). */
        val subtitleOffsetMs: Int = 0,
        /** The first 10 English subtitles are fetched here and handed out as ASS (white bold text, black outline, bottom centre). */
        val styledSubtitles: Boolean = true,
        /** Every TV extension's catalogs are shown as the one "All TV channels (A-Z)" catalog (the separate ones are not listed). */
        val combineTv: Boolean = true,
        /** TV extensions (plugin internal names) left out of the combined TV catalog; their own catalogs stay listed. */
        val tvOff: Set<String> = emptySet(),
        /** Every short-drama extension's catalogs are shown as one "Short dramas" catalog (the separate ones are not listed). */
        val combineShortDrama: Boolean = true,
        /** Extensions (plugin internal names) marked as short-drama by hand / taken out of the automatic detection. */
        val shortDramaOn: Set<String> = emptySet(),
        val shortDramaOff: Set<String> = emptySet(),
        /** Links of the styled subtitles: both | ass (".ass" link only) | plain (link without ".ass", for Nuvio mobile). */
        val styledSubtitleLinks: String = "both",
        /** How streams are grouped: default (by quality) | provider. */
        val groupBy: String = "default",
        /** Order inside a group: default | size (largest first, unknown size last). */
        val sortBy: String = "default",
        /** Streams with a KNOWN file size outside [minSizeGb, maxSizeGb] are dropped; 0 = no bound. */
        val minSizeGb: Double = 0.0,
        val maxSizeGb: Double = 0.0,
        /** Extension names in priority order: earlier ones come first inside each group. */
        val providerOrder: List<String> = emptyList(),
        /** Show the "support the project" entry on top of stream lists. */
        val showSupport: Boolean = false,
        /** Popups that extensions open on their own (donation / Telegram ads) are closed at once (Android). */
        val blockExtensionPopups: Boolean = true,
        /** A "refresh results" entry on top of every stream list: opens a page that clears this title's saved results and searches again. */
        val showRefresh: Boolean = true,
        /** Pixeldrain links (file ids) are sent through the fast mirror cdn.pixeldrain.eu.cc. */
        val pixeldrainBypass: Boolean = false,
        /** Hide direct-download files whose server cannot seek (no byte ranges). Relay/proxy links are never hidden. */
        val filterNonSeekable: Boolean = false,
        /**
         * With the media server OFF: hide links that only play with a Referer / Cookie / custom header (the player cannot send
         * them, so they never play). With the media server on they are kept: it sends the headers itself.
         */
        val hideHeaderLinks: Boolean = false,
        /** Drop every HLS (m3u8) link from movie / series results (live TV keeps them). */
        val hideHlsLinks: Boolean = false,
        /** Drop every direct video file (mp4 / mkv / ... ) from movie / series results. */
        val hideDirectFiles: Boolean = false,
        /**
         * Resolution cap, e.g. "1080p": streams above it are listed after everything within the cap
         * (a TV or phone that cannot decode 4K gets playable links first). "" = no cap.
         */
        val maxResolution: String = "",
        /** Streams with a KNOWN resolution below this are hidden ("720p" by default; "" = show everything). */
        val minResolution: String = "720p",
        /** Read adaptive HLS playlists to find their real resolution instead of listing them as "Auto". */
        val probeHls: Boolean = true,
        /** Show the results once, when every extension has answered (up to 45 s) instead of after a few seconds. */
        val waitForAll: Boolean = true,
        /** Adaptive (auto-resolution) HLS streams always play their highest resolution and are labelled with it. */
        val bestHls: Boolean = true,
        /** After an episode's links are loaded, load the next episode's links in the background. */
        val prefetchNext: Boolean = false,
        /** Offer each rendition of an adaptive HLS playlist as its own stream (1080p / 720p / ...). */
        val splitHls: Boolean = false,
        /** Drop streams whose resolution cannot be found at all. */
        val hideUnknownQuality: Boolean = false,
        /** Anime audio preference: "all" | "sub" | "dub" - the preferred kind is listed first. */
        val animeAudio: String = "all",
        /** With [animeAudio] sub/dub: hide the other kind completely. */
        val animeAudioOnly: Boolean = false,
    )

    @Volatile var streamPrefs: StreamPrefs = StreamPrefs()
        private set
    private var streamPrefsFile: File? = null

    private fun loadStreamPrefs() {
        val file = streamPrefsFile ?: return
        if (!file.exists()) return
        streamPrefs = runCatching { serverJson.decodeFromString<StreamPrefs>(file.readText()) }
            .onFailure { ServerState.warn("Failed to load stream_prefs.json: ${it.message}") }
            .getOrDefault(StreamPrefs())
    }

    /** Validates, applies and saves [p]. */
    fun updateStreamPrefs(p: StreamPrefs) {
        val max = p.maxSizeGb.coerceIn(0.0, 1000.0)
        val min = p.minSizeGb.coerceIn(0.0, 1000.0).let { if (max > 0 && it > max) max else it }
        val cleaned = p.copy(
            groupBy = p.groupBy.takeIf { it in setOf("default", "provider") } ?: "default",
            sortBy = p.sortBy.takeIf { it in setOf("default", "size") } ?: "default",
            minSizeGb = min,
            maxSizeGb = max,
            maxStreamsPerResolution = p.maxStreamsPerResolution.coerceIn(0, 100),
            maxResolution = p.maxResolution.takeIf { it in SELECTABLE_RESOLUTIONS } ?: "",
            minResolution = p.minResolution.takeIf { it in SELECTABLE_RESOLUTIONS } ?: "",
            animeAudio = p.animeAudio.takeIf { it in setOf("all", "sub", "dub") } ?: "all",
            providerOrder = p.providerOrder.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            subtitleLangs = p.subtitleLangs.trim().take(200),
            subtitleOffsetMs = p.subtitleOffsetMs.coerceIn(-60_000, 60_000),
            styledSubtitleLinks = p.styledSubtitleLinks.takeIf { it in setOf("both", "ass", "plain") } ?: "both",
        )
        streamPrefs = cleaned
        val file = streamPrefsFile ?: return
        runCatching { file.writeText(serverJson.encodeToString(cleaned)) }
            .onFailure { ServerState.warn("Failed to save stream_prefs.json: ${it.message}") }
    }

    /** Present ⇔ catalogs are turned off globally (survives restarts). */
    private var catalogsOffMarker: File? = null

    internal fun saveGlobalCatalogSetting() {
        val f = catalogsOffMarker ?: return
        runCatching { if (ServerState.disableCatalogsGlobally) f.writeText("1") else f.delete() }
            .onFailure { ServerState.warn("Failed to save global catalog setting: ${it.message}") }
    }

    private fun loadDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                disabledPlugins.clear()
                disabledPlugins.addAll(serverJson.decodeFromString<Set<String>>(json))
            } catch (e: Exception) {
                ServerState.warn("Failed to load disabled plugins: ${e.message}")
            }
        }
        enabledSourcesFile?.takeIf { it.exists() }?.let { f ->
            runCatching {
                enabledSources.clear()
                enabledSources.addAll(serverJson.decodeFromString<Set<String>>(f.readText()))
            }.onFailure { ServerState.warn("Failed to load enabled sources: ${it.message}") }
        }
    }

    internal fun saveDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        try {
            val json = serverJson.encodeToString(disabledPlugins)
            file.writeText(json)
            enabledSourcesFile?.writeText(serverJson.encodeToString(enabledSources.toSet()))
        } catch (e: Exception) {
            ServerState.warn("Failed to save disabled plugins: ${e.message}")
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                true
            }
        } catch (e: Throwable) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                    true
                }
            } catch (e2: Throwable) {
                false
            }
        }
    }

    private suspend fun findAvailablePort(defaultPort: Int): Int {
        // Retry default port a few times in case an old server instance is actively shutting down
        for (attempt in 1..3) {
            if (isPortAvailable(defaultPort)) return defaultPort
            if (attempt < 3) delay(300)
        }

        // If default port is occupied by another process, scan fallback ports
        for (port in (defaultPort + 1)..(defaultPort + 50)) {
            if (isPortAvailable(port)) {
                ServerState.info("Port $defaultPort is in use, falling back to port $port")
                return port
            }
        }

        // As a last resort, ask OS for an ephemeral port
        try {
            ServerSocket(0).use { socket ->
                val randomPort = socket.localPort
                ServerState.info("Default ports in use, using dynamic port $randomPort")
                return randomPort
            }
        } catch (e: Throwable) {
            // ignore
        }

        throw BindException("No available ports found between $defaultPort and ${defaultPort + 50}")
    }

    @Volatile private var configLoadedFrom: String? = null

    /**
     * Loads the saved configuration (disabled extensions, stream preferences,
     * catalogs switch, formatter, stream cache) from [cacheDir] once. Called at app
     * start so the settings screens edit the real, persisted values before the
     * server runs, and again (no-op) by [start].
     */
    fun loadConfig(cacheDir: String) {
        if (configLoadedFrom == cacheDir) return
        synchronized(this) {
            if (configLoadedFrom == cacheDir) return
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
            streamPrefsFile = File(cacheDir, "stream_prefs.json")
            loadStreamPrefs()
            Profiles.load(cacheDir)
            catalogsOffMarker = File(cacheDir, "catalogs_disabled_globally")
            ServerState.disableCatalogsGlobally = catalogsOffMarker?.exists() == true
            com.cncverse.stremiobridge.format.StreamFormatter.init(cacheDir)
            com.cncverse.stremiobridge.cache.StreamCacheManager.init(cacheDir)
            MediaServer.init(cacheDir)
            runCatching { NameStore.init(cacheDir) }
            runCatching { MatchMemory.init(cacheDir) }
            // settings saved before the store was ready at the first lookup are read now
            runCatching { com.cncverse.stremiobridge.network.DohConfig.reload() }
            runCatching { SubtitleTools.init(cacheDir) }
            UpstreamAddons.init(cacheDir); com.cncverse.stremiobridge.state.StreamHistory.init(cacheDir)
            com.cncverse.stremiobridge.network.CfCookieStore.initIfNeeded(cacheDir)
            WebSite.load(cacheDir)
            configLoadedFrom = cacheDir
        }
    }

    /** Re-reads every saved setting (after a backup import). */
    fun reloadConfig(cacheDir: String) {
        synchronized(this) {
            streamPrefs = StreamPrefs()
            disabledPlugins.clear()
            enabledSources.clear()
            configLoadedFrom = null
        }
        loadConfig(cacheDir)
        com.cncverse.stremiobridge.cache.StreamCacheManager.reloadConfigFromDisk()
        homePageCatalogCache.clear()
        encodedCatalogs.clear()
    }

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort
        if (cacheDir != null) {
            loadConfig(cacheDir)
        } else {
            val defaultCache = System.getProperty("user.home") + "/.cncverse_bridge"
            com.cncverse.stremiobridge.cache.StreamCacheManager.init(defaultCache)
        }
        val targetPort = findAvailablePort(port)
        activePort = targetPort
        engine = embeddedServer(CIO, port = targetPort, host = "0.0.0.0") {
            setupPlugins()
            setupRoutes()
        }.start(wait = false)
        ServerState.serverPort = targetPort
        ServerState.info("Stremio server started on port $targetPort")

        if (ServerState.isStremioMode.value) {
            com.cncverse.stremiobridge.tunnel.CloudflaredManager.startTunnel(targetPort)
        }

        initFastCatalogs()
        startPeriodicRefreshJob()

        return targetPort
    }

    fun stop() {
        stopPeriodicRefreshJob()
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        com.cncverse.stremiobridge.cache.StreamCacheManager.shutdown()
        MediaServer.shutdown()
        engine?.stop(0, 500)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    /**
     * Canonical identifiers for a loaded extension: the per-API internalName and the
     * backing plugin's internalName. These are always unique per plugin file and are
     * the primary keys used for the disabled set.
     *
     * NOTE: We deliberately exclude nameSlug(api.name) here. Display names are NOT
     * unique — two repos can ship a plugin with the same display name (e.g. "Netflix"),
     * and using the slug would cause disabling one to silently disable the other.
     * Slug matching is handled separately as a legacy-only, uniqueness-guarded fallback.
     */
    private fun canonicalIds(api: MainApiWrapper): List<String> =
        listOf(api.internalName, api.pluginInternalName).distinct()

    /**
     * Returns the display-name slug for [api] **only if** no other currently loaded
     * API shares the same slug. When two plugins have the same display name (cross-repo
     * duplicates) the slug is ambiguous and must NOT be used to identify either one.
     */
    private fun unambiguousSlug(api: MainApiWrapper): String? {
        val slug = nameSlug(api.name)
        return if ((slugCounts()[slug] ?: 0) == 1) slug else null
    }

    /** slug -> number of loaded APIs sharing it; rebuilt only when [loadedApis] changes. */
    @Volatile private var slugCountCache: Pair<Long, Map<String, Int>>? = null

    private fun slugCounts(): Map<String, Int> {
        val version = apiList.version
        slugCountCache?.let { (v, counts) -> if (v == version) return counts }
        val counts = HashMap<String, Int>()
        for (api in apiList) {
            val slug = nameSlug(api.name)
            counts[slug] = (counts[slug] ?: 0) + 1
        }
        slugCountCache = version to counts
        return counts
    }

    /** Catalog/meta/stream key for an API: unambiguous slug, or slug + plugin name. */
    private fun apiKey(api: MainApiWrapper): String =
        unambiguousSlug(api) ?: (nameSlug(api.name) + "_" + nameSlug(api.pluginInternalName))

    /**
     * Returns all alias keys in [disabledPlugins] that belong exclusively to the
     * extension identified by [internalName]. Slugs are only included when they are
     * unambiguous (unique to this plugin) — this prevents enabling plugin A from
     * accidentally removing a shared slug that plugin B still needs.
     */
    private fun allAliasesInDisabled(internalName: String): Set<String> {
        val result = mutableSetOf(internalName)
        // Collect canonical IDs from the matching loaded API(s)
        val matchingApis = loadedApis.filter { canonicalIds(it).contains(internalName) }
        // Per-source entries of multi-source plugins are the admin's own choices — the
        // plugin switch never clears them (only single-source plugins alias their API id)
        matchingApis.flatMapTo(result) { api ->
            if ((apisPerPlugin()[api.pluginInternalName] ?: 1) > 1 && api.internalName != internalName) listOf(api.pluginInternalName)
            else canonicalIds(api)
        }
        // Add unambiguous slug(s) so legacy slug entries are cleaned up on enable
        matchingApis.forEach { api ->
            unambiguousSlug(api)?.let { slug ->
                result += slug
                result += api.name          // exact display name variant
            }
        }
        // Also cover display name from installed plugin metadata (pre-load legacy)
        RepoState.installedPlugins.value
            .find { it.internalName == internalName }
            ?.let { inst ->
                result += inst.internalName
                if (inst.displayName.isNotBlank()) {
                    val dn = inst.displayName
                    val slug = nameSlug(dn)
                    // Only include display name / slug if no OTHER installed plugin shares it
                    val slugSharers = RepoState.installedPlugins.value.count { nameSlug(it.displayName) == slug }
                    if (slugSharers == 1) {
                        result += dn
                        result += slug
                    }
                }
            }
        return result
    }

    /**
     * Toggles a plugin's enabled state and persists it. Returns the new enabled state.
     *
     * When **enabling**, removes ALL alias entries that exclusively belong to this
     * plugin (canonical IDs + unambiguous slug) so legacy ghost entries are cleared
     * without touching sibling plugins that share the same display name.
     */
    fun togglePluginDisabled(internalName: String): Boolean {
        if (isSourceId(internalName)) {
            val api = loadedApis.first { it.internalName == internalName }
            val nowDisabled = !isGloballyDisabled(api)
            setSourceDisabled(internalName, nowDisabled)
            return !nowDisabled
        }
        val isCurrentlyDisabled = disabledPlugins.contains(internalName) ||
            loadedApis.any { api -> canonicalIds(api).contains(internalName) && isGloballyDisabled(api) }
        return if (isCurrentlyDisabled) {
            allAliasesInDisabled(internalName).forEach { disabledPlugins.remove(it) }
            saveDisabledPlugins()
            true
        } else {
            disabledPlugins.add(internalName)
            saveDisabledPlugins()
            false
        }
    }

    /**
     * Global on/off for one source of a multi-source plugin, independent of the
     * plugin switch: an enabled source stays on even while its plugin is off,
     * a disabled one stays off when the plugin is switched on.
     */
    fun setSourceDisabled(apiInternalName: String, disabled: Boolean) {
        if (disabled) {
            disabledPlugins.add(apiInternalName)
            enabledSources.remove(apiInternalName)
        } else {
            disabledPlugins.remove(apiInternalName)
            enabledSources.add(apiInternalName)
        }
        saveDisabledPlugins()
    }

    /** Number of home-page items cached for [api] (any type/section), 0 when none. */
    fun cachedHomeItems(api: MainApiWrapper): Int {
        val key = apiKey(api)
        return homePageCatalogCache.entries.filter { (k, _) -> k.contains(":cnc_${key}_") }.sumOf { it.value.size }
    }

    /** True when [id] is the per-source id of a plugin that has several sources. */
    fun isSourceId(id: String): Boolean =
        loadedApis.any { it.internalName == id && it.internalName != it.pluginInternalName } &&
            (apisPerPlugin()[loadedApis.first { it.internalName == id }.pluginInternalName] ?: 1) > 1

    /** Sets a plugin's global enabled state and persists it. */
    fun setPluginDisabled(internalName: String, disabled: Boolean = true) {
        if (disabled) {
            disabledPlugins.add(internalName)
        } else {
            allAliasesInDisabled(internalName).forEach { disabledPlugins.remove(it) }
        }
        saveDisabledPlugins()
    }

    /**
     * Removes stale/alias entries from [disabledPlugins], keeping only canonical
     * [InstalledPlugin.internalName] values. Run at startup and after bulk installs.
     */
    fun cleanupDisabledPlugins() {
        val installed = RepoState.installedPlugins.value
        if (installed.isEmpty()) return
        val canonicalNames = installed.map { it.internalName }.toSet()
        val before = disabledPlugins.size
        // Per-source entries ("<plugin>_<source>") of installed plugins are kept
        val isKept = { id: String -> id in canonicalNames || canonicalNames.any { id.startsWith(it + "_") } }
        val stale = disabledPlugins.filter { !isKept(it) }.toSet()
        enabledSources.removeIf { !isKept(it) }
        if (stale.isNotEmpty()) {
            stale.forEach { disabledPlugins.remove(it) }
            saveDisabledPlugins()
            ServerState.info("Disabled-plugins cleanup: removed ${stale.size} stale alias entries ($before → ${disabledPlugins.size})")
        }
    }

    /**
     * True when the admin has globally disabled this extension.
     *
     * Checks canonical IDs first (internalName, pluginInternalName — always unique).
     * Falls back to slug matching only when the slug is unambiguous (i.e. no other
     * loaded plugin shares the same display name), preventing cross-repo collisions.
     */
    fun isGloballyDisabled(api: MainApiWrapper): Boolean {
        // Serving an extra profile: it uses exactly the extensions it was given
        Profiles.current.get()?.let { return !Profiles.allows(it, api) }
        // A source's own choice wins over its plugin's (multi-source plugins: PlayFy = Live
        // Events + Highlights + one source per playlist enabled in its settings)
        if (api.internalName != api.pluginInternalName) {
            if (disabledPlugins.contains(api.internalName)) return true
            if (enabledSources.contains(api.internalName)) return false
        }
        // Primary check: canonical unique IDs
        if (canonicalIds(api).any { disabledPlugins.contains(it) }) return true
        // Legacy fallback: slug, but only if it's unambiguous
        val slug = unambiguousSlug(api) ?: return false
        return disabledPlugins.contains(slug) || disabledPlugins.contains(api.name)
    }

    /** True when [id] (canonical name / slug) maps to a globally disabled extension. */
    fun isIdGloballyDisabled(id: String): Boolean {
        if (disabledPlugins.contains(id)) return true
        val api = loadedApis.find { profileMatchIds(it).contains(id) } ?: return false
        return isGloballyDisabled(api)
    }


    private fun Application.setupPlugins() {
        install(ContentNegotiation) { json(serverJson) }
        // JSON was sent uncompressed: the biggest catalog is 14.6 MB raw vs 0.6 MB gzipped, and copying
        // that per viewer was a large share of the server's kernel time. Video segments and playlists
        // aren't JSON and stay untouched; pre-compressed catalogs opt out (suppressCompression).
        install(io.ktor.server.plugins.compression.Compression) {
            gzip {
                matchContentType(io.ktor.http.ContentType.Application.Json)
                minimumSize(1024)
                // Catalog routes gzip their own cached bytes once (respondCatalogJson); compressing
                // here as well sent big catalogs gzipped twice
                condition { !request.path().contains("/catalog/") }
            }
        }
        
        // Stremio Web (and sometimes Desktop) can be very strict or send 'Origin: null'. 
        // Manually appending these headers ensures maximum compatibility across all Stremio clients.
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
            call.response.header("Access-Control-Allow-Origin", "*")
            call.response.header("Access-Control-Allow-Headers", "*")

            // Track the public base URL from the Host header so proxy/admin URLs
            // reflect the domain the client is connecting through (not the LAN IP).
            val reqHost = call.request.host()
            val reqPort = call.request.port()
            val inferredBase = if (reqPort > 0 && reqPort != 80 && reqPort != 443) {
                "http://$reqHost:$reqPort"
            } else {
                "http://$reqHost"
            }
            ServerState.publicBaseUrl = inferredBase
            if (ServerState.ownHosts.size < 64) ServerState.ownHosts.add(reqHost.lowercase())
            if (reqPort > 0 && ServerState.ownPorts.size < 16) ServerState.ownPorts.add(reqPort)
            
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                return@intercept // End pipeline for OPTIONS
            }
        }
    }

    private fun Application.setupRoutes() {
        setupMpdProxyRoutes()
        routing {
            // The website version (off unless switched on in the app): /home and /web/...
            with(WebSite) { webRoutes() }
            // Configuration lives in the app; the root just says the addon is up
            get("/") {
                call.respondText(
                    "CNCVerse Bridge is running. Add /manifest.json to Stremio or Nuvio; configure it in the CNCVerse Bridge app.",
                    ContentType.Text.Plain,
                )
            }
            get("/logo.png") {
                val bytes = logoBytes
                if (bytes != null) {
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } else {
                    call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
                }
            }
            get("/favicon.ico") {
                val bytes = logoBytes
                if (bytes != null) {
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } else {
                    call.respondRedirect("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png")
                }
            }

            get("/refresh/{type}/{id}") { call.respondRefresh() }

            // Links to an extension's own server on this machine (Re:ANIME: http://127.0.0.1:41949/…)
            get("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }
            head("/proxy/local/{port}/{path...}") {
                val port = call.parameters["port"]?.toIntOrNull() ?: return@head call.respond(HttpStatusCode.NotFound)
                LocalRelay.handle(call, port, call.parameters.getAll("path").orEmpty().joinToString("/"), call.request.queryString())
            }

            // Subtitle with a timing offset: the file is fetched here, moved and handed out as WebVTT
            get("/subshift") {
                val u = call.request.queryParameters["u"]
                val ms = call.request.queryParameters["ms"]?.toIntOrNull()?.coerceIn(-60_000, 60_000) ?: 0
                if (u.isNullOrBlank() || !u.startsWith("http", true)) return@get call.respond(HttpStatusCode.BadRequest)
                val text = withContext(Dispatchers.IO) { SubtitleTools.fetchShifted(u, ms) }
                // could not be read here: the player gets the original file instead of nothing
                if (text == null) call.respondRedirect(u)
                else call.respondText(text, ContentType.parse("text/vtt; charset=utf-8"))
            }

            // The same subtitle as a styled ASS file (the .ass name helps players that go by the extension)
            get("/subssa/{name}") { respondStyledSub(call) }
            head("/subssa/{name}") { respondStyledSub(call) }
            // links handed out before 1.7.46 (they now get the SSA file too)
            get("/subass/{name}") { respondStyledSub(call) }
            // some players check the file with HEAD first
            head("/subass/{name}") { respondStyledSub(call) }
            get("/subsrt/{name}") { respondStyledSub(call, srt = true) }
            head("/subsrt/{name}") { respondStyledSub(call, srt = true) }

            // ── Media server: files and HLS cached on this device, then served to the player ──
            get("/media/{id}/file/{name}") { MediaServer.serveFile(call, call.parameters["id"].orEmpty(), call.parameters["name"].orEmpty()) }
            head("/media/{id}/file/{name}") { MediaServer.serveFile(call, call.parameters["id"].orEmpty(), call.parameters["name"].orEmpty()) }
            get("/media/{id}/pl/{tok}") { MediaServer.servePlaylist(call, call.parameters["id"].orEmpty(), call.parameters["tok"].orEmpty()) }
            get("/media/{id}/seg/{tok}") { MediaServer.serveSegment(call, call.parameters["id"].orEmpty(), call.parameters["tok"].orEmpty()) }

            // ── Addon ────────────────────────────────────────────────────────
            // /u/{id}/… are the links of the extra profiles (Settings > Profiles); an id that is not a profile
            // serves the main add-on (older installs made with such links keep working).
            get("/manifest.json") { ServerState.info("[Request] manifest"); call.respond(buildManifest()) }
            get("/u/{profileId}/manifest.json") { call.asProfile { call.respond(buildManifest()) } }

            get("/catalog/{path...}") { call.asProfile { call.respondCatalog() } }
            get("/u/{profileId}/catalog/{path...}") { call.asProfile { call.respondCatalog() } }

            get("/meta/{type}/{id}.json") { call.asProfile { call.respondMeta() } }
            get("/u/{profileId}/meta/{type}/{id}.json") { call.asProfile { call.respondMeta() } }

            get("/stream/{type}/{id}.json") { call.asProfile { call.respondStreams() } }
            get("/u/{profileId}/stream/{type}/{id}.json") { call.asProfile { call.respondStreams() } }

            get("/subtitles/{type}/{id}.json") { call.asProfile { call.respondSubtitles() } }
            get("/u/{profileId}/subtitles/{type}/{id}.json") { call.asProfile { call.respondSubtitles() } }
        }
    }

    /** Runs [block] as the profile named in the link (/u/{profileId}/…), or as the main profile. */
    private suspend fun ApplicationCall.asProfile(block: suspend () -> Unit) {
        Profiles.run(Profiles.find(parameters["profileId"])) { block() }
    }

    /** The stream cache, with the profile of the request carried into the shared fetch (it runs in a scope of its own). */
    private suspend fun profiledFetch(
        key: String, providerKey: String?,
        fetcher: suspend () -> com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult,
    ): List<StremioStream> {
        val prof = Profiles.current.get()
        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetchResult(key, providerKey) { Profiles.run(prof) { fetcher() } }
    }

    // ── Route handlers ───────────────────────────────────────────────────────

    /** Serves a catalog request (empty when catalogs are turned off). */
    private suspend fun ApplicationCall.respondCatalog() {
        if (ServerState.disableCatalogsGlobally) {
            respond(StremioCatalogResponse(emptyList()))
            return
        }

        val pathSegments = parameters.getAll("path") ?: emptyList()
        if (pathSegments.size < 2) {
            respond(HttpStatusCode.BadRequest)
            return
        }

        val type = pathSegments[0]

        if (pathSegments.size == 2) {
            val idWithExt = pathSegments[1]
            if (!idWithExt.endsWith(".json")) {
                respond(HttpStatusCode.NotFound)
                return
            }

            val id = idWithExt.removeSuffix(".json")
            val search = request.queryParameters["search"]
            val skip = request.queryParameters["skip"]?.toIntOrNull() ?: 0

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, null) }
            respondCatalogJson(metas, cacheable = search.isNullOrBlank())
        } else if (pathSegments.size == 3) {
            val id = pathSegments[1]
            val extraWithExt = pathSegments[2]
            if (!extraWithExt.endsWith(".json")) {
                respond(HttpStatusCode.NotFound)
                return
            }

            val extraStr = extraWithExt.removeSuffix(".json")
            val parsedExtra = io.ktor.http.parseQueryString(extraStr)

            val search = parsedExtra["search"] ?: request.queryParameters["search"]
            val skip = (parsedExtra["skip"] ?: request.queryParameters["skip"])?.toIntOrNull() ?: 0
            val genre = parsedExtra["genre"]

            val metas = withContext(pluginDispatcher) { buildCatalog(type, id, search, skip, genre) }
            respondCatalogJson(metas, cacheable = search.isNullOrBlank())
        } else {
            respond(HttpStatusCode.BadRequest)
        }
    }

    /**
     * Big home-page catalogs (live TV playlists: thousands of channels) are served from
     * homePageCatalogCache as the same list instance until refreshed, yet were re-encoded to
     * JSON on every request — one of the larger CPU costs. Their bytes are kept with the exact
     * list they came from (identity check), so a refreshed page is re-encoded once and a stale
     * encoding can never be served.
     */
    /** Only the gzipped JSON is kept (~0.6 MB for a 15 MB catalog); the raw form is inflated on demand. */
    private class EncodedCatalog(val metas: List<StremioMeta>, val gzipped: ByteArray)
    private val encodedCatalogs = ConcurrentHashMap<Int, EncodedCatalog>()
    private const val ENCODE_CACHE_MIN_ITEMS = 200
    private const val ENCODE_CACHE_MAX = 64
    /** Encoded catalogs can be ~15 MB each (big playlists): cap the total, not just the count. */
    private const val ENCODE_CACHE_MAX_BYTES = 300L * 1024 * 1024

    private suspend fun ApplicationCall.respondCatalogJson(metas: List<StremioMeta>, cacheable: Boolean) {
        if (!cacheable || metas.size < ENCODE_CACHE_MIN_ITEMS) {
            respond(StremioCatalogResponse(metas))
            return
        }
        val key = System.identityHashCode(metas)
        val encoded = encodedCatalogs[key]?.takeIf { it.metas === metas }
            ?: withContext(Dispatchers.Default) {
                // Streamed straight into gzip: building the JSON as one String (~30 MB for the
                // biggest playlists) plus a 15 MB byte copy, several at once, filled the heap with
                // oversized (humongous) objects and crashed the server with OutOfMemoryError.
                val out = java.io.ByteArrayOutputStream(256 * 1024)
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                java.util.zip.GZIPOutputStream(out, 64 * 1024).use { gz ->
                    serverJson.encodeToStream(StremioCatalogResponse.serializer(), StremioCatalogResponse(metas), gz)
                }
                EncodedCatalog(metas, out.toByteArray())
            }.also {
                if (encodedCatalogs.size >= ENCODE_CACHE_MAX ||
                    encodedCatalogs.values.sumOf { e -> e.gzipped.size.toLong() } + it.gzipped.size > ENCODE_CACHE_MAX_BYTES
                ) encodedCatalogs.clear()
                encodedCatalogs[key] = it
            }
        if (request.headers[io.ktor.http.HttpHeaders.AcceptEncoding]?.contains("gzip", ignoreCase = true) == true) {
            response.headers.append(io.ktor.http.HttpHeaders.ContentEncoding, "gzip")
            response.headers.append(io.ktor.http.HttpHeaders.Vary, io.ktor.http.HttpHeaders.AcceptEncoding)
            respondBytes(encoded.gzipped, io.ktor.http.ContentType.Application.Json)
        } else {
            // Rare (clients without gzip): inflate on the fly
            respondOutputStream(io.ktor.http.ContentType.Application.Json) {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(encoded.gzipped)).use { it.copyTo(this) }
            }
        }
    }

    private suspend fun ApplicationCall.respondMeta() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        val meta = withContext(pluginDispatcher) { buildMeta(type, id) }
        if (meta != null) {
            respond(StremioMetaResponse(meta))
        } else {
            respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun ApplicationCall.respondStreams() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)
        val requestStart = System.currentTimeMillis()
        streamRequestStart["$type:$id"] = requestStart
        ServerState.info("[Request] streams for $type/$id from ${request.local.remoteHost.let { request.headers["X-Forwarded-For"] ?: it }}")

        // The player is not kept waiting for ever: after RESPONSE_CAP_MS an empty list goes out (late results
        // are in the cache for the next try)
        // The search runs detached: a player that hangs up (or this cap) must not cancel it, it keeps filling the cache
        val search = streamSearchScope.async(pluginDispatcher + Profiles.element()) { buildStreams(type, id) }
        val built = withTimeoutOrNull(RESPONSE_CAP_MS) { search.await() }
        if (built == null) ServerState.warn("[Request] $type/$id: search still running after ${RESPONSE_CAP_MS / 1000}s - answering now, results will be in the cache for the next try")
        val prep = prepareStreams(type, id, built, requestStart)
        val streams = prep.streams
        val out = prep.out
        val safeOut = prep.safeOut
        val p = streamPrefs
        // The history entry is written whatever happens to the connection (a player that hung up must not lose it)
        try {
            try {
                respond(StremioStreamResponse(safeOut))
            } catch (e: Throwable) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ServerState.warn("[Request] $type/$id: sending the list failed (${e.javaClass.simpleName}: ${e.message}) - sending it without subtitles")
                respond(StremioStreamResponse(safeOut.map { it.copy(subtitles = null) }))
            }
            ServerState.info("[Request] sent ${out.size} link(s) for $type/$id (${(System.currentTimeMillis() - requestStart) / 1000.0}s)")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { recordHistory(type, id, prep, requestStart) }
        }
        if (p.prefetchNext) prefetchNextEpisode(type, id)
    }

    private fun recordHistory(type: String, id: String, prep: Prepared, requestStart: Long) {
        runCatching {
            val streams = prep.streams
            val out = prep.out
            val parts = id.split(':')
            val ep = if ((type == "series" || type == "anime") && parts.size >= 3) {
                if (parts[0] == "kitsu" || parts[0] == "mal" || parts[0] == "anilist" || parts[0] == "anidb") "E${parts.last()}"
                else "S${parts[parts.size - 2]} E${parts.last()}"
            } else ""
            com.cncverse.stremiobridge.state.StreamHistory.add(
                com.cncverse.stremiobridge.state.HistoryEntry(
                    time = System.currentTimeMillis(), type = type, id = id,
                    title = com.cncverse.stremiobridge.state.StreamHistory.titleFor(id) ?: id.substringBefore(':'),
                    episode = ep, found = prep.trace.foundTotal.coerceAtLeast(streams.size), shown = out.size,
                    ext = prep.trace.report(out),
                    tookMs = System.currentTimeMillis() - requestStart,
                    providers = prep.trace.foundBy.entries.sortedByDescending { it.value }.map { "${it.key}: ${it.value}" },
                    lines = out.map { st -> listOfNotNull(st.name, st.title).joinToString(" | ").replace("\n", " / ").replace("\r", "") },
                    urls = out.map { st -> st.url ?: st.externalUrl ?: st.infoHash?.let { "magnet:?xt=urn:btih:$it" } ?: "" },
                )
            )
        }
    }

    private class Prepared(val streams: List<StremioStream>, val out: List<StremioStream>, val safeOut: List<StremioStream>, val trace: SearchTrace = SearchTrace())

    /**
     * What the stream settings did to each extension's links in one request (for the search report in History):
     * how many each extension found, how many were dropped at each step and why, and how many were the same link.
     */
    private class SearchTrace {
        val foundBy = java.util.LinkedHashMap<String, Int>()
        var foundTotal = 0
        private val rejected = java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, Int>>()
        private val merged = java.util.LinkedHashMap<String, Int>()
        private fun nameOf(st: StremioStream): String = st.info?.addonName ?: st.name ?: "?"
        private fun counts(l: List<StremioStream>): Map<String, Int> = l.groupingBy { nameOf(it) }.eachCount()
        fun start(built: List<StremioStream>) { foundTotal = built.size; foundBy.putAll(counts(built)) }
        /** Records what a step removed: links of each extension that were in [before] but not in [after]. */
        fun step(reason: String, before: List<StremioStream>, after: List<StremioStream>) {
            if (after.size >= before.size) return
            val a = counts(after)
            counts(before).forEach { (n, c) ->
                val d = c - (a[n] ?: 0)
                if (d > 0) { val m = rejected.getOrPut(n) { java.util.LinkedHashMap() }; m[reason] = (m[reason] ?: 0) + d }
            }
        }
        fun mergedAway(before: List<StremioStream>, after: List<StremioStream>) {
            if (after.size >= before.size) return
            val a = counts(after)
            counts(before).forEach { (n, c) -> val d = c - (a[n] ?: 0); if (d > 0) merged[n] = (merged[n] ?: 0) + d }
        }
        fun report(out: List<StremioStream>): List<com.cncverse.stremiobridge.state.ExtReport> {
            val shown = counts(out)
            val names = (foundBy.keys + rejected.keys + merged.keys).toList()
            return names.map { n ->
                com.cncverse.stremiobridge.state.ExtReport(
                    name = n, found = foundBy[n] ?: 0, shown = shown[n] ?: 0,
                    rejected = rejected[n]?.entries?.map { "${it.key}: ${it.value}" } ?: emptyList(),
                    merged = merged[n] ?: 0,
                )
            }.sortedByDescending { it.found }
        }
    }

    /**
     * Everything after the search - archive filter, quality / seek checks, sorting, the stream settings, formatting and the
     * media-server links. Shared by the add-on answer and by the website's live results.
     */
    private suspend fun prepareStreams(type: String, id: String, built: List<StremioStream>?, requestStart: Long, fast: Boolean = false, forWeb: Boolean = false): Prepared {
        val tBuilt = System.currentTimeMillis()
        val trace = SearchTrace()
        trace.start(built ?: emptyList())
        // Archive downloads (".zip" season packs) can never play in any player
        val streams = (built ?: emptyList()).filter { !SeekProbe.isArchiveUrl(it.url) }
        trace.step("Archive file (.zip), cannot be played", built ?: emptyList(), streams)
        // Filters and ordering from the app's stream settings
        val p = streamPrefs
        // Adaptive HLS links report no quality: read their playlist for the real resolution
        // "Hide HLS links" / "Hide direct files" (live TV is always HLS: it keeps its links)
        var byKind = streams
        if (type != "tv" && p.hideHlsLinks) { val n = byKind.filter { !isHlsLink(it) }; trace.step("Setting \"Hide HLS links\"", byKind, n); byKind = n }
        if (type != "tv" && p.hideDirectFiles) { val n = byKind.filter { !isDirectFileLink(it) }; trace.step("Setting \"Hide direct files\"", byKind, n); byKind = n }
        val probed = if (!fast && (p.probeHls || p.splitHls)) QualityProbe.enrich(byKind, p.splitHls, 4_000L, p.bestHls) else byKind
        val tProbed = System.currentTimeMillis()
        val sorted = sortStreamsByQuality(probed)

        // Pixeldrain first, so the seek check below judges the link that is really played: a pixeldrain.com/u/ID address is
        // a web page (it used to be hidden as "not a file") while the fast mirror it becomes is a seekable file
        // and the mirror's redirect is followed here: the list carries the address of the server that holds the file
        val profiled = if (fast) sorted else filterStreamsByProfile(sorted, p) { why, b, a -> trace.step(why, b, a) }
        val kept = if (fast) sorted else PixeldrainResolver.resolveAll(profiled.map { pixeldrainBypass(it, p) }).also { trace.step("Pixeldrain link could not be used", profiled, it) }
        // Whatever the settings above did, the list that goes out is sorted by resolution and has nothing below 720p
        val playableHere = if (p.hideHeaderLinks && !MediaServer.prefs.enabled) kept.filterNot { needsHeaders(it) }.also { trace.step("Setting \"Hide links that need headers\"", kept, it) } else kept
        val seekable = if (!fast && p.filterNonSeekable) SeekProbe.dropNonSeekable(playableHere).also { trace.step("Setting \"Hide non-seekable files\" (the server cannot seek or the link is download-only)", playableHere, it) } else playableHere
        val tSeek = System.currentTimeMillis()
        // live channels (fast): no probes, and no resolution rules - a 480p channel is still a channel
        val filtered = if (fast) seekable else {
            val arranged = arrangeStreams(seekable, p)
            val audioed = applyAnimeAudio(arranged, p).also { trace.step("Anime audio: only ${p.animeAudio} links wanted", arranged, it) }
            val ordered = hardsubFirst(audioed)
            enforceResolutionOrder(ordered).also { trace.step("Below the minimum resolution (${p.minResolution.ifBlank { "none" }})", ordered, it) }
        }
        ServerState.info("[Request] $type/$id timing: search ${(tBuilt - requestStart) / 1000.0}s, quality probe ${(tProbed - tBuilt) / 1000.0}s, seek check ${(tSeek - tProbed) / 1000.0}s")

        val out: List<StremioStream> = try {
            // Drop results whose link is exactly the same as an earlier (better ranked) one; different links are never merged
            val unique = filtered.distinctBy<StremioStream, Any> { it.url ?: it.externalUrl ?: it.infoHash?.let { h -> "magnet:$h" } ?: it.ytId?.let { y -> "yt:$y" } ?: it }
            trace.mergedAway(filtered, unique)

            // Streams of upstream add-ons already carry their add-on's own name and description: keep those
            // (marked with the add-on's name) and format only this bridge's own streams
            val (fromUpstream, own) = unique.partition { it.info?.linkType == "UPSTREAM" }
            // the running time gives each link's bitrate (size / time); a slow lookup is skipped, the next request has it cached
            // (looked up together with the files' sizes, which the links' own text rarely gives)
            val skipBitrate = type == "tv" || own.isEmpty()
            val (runtime, fileSizes) = if (skipBitrate) Pair<Int?, Map<String, Long>>(null, emptyMap()) else coroutineScope {
                val r = async { withTimeoutOrNull(2_500L) { runtimeSec(type, id) } }
                val z = async { runCatching { SizeProbe.fill(own, 2_500L) }.getOrDefault(emptyMap()) }
                r.await() to z.await()
            }
            // No answer for the running time: a usual length for the kind of title, and the bitrate is shown with a "~"
            val usualSec = when {
                skipBitrate -> null
                ExternalIds.parse(id)?.scheme?.let { it in ExternalIds.ANIME_SCHEMES } == true || type == "anime" -> 24 * 60
                type == "series" -> 42 * 60
                else -> 110 * 60
            }
            val ownFormatted = com.cncverse.stremiobridge.format.StreamFormatter.applyFor(
                own,
                com.cncverse.stremiobridge.format.StreamFormatter.contextFromId(type, id)
                    .copy(runtimeSec = runtime ?: usualSec, runtimeApprox = runtime == null && usualSec != null, sizes = fileSizes),
                null,
            ).associateBy { it.url ?: it.infoHash ?: it.hashCode().toString() }
            val upstreamTagged = fromUpstream.associateBy({ it.url ?: it.infoHash ?: it.hashCode().toString() }) { u ->
                val addon = u.info?.addonName.orEmpty()
                u.copy(name = listOf(addon.ifBlank { "Upstream" }, u.name?.takeIf { n -> n != addon }).filterNotNull().joinToString(" • "))
            }
            val formatted = unique.map { st ->
                val k = st.url ?: st.infoHash ?: st.hashCode().toString()
                ownFormatted[k] ?: upstreamTagged[k] ?: st
            }
            // "Only links that need headers": ask the hosts whether the headers are really needed (the website's growing lists only start the check)
            if (!fast) runCatching { HeaderNeed.precheck(formatted, if (forWeb) 0L else 3_500L) }
            // "Hide subtitles": no subtitle tracks go out (after formatting, so the description stays truthful)
            val out = (if (p.hideSubtitles) formatted.map { it.copy(subtitles = null) } else formatted)
                // Links into an extension's own server that no longer answers (and have no copy here) are left out
                .let { l -> l.filter { st -> !LocalRelay.isDeadLink(st) }.also { kept2 -> trace.step("The extension's own server no longer answers", l, kept2) } }
                // One odd link must never cost the whole answer: a link whose rewrite fails goes out as it is
                .map { st ->
                    try {
                        // Media server: links that need a Referer / Cookie are fetched by this bridge and cached here
                        LocalRelay.rewriteStream(MediaServer.rewrite(withStreamBase(st), mediaBase(), forWeb))
                    } catch (e: Throwable) {
                        ServerState.warn("[Request] could not prepare a link (${st.info?.addonName}): ${e.javaClass.simpleName}: ${e.message}")
                        st
                    }
                }
                .let { list ->
                    // "Support the project" entry on top, only above real results (can be hidden in the app)
                    val withSupport = if (p.showSupport && list.isNotEmpty()) listOf(SUPPORT_STREAM) + list else list
                    // "Refresh results" button first (it is not a video: it opens a page in the browser)
                    if (p.showRefresh) listOf(refreshStream(type, id, streams.size, list.size)) + withSupport else withSupport
                }
                .map { s -> s.subtitles?.let { subs -> s.copy(subtitles = SubtitleTools.process(subs, p, mediaBase(), isAnime(type, id))) } ?: s }

            out
        } catch (e: Throwable) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[Request] $type/$id: preparing the list failed (${e.javaClass.simpleName}: ${e.message}) - sending the plain list. ${e.stackTraceToString().take(900)}")
            filtered.mapNotNull { st -> if (st.url == null && st.externalUrl == null && st.infoHash == null) null else st.copy(subtitles = null) }
        }
        // Text with a broken emoji (a lone surrogate half) cannot be written as UTF-8 and would fail the whole answer
        val safeOut = out.map { sanitizeStream(it) }
        // the best direct links are opened now, so their slow start (the source resolving the file) is over when one is chosen
        // (not for the website's growing lists: their best links change while the search runs)
        if (!fast && !forWeb) runCatching { MediaServer.prewarm(safeOut) }
        return Prepared(streams, out, safeOut, trace)
    }

    // ── Website: search straight in the extensions (their own catalogs and their own links) ──
    private fun webExtApi(id: String): MainApiWrapper? = loadedApis.firstOrNull { it.internalName == id && !isGloballyDisabled(it) }

    private val extGate = kotlinx.coroutines.sync.Semaphore(4)

    /** The extensions the website can search one by one. */
    fun webExtListJson(): String {
        val a = kotlinx.serialization.json.buildJsonArray {
            loadedApis.filter { !isGloballyDisabled(it) }.sortedBy { it.name.lowercase() }.forEach { api ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", api.internalName); put("name", api.name)
                    put("lang", api.pluginLanguage ?: api.apiLang ?: "")
                })
            }
        }
        return a.toString()
    }

    /** What one extension finds for [q] (its own catalog). */
    suspend fun webExtSearchJson(ext: String, q: String): String {
        val api = webExtApi(ext) ?: return "[]"
        val res = extGate.withPermit {
            withTimeoutOrNull(20_000L) { withContext(pluginDispatcher) { runCatching { api.search(q) }.getOrDefault(emptyList()) } }
        } ?: emptyList()
        return kotlinx.serialization.json.buildJsonArray {
            res.take(60).forEach { r ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("title", r.name); put("url", r.url); put("poster", r.posterUrl ?: "")
                    put("type", r.type); put("year", r.year?.toString() ?: "")
                })
            }
        }.toString()
    }

    /** The page of one title of an extension: details, and the episodes (or the movie's own link data). */
    suspend fun webExtTitleJson(ext: String, url: String): String? {
        val api = webExtApi(ext) ?: return null
        val info = extGate.withPermit {
            withTimeoutOrNull(25_000L) { withContext(pluginDispatcher) { runCatching { api.load(url) }.getOrNull() } }
        } ?: return null
        return kotlinx.serialization.json.buildJsonObject {
            put("title", info.name); put("poster", info.posterUrl ?: info.backgroundUrl ?: "")
            put("desc", info.description ?: ""); put("type", info.type); put("year", info.year?.toString() ?: "")
            put("data", info.dataUrl)
            put("genres", kotlinx.serialization.json.buildJsonArray { info.genres.orEmpty().take(8).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("episodes", kotlinx.serialization.json.buildJsonArray {
                info.episodes.orEmpty().forEach { e ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("name", e.name ?: ""); put("s", e.season?.toString() ?: ""); put("e", e.episode?.toString() ?: "")
                        put("data", e.dataUrl); put("poster", e.posterUrl ?: ""); put("desc", e.description ?: "")
                        put("variant", e.variant ?: "")
                    })
                }
            })
        }.toString()
    }

    /** The streaming links ONE extension gives for a movie / episode, prepared like the website's other lists (no slow checks). */
    suspend fun webExtLinksJson(ext: String, data: String): String {
        val api = webExtApi(ext) ?: return serverJson.encodeToJsonElement(StremioStreamResponse(emptyList())).toString()
        val built = extGate.withPermit {
            withTimeoutOrNull(40_000L) { withContext(pluginDispatcher) { runCatching { api.loadLinks(data) }.getOrDefault(emptyList()) } }
        } ?: emptyList()
        val prep = prepareStreams("movie", "cncx:" + ext, built, System.currentTimeMillis(), fast = true, forWeb = true)
        val base = serverJson.encodeToJsonElement(StremioStreamResponse(prep.safeOut)).jsonObject
        val pool = runCatching {
            SubtitleTools.process(built.flatMap { it.subtitles.orEmpty() }, streamPrefs.copy(subtitleOnlyPreferred = false, subtitleOffsetMs = 0), "")
        }.getOrDefault(emptyList()).take(300)
        return JsonObject(base + mapOf("subs" to serverJson.encodeToJsonElement(pool))).toString()
    }

    /** Website "Short dramas": the combined short-drama catalog (a genre = one extension), [q] searches all of them. */
    suspend fun webDramaJson(skip: Int, genre: String?, q: String?): String {
        val items = withContext(pluginDispatcher) {
            withTimeoutOrNull(40_000L) { runCatching { buildAllDrama(q?.ifBlank { null }, skip, genre?.ifBlank { null }) }.getOrNull() }
        } ?: emptyList()
        val srcs = dramaSources()
        if (srcs.isEmpty()) return """{"none":true,"genres":[],"items":[]}"""
        return shelfJson(srcs.map { it.first.name }.distinct().takeIf { it.size > 1 }.orEmpty(), items, "")
    }

    /** Website "Music": the music extension (Monochrome): its home-page sections as genres, and its search. */
    suspend fun webMusicJson(skip: Int, genre: String?, q: String?): String {
        val apis = loadedApis.filter { a ->
            !isGloballyDisabled(a) && (a.name.contains("monochrome", true) ||
                a.supportedTypes.any { it.equals("Music", true) || it.equals("Audio", true) })
        }
        val api = apis.firstOrNull { it.name.contains("monochrome", true) } ?: apis.firstOrNull()
            ?: return """{"none":true,"genres":[],"items":[]}"""
        val def = (providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)).firstOrNull()
            ?: return """{"none":true,"genres":[],"items":[]}"""
        val genres = def.extra.firstOrNull { it.name == "genre" }?.options.orEmpty()
        val items = withContext(pluginDispatcher) {
            withTimeoutOrNull(30_000L) { runCatching { buildCatalog(def.type, def.id, q?.ifBlank { null }, skip, genre?.ifBlank { null }) }.getOrNull() }
        } ?: emptyList()
        return shelfJson(genres, items, api.name)
    }

    /** Items of a catalog for the website, each with the extension and address its title page opens with. */
    private fun shelfJson(genres: List<String>, items: List<StremioMeta>, source: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("source", source)
            put("genres", kotlinx.serialization.json.buildJsonArray { genres.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("items", kotlinx.serialization.json.buildJsonArray {
                items.forEach { m ->
                    val d = StremioIds.decode(m.id) ?: return@forEach
                    // catalog items name their extension by its name slug (see toStremiMeta), older ones by internal name
                    val api = loadedApis.firstOrNull { nameSlug(it.name) == d.first } ?: loadedApis.firstOrNull { it.internalName == d.first }
                        ?: loadedApis.firstOrNull { it.pluginInternalName == d.first } ?: return@forEach
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("title", m.name); put("poster", m.poster ?: "")
                        put("year", m.releaseInfo ?: m.year?.toString() ?: "")
                        put("ext", api.internalName); put("url", d.second)
                    })
                }
            })
        }.toString()

    /** One live channel source for the website: the links of that source only, without the slow checks, so the first one can start playing at once. */
    suspend fun webTvStreamsJson(id: String): String {
        val built = withTimeoutOrNull(25_000L) { withContext(pluginDispatcher) { buildStreams("tv", id) } } ?: emptyList()
        val prep = prepareStreams("tv", id, built, System.currentTimeMillis(), fast = true, forWeb = true)
        return serverJson.encodeToJsonElement(StremioStreamResponse(prep.safeOut)).toString()
    }

    /** The links found so far for a title (the list the providers add to while the search runs), by "type:id". */
    private val searchPartial = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<StremioStream>>()
    private val webSearches = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<StremioStream>>>()

    /**
     * The website's live results: with [start] the search for the title is started (detached) and the links found so far are
     * answered at once; asking again returns the growing list and how many sources have answered, until the search is finished.
     */
    suspend fun webStreamsJson(type: String, id: String, start: Boolean): String {
        val key = "$type:$id"
        if (start) {
            searchStatus.remove(key); searchPartial.remove(key)
            streamRequestStart[key] = System.currentTimeMillis()
            if (webSearches.size > 50) webSearches.clear()
            webSearches[key] = streamSearchScope.async(pluginDispatcher) { buildStreams(type, id) }
        }
        val job = webSearches[key]
        val status = searchStatus[key]
        val partial = searchPartial[key]
        val raw: List<StremioStream> = when {
            status != null && partial != null -> partial.toList().map { st ->
                val u = st.url
                if (u != null && (u.contains("\\/") || u.contains("\\u0026"))) st.copy(url = u.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")) else st
            }
            job != null && job.isCompleted -> job.await()
            else -> emptyList()
        }
        val finished = if (status != null) status.finished else (job == null || job.isCompleted)
        val prep = prepareStreams(type, id, raw, System.currentTimeMillis(), forWeb = true)
        val base = serverJson.encodeToJsonElement(StremioStreamResponse(prep.safeOut)).jsonObject
        // Every subtitle track any extension found for this title, once each, for any video of the list (the "Hide subtitles" setting is for Stremio / Nuvio only)
        val pool = runCatching {
            SubtitleTools.process(raw.flatMap { it.subtitles.orEmpty() }, streamPrefs.copy(subtitleOnlyPreferred = false, subtitleOffsetMs = 0), "")
        }.getOrDefault(emptyList()).take(300)
        return JsonObject(base + mapOf(
            "subs" to serverJson.encodeToJsonElement(pool),
            "found" to kotlinx.serialization.json.JsonPrimitive(raw.size),
            "done" to kotlinx.serialization.json.JsonPrimitive(status?.done?.get() ?: 0),
            "total" to kotlinx.serialization.json.JsonPrimitive(status?.total ?: 0),
            "finished" to kotlinx.serialization.json.JsonPrimitive(finished),
            "counts" to JsonObject((status?.counts ?: emptyMap<String, Int>()).entries.sortedByDescending { it.value }.associate { it.key to kotlinx.serialization.json.JsonPrimitive(it.value) }),
        )).toString()
    }

    /** Request headers a player cannot be asked to send: anything but the usual User-Agent / Accept family. */
    private val HARMLESS_HEADERS = setOf("user-agent", "accept", "accept-language", "accept-encoding", "connection", "range", "host", "cache-control")

    /** An HLS stream: an m3u8 playlist (also this bridge's DASH -> HLS conversion). */
    fun isHlsLink(s: StremioStream): Boolean {
        val u = s.url ?: return false
        return s.info?.linkType.equals("M3U8", true) || u.substringBefore('?').endsWith(".m3u8", true) || u.contains(".m3u8", true)
    }

    /** A direct video file: a plain http(s) link that is not a playlist, a DASH manifest, a torrent, YouTube or an external page. */
    fun isDirectFileLink(s: StremioStream): Boolean {
        val u = s.url ?: return false
        if (!u.startsWith("http", true) || s.infoHash != null || s.ytId != null || s.externalUrl != null) return false
        if (isHlsLink(s)) return false
        if (s.info?.linkType?.uppercase() in setOf("TORRENT", "MAGNET")) return false
        if (s.info?.linkType.equals("DASH", true) || u.substringBefore('?').endsWith(".mpd", true) || u.contains(".mpd?", true)) return false
        return true
    }

    fun needsHeaders(s: StremioStream): Boolean =
        s.behaviorHints?.proxyHeaders?.request.orEmpty().any { (k, v) -> v.isNotBlank() && k.lowercase() !in HARMLESS_HEADERS }

    private val prefetchedNext = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The id of the episode after [id] ("tt1:1:5" -> "tt1:1:6", "kitsu:7:12" -> "kitsu:7:13"), or null for movies / unknown shapes. */
    private fun nextEpisodeId(type: String, id: String): String? {
        if (type != "series" && type != "anime") return null
        val parts = id.split(':')
        if (parts.size < 3) return null
        val ep = parts.last().toIntOrNull() ?: return null
        if (ep < 0) return null
        return parts.dropLast(1).joinToString(":") + ":" + (ep + 1)
    }

    /** Loads the next episode's links in the background so pressing "next" is instant (results land in the stream cache). */
    private fun prefetchNextEpisode(type: String, id: String) {
        val next = nextEpisodeId(type, id) ?: return
        if (prefetchedNext.size > 500) prefetchedNext.clear()
        if (!prefetchedNext.add(next)) return
        streamSearchScope.launch {
            // Only once the current episode's search is completely done (the extensions keep adding links in the
            // background after the first answer): the two never run at the same time. At most 4 minutes of waiting.
            delay(1_500)
            var waited = 0L
            while (waited < 240_000L && searchStatus["$type:$id"]?.finished == false) { delay(2_000); waited += 2_000 }
            runCatching {
                ServerState.info("[Streams] Preparing the next episode in the background: $next" + if (waited > 0) " (after the current episode's search finished)" else "")
                withContext(pluginDispatcher) { buildStreams(type, next) }
            }.onFailure { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
        }
    }

    private suspend fun respondStyledSub(call: ApplicationCall, srt: Boolean = false) {
        val u = call.request.queryParameters["u"]
        val ms = call.request.queryParameters["ms"]?.toIntOrNull()?.coerceIn(-60_000, 60_000) ?: 0
        val who = call.request.headers[HttpHeaders.UserAgent].orEmpty().take(60)
        val what = "${if (srt) "SRT" else "SSA"} ${call.parameters["name"]} (${call.request.httpMethod.value}, $who)"
        if (u.isNullOrBlank() || !u.startsWith("http", true)) return call.respond(HttpStatusCode.BadRequest)
        val text = withContext(Dispatchers.IO) { SubtitleTools.fetchStyled(u, ms, srt) }
        if (text == null) {
            ServerState.warn("[Subtitle] $what could not be made (source unreachable or expired): ${u.take(120)}")
            call.respondRedirect(u)
        } else {
            ServerState.info("[Subtitle] $what served, ${text.length / 1024} KB")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(text, ContentType.parse(if (srt) "application/x-subrip; charset=utf-8" else "text/x-ssa; charset=utf-8"))
        }
    }

    /** Anime titles: the anime type or an anime id scheme (kitsu / MAL / AniList / AniDB). */
    private fun isAnime(type: String, id: String): Boolean =
        type == "anime" || id.substringBefore(':').lowercase() in setOf("kitsu", "mal", "anilist", "anidb")

    /** Where clients reach this bridge (for the links the media server hands out). */
    private fun mediaBase(): String =
        ServerState.streamBaseUrl ?: ServerState.publicBaseUrl.ifBlank { "http://127.0.0.1:${ServerState.serverPort}" }

    private val SUB_TAG = Regex("\\[sub(?:bed)?\\]", RegexOption.IGNORE_CASE)
    private val DUB_TAG = Regex("\\[dub(?:bed)?\\]", RegexOption.IGNORE_CASE)

    /** "sub" / "dub" when the extension labelled the link (the bridge tags anime variants "[Sub]" / "[Dub]"). */
    private fun audioKind(s: StremioStream): String? {
        val text = s.title.orEmpty() + " " + s.name.orEmpty() + " " + s.info?.linkName.orEmpty()
        return when {
            DUB_TAG.containsMatchIn(text) -> "dub"
            SUB_TAG.containsMatchIn(text) -> "sub"
            else -> null
        }
    }

    /**
     * Anime: links with the subtitles burned into the video (hardsub) go on top - they play in every
     * player with no subtitle setup. Order inside each group is unchanged (best quality first). Lists
     * without any hardsub link are left exactly as they are.
     */
    fun hardsubFirst(streams: List<StremioStream>): List<StremioStream> {
        val hard = streams.filter { com.cncverse.stremiobridge.format.StreamVariables.subTypeOf(it) == "Hardsub" }
        if (hard.isEmpty() || hard.size == streams.size) return streams
        val dubs = streams.filter { audioKind(it) == "dub" }
        // A dub list keeps the dub on top when the dub is what was asked for
        val keep = if (streamPrefs.animeAudio == "dub" && dubs.isNotEmpty()) return streams else hard
        return keep + streams.filter { it !in keep }
    }

    /** Anime: the preferred audio (sub or dub) first; optionally the other kind is hidden. */
    fun applyAnimeAudio(streams: List<StremioStream>, p: StreamPrefs): List<StremioStream> {
        if (p.animeAudio == "all") return streams
        val preferred = streams.filter { audioKind(it) == p.animeAudio }
        if (preferred.isEmpty()) return streams // nothing of that kind: never leave the list empty
        val unlabelled = streams.filter { audioKind(it) == null }
        val other = streams.filter { val k = audioKind(it); k != null && k != p.animeAudio }
        return if (p.animeAudioOnly) preferred + unlabelled else preferred + unlabelled + other
    }

    /** Drops unpaired UTF-16 surrogates (cut-off emoji) that make JSON/UTF-8 encoding throw MalformedInputException. */
    fun cleanText(t: String?): String? {
        if (t == null) return null
        var bad = false
        for (i in t.indices) {
            val c = t[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 >= t.length || !Character.isLowSurrogate(t[i + 1])) { bad = true; break } }
            else if (Character.isLowSurrogate(c)) { if (i == 0 || !Character.isHighSurrogate(t[i - 1])) { bad = true; break } }
        }
        if (!bad) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (Character.isHighSurrogate(c) && i + 1 < t.length && Character.isLowSurrogate(t[i + 1])) { sb.append(c).append(t[i + 1]); i += 2; continue }
            if (!Character.isSurrogate(c)) sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun sanitizeStream(s: StremioStream): StremioStream = s.copy(
        name = cleanText(s.name), title = cleanText(s.title), url = cleanText(s.url),
        externalUrl = cleanText(s.externalUrl),
        subtitles = s.subtitles?.map { it.copy(id = cleanText(it.id) ?: "", lang = cleanText(it.lang) ?: "", url = cleanText(it.url) ?: "") },
        behaviorHints = s.behaviorHints?.let { h ->
            h.copy(
                bingeGroup = cleanText(h.bingeGroup),
                proxyHeaders = h.proxyHeaders?.let { ph -> ph.copy(request = ph.request?.entries?.associate { (k, v) -> (cleanText(k) ?: "") to (cleanText(v) ?: "") }) },
            )
        },
    )

    private val PIXELDRAIN = Regex("^https?://(?:www\\.)?pixeldrain\\.(?:com|dev|net)/(?:api/file|u)/([A-Za-z0-9_-]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

    /** pixeldrain.com / .dev / .net file links -> https://cdn.pixeldrain.eu.cc/<id> (no hotlink / download limits). */
    fun pixeldrainBypass(s: StremioStream, p: StreamPrefs): StremioStream {
        if (!p.pixeldrainBypass) return s
        val id = s.url?.let { PIXELDRAIN.matchEntire(it) }?.groupValues?.get(1) ?: return s
        return s.copy(url = "https://cdn.pixeldrain.eu.cc/$id")
    }

    private val lastRefresh = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun refreshStream(type: String, id: String, found: Int, shown: Int): StremioStream {
        val st = searchStatus["$type:$id"]
        val perSource = st?.counts?.entries?.filter { it.value > 0 }?.sortedByDescending { it.value }
        val total = maxOf(found, st?.counts?.values?.sum() ?: 0)
        val head = "🔄 FORCE REFRESH • $total result(s) found" + (if (shown != total) ", $shown shown" else "")
        val state = when {
            st == null -> "📦 Saved results (no live search state): reload if something is missing"
            st.finished -> "✅ Search complete: all ${st.total} source(s) answered"
            else -> "⏳ Search still running: ${st.done.get()}/${st.total} source(s) done - reload the list in a few seconds"
        }
        val sources = when {
            st == null || perSource == null -> ""
            perSource.isEmpty() -> "\nNo source has found anything yet"
            else -> "\n" + perSource.joinToString(" • ") { "${it.key}: ${it.value}" } +
                (st.counts.size - perSource.size).takeIf { it > 0 }?.let { " • $it found nothing" }.orEmpty()
        }
        return StremioStream(
            name = head,
            title = "$state$sources\n⚡ Click here to clear the saved results of this title and search again (not a video)",
            url = mediaBase() + "/refresh/" + java.net.URLEncoder.encode(type, "UTF-8") + "/" + java.net.URLEncoder.encode(id, "UTF-8"),
        )
    }

    /** Removes everything saved for this title (lists, extension searches) and starts a complete new search that fills the cache again. */
    private suspend fun ApplicationCall.respondRefresh() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id = parameters["id"] ?: return respond(HttpStatusCode.BadRequest)
        // players ask the same address more than once (probe, retry): one refresh per title in 20 s
        val key = "$type:$id"
        val nowMs = System.currentTimeMillis()
        if (nowMs - (lastRefresh[key] ?: 0L) < 20_000L) return respondText("Refresh already started", ContentType.Text.Plain)
        if (lastRefresh.size > 200) lastRefresh.clear()
        lastRefresh[key] = nowMs
        val cache = com.cncverse.stremiobridge.cache.StreamCacheManager
        val removed = cache.invalidatePrefix("stream:generic:$type:$id:") +
            (StremioIds.decode(id)?.let { (pluginKey, dataUrl) -> cache.invalidatePrefix("stream:direct:$pluginKey:$dataUrl") } ?: 0)
        SearchLoadCache.clear()
        ServerState.info("[Request] refresh $type/$id: $removed saved list(s) removed, searching again")
        streamSearchScope.launch(pluginDispatcher) { runCatching { buildStreams(type, id) } }
        val safe = id.replace("<", "").replace(">", "").replace("&", "")
        respondText(
            "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Refreshing</title><style>body{font-family:sans-serif;background:#000;color:#eee;padding:24px;max-width:480px;margin:auto}</style></head><body>" +
                "<h2>🔄 Refreshing</h2><p>The saved results for <b>$safe</b> were removed and a complete new search started.</p>" +
                "<p>Go back and open the title again in about a minute: the new results are saved for the next time.</p></body></html>",
            ContentType.Text.Html,
        )
    }

    private val SUPPORT_STREAM = StremioStream(
        name = "✨ | support the project!",
        title = "Click to donate this project ❤️\n(you can hide this in the app settings)",
        externalUrl = "https://cncverse.pages.dev",
    )

    /** Paths of the bridge's own relay endpoints (links that point back at this server). */
    private val RELAY_PATH = Regex("^https?://([^/:]+)(?::[0-9]+)?(/(proxy/|decrypt|init_decrypt).*)$")

    /**
     * Points the bridge's own relay links at [ServerState.streamBaseUrl] (the server IP) - also
     * links cached earlier under the domain. Extension links to other sites are untouched.
     */
    private fun withStreamBase(s: StremioStream): StremioStream {
        val base = ServerState.streamBaseUrl ?: return s
        fun fix(u: String?): String? {
            val m = u?.let { RELAY_PATH.matchEntire(it) } ?: return u
            // Only links back to this bridge; another site's own /proxy/ path is left alone
            if (u == null || !ServerState.isOwnUrl(u)) return u
            return base + m.groupValues[2]
        }
        val subs = s.subtitles?.map { it.copy(url = fix(it.url) ?: it.url) }
        return s.copy(url = fix(s.url), subtitles = subs)
    }

    private suspend fun ApplicationCall.respondSubtitles() {
        val type = parameters["type"] ?: return respond(HttpStatusCode.BadRequest)
        val id   = parameters["id"]   ?: return respond(HttpStatusCode.BadRequest)

        if (streamPrefs.hideSubtitles) {
            respond(StremioSubtitleResponse(emptyList()))
            return
        }
        val streams = withContext(pluginDispatcher) { buildStreams(type, id) }
        val subtitles = SubtitleTools.process(streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }, streamPrefs, mediaBase(), isAnime(type, id))

        respond(StremioSubtitleResponse(subtitles))
    }

    /**
     * Identifiers that can match this API in a profile record.
     * Includes canonical IDs (always unique per plugin/API) and the display-name slug
     * ONLY if unambiguous (unique across all loaded extensions), preventing cross-repo
     * collisions when two repos ship an extension with the same name.
     */
    private fun profileMatchIds(api: MainApiWrapper): List<String> {
        val ids = mutableListOf(api.internalName)
        // A plugin with several sources (VegaMovies plugin = VegaMovies + Rogmovies) gets one
        // card per source, and its plugin id often equals one source's card id. Matching
        // every source by the plugin id made deselecting "VegaMovies" also hide Rogmovies —
        // so only single-source plugins are matched by their plugin id.
        if ((apisPerPlugin()[api.pluginInternalName] ?: 1) <= 1) ids.add(api.pluginInternalName)
        unambiguousSlug(api)?.let { ids.add(it) }
        return ids.distinct()
    }

    /** pluginInternalName -> number of loaded APIs; rebuilt only when [loadedApis] changes. */
    @Volatile private var apisPerPluginCache: Pair<Long, Map<String, Int>>? = null

    private fun apisPerPlugin(): Map<String, Int> {
        val version = apiList.version
        apisPerPluginCache?.let { (v, counts) -> if (v == version) return counts }
        val counts = HashMap<String, Int>()
        for (api in apiList) counts[api.pluginInternalName] = (counts[api.pluginInternalName] ?: 0) + 1
        apisPerPluginCache = version to counts
        return counts
    }



    // 📺 Manifest builder 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺

    /**
     * Converts a plugin display-name to a short alphanumeric slug for use in catalog IDs.
     * Normalises special characters so JIO TV (IND) → JIOTVIND and
     * JIO TV+ (IND) → JIOTVPlusIND, giving each variant a unique catalog ID
     * even when two APIs share the same [internalName].
     */
    private fun nameSlug(name: String): String {
        slugMemo[name]?.let { return it }
        val slug = name
            .replace("+", "Plus")
            .replace("&", "And")
            .replace(NON_ALNUM, "")
            .take(48)
            .ifBlank { "unknown" }
        if (slugMemo.size > 10_000) slugMemo.clear()
        slugMemo[name] = slug
        return slug
    }

    /** Public alias used by WebAdmin to build profile-cleanup ID sets. */
    internal fun publicNameSlug(name: String) = nameSlug(name)

    /**
     * Normalises raw.githubusercontent.com URLs so that variants with and
     * without /refs/heads/ or /refs/tags/ compare as equal.  Used to
     * canonicalise the repoUrl reported by installedPlugins against the
     * canonical URL stored in RepoState.repos, preventing "phantom" repo tabs
     * in the user-facing Extensions/Profile UI.
     */
    private fun normalizeGhUrl(url: String): String =
        url.replace("/refs/heads/", "/").replace("/refs/tags/", "/")

    fun defaultCatalogDefsForApi(api: MainApiWrapper): List<StremioCatalogDef> {
        val slug = apiKey(api)
        return api.supportedTypes
            .map { cs3TvTypeToStremio(it) }
            .distinct()
            .map { stremioType ->
                StremioCatalogDef(
                    type = stremioType,
                    id   = "cnc_${slug}_$stremioType",
                    name = "${api.name} ($stremioType)",
                    extra = listOf(ExtraEntry("search"), ExtraEntry("skip"))
                )
            }
    }

    suspend fun fetchCatalogDefsForApi(api: MainApiWrapper): List<StremioCatalogDef> {
        val sections = try {
            withTimeoutOrNull(25_000) {
                api.getMainPageSections()
            } ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
        val slug = apiKey(api)

        return api.supportedTypes
            .map { cs3TvTypeToStremio(it) }
            .distinct()
            .map { stremioType ->
                val extra = mutableListOf<ExtraEntry>()
                if (sections.isNotEmpty() && (sections.size > 1 || sections.first().isNotBlank())) {
                    extra.add(ExtraEntry(name = "genre", options = sections))
                }
                extra.add(ExtraEntry("search"))
                extra.add(ExtraEntry("skip"))

                StremioCatalogDef(
                    type = stremioType,
                    id   = "cnc_${slug}_$stremioType",
                    name = "${api.name} ($stremioType)",
                    extra = extra
                )
            }
    }

    /**
     * Initializes fast baseline in-memory catalog definitions for all loaded APIs
     * if not already present, ensuring cold-start manifest requests return instantly.
     */
    fun initFastCatalogs() {
        val apis = loadedApis.toList()
        for (api in apis) {
            if (!providerCatalogCache.containsKey(api.internalName)) {
                providerCatalogCache[api.internalName] = defaultCatalogDefsForApi(api)
            }
        }
        val currentNames = apis.map { it.internalName }.toSet()
        providerCatalogCache.keys.retainAll(currentNames)
    }

    private const val HOME_REFRESH_INTERVAL_MS = 30 * 60_000L

    /** Reloads every extension's home page (genres + items) every 30 minutes while the server runs. */
    fun startPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = manifestRefreshScope.launch {
            while (isActive) {
                delay(HOME_REFRESH_INTERVAL_MS)
                // the refresh asks 30+ extensions' sites at once: while a video is being streamed it waits (at most 15 min) so it does not compete for the connection
                var waited = 0L
                while (waited < 15 * 60_000L && MediaServer.isStreaming()) { delay(15_000L); waited += 15_000L }
                runCatching { refreshManifestAndHomepages() }
                    .onFailure { ServerState.warn("Periodic home page refresh failed: ${it.message}") }
            }
        }
    }

    /** Completed once the home pages' genres (catalog definitions) have been loaded the first time. */
    private val firstCatalogDefs = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * The first manifest a player fetches should already list each extension's
     * home-page sections as genres: start the home-page load if nobody has yet
     * and wait for the genres (bounded, so the player never hangs).
     */
    private suspend fun awaitFirstCatalogDefs() {
        if (firstCatalogDefs.isCompleted || loadedApis.isEmpty()) return
        if (!isRefreshing.get()) manifestRefreshScope.launch { refreshManifestAndHomepages() }
        withTimeoutOrNull(30_000) { firstCatalogDefs.await() }
    }

    fun stopPeriodicRefreshJob() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Non-blocking stale-while-revalidate background refresh:
     * 1. Clears provider dynamic section caches.
     * 2. Fetches fresh catalog definitions (sections/genres) with bounded concurrency (Semaphore 5).
     * 3. Pre-warms home page catalog items into a staging map.
     * 4. While this runs, all manifest and catalog requests serve the existing (old) cached data.
     * 5. Atomically publishes the new data to live caches only when everything is fetched.
     */
    /** Scope of the in-flight background refresh, so a plugin reload can cancel it. */
    @Volatile private var refreshWork: kotlinx.coroutines.Job? = null

    /**
     * Cancels an in-flight background refresh. Called before plugins are
     * unloaded: the refresh holds a snapshot of the old APIs (keeping the old
     * plugin classloaders alive) and would keep calling unloaded plugins.
     */
    fun cancelBackgroundRefresh() {
        refreshWork?.cancel()
    }

    suspend fun refreshManifestAndHomepages() {
        if (!isRefreshing.compareAndSet(false, true)) {
            ServerState.info("Manifest refresh already in progress, skipping duplicate call")
            return
        }
        try {
            coroutineScope {
                refreshWork = coroutineContext[kotlinx.coroutines.Job]
                refreshManifestAndHomepagesInner()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Our own scope was cancelled by a plugin reload — the caller keeps running
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            ServerState.info("Background refresh cancelled (plugins reloaded)")
        } catch (e: Throwable) {
            ServerState.warn("Manifest refresh error: ${e.message}")
        } finally {
            refreshWork = null
            isRefreshing.set(false)
        }
    }

    private suspend fun refreshManifestAndHomepagesInner() {
        run {
            ServerState.info("🔄 Refreshing home pages & provider manifest catalogs in background…")
            val apis = loadedApis.toList()
            if (apis.isEmpty()) return

            // 1. Clear dynamic sections cache on providers so fresh sections are fetched
            apis.forEach { runCatching { it.clearCache() } }

            val newProviderCatalogs = ConcurrentHashMap<String, List<StremioCatalogDef>>()
            val newHomePageCache = ConcurrentHashMap<String, List<StremioMeta>>()

            // 2. Fetch fresh catalog definitions (sections/genres) with bounded concurrency.
            // Kept low: this is background work and must leave room for live requests.
            val sem = Semaphore(3)
            coroutineScope {
                apis.map { api ->
                    async(pluginDispatcher) {
                        sem.withPermit {
                            val defs = try {
                                fetchCatalogDefsForApi(api)
                            } catch (e: Throwable) {
                                providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
                            }
                            newProviderCatalogs[api.internalName] = defs
                        }
                    }
                }.awaitAll()
            }
            // Genres are known now: publish them right away (the manifest waits for this),
            // the home-page items follow below
            providerCatalogCache.putAll(newProviderCatalogs)
            firstCatalogDefs.complete(Unit)

            // 3. Pre-warm home page catalogs into newHomePageCache
            val catalogsToPrewarm = newProviderCatalogs.values.flatten().distinctBy { it.id }
            ServerState.info("🔥 Pre-warming ${catalogsToPrewarm.size} fresh home page(s)…")
            // Home pages are background work: while someone is waiting for a stream search they hold back (at most 3
            // minutes in all per refresh), so the extensions' sites and the device's connections go to the search first
            val yielded = java.util.concurrent.atomic.AtomicLong(0)
            coroutineScope {
                catalogsToPrewarm.map { cat ->
                    async(pluginDispatcher) {
                        sem.withPermit {
                            while (yielded.get() < 180_000L && searchStatus.values.any { !it.finished }) {
                                delay(1_000L); yielded.addAndGet(1_000L)
                            }
                            runCatching {
                                val metas = withTimeoutOrNull(15_000) {
                                    fetchCatalogItemsDirect(cat.type, cat.id, null, 0, null)
                                }
                                if (!metas.isNullOrEmpty()) {
                                    newHomePageCache["${cat.type}:${cat.id}:null"] = metas
                                    ServerState.info("🔥 Pre-warmed: ${cat.name}")
                                }
                            }.onFailure { e ->
                                ServerState.warn("🔥 Pre-warm failed for ${cat.name}: ${e.message?.take(80)}")
                            }
                        }
                    }
                }.awaitAll()
            }

            // 4. ATOMIC UPDATE: Stale data was served during the entire fetch above.
            // Now that EVERYTHING is fetched, publish the new data to the live caches!
            providerCatalogCache.putAll(newProviderCatalogs)
            providerCatalogCache.keys.retainAll(apis.map { it.internalName }.toSet())
            homePageCatalogCache.putAll(newHomePageCache)

            ServerState.info("✅ Fresh home page & manifest refresh complete (${newProviderCatalogs.size} providers, ${newHomePageCache.size} home pages)")
        }
    }

    /**
     * Pre-warms every plugin's home page by refreshing manifests and home pages in the background.
     */
    suspend fun preWarmHomepages() {
        refreshManifestAndHomepages()
    }

    /**
     * Builds the Stremio manifest from [providerCatalogCache]: every enabled extension's
     * catalogs with its home-page sections as genres, minus catalogs hidden in the app.
     */
    suspend fun buildManifest(): StremioManifest {
        val types = listOf("movie", "series", "other", "tv")
        val prefs = streamPrefs

        val catalogs = if (ServerState.disableCatalogsGlobally) {
            emptyList()
        } else {
            awaitFirstCatalogDefs()
            loadedApis.filter { !isGloballyDisabled(it) }.flatMap { api ->
                providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
            }.distinctBy { it.id }
                .filter { "${it.type}/${it.id}" !in prefs.disabledCatalogs }
        }
        val hasCatalogs = catalogs.isNotEmpty()
        return buildManifestFrom(withCombinedCatalogs(catalogs), hasCatalogs, types)
    }

    /**
     * The combined catalogs: "All TV channels (A-Z)" goes where the first TV catalog was and "Short dramas" where the first
     * short-drama catalog was. While combining is on, the separate catalogs they are made of are not listed.
     */
    private fun withCombinedCatalogs(catalogs: List<StremioCatalogDef>): List<StremioCatalogDef> {
        val p = streamPrefs
        var out = catalogs
        val dramaIds = dramaSources().map { it.second.id }.toSet()
        if (p.combineShortDrama && dramaIds.isNotEmpty()) {
            val first = out.indexOfFirst { it.id in dramaIds }
            if (first >= 0) {
                val rest = out.filterIndexed { i, d -> i < first || d.id !in dramaIds }
                val at = first
                out = if ("other/$ALL_DRAMA_ID" in p.disabledCatalogs) rest else rest.take(at) + allDramaDef() + rest.drop(at)
            }
        }
        val tvIds = tvSources().map { it.id }.toSet()
        val firstTv = out.indexOfFirst { it.type == "tv" && (it.id in tvIds || !p.combineTv) }
        if (firstTv >= 0 || (tvIds.isNotEmpty() && out.any { it.type == "tv" })) {
            val at = if (firstTv >= 0) firstTv else out.indexOfFirst { it.type == "tv" }
            val withoutTv = if (p.combineTv) out.filterIndexed { i, d -> i < at || d.type != "tv" || d.id !in tvIds } else out
            out = if ("tv/$ALL_TV_ID" in p.disabledCatalogs || tvIds.isEmpty()) withoutTv else withoutTv.take(at) + allTvDef() + withoutTv.drop(at)
        }
        return out
    }

    // ── Combined short-drama catalog ──────────────────────────────────────────

    private const val ALL_DRAMA_ID = "cnc_all_shortdrama"
    private val DRAMA_WORDS = Regex(
        "short[ _-]?dramas?|vertical|\\breels?\\b|micro[ _-]?dramas?|mini[ _-]?dramas?|dramabox|shortmax|reelshort|goodshort|netshort|flextv|dramawave|\\bshorts\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Short-drama extension: marked by hand, or its name / description says so (vertical, reels, short drama...). */
    fun isShortDramaApi(api: MainApiWrapper): Boolean {
        val key = api.pluginInternalName
        val p = streamPrefs
        if (key in p.shortDramaOff) return false
        if (key in p.shortDramaOn) return true
        val desc = runCatching {
            com.cncverse.stremiobridge.state.RepoState.installedPlugins.value.firstOrNull { it.internalName == key }?.description
        }.getOrNull().orEmpty()
        return DRAMA_WORDS.containsMatchIn(api.name + " " + api.internalName + " " + desc)
    }

    /** TV catalogs that go into the combined TV catalog: the ones switched on, of extensions not left out by hand. */
    private fun tvSources(): List<StremioCatalogDef> =
        loadedApis.filter { !isGloballyDisabled(it) && it.pluginInternalName !in streamPrefs.tvOff }.flatMap { api ->
            providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
        }.filter { it.type == "tv" && it.id != ALL_TV_ID && "tv/${it.id}" !in streamPrefs.disabledCatalogs }.distinctBy { it.id }

    /** Extensions with TV channels (internal name -> name, in the combined catalog or not), for the app's settings. */
    fun tvChoices(): List<Triple<String, String, Boolean>> =
        loadedApis.filter { !isGloballyDisabled(it) && it.supportedTypes.any { t -> cs3TvTypeToStremio(t) == "tv" } }
            .distinctBy { it.pluginInternalName }
            .map { Triple(it.pluginInternalName, it.name, it.pluginInternalName !in streamPrefs.tvOff) }
            .sortedBy { it.second.lowercase() }

    /** Extensions with a non-TV catalog (internal name -> name, short-drama ones marked), for the app's settings. */
    fun shortDramaChoices(): List<Triple<String, String, Boolean>> =
        loadedApis.filter { !isGloballyDisabled(it) && it.supportedTypes.any { t -> cs3TvTypeToStremio(t) == "other" } }
            .distinctBy { it.pluginInternalName }
            .map { Triple(it.pluginInternalName, it.name, isShortDramaApi(it)) }
            .sortedBy { it.second.lowercase() }

    /** (extension, its catalog) of every short-drama catalog that is switched on. */
    private fun dramaSources(): List<Pair<MainApiWrapper, StremioCatalogDef>> =
        loadedApis.filter { !isGloballyDisabled(it) && isShortDramaApi(it) }.flatMap { api ->
            (providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api))
                .filter { it.type == "other" && "${it.type}/${it.id}" !in streamPrefs.disabledCatalogs }
                .map { api to it }
        }.distinctBy { it.second.id }

    private fun allDramaDef(): StremioCatalogDef {
        val names = dramaSources().map { it.first.name }.distinct()
        return StremioCatalogDef(
            type = "other", id = ALL_DRAMA_ID, name = "🎬 Short dramas",
            extra = listOfNotNull(
                // a genre per extension: one source on its own
                names.takeIf { it.size > 1 }?.let { ExtraEntry(name = "genre", options = it) },
                ExtraEntry("search"), ExtraEntry("skip"),
            ),
        )
    }

    /** "<sources>|<search>|<skip>" -> where each source's next page starts (-1 = that source has no more). */
    private val dramaNext = ConcurrentHashMap<String, List<Int>>()

    /**
     * The short-drama extensions' catalogs as ONE list: their pages are fetched at the same time and taken in turns
     * (one from each), so every extension shows up near the top. A genre picks one extension on its own.
     */
    private suspend fun buildAllDrama(search: String?, skip: Int, genre: String?): List<StremioMeta> {
        val srcs = dramaSources()
        if (srcs.isEmpty()) return emptyList()
        if (!genre.isNullOrBlank()) {
            val one = srcs.firstOrNull { it.first.name == genre } ?: return emptyList()
            return buildCatalog(one.second.type, one.second.id, search, skip, null)
        }
        val sig = srcs.joinToString(",") { it.second.id } + "|" + search.orEmpty()
        val starts = if (skip == 0) List(srcs.size) { 0 } else dramaNext["$sig|$skip"] ?: List(srcs.size) { skip / srcs.size }
        val pages = coroutineScope {
            srcs.mapIndexed { i, (_, d) ->
                async {
                    if (starts[i] < 0) emptyList()
                    else withTimeoutOrNull(25_000L) { runCatching { buildCatalog(d.type, d.id, search, starts[i], null) }.getOrNull() } ?: emptyList()
                }
            }.awaitAll()
        }
        val out = ArrayList<StremioMeta>()
        val seen = HashSet<String>()
        val longest = pages.maxOfOrNull { it.size } ?: 0
        for (k in 0 until longest) for (pg in pages) pg.getOrNull(k)?.let { if (seen.add(it.id)) out.add(it) }
        if (out.isNotEmpty()) {
            if (dramaNext.size > 2000) dramaNext.clear()
            dramaNext["$sig|${skip + out.size}"] = starts.mapIndexed { i, s -> if (s < 0 || pages[i].isEmpty()) -1 else s + pages[i].size }
        }
        return out
    }

    private fun allTvDef() = StremioCatalogDef(
        type = "tv", id = ALL_TV_ID, name = "📺 All TV channels (A-Z)",
        extra = listOf(ExtraEntry("search"), ExtraEntry("skip")),
    )

    private fun buildManifestFrom(catalogs: List<StremioCatalogDef>, hasCatalogs: Boolean, types: List<String>): StremioManifest {
        // Changes whenever the catalog list does, so players drop their cached manifest
        val configHash = (catalogs.joinToString(",") { it.id + it.extra.size }.hashCode() xor catalogs.size)
            .let { kotlin.math.abs(it) % 10000 }

        return StremioManifest(
            id          = "com.cncverse.stremiobridge" + (Profiles.current.get()?.let { "." + it.id } ?: ""),
            version     = "1.0.$configHash",
            name        = "CNCVerse Bridge" + (Profiles.current.get()?.let { " - " + it.name } ?: ""),
            description = "Cloudstream plugin bridge for Stremio \u2014 Developed by NivinCNC",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Bridge/refs/heads/main/logo.png",
            types       = types,
            resources   = if (hasCatalogs) listOf("catalog", "meta", "stream", "subtitles") else listOf("meta", "stream", "subtitles"),
            catalogs    = catalogs,
            behaviorHints = BehaviorHints(configurable = false),
        )
    }

    /** Every catalog an enabled extension offers (`<type>/<id>` \u2192 name), for the app's catalog switches. */
    fun allCatalogs(): List<Pair<String, String>> =
        loadedApis.filter { !isGloballyDisabled(it) }.flatMap { api ->
            providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)
        }.distinctBy { it.id }.let { defs ->
            // the combined catalogs are listed in front of the first catalog they are made of, so they can be switched off like the others
            val i = defs.indexOfFirst { it.type == "tv" }
            val withTv = if (i < 0) defs else defs.take(i) + allTvDef() + defs.drop(i)
            val dramaIds = loadedApis.filter { !isGloballyDisabled(it) && isShortDramaApi(it) }
                .flatMap { api -> (providerCatalogCache[api.internalName] ?: defaultCatalogDefsForApi(api)).filter { it.type == "other" }.map { it.id } }.toSet()
            val j = withTv.indexOfFirst { it.id in dramaIds }
            if (j < 0) withTv else withTv.take(j) + StremioCatalogDef(type = "other", id = ALL_DRAMA_ID, name = "🎬 Short dramas") + withTv.drop(j)
        }.map { "${it.type}/${it.id}" to it.name }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun fetchCatalogItemsDirect(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)

        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { apiKey(it) == nameSlugFromId }
            ?: loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }          // old-format compat
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }    // prefix fallback
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                // Cached per extension + query (shared with the stream lookup's searches)
                val results = SearchLoadCache.getSearch(api.internalName, search)
                    ?: api.search(search).also { SearchLoadCache.putSearch(api.internalName, search, it) }
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }.also { rememberTitles(it) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(nameSlug(api.name), type) }.also { rememberTitles(it) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Stremio item id → display title for items we served in catalogs/meta.
     * Stream requests only carry the id, so this is how streams opened from our
     * own catalogs (live events, provider home pages) learn their title for the
     * stream formatter's metadata.title. Bounded LRU.
     */
    private val metaTitles = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 20_000
    }
    private val LEADING_SYMBOLS = Regex("^[^\\p{L}\\p{N}]+")

    private fun rememberTitles(metas: List<StremioMeta>) {
        synchronized(metaTitles) { metas.forEach { m -> if (m.name.isNotBlank()) metaTitles[m.id] = m.name } }
    }

    /** Remembered title for [id], minus leading status emoji like "🔴 ". */
    private fun titleForId(id: String): String? =
        synchronized(metaTitles) { metaTitles[id] }?.replace(LEADING_SYMBOLS, "")?.trim()?.takeIf { it.isNotEmpty() }

    private fun withMetadataTitle(streams: List<StremioStream>, title: String?): List<StremioStream> {
        if (title == null) return streams
        return streams.map { st ->
            val info = st.info ?: com.cncverse.stremiobridge.model.StreamInfo()
            if (info.metadataTitle != null) st else st.copy(info = info.copy(metadataTitle = title))
        }
    }

    private val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    // api.tmdb.org answers in ~0.4 s from the server; api.themoviedb.org (same API) gets its
    // connections reset about half the time (hostname filtered on Indian networks). Both are
    // asked at once and the first answer wins, so a hiccup on one costs nothing. A short
    // per-host timeout was tried instead: under load the server itself was slower than that
    // and every lookup failed.
    private val TMDB_HOSTS = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private const val TMDB_TIMEOUT_MS = 8_000L

    /** TMDB/IMDb id → (title, year). Bounded LRU: one entry per title ever requested would grow forever. */
    private val genericMediaCache: MutableMap<String, Pair<String, Int?>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, Int?>>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Int?>>?) = size > 5_000
        }
    )

    /** TMDB answer for the website version ([endpointPathAndQuery] without the key). */
    suspend fun tmdb(endpointPathAndQuery: String): JsonObject? = fetchTmdbJson(endpointPathAndQuery)

    /** Every address stream links can start with, so the website can turn them into its own address. */
    fun webBases(): List<String> = listOfNotNull(
        ServerState.streamBaseUrl, ServerState.publicBaseUrl.ifBlank { null },
        "http://127.0.0.1:${ServerState.serverPort}", "http://localhost:${ServerState.serverPort}",
    ).map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    private suspend fun fetchTmdbJson(endpointPathAndQuery: String): JsonObject? {
        val cleanPath = endpointPathAndQuery.trimStart('/')
        val delimiter = if (cleanPath.contains("?")) "&" else "?"
        val answers = kotlinx.coroutines.channels.Channel<JsonObject?>(TMDB_HOSTS.size)
        return coroutineScope {
            val tries = TMDB_HOSTS.map { host ->
                launch {
                    val url = "$host/$cleanPath${delimiter}api_key=$TMDB_API_KEY"
                    ServerState.debug("Fetching TMDB: $url")
                    val json = try {
                        serverJson.parseToJsonElement(withContext(Dispatchers.IO) { com.cncverse.stremiobridge.network.DohHttp.text(url) }).jsonObject
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ServerState.debug("TMDB error on $host: ${e.message?.take(80)}")
                        null
                    }
                    answers.send(json)
                }
            }
            // First successful answer wins; a failed host just leaves it to the other one
            val result = withTimeoutOrNull(TMDB_TIMEOUT_MS) {
                var found: JsonObject? = null
                repeat(TMDB_HOSTS.size) { if (found == null) found = answers.receive() }
                found
            }
            tries.forEach { it.cancel() }
            if (result == null) ServerState.warn("TMDB lookup failed on all hosts: $cleanPath")
            result
        }
    }

    /** "scheme:key:season:episode" -> running time in seconds (0 = not known). */
    private val runtimeCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Running time of the movie / episode behind [id]: TMDB (movie, episode, or the show's usual episode length), Kitsu or AniList for anime ids. */
    private suspend fun runtimeSec(type: String, id: String): Int? {
        val ext = ExternalIds.parse(id) ?: return null
        val key = "${ext.scheme}:${ext.key}:${ext.season}:${ext.episode}"
        runtimeCache[key]?.let { return it.takeIf { s -> s > 0 } }
        val minutes: Int? = try {
            when (ext.scheme) {
                "imdb", "tvdb", "tmdb" -> {
                    val isTv = type == "series" || type == "anime" || ext.episode != null
                    val tmdbId = if (ext.scheme == "tmdb") ext.key else
                        fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                            ?.get(if (isTv) "tv_results" else "movie_results")
                            ?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                            ?.get("id")?.jsonPrimitive?.contentOrNull
                    when {
                        tmdbId == null -> null
                        !isTv -> fetchTmdbJson("movie/$tmdbId")?.get("runtime")?.jsonPrimitive?.intOrNull
                        else -> {
                            val s = ext.season; val e = ext.episode
                            (if (s != null && e != null) fetchTmdbJson("tv/$tmdbId/season/$s/episode/$e")?.get("runtime")?.jsonPrimitive?.intOrNull else null)
                                ?: fetchTmdbJson("tv/$tmdbId")?.let { j ->
                                    (j["episode_run_time"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
                                        ?: (j["last_episode_to_air"] as? kotlinx.serialization.json.JsonObject)?.get("runtime")?.jsonPrimitive?.intOrNull
                                }
                        }
                    }
                }
                "kitsu" -> getJson("https://kitsu.io/api/edge/anime/${ext.key}", accept = "application/vnd.api+json")
                    ?.get("data")?.jsonObject?.get("attributes")?.jsonObject?.get("episodeLength")?.jsonPrimitive?.intOrNull
                "anilist", "mal" -> {
                    val field = if (ext.scheme == "mal") "idMal" else "id"
                    val q = "query{Media(" + field + ":" + ext.key + ",type:ANIME){duration}}"
                    val body = kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive(q)) }.toString()
                    idApiCall(
                        okhttp3.Request.Builder().url("https://graphql.anilist.co").header("Accept", "application/json")
                            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body)).build()
                    )?.let { txt ->
                        serverJson.parseToJsonElement(txt).jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject?.get("duration")?.jsonPrimitive?.intOrNull
                    }
                }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
        val sec = (minutes ?: 0).coerceIn(0, 600) * 60
        if (runtimeCache.size > 5_000) runtimeCache.clear()
        runtimeCache[key] = sec
        return sec.takeIf { it > 0 }
    }

    private val tmdbSeasonCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Number of episodes TMDB lists for season [season] of the series behind [ext]; null when unknown. */
    private suspend fun tmdbSeasonEpisodeCount(ext: ExternalId, season: Int): Int? {
        val key = "${ext.scheme}:${ext.key}:$season"
        tmdbSeasonCounts[key]?.let { return it }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "imdb", "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return null
            val n = (fetchTmdbJson("tv/$tvId/season/$season")?.get("episodes") as? kotlinx.serialization.json.JsonArray)?.size
            if (n != null && n > 0) tmdbSeasonCounts[key] = n
            n?.takeIf { it > 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    private val tmdbAllCounts = java.util.concurrent.ConcurrentHashMap<String, Map<Int, Int>>()

    /** Season number -> number of episodes TMDB lists, for the whole series behind [ext] (specials excluded). */
    private suspend fun tmdbAllSeasonCounts(ext: ExternalId): Map<Int, Int>? {
        val key = "${ext.scheme}:${ext.key}"
        tmdbAllCounts[key]?.let { return it }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "imdb", "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return null
            val seasons = fetchTmdbJson("tv/$tvId")?.get("seasons") as? kotlinx.serialization.json.JsonArray ?: return null
            val m = HashMap<Int, Int>()
            for (e in seasons) {
                val o = e as? kotlinx.serialization.json.JsonObject ?: continue
                val n = o["season_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                val c = o["episode_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                if (n > 0 && c > 0) m[n] = c
            }
            if (m.isNotEmpty()) tmdbAllCounts[key] = m
            m.takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    private val imdbOfTmdb = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The IMDb id of the series behind a tt / tmdb / tvdb id (TMDB's external ids), or null. */
    private suspend fun imdbIdOf(ext: ExternalId): String? {
        if (ext.scheme == "imdb") return ext.key
        val key = "${ext.scheme}:${ext.key}"
        imdbOfTmdb[key]?.let { return it.ifEmpty { null } }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=tvdb_id")
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return null
            val tt = fetchTmdbJson("tv/$tvId/external_ids")?.get("imdb_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
            if (imdbOfTmdb.size > 5_000) imdbOfTmdb.clear()
            imdbOfTmdb[key] = tt.orEmpty()
            tt
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    /** Request id -> running (absolute) episode number, for extensions that number a long show 1, 2, ... 1000+ (One Piece). */
    private val absoluteEpisodes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * The same episode under the other kind of id, searched next to the request's own: a TMDB / IMDb id gets the anime
     * entry with the running episode number (AniList id), an anime id (Kitsu / MAL / AniList) gets the TMDB series with
     * TMDB's season and episode. Also notes the running episode number of both. Null when there is no other path.
     */
    private suspend fun otherIdPath(type: String, id: String, ext: ExternalId, anime: AnimeInfo?): String? {
        val sn = ext.season; val en = ext.episode ?: return null
        if (type == "movie") return null
        if (absoluteEpisodes.size > 5_000) absoluteEpisodes.clear()
        return try {
            if (ext.scheme in setOf("imdb", "tmdb", "tvdb")) {
                // 1) the Kitsu add-on's mapping: the exact Kitsu entry and its own episode number for this IMDb season / episode
                val tt = imdbIdOf(ext)
                val k = if (tt != null && sn != null) runCatching { KitsuMap.fromImdb(tt, sn, en) }.getOrNull() else null
                if (k != null) {
                    tmdbAllSeasonCounts(ext)?.let { counts ->
                        val s = sn ?: 1
                        if ((1 until s).all { counts[it] != null }) absoluteEpisodes[id] = (1 until s).sumOf { counts[it]!! } + en
                    }
                    return "kitsu:${k.first}:${k.second}"
                }
                // 2) otherwise the running number in the AniList entry (one entry for the whole show, e.g. One Piece)
                val counts = tmdbAllSeasonCounts(ext) ?: return null
                val s = sn ?: 1
                if ((1 until s).any { counts[it] == null }) return null
                val abs = (1 until s).sumOf { counts[it]!! } + en
                absoluteEpisodes[id] = abs
                val al = anime?.aniListId ?: return null
                "anilist:$al:$abs".also { absoluteEpisodes[it] = abs }
            } else if (ext.scheme in setOf("kitsu", "mal", "anilist")) {
                // The request's own episode number is the Kitsu episode (kitsu:12:153 = episode 153): an entry that holds the
                // whole show (One Piece) numbers it 1, 2, ... 1000+, so that is also the running number for sites that list it so
                val wholeShow = anime == null || (anime.seasonHint ?: 1) <= 1
                if (wholeShow) absoluteEpisodes[id] = en
                val kid = if (ext.scheme == "kitsu") ext.key else runCatching { KitsuMap.kitsuOf(ext.scheme, ext.key) }.getOrNull()
                val imdbPlace = kid?.let { runCatching { KitsuMap.toImdb(it, en) }.getOrNull() }
                val src = when (ext.scheme) { "mal" -> "myanimelist"; else -> ext.scheme }
                // The TMDB series: arm.haglund.dev, else TMDB's own lookup of the IMDb id the Kitsu add-on gave
                val tmdbId = getJson("https://arm.haglund.dev/api/v2/ids?source=$src&id=${ext.key}")
                    ?.get("themoviedb")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                    ?: imdbPlace?.first?.let { tt ->
                        fetchTmdbJson("find/$tt?external_source=imdb_id")?.get("tv_results")
                            ?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                            ?.get("id")?.jsonPrimitive?.contentOrNull
                    }
                // TMDB season / episode from the running number and TMDB's season sizes (kitsu 153 -> TMDB S9E10): the
                // numbering the TMDB side of every extension expects - IMDb splits One Piece into other seasons
                if (wholeShow && tmdbId != null) {
                    val counts = tmdbAllSeasonCounts(ExternalId("tmdb", tmdbId, null, null))
                    if (counts != null) {
                        var cum = 0
                        for (s in counts.keys.filter { it >= 1 }.sorted()) {
                            val c = counts[s] ?: break
                            if (en <= cum + c) return "tmdb:$tmdbId:$s:${en - cum}".also { absoluteEpisodes[it] = en }
                            cum += c
                        }
                    }
                }
                // A later-season entry (own numbering from 1): the Kitsu add-on's exact IMDb place of this episode
                if (imdbPlace != null) return "${imdbPlace.first}:${imdbPlace.second}:${imdbPlace.third}"
                null
            } else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { null }
    }

    private val tmdbAltTitles = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * The other names TMDB knows a series by (its "alternative titles": the regional and English spellings), for the anime lookup:
     * only Latin-script ones, since the extensions' sites are searched in romaji / English. Empty when TMDB does not answer.
     */
    private suspend fun tmdbAlternativeTitles(ext: ExternalId): List<String> {
        val key = "${ext.scheme}:${ext.key}"
        tmdbAltTitles[key]?.let { return it }
        return try {
            val tvId = when (ext.scheme) {
                "tmdb" -> ext.key
                "imdb", "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=" + (if (ext.scheme == "imdb") "imdb_id" else "tvdb_id"))
                    ?.get("tv_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: return emptyList()
            val results = fetchTmdbJson("tv/$tvId/alternative_titles")?.get("results") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            val names = results.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull?.trim() }
                .filter { n -> n.length >= 3 && n.count { c -> c.code < 0x250 } * 10 >= n.length * 8 }
                .distinctBy { AnimeMatcher.normalize(it) }
                .take(10)
            if (tmdbAltTitles.size > 2_000) tmdbAltTitles.clear()
            tmdbAltTitles[key] = names
            names
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { emptyList() }
    }

    /** Title + year for any supported id; null when the source has no such item. */
    private suspend fun resolveExternal(type: String, ext: ExternalId): Pair<String, Int?>? {
        val storeKey = "title:$type:${ext.scheme}:${ext.key}"
        // one retry, then the names found by an earlier lookup (a slow or failing Kitsu / AniList / TMDB must not mean "nothing found")
        val found = resolveExternalOnce(type, ext) ?: run { delay(700); resolveExternalOnce(type, ext) }
        if (found != null) { NameStore.put(storeKey, listOf(found.first, found.second?.toString().orEmpty())); return found }
        val saved = NameStore.get(storeKey)?.takeIf { it.isNotEmpty() && it[0].isNotBlank() } ?: return null
        ServerState.warn("Title lookup for ${ext.scheme}:${ext.key} failed - searching with the name saved earlier ('${saved[0]}')")
        return saved[0] to saved.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun resolveExternalOnce(type: String, ext: ExternalId): Pair<String, Int?>? {
        if (ext.scheme == "imdb" || ext.scheme == "tmdb") return resolveGenericMedia(type, ext.key)
        val cacheKey = "$type:${ext.scheme}:${ext.key}"
        genericMediaCache[cacheKey]?.let { return it }
        val result = try {
            when (ext.scheme) {
                "tvdb" -> fetchTmdbJson("find/${ext.key}?external_source=tvdb_id")?.let { j ->
                    val r = ((j["tv_results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                        ?: (j["movie_results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull())?.jsonObject
                    val title = r?.get("name")?.jsonPrimitive?.contentOrNull ?: r?.get("title")?.jsonPrimitive?.contentOrNull
                    val date = r?.get("first_air_date")?.jsonPrimitive?.contentOrNull ?: r?.get("release_date")?.jsonPrimitive?.contentOrNull
                    title?.let { it to date?.take(4)?.toIntOrNull() }
                }
                "tvmaze" -> getJson("https://api.tvmaze.com/shows/${ext.key}")?.let { j ->
                    j["name"]?.jsonPrimitive?.contentOrNull?.let { it to j["premiered"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull() }
                }
                "kitsu" -> getJson("https://kitsu.io/api/edge/anime/${ext.key}", accept = "application/vnd.api+json")?.let { j ->
                    val a = j["data"]?.jsonObject?.get("attributes")?.jsonObject
                    val titles = a?.get("titles")?.jsonObject
                    val title = titles?.get("en")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: a?.get("canonicalTitle")?.jsonPrimitive?.contentOrNull
                        ?: titles?.get("en_jp")?.jsonPrimitive?.contentOrNull
                    title?.let { it to a?.get("startDate")?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull() }
                }
                "mal" -> aniList("idMal", ext.key)
                "anilist" -> aniList("id", ext.key)
                "anidb" -> getJson("https://arm.haglund.dev/api/v2/ids?source=anidb&id=${ext.key}")
                    ?.get("anilist")?.jsonPrimitive?.contentOrNull?.let { aniList("id", it) }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ServerState.warn("Resolve ${ext.scheme}:${ext.key} failed: ${e.message?.take(80)}")
            null
        }
        if (result != null) {
            genericMediaCache[cacheKey] = result
            ServerState.debug("Resolved ${ext.scheme}:${ext.key} -> '${result.first}' (${result.second})")
        }
        return result
    }

    /**
     * ID-lookup APIs (Kitsu, AniList, the AniDB mapping) go through OkHttp: Ktor's CIO client
     * fails the TLS handshake with some of them (arm.haglund.dev: "ProtocolVersion").
     */
    private val idApiClient by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private suspend fun idApiCall(req: okhttp3.Request): String? = withContext(Dispatchers.IO) {
        idApiClient.newCall(req).execute().use { r -> if (r.code == 200) r.body?.string() else null }
    }

    private suspend fun getJson(url: String, accept: String = "application/json"): JsonObject? =
        idApiCall(okhttp3.Request.Builder().url(url).header("Accept", accept).build())
            ?.let { serverJson.parseToJsonElement(it).jsonObject }

    /** AniList lookup by its own id or the MAL id: English title, else romaji. */
    private suspend fun aniList(field: String, key: String): Pair<String, Int?>? {
        val id = key.toIntOrNull() ?: return null
        val query = "query{Media(" + field + ":" + id + ",type:ANIME){title{english romaji}seasonYear startDate{year}}}"
        val body = kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive(query)) }.toString()
        val text = idApiCall(
            okhttp3.Request.Builder().url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
                .build()
        ) ?: return null
        val media = serverJson.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject ?: return null
        val t = media["title"]?.jsonObject
        val title = t?.get("english")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: t?.get("romaji")?.jsonPrimitive?.contentOrNull ?: return null
        val year = media["seasonYear"]?.jsonPrimitive?.intOrNull
            ?: media["startDate"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull
        return title to year
    }

    private suspend fun resolveGenericMedia(type: String, tmdbId: String): Pair<String, Int?>? {
        val cacheKey = "$type:$tmdbId"
        genericMediaCache[cacheKey]?.let { return it }

        val isImdbId = tmdbId.startsWith("tt")
        val mediaType = if (type == "series") "tv" else "movie"

        // 1. If it's an IMDb ID, try Cinemeta first (extremely fast & resilient)
        if (isImdbId) {
            try {
                val cinemetaType = if (type == "series") "series" else "movie"
                val cinemetaUrl = "https://v3-cinemeta.strem.io/meta/$cinemetaType/$tmdbId.json"
                val responseText = withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) { com.cncverse.stremiobridge.network.DohHttp.text(cinemetaUrl) }
                }
                if (responseText != null) {
                    val json = serverJson.parseToJsonElement(responseText).jsonObject
                    val meta = json["meta"]?.jsonObject
                    val title = meta?.get("name")?.jsonPrimitive?.content
                    if (!title.isNullOrBlank()) {
                        val yearStr = meta["year"]?.jsonPrimitive?.content
                            ?: meta["releaseInfo"]?.jsonPrimitive?.content
                        val year = yearStr?.take(4)?.toIntOrNull()
                        val result = Pair(title, year)
                        genericMediaCache[cacheKey] = result
                        ServerState.debug("Cinemeta resolved $tmdbId -> '$title' ($year)")
                        return result
                    }
                }
            } catch (e: Throwable) {
                ServerState.warn("Cinemeta resolve error for $tmdbId: ${e.message?.take(80)}")
            }
        }

        // 2. Query TMDB with multi-host fallback (api.tmdb.org -> api.themoviedb.org)
        val endpoint = if (isImdbId) {
            "find/$tmdbId?external_source=imdb_id"
        } else {
            "$mediaType/$tmdbId"
        }

        val jsonObject = fetchTmdbJson(endpoint) ?: return null

        val mediaObj = if (isImdbId) {
            val movieResults = jsonObject["movie_results"] as? kotlinx.serialization.json.JsonArray
            val tvResults = jsonObject["tv_results"] as? kotlinx.serialization.json.JsonArray
            (movieResults?.firstOrNull() ?: tvResults?.firstOrNull())?.jsonObject
        } else {
            jsonObject
        } ?: return null

        val title = mediaObj["title"]?.jsonPrimitive?.content
            ?: mediaObj["name"]?.jsonPrimitive?.content
            ?: return null

        val year = mediaObj["release_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()
            ?: mediaObj["first_air_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()

        val result = Pair(title, year)
        genericMediaCache[cacheKey] = result
        ServerState.debug("TMDB resolved $tmdbId -> '$title' ($year)")
        return result
    }
    /** Every channel of the combined TV catalog (A-Z), for the website's Live TV tab. */
    suspend fun liveChannels(): List<StremioMeta> = withContext(pluginDispatcher) { buildAllTv(null, 0) }

    private const val ALL_TV_ID = "cnc_all_tv"
    /** The merged list and the cached pages it was made from (by identity), so a refreshed page rebuilds it and nothing else does. */
    private class AllTv(val sources: List<Int>, val metas: List<StremioMeta>)
    @Volatile private var allTvCache: AllTv? = null

    /**
     * Every channel of every TV catalog in the manifest (the ones not switched off) as ONE list in alphabetical order
     * (leading symbols and emoji ignored). The whole list goes out at once, so later pages (skip > 0) are empty.
     */
    private suspend fun buildAllTv(search: String?, skip: Int): List<StremioMeta> {
        if (skip > 0) return emptyList()
        val defs = tvSources()
        // a source whose first page is not loaded yet is loaded now (each one is cached by buildCatalog), all at the same time
        coroutineScope {
            defs.filter { d -> homePageCatalogCache.keys.none { it.startsWith("tv:${d.id}:") } }.map { d ->
                async { withTimeoutOrNull(30_000L) { runCatching { buildCatalog("tv", d.id, null, 0, null) } } }
            }.awaitAll()
        }
        val pages = homePageCatalogCache.entries.filter { (k, _) -> defs.any { k.startsWith("tv:${it.id}:") } }.map { it.value }
        val sig = pages.map { System.identityHashCode(it) }.sorted()
        val merged = allTvCache?.takeIf { it.sources == sig }?.metas ?: pages.asSequence().flatten().distinctBy { it.id }
            .sortedWith(compareBy<StremioMeta, String>(String.CASE_INSENSITIVE_ORDER) { m -> m.name.replace(LEADING_SYMBOLS, "").trim().ifEmpty { m.name } })
            .toList().also { allTvCache = AllTv(sig, it) }
        if (search.isNullOrBlank()) return merged
        val words = normalizeTitle(search).split(' ').filter { it.isNotEmpty() }
        return merged.filter { m -> val n = m.name.lowercase(); words.all { n.contains(it) } }.take(200)
    }

    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        if (id == ALL_TV_ID) return if (type == "tv" && !ServerState.disableCatalogsGlobally) buildAllTv(search, skip) else emptyList()
        if (id == ALL_DRAMA_ID) return if (!ServerState.disableCatalogsGlobally) buildAllDrama(search, skip, genre) else emptyList()
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)
        val nameSlugFromId = rest.removeSuffix("_$type")
        val api = loadedApis.find { apiKey(it) == nameSlugFromId }
            ?: loadedApis.find { nameSlug(it.name) == nameSlugFromId }
            ?: loadedApis.find { it.internalName == nameSlugFromId }
            ?: loadedApis.find { rest.startsWith(it.internalName + "_") }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (ServerState.disableCatalogsGlobally) return emptyList()
        if (isGloballyDisabled(api)) return emptyList()

        // Live-TV sources (SKTech, PlayZTV, PlayFy…) answer a search by downloading, decrypting and
        // re-parsing every playlist — thousands of channels, per source, per search — and Stremio
        // sends a search to every catalog. Their home pages are already parsed and cached, so a
        // search is just a filter over those channels.
        if (!search.isNullOrBlank() && api.supportedTypes.all { it == "tv" }) {
            val pages = homePageCatalogCache.filterKeys { it.startsWith("$type:$id:") }.values
            if (pages.isNotEmpty()) {
                // Plain lower-case contains: normalizeTitle's regexes over ~15k channels per
                // catalog per search showed up in the CPU profile
                val words = normalizeTitle(search).split(' ').filter { it.isNotEmpty() }
                return pages.asSequence().flatten().distinctBy { it.id }
                    .filter { m -> val n = m.name.lowercase(); words.all { n.contains(it) } }
                    .drop(skip).take(100).toList()
            }
        }

        val isHomePage = search.isNullOrBlank() && skip == 0
        val cacheKey = "$type:$id:$genre"

        if (isHomePage) {
            val cached = homePageCatalogCache[cacheKey]
            if (!cached.isNullOrEmpty()) {
                // Home pages are loaded once at startup (and after a plugin reload). After that a
                // page is refreshed only when someone opens it and it is older than its TTL: the
                // cached page is served at once and that one page reloads in the background.
                // (A timer used to reload every home page every 30 min, opened or not.)
                val liveOnly = api.supportedTypes.all { it == "tv" }
                val age = System.currentTimeMillis() - (homePageCachedAt[cacheKey] ?: 0L)
                // Live events change by the minute; channel playlists re-parse thousands of
                // lines, so 15 min; everything else 30 min.
                val ttl = when {
                    !liveOnly -> HOME_PAGE_TTL_MS
                    cached.size <= 150 -> LIVE_HOME_TTL_MS
                    else -> LIVE_PLAYLIST_TTL_MS
                }
                // Live: one refresh per provider at a time (its sections share one fetch);
                // others: one per page
                val refreshKey = if (liveOnly) api.internalName else cacheKey
                if (age > ttl && homePageRefreshing.add(refreshKey)) {
                    streamSearchScope.launch {
                        try {
                            val fresh = fetchCatalogItemsDirect(type, id, null, 0, genre)
                            if (fresh.isNotEmpty()) {
                                homePageCatalogCache[cacheKey] = fresh
                                homePageCachedAt[cacheKey] = System.currentTimeMillis()
                            }
                        } finally {
                            homePageRefreshing.remove(refreshKey)
                        }
                    }
                }
                return cached
            }
        }

        val metas = fetchCatalogItemsDirect(type, id, search, skip, genre)
        if (isHomePage && metas.isNotEmpty()) {
            homePageCatalogCache[cacheKey] = metas
            homePageCachedAt[cacheKey] = System.currentTimeMillis()
        }
        return metas
    }


    // ── Meta builder ──────────────────────────────────────────────────────────

    /** A source that has not answered its detail page by now is skipped (Stremio gives up long before). */
    private const val META_LOAD_TIMEOUT_MS = 25_000L

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (pluginKey, dataUrl) = StremioIds.decode(id) ?: return null
        // Item ids carry the source's display-name slug, which several extensions can share
        // (SKTech and LivXow both have "📺 SUN NXT"). Taking the first match could pick a
        // copy that is disabled for this user → 404 and a detail page that never loads.
        // Try every enabled match instead: exact keys first, then the name slug.
        val candidates = (loadedApis.filter { apiKey(it) == pluginKey } +
            loadedApis.filter { nameSlug(it.name) == pluginKey } +
            loadedApis.filter { it.internalName == pluginKey })
            .distinct()
            .filter { !isGloballyDisabled(it) }
        for (api in candidates) {
            val meta = try {
                // Opening the same detail page again used to re-scrape the site every time
                val key = apiKey(api)
                val info = SearchLoadCache.getLoad(key, dataUrl)?.info
                    ?: kotlinx.coroutines.withTimeoutOrNull(META_LOAD_TIMEOUT_MS) { api.load(dataUrl) }
                        ?.also { SearchLoadCache.putLoad(key, dataUrl, it) }
                // The meta keeps the id it was asked for. toStremiMeta() names a movie by its
                // load() data (often a JSON blob of players); clients that store the returned
                // id (Nuvio) then asked meta for that blob, which load() can't open → 404.
                info?.toStremiMeta(nameSlug(api.name), type)?.copy(id = id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ServerState.warn("Meta error for ${api.name}: ${e.message}")
                null
            }
            if (meta != null) return meta.also { rememberTitles(listOf(it)) }
        }
        return null
    }


    // ── Quality sorting ───────────────────────────────────────────────────────

    /** Higher is listed earlier: the stream's real resolution height (unknown = 0, listed after every known one). */
    private fun streamQualityRank(stream: StremioStream): Int = resolutionHeight(stream) ?: 0

    /** Lowest resolution that is ever shown. Streams with a KNOWN lower resolution are always dropped. */

    /** The stream's resolution as a pixel height (2160, 1080, 720, 480, 360 ...), or null when it cannot be found. */
    fun resolutionHeight(stream: StremioStream): Int? =
        com.cncverse.stremiobridge.format.StreamVariables.resolutionOf(stream)
            ?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * Final, settings-independent pass over every list that is sent out: streams whose resolution is known
     * and below 720p are removed, and the rest is ordered by resolution, highest first (unknown last).
     * The sort is stable, so inside one resolution the order chosen by the other settings (provider order,
     * size, hardsub first, anime audio) is kept.
     */
    fun enforceResolutionOrder(streams: List<StremioStream>): List<StremioStream> {
        // Settings -> Streams -> "Hide links below" (default 720p; "" = show all)
        val min = streamPrefs.minResolution.filter { it.isDigit() }.toIntOrNull() ?: 0
        val keyed = streams.map { it to resolutionHeight(it) }
        // Never leave a title with no links just because every link is low quality
        val visible = keyed.filter { (_, h) -> h == null || h >= min }.ifEmpty { keyed }
        return visible.sortedByDescending { (_, h) -> h ?: 0 }.map { it.first }
    }

    /**
     * Best quality first. Ties are broken the same way on every load (provider, then name, then link) -
     * they used to keep the order in which providers happened to answer, so equal-quality links changed
     * places from one request to the next.
     */
    private fun sortStreamsByQuality(streams: List<StremioStream>): List<StremioStream> {
        class K(val s: StremioStream, val rank: Int, val provider: String, val label: String, val link: String)
        val keyed = streams.map {
            K(
                it, streamQualityRank(it),
                (it.info?.providerName ?: com.cncverse.stremiobridge.format.StreamVariables.addonOf(it)).orEmpty().lowercase(),
                (it.info?.linkName ?: it.title ?: it.name).orEmpty().lowercase(),
                it.url ?: it.infoHash ?: "",
            )
        }
        return keyed.sortedWith(compareByDescending<K> { it.rank }.thenBy { it.provider }.thenBy { it.label }.thenBy { it.link }).map { it.s }
    }

    /** Detects standard resolution tier: 2160p, 1080p, 720p, 480p, 360p, or other. */
    fun detectStreamResolution(stream: StremioStream): String =
        // Same rule as the formatter: reported quality, else the link's own name —
        // never the matched release title (multi-quality pack pages say "4K 1080p 720p").
        when (com.cncverse.stremiobridge.format.StreamVariables.resolutionOf(stream)) {
            "4320p", "2160p" -> "2160p"
            "1440p", "1080p" -> "1080p"
            "720p" -> "720p"
            "576p", "480p" -> "480p"
            "360p", "240p", "144p", "180p" -> "360p"
            else -> "other"
        }

    /** Returns true if the stream name/title indicates a CAM / TeleSync / Screener recording. */
    fun isCamStream(stream: StremioStream): Boolean {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".uppercase()
        // No bare "TS"/"SCR": they match MPEG-TS / HLS ".ts" segments and language tags.
        return CAM_RX.containsMatchIn(text)
    }

    private val CAM_RX = Regex("\\b(CAM|CAMRIP|CAM-RIP|HDCAM|HD-CAM|TELESYNC|HDTS|HD-TS|SCREENER|DVDSCR|DVDSCREENER)\\b")

    /** Resolution tiers the configure page lets users pick; anything else is never filtered out. */
    private val SELECTABLE_RESOLUTIONS = setOf("2160p", "1080p", "720p", "480p", "360p")

    /**
     * Orders already-filtered streams by the profile's grouping, sorting and provider order.
     * Default/quality: best resolution first, inside a resolution the provider order (or, with
     * "sort by size", the biggest file first, unknown last). Provider grouping: all of one
     * provider together, providers in the chosen order, best resolution first inside each.
     */
    fun arrangeStreams(streams: List<StremioStream>, profile: StreamPrefs): List<StremioStream> {
        val bySize = profile.sortBy == "size"
        val byProvider = profile.groupBy == "provider"
        val rank = profile.providerOrder.withIndex().associate { (i, n) -> n.lowercase() to i }
        // Sort keys are computed ONCE per stream: the size lookup is a text search and a sort
        // compares each stream about log2(n) times (6 ms -> well under 1 ms for 250 streams)
        class Keyed(val stream: StremioStream, val providerRank: Int, val provider: String, val quality: Int, val sizeKey: Long, val kindRank: Int, val kindName: String, val height: Int)
        val keyed = streams.map { st ->
            // The configure page lists extensions by display name ("4K HDHUB"); a stream's addon
            // label is often the plugin file name ("FourKHDHub"), so match on the display name
            val provider = (st.info?.providerName ?: com.cncverse.stremiobridge.format.StreamVariables.addonOf(st))?.lowercase().orEmpty()
            // Largest first via a negated key; unknown size gets +1 so it sorts after every known one
            val sizeKey = if (!bySize) 0L else -(com.cncverse.stremiobridge.format.StreamVariables.sizeBytesOf(st) ?: -1L)
            val (kindRank, kindName) = linkKind(st)
            Keyed(st, rank[provider] ?: Int.MAX_VALUE, provider, streamQualityRank(st), sizeKey, kindRank, kindName, resolutionHeight(st) ?: 0)
        }
        val order = if (byProvider)
            compareBy<Keyed>({ it.providerRank }, { it.provider }, { -it.quality }, { it.sizeKey })
        else
            // default: highest resolution first; inside a resolution the fast links come first (fsl, pixeldrain, workers,
            // then adaptive m3u8), the rest after; similar links together, then quality / size / provider order inside each group
            compareBy<Keyed>({ -it.height }, { speedTier(it.kindRank) }, { it.kindName }, { -it.quality }, { it.sizeKey }, { it.providerRank })
        return keyed.sortedWith(order).map { it.stream } // stable: ties keep their arrival order
    }

    /** fsl, pixeldrain, workers = 0..2, adaptive m3u8 = 3 (fast but lower quality), other direct = 4, relay = 5, rest unchanged */
    private fun speedTier(kindRank: Int): Int = when (kindRank) { 5 -> 3; 3 -> 4; 4 -> 5; else -> kindRank }

    private val HOST_RX = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE)
    private val FSL_RX = Regex("fsl", RegexOption.IGNORE_CASE)
    private val HUB_PATH_RX = Regex("^https?://[^/]+/hub2?/")
    private fun isFslName(t: String?): Boolean =
        !t.isNullOrBlank() && FSL_RX.containsMatchIn(java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC))

    /**
     * The kind of a link, for grouping similar links together: (group order, name inside the group).
     * 0 fsl, 1 pixeldrain, 2 workers (Cloudflare workers.dev), 3 other direct files (grouped by site),
     * 4 Telegram / this bridge's own relay, 5 adaptive m3u8 (grouped by site), 6 torrents, 7 everything else.
     */
    internal fun linkKind(s: StremioStream): Pair<Int, String> {
        val u = s.url
        if (u.isNullOrBlank()) return (if (s.infoHash != null) 6 else 7) to ""
        val host = HOST_RX.find(u)?.groupValues?.get(1)?.lowercase() ?: return 7 to ""
        val lower = u.lowercase()
        val labels = host.split('.')
        val site = if (labels.size > 2 && labels[labels.size - 2].length <= 3 && labels.last().length == 2) labels.takeLast(3).joinToString(".")
            else labels.takeLast(2).joinToString(".")
        return when {
            // fsl: in the address, or only in the link's name ("Hub-Cloud[FSLv2 Server]" on an r2.dev / cloudflarestorage host)
            host.contains("fsl") || lower.contains("/fsl/") ||
                // HubCloud's FSL servers are Cloudflare R2 buckets named "hub" / "hub2"
                (host.endsWith("r2.cloudflarestorage.com") && HUB_PATH_RX.containsMatchIn(lower)) ||
                // names are often written in "mathematical bold" letters (𝗙𝗦𝗟): fold them to plain letters first
                isFslName(s.info?.linkName) || isFslName(s.name) || isFslName(s.title) -> 0 to "fsl"
            host.endsWith("r2.dev") || host.endsWith("r2.cloudflarestorage.com") -> 2 to host
            host.contains("pixeldrain") -> 1 to "pixeldrain"
            host.endsWith("workers.dev") -> 2 to host
            lower.contains("/proxy/") || lower.contains("/media/") || host.contains("telegram") || s.info?.addonName?.contains("telegram", true) == true -> 4 to site
            s.info?.linkType.equals("M3U8", true) || lower.substringBefore('?').endsWith(".m3u8") || lower.contains(".m3u8") -> 5 to site
            else -> 3 to site
        }
    }

    /** Filters a list of streams against a user's profile quality preferences. */
    fun filterStreamsByProfile(streams: List<StremioStream>, profile: StreamPrefs, note: (String, List<StremioStream>, List<StremioStream>) -> Unit = { _, _, _ -> }): List<StremioStream> {
        var result = streams

        // 1. Exclude CAM / Screener rips
        if (profile.excludeCam) {
            val b = result
            result = result.filter { !isCamStream(it) }
            note("Setting \"Hide CAM / TeleSync\"", b, result)
        }

        // 1b. Streams whose resolution cannot be found at all
        if (profile.hideUnknownQuality) {
            val b = result
            result = result.filter { detectStreamResolution(it) != "other" }
            note("Setting \"Hide streams with unknown quality\"", b, result)
        }

        // 2. Filter allowed resolutions if user chose specific ones
        // Streams without a selectable tier (unlabeled HLS "Auto", live TV, 240p…) always pass:
        // the UI has no checkbox for them, so filtering them would silently empty whole titles.
        val allowed = profile.allowedResolutions.map { it.lowercase().trim() }.toSet()
        if (allowed.isNotEmpty() && !allowed.containsAll(SELECTABLE_RESOLUTIONS)) {
            val b = result
            result = result.filter { stream ->
                val res = detectStreamResolution(stream).lowercase()
                res !in SELECTABLE_RESOLUTIONS || res in allowed
            }
            note("Resolution not ticked in the stream settings", b, result)
        }

        // 2b. File size window: only streams whose size is KNOWN can be outside it (unknown always pass)
        if (profile.minSizeGb > 0 || profile.maxSizeGb > 0) {
            val gb = 1024.0 * 1024 * 1024
            val lo = (profile.minSizeGb * gb).toLong()
            val hi = if (profile.maxSizeGb > 0) (profile.maxSizeGb * gb).toLong() else Long.MAX_VALUE
            val b = result
            result = result.filter { st ->
                val size = com.cncverse.stremiobridge.format.StreamVariables.sizeBytesOf(st) ?: return@filter true
                size in lo..hi
            }
            note("File size outside the minimum / maximum size setting", b, result)
        }

        // 3. Limit streams per resolution tier if configured
        if (profile.maxStreamsPerResolution > 0) {
            val groups = LinkedHashMap<String, MutableList<StremioStream>>()
            for (st in result) {
                val res = detectStreamResolution(st)
                val list = groups.getOrPut(res) { mutableListOf() }
                if (list.size < profile.maxStreamsPerResolution) {
                    list.add(st)
                }
            }
            val b = result
            result = groups.values.flatten()
            note("More than ${profile.maxStreamsPerResolution} links of one resolution (setting)", b, result)
        }

        return result
    }
        
    // ── Main stream builder ───────────────────────────────────────────────────

    /**
     * Deadline-based parallel stream loader.
     * All providers start concurrently on [streamSearchScope]; each appends to a shared list as it
     * finishes. After [STREAM_DEADLINE_MS] any still-running jobs are
     * cancelled and whatever has accumulated is returned — ensuring Stremio always
     * gets a response well within its 60-second addon timeout.
     */
    private val STREAM_DEADLINE_MS = 170_000L
    /** Answer with what has loaded after this long; the rest load into the cache until STREAM_DEADLINE_MS. */
    private val streamRequestStart = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val STREAM_SOFT_DEADLINE_MS = 8_000L
    /** Never keep a client waiting longer than this for the first answer. */
    private val STREAM_ANSWER_CAP_MS = 32_000L
    /** Hard limit for the whole search part of a stream request. */
    private val RESPONSE_CAP_MS = 38_000L
    /** Time budget for the anime name / episode mapping lookups before the providers are started. */
    private val ANIME_RESOLVE_BUDGET_MS = 25_000L
    /** The providers are always given at least this long before the answer cap can end the wait. */
    private val STREAM_MIN_WINDOW_MS = 12_000L
    private val PROVIDER_TIMEOUT_MS = 150_000L
    private val streamSearchScope = CoroutineScope(pluginDispatcher + SupervisorJob())

    /** Live state of one title's search: how many sources there are, how many finished, what each one found. */
    private class SearchStatus(val total: Int) {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        @Volatile var finished = false
    }
    private val searchStatus = java.util.concurrent.ConcurrentHashMap<String, SearchStatus>()

    /**
     * Short-lived TTL cache for [search] and [load] results used in the generic
     * TMDB stream path. Avoids redundant plugin calls for the same title when
     * multiple concurrent Stremio requests arrive or a user replays a stream.
     *
     * Modeled on CloudStream's APIRepository cache (LRU-style, keyed by
     * "pluginInternalName::query" or "pluginInternalName::url").
     */
    /**
     * Android says the phone is short of memory ([critical]: very short): cached extension pages, older searches and the
     * Kitsu episode lists are dropped. Nothing is lost - they are fetched again when a search needs them.
     */
    fun trimMemory(critical: Boolean) {
        runCatching { SearchLoadCache.trim(critical) }
        runCatching { KitsuMap.trim() }
        ServerState.debug("[Memory] Low memory: cached pages" + (if (critical) ", searches" else "") + " cleared")
    }

    private object SearchLoadCache {
        private const val TTL_MS = 30 * 60 * 1_000L   // load() results: 30 minutes (the next episode of a series opens without loading its page again)
        private const val MAX_ENTRIES = 200
        // Search results (shared by Stremio's catalog search and the IMDb/TMDB stream lookup):
        // hits 30 min, empty results 5 min so a site that hiccupped isn't "no results" for long
        private const val SEARCH_TTL_MS = 30 * 60 * 1_000L
        private const val EMPTY_SEARCH_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_SEARCH_ENTRIES = 5_000

        private data class Entry<T>(val value: T, val timestamp: Long = System.currentTimeMillis())
        /** Wraps a nullable MediaInfo so we can cache a 'null' result (load returned nothing). */
        data class CachedLoad(val info: MediaInfo?)

        private val searchCache = ConcurrentHashMap<String, Entry<List<SearchResult>>>()
        private val loadCache   = ConcurrentHashMap<String, Entry<CachedLoad>>()

        /** "Dune", " dune " and "DUNE" share an entry. */
        private fun searchKey(apiKey: String, query: String) =
            "$apiKey::search::" + query.trim().lowercase().replace(WHITESPACE, " ")
        private val WHITESPACE = Regex("[ \t\r\n]+")
        private fun loadKey(apiKey: String, url: String)    = "$apiKey::load::$url"

        fun getSearch(apiKey: String, query: String): List<SearchResult>? {
            val k = searchKey(apiKey, query)
            val e = searchCache[k] ?: return null
            val ttl = if (e.value.isEmpty()) EMPTY_SEARCH_TTL_MS else SEARCH_TTL_MS
            if (System.currentTimeMillis() - e.timestamp > ttl) { searchCache.remove(k); return null }
            return e.value
        }

        /** Only call with the result of a search that completed (errors are never cached). */
        fun putSearch(apiKey: String, query: String, value: List<SearchResult>) {
            if (searchCache.size >= MAX_SEARCH_ENTRIES) {
                // Drop the oldest tenth
                searchCache.entries.sortedBy { it.value.timestamp }.take(MAX_SEARCH_ENTRIES / 10)
                    .forEach { searchCache.remove(it.key) }
            }
            searchCache[searchKey(apiKey, query)] = Entry(value)
        }

        /** Returns the cached [CachedLoad] wrapper, or `null` if not cached / expired. */
        fun getLoad(apiKey: String, url: String): CachedLoad? {
            val k = loadKey(apiKey, url)
            val e = loadCache[k] ?: return null
            if (System.currentTimeMillis() - e.timestamp > TTL_MS) { loadCache.remove(k); return null }
            return e.value
        }

        /**
         * Pages are kept by size, not only by count: a page of a long anime holds 1000+ episodes (One Piece on AnimePahe,
         * with every episode's title, picture and summary), and 500 of those used to stay in memory for half an hour -
         * enough to push other apps out of the phone's memory. At most [MAX_EPISODES] episodes in all; the oldest go first.
         */
        private const val MAX_EPISODES = 12_000
        private fun weight(e: Entry<CachedLoad>) = 1 + (e.value.info?.episodes?.size ?: 0)

        fun putLoad(apiKey: String, url: String, value: MediaInfo?) {
            val now = System.currentTimeMillis()
            loadCache.entries.filter { now - it.value.timestamp > TTL_MS }.forEach { loadCache.remove(it.key) }
            loadCache[loadKey(apiKey, url)] = Entry(CachedLoad(value))
            var total = loadCache.values.sumOf { weight(it) }
            if (total > MAX_EPISODES || loadCache.size > MAX_ENTRIES) {
                for (old in loadCache.entries.sortedBy { it.value.timestamp }) {
                    if (total <= MAX_EPISODES * 3 / 4 && loadCache.size <= MAX_ENTRIES) break
                    if (loadCache.remove(old.key) != null) total -= weight(old.value)
                }
            }
        }

        /** The phone is short of memory: the pages go (they are loaded again when needed), and the older searches. */
        fun trim(all: Boolean) {
            loadCache.clear()
            val now = System.currentTimeMillis()
            searchCache.entries.filter { all || now - it.value.timestamp > 5 * 60_000L }.forEach { searchCache.remove(it.key) }
        }

        fun invalidate(apiKey: String) {
            searchCache.keys.filter { it.startsWith(apiKey) }.forEach { searchCache.remove(it) }
            loadCache.keys.filter   { it.startsWith(apiKey) }.forEach { loadCache.remove(it) }
        }

        fun clear() { searchCache.clear(); loadCache.clear() }
    }

    /** Every extension's answer, with links that arrive JSON-escaped (`https:\/\/host\/x.m3u8`, seen from Anizone) put right. */
    private suspend fun buildStreams(type: String, id: String): List<StremioStream> =
        buildStreamsRaw(type, id).map { st ->
            val u = st.url
            if (u != null && (u.contains("\\/") || u.contains("\\u0026")))
                st.copy(url = u.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "="))
            else st
        }

    private suspend fun buildStreamsRaw(type: String, id: String): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (pluginKey, dataUrl) = decoded
            val matchingApis = loadedApis.filter { api ->
                val slug = apiKey(api)
                slug == pluginKey || nameSlug(api.name) == pluginKey || api.internalName == pluginKey
            }.filter { !isGloballyDisabled(it) }

            if (matchingApis.isEmpty()) return emptyList()

            val directCacheKey = "stream:direct:$pluginKey:$dataUrl"
            // Live TV / live events: links are short-lived session URLs — never cache them.
            val isLive = type == "tv" || matchingApis.all { api -> api.supportedTypes.all { it == "tv" } }
            return profiledFetch(directCacheKey, pluginKey) {
                ServerState.info("Parallel stream load: ${matchingApis.size} API(s) for key '$pluginKey'")

                val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
                val jobs = matchingApis.map { api ->
                    streamSearchScope.launch {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            try {
                                ServerState.debug("[${api.name}] Loading links for $dataUrl")
                                fun loadData(mi: MediaInfo?) = mi?.let {
                                    it.dataUrl.takeIf { d -> d.isNotBlank() && d != dataUrl }
                                        ?: it.episodes?.firstOrNull()?.dataUrl
                                }?.takeIf { it != dataUrl }
                                // A movie's id is its page URL (the id the client asked meta for); its
                                // detail page was usually just opened, so load() is cached — go straight
                                // to the links instead of trying the page URL first.
                                val cachedData = loadData(SearchLoadCache.getLoad(apiKey(api), dataUrl)?.info)
                                var links = api.loadLinksAll(cachedData ?: dataUrl)
                                // Catalog items opened straight from a row (defaultVideoId) carry the
                                // item URL, not the load() data — e.g. Netflix mirrors need the title
                                // that only load() adds. Resolve it once and retry.
                                if (links.isEmpty() && cachedData == null && !api.supportedTypes.all { it == "tv" }) {
                                    val resolved = loadData(runCatching { api.load(dataUrl) }.getOrNull())
                                    if (resolved != null) {
                                        ServerState.info("[${api.name}] Retrying with load() data")
                                        links = api.loadLinksAll(resolved)
                                    }
                                }
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, links.size, null)
                                if (links.isNotEmpty()) {
                                    ServerState.debug("[STREAM_SUCCESS] [${api.name}] Resolved ${links.size} streamable link(s)")
                                } else {
                                    ServerState.debug("[STREAM_EMPTY] [${api.name}] 0 streamable links returned")
                                }
                                accumulated.addAll(withMetadataTitle(links, titleForId(id)))
                            } catch (e: Throwable) {
                                // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
                                ServerState.error("[STREAM_ERROR] [${api.name}] Stream error: ${e.message}")
                            }
                        }
                    }
                }

                withTimeoutOrNull(STREAM_DEADLINE_MS) { jobs.joinAll() }
                val remaining = jobs.count { it.isActive }
                if (remaining > 0) {
                    ServerState.warn("Deadline reached ($STREAM_DEADLINE_MS ms) — returning ${accumulated.size} stream(s), cancelling $remaining slow provider(s)")
                    jobs.forEach { it.cancel() }
                }
                // Partial (deadline hit) or live results are served but not cached
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(
                    sortStreamsByQuality(accumulated),
                    cacheable = remaining == 0 && !isLive,
                )
            }
        }

        // Handle generic Stremio requests with TMDB/IMDB IDs
        // IMDb, TMDB, TVDB, TVmaze and anime (Kitsu, MAL, AniList, AniDB) ids; anything else
        // (other add-ons' own items) can never be looked up, so it is skipped right away
        val ext = ExternalIds.parse(id) ?: return emptyList()

        ServerState.debug("Generic request: id=$id, type=$type, ext=$ext")

        // Key on the exact set of extensions this request may use, so profiles with
        // different opt-ins / disables and admin enable/disable changes never share results.
        val activeSig = loadedApis.asSequence()
            .filter { api -> !isGloballyDisabled(api) && api.supportedTypes.any { it != "tv" } }
            .map { it.internalName }.sorted().plus(UpstreamAddons.active().map { it.url }).joinToString(",").hashCode()
        // "k2": answers saved before the strict Kitsu matching are not served again
        val aggCacheKey = "stream:generic:k2:$type:$id:$activeSig"

        return profiledFetch(aggCacheKey, null) {
            try {
                // Anime ids: every title the show is known by (English, romaji, synonyms) for the matching
                val animeInfo = if (ext.scheme in ExternalIds.ANIME_SCHEMES) AnimeResolver.resolve(ext) else null
                val resolved = resolveExternal(type, ext) ?: animeInfo?.let { it.titles.first() to it.year }
                if (resolved == null) {
                    ServerState.warn("Media resolve failed: no title/year found for $id")
                    return@profiledFetch com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(emptyList())
                }
                val (title, year) = resolved
                com.cncverse.stremiobridge.state.StreamHistory.noteTitle(id, title)
                ServerState.debug("Media resolve success: title='$title', year=$year")
                // tt / tmdb / tvdb ids of anime: collect every alternative name (AniList, Kitsu, MyAnimeList) too,
                // and map TMDB (season, episode) onto the AniList entry and the episode number inside it.
                // This is only a refinement of the search, so it gets a time budget: the lookups are blocking HTTP
                // calls (AniList / Kitsu / MAL / TMDB) and when one is slow they used to eat the whole answer time,
                // so the providers started late and the player got 0 links. The lookup keeps running detached
                // (it fills the resolver cache for the next try); this request carries on with the plain title.
                val animeResolution = streamSearchScope.async {
                    val names = animeInfo ?: if ((type == "series" || type == "anime") && ext.scheme in setOf("imdb", "tmdb", "tvdb")) {
                        val base = AnimeResolver.fromTitle(title, year)
                        val sn = ext.season
                        if (base != null && sn != null && sn > 1) {
                            // Later season: AniList lists it as its own entry (own names, own episode count)
                            val s2 = AnimeResolver.fromTitle("$title Season $sn", null)
                                ?: AnimeResolver.fromTitle("$title ${mapOf(2 to "2nd", 3 to "3rd").getOrDefault(sn, "${sn}th")} Season", null)
                            if (s2 != null && AnimeMatcher.seasonNumber(AnimeMatcher.normalize(s2.titles.first())) == sn)
                                s2.copy(titles = (s2.titles + base.titles).distinct(), seasonHint = sn)
                            else base.copy(seasonHint = sn, episodeCount = null, aniListId = null, malId = null)
                        } else base
                    } else null
                    val target: AnimeTarget? = if (names != null && ext.scheme in setOf("imdb", "tmdb", "tvdb") &&
                        ext.season != null && ext.episode != null && names.aniListId != null) {
                        // the Kitsu add-on's own episode mapping first (exact per entry), the episode counts otherwise
                        val kitsuHint = imdbIdOf(ext)?.let { tt -> runCatching { KitsuMap.fromImdb(tt, ext.season, ext.episode) }.getOrNull() }
                        tmdbAllSeasonCounts(ext)?.let { counts -> AnimeResolver.mapEpisode(names, ext.season, ext.episode, counts, kitsuHint) }
                    } else null
                    // TMDB's own alternative titles of the series join the names (after AniList / Kitsu / MyAnimeList ones)
                    val named = if (names != null && ext.scheme in setOf("imdb", "tmdb", "tvdb") && type != "movie") {
                        val more = tmdbAlternativeTitles(ext)
                        if (more.isEmpty()) names else {
                            val seen = names.titles.map { AnimeMatcher.normalize(it) }.toMutableSet()
                            names.copy(titles = names.titles + more.filter { seen.add(AnimeMatcher.normalize(it)) })
                        }
                    } else names
                    named to target
                }
                var animeTimedOut = false
                val (animeNames, animeTarget) = withTimeoutOrNull(ANIME_RESOLVE_BUDGET_MS) { animeResolution.await() }
                    ?: run {
                        animeTimedOut = true
                        ServerState.warn("[Streams] '$title': anime lookup took longer than ${ANIME_RESOLVE_BUDGET_MS / 1000}s - searching with the plain title first, the anime names are added when the lookup finishes")
                        animeInfo to null
                    }
                val animeForSearch = animeTarget?.entry?.copy(titles = (animeTarget.entry.titles + (animeNames?.titles.orEmpty())).distinct()) ?: animeNames

                // Exclude live-TV-only extensions from generic TMDB VOD searches —
                // they don't carry on-demand movie/series content.
                // Plugins still loading (app just started): wait for them instead of answering "0 streams"
                if (!com.cncverse.stremiobridge.plugin.GlobalPluginManager.isPluginsLoaded.value) {
                    ServerState.info("[Streams] plugins still loading - waiting up to 20 s before searching")
                    withTimeoutOrNull(20_000) {
                        com.cncverse.stremiobridge.plugin.GlobalPluginManager.isPluginsLoaded.first { it }
                    }
                }
                val activePlugins = loadedApis.filter { api ->
                    !isGloballyDisabled(api) &&
                    api.supportedTypes.any { it != "tv" }
                }
                ServerState.debug("Searching across ${activePlugins.size} plugin(s) (live-TV-only excluded)...")

                // The same episode under the other kind of id (anime entry <-> TMDB series), searched next to this one
                // worked out next to the searches (it used to hold every extension back for up to 8 s)
                val altIdRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val altIdJob = streamSearchScope.async {
                    withTimeoutOrNull(8_000L) { otherIdPath(type, id, ext, animeForSearch) }.also { a ->
                        altIdRef.set(a)
                        if (a != null) ServerState.info("[Streams] '$title': also searching as $a" + (absoluteEpisodes[id]?.let { " (running episode $it)" } ?: ""))
                    }
                }
                val pathCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
                // Anime with a Kitsu entry (a Kitsu / MAL / AniList id, or the Kitsu episode found for a TMDB / IMDb one):
                // strict mode - every extension only searches Kitsu's own names for that entry, takes only results named
                // exactly like them and Kitsu's episode number in them. An extension without such an entry gives nothing
                // (no TMDB / similar-name guessing, which brought the first season's episode for a later season).
                val strictKitsuJob = streamSearchScope.async {
                    val key = if (ext.scheme in ExternalIds.ANIME_SCHEMES && ext.episode != null) id
                        else altIdJob.await()?.takeIf { it.startsWith("kitsu:") }
                    val p = key?.let { ExternalIds.parse(it) } ?: return@async null
                    val kid = if (p.scheme == "kitsu") p.key else runCatching { KitsuMap.kitsuOf(p.scheme, p.key) }.getOrNull()
                    val names = kid?.let { withTimeoutOrNull(10_000L) { runCatching { KitsuMap.names(it) }.getOrNull() } }
                    if (names == null) null else {
                        ServerState.info("[Streams] '$title': anime - searching only Kitsu's names for kitsu:$kid (${names.titles.take(4).joinToString(" / ")}), episode ${p.episode}")
                        key
                    }
                }

                val sem = Semaphore(20)
                val started = System.currentTimeMillis()
                val accumulated = java.util.concurrent.CopyOnWriteArrayList<StremioStream>()
                // Set once the request has been answered early: from then on every provider that
                // finishes re-saves the cached list, so reloads see links arrive one by one.
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val publish = {
                    com.cncverse.stremiobridge.cache.StreamCacheManager.put(aggCacheKey, withLastGood(type, id, title, sortStreamsByQuality(accumulated), null))
                }
                val status = SearchStatus(activePlugins.size + UpstreamAddons.active().size)
                if (searchStatus.size > 100) searchStatus.keys.take(50).forEach { searchStatus.remove(it) }
                searchStatus["$type:$id"] = status
                searchPartial["$type:$id"] = accumulated
                val jobs = activePlugins.map { api ->
                    streamSearchScope.launch {
                        sem.withPermit {
                            val res = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                try {
                                    // The two ids of an anime one after the other, never at the same time (half the load on the
                                    // device and on each site): the Kitsu side first, then the TMDB / IMDb side
                                    // Each side's links join the answer as soon as that side is done (not after both)
                                    val seenUrls = java.util.Collections.synchronizedSet(HashSet<String>())
                                    val got = java.util.concurrent.atomic.AtomicInteger(0)
                                    fun take(list: List<StremioStream>, side: String) {
                                        // Series (not anime): a link whose own file name says another episode ("S01E03" for
                                        // S1E5 - a site that has not added the episode yet and hands out the last one) is dropped
                                        val checked = if (side == "own" && animeForSearch == null) dropOtherEpisodes(api.name, id, ext, list) else list
                                        val fresh = checked.filter { s -> (s.url ?: s.infoHash)?.let { seenUrls.add(it) } ?: true }
                                        if (fresh.isEmpty()) return
                                        pathCounts.merge(side, fresh.size) { x, y -> x + y }
                                        accumulated.addAll(fresh)
                                        status.counts[api.name] = got.addAndGet(fresh.size)
                                        if (answered.get()) publish()
                                    }
                                    val ownIsKitsu = ext.scheme in ExternalIds.ANIME_SCHEMES
                                    val ownPath: suspend () -> Unit = {
                                        // one side failing does not stop the other side
                                        try { take(buildGenericStreamsForApi(api, type, id, title, year, animeForSearch, animeTarget), "own") }
                                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                        catch (e: Throwable) { ServerState.debug("[${api.name}] own id: ${e.message}") }
                                    }
                                    val otherPath: suspend () -> Unit = {
                                        val altId = altIdJob.await()
                                        if (altId != null) {
                                            val altType = if (altId.startsWith("tmdb:") || altId.startsWith("tt")) "series" else type
                                            take(runCatching { buildGenericStreamsForApi(api, altType, altId, title, year, animeForSearch, null) }.getOrDefault(emptyList()), "other")
                                        }
                                    }
                                    val strictKey = strictKitsuJob.await()
                                    if (strictKey != null) {
                                        val r = try { kitsuExactStreams(api, strictKey, title, year) }
                                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (e: Throwable) { ServerState.debug("[${api.name}] kitsu: ${e.message}"); null }
                                        if (r == null) StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No entry named like Kitsu's names", noMatch = true)
                                        else take(r, "kitsu")
                                    }
                                    // The Kitsu side first; the other side only when the Kitsu side found nothing in this extension
                                    else if (ownIsKitsu) { ownPath(); if (got.get() == 0) otherPath() }
                                    else {
                                        val alt = altIdJob.await()
                                        if (alt != null && alt.startsWith("kitsu:")) { otherPath(); if (got.get() == 0) ownPath() }
                                        else { ownPath(); otherPath() }
                                    }
                                    status.counts[api.name] = got.get()
                                } catch (e: Throwable) {
                                    // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    ServerState.warn("[${api.name}] Generic stream error: ${e.message}")
                                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message ?: "Stream error")
                                }
                            }
                            if (res == null) {
                                ServerState.debug("[STREAM_TIMEOUT] [${api.name}] Provider timed out after ${PROVIDER_TIMEOUT_MS}ms")
                                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Timed out after ${PROVIDER_TIMEOUT_MS}ms")
                            }
                        }
                        if (answered.get() && accumulated.isNotEmpty()) publish()
                    }
                } + UpstreamAddons.active().map { up ->
                    streamSearchScope.launch {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            try {
                                val got = UpstreamAddons.fetch(up, type, id)
                                accumulated.addAll(got)
                                status.counts[up.name] = got.size
                                ServerState.info("[Upstream] ${up.name}: ${got.size} stream(s)")
                            } catch (e: Throwable) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                ServerState.warn("[Upstream] ${up.name}: ${e.message}")
                            }
                        }
                        if (answered.get() && accumulated.isNotEmpty()) publish()
                    }
                }

                jobs.forEach { j -> j.invokeOnCompletion { status.done.incrementAndGet() } }
                streamSearchScope.launch { jobs.joinAll(); status.finished = true }

                // The anime lookup missed its time budget: the extensions above were searched with the plain title only.
                // When the lookup does finish (it keeps running detached), search every extension again with all the
                // names / the exact season entry and add whatever is new - to this answer if it is still open, else to the cache.
                if (animeTimedOut) {
                    streamSearchScope.launch {
                        // strict Kitsu mode: the extensions already searched Kitsu's exact names, no similar-name search after
                        if (strictKitsuJob.await() != null) return@launch
                        val late = withTimeoutOrNull(STREAM_DEADLINE_MS) { animeResolution.await() } ?: return@launch
                        val (lateNames, lateTarget) = late
                        val lateSearch = lateTarget?.entry?.copy(titles = (lateTarget.entry.titles + (lateNames?.titles.orEmpty())).distinct()) ?: lateNames
                            ?: return@launch // not an anime after all: nothing more to find
                        ServerState.info("[Streams] '$title': anime lookup finished late - searching the extensions again with the anime names")
                        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                        accumulated.forEach { st -> (st.url ?: st.infoHash ?: st.externalUrl)?.let { seen.add(it) } }
                        activePlugins.map { api ->
                            launch {
                                sem.withPermit {
                                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                        try {
                                            val more = doBuildGenericStreamsForApi(api, type, id, title, year, lateSearch, lateTarget)
                                            accumulated.addAll(more.filter { st -> (st.url ?: st.infoHash ?: st.externalUrl)?.let { seen.add(it) } ?: true })
                                        } catch (e: Throwable) {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        }
                                    }
                                }
                            }
                        }.joinAll()
                        if (accumulated.isNotEmpty()) publish()
                    }
                }

                // Answer when everything is done, or once STREAM_SOFT_DEADLINE_MS has passed and
                // there is something to show; with nothing yet, wait up to STREAM_DEADLINE_MS.
                val waitAll = streamPrefs.waitForAll
                var firstFoundAt = 0L
                withTimeoutOrNull(STREAM_DEADLINE_MS) {
                    while (jobs.any { it.isActive }) {
                        val now = System.currentTimeMillis()
                        val t = now - started
                        if (accumulated.isNotEmpty() && firstFoundAt == 0L) firstFoundAt = now
                        // Something found: answer once 8 s have passed and the first links are 4 s old (others arriving
                        // right behind it are included); the rest keeps filling the cache for the next reload
                        if (!waitAll && firstFoundAt != 0L && t >= STREAM_SOFT_DEADLINE_MS && now - firstFoundAt >= 4_000L) break
                        // Never later than ANSWER_CAP after the request arrived (searching the title already took time) -
                        // but the providers always get STREAM_MIN_WINDOW_MS first: when the title lookup used up the
                        // cap, this used to break on the very first pass and answer 0 links with every provider
                        // still running. The hard stop leaves time to send before RESPONSE_CAP_MS cuts the request off.
                        val sinceRequest = now - (streamRequestStart["$type:$id"]?.takeIf { now - it < 120_000L } ?: started)
                        if (sinceRequest >= RESPONSE_CAP_MS - 2_000L) break
                        if (sinceRequest >= STREAM_ANSWER_CAP_MS && t >= STREAM_MIN_WINDOW_MS) break
                        delay(200)
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                val remaining = jobs.count { it.isActive }
                if (remaining > 0 && elapsed < STREAM_DEADLINE_MS) {
                    // Early answer: the rest keep going until STREAM_DEADLINE_MS from the start —
                    // each one that finishes adds its links to the cached list — then all are killed.
                    answered.set(true)
                    streamSearchScope.launch {
                        withTimeoutOrNull(STREAM_DEADLINE_MS - elapsed) { jobs.joinAll() }
                        val killed = jobs.count { it.isActive }
                        jobs.forEach { it.cancel() }
                        if (accumulated.isNotEmpty()) publish()
                        ServerState.info("[Streams] '$title': ${accumulated.size} streams after ${(System.currentTimeMillis() - started) / 1000}s (background done, $killed slow provider(s) stopped)")
                    }
                } else if (remaining > 0) {
                    jobs.forEach { it.cancel() }
                }
                val altId = altIdRef.get()
                if (altId != null) ServerState.info("[Streams] '$title': $id found ${pathCounts["own"] ?: 0} link(s), $altId found ${pathCounts["other"] ?: 0} (so far)")
                ServerState.info(
                    "[Streams] '$title': answered with ${accumulated.size} streams in ${elapsed / 1000.0}s, " +
                        "${activePlugins.size - remaining}/${activePlugins.size} providers done" +
                        (if (remaining > 0 && elapsed < STREAM_DEADLINE_MS) ", $remaining still loading into cache" else "")
                )
                // Cached even when partial: reloads get it at once while the cache keeps filling
                val answer = withLastGood(type, id, title, sortStreamsByQuality(accumulated),
                    if (remaining > 0) "$remaining of ${activePlugins.size} extensions had not answered yet" else "every extension answered")
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(
                    answer,
                    cacheable = answer.isNotEmpty(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerState.warn("Generic stream resolve error for $id: ${e.stackTraceToString()}")
                com.cncverse.stremiobridge.cache.StreamCacheManager.FetchResult(emptyList())
            }
        }
    }

    private class GoodList(val at: Long, val streams: List<StremioStream>)
    /** "type:id" -> the biggest recent list of links (any extension set / profile): a search that finds far fewer keeps these. */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, GoodList>()
    private const val LAST_GOOD_MS = 30 * 60_000L

    /**
     * A new list that is much smaller than the one found for the same title a short while ago (sites slow or rate-limiting,
     * extensions reloading, the saved results cleared) keeps the earlier links too, so a reload never drops from 45 links to 3.
     * The bigger list is remembered for [LAST_GOOD_MS]. [why] (when given) says why the new list is short, for the log.
     */
    private fun withLastGood(type: String, id: String, title: String, now: List<StremioStream>, why: String?): List<StremioStream> {
        // "k2": lists kept before the strict Kitsu matching (with other seasons' episodes) are never added back
        val key = "k2:$type:$id"
        val t = System.currentTimeMillis()
        val prev = lastGood[key]?.takeIf { t - it.at < LAST_GOOD_MS }
        var out = now
        if (prev != null && prev.streams.size >= 8 && now.size < prev.streams.size / 2) {
            val have = now.mapNotNull { it.url ?: it.infoHash ?: it.externalUrl }.toHashSet()
            val kept = prev.streams.filter { s -> (s.url ?: s.infoHash ?: s.externalUrl)?.let { have.add(it) } ?: false }
            if (kept.isNotEmpty()) {
                if (why != null) ServerState.warn("[Streams] '$title': this search found only ${now.size} link(s), ${prev.streams.size} were found " +
                    "${(t - prev.at) / 60_000} min ago ($why) - the earlier ${kept.size} are kept")
                out = sortStreamsByQuality(now + kept)
            }
        }
        if (out.size >= (prev?.streams?.size ?: 0)) {
            if (lastGood.size > 300) lastGood.clear()
            lastGood[key] = GoodList(t, out)
        }
        return out
    }

    // ── Generic per-provider stream fetch (shared by buildStreams + buildStreamsForApi) ─

    /**
     * Resolves streams from a single [api] for a generic title/year.
     * Reuses already-resolved title & year to avoid redundant TMDB requests.
     */
    internal suspend fun buildGenericStreamsForApi(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?,
        anime: AnimeInfo? = null,
        target: AnimeTarget? = null,
    ): List<StremioStream> {
        if (id.startsWith("probe_")) {
            return doBuildGenericStreamsForApi(api, type, id, title, year, anime, target)
        }
        val providerCacheKey = "stream:provider:${api.internalName}:$type:$id"
        return com.cncverse.stremiobridge.cache.StreamCacheManager.getOrFetch(providerCacheKey, api.internalName) {
            doBuildGenericStreamsForApi(api, type, id, title, year, anime, target)
        }
    }

    /** A matched movie / series page of one extension: the result, its page, the link data to load and the episode label. */
    private class GenericMatch(val match: SearchResult, val info: MediaInfo, val dataUrl: String, val episodeLabel: String?)

    private fun episodeLabelOf(ep: MediaInfoEpisode): String? {
        val numbers = listOfNotNull(ep.season?.let { "S$it" }, (ep.episode ?: TitleMatch.episodeNumberFromName(ep.name))?.let { "E$it" }).joinToString("")
        return listOfNotNull(numbers.takeIf { it.isNotEmpty() }, ep.name?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** id -> other titles (TMDB alternative titles, Latin script), fetched once per id for the extra searches. */
    private val genericAltTitles = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<String>>>()

    private suspend fun altTitlesFor(type: String, id: String): List<String> {
        val ext = ExternalIds.parse(id) ?: return emptyList()
        if (ext.scheme !in setOf("imdb", "tmdb", "tvdb")) return emptyList()
        val key = "$type:${ext.scheme}:${ext.key}"
        if (genericAltTitles.size > 2_000) genericAltTitles.clear()
        val job = genericAltTitles.getOrPut(key) {
            streamSearchScope.async {
                runCatching { if (type == "movie") tmdbMovieAltTitles(ext) else tmdbAlternativeTitles(ext) }.getOrDefault(emptyList())
            }
        }
        return withTimeoutOrNull(4_000L) { job.await() } ?: emptyList()
    }

    /** TMDB's alternative titles of a movie (Latin script), like [tmdbAlternativeTitles] for series. */
    private suspend fun tmdbMovieAltTitles(ext: ExternalId): List<String> {
        val movieId = when (ext.scheme) {
            "tmdb" -> ext.key
            "imdb" -> fetchTmdbJson("find/${ext.key}?external_source=imdb_id")
                ?.get("movie_results")?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject }
                ?.get("id")?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: return emptyList()
        val details = fetchTmdbJson("movie/$movieId")
        val original = details?.get("original_title")?.jsonPrimitive?.contentOrNull
        val results = fetchTmdbJson("movie/$movieId/alternative_titles")?.get("titles") as? kotlinx.serialization.json.JsonArray
        val names = (listOfNotNull(original) + results.orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull })
            .map { it.trim() }
            .filter { n -> n.length >= 3 && n.count { c -> c.code < 0x250 } * 10 >= n.length * 8 }
            .distinctBy { AnimeMatcher.normalize(it) }
        return names.take(10)
    }

    /**
     * Finds the movie / episode in one extension that has its own catalog (no TMDB): the page remembered from an earlier
     * match first, then the search results scored by [TitleMatch.score] (today's picks are kept as candidates too), up to 4
     * pages opened two at a time. A page is accepted when its IMDb / TMDB id is the requested one, rejected when that id is
     * another one; without an id a movie page must have about the same year and not be a series, and a series page must
     * have the episode (the season can come from the entry's title). Nothing fits: up to two other spellings are searched.
     */
    private suspend fun matchGeneric(
        api: MainApiWrapper, type: String, id: String, title: String, year: Int?, firstResults: List<SearchResult>,
    ): GenericMatch? {
        val cacheKey = api.internalName
        val parsed = ExternalIds.parse(id)
        val season = if (type == "series") parsed?.season else null
        val episode = if (type == "series") parsed?.episode else null
        val isEpisode = season != null && episode != null
        val wantImdb = parsed?.takeIf { it.scheme == "imdb" }?.key
        val wantTmdb = parsed?.takeIf { it.scheme == "tmdb" }?.key
        val memKey = MatchMemory.key(api.internalName, id, season)
        val tried = HashSet<String>()
        var noEpisode = false

        suspend fun loadInfo(url: String): MediaInfo? = try {
            SearchLoadCache.getLoad(cacheKey, url)?.info
                ?: api.load(url)?.also { SearchLoadCache.putLoad(cacheKey, url, it) }
        } catch (e: Throwable) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.debug("[${api.name}] load failed for $url: ${e.message}")
            null
        }

        fun idVerdict(mi: MediaInfo): Boolean? {
            if (wantImdb != null && mi.imdbId != null) return mi.imdbId.equals(wantImdb, true)
            if (wantTmdb != null && mi.tmdbId != null) return mi.tmdbId == wantTmdb
            return null
        }

        fun accept(c: SearchResult, mi: MediaInfo): GenericMatch? {
            val verdict = idVerdict(mi)
            if (verdict == false) {
                ServerState.debug("[${api.name}] '${c.name}' is another title (id ${mi.imdbId ?: mi.tmdbId})")
                return null
            }
            if (!isEpisode) {
                if (verdict == null && type == "movie") {
                    val my = mi.year
                    if (year != null && my != null && kotlin.math.abs(my - year) > 1) {
                        ServerState.debug("[${api.name}] '${c.name}' is from $my, not $year")
                        return null
                    }
                    val t = mi.type.lowercase()
                    if ((mi.episodes?.size ?: 0) > 1 && (t.contains("series") || t.contains("anime") || t.contains("drama") || t.contains("cartoon"))) {
                        ServerState.debug("[${api.name}] '${c.name}' is a series page")
                        return null
                    }
                }
                return GenericMatch(c, mi, mi.dataUrl, null)
            }
            // a post of several seasons ("S01-S03") is not one season's entry
            val entrySeason = if (TitleMatch.seasonRange(c.name) != null) null
                else TitleMatch.seasonFromTitle(c.name) ?: TitleMatch.seasonFromTitle(mi.name)
            val ep = TitleMatch.findSeriesEpisode(mi.episodes, season!!, episode!!, entrySeason, absoluteEpisodes[id])
            if (ep == null) {
                noEpisode = true
                ServerState.debug("[${api.name}] '${c.name}' has no S${season}E$episode")
                return null
            }
            return GenericMatch(c, mi, ep.dataUrl, episodeLabelOf(ep))
        }

        suspend fun tryCandidates(cands: List<SearchResult>, how: String, budget: Int): GenericMatch? {
            val todo = cands.filter { it.url !in tried }.take(budget)
            tried.addAll(todo.map { it.url })
            for (pair in todo.chunked(2)) {
                val infos = coroutineScope { pair.map { c -> async { c to loadInfo(c.url) } }.awaitAll() }
                val accepted = infos.mapNotNull { (c, mi) -> mi?.let { accept(c, it)?.let { m -> m to idVerdict(it) } } }
                val pick = accepted.firstOrNull { it.second == true } ?: accepted.firstOrNull()
                if (pick != null) {
                    val m = pick.first
                    ServerState.debug("[${api.name}] Match: '${m.match.name}' ($how${if (pick.second == true) ", id confirmed" else ""})")
                    MatchMemory.put(memKey, m.match.url, m.match.name)
                    return m
                }
            }
            return null
        }

        // 1. the page that had this title / season last time
        MatchMemory.get(memKey)?.let { (url, name) ->
            tryCandidates(listOf(SearchResult(name, url, null, type, null)), "remembered page", 1)?.let { return it }
            MatchMemory.forget(memKey)
        }

        // 2. the scored results of the first search, plus today's picks (exact "<title> season N" post, exact / partial title, fuzzy)
        // every result of every search, for the CineStream fallback pass at the end
        val seenResults = LinkedHashMap<String, SearchResult>()
        fun candidatesOf(results: List<SearchResult>): List<SearchResult> {
            results.forEach { seenResults.putIfAbsent(it.url, it) }
            val ranked = TitleMatch.rank(results, title, year, season)
            val legacy = buildList {
                if (season != null) {
                    val wanted = normalizeTitle(title)
                    val seasonRx = Regex("^" + Regex.escape(wanted) + " (?:season 0*$season|s0*$season)(?: |$)")
                    results.find { r -> seasonRx.containsMatchIn(normalizeTitle(r.name)) }?.let { add(it) }
                }
                pickBestMatch(results, title, year)?.let { add(it) }
                AnimeMatcher.pickFuzzy(results, title, year, season)?.let { add(it) }
            }
            return (ranked + legacy).distinctBy { it.url }
        }
        var budget = 5
        val first = candidatesOf(firstResults)
        tryCandidates(first, "search '$title'", budget)?.let { return it }
        budget -= minOf(budget, first.size)

        // 3. other spellings: no punctuation, no subtitle, other titles, title + year (at most two more searches)
        // TMDB's original and other titles too (CineStream searches the original title when the English one misses)
        val variants = TitleMatch.queryVariants(title, year, altTitlesFor(type, id), max = 3)
        for (q in variants) {
            if (budget <= 0 && tried.isNotEmpty()) break
            val more = try {
                SearchLoadCache.getSearch(cacheKey, q)
                    ?: api.search(q).also { SearchLoadCache.putSearch(cacheKey, q, it) }
            } catch (e: Throwable) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                emptyList()
            }
            val cands = candidatesOf(more).filter { it.url !in tried }
            if (cands.isEmpty()) continue
            tryCandidates(cands, "search '$q'", budget.coerceAtLeast(2))?.let { return it }
            budget -= minOf(budget, cands.size)
        }

        // 4. fallback: CineStream's looser rules over everything the searches returned (same letters without spaces,
        //    the title inside a longer post name, a shorter name with the same year, season packs "S01-S04")
        val loose = TitleMatch.rank(seenResults.values.toList(), title, year, season, loose = true).filter { it.url !in tried }
        if (loose.isNotEmpty()) {
            ServerState.debug("[${api.name}] No page by the TMDB title - trying CineStream-style matches: ${loose.take(3).joinToString { "'${it.name}'" }}")
            tryCandidates(loose, "CineStream-style match", 3)?.let { return it }
        }

        if (tried.isEmpty()) {
            ServerState.debug("[${api.name}] No result matches '$title'" + (year?.let { " ($it)" } ?: ""))
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No result matches '$title'", noMatch = true)
        } else if (noEpisode) {
            ServerState.debug("[STREAM_EMPTY] [${api.name}] Episode S${season}E$episode not found on ${tried.size} page(s)")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Episode S${season}E$episode not found", noMatch = true)
        } else {
            ServerState.debug("[${api.name}] ${tried.size} page(s) opened, none is '$title'")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No page of '$title' (${tried.size} checked)", noMatch = true)
        }
        return null
    }

    /** Lower-cases and strips punctuation/extra spaces so "Spider-Man: No Way Home" == "spider man no way home". */
    private fun normalizeTitle(s: String): String = s.lowercase()
        .replace("&", " and ")
        .replace(TITLE_PUNCT, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private val IMDB_ID = Regex("tt[0-9]+")
    private val TITLE_PUNCT = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")

    /**
     * Picks the search result for [title]/[year], in order of confidence:
     *  1. exact title + same year
     *  2. exact title, result has no year
     *  3. partial title (result contains the whole title as words) + same year
     *  4. partial title, result has no year
     * When TMDB gave no year, the year condition is dropped. A result whose
     * year is known but differs is never accepted, and a result whose title
     * doesn't match is never picked (no "first result" fallback) — so a
     * provider without the title returns nothing instead of a wrong film.
     */
    private fun pickBestMatch(results: List<SearchResult>, title: String, year: Int?): SearchResult? {
        val wanted = normalizeTitle(title)
        if (wanted.isEmpty()) return null
        val candidates = results.map { it to normalizeTitle(it.name) }
        val exact = candidates.filter { (_, n) -> n == wanted }.map { it.first }
        // Whole-word containment, result ⊇ title only: "dune part two 2024 hindi"
        // matches "Dune Part Two", but "Up" never matches "Upgrade" and a
        // shorter "Dune" never stands in for "Dune Part Two".
        val partial = candidates.filter { (_, n) ->
            n != wanted && " $n ".contains(" $wanted ")
        }.map { it.first }

        if (year == null) return exact.firstOrNull() ?: partial.firstOrNull()
        return exact.firstOrNull { it.year == year }
            ?: exact.firstOrNull { it.year == null }
            ?: partial.firstOrNull { it.year == year }
            ?: partial.firstOrNull { it.year == null }
    }

    /** Marks a request whose whole time (search + load + links) is timed once, so loadLinksAll does not time it again. */
    internal class WholeRequestTimed : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<WholeRequestTimed>
    }

    /** One real answer of an extension, timed from search to links (feeds the report card). A cancelled request is not timed. */
    private suspend fun doBuildGenericStreamsForApi(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?,
        anime: AnimeInfo? = null,
        target: AnimeTarget? = null,
    ): List<StremioStream> {
        val t0 = System.currentTimeMillis()
        val out = kotlinx.coroutines.withContext(WholeRequestTimed()) {
            doBuildGenericStreamsForApiInner(api, type, id, title, year, anime, target)
        }
        runCatching { StreamTracker.recordTime(api.pluginInternalName, api.internalName, System.currentTimeMillis() - t0, api.name) }
        return out
    }

    private suspend fun doBuildGenericStreamsForApiInner(
        api: MainApiWrapper,
        type: String,
        id: String,
        title: String,
        year: Int?,
        anime: AnimeInfo? = null,
        target: AnimeTarget? = null,
    ): List<StremioStream> {
        // Anime with a Kitsu id: the entry whose name is exactly one of Kitsu's names for it, and Kitsu's episode number
        // in it - no guessing from TMDB seasons or similar names. Only when that finds nothing, the other ways below.
        if (type != "movie" && !id.startsWith("probe_")) kitsuExactStreams(api, id, title, year)?.let { return it }

        ServerState.debug("[${api.name}] Searching for '$title'")
        val cacheKey = api.internalName
        val searchResults = try {
            SearchLoadCache.getSearch(cacheKey, title)
                ?: api.search(title).also { SearchLoadCache.putSearch(cacheKey, title, it) }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[STREAM_ERROR] [${api.name}] Search failed: ${e.message}")
            if (anime == null) {
                StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Search error: ${e.message}")
                return emptyList()
            }
            // Anime: one spelling can break a site's search (punctuation, long names) - the other names are still tried
            emptyList<SearchResult>()
        }
        ServerState.debug("[${api.name}] Found ${searchResults.size} results")

        // Movies and series: every fitting result is scored and up to 4 are opened, the extension's own IMDb / TMDB ids
        // confirm a page, and a page without the episode makes way for the next one
        if (anime == null && !id.startsWith("probe_")) {
            val m = matchGeneric(api, type, id, title, year, searchResults) ?: return emptyList()
            return linksFor(api, m.match.name, m.dataUrl, m.episodeLabel, title, year)
        }

        val requestedSeason = if (type == "series" && id.contains(":")) ExternalIds.parse(id)?.season else null

        // tt / tmdb / tvdb ids carry the real season; kitsu / MAL / AniList ids name one season entry already
        val parsedId = ExternalIds.parse(id)
        val animeSeason = if (target != null) target.index + 1
            else if (anime != null && parsedId != null && parsedId.scheme !in ExternalIds.ANIME_SCHEMES) parsedId.season else null
        val pool = ArrayList<SearchResult>(searchResults)

        var bestMatch = (if (anime != null) {
            // Anime: fuzzy match against every known title (romaji, English, synonyms) and the season;
            // results of all spellings are pooled so the best one wins, not the first one over the bar
            var found = AnimeMatcher.pickBest(pool, anime, animeSeason)
            var foundScore = found?.let { AnimeMatcher.score(it, anime, animeSeason) } ?: 0.0
            if (foundScore < 0.92) {
                for (term in AnimeMatcher.searchTerms(anime, animeSeason).filter { !it.equals(title, true) }) {
                    val more = try {
                        SearchLoadCache.getSearch(cacheKey, term)
                            ?: api.search(term).also { SearchLoadCache.putSearch(cacheKey, term, it) }
                    } catch (e: Throwable) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        emptyList()
                    }
                    more.filter { m -> pool.none { it.url == m.url } }.forEach { pool += it }
                    found = AnimeMatcher.pickBest(pool, anime, animeSeason)
                    foundScore = found?.let { AnimeMatcher.score(it, anime, animeSeason) } ?: 0.0
                    if (foundScore >= 0.92) break
                }
            }
            found
        } else if (requestedSeason != null) {
            // Sites often list each season separately ("The Boys Season 2", sometimes with that
            // season's year), which strict title+year matching rejects. Accept those only when the
            // result is exactly "<title> season N" / "<title> sN" at the start — so "Season 1"
            // never matches "Season 10" and spin-offs ("The Boys Presents …") never match.
            val wanted = normalizeTitle(title)
            val seasonRx = Regex("^" + Regex.escape(wanted) + " (?:season 0*$requestedSeason|s0*$requestedSeason)(?: |$)")
            searchResults.find { r -> seasonRx.containsMatchIn(normalizeTitle(r.name)) }
                ?: pickBestMatch(searchResults, title, year)
                ?: AnimeMatcher.pickFuzzy(searchResults, title, year, requestedSeason)
        } else {
            pickBestMatch(searchResults, title, year)
        } ?: AnimeMatcher.pickFuzzy(searchResults, title, year, requestedSeason)
        ) ?: (if (anime == null) {
            // Nothing close in the first answer: type the title another way (no subtitle, no "The") and try fuzzy again
            var alt: SearchResult? = null
            for (q in AnimeMatcher.altQueries(title)) {
                val more = try {
                    SearchLoadCache.getSearch(cacheKey, q)
                        ?: api.search(q).also { SearchLoadCache.putSearch(cacheKey, q, it) }
                } catch (e: Throwable) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    emptyList()
                }
                alt = pickBestMatch(more, title, year) ?: AnimeMatcher.pickFuzzy(more, title, year, requestedSeason)
                if (alt != null) break
            }
            alt
        } else null) ?: run {
            ServerState.debug("[${api.name}] No result matches '$title'" + (year?.let { " ($it)" } ?: ""))
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No result matches '$title'", noMatch = true)
            return emptyList()
        }

        ServerState.debug("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
        val cachedLoad = SearchLoadCache.getLoad(cacheKey, bestMatch.url)
        var mediaInfo = try {
            if (cachedLoad != null) {
                cachedLoad.info
            } else {
                val fresh = api.load(bestMatch.url)
                SearchLoadCache.putLoad(cacheKey, bestMatch.url, fresh)
                fresh
            }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ServerState.warn("[STREAM_ERROR] [${api.name}] MediaInfo load failed: ${e.message}")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Load error: ${e.message}")
            return emptyList()
        } ?: run {
            ServerState.debug("[STREAM_EMPTY] [${api.name}] MediaInfo load returned null for ${bestMatch.url}")
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "MediaInfo null for match")
            return emptyList()
        }

        var mergedList = false
        // Season check by episode count: the entry must have about as many episodes as the season we want
        if (anime != null && parsedId != null) {
            val expected = ArrayList<Int>()
            // Running-number lists: a site that merges seasons lists as many episodes as the franchise has so far
            val expectedMerged = ArrayList<Int>()
            if (target != null) {
                // the entry is exact (own AniList id): its own episode count, or the running total when a site merges seasons
                anime.episodeCount?.let { expected += it }
                var cum = 0
                for (i in 0..target.index) { cum += target.counts.getOrNull(i) ?: break; if (i >= 1) expectedMerged += cum }
            } else if (parsedId.scheme in ExternalIds.ANIME_SCHEMES) {
                anime.episodeCount?.let { expected += it }
            } else {
                animeSeason?.let { sn -> tmdbSeasonEpisodeCount(parsedId, sn)?.let { expected += it } }
                if (animeSeason == null || anime.seasonHint == animeSeason) anime.episodeCount?.let { expected += it }
            }
            val running = anime.year?.let { it >= java.time.Year.now().value - 1 } ?: true
            // The entry's own AniList / MyAnimeList id, when the extension reports it: the exact season entry
            fun idVerdict(mi: MediaInfo): Boolean? {
                if (anime.aniListId != null && mi.aniListId != null) return anime.aniListId == mi.aniListId
                if (anime.malId != null && mi.malId != null) return anime.malId == mi.malId
                return null
            }
            fun fits(mi: MediaInfo): Boolean {
                idVerdict(mi)?.let { return it }
                val eps = mi.episodes.orEmpty()
                if (eps.isEmpty() || eps.any { (it.season ?: 1) > 1 }) return true
                val n = eps.mapNotNull { it.episode }.distinct().size.takeIf { it > 0 } ?: eps.size
                if (AnimeMatcher.countFits(n, expected, running, parsedId.episode)) return true
                if (expectedMerged.any { m -> n == m }) { mergedList = true; return true }
                return false
            }
            if ((expected.isNotEmpty() || idVerdict(mediaInfo) != null) && !fits(mediaInfo)) {
                ServerState.debug("[${api.name}] '${bestMatch.name}' has ${mediaInfo.episodes?.size} episodes, wanted about $expected - trying other entries")
                var replaced = false
                // at most 3 other entries are opened: each is a whole page (1000+ episodes for a long show)
                for (c in AnimeMatcher.rank(pool, anime, animeSeason).filter { it.url != bestMatch.url }.take(3)) {
                    val mi = try {
                        SearchLoadCache.getLoad(cacheKey, c.url)?.info
                            ?: api.load(c.url)?.also { SearchLoadCache.putLoad(cacheKey, c.url, it) }
                    } catch (e: Throwable) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
                    if (mi != null && fits(mi)) { bestMatch = c; mediaInfo = mi; replaced = true; break }
                }
                if (!replaced) {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] no entry has the episode count of the wanted season ($expected)")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "No entry with the wanted season's episode count", noMatch = true)
                    return emptyList()
                }
            }
        }

        var dataUrlToLoad = mediaInfo.dataUrl
        // The episode the extension's link is for, as the extension lists it ("S2E2 · Episode name"), shown next to the file name
        var matchedEpisode: String? = null
        fun episodeLabel(ep: MediaInfoEpisode): String? {
            val numbers = listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "E$it" }).joinToString("")
            return listOfNotNull(numbers.takeIf { it.isNotEmpty() }, ep.name?.trim()?.takeIf { it.isNotEmpty() })
                .joinToString(" · ").takeIf { it.isNotEmpty() }
        }
        if (anime != null) {
            val parsed = ExternalIds.parse(id)
            // TMDB numbering was translated to the AniList entry: its own episode number, or the running number on a site that merges seasons
            val epNo = target?.let { if (mergedList) it.absolute else it.local } ?: parsed?.episode
            val epList = mediaInfo.episodes
            if (!epList.isNullOrEmpty()) {
                // A later-season entry (kitsu "Season 2") numbers its own episodes from 1; a franchise page lists seasons
                val wantSeason = if (target != null) (if (mergedList) 1 else target.index + 1)
                    else anime.seasonHint?.takeIf { it > 1 } ?: parsed?.season
                // A page in TMDB's own season numbering (several seasons the size of TMDB's), or one running list of the whole
                // show: the request's TMDB numbers / the running number fit it better than the AniList entry's own numbers
                val style = if (parsed != null && parsed.scheme in setOf("imdb", "tmdb", "tvdb") && parsed.season != null && parsed.episode != null)
                    TitleMatch.numbering(epList, tmdbAllSeasonCounts(parsed)) else TitleMatch.Numbering.UNKNOWN
                // A later-season Kitsu / MAL / AniList entry ("... Season 2") that only matched the first season's page:
                // its episode 6 would be the first season's episode 6 - not this entry
                if (target == null && parsed != null && parsed.scheme in ExternalIds.ANIME_SCHEMES && wantSeason != null && wantSeason > 1 &&
                    epList.none { it.season == wantSeason }) {
                    fun seasonOfName(n: String?): Int? {
                        val x = AnimeMatcher.normalize(n ?: return null)
                        return AnimeMatcher.seasonNumber(x)
                            ?: Regex("(?<!\\bno) ([2-9])$").find(x)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    val entrySeason = seasonOfName(bestMatch.name) ?: seasonOfName(mediaInfo.name)
                    if (entrySeason != wantSeason) {
                        ServerState.debug("[STREAM_EMPTY] [${api.name}] '${bestMatch.name}' is not season $wantSeason")
                        StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Only another season's entry found", noMatch = true)
                        return emptyList()
                    }
                }
                var ep = when (style) {
                    TitleMatch.Numbering.TMDB -> AnimeEpisodes.pick(epList, parsed!!.season, parsed.episode!!)
                    TitleMatch.Numbering.ABSOLUTE -> absoluteEpisodes[id]?.let { abs -> AnimeEpisodes.pick(epList, 1, abs) }
                    else -> null
                }?.also { ServerState.debug("[${api.name}] page numbered like ${style.name.lowercase()}: episode '${it.name}'") }
                    ?: if (epNo != null) AnimeEpisodes.pick(epList, wantSeason, epNo) else epList.first()
                if (ep == null && target != null) {
                    // Last resorts, so a correct entry still gives results: TMDB's own numbers, then the running number
                    ep = parsed?.season?.let { sn -> parsed.episode?.let { en -> AnimeEpisodes.pick(epList, sn, en) } }
                        ?: AnimeEpisodes.pick(epList, 1, target.absolute)
                }
                if (ep == null) {
                    // a site that numbers the whole show 1, 2, ... 1000+ (One Piece): the running number from TMDB's season counts
                    absoluteEpisodes[id]?.takeIf { it != epNo }?.let { abs ->
                        ep = AnimeEpisodes.pick(epList, 1, abs)
                        if (ep != null) ServerState.debug("[${api.name}] episode $epNo not listed - running number $abs found")
                    }
                }
                if (ep == null) {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] Episode $epNo not found")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Episode $epNo not found", noMatch = true)
                    return emptyList()
                }
                dataUrlToLoad = ep.dataUrl
                matchedEpisode = episodeLabel(ep)
                ServerState.debug("[${api.name}] Anime episode $epNo -> '${ep.name}'")
            }
        } else if (type == "series" && id.contains(":")) {
            val parsed  = ExternalIds.parse(id)
            val season  = parsed?.season
            val episode = parsed?.episode
            if (season != null && episode != null) {
                // Anime extensions often leave the season empty: that is season 1
                val ep = mediaInfo.episodes?.find { (it.season ?: 1) == season && it.episode == episode }
                    // anime sites number a long show 1, 2, ... 1000+ in one list: TMDB's season counts give the running number
                    ?: absoluteEpisodes[id]?.takeIf { season > 1 }?.let { abs -> mediaInfo.episodes?.find { (it.season ?: 1) == 1 && it.episode == abs } }
                        ?.also { ServerState.debug("[${api.name}] S${season}E${episode} found as running episode ${absoluteEpisodes[id]}") }
                if (ep != null) {
                    dataUrlToLoad = ep.dataUrl
                    matchedEpisode = episodeLabel(ep)
                    ServerState.debug("[${api.name}] Found episode S${season}E${episode}")
                } else {
                    ServerState.debug("[STREAM_EMPTY] [${api.name}] Episode S${season}E${episode} not found")
                    StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Episode S${season}E${episode} not found", noMatch = true)
                    return emptyList()
                }
            }
        }
        return linksFor(api, bestMatch.name, dataUrlToLoad, matchedEpisode, title, year)
    }

    /** The links of the matched page / episode, labelled with the entry they came from. */
    /** "S01E03", "S1 E3", "S01.E03", "S01E01-E08" / "S01E01-08" (a range); not "S01" alone (a season pack). */
    private val FILE_EPISODE_RX = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,4})((?:[ ._]?e\\d{1,4})*)(?:[ ._]?[-–~][ ._]?e?(\\d{1,4}))?(?![0-9])")
    private val EXTRA_E_RX = Regex("(?i)e(\\d{1,4})")
    /** "1x05" (not "1920x1080"). */
    private val FILE_X_RX = Regex("(?<![0-9a-zA-Z])(\\d{1,2})x(\\d{2,3})(?![0-9])")

    /**
     * The links of a series episode whose own name (the release / file name the extension gives) names another episode
     * of the show - MoviesDrive hands out "VisionQuest.S01E03..." for S1E5 while its page only has episodes 1-4. A link that
     * names no episode, a season pack, or a range that holds the episode is kept. The running number (One Piece "S01E153")
     * counts as the episode too.
     */
    private fun dropOtherEpisodes(apiName: String, id: String, ext: ExternalId, list: List<StremioStream>): List<StremioStream> {
        if (ext.scheme !in setOf("imdb", "tmdb", "tvdb")) return list
        val season = ext.season ?: return list
        val episode = ext.episode ?: return list
        val abs = absoluteEpisodes[id]
        fun fits(s: Int, e1: Int, e2: Int?, extra: List<Int> = emptyList()): Boolean {
            val range = if (e2 != null && e2 > e1) e1..e2 else e1..e1
            if (s == season && (episode in range || episode in extra)) return true
            // a show numbered in one run: the running number in season 1
            if (abs != null && s <= 1 && (abs in range || abs in extra)) return true
            return false
        }
        val out = list.filter { st ->
            val names = listOfNotNull(st.info?.linkName, st.behaviorHints?.filename,
                st.url?.substringBefore('?')?.substringAfterLast('/')?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) })
            var named = false
            var ok = false
            for (n in names) {
                for (m in FILE_EPISODE_RX.findAll(n)) {
                    named = true
                    // "S01E04E05": every episode the file holds
                    val extra = EXTRA_E_RX.findAll(m.groupValues[3]).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    if (fits(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[4].toIntOrNull(), extra)) ok = true
                }
                if (!named) for (m in FILE_X_RX.findAll(n)) {
                    named = true
                    if (fits(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null)) ok = true
                }
            }
            !named || ok
        }
        if (out.size < list.size) ServerState.info("[$apiName] ${list.size - out.size} link(s) dropped: their file is another episode than S${season}E$episode")
        return out
    }

    /** Kitsu's names compared the way sites write them: no case, punctuation or "(Dub)" / "(Sub)" tags, no trailing "TV". */
    private fun exactName(n: String): String = AnimeMatcher.normalize(n).removeSuffix(" tv").trim()

    /**
     * The episode of a Kitsu entry in one extension, found the direct way: every name Kitsu stores for the entry
     * ("Farming Life in Another World Season 2", "Isekai Nonbiri Nouka 2"; "One Piece") is searched, only results whose
     * name is exactly one of them count, and the episode is Kitsu's own number in that entry (kitsu:50117:6 = episode 6 of
     * "Isekai Nonbiri Nouka 2", kitsu:12:153 = episode 153 of "One Piece"). Null when the extension has no entry of that
     * name or the entry lacks the episode: the other ways are tried then.
     */
    private suspend fun kitsuExactStreams(api: MainApiWrapper, id: String, title: String, year: Int?): List<StremioStream>? {
        val p = ExternalIds.parse(id) ?: return null
        if (p.scheme !in ExternalIds.ANIME_SCHEMES) return null
        val ep = p.episode ?: return null
        val kid = if (p.scheme == "kitsu") p.key else runCatching { KitsuMap.kitsuOf(p.scheme, p.key) }.getOrNull() ?: return null
        val names = runCatching { KitsuMap.names(kid) }.getOrNull() ?: return null
        val wanted = names.titles.map { exactName(it) }.filter { it.isNotEmpty() }.toHashSet()
        // only Latin-script names are typed into the sites' searches
        val queries = names.titles.filter { n -> n.count { c -> c.code < 0x250 } * 10 >= n.length * 8 }
            .distinctBy { exactName(it) }.take(4)
        val cacheKey = api.internalName
        val matches = LinkedHashMap<String, SearchResult>()
        for (q in queries) {
            val results = try {
                SearchLoadCache.getSearch(cacheKey, q) ?: api.search(q).also { SearchLoadCache.putSearch(cacheKey, q, it) }
            } catch (e: Throwable) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                emptyList()
            }
            results.filter { exactName(it.name) in wanted }.forEach { matches.putIfAbsent(it.url, it) }
            if (matches.isNotEmpty()) break
        }
        if (matches.isEmpty()) {
            ServerState.debug("[${api.name}] No entry named exactly like kitsu:$kid (${queries.joinToString(" / ")})")
            return null
        }
        val out = ArrayList<StremioStream>()
        var found = false
        // the entry and, when the site lists it separately, its dubbed twin
        for (m in matches.values.take(2)) {
            val mi = try {
                SearchLoadCache.getLoad(cacheKey, m.url)?.info ?: api.load(m.url)?.also { SearchLoadCache.putLoad(cacheKey, m.url, it) }
            } catch (e: Throwable) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            } ?: continue
            val list = mi.episodes
            val e: MediaInfoEpisode? = if (list.isNullOrEmpty()) null else run {
                val nums = list.mapNotNull { TitleMatch.numberOf(it) }
                list.firstOrNull { TitleMatch.numberOf(it) == ep && (it.season ?: 1) <= 1 }
                    ?: list.firstOrNull { TitleMatch.numberOf(it) == ep }
                    // a site that keeps counting from the season before (13, 14, ... for a second season of 12)
                    ?: nums.minOrNull()?.takeIf { it > 1 && (names.episodeCount == null || nums.size <= names.episodeCount + 2) }
                        ?.let { first -> list.firstOrNull { TitleMatch.numberOf(it) == first + ep - 1 } }
                    ?: AnimeEpisodes.pick(list, null, ep)
            }
            val dataUrl = when {
                e != null -> e.dataUrl
                list.isNullOrEmpty() && ep == 1 -> mi.dataUrl
                else -> null
            }
            if (dataUrl == null) {
                ServerState.debug("[${api.name}] '${m.name}' (kitsu:$kid) has no episode $ep")
                continue
            }
            found = true
            ServerState.debug("[${api.name}] kitsu:$kid episode $ep -> '${m.name}'" + (e?.let { " · " + (episodeLabelOf(it) ?: "") } ?: ""))
            out += linksFor(api, m.name, dataUrl, e?.let { episodeLabelOf(it) }, title, year)
        }
        return if (found) out else null
    }

    private suspend fun linksFor(api: MainApiWrapper, matchName: String, dataUrlToLoad: String, matchedEpisode: String?, title: String, year: Int?): List<StremioStream> {
        ServerState.debug("[${api.name}] Loading links for $dataUrlToLoad")
        try {
            val links = api.loadLinksAll(dataUrlToLoad)
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, links.size, null)
            if (links.isNotEmpty()) {
                ServerState.debug("[STREAM_SUCCESS] [${api.name}] Resolved ${links.size} streamable link(s) for '$title'")
            } else {
                ServerState.debug("[STREAM_EMPTY] [${api.name}] 0 streamable links returned for '$title'")
            }
            return links.map { stream ->
                val newName = matchName + (if (!stream.name.isNullOrBlank()) "\n${stream.name}" else "")
                val info = (stream.info ?: com.cncverse.stremiobridge.model.StreamInfo(addonName = api.name))
                    .copy(metadataTitle = title, metadataYear = year, matchedName = matchName, matchedEpisode = matchedEpisode)
                stream.copy(name = newName, info = info)
            }
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
            ServerState.error("[STREAM_ERROR] [${api.name}] Stream error for '$title': ${e.message}")
            return emptyList()
        }
    }

    /** Probes a single API wrapper with a search & loadLinks test, returning stream count. */
    suspend fun probeApi(api: MainApiWrapper, query: String = "Avatar"): Int {
        return try {
            val streams = buildGenericStreamsForApi(api, "movie", "probe_test", query, null)
            streams.size
        } catch (e: Throwable) {
            // Cancelled by the bridge (request gone / 45 s stop): not the provider's failure
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, e.message)
            0
        }
    }

    /** Probes all active APIs in parallel (up to 5 concurrent) and updates StreamTracker. */
    suspend fun probeAllApis(query: String = "Avatar"): Map<String, Int> {
        val active = loadedApis.filter { api -> api.supportedTypes.any { it != "tv" } }
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val sem = Semaphore(5)
        coroutineScope {
            active.map { api ->
                launch {
                    sem.withPermit {
                        withTimeoutOrNull(20_000) {
                            val count = probeApi(api, query)
                            results[api.internalName] = count
                        } ?: run {
                            StreamTracker.record(api.pluginInternalName, api.internalName, api.name, 0, "Probe timed out (20s)")
                            results[api.internalName] = 0
                        }
                    }
                }
            }.joinAll()
        }
        return results
    }
}


// ── MainApiWrapper ─────────────────────────────────────────────────────────────

/**
 * Platform-agnostic wrapper around a loaded CS3 MainAPI instance.
 * Implemented by each platform's PluginLoader actual.
 */
interface MainApiWrapper {
    val name: String
    val internalName: String
    /** The bare plugin internalName used for repo/settings lookups (no API-name suffix). */
    val pluginInternalName: String get() = internalName
    val supportedTypes: List<String>
    /** Home-page section names declared by the API (no network). */
    val staticSectionNames: List<String> get() = emptyList()
    /** MainAPI.lang as declared by the extension. */
    val apiLang: String? get() = null
    /** Language from the repo manifest. */
    val pluginLanguage: String? get() = null
    /** False for search-only providers (no home page by design). */
    val hasHomePage: Boolean get() = true
    suspend fun getMainPageSections(): List<String>
    fun clearCache() {}

    suspend fun search(query: String): List<SearchResult>
    suspend fun getMainPage(page: Int, type: String, sectionName: String? = null): List<SearchResult>
    suspend fun load(url: String): MediaInfo?
    suspend fun loadLinks(dataUrl: String): List<StremioStream>
}

/**
 * Episodes an extension lists once per dub status (anime: "Subbed" / "Dubbed") are merged into
 * one episode whose data carries every variant; loading it returns all of them, labelled.
 */
object VariantData {
    private const val PREFIX = "cncvariants:"

    fun encode(parts: List<Pair<String, String>>): String =
        PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<List<List<String>>>(), parts.map { listOf(it.first, it.second) }
            ).toByteArray()
        )

    fun decode(data: String): List<Pair<String, String>>? {
        if (!data.startsWith(PREFIX)) return null
        return runCatching {
            val json = String(java.util.Base64.getUrlDecoder().decode(data.removePrefix(PREFIX)))
            kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.serializer<List<List<String>>>(), json)
                .filter { it.size == 2 }.map { it[0] to it[1] }
        }.getOrNull()
    }

    /** "Subbed" -> "Sub", "Dubbed" -> "Dub"; null for "None". */
    fun label(variant: String?): String? = when (variant?.trim()?.lowercase()) {
        null, "", "none" -> null
        "subbed", "sub" -> "Sub"
        "dubbed", "dub" -> "Dub"
        else -> variant.trim()
    }
}

/**
 * loadLinks for every variant a merged episode carries, each stream labelled "[Sub]" / "[Dub]",
 * and every stream tagged with this extension's display name (provider ordering matches on it).
 */
suspend fun MainApiWrapper.loadLinksAll(dataUrl: String): List<StremioStream> {
    val t0 = System.currentTimeMillis()
    // Not cancelled: a request the bridge stopped says nothing about the extension's speed
    val out = loadLinksAllTimed(dataUrl)
    if (kotlin.coroutines.coroutineContext[StremioServer.WholeRequestTimed.Key] == null) {
        runCatching { com.cncverse.stremiobridge.state.StreamTracker.recordTime(pluginInternalName, internalName, System.currentTimeMillis() - t0, name) }
    }
    return out
}

private suspend fun MainApiWrapper.loadLinksAllTimed(dataUrl: String): List<StremioStream> {
    val api = this
    fun tag(s: StremioStream, label: String?): StremioStream {
        val info = (s.info ?: com.cncverse.stremiobridge.model.StreamInfo(addonName = api.name)).let { i ->
            i.copy(providerName = api.name, linkName = if (label != null) "[$label] " + (i.linkName ?: s.title.orEmpty()) else i.linkName)
        }
        return if (label == null) s.copy(info = info)
        else s.copy(title = "[$label] " + s.title.orEmpty(), info = info)
    }
    val parts = VariantData.decode(dataUrl) ?: return loadLinks(dataUrl).map { tag(it, null) }
    return kotlinx.coroutines.coroutineScope {
        parts.map { (variant, data) ->
            async {
                try {
                    loadLinks(data).map { tag(it, VariantData.label(variant)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}

data class SearchResult(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val year: Int?,
    /** True when the HomePageList had isHorizontalImages = true */
    val isHorizontal: Boolean = false,
    /** The HomePageList section name — forwarded as genres in Stremio */
    val sectionName: String? = null,
)

data class MediaInfoEpisode(
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val dataUrl: String,
    val posterUrl: String?,
    val description: String? = null,
    /** Air date, epoch milliseconds. */
    val releasedMs: Long? = null,
    /** CloudStream DubStatus key the episode came from ("Subbed", "Dubbed"), when the extension splits them. */
    val variant: String? = null,
)

data class CastPerson(val name: String, val character: String? = null, val photo: String? = null)

data class MediaInfo(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val description: String?,
    val year: Int?,
    val dataUrl: String,
    val episodes: List<MediaInfoEpisode>? = null,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
    /** CloudStream "tags": genres, or labels like "Live" / "Rank: 5" for live sources. */
    val genres: List<String>? = null,
    /** 0..10 */
    val rating: Double? = null,
    val cast: List<String>? = null,
    /** Same people as [cast], with photo and role where the extension gives them. */
    val castPeople: List<CastPerson>? = null,
    val runtimeMinutes: Int? = null,
    val contentRating: String? = null,
    /** AniList / MyAnimeList ids the extension reports for this entry (anime), null when it does not. */
    val aniListId: Int? = null,
    val malId: Int? = null,
    /** IMDb ("tt…") / TMDB ids the extension reports for this page (addImdbId / addTMDbId): they confirm a match exactly. */
    val imdbId: String? = null,
    val tmdbId: String? = null,
)

fun SearchResult.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val encodedId = StremioIds.encode(pluginInternalName, url)
    val resolvedType = cs3TvTypeToStremio(type)
    return StremioMeta(
        id          = encodedId,
        type        = resolvedType,
        name        = name,
        poster      = PublicUrls.safeForBrowser(posterUrl),
        background  = if (isHorizontal) PublicUrls.safeForBrowser(posterUrl) else null,
        posterShape = if (isHorizontal) "landscape" else "poster",
        genres      = null,
        year        = year,
        // For TV/live items, set defaultVideoId so Stremio can auto-play without extra navigation
        behaviorHints = if (resolvedType == "tv" || isHorizontal) {
            MetaBehaviorHints(defaultVideoId = encodedId)
        } else null,
    )
}

fun MediaInfo.toStremiMeta(pluginInternalName: String, stremioType: String) = StremioMeta(
    id          = StremioIds.encode(pluginInternalName, dataUrl),
    type        = cs3TvTypeToStremio(type),
    name        = name,
    poster      = PublicUrls.safeForBrowser(posterUrl),
    background  = PublicUrls.safeForBrowser(backgroundUrl),
    logo        = PublicUrls.safeForBrowser(logoUrl),
    description = description,
    year        = year,
    releaseInfo = year?.toString(),
    genres      = genres,
    cast        = cast,
    appExtras   = castPeople?.takeIf { it.isNotEmpty() }?.let { people ->
        AppExtras(cast = people.map { AppCast(it.name, it.character, PublicUrls.safeForBrowser(it.photo)) })
    },
    imdbRating  = rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
    runtime     = runtimeMinutes?.let { "$it min" },
    videos      = episodes?.mapIndexed { index, ep ->
        StremioVideo(
            id       = StremioIds.encode(pluginInternalName, ep.dataUrl),
            title    = ep.name ?: "Episode ${ep.episode ?: (index + 1)}",
            released = ep.releasedMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
            season   = ep.season ?: 1,
            episode  = ep.episode ?: (index + 1),
            thumbnail= PublicUrls.safeForBrowser(ep.posterUrl ?: posterUrl),
            overview = ep.description,
        )
    }
)

expect fun Application.setupMpdProxyRoutes()

