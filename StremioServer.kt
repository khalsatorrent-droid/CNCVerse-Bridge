package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val serverJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val httpClient by lazy {
    HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(serverJson)
        }
        try {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        } catch (e: Throwable) {}
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  Title / season matching engine
//
//  Used when Stremio asks for streams with a TMDB / IMDb / Kitsu id (i.e. not
//  one of our own encoded plugin ids). The goal is to find the *right* entry
//  inside every plugin:
//    - right title (English, romaji and alternative names are all tried)
//    - right SEASON (anime sites list every season as its own entry, named
//      "Season 2", "2nd Season", "II", or with a completely different subtitle)
//    - right episode number, and BOTH sub and dub variants when offered
// ══════════════════════════════════════════════════════════════════════════

private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
private const val KITSU_ACCEPT = "application/vnd.api+json"

/** Minimum title similarity (0..1) for a search result to be considered at all. */
private const val MIN_SERIES_SIMILARITY = 0.5
private const val MIN_MOVIE_SIMILARITY = 0.85

/** How many ranked search results we are willing to open per plugin. */
private const val MAX_CANDIDATES = 4

/** Once the best candidate reaches this score we stop issuing extra search queries. */
private const val EARLY_STOP_SCORE = 118.0

/** Hard cap for the whole lookup of one plugin (search + load + links). */
private const val PLUGIN_TIMEOUT_MS = 90_000L

private const val ANILIST_QUERY =
    "query(\$search:String){Page(perPage:15){media(search:\$search,type:ANIME,sort:SEARCH_MATCH)" +
        "{id format episodes seasonYear startDate{year} title{romaji english} synonyms}}}"

private class MatchCache<V : Any>(private val ttlMs: Long, private val maxSize: Int = 300) {
    private val map = ConcurrentHashMap<String, Pair<Long, V>>()

    fun get(key: String): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.first > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: V) {
        if (map.size >= maxSize) map.clear()
        map[key] = System.currentTimeMillis() to value
    }
}

private val searchCache = MatchCache<List<SearchResult>>(10 * 60_000L)
private val loadCache = MatchCache<MediaInfo>(5 * 60_000L)
private val linksCache = MatchCache<List<StremioStream>>(3 * 60_000L)
private val baseInfoCache = MatchCache<MatchTitleInfo>(30 * 60_000L)
private val aniListCache = MatchCache<List<MatchAniEntry>>(30 * 60_000L)

/**
 * Some plugins (RaghavAnime in particular) keep per-instance state in loadLinks() and cancel the
 * previous call when a new one starts. Calls to the same plugin are therefore serialized.
 */
private val linkLocks = ConcurrentHashMap<String, Mutex>()

private data class MatchSeasonInfo(
    val number: Int,
    val episodeCount: Int,
    val name: String?,
    val year: Int?
)

private data class MatchAniEntry(
    val id: Int,
    val titles: List<String>,
    val year: Int?,
    val episodes: Int?,
    val format: String?
)

private data class MatchParsedId(
    val kind: String,
    val baseId: String,
    val season: Int?,
    val episode: Int?
)

private data class MatchTitleParts(val base: String, val season: Int?)

private data class MatchTitleInfo(
    /** Title variants to search for, best first. */
    val titles: List<String>,
    val year: Int?,
    val isMovie: Boolean,
    /** True for Kitsu ids: the id already points at one specific season/entry. */
    val direct: Boolean,
    val season: Int?,
    val episode: Int?,
    /** TMDB season number -> info (episode count, name, air year). */
    val seasons: Map<Int, MatchSeasonInfo>,
    val animeLike: Boolean = false,
    /** AniList entry that we are confident is the requested season (if resolved). */
    val pinnedAniListId: Int? = null,
    val pinnedTitles: List<String> = emptyList()
) {
    val seasonInfo: MatchSeasonInfo? get() = season?.let { seasons[it] }

    val targetYear: Int?
        get() = if (isMovie) year else (seasonInfo?.year ?: if ((season ?: 1) <= 1) year else null)

    /** Episode number counted across all previous TMDB seasons (for single-entry long runners). */
    val absoluteEpisode: Int?
        get() {
            val s = season ?: return null
            val e = episode ?: return null
            if (s <= 1 || seasons.isEmpty()) return null
            val before = seasons.filterKeys { it in 1 until s }.values.sumOf { it.episodeCount }
            return if (before > 0) before + e else null
        }
}

private class MatchScored(
    val result: SearchResult,
    val parts: MatchTitleParts,
    val baseSim: Double,
    val score: Double,
    val pinned: Boolean
)

private val MATCH_STOPWORDS = setOf("the", "a", "an", "of", "no", "wa", "to", "season", "part", "tv")
private val MATCH_ROMAN = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9)
private val MATCH_ORDINAL_WORDS = mapOf(
    "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6
)

private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun normalizeForMatch(raw: String): String {
    var s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
    s = s.replace(Regex("\\p{M}+"), "")
    s = s.lowercase()
    s = s.replace("'", "").replace("\u2019", "").replace("`", "")
    s = s.replace("&", " and ")
    s = s.replace(Regex("\\((dub|dubbed|sub|subbed|tv|uncensored)\\)"), " ")
    s = s.replace(Regex("[^a-z0-9\\u3040-\\u30ff\\u4e00-\\u9fff]+"), " ")
    return s.trim().replace(Regex("\\s+"), " ")
}

/**
 * Splits a title into its base name and a season number detected from common naming styles:
 *   "Title Season 2", "Title 2nd Season", "Title Second Season", "Title S2",
 *   "Title II" (roman numeral), "Title 2" (trailing number).
 * With [detect] = false (movies) the whole normalized title is the base and no season is read.
 */
private fun splitSeasonMarker(raw: String, detect: Boolean = true): MatchTitleParts {
    var t = normalizeForMatch(raw)
    if (!detect) return MatchTitleParts(t, null)
    var season: Int? = null

    val patterns = listOf(
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th) season\\b"),
        Regex("\\bseason (\\d{1,2})\\b"),
        Regex("\\b(first|second|third|fourth|fifth|sixth) season\\b"),
        Regex("\\bs(\\d{1,2})\$")
    )
    for (p in patterns) {
        val m = p.find(t) ?: continue
        val g = m.groupValues[1]
        val n = g.toIntOrNull() ?: MATCH_ORDINAL_WORDS[g]
        if (n != null) {
            season = n
            t = t.replaceRange(m.range, " ")
            break
        }
    }

    if (season == null) {
        val roman = Regex("^(.+) (ii|iii|iv|vi|vii|viii|ix)\$").find(t)
        if (roman != null) {
            season = MATCH_ROMAN[roman.groupValues[2]]
            t = roman.groupValues[1]
        } else {
            val digit = Regex("^(.+) (\\d{1,2})\$").find(t)
            val n = digit?.groupValues?.get(2)?.toIntOrNull()
            if (digit != null && n != null && n in 2..20) {
                season = n
                t = digit.groupValues[1]
            }
        }
    }

    t = t.replace(Regex("\\b(part|cour) \\d+\\b"), " ").replace(Regex("\\s+"), " ").trim()
    return MatchTitleParts(t, season)
}

private fun matchTokens(s: String): Set<String> =
    s.split(' ').filter { it.isNotBlank() && it !in MATCH_STOPWORDS }.toSet()

/** 0.0 .. 1.0 similarity between two already-normalized base titles. */
private fun similarityScore(a: String, b: String): Double {
    if (a.isBlank() || b.isBlank()) return 0.0
    if (a == b) return 1.0
    val ta = matchTokens(a)
    val tb = matchTokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    if (ta == tb) return 0.98
    val inter = ta.intersect(tb).size.toDouble()
    val jaccard = inter / ta.union(tb).size.toDouble()
    val containment = inter / minOf(ta.size, tb.size).toDouble()
    return (0.6 * jaccard + 0.4 * containment).coerceAtMost(0.97)
}

private fun isMostlyLatin(s: String): Boolean {
    val letters = s.filter { it.isLetter() }
    return letters.isNotEmpty() && letters.all { it.code < 0x250 }
}

private fun dedupeTitles(list: List<String>): List<String> =
    list.filter { it.isNotBlank() }.distinctBy { normalizeForMatch(it) }.filter { normalizeForMatch(it).isNotBlank() }

private fun isNonGenericSeasonName(name: String): Boolean {
    val n = normalizeForMatch(name)
    if (n.isBlank() || n == "specials") return false
    return !Regex("^(season|series|part|volume|vol|book|chapter|cour) \\d+\$").matches(n)
}

private fun ordinalOf(n: Int): String {
    val suffix = if (n % 100 in 11..13) {
        "th"
    } else {
        when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$n$suffix"
}

private fun parseStremioIdParts(id: String): MatchParsedId {
    val parts = id.split(":")
    val head = parts[0].lowercase()
    return when {
        head == "kitsu" -> MatchParsedId("kitsu", parts.getOrNull(1) ?: "", null, parts.getOrNull(2)?.toIntOrNull())
        head == "tmdb" -> MatchParsedId(
            "tmdb", parts.getOrNull(1) ?: "", parts.getOrNull(2)?.toIntOrNull(), parts.getOrNull(3)?.toIntOrNull()
        )
        head.startsWith("tt") -> MatchParsedId(
            "imdb", parts[0], parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull()
        )
        else -> MatchParsedId(
            "tmdb", parts[0], parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull()
        )
    }
}

// ── HTTP helpers ───────────────────────────────────────────────────────────

private suspend fun httpGetJson(url: String, accept: String? = null): JsonObject? {
    return try {
        val response = httpClient.get(url) {
            if (accept != null) headers.append(HttpHeaders.Accept, accept)
        }
        serverJson.parseToJsonElement(response.bodyAsText()) as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerState.warn("HTTP error for ${url.substringBefore('?')}: ${e.message}")
        null
    }
}

private suspend fun fetchAniListEntries(search: String): List<MatchAniEntry> {
    val key = normalizeForMatch(search)
    aniListCache.get(key)?.let { return it }
    return try {
        val body = "{\"query\":${JsonPrimitive(ANILIST_QUERY)},\"variables\":{\"search\":${JsonPrimitive(search)}}}"
        val text = httpClient.post("https://graphql.anilist.co") {
            setBody(TextContent(body, ContentType.Application.Json))
        }.bodyAsText()
        val root = serverJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val media = root["data"].asObj()?.get("Page").asObj()?.get("media").asArr() ?: return emptyList()
        val list = media.mapNotNull { m ->
            val o = m.asObj() ?: return@mapNotNull null
            val id = o["id"].asStr()?.toIntOrNull() ?: return@mapNotNull null
            val t = o["title"].asObj()
            val titles = LinkedHashSet<String>()
            t?.get("english").asStr()?.let { titles.add(it) }
            t?.get("romaji").asStr()?.let { titles.add(it) }
            o["synonyms"].asArr()?.forEach { s ->
                s.asStr()?.takeIf { isMostlyLatin(it) }?.let { titles.add(it) }
            }
            MatchAniEntry(
                id = id,
                titles = titles.toList().take(6),
                year = o["seasonYear"].asStr()?.toIntOrNull()
                    ?: o["startDate"].asObj()?.get("year").asStr()?.toIntOrNull(),
                episodes = o["episodes"].asStr()?.toIntOrNull(),
                format = o["format"].asStr()
            )
        }
        if (list.isNotEmpty()) aniListCache.put(key, list)
        list
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerState.warn("AniList lookup failed for '$search': ${e.message}")
        emptyList()
    }
}

// ── Resolving what Stremio is asking for ───────────────────────────────────

private suspend fun fetchKitsuInfo(parsed: MatchParsedId, stremioType: String): MatchTitleInfo? {
    val root = httpGetJson("https://kitsu.io/api/edge/anime/${parsed.baseId}", KITSU_ACCEPT) ?: return null
    val attrs = root["data"].asObj()?.get("attributes").asObj() ?: return null
    val raw = mutableListOf<String>()
    attrs["canonicalTitle"].asStr()?.let { raw.add(it) }
    val tObj = attrs["titles"].asObj()
    for (k in listOf("en", "en_us", "en_jp", "ja_jp")) {
        tObj?.get(k).asStr()?.let { raw.add(it) }
    }
    attrs["abbreviatedTitles"].asArr()?.forEach { el -> el.asStr()?.let { raw.add(it) } }
    val titles = dedupeTitles(raw.filter { isMostlyLatin(it) }).take(6)
    if (titles.isEmpty()) return null

    val year = attrs["startDate"].asStr()?.take(4)?.toIntOrNull()
    val isMovie = stremioType == "movie" || attrs["subtype"].asStr().equals("movie", ignoreCase = true)
    val seasonHint = titles.mapNotNull { splitSeasonMarker(it, !isMovie).season }.firstOrNull()

    val mappings = httpGetJson(
        "https://kitsu.io/api/edge/anime/${parsed.baseId}/mappings?filter%5BexternalSite%5D=anilist%2Fanime",
        KITSU_ACCEPT
    )
    val aniListId = mappings?.get("data").asArr()?.firstOrNull().asObj()
        ?.get("attributes").asObj()?.get("externalId").asStr()?.toIntOrNull()

    return MatchTitleInfo(
        titles = titles,
        year = year,
        isMovie = isMovie,
        direct = true,
        season = seasonHint,
        episode = null,
        seasons = emptyMap(),
        animeLike = true,
        pinnedAniListId = aniListId
    )
}

private suspend fun fetchBaseInfo(parsed: MatchParsedId, stremioType: String): MatchTitleInfo? {
    if (parsed.kind == "kitsu") return fetchKitsuInfo(parsed, stremioType)

    val mediaType: String
    val tmdbId: String
    if (parsed.kind == "imdb") {
        val found = httpGetJson(
            "https://api.themoviedb.org/3/find/${parsed.baseId}?api_key=$TMDB_API_KEY&external_source=imdb_id"
        ) ?: return null
        val movieRes = found["movie_results"].asArr()?.firstOrNull().asObj()
        val tvRes = found["tv_results"].asArr()?.firstOrNull().asObj()
        val pick: Pair<String, JsonObject> = (
            if (stremioType == "series") {
                tvRes?.let { "tv" to it } ?: movieRes?.let { "movie" to it }
            } else {
                movieRes?.let { "movie" to it } ?: tvRes?.let { "tv" to it }
            }
        ) ?: return null
        mediaType = pick.first
        tmdbId = pick.second["id"].asStr() ?: return null
    } else {
        mediaType = if (stremioType == "series") "tv" else "movie"
        tmdbId = parsed.baseId
    }

    val d = httpGetJson(
        "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=alternative_titles"
    ) ?: return null

    val isMovie = mediaType == "movie"
    val title = d["title"].asStr() ?: d["name"].asStr() ?: return null
    val original = d["original_title"].asStr() ?: d["original_name"].asStr()
    val year = (d["release_date"].asStr() ?: d["first_air_date"].asStr())?.take(4)?.toIntOrNull()

    val altArr = d["alternative_titles"].asObj()?.let { it["results"].asArr() ?: it["titles"].asArr() }
    val alts = altArr?.mapNotNull { it.asObj()?.get("title").asStr() } ?: emptyList()

    val ordered = mutableListOf(title)
    ordered.addAll(alts.filter { isMostlyLatin(it) }.take(6))
    if (!original.isNullOrBlank()) ordered.add(original)
    val titles = dedupeTitles(ordered).take(6)

    val genreIds = d["genres"].asArr()?.mapNotNull { it.asObj()?.get("id").asStr()?.toIntOrNull() } ?: emptyList()
    val animeLike = d["original_language"].asStr() == "ja" && 16 in genreIds

    val seasons = LinkedHashMap<Int, MatchSeasonInfo>()
    d["seasons"].asArr()?.forEach { el ->
        val o = el.asObj() ?: return@forEach
        val num = o["season_number"].asStr()?.toIntOrNull() ?: return@forEach
        if (num < 1) return@forEach
        seasons[num] = MatchSeasonInfo(
            number = num,
            episodeCount = o["episode_count"].asStr()?.toIntOrNull() ?: 0,
            name = o["name"].asStr(),
            year = o["air_date"].asStr()?.take(4)?.toIntOrNull()
        )
    }

    return MatchTitleInfo(
        titles = titles,
        year = year,
        isMovie = isMovie,
        direct = false,
        season = null,
        episode = null,
        seasons = seasons,
        animeLike = animeLike
    )
}

/**
 * Picks the AniList entry that corresponds to the requested TMDB season / movie, using title,
 * season marker, release year, episode count and format. Returns null unless clearly the best.
 */
private fun pickAniListEntry(entries: List<MatchAniEntry>, info: MatchTitleInfo): MatchAniEntry? {
    val detect = !info.isMovie
    val queryBases = info.titles.map { splitSeasonMarker(it, detect).base }
    val want: Int? = if (info.isMovie) null else (info.season ?: 1)
    val minSim = if (info.isMovie) MIN_MOVIE_SIMILARITY else 0.6

    val ranked = ArrayList<Pair<MatchAniEntry, Double>>()
    for (e in entries) {
        val parts = e.titles.map { splitSeasonMarker(it, detect) }
        var sim = 0.0
        for (p in parts) for (q in queryBases) sim = maxOf(sim, similarityScore(p.base, q))
        if (sim < minSim) continue

        var score = sim * 50.0
        val fmt = e.format
        if (info.isMovie) {
            score += if (fmt == "MOVIE") 25.0 else -25.0
        } else {
            score += when (fmt) {
                "TV" -> 10.0
                "ONA" -> 5.0
                "TV_SHORT" -> -5.0
                null -> 0.0
                else -> -25.0
            }
            val marks = parts.mapNotNull { it.season }
            val w = want ?: 1
            score += if (w <= 1) {
                if (marks.isEmpty() || marks.all { it == 1 }) 20.0 else -25.0
            } else {
                if (w in marks) 30.0 else if (marks.isEmpty()) -5.0 else -30.0
            }
            val expected = info.seasonInfo?.episodeCount
            val eps = e.episodes
            if (eps != null && expected != null && expected > 0) {
                score += if (abs(eps - expected) <= 1) 20.0 else -5.0
            }
        }
        val ty = info.targetYear
        val ey = e.year
        if (ey != null && ty != null) {
            score += if (ey == ty) 25.0 else if (abs(ey - ty) == 1) 8.0 else -15.0
        }
        ranked.add(e to score)
    }

    ranked.sortByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    if (best.second < 70.0) return null
    val second = ranked.getOrNull(1)
    if (second != null && best.second - second.second < 8.0) return null
    return best.first
}

private suspend fun applyAniListPin(info: MatchTitleInfo): MatchTitleInfo {
    if (!info.animeLike || info.direct) return info
    val pool = LinkedHashMap<Int, MatchAniEntry>()
    for (q in info.titles.take(3)) {
        fetchAniListEntries(q).forEach { pool.putIfAbsent(it.id, it) }
        val pick = pickAniListEntry(pool.values.toList(), info)
        if (pick != null) {
            ServerState.info(
                "AniList match for S${info.season ?: 0}: id=${pick.id} titles=${pick.titles.take(2)} " +
                    "year=${pick.year} eps=${pick.episodes}"
            )
            return info.copy(pinnedAniListId = pick.id, pinnedTitles = pick.titles)
        }
    }
    ServerState.info("AniList: no confident match for '${info.titles.first()}' season ${info.season}")
    return info
}

private suspend fun resolveTitleInfo(parsed: MatchParsedId, stremioType: String): MatchTitleInfo? {
    val key = "${parsed.kind}:${parsed.baseId}:$stremioType"
    val base = baseInfoCache.get(key)
        ?: fetchBaseInfo(parsed, stremioType)?.also { baseInfoCache.put(key, it) }
        ?: return null

    val season: Int? = when {
        base.isMovie -> null
        base.direct -> base.season
        else -> parsed.season ?: 1
    }
    val episode: Int? = if (base.isMovie) null else (parsed.episode ?: 1)
    return applyAniListPin(base.copy(season = season, episode = episode))
}

// ── Searching and ranking inside a plugin ──────────────────────────────────

private fun buildQueries(info: MatchTitleInfo): List<String> {
    val out = LinkedHashSet<String>()
    // Exact names of the correct season (from AniList) first: usually a hit on the first query.
    info.pinnedTitles.take(2).forEach { out.add(it) }
    info.titles.take(4).forEach { out.add(it) }
    val s = info.season
    if (!info.isMovie && !info.direct && s != null && s > 1) {
        val primary = info.titles.first()
        out.add("$primary season $s")
        out.add("$primary ${ordinalOf(s)} season")
        info.seasonInfo?.name?.takeIf { isNonGenericSeasonName(it) }?.let { out.add("$primary $it") }
    }
    return out.toList().take(8)
}

private fun scoreResults(results: Collection<SearchResult>, info: MatchTitleInfo): List<MatchScored> {
    val detect = !info.isMovie
    val queryParts = (info.titles).map { splitSeasonMarker(it, detect) }
    val pinnedParts = info.pinnedTitles.map { splitSeasonMarker(it, detect) }
    val pinnedNorm = info.pinnedTitles.map { normalizeForMatch(it) }.filter { it.isNotBlank() }.toSet()
    val seasonName = info.seasonInfo?.name?.takeIf { isNonGenericSeasonName(it) }?.let { normalizeForMatch(it) }
    val want: Int? = if (info.isMovie) null else (info.season ?: 1)
    val minSim = if (info.isMovie) MIN_MOVIE_SIMILARITY else MIN_SERIES_SIMILARITY
    val targetYear = info.targetYear

    val out = ArrayList<MatchScored>()
    for (r in results) {
        val cand = splitSeasonMarker(r.name, detect)
        val candNorm = normalizeForMatch(r.name)

        val aniId = info.pinnedAniListId
        val idPinned = aniId != null && r.url.contains("/info/$aniId") && r.url.contains("anilist", ignoreCase = true)
        val titlePinned = candNorm.isNotEmpty() && candNorm in pinnedNorm
        val pinned = idPinned || titlePinned

        var sim = 0.0
        for (q in queryParts) sim = maxOf(sim, similarityScore(cand.base, q.base))
        for (q in pinnedParts) sim = maxOf(sim, similarityScore(cand.base, q.base))
        if (!pinned && sim < minSim) continue

        var score = sim * 100.0
        if (pinned) {
            score += if (idPinned) 120.0 else 60.0
        } else {
            if (want != null) {
                val cs = cand.season
                score += when {
                    want <= 1 -> if (cs == null || cs == 1) 20.0 else -25.0
                    cs == want -> 30.0
                    cs == null -> -5.0
                    else -> -30.0
                }
            }
            if (seasonName != null && candNorm.contains(seasonName)) score += 30.0
        }

        val ry = r.year
        if (ry != null && targetYear != null) {
            score += if (ry == targetYear) 10.0 else if (abs(ry - targetYear) <= 1) 0.0 else -10.0
        }
        out.add(MatchScored(r, cand, sim, score, pinned))
    }
    return out.sortedByDescending { it.score }
}

/** Decides whether a single-season entry (typical anime site) really is the season we want. */
private fun isSeasonCompatible(
    c: MatchScored,
    info: MatchTitleInfo,
    queryBases: Set<String>,
    entryEpisodeCount: Int
): Boolean {
    if (c.pinned || info.isMovie || info.direct) return true
    val want = info.season ?: 1
    val cs = c.parts.season
    if (want <= 1) return cs == null || cs == 1
    if (cs == want) return true
    if (cs != null) return false

    // Unmarked entry while we want season 2+: accept only if it is clearly not the base series.
    val sn = info.seasonInfo?.name?.takeIf { isNonGenericSeasonName(it) }?.let { normalizeForMatch(it) }
    if (sn != null && sn.isNotBlank() && normalizeForMatch(c.result.name).contains(sn)) return true
    val expected = info.seasonInfo?.episodeCount
    return c.parts.base !in queryBases && expected != null && expected > 0 &&
        abs(entryEpisodeCount - expected) <= 1
}

private fun variantLabel(name: String?, dataUrl: String): String? {
    val n = name?.lowercase().orEmpty()
    return when {
        n.endsWith("(dubbed)") || n.endsWith("(dub)") -> "DUB"
        n.endsWith("(subbed)") || n.endsWith("(sub)") -> "SUB"
        dataUrl.contains("\"isDub\":true") -> "DUB"
        dataUrl.contains("\"isDub\":false") -> "SUB"
        else -> null
    }
}

private fun toVariants(eps: List<MediaInfoEpisode>): List<Pair<String, String?>> =
    eps.map { it.dataUrl to variantLabel(it.name, it.dataUrl) }.distinctBy { it.first }

/**
 * Chooses which episode data url(s) to play inside an opened entry. Returns every variant
 * (e.g. sub AND dub) of the wanted episode, or an empty list when this entry is not a match.
 */
private fun pickDataUrls(
    mi: MediaInfo,
    cand: MatchScored,
    info: MatchTitleInfo,
    queryBases: Set<String>
): List<Pair<String, String?>> {
    val eps = mi.episodes.orEmpty()

    if (info.isMovie) {
        if (eps.isEmpty()) return listOf(mi.dataUrl to null)
        // A movie entry holds one episode (sub/dub). A long series with the same name is not it.
        val distinctNumbers = eps.mapNotNull { it.episode }.toSet()
        if (distinctNumbers.size > 2 && !cand.pinned) return emptyList()
        val first = eps.filter { (it.episode ?: 1) == 1 }
        return toVariants(if (first.isNotEmpty()) first else eps.take(2))
    }

    val n = info.episode ?: return emptyList()
    val want = info.season ?: 1

    // The entry holds several seasons (classic TV source): match season + episode exactly.
    val reportedSeasons = eps.mapNotNull { it.season }.toSet()
    if (reportedSeasons.size > 1) {
        return toVariants(eps.filter { it.season == want && it.episode == n })
    }

    val byNumber = eps.filter { it.episode == n }
    if (byNumber.isNotEmpty()) {
        if (reportedSeasons.size == 1 && reportedSeasons.first() == want && want > 1) return toVariants(byNumber)
        val entryEpisodeCount = eps.mapNotNull { it.episode }.maxOrNull() ?: 0
        if (isSeasonCompatible(cand, info, queryBases, entryEpisodeCount)) return toVariants(byNumber)
    }

    // Long running show kept as one entry with absolute numbering (e.g. TMDB S3E5 = ep 61).
    if (want > 1 && !info.direct) {
        val absNumber = info.absoluteEpisode
        if (absNumber != null && absNumber != n && (cand.parts.season == null || cand.parts.season == 1)) {
            val a = eps.filter { it.episode == absNumber }
            if (a.isNotEmpty()) return toVariants(a)
        }
    }
    return emptyList()
}

private suspend fun cachedSearch(api: MainApiWrapper, query: String): List<SearchResult> {
    val key = "${api.internalName}|${normalizeForMatch(query)}"
    searchCache.get(key)?.let { return it }
    val results = try {
        api.search(query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerState.warn("[${api.name}] search failed for '$query': ${e.message}")
        emptyList()
    }
    if (results.isNotEmpty()) searchCache.put(key, results)
    return results
}

private suspend fun cachedLoad(api: MainApiWrapper, url: String): MediaInfo? {
    val key = "${api.internalName}|$url"
    loadCache.get(key)?.let { return it }
    val info = try {
        api.load(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerState.warn("[${api.name}] load failed for $url: ${e.message}")
        null
    }
    if (info != null) loadCache.put(key, info)
    return info
}

private suspend fun loadLinksSafe(api: MainApiWrapper, dataUrl: String): List<StremioStream> {
    val key = "${api.internalName}|$dataUrl"
    linksCache.get(key)?.let { return it }
    val lock = linkLocks.getOrPut(api.internalName) { Mutex() }
    return lock.withLock {
        linksCache.get(key)?.let { return@withLock it }
        val links = try {
            api.loadLinks(dataUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerState.warn("[${api.name}] loadLinks failed: ${e.message}")
            emptyList()
        }
        if (links.isNotEmpty()) linksCache.put(key, links)
        links
    }
}

/** Full lookup for one plugin: search -> rank -> open best entries -> pick episode -> links. */
private suspend fun streamsFromPlugin(api: MainApiWrapper, info: MatchTitleInfo): List<StremioStream> {
    val collected = mutableListOf<StremioStream>()
    try {
        withTimeoutOrNull(PLUGIN_TIMEOUT_MS) {
            val detect = !info.isMovie
            val queryBases = (info.titles + info.pinnedTitles).map { splitSeasonMarker(it, detect).base }.toSet()

            val pool = LinkedHashMap<String, SearchResult>()
            var ranked: List<MatchScored> = emptyList()
            for (q in buildQueries(info)) {
                val res = cachedSearch(api, q)
                ServerState.info("[${api.name}] '$q' -> ${res.size} result(s)")
                for (r in res) pool.putIfAbsent(r.url, r)
                ranked = scoreResults(pool.values, info)
                val top = ranked.firstOrNull()
                if (top != null && top.score >= EARLY_STOP_SCORE) break
            }

            if (ranked.isEmpty()) {
                ServerState.info("[${api.name}] no acceptable match for '${info.titles.first()}'")
                return@withTimeoutOrNull
            }

            val topScore = ranked.first().score
            for (cand in ranked.take(MAX_CANDIDATES)) {
                if (cand.score < topScore - 45.0) break
                val mi = cachedLoad(api, cand.result.url)
                if (mi == null) {
                    ServerState.warn("[${api.name}] could not open '${cand.result.name}'")
                    continue
                }
                val picks = pickDataUrls(mi, cand, info, queryBases)
                if (picks.isEmpty()) {
                    ServerState.info(
                        "[${api.name}] '${cand.result.name}' (score ${cand.score.toInt()}) rejected: " +
                            "wrong season or episode ${info.episode} missing"
                    )
                    continue
                }
                ServerState.info(
                    "[${api.name}] using '${cand.result.name}' (score ${cand.score.toInt()}" +
                        "${if (cand.pinned) ", AniList-confirmed" else ""}) with ${picks.size} variant(s)"
                )
                for ((dataUrl, label) in picks) {
                    val links = loadLinksSafe(api, dataUrl)
                    ServerState.info("[${api.name}] ${label ?: "default"}: ${links.size} stream(s)")
                    for (s in links) {
                        val newName = buildString {
                            append(cand.result.name)
                            if (label != null) append(" [").append(label).append("]")
                            if (!s.name.isNullOrBlank()) append("\n").append(s.name)
                        }
                        collected.add(s.copy(name = newName))
                    }
                }
                break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerState.warn("Search/load error in ${api.name}: ${e.message}")
    }
    return collected
}

/**
 * Manages the Ktor-based embedded HTTP server exposing the Stremio addon protocol.
 *
 * Routes:
 *   GET /manifest.json
 *   GET /catalog/{type}/{id}.json[?search=query]
 *   GET /meta/{type}/{id}.json
 *   GET /stream/{type}/{id}.json
 *   GET /           (status HTML page)
 */
object StremioServer {

    private var engine: EmbeddedServer<*, *>? = null
    private var activePort: Int = 8080

    val isRunning: Boolean get() = engine != null

    /**
     * Holds live references to loaded [MainApiWrapper] instances.
     * Populated by the platform-specific [PluginLoader] after loading.
     */
    val loadedApis: MutableList<MainApiWrapper> = mutableListOf()

    val disabledPlugins: MutableSet<String> = mutableSetOf()
    private var disabledPluginsFile: File? = null

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
    }

    private fun saveDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        try {
            val json = serverJson.encodeToString(disabledPlugins)
            file.writeText(json)
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

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort

        if (cacheDir != null) {
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
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

        return targetPort
    }

    fun stop() {
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        engine?.stop(0, 500)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    private fun Application.setupPlugins() {
        install(ContentNegotiation) { json(serverJson) }

        // Stremio Web (and sometimes Desktop) can be very strict or send 'Origin: null'.
        // Manually appending these headers ensures maximum compatibility across all Stremio clients.
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
            call.response.header("Access-Control-Allow-Origin", "*")
            call.response.header("Access-Control-Allow-Headers", "*")
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                return@intercept // End pipeline for OPTIONS
            }
        }
    }

    private fun Application.setupRoutes() {
        setupMpdProxyRoutes()
        routing {

            // ── Status page ──────────────────────────────────────────────
            get("/") {
                call.respondText(buildStatusHtml(), ContentType.Text.Html)
            }

            get("/api/toggle-plugin") {
                val id = call.request.queryParameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (disabledPlugins.contains(id)) {
                    disabledPlugins.remove(id)
                } else {
                    disabledPlugins.add(id)
                }
                saveDisabledPlugins()
                call.respondRedirect("/")
            }

            // ── Manifest ─────────────────────────────────────────────────
            get("/manifest.json") {
                call.respond(buildManifest())
            }

            // ── Catalog ──────────────────────────────────────────────────
            get("/catalog/{path...}") {
                val pathSegments = call.parameters.getAll("path") ?: emptyList()
                if (pathSegments.size < 2) return@get call.respond(HttpStatusCode.BadRequest)

                val type = pathSegments[0]

                if (pathSegments.size == 2) {
                    val idWithExt = pathSegments[1]
                    if (!idWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    val id = idWithExt.removeSuffix(".json")
                    val search = call.request.queryParameters["search"]
                    val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0
                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, null) }
                    call.respond(StremioCatalogResponse(metas))
                } else if (pathSegments.size == 3) {
                    val id = pathSegments[1]
                    val extraWithExt = pathSegments[2]
                    if (!extraWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    val extraStr = extraWithExt.removeSuffix(".json")
                    val parsedExtra = io.ktor.http.parseQueryString(extraStr)
                    val search = parsedExtra["search"] ?: call.request.queryParameters["search"]
                    val skip = (parsedExtra["skip"] ?: call.request.queryParameters["skip"])?.toIntOrNull() ?: 0
                    val genre = parsedExtra["genre"]
                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, genre) }
                    call.respond(StremioCatalogResponse(metas))
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }

            // ── Meta ─────────────────────────────────────────────────────
            get("/meta/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val meta = withContext(Dispatchers.IO) { buildMeta(type, id) }
                if (meta != null) {
                    call.respond(StremioMetaResponse(meta))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // ── Stream ───────────────────────────────────────────────────
            get("/stream/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                call.respond(StremioStreamResponse(streams.hideLowQualityAndSort()))
            }

            // ── Subtitles ────────────────────────────────────────────────
            get("/subtitles/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                val subtitles = streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }
                call.respond(StremioSubtitleResponse(subtitles))
            }
        }
    }

    // ── Manifest builder ──────────────────────────────────────────────────────

    private suspend fun buildManifest(): StremioManifest {
        val activeApis = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
        val types = listOf("movie","series", "other", "tv")

        val catalogs = activeApis.flatMap { api ->
            api.supportedTypes
                .map { cs3TvTypeToStremio(it) }
                .distinct()
                .flatMap { stremioType ->
                    val extra = mutableListOf<ExtraEntry>()
                    val sections = api.getMainPageSections()
                    if (sections.isNotEmpty() && (sections.size > 1 || sections.first().isNotBlank())) {
                        extra.add(ExtraEntry(name = "genre", options = sections))
                    }
                    extra.add(ExtraEntry("search"))
                    extra.add(ExtraEntry("skip"))
                    listOf(
                        StremioCatalogDef(
                            type = stremioType,
                            id = "cnc_${api.internalName}_$stremioType",
                            name = "${api.name} ($stremioType)",
                            extra = extra
                        )
                    )
                }
        }.distinctBy { it.id }
            .ifEmpty {
                listOf(StremioCatalogDef("movie", "cnc_all_movie", "CNCVerse (Movie)"))
            }

        return StremioManifest(
            id = "com.cncverse.stremiobridge",
            version = "1.0.0",
            name = "CNCVerse Bridge",
            description = "CS3 plugin bridge for Stremio — powered by CNCVerse extensions",
            logo = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png",
            types = types,
            resources = listOf("catalog", "meta", "stream", "subtitles"),
            catalogs = catalogs,
        )
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)

        // Exact match first ("<internalName>_<type>"); otherwise the LONGEST matching prefix.
        // A plain startsWith() would send "RaghavAnime_RaghavAnimeKitsu_movie" to the plugin
        // whose internal name is just "RaghavAnime_RaghavAnime" when both are loaded.
        val api = loadedApis.firstOrNull { rest == "${it.internalName}_$type" }
            ?: loadedApis.filter { rest.startsWith(it.internalName) }.maxByOrNull { it.internalName.length }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()
        if (disabledPlugins.contains(api.internalName)) return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                val results = api.search(search)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r -> cs3TvTypeToStremio(r.type) == type }
                        .ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    // ── Meta builder ──────────────────────────────────────────────────────────

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (internalName, dataUrl) = StremioIds.decode(id) ?: return null
        val api = loadedApis.find { it.internalName == internalName } ?: return null
        if (disabledPlugins.contains(api.internalName)) return null

        return try {
            api.load(dataUrl)?.toStremiMeta(api.internalName, type)
        } catch (e: Throwable) {
            ServerState.warn("Meta error for $internalName: ${e.message}")
            null
        }
    }

    // ── Stream quality filtering / sorting ────────────────────────────────────

    /** Streams reporting a resolution below this (in pixels of height) are hidden. */
    private const val MIN_QUALITY = 720

    /**
     * Hides streams below [MIN_QUALITY] and sorts the rest from highest to lowest quality.
     * Streams whose quality is unknown (plugin reported Unknown) are kept, since they may well
     * be HD, but are placed after all streams with a known quality. The sort is stable, so the
     * original order is preserved among streams of equal quality.
     */
    private fun List<StremioStream>.hideLowQualityAndSort(): List<StremioStream> =
        filter { (it.quality ?: Int.MAX_VALUE) >= MIN_QUALITY }
            .sortedByDescending { it.quality ?: -1 }

    // ── Stream builder ────────────────────────────────────────────────────────

    private suspend fun buildStreams(type: String, id: String): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (internalName, dataUrl) = decoded
            val api = loadedApis.find { it.internalName == internalName } ?: return emptyList()
            if (disabledPlugins.contains(api.internalName)) return emptyList()
            return try {
                api.loadLinks(dataUrl)
            } catch (e: Throwable) {
                ServerState.warn("Stream error for $internalName: ${e.message}")
                emptyList()
            }
        }

        // Generic Stremio requests: "tt123", "tt123:1:5", "tmdb:123", "tmdb:123:1:5", "kitsu:456:3"
        val parsed = parseStremioIdParts(id)
        ServerState.info(
            "Generic request: id=$id, type=$type -> kind=${parsed.kind}, base=${parsed.baseId}, " +
                "season=${parsed.season}, episode=${parsed.episode}"
        )

        return try {
            val resolved = resolveTitleInfo(parsed, type)
            if (resolved == null) {
                ServerState.warn("Title resolve failed for $id")
                return emptyList()
            }
            if (!resolved.isMovie && resolved.season == 0) {
                ServerState.info("Season 0 (specials) is not supported for generic lookups: $id")
                return emptyList()
            }
            ServerState.info(
                "Resolved: titles=${resolved.titles}, year=${resolved.year}, movie=${resolved.isMovie}, " +
                    "season=${resolved.season}, episode=${resolved.episode}"
            )

            val allStreams = mutableListOf<StremioStream>()
            val activePlugins = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
            ServerState.info("Searching across ${activePlugins.size} plugins...")

            coroutineScope {
                activePlugins.map { api ->
                    async { streamsFromPlugin(api, resolved) }
                }.awaitAll().forEach { allStreams.addAll(it) }
            }

            ServerState.info("Returning total ${allStreams.size} streams")
            allStreams
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerState.warn("Stream resolve error for $id: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    // ── HTML status page ──────────────────────────────────────────────────────

    private fun buildStatusHtml(): String {
        val pluginRows = loadedApis.joinToString("") { api ->
            val isEnabled = !disabledPlugins.contains(api.internalName)
            val statusHtml = if (isEnabled) "<td style=\"color:#4ade80\">&#9679; Enabled</td>" else "<td style=\"color:#f87171\">&#9679; Disabled</td>"
            val actionText = if (isEnabled) "Disable" else "Enable"
            """<tr>
                <td>${api.name}</td>
                <td>${api.internalName}</td>
                <td>${api.supportedTypes.joinToString(", ")}</td>
                $statusHtml
                <td><a class="btn" style="padding:0.25rem 0.75rem;margin:0;font-size:0.9rem;" href="/api/toggle-plugin?id=${api.internalName}">$actionText</a></td>
            </tr>"""
        }
        return """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8">
<title>CNCVerse Bridge</title>
<style>
  body{background:#0f0f1a;color:#e0e0f0;font-family:sans-serif;padding:2rem}
  h1{color:#a78bfa}
  table{border-collapse:collapse;width:100%;margin-top:1rem}
  th,td{padding:.5rem 1rem;border:1px solid #2a2a4a;text-align:left}
  th{background:#1a1a2e}
  .btn{display:inline-block;margin-top:1rem;padding:.75rem 1.5rem;background:#7c3aed;color:#fff;border-radius:.5rem;text-decoration:none;font-weight:bold}
</style></head><body>
<h1>&#127916; CNCVerse Bridge</h1>
<p>Loaded plugins: <strong>${loadedApis.size}</strong></p>
<a class="btn" href="stremio://localhost:${ServerState.serverPort}/manifest.json">&#9654; Add to Stremio</a>
<table><thead><tr><th>Name</th><th>Internal</th><th>Types</th><th>Status</th><th>Action</th></tr></thead>
<tbody>$pluginRows</tbody></table>
</body></html>"""
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
    val supportedTypes: List<String>
    suspend fun getMainPageSections(): List<String>
    suspend fun search(query: String): List<SearchResult>
    suspend fun getMainPage(page: Int, type: String, sectionName: String? = null): List<SearchResult>
    suspend fun load(url: String): MediaInfo?
    suspend fun loadLinks(dataUrl: String): List<StremioStream>
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
    val posterUrl: String?
)

data class MediaInfo(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val description: String?,
    val year: Int?,
    val dataUrl: String,
    val episodes: List<MediaInfoEpisode>? = null
)

fun SearchResult.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val encodedId = StremioIds.encode(pluginInternalName, url)
    val resolvedType = cs3TvTypeToStremio(type)
    return StremioMeta(
        id = encodedId,
        type = resolvedType,
        name = name,
        poster = posterUrl,
        background = if (isHorizontal) posterUrl else null,
        posterShape = if (isHorizontal) "landscape" else "poster",
        genres = null,
        year = year,
        // For TV/live items, set defaultVideoId so Stremio can auto-play without extra navigation
        behaviorHints = if (resolvedType == "tv" || isHorizontal) {
            MetaBehaviorHints(defaultVideoId = encodedId)
        } else null,
    )
}

fun MediaInfo.toStremiMeta(pluginInternalName: String, stremioType: String) = StremioMeta(
    id = StremioIds.encode(pluginInternalName, dataUrl),
    type = cs3TvTypeToStremio(type),
    name = name,
    poster = posterUrl,
    description = description,
    year = year,
    videos = episodes?.map { ep ->
        StremioVideo(
            id = StremioIds.encode(pluginInternalName, ep.dataUrl),
            title = ep.name ?: "Episode ${ep.episode}",
            season = ep.season ?: 1,
            episode = ep.episode ?: 1,
            thumbnail= ep.posterUrl ?: posterUrl
        )
    }
)

expect fun Application.setupMpdProxyRoutes()
