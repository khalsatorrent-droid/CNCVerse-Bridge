package com.cncverse.stremiobridge.server

import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Matching a movie / series against an extension's own search results (sites that do not use TMDB catalogs):
 * a score for every result instead of the first one that looks right, the search words typed the way those sites
 * need them, the season read from an entry's title, and episode numbers read from episode names.
 */
object TitleMatch {
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETS = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")
    private val YEAR = Regex("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])")
    /** Words sites add to a post title that are not part of the name (language, quality, encodes, "full movie"...). */
    private val TAG_WORDS = Regex(
        "\\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|urdu|korean|japanese|chinese|" +
            "dual audio|multi audio|dual|multi|dubbed|dub|subbed|org|esubs?|msubs?|hevc|x264|x265|h264|h265|10bit|8bit|hdr10|hdr|dv|" +
            "web dl|webdl|web rip|webrip|web|hd rip|hdrip|blu ray|bluray|brrip|bdrip|dvdrip|dvd|hdtc|hdts|hdcam|camrip|cam|pre dvd|predvd|" +
            "2160p|1440p|1080p|720p|576p|480p|360p|240p|4k|uhd|fhd|hd|sd|full movie|movie|film|full series|web series|series|" +
            "complete|all episodes|episodes|ddp?5 1|dd5 1|ddp?2 0|aac|atmos|uncut|extended|remastered|unrated|imax|proper|repack|" +
            "download|watch online)\\b"
    )
    private val SEASON_RX = listOf(
        Regex("\\bseason\\s*0*([0-9]{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b([0-9]{1,2})(?:st|nd|rd|th)\\s+season\\b", RegexOption.IGNORE_CASE),
        Regex("\\bs0*([0-9]{1,2})(?:\\s*e[0-9]{1,4})?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bseries\\s*0*([0-9]{1,2})\\b", RegexOption.IGNORE_CASE),
    )
    private val PART_RX = Regex("\\b(?:part|cour|vol(?:ume)?)\\.?\\s*0*([0-9]{1,2})\\b", RegexOption.IGNORE_CASE)
    private val SEASON_WORDS_RX = Regex(
        "\\bseason\\s*[0-9]{1,2}\\b|\\b[0-9]{1,2}(?:st|nd|rd|th)\\s+season\\b|\\bs[0-9]{1,2}(?:\\s*e[0-9]{1,4})?\\b|\\bseries\\s*[0-9]{1,2}\\b",
        RegexOption.IGNORE_CASE,
    )
    private val EPISODE_RX = Regex("(?:\\bs[0-9]{1,2}\\s*e|\\bepisode|\\bepi|\\bep|\\be|#)\\s*\\.?\\s*0*([0-9]{1,4})(?![0-9])", RegexOption.IGNORE_CASE)
    /** Characters that break a site's search when the extension pastes the words straight into its address. */
    private val QUERY_BREAKERS = Regex("[&#?%:'\"’`!]")

    /** The name without year, brackets, season words, language / quality tags and punctuation: "The Boys (2019) Season 4 Hindi 1080p" -> "the boys". */
    fun baseName(name: String): String {
        var t = name.lowercase().replace("&", " and ")
        t = BRACKETS.replace(t, " ")
        t = YEAR.replace(t, " ")
        t = SEASON_WORDS_RX.replace(t, " ")
        t = NON_ALNUM.replace(t, " ")
        t = TAG_WORDS.replace(" $t ", " ")
        return NON_ALNUM.replace(t, " ").trim()
    }

    /** Several seasons in one post, the way CineStream reads them: "S01-S03", "S1-3", "Season 1-4", "Seasons 1 to 3", "Season 1 & 2". */
    private val SEASON_RANGE_RX = listOf(
        Regex("\\bs0*([0-9]{1,2})\\s*(?:-|–|~|to)\\s*s?0*([0-9]{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bseasons?\\s*0*([0-9]{1,2})\\s*(?:-|–|~|to|&|and)\\s*(?:season\\s*)?0*([0-9]{1,2})\\b", RegexOption.IGNORE_CASE),
    )
    fun seasonRange(name: String?): IntRange? {
        val n = name ?: return null
        for (rx in SEASON_RANGE_RX) rx.find(n)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            if (a in 0..60 && b in a + 1..60) return a..b
        }
        return null
    }

    /** "S02E05" in an episode's name -> 2 (sites that leave the episode's season empty but write it in the name). */
    private val EP_SEASON_RX = Regex("\\bs0*([0-9]{1,2})\\s*e\\s*0*[0-9]{1,4}\\b", RegexOption.IGNORE_CASE)
    fun seasonOfEpisodeName(name: String?): Int? = name?.let { EP_SEASON_RX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** A year written in the name ("Movie (2019)"), when the site gave none. */
    fun yearOf(name: String): Int? = YEAR.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The season an entry's title names: "Season 2", "2nd Season", "S02", "Series 2"; "Part 2" only when [allowPart]. */
    fun seasonFromTitle(name: String?, allowPart: Boolean = false): Int? {
        val n = name ?: return null
        for (rx in SEASON_RX) rx.find(n)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..60 }?.let { return it } }
        if (allowPart) PART_RX.find(n)?.let { m -> return m.groupValues[1].toIntOrNull() }
        return null
    }

    /** "Episode 12", "Ep. 12", "E12", "#12", "S01E12" -> 12; null when the name carries no number. */
    fun episodeNumberFromName(name: String?): Int? {
        val n = name ?: return null
        return EPISODE_RX.find(n)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..5000 }
    }

    /** The search words without the characters that break URL-pasting sites. */
    fun cleanQuery(q: String): String {
        var t = q.replace("&", " and ")
        t = POSSESSIVE.replace(t, "")
        t = APOSTROPHES.replace(t, "")
        return NON_ALNUM_SPACES.replace(QUERY_BREAKERS.replace(t, " "), " ").trim()
    }
    private val POSSESSIVE = Regex("['’`]s\\b")
    private val APOSTROPHES = Regex("['’`]")
    private val NON_ALNUM_SPACES = Regex("\\s+")

    /**
     * Other ways to type [title] for a site's search box, best first, never [title] itself: without the punctuation that
     * breaks pasted searches, without the subtitle ("Dune: Part Two" -> "Dune"), other (Latin) titles, then "title year".
     */
    fun queryVariants(title: String, year: Int?, otherTitles: List<String> = emptyList(), max: Int = 3): List<String> {
        val out = LinkedHashSet<String>()
        fun add(q: String?) {
            val t = q?.trim()?.takeIf { it.length >= 2 } ?: return
            if (t.equals(title.trim(), true)) return
            if (out.any { it.equals(t, true) }) return
            out += t
        }
        val clean = cleanQuery(title)
        if (clean != title.trim()) add(clean)
        val head = title.substringBefore(":").substringBefore(" - ").substringBefore(" – ").trim()
        if (head.length >= 3 && head != title.trim()) add(cleanQuery(head))
        for (o in otherTitles) {
            // Latin-script names only: the site's search box rarely knows the other scripts
            if (o.count { it.code < 0x250 } * 10 < o.length * 8) continue
            add(cleanQuery(o))
        }
        if (year != null) add("${cleanQuery(head.ifBlank { title })} $year")
        return out.take(max)
    }

    /**
     * 0..1 how well a search result fits [title] / [year] / [season]. The base names are compared; leftover words in the
     * result ("The Boys Presents ...") cost a little each, another season or a far-off year costs a lot.
     */
    fun score(resultName: String, resultYear: Int?, title: String, year: Int?, season: Int?): Double {
        val br = baseName(resultName)
        val bt = baseName(title)
        if (br.isEmpty() || bt.isEmpty()) return 0.0
        val ry = resultYear ?: yearOf(resultName)
        val sameYear = year != null && ry != null && kotlin.math.abs(ry - year) <= 1
        var s = when {
            br == bt -> 1.0
            // the same letters and digits, spaces aside ("Spider-Man" / "Spiderman", "WALL·E" / "Wall E") - CineStream's slug match
            br.replace(" ", "") == bt.replace(" ", "") -> 0.97
            " $br ".startsWith(" $bt ") || " $br ".endsWith(" $bt ") -> {
                val extra = br.split(' ').size - bt.split(' ').size
                (0.88 - 0.06 * (extra - 1).coerceAtLeast(0)).coerceAtLeast(0.55)
            }
            // the whole title inside the post's name ("Watch The Boys Online Free") - CineStream accepts a name that contains the title
            " $br ".contains(" $bt ") -> {
                val extra = br.split(' ').size - bt.split(' ').size
                (0.82 - 0.06 * (extra - 1).coerceAtLeast(0)).coerceAtLeast(0.5)
            }
            // the post's name inside the title ("No Way Home" for "Spider-Man: No Way Home"): fine when the year agrees too
            " $bt ".contains(" $br ") && br.length >= 4 ->
                if (sameYear && br.length >= 6) maxOf(0.7, 0.7 * br.length / bt.length + 0.15) else 0.7 * br.length / bt.length + 0.15
            else -> AnimeMatcher.dice(br, bt) * 0.95
        }
        if (year != null && ry != null) {
            val d = ry - year
            when {
                d == 0 -> s += 0.05
                kotlin.math.abs(d) <= 1 -> {}
                // a later season's post carries that season's year
                season != null && d > 0 -> {}
                // a movie of another year is another film (remake); a series page may carry an earlier year
                season == null -> s -= 0.45
                else -> s -= 0.3
            }
        }
        // a post with several seasons ("S01-S03") holds every season of its range
        val range = seasonRange(resultName)
        if (range != null) {
            if (season != null) { if (season in range) s += 0.03 else s -= 0.5 }
            return s.coerceIn(0.0, 1.0)
        }
        val rs = seasonFromTitle(resultName)
        if (season != null && rs != null && rs != season) s -= 0.5
        if (season == null && rs != null && rs > 1) s -= 0.3
        if (season != null && rs != null && rs == season) s += 0.03
        return s.coerceIn(0.0, 1.0)
    }

    /** Results that fit, best first ([minScore] and above), at most [limit]. */
    fun rank(results: List<SearchResult>, title: String, year: Int?, season: Int?, minScore: Double = 0.6, limit: Int = 6): List<SearchResult> =
        results.distinctBy { it.url }
            .map { it to score(it.name, it.year, title, year, season) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /** How a page numbers its episodes, compared with TMDB's season sizes. */
    enum class Numbering { TMDB, ABSOLUTE, ENTRY, UNKNOWN }

    fun numberOf(ep: MediaInfoEpisode): Int? = ep.episode ?: episodeNumberFromName(ep.name)

    /**
     * TMDB: several seasons whose sizes match TMDB's (2 or more within 1). ABSOLUTE: one list longer than TMDB's first season
     * (One Piece 1..1100). ENTRY: one season entry numbered from 1. UNKNOWN without TMDB sizes or episodes.
     */
    fun numbering(episodes: List<MediaInfoEpisode>?, tmdbCounts: Map<Int, Int>?): Numbering {
        val list = episodes.orEmpty()
        if (list.isEmpty() || tmdbCounts.isNullOrEmpty()) return Numbering.UNKNOWN
        val seasons = list.groupBy { it.season ?: 1 }
        if (seasons.size >= 2) {
            val equal = seasons.count { (s, eps) -> tmdbCounts[s]?.let { kotlin.math.abs(it - eps.size) <= 1 } == true }
            return if (equal >= 2) Numbering.TMDB else Numbering.ENTRY
        }
        val maxNo = list.mapNotNull { numberOf(it) }.maxOrNull() ?: list.size
        val first = tmdbCounts[1] ?: return Numbering.UNKNOWN
        return if (maxNo > first + maxOf(2, first / 10)) Numbering.ABSOLUTE else Numbering.ENTRY
    }

    /** The list in viewing order: by air date when every episode has one, reversed when the numbers count down. */
    fun ordered(episodes: List<MediaInfoEpisode>): List<MediaInfoEpisode> {
        if (episodes.size < 2) return episodes
        // No numbers but dates: the site's own order is the viewing order (a date can be when the site uploaded the
        // episode, not when it aired - AnimePahe), only a list that runs newest first is turned around
        if (episodes.all { numberOf(it) == null } && episodes.all { it.releasedMs != null })
            return if (episodes.first().releasedMs!! > episodes.last().releasedMs!!) episodes.reversed() else episodes
        val nums = episodes.mapNotNull { numberOf(it) }
        if (nums.size >= 2 && nums.first() > nums.last()) return episodes.reversed()
        return episodes
    }

    /**
     * The episode [season]x[episode] of a series page. [entrySeason] is the season the entry's title names (sites that list
     * each season on its own and leave the episodes' season empty); [absolute] is the running number (all earlier seasons +
     * this one) for pages that number the whole show in one list.
     */
    fun findSeriesEpisode(episodes: List<MediaInfoEpisode>?, season: Int, episode: Int, entrySeason: Int?, absolute: Int?): MediaInfoEpisode? {
        val list = episodes.orEmpty()
        if (list.isEmpty()) return null
        val noSeasons = list.all { it.season == null }
        fun effSeason(e: MediaInfoEpisode) = e.season ?: seasonOfEpisodeName(e.name) ?: (if (noSeasons) entrySeason else null) ?: 1
        list.firstOrNull { effSeason(it) == season && numberOf(it) == episode }?.let { return it }
        // a one-list page of the whole show
        if (absolute != null && season > 1) list.firstOrNull { effSeason(it) == 1 && numberOf(it) == absolute }?.let { return it }
        // no numbers at all: the position, on a page that is this season (or the only season)
        if (list.all { numberOf(it) == null } && (entrySeason == season || (season == 1 && entrySeason == null))) {
            return ordered(list).getOrNull(episode - 1)
        }
        return null
    }
}

/**
 * A match an extension confirmed (the episode was found on that page): next time the same title / season is asked of that
 * extension, its page is tried first and the search is skipped when it still has the episode. Kept on the device for 5 days.
 */
object MatchMemory {
    private const val TTL_MS = 5L * 24 * 3600_000
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    @kotlinx.serialization.Serializable
    private data class Hit(val url: String, val name: String, val at: Long)
    private val map = ConcurrentHashMap<String, Hit>()
    @Volatile private var file: File? = null
    private val lock = Any()

    fun init(cacheDir: String) {
        val f = File(cacheDir, "match_memory.json")
        file = f
        runCatching { if (f.exists()) map.putAll(json.decodeFromString<Map<String, Hit>>(f.readText())) }
        val now = System.currentTimeMillis()
        map.entries.removeIf { now - it.value.at > TTL_MS }
    }

    /** "extension|scheme:key|season" (season 0 for movies). */
    fun key(api: String, id: String, season: Int?): String? {
        val ext = ExternalIds.parse(id) ?: return null
        return "$api|${ext.scheme}:${ext.key}|${season ?: 0}"
    }

    fun get(key: String?): Pair<String, String>? {
        val h = map[key ?: return null] ?: return null
        if (System.currentTimeMillis() - h.at > TTL_MS) { map.remove(key); return null }
        return h.url to h.name
    }

    fun put(key: String?, url: String, name: String) {
        val k = key ?: return
        val old = map[k]
        if (old != null && old.url == url && System.currentTimeMillis() - old.at < 12 * 3600_000L) return
        if (map.size > 5_000) map.entries.sortedBy { it.value.at }.take(1_500).forEach { map.remove(it.key) }
        map[k] = Hit(url, name, System.currentTimeMillis())
        save()
    }

    fun forget(key: String?) { if (key != null && map.remove(key) != null) save() }

    private fun save() {
        val f = file ?: return
        runCatching { synchronized(lock) { f.writeText(json.encodeToString(map.toMap())) } }
    }
}
