package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioIds
import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The music add-on: a separate link (/music/manifest.json) that speaks the Eclipse add-on protocol (search, stream, album, artist)
 * for the music extensions (Monochrome, MusicBox - extensions of the "Music" type). Those extensions are never part of the main
 * add-on or the profiles (see [MainApiWrapper.isMusic]); this link is the only place they are listed.
 *
 * Ids are the bridge's usual "cnc:" ids; the address behind them starts with what the id is for:
 *  - "t:" a track found by a search (its page is loaded first to get the link address),
 *  - "d:" a track that is already the extension's link address (the tracks of an album),
 *  - "a:" an album, "r:" an artist (their pages are loaded for the track list).
 */
object MusicAddon {
    private const val ADDON_ID = "com.cncverse.stremiobridge.music"
    private const val SEARCH_LIMIT_MS = 12_000L
    private const val LOAD_LIMIT_MS = 25_000L
    private const val LINKS_LIMIT_MS = 30_000L

    /** The enabled music extensions. */
    fun apis(): List<MainApiWrapper> =
        StremioServer.loadedApis.filter { it.isMusic && !StremioServer.isSwitchedOff(it) }

    private fun api(internalName: String): MainApiWrapper? =
        apis().firstOrNull { it.internalName == internalName || it.pluginInternalName == internalName }

    private fun id(api: MainApiWrapper, kind: Char, url: String): String = StremioIds.encode(api.internalName, "$kind:$url")

    /** (extension, kind, address) of an id made by [id]; null when it is not one of ours. */
    private fun decode(id: String): Triple<MainApiWrapper, Char, String>? {
        val (ext, data) = StremioIds.decode(id) ?: return null
        if (data.length < 3 || data[1] != ':') return null
        val api = api(ext) ?: return null
        return Triple(api, data[0], data.substring(2))
    }

    // ── Responses ───────────────────────────────────────────────────────────

    private suspend fun ApplicationCall.reply(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) {
        cors()
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private fun ApplicationCall.cors() {
        response.header("Access-Control-Allow-Origin", "*")
        response.header("Access-Control-Expose-Headers", "Retry-After")
    }

    suspend fun preflight(call: ApplicationCall) {
        call.cors()
        call.response.header("Access-Control-Allow-Methods", "GET, OPTIONS")
        call.response.header("Access-Control-Allow-Headers", "*")
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    suspend fun manifest(call: ApplicationCall) {
        ServerState.info("[Request] music manifest")
        call.reply(buildJsonObject {
            put("id", ADDON_ID)
            put("name", "CNCVerse Music")
            put("version", "1.0.0")
            put("description", "Music from the CNCVerse Bridge music extensions (Monochrome, MusicBox...)")
            put("icon", "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Bridge/refs/heads/main/logo.png")
            put("resources", buildJsonArray { listOf("search", "stream", "catalog").forEach { add(JsonPrimitive(it)) } })
            put("types", buildJsonArray { listOf("track", "album", "artist").forEach { add(JsonPrimitive(it)) } })
            put("contentType", "music")
        })
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private enum class Kind { TRACK, ALBUM, ARTIST }

    private fun kindOf(r: SearchResult): Kind {
        val u = r.url.lowercase()
        return when {
            "/artist" in u -> Kind.ARTIST
            "/album" in u || "/releases" in u || r.type.lowercase() in setOf("series", "tvseries", "anime", "ova") -> Kind.ALBUM
            else -> Kind.TRACK
        }
    }

    /** "Title - Artist" names are split; without that shape the extension's own name stands in for the artist (the field is required). */
    private fun titleAndArtist(name: String, fallbackArtist: String): Pair<String, String> {
        for (sep in listOf(" - ", " – ", " — ", " • ", " | ")) {
            val i = name.indexOf(sep)
            if (i > 0 && i < name.length - sep.length) return name.substring(0, i).trim() to name.substring(i + sep.length).trim()
        }
        return name.trim() to fallbackArtist
    }

    suspend fun search(call: ApplicationCall) {
        val q = call.request.queryParameters["q"].orEmpty().trim()
        ServerState.info("[Request] music search \"$q\"")
        val list = apis()
        if (q.isEmpty() || list.isEmpty()) return call.reply(buildJsonObject {
            put("tracks", JsonArray(emptyList())); put("albums", JsonArray(emptyList())); put("artists", JsonArray(emptyList()))
        })
        // every music extension at the same time; one that is slow or fails only costs its own results
        val found = coroutineScope {
            list.map { api ->
                async(StremioServer.pluginDispatcher) {
                    val res = withTimeoutOrNull(SEARCH_LIMIT_MS) { runCatching { api.search(q) }.getOrNull() }.orEmpty()
                    api to res
                }
            }.awaitAll()
        }
        val tracks = ArrayList<JsonObject>()
        val albums = ArrayList<JsonObject>()
        val artists = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        for ((api, results) in found) for (r in results) {
            if (!seen.add(api.internalName + "|" + r.url)) continue
            when (kindOf(r)) {
                Kind.TRACK -> {
                    val (title, artist) = titleAndArtist(r.name, api.name)
                    tracks += buildJsonObject {
                        put("id", id(api, 't', r.url)); put("title", title); put("artist", artist)
                        r.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
                    }
                }
                Kind.ALBUM -> {
                    val (title, artist) = titleAndArtist(r.name, api.name)
                    albums += buildJsonObject {
                        put("id", id(api, 'a', r.url)); put("title", title); put("artist", artist)
                        r.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
                        r.year?.let { put("year", it.toString()) }
                    }
                }
                Kind.ARTIST -> artists += buildJsonObject {
                    put("id", id(api, 'r', r.url)); put("name", r.name.trim())
                    r.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
                }
            }
        }
        call.reply(buildJsonObject {
            put("tracks", JsonArray(tracks)); put("albums", JsonArray(albums)); put("artists", JsonArray(artists))
        })
    }

    // ── Album / artist ──────────────────────────────────────────────────────

    private suspend fun loadInfo(api: MainApiWrapper, url: String): MediaInfo? =
        withTimeoutOrNull(LOAD_LIMIT_MS) {
            withContext(StremioServer.pluginDispatcher) { runCatching { api.load(url) }.getOrNull() }
        }

    private fun trackObjects(api: MainApiWrapper, info: MediaInfo, artist: String): List<JsonObject> {
        val eps = info.episodes.orEmpty()
        if (eps.isEmpty() && info.dataUrl.isNotBlank()) {
            return listOf(buildJsonObject {
                put("id", id(api, 'd', info.dataUrl)); put("title", info.name); put("artist", artist)
                info.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
            })
        }
        return eps.mapIndexed { i, e ->
            buildJsonObject {
                put("id", id(api, 'd', e.dataUrl))
                put("title", e.name?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}")
                put("artist", artist)
                (e.posterUrl?.takeIf { it.isNotBlank() } ?: info.posterUrl?.takeIf { it.isNotBlank() })?.let { put("artworkURL", it) }
            }
        }
    }

    suspend fun album(call: ApplicationCall) {
        val d = decode(call.parameters["id"].orEmpty()) ?: return call.reply(error("Unknown album"), HttpStatusCode.NotFound)
        val (api, _, url) = d
        val info = loadInfo(api, url) ?: return call.reply(error("Album not found"), HttpStatusCode.NotFound)
        val artist = info.cast?.firstOrNull()?.takeIf { it.isNotBlank() } ?: titleAndArtist(info.name, api.name).second
        val tracks = trackObjects(api, info, artist)
        call.reply(buildJsonObject {
            put("id", call.parameters["id"].orEmpty()); put("title", info.name); put("artist", artist)
            info.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
            info.year?.let { put("year", it.toString()) }
            info.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            put("trackCount", tracks.size)
            put("tracks", JsonArray(tracks))
        })
    }

    suspend fun artist(call: ApplicationCall) {
        val d = decode(call.parameters["id"].orEmpty()) ?: return call.reply(error("Unknown artist"), HttpStatusCode.NotFound)
        val (api, _, url) = d
        val info = loadInfo(api, url) ?: return call.reply(error("Artist not found"), HttpStatusCode.NotFound)
        val tracks = trackObjects(api, info, info.name)
        call.reply(buildJsonObject {
            put("id", call.parameters["id"].orEmpty()); put("name", info.name)
            info.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
            info.description?.takeIf { it.isNotBlank() }?.let { put("bio", it) }
            info.genres?.takeIf { it.isNotEmpty() }?.let { g -> put("genres", buildJsonArray { g.forEach { add(JsonPrimitive(it)) } }) }
            put("topTracks", JsonArray(tracks))
            put("albums", JsonArray(emptyList()))
        })
    }

    // ── Stream ──────────────────────────────────────────────────────────────

    private fun error(message: String): JsonObject = buildJsonObject { put("error", message) }

    private val LOSSLESS = Regex("flac|lossless|hi-?res|alac|24.?bit", RegexOption.IGNORE_CASE)

    /** The link to play: lossless first, then the highest quality the extension reports; links that are not audio files are skipped. */
    internal fun pickBest(streams: List<StremioStream>): StremioStream? {
        val ok = streams.filter { it.url?.startsWith("http", true) == true && it.externalUrl == null && it.infoHash == null && it.ytId == null }
        return ok.maxWithOrNull(compareBy<StremioStream>(
            { if (LOSSLESS.containsMatchIn("${it.name} ${it.title} ${it.url}")) 1 else 0 },
            { it.info?.quality ?: 0 },
        ))
    }

    internal fun formatOf(url: String, label: String): String? {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            ext in setOf("mp3", "flac", "aac", "m4a") -> ext
            ext == "mp4" || ext == "m4b" -> "m4a"
            LOSSLESS.containsMatchIn(label) && !label.contains("alac", true) -> "flac"
            else -> null
        }
    }

    suspend fun stream(call: ApplicationCall) {
        val rawId = call.parameters["id"].orEmpty()
        val (api, kind, url) = decode(rawId) ?: return call.reply(error("Unknown track"), HttpStatusCode.NotFound)
        ServerState.info("[Request] music stream ${api.name}")
        // a track from a search: its page gives the address the links are asked for; an album's track already is that address
        val data = if (kind == 't') {
            val info = loadInfo(api, url)
            info?.episodes?.firstOrNull()?.dataUrl ?: info?.dataUrl?.takeIf { it.isNotBlank() } ?: url
        } else url
        val built = withTimeoutOrNull(LINKS_LIMIT_MS) {
            withContext(StremioServer.pluginDispatcher) { runCatching { api.loadLinks(data) }.getOrDefault(emptyList()) }
        }.orEmpty()
        val best = pickBest(built) ?: return call.reply(error("No playable link for this track"), HttpStatusCode.NotFound)
        val label = "${best.name} ${best.title}"
        val rawUrl = best.url.orEmpty()
        val out = StremioServer.prepareMusicStream(best, StremioServer.musicBase(call))
        val outUrl = out.url ?: rawUrl
        val hls = best.info?.linkType.equals("M3U8", true) || rawUrl.substringBefore('?').endsWith(".m3u8", true)
        call.reply(buildJsonObject {
            put("url", outUrl)
            formatOf(rawUrl, label)?.let { put("format", it) }
            if (LOSSLESS.containsMatchIn(label)) put("quality", "lossless")
            if (hls) put("manifest", "hls")
        })
    }
}
