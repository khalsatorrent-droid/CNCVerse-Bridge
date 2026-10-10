package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What the bridge knows about an anime behind a kitsu: / mal: / anilist: / anidb: id.
 * [titles] are in order of preference (English, romaji, synonyms, native): extensions such as
 * AniPM and Anidap index the same show under any of them.
 */
data class AnimeInfo(
    val titles: List<String>,
    val year: Int?,
    val episodeCount: Int?,
    val format: String?,
    val seasonHint: Int?,
) {
    val isMovie: Boolean get() = format.equals("MOVIE", true) || format.equals("movie", true)
}

/** Looks an anime id up on AniList / Kitsu and collects every title it is known by. */
object AnimeResolver {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { okhttp3.OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build() }
    private val cache = ConcurrentHashMap<String, AnimeInfo>()

    private fun get(url: String, accept: String = "application/json"): JsonObject? = runCatching {
        client.newCall(okhttp3.Request.Builder().url(url).header("Accept", accept).build()).execute().use { r ->
            if (r.code == 200) r.body?.string()?.let { json.parseToJsonElement(it).jsonObject } else null
        }
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

    private fun aniList(field: String, id: Int): AnimeInfo? {
        val query = "query{Media($field:$id,type:ANIME){title{english romaji native}synonyms seasonYear startDate{year}episodes format}}"
        val body = JsonObject(mapOf("query" to JsonPrimitive(query))).toString()
        val text = runCatching {
            client.newCall(
                okhttp3.Request.Builder().url("https://graphql.anilist.co")
                    .header("Accept", "application/json")
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
                    .build()
            ).execute().use { r -> if (r.code == 200) r.body?.string() else null }
        }.getOrNull() ?: return null
        val media = (json.parseToJsonElement(text).jsonObject["data"] as? JsonObject)?.get("Media") as? JsonObject ?: return null
        val t = media["title"] as? JsonObject
        val titles = listOfNotNull(t?.str("english"), t?.str("romaji")) + media.strList("synonyms").take(4) + listOfNotNull(t?.str("native"))
        if (titles.isEmpty()) return null
        val year = (media["seasonYear"] as? JsonPrimitive)?.intOrNull
            ?: ((media["startDate"] as? JsonObject)?.get("year") as? JsonPrimitive)?.intOrNull
        return AnimeInfo(
            titles = titles.distinct(),
            year = year,
            episodeCount = (media["episodes"] as? JsonPrimitive)?.intOrNull,
            format = media.str("format"),
            seasonHint = AnimeMatcher.seasonNumber(AnimeMatcher.normalize(titles.first())),
        )
    }

    private fun kitsu(id: String): AnimeInfo? {
        val a = (get("https://kitsu.io/api/edge/anime/$id", "application/vnd.api+json")?.get("data") as? JsonObject)
            ?.get("attributes") as? JsonObject ?: return null
        val t = a["titles"] as? JsonObject
        val titles = listOfNotNull(t?.str("en"), a.str("canonicalTitle"), t?.str("en_jp"), t?.str("en_us")) +
            a.strList("abbreviatedTitles").take(3) + listOfNotNull(t?.str("ja_jp"))
        if (titles.isEmpty()) return null
        return AnimeInfo(
            titles = titles.distinct(),
            year = a.str("startDate")?.take(4)?.toIntOrNull(),
            episodeCount = (a["episodeCount"] as? JsonPrimitive)?.intOrNull,
            format = a.str("subtype")?.uppercase(),
            seasonHint = AnimeMatcher.seasonNumber(AnimeMatcher.normalize(titles.first())),
        )
    }

    /** arm.haglund.dev maps ids between Kitsu, AniDB, MAL and AniList. */
    private fun mapToAniList(source: String, id: String): Int? =
        get("https://arm.haglund.dev/api/v2/ids?source=$source&id=$id")?.str("anilist")?.toIntOrNull()

    suspend fun resolve(ext: ExternalId): AnimeInfo? {
        val key = ext.scheme + ":" + ext.key
        cache[key]?.let { return it }
        val info = withContext(Dispatchers.IO) {
            try {
                when (ext.scheme) {
                    "anilist" -> ext.key.toIntOrNull()?.let { aniList("id", it) }
                    "mal" -> ext.key.toIntOrNull()?.let { aniList("idMal", it) }
                    "kitsu" -> {
                        val fromKitsu = kitsu(ext.key)
                        // AniList adds romaji and synonyms Kitsu does not list: merge them in
                        val al = mapToAniList("kitsu", ext.key)?.let { aniList("id", it) }
                        when {
                            fromKitsu == null -> al
                            al == null -> fromKitsu
                            else -> fromKitsu.copy(titles = (fromKitsu.titles + al.titles).distinct(), year = fromKitsu.year ?: al.year)
                        }
                    }
                    "anidb" -> mapToAniList("anidb", ext.key)?.let { aniList("id", it) }
                    else -> null
                }
            } catch (e: Exception) {
                ServerState.warn("Anime lookup ${ext.scheme}:${ext.key} failed: ${e.message?.take(80)}")
                null
            }
        }
        if (info != null) {
            if (cache.size > 2_000) cache.clear()
            cache[key] = info
            ServerState.debug("Anime ${ext.scheme}:${ext.key} -> ${info.titles.take(3)} (${info.year}, ${info.format}, ${info.episodeCount} eps)")
        }
        return info
    }
}

/** Fuzzy matching of an anime's titles against extension search results. */
object AnimeMatcher {
    private val TAGS = Regex("[\\(\\[][^\\)\\]]*\\b(dub|dubbed|sub|subbed|english|hindi|tamil|telugu|multi|uncensored|raw|hd|1080p|720p)\\b[^\\)\\]]*[\\)\\]]")
    private val AUDIO_WORDS = Regex("\\b(english|hindi|tamil|telugu)\\s+(dub|dubbed|sub|subbed)\\b|\\b(dubbed|subbed|dub)\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SEASON_WORDS = listOf(
        Regex("\\bseason\\s*(\\d{1,2})\\b"),
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b"),
        Regex("\\bs(\\d{1,2})\\b"),
    )
    private val ROMAN_END = Regex("\\s(ii|iii|iv|v)$")
    private val PART = Regex("\\b(?:part|cour)\\s*\\d{1,2}\\b")

    /** Lower case, no dub/sub tags, punctuation as single spaces. */
    fun normalize(s: String): String {
        var t = s.lowercase()
        t = TAGS.replace(t, " ")
        t = t.replace("&", " and ")
        t = AUDIO_WORDS.replace(t, " ")
        return NON_ALNUM.replace(t, " ").trim()
    }

    /** Season number a (normalized) title names: "season 2", "2nd season", "s2", "... ii"; null when none. */
    fun seasonNumber(n: String): Int? {
        for (rx in SEASON_WORDS) rx.find(n)?.let { m -> return m.groupValues[1].toIntOrNull() }
        ROMAN_END.find(n)?.let { m ->
            return when (m.groupValues[1]) { "ii" -> 2; "iii" -> 3; "iv" -> 4; "v" -> 5; else -> null }
        }
        return null
    }

    /** The title without season / part words. */
    private fun base(n: String): String {
        var t = n
        for (rx in SEASON_WORDS) t = rx.replace(t, " ")
        t = ROMAN_END.replace(t, "")
        t = PART.replace(t, " ")
        return NON_ALNUM.replace(t, " ").trim()
    }

    private fun bigrams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until s.length - 1) m.merge(s.substring(i, i + 2), 1, Int::plus)
        return m
    }

    /** Sorensen-Dice similarity of character pairs, 0..1. */
    fun dice(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val x = bigrams(a)
        val y = bigrams(b)
        var common = 0
        for ((k, v) in x) common += minOf(v, y[k] ?: 0)
        return 2.0 * common / ((a.length - 1) + (b.length - 1))
    }

    /** 0..1 how well one search result fits [info] (or its [wantedTitles]). */
    fun score(result: SearchResult, info: AnimeInfo, wantedSeason: Int?): Double {
        val nr = normalize(result.name)
        if (nr.isEmpty()) return 0.0
        val br = base(nr)
        val sr = seasonNumber(nr)
        var best = 0.0
        for (title in info.titles) {
            val nt = normalize(title)
            if (nt.isEmpty()) continue
            val bt = base(nt)
            var s = when {
                bt == br -> 1.0
                br.isNotEmpty() && bt.isNotEmpty() && (" $br ".contains(" $bt ") || " $bt ".contains(" $br ")) ->
                    0.9 * minOf(br.length, bt.length).toDouble() / maxOf(br.length, bt.length).coerceAtLeast(1).toDouble() + 0.1
                else -> dice(br, bt)
            }
            val st = seasonNumber(nt) ?: wantedSeason ?: info.seasonHint
            val seasonWanted = st ?: 1
            val seasonGot = sr ?: 1
            if (seasonWanted != seasonGot) s *= 0.55
            best = maxOf(best, s)
        }
        // Release year: the same year is a good sign, a far-off one means a remake or another show
        val ry = result.year
        val wy = info.year
        if (ry != null && wy != null) {
            val d = kotlin.math.abs(ry - wy)
            if (d == 0) best = minOf(1.0, best + 0.04) else if (d > 1) best *= 0.7
        }
        return best
    }

    /** The best result, or null when nothing is close enough. */
    fun pickBest(results: List<SearchResult>, info: AnimeInfo, wantedSeason: Int? = null, minScore: Double = 0.62): SearchResult? {
        var best: SearchResult? = null
        var bestScore = 0.0
        for (r in results) {
            val s = score(r, info, wantedSeason)
            if (s > bestScore) { bestScore = s; best = r }
        }
        return if (bestScore >= minScore) best else null
    }

    /** Search terms to try, most promising first (an extension's search can be picky about the spelling). */
    fun searchTerms(info: AnimeInfo): List<String> {
        val out = ArrayList<String>()
        for (t in info.titles) {
            val cleaned = t.trim()
            if (cleaned.isNotEmpty() && out.none { it.equals(cleaned, true) }) out += cleaned
            if (out.size >= 3) break
        }
        return out
    }
}

/** Choosing the episode of an anime among whatever numbering an extension uses. */
object AnimeEpisodes {
    /**
     * [season] / [episode] come from the requested id. kitsu:/mal:/anilist: ids number the episodes of one
     * season entry (episode N of that entry); tt ids carry season and episode. Extensions list either one
     * flat run (1..N), one run per season, or absolute numbers - all four are tried in that order.
     */
    fun pick(episodes: List<MediaInfoEpisode>?, season: Int?, episode: Int): MediaInfoEpisode? {
        val list = episodes.orEmpty()
        if (list.isEmpty()) return null
        val wantedSeason = season ?: 1
        // 1. exactly that season and episode
        list.firstOrNull { (it.season ?: 1) == wantedSeason && it.episode == episode }?.let { return it }
        val seasons = list.groupBy { it.season ?: 1 }
        // 2. one flat list: the episode number is the position
        if (seasons.size == 1) {
            list.firstOrNull { it.episode == episode }?.let { return it }
        } else if (wantedSeason > 1 && wantedSeason !in seasons) {
            // 3. several seasons but not ours: the number may be absolute (all earlier seasons + ours)
            val before = seasons.filterKeys { it < wantedSeason }.values.sumOf { s -> s.size }
            list.firstOrNull { it.episode == before + episode && (it.season ?: 1) >= wantedSeason - 1 }?.let { return it }
            list.firstOrNull { it.episode == episode }?.let { return it }
        } else {
            // season 1 asked but it lists seasons with other numbers: take the first with that number
            list.firstOrNull { it.episode == episode }?.let { return it }
        }
        // 4. no episode numbers at all: the order of the list
        if (list.all { it.episode == null }) return list.getOrNull(episode - 1)
        return null
    }
}
