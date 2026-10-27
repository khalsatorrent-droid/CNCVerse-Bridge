package com.cncverse.stremiobridge.server

import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Names that were found for an id (title / year, every name of an anime), kept on the device. When a later lookup fails
 * (Kitsu / AniList / TMDB slow or down, the app just restarted), the search still runs with these instead of finding nothing.
 */
object NameStore {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val map = ConcurrentHashMap<String, List<String>>()
    @Volatile private var file: File? = null
    private val lock = Any()

    fun init(cacheDir: String) {
        val f = File(cacheDir, "known_names.json")
        file = f
        runCatching {
            if (f.exists()) map.putAll(json.decodeFromString<Map<String, List<String>>>(f.readText()))
        }
    }

    fun get(key: String): List<String>? = map[key]

    fun put(key: String, value: List<String>) {
        if (value.isEmpty() || map[key] == value) return
        if (map.size > 3_000) map.keys.take(1_000).forEach { map.remove(it) }
        map[key] = value
        val f = file ?: return
        runCatching { synchronized(lock) { f.writeText(json.encodeToString(map.toMap())) } }
    }

    /** An [AnimeInfo] as a list of strings (and back): the few numbers first, then the names. */
    fun encode(a: AnimeInfo): List<String> = listOf(
        a.year?.toString().orEmpty(), a.episodeCount?.toString().orEmpty(), a.format.orEmpty(),
        a.seasonHint?.toString().orEmpty(), a.aniListId?.toString().orEmpty(), a.malId?.toString().orEmpty(),
    ) + a.titles

    fun decode(v: List<String>): AnimeInfo? {
        if (v.size < 7) return null
        return AnimeInfo(
            titles = v.drop(6), year = v[0].toIntOrNull(), episodeCount = v[1].toIntOrNull(), format = v[2].ifEmpty { null },
            seasonHint = v[3].toIntOrNull(), aniListId = v[4].toIntOrNull(), malId = v[5].toIntOrNull(),
        )
    }
}
