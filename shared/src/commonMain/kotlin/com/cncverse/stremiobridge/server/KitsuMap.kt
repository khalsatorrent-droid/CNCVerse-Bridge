package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Kitsu <-> IMDb episode mapping, the way Stremio's own Kitsu add-on keeps it (and what CineStream reads): every episode
 * of a Kitsu entry lists the IMDb series it belongs to and its IMDb season and episode
 * (`anime-kitsu.strem.fun/meta/…/kitsu:ID.json` -> `videos[].imdb_id / imdbSeason / imdbEpisode`). The other ids of an
 * entry come from arm.haglund.dev. This is exact per entry, where adding up TMDB season sizes is not (Kitsu splits a
 * show into cours, specials and parts differently from TMDB).
 */
object KitsuMap {
    class Vid(val episode: Int, val imdb: String?, val season: Int?, val imdbEpisode: Int?)

    private const val ADDON = "https://anime-kitsu.strem.fun"
    private const val ARM = "https://arm.haglund.dev/api/v2"
    private const val TTL_MS = 12 * 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** kitsu id -> (when, its episodes); an entry the add-on does not know is remembered as an empty list. */
    private val metas = ConcurrentHashMap<String, Pair<Long, List<Vid>>>()
    /** imdb id -> (when, the Kitsu entries of that series). */
    private val byImdb = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    /** "source:id" -> (when, kitsu id or "" when none). */
    private val toKitsu = ConcurrentHashMap<String, Pair<Long, String>>()
    /** kitsu id -> (when, AniList id or 0 when none). */
    private val toAniList = ConcurrentHashMap<String, Pair<Long, Int>>()

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    private fun fetch(url: String): JsonElement? = runCatching {
        client.newCall(okhttp3.Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) }
        }
    }.getOrNull()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

    /** Every entry object of an arm.haglund.dev answer (one object, or a list of them). */
    private fun entries(el: JsonElement?): List<JsonObject> = when (el) {
        is JsonArray -> el.mapNotNull { it.obj() }
        is JsonObject -> listOf(el)
        else -> emptyList()
    }

    /** The episodes of Kitsu entry [kitsuId] with their IMDb place; null when the add-on cannot be reached. */
    suspend fun videos(kitsuId: String): List<Vid>? = withContext(Dispatchers.IO) {
        metas[kitsuId]?.takeIf { fresh(it.first) }?.let { return@withContext it.second }
        var reached = false
        var meta: JsonObject? = null
        for (type in listOf("anime", "series", "movie")) {
            val el = fetch("$ADDON/meta/$type/kitsu:$kitsuId.json")
            if (el != null) reached = true
            meta = el.obj()?.get("meta").obj()
            if (meta != null) break
        }
        if (meta == null) {
            if (reached) metas[kitsuId] = System.currentTimeMillis() to emptyList()
            return@withContext if (reached) emptyList() else null
        }
        val top = meta.str("imdb_id")
        val list = (meta["videos"] as? JsonArray).orEmpty().mapNotNull { e ->
            val v = e.obj() ?: return@mapNotNull null
            val ep = v.int("episode") ?: v.str("id")?.substringAfterLast(':')?.toIntOrNull() ?: return@mapNotNull null
            Vid(ep, v.str("imdb_id") ?: top, v.int("imdbSeason"), v.int("imdbEpisode"))
        }
        if (metas.size > 2_000) metas.clear()
        metas[kitsuId] = System.currentTimeMillis() to list
        list
    }

    /** IMDb series, season and episode of episode [episode] of Kitsu entry [kitsuId]; null when the add-on has no mapping. */
    suspend fun toImdb(kitsuId: String, episode: Int): Triple<String, Int, Int>? {
        val v = videos(kitsuId)?.firstOrNull { it.episode == episode } ?: return null
        val imdb = v.imdb?.takeIf { it.startsWith("tt") } ?: return null
        return Triple(imdb, v.season ?: return null, v.imdbEpisode ?: return null)
    }

    /** The Kitsu entries of IMDb series [imdb] (arm.haglund.dev, then ani.zip), at most 12. */
    suspend fun kitsuIdsForImdb(imdb: String): List<String> = withContext(Dispatchers.IO) {
        byImdb[imdb]?.takeIf { fresh(it.first) }?.let { return@withContext it.second }
        val out = LinkedHashSet<String>()
        entries(fetch("$ARM/imdb?id=$imdb")).forEach { o -> o.str("kitsu")?.let { out.add(it) } }
        if (out.isEmpty()) entries(fetch("$ARM/ids?source=imdb&id=$imdb")).forEach { o -> o.str("kitsu")?.let { out.add(it) } }
        if (out.isEmpty()) fetch("https://api.ani.zip/mappings?imdb_id=$imdb").obj()?.get("mappings").obj()?.str("kitsu_id")?.let { out.add(it) }
        val list = out.filter { it.all(Char::isDigit) }.take(12)
        if (byImdb.size > 2_000) byImdb.clear()
        byImdb[imdb] = System.currentTimeMillis() to list
        list
    }

    /**
     * The Kitsu entry and its own episode number for IMDb [imdb] season [season] episode [episode], or null when the
     * Kitsu add-on maps no episode there. Every candidate entry is read at the same time.
     */
    suspend fun fromImdb(imdb: String, season: Int, episode: Int): Pair<String, Int>? {
        val ids = kitsuIdsForImdb(imdb)
        if (ids.isEmpty()) return null
        val found = coroutineScope { ids.map { kid -> async { kid to videos(kid) } }.awaitAll() }
        for ((kid, vids) in found) {
            val v = vids?.firstOrNull { (it.imdb == null || it.imdb == imdb) && it.season == season && it.imdbEpisode == episode }
            if (v != null) {
                ServerState.debug("Kitsu mapping: $imdb S${season}E$episode -> kitsu:$kid episode ${v.episode}")
                return kid to v.episode
            }
        }
        return null
    }

    /** The Kitsu entry of an AniList / MyAnimeList id. */
    suspend fun kitsuOf(scheme: String, id: String): String? = withContext(Dispatchers.IO) {
        val source = when (scheme) { "mal" -> "myanimelist"; else -> scheme }
        val key = "$source:$id"
        toKitsu[key]?.takeIf { fresh(it.first) }?.let { return@withContext it.second.ifEmpty { null } }
        val el = fetch("$ARM/ids?source=$source&id=$id") ?: return@withContext null
        val k = entries(el).firstNotNullOfOrNull { it.str("kitsu") }.orEmpty()
        if (toKitsu.size > 5_000) toKitsu.clear()
        toKitsu[key] = System.currentTimeMillis() to k
        k.ifEmpty { null }
    }

    /** The AniList id of Kitsu entry [kitsuId]. */
    suspend fun aniListOf(kitsuId: String): Int? = withContext(Dispatchers.IO) {
        toAniList[kitsuId]?.takeIf { fresh(it.first) }?.let { return@withContext it.second.takeIf { v -> v > 0 } }
        val el = fetch("$ARM/ids?source=kitsu&id=$kitsuId") ?: return@withContext null
        val a = entries(el).firstNotNullOfOrNull { it.int("anilist") } ?: 0
        if (toAniList.size > 5_000) toAniList.clear()
        toAniList[kitsuId] = System.currentTimeMillis() to a
        a.takeIf { it > 0 }
    }
}
