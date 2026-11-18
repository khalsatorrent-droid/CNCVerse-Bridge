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
 *  - "t:" a track found by a search (its page is loaded first to get the link address); after the address come the track's title,
 *    artist and the search it came from, separated by [SEP], so playing it can look for the same track in the other extensions,
 *  - "d:" a track that is already the extension's link address (the tracks of an album),
 *  - "a:" an album, "r:" an artist (their pages are loaded for the track list).
 *
 * With several music extensions, a search asks all of them at once (8 s at most), the same track found by several of them is listed
 * once (the most accurate matches first), and playing a track asks every extension that has it for links - again 8 s at most -
 * and answers with the best one: hi-res FLAC > FLAC / lossless > 999 kbps > 320 kbps > ... > previews.
 */
object MusicAddon {
    private const val ADDON_ID = "com.cncverse.stremiobridge.music"
    private const val SEARCH_LIMIT_MS = 8_000L
    private const val LOAD_LIMIT_MS = 15_000L
    private const val LINKS_LIMIT_MS = 20_000L
    /** Looking in the other music extensions for a better link of the same track: all at once, this long at most. */
    private const val BEST_LINK_LIMIT_MS = 8_000L
    /** Separates the parts of a "t:" id ([trackData]). */
    private const val SEP = '\u001F'

    /** The enabled music extensions. */
    fun apis(): List<MainApiWrapper> =
        StremioServer.loadedApis.filter { it.isMusic && !StremioServer.isSwitchedOff(it) }

    /**
     * Music gets its own small pool of threads: a search that is typed letter by letter, or a slow extension, must never use up the
     * threads the main add-on, the manifest and the tunnel check need (the shared plugin pool has 32).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val musicDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(6)

    private class Cached(val at: Long, val value: List<SearchResult>)
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<SearchResult>>>()
    private const val SEARCH_CACHE_MS = 90_000L

    private fun api(internalName: String): MainApiWrapper? =
        apis().firstOrNull { it.internalName == internalName || it.pluginInternalName == internalName }

    private fun id(api: MainApiWrapper, kind: Char, url: String): String = StremioIds.encode(api.internalName, "$kind:$url")

    /** The address of a "t:" id with what playing it needs to find the same track elsewhere. */
    internal fun trackData(url: String, title: String, artist: String, query: String): String =
        listOf(url, title, artist, query).joinToString(SEP.toString()) { it.replace(SEP, ' ') }

    /** (address, title, artist, query) of [trackData]; ids made before it carry the address only. */
    internal fun splitTrack(data: String): List<String> = data.split(SEP).let { p -> List(4) { p.getOrElse(it) { "" } } }

    // ── Matching ────────────────────────────────────────────────────────────

    private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
    private val FEAT = Regex("""\s(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex("""\s[-–—]\s(.*)$""")

    /**
     * Words that make a different recording of the same song: "Song (Instrumental)" is not "Song". Two titles only match when they
     * carry the same set of these. Remastered, "From <film>", "feat." and the like do not change the recording and are ignored.
     */
    private val VARIANTS = linkedMapOf(
        "instrumental" to Regex("""instrumental|karaoke|backing.?track|minus.?one|no.?vocals?|without.?vocals?|beat\s*only""", RegexOption.IGNORE_CASE),
        "acapella" to Regex("""a.?cap+el+a|vocals?\s*only""", RegexOption.IGNORE_CASE),
        "remix" to Regex("""remix|\brmx\b|\bvip\b|bootleg|mashup|\bflip\b""", RegexOption.IGNORE_CASE),
        "live" to Regex("""\blive\b|concert|in session""", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("""acoustic|unplugged|piano version|stripped""", RegexOption.IGNORE_CASE),
        "cover" to Regex("""\bcover\b|tribute|made famous|in the style of""", RegexOption.IGNORE_CASE),
        "speed" to Regex("""sped.?up|slowed|reverb|nightcore|\b8d\b|lo.?fi|daycore|super.?slowed""", RegexOption.IGNORE_CASE),
        "extended" to Regex("""extended|club mix|radio edit|\bedit\b|short version|tv size""", RegexOption.IGNORE_CASE),
        "demo" to Regex("""\bdemo\b""", RegexOption.IGNORE_CASE),
        "reprise" to Regex("""reprise|\bsad version|female version|male version|duet version""", RegexOption.IGNORE_CASE),
    )

    /** Which [VARIANTS] a title is (from its brackets, its " - ..." tail or anywhere in it). */
    internal fun variantsOf(title: String): Set<String> = VARIANTS.filterValues { it.containsMatchIn(title) }.keys

    private fun plain(s: String): String {
        // apostrophes join (D'tour -> dtour): split, the "d" alone would match any other artist with a lone letter
        var x = s.replace(Regex("['’`´]"), "")
        x = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return x.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** A title or artist reduced to lower-case words: no brackets, "feat. ...", " - Remastered 2011", accents or punctuation. */
    internal fun norm(s: String): String = plain(s.replace(BRACKETS, " ").replace(FEAT, "").replace(DASH_TAIL, ""))

    private fun words(s: String): Set<String> = norm(s).split(' ').filter { it.isNotEmpty() }.toSet()

    /** How well "title artist" answers the search: the share of the searched words found, plus a bonus for an exact title. */
    internal fun accuracy(query: String, title: String, artist: String): Double {
        val q = words(query)
        if (q.isEmpty()) return 0.0
        val have = words("$title $artist")
        val share = q.count { it in have }.toDouble() / q.size
        val exactTitle = norm(title).isNotEmpty() && (norm(query) == norm(title) || norm(query).startsWith(norm(title) + " ") || norm(query).endsWith(" " + norm(title)))
        // a variant nobody searched for (Instrumental, Sped up, Cover...) goes below the original
        val unasked = (variantsOf(title) - variantsOf(query)).size
        return share + (if (exactTitle) 0.5 else 0.0) - 0.6 * unasked
    }

    private val ARTIST_SPLIT = Regex("""\s*(,|&|/|;|\+|\bx\b|\band\b|\bwith\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)

    /** The full names of the artists ("Matthew Ifield, Ted Fresco" -> [matthew ifield, ted fresco]); a leading "the" is dropped. */
    internal fun artistNames(s: String): Set<String> =
        s.split(ARTIST_SPLIT).map { plain(it).removePrefix("the ").trim() }.filter { it.length >= 2 }.toSet()

    /**
     * The same recording, strictly: the same title once cleaned, the same version (an Instrumental, Remix, Live, Sped up... only
     * matches the same kind), and at least one artist with exactly the same full name. Both artists must be known.
     */
    internal fun sameTrack(title1: String, artist1: String, title2: String, artist2: String): Boolean {
        if (norm(title1).isEmpty() || norm(title1) != norm(title2)) return false
        if (variantsOf(title1) != variantsOf(title2)) return false
        val a1 = artistNames(artist1); val a2 = artistNames(artist2)
        return a1.isNotEmpty() && a2.isNotEmpty() && a1.any { it in a2 }
    }

    /**
     * How sure a link is to be the right recording - this comes before its quality. Links from download services that find a song
     * by searching its name (YouTube converters, Spotify downloaders) can be another song or an instrumental; MusicBox gathers its
     * links that way, so its links come after the catalogue extensions (Monochrome, Octave...) that play the exact track they list.
     */
    internal fun trust(api: String, st: StremioStream): Int {
        val label = "${st.name.orEmpty()} ${st.title.orEmpty()} ${st.description.orEmpty()}"
        return when {
            BY_NAME.containsMatchIn(label) -> 0
            api.equals("MusicBox", true) -> 1
            else -> 2
        }
    }
    private val BY_NAME = Regex("""y2mate|ytmp3|youtube|\byt\b|cnvmp3|spotsaver|spotidown|spotifydownloads|spotify ?down|musicfab|rapidapi|savefrom|ssyoutube""", RegexOption.IGNORE_CASE)

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
        // the last separator: titles carry " - " themselves ("Ghalat Fehmi - From \"Superstar\" - Asim Azhar"), artists rarely do
        for (sep in listOf(" - ", " – ", " — ", " • ", " | ")) {
            val i = name.lastIndexOf(sep)
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
                async {
                    api to searchOne(api, q)
                }
            }.awaitAll()
        }
        class Track(val api: MainApiWrapper, val r: SearchResult, val title: String, val artist: String, val score: Double, val order: Int)
        val trackList = ArrayList<Track>()
        val albums = ArrayList<JsonObject>()
        val artists = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        for ((api, results) in found) for ((i, r) in results.withIndex()) {
            if (!seen.add(api.internalName + "|" + r.url)) continue
            when (kindOf(r)) {
                Kind.TRACK -> {
                    val (title, artist) = titleAndArtist(r.name, api.name)
                    trackList += Track(api, r, title, artist, accuracy(q, title, artist), i)
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
        // the most accurate answers first (an extension's own order breaks ties); a track several extensions found is listed once -
        // playing it looks in all of them for the best link anyway
        val kept = ArrayList<Track>()
        for (tr in trackList.sortedWith(compareByDescending<Track> { it.score }.thenBy { it.order })) {
            if (kept.none { sameTrack(it.title, it.artist, tr.title, tr.artist) }) kept += tr
        }
        val tracks = kept.map { tr ->
            buildJsonObject {
                put("id", id(tr.api, 't', trackData(tr.r.url, tr.title, tr.artist, q))); put("title", tr.title); put("artist", tr.artist)
                tr.r.posterUrl?.takeIf { it.isNotBlank() }?.let { put("artworkURL", it) }
            }
        }
        if (found.count { it.second.isNotEmpty() } > 1) ServerState.info("[Music] \"$q\": ${trackList.size} tracks from ${found.count { it.second.isNotEmpty() }} extensions, ${tracks.size} after merging the same tracks")
        call.reply(buildJsonObject {
            put("tracks", JsonArray(tracks)); put("albums", JsonArray(albums)); put("artists", JsonArray(artists))
        })
    }

    /** One extension's results for a query: kept for a minute and a half, and a second ask for the same query waits for the first. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private suspend fun searchOne(api: MainApiWrapper, q: String): List<SearchResult> {
        val key = api.internalName + "|" + q.lowercase()
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.at < SEARCH_CACHE_MS) return it.value }
        if (searchCache.size > 200) searchCache.entries.removeIf { now - it.value.at >= SEARCH_CACHE_MS }
        val job = inFlight.computeIfAbsent(key) {
            kotlinx.coroutines.GlobalScope.async(musicDispatcher) {
                try {
                    val res = withTimeoutOrNull(SEARCH_LIMIT_MS) { runCatching { api.search(q) }.getOrNull() }.orEmpty()
                    if (res.isNotEmpty()) searchCache[key] = Cached(System.currentTimeMillis(), res)
                    res
                } finally { inFlight.remove(key) }
            }
        }
        return job.await()
    }

    // ── Album / artist ──────────────────────────────────────────────────────

    private suspend fun loadInfo(api: MainApiWrapper, url: String): MediaInfo? =
        withTimeoutOrNull(LOAD_LIMIT_MS) {
            withContext(musicDispatcher) { runCatching { api.load(url) }.getOrNull() }
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
    private val HI_RES = Regex("""hi-?res|24.?bit|(88|96|176|192)(\.\d)?\s*khz""", RegexOption.IGNORE_CASE)
    private val KBPS = Regex("""(\d{2,4})\s*k(?:bps|b/s|bit/s|bits)?\b""", RegexOption.IGNORE_CASE)
    private val PREVIEW = Regex("""preview|30.?s(ec)?\b|snippet""", RegexOption.IGNORE_CASE)

    private fun playable(it: StremioStream) =
        it.url?.startsWith("http", true) == true && it.externalUrl == null && it.infoHash == null && it.ytId == null

    /**
     * How good a link sounds: hi-res FLAC 3000, FLAC / lossless 2000, otherwise the kbps the extension names (999, 320, 256...),
     * an .mp3 / .m4a without one 128, unknown 100; a preview (30-second sample) always comes last.
     * The quality the extension set on the link counts as kbps (MusicBox "1411p" = CD FLAC: 1000-1999 is lossless, 2000+ hi-res).
     */
    internal fun qualityRank(st: StremioStream): Int {
        val label = "${st.name.orEmpty()} ${st.title.orEmpty()} ${st.description.orEmpty()}"
        val ext = st.url.orEmpty().substringBefore('?').substringAfterLast('.', "").lowercase()
        val q = st.info?.quality ?: 0
        val rank = when {
            (LOSSLESS.containsMatchIn(label) || ext == "flac") && HI_RES.containsMatchIn(label) -> 3000
            q >= 2000 -> 3000
            LOSSLESS.containsMatchIn(label) || ext == "flac" || ext == "alac" || q >= 1000 -> 2000
            else -> (KBPS.findAll(label).mapNotNull { it.groupValues[1].toIntOrNull() } + q).filter { it in 32..1999 }.maxOrNull()
                ?: if (ext in setOf("mp3", "m4a", "aac", "mp4", "ogg", "opus")) 128 else 100
        }
        return if (PREVIEW.containsMatchIn(label)) rank - 10_000 else rank
    }

    /** The link to play: the best [qualityRank]; links that are not audio files are skipped. */
    internal fun pickBest(streams: List<StremioStream>): StremioStream? =
        streams.filter(::playable).maxWithOrNull(compareBy<StremioStream>({ qualityRank(it) }, { it.info?.quality ?: 0 }))

    internal fun formatOf(url: String, label: String): String? {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            ext in setOf("mp3", "flac", "aac", "m4a") -> ext
            ext == "mp4" || ext == "m4b" -> "m4a"
            LOSSLESS.containsMatchIn(label) && !label.contains("alac", true) -> "flac"
            else -> null
        }
    }

    /** Every link an extension gives for one track (its page is loaded first for a "t:" id). */
    private suspend fun linksOf(api: MainApiWrapper, kind: Char, url: String): List<StremioStream> {
        val data = if (kind == 't') {
            val info = loadInfo(api, url)
            info?.episodes?.firstOrNull()?.dataUrl ?: info?.dataUrl?.takeIf { it.isNotBlank() } ?: url
        } else url
        return withTimeoutOrNull(LINKS_LIMIT_MS) {
            withContext(musicDispatcher) { runCatching { api.loadLinks(data) }.getOrDefault(emptyList()) }
        }.orEmpty()
    }

    /** The same track in another music extension: its results for the original search (usually still cached), else for "title artist". */
    private suspend fun findIn(api: MainApiWrapper, title: String, artist: String, query: String): SearchResult? {
        fun match(results: List<SearchResult>) = results.firstOrNull { r ->
            kindOf(r) == Kind.TRACK && titleAndArtist(r.name, "").let { (t, a) -> sameTrack(title, artist, t, a) }
        }
        val r = (if (query.isNotBlank()) match(searchOne(api, query)) else null) ?: match(searchOne(api, "$title $artist".trim()))
        ServerState.info("[Music] ${api.name}: " + (r?.let { "same track \"${it.name.take(80)}\"" } ?: "no \"$title\" by $artist"))
        return r
    }

    suspend fun stream(call: ApplicationCall) {
        val rawId = call.parameters["id"].orEmpty()
        val (api, kind, data) = decode(rawId) ?: return call.reply(error("Unknown track"), HttpStatusCode.NotFound)
        val (url, title, artist, query) = if (kind == 't') splitTrack(data) else listOf(data, "", "", "")
        val others = if (kind == 't' && title.isNotBlank()) apis().filter { it.internalName != api.internalName } else emptyList()
        ServerState.info("[Request] music stream ${api.name}" + if (others.isEmpty()) "" else " (+ ${others.size} more extension(s) for the best link)")
        val all = coroutineScope {
            // the extension the track came from may take its usual time; the others get 8 s together (searching and loading links)
            val own = async { listOf(api to linksOf(api, kind, url)) }
            val more = others.map { o ->
                async {
                    withTimeoutOrNull(BEST_LINK_LIMIT_MS) {
                        findIn(o, title, artist, query)?.let { r -> o to linksOf(o, 't', r.url) }
                    }
                }
            }
            own.await() + more.awaitAll().filterNotNull()
        }
        val ranked = all.flatMap { (a, links) -> links.filter(::playable).map { a to it } }
            // the right recording first, the best quality among those
            .sortedWith(compareByDescending<Pair<MainApiWrapper, StremioStream>> { trust(it.first.name, it.second) }
                .thenByDescending { qualityRank(it.second) }.thenByDescending { it.second.info?.quality ?: 0 })
        val (bestApi, best) = ranked.firstOrNull() ?: return call.reply(error("No playable link for this track"), HttpStatusCode.NotFound)
        if (all.size > 1) ServerState.info("[Music] links: " + ranked.take(8).joinToString(", ") { (a, s) ->
            "${a.name} [trust ${trust(a.name, s)}, ${qualityRank(s)}" + (s.info?.quality?.let { ", q$it" } ?: "") + "] " + s.title.orEmpty().take(40)
        })
        if (all.size > 1) ServerState.info("[Music] \"$title\": ${ranked.size} links from ${all.count { it.second.isNotEmpty() }} extension(s); playing ${bestApi.name} \"${best.name.orEmpty().ifBlank { best.title.orEmpty() }.take(60)}\"")
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
