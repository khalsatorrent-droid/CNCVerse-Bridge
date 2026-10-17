package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The website version of the bridge: a small password-protected page at /home with the TMDB catalog,
 * search, details and a player, so a laptop browser needs neither Stremio nor Nuvio.
 * Streams come from the bridge's own /stream endpoint, the same list the add-on serves.
 */
object WebSite {
    @Serializable
    data class Prefs(val enabled: Boolean = false, val password: String = "")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var prefs: Prefs = Prefs()
        private set
    private var file: File? = null

    fun load(cacheDir: String) {
        val f = File(cacheDir, "web_prefs.json")
        file = f
        prefs = runCatching { json.decodeFromString<Prefs>(f.readText()) }.getOrDefault(Prefs())
    }

    /** Saves the switch and the password; turning it on needs a password of at least 4 characters. */
    fun update(p: Prefs): String? {
        val pw = p.password.take(100)
        if (p.enabled && pw.trim().length < 4) return "Set a password of at least 4 characters first"
        prefs = Prefs(p.enabled, pw)
        runCatching { file?.writeText(json.encodeToString(prefs)) }
            .onFailure { ServerState.warn("Failed to save web_prefs.json: ${it.message}") }
        return null
    }

    private const val COOKIE = "cncweb"

    private fun token(): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prefs.password.toByteArray(), "HmacSHA256"))
        return mac.doFinal("cncverse-web-v1".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun ApplicationCall.authed(): Boolean {
        val p = prefs
        if (!p.enabled || p.password.length < 4) return false
        val c = request.cookies[COOKIE] ?: return false
        return MessageDigest.isEqual(c.toByteArray(), token().toByteArray())
    }

    private fun ApplicationCall.isHttps(): Boolean =
        request.headers["X-Forwarded-Proto"]?.contains("https", true) == true || request.origin.scheme == "https"

    // ── Login attempts: 5 wrong passwords lock that address out for a minute ──
    private class Tries(var count: Int = 0, var until: Long = 0L)
    private val tries = ConcurrentHashMap<String, Tries>()

    private fun ApplicationCall.who(): String =
        request.headers["CF-Connecting-IP"] ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
            ?: request.local.remoteHost

    // ── TMDB shaping ──
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun img(path: String?, size: String): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun item(o: JsonObject, forced: String?): JsonObject? {
        val type = forced ?: o.str("media_type") ?: return null
        if (type != "movie" && type != "tv") return null
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: o.str("name") ?: return null
        val date = o.str("release_date") ?: o.str("first_air_date") ?: ""
        return buildJsonObject {
            put("id", id); put("type", type); put("title", title)
            put("year", date.take(4))
            img(o.str("poster_path"), "w342")?.let { put("poster", it) }
            o.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { put("rating", Math.round(it * 10) / 10.0) }
        }
    }

    private fun items(r: JsonObject?, forced: String?): JsonArray = buildJsonArray {
        r?.arr("results")?.forEach { e -> (e as? JsonObject)?.let { item(it, forced) }?.let { add(it) } }
    }

    private class Cached(val at: Long, val body: String)
    private var rowsCache: Cached? = null

    private suspend fun rows(): String {
        rowsCache?.let { if (System.currentTimeMillis() - it.at < 10 * 60_000) return it.body }
        val defs = listOf(
            Triple("Trending movies", "trending/movie/week", "movie"),
            Triple("Trending series", "trending/tv/week", "tv"),
            Triple("Popular movies", "movie/popular", "movie"),
            Triple("Popular series", "tv/popular", "tv"),
            Triple("Top rated movies", "movie/top_rated", "movie"),
            Triple("Top rated series", "tv/top_rated", "tv"),
        )
        val got = coroutineScope { defs.map { d -> async { StremioServer.tmdb(d.second) } }.awaitAll() }
        val body = buildJsonArray {
            defs.forEachIndexed { i, d ->
                val list = items(got[i], d.third)
                if (list.isNotEmpty()) add(buildJsonObject { put("title", d.first); put("items", list) })
            }
        }.toString()
        if (body != "[]") rowsCache = Cached(System.currentTimeMillis(), body)
        return body
    }

    private suspend fun ApplicationCall.json(body: String) = respondText(body, ContentType.Application.Json)

    private suspend fun ApplicationCall.guard(): Boolean {
        if (authed()) return true
        respond(HttpStatusCode.Unauthorized)
        return false
    }

    private val DIGITS = Regex("[0-9]{1,10}")
    private val ID_OK = Regex("tmdb:[0-9]{1,10}(:[0-9]{1,4}:[0-9]{1,4})?")
    private const val TV_PAGE = 120
    private val LEADING_SYMBOLS = Regex("^[^\\p{L}\\p{N}]+")

    /** The A-Z group of a channel name (leading symbols and emoji ignored); "#" for digits and other scripts. */
    private fun letterOf(name: String): String {
        val c = name.replace(LEADING_SYMBOLS, "").firstOrNull()?.uppercaseChar()
        return if (c != null && c in 'A'..'Z') c.toString() else "#"
    }

    /** One channel for the page: the same name from several sources is one entry that holds every source's id. */
    private class ChannelGroup(val name: String, val key: String, val letter: String, val poster: String?, val ids: List<String>)

    private var groupedFrom: List<com.cncverse.stremiobridge.model.StremioMeta>? = null
    private var grouped: List<ChannelGroup> = emptyList()
    private val NAME_SEP = Regex("[^\\p{L}\\p{N}]+")

    private suspend fun channelGroups(): List<ChannelGroup> {
        val all = StremioServer.liveChannels()
        if (all === groupedFrom) return grouped
        val by = LinkedHashMap<String, MutableList<com.cncverse.stremiobridge.model.StremioMeta>>()
        for (m in all) {
            val key = m.name.lowercase().replace(NAME_SEP, " ").trim()
            if (key.isNotEmpty()) by.getOrPut(key) { mutableListOf() }.add(m)
        }
        grouped = by.map { (key, ms) ->
            ChannelGroup(
                ms.first().name.trim(), key, letterOf(ms.first().name),
                ms.firstNotNullOfOrNull { m -> (m.poster ?: m.logo)?.takeIf { it.startsWith("http") } },
                ms.map { it.id }.distinct().take(8),
            )
        }
        groupedFrom = all
        return grouped
    }

    fun Route.webRoutes() {
        get("/home") {
            if (!prefs.enabled) return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(WebPage.HTML, ContentType.Text.Html)
        }
        get("/home/") { call.respondRedirect("/home") }

        post("/web/login") {
            if (!prefs.enabled) return@post call.respond(HttpStatusCode.NotFound)
            val who = call.who()
            val t = tries.getOrPut(who) { Tries() }
            if (System.currentTimeMillis() < t.until) return@post call.respond(HttpStatusCode.TooManyRequests)
            val pw = call.receiveParameters()["password"].orEmpty()
            val ok = prefs.password.length >= 4 &&
                MessageDigest.isEqual(pw.toByteArray(), prefs.password.toByteArray())
            if (!ok) {
                delay(800)
                if (++t.count >= 5) { t.count = 0; t.until = System.currentTimeMillis() + 60_000 }
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            tries.remove(who)
            call.response.cookies.append(
                Cookie(
                    COOKIE, token(), maxAge = 30 * 24 * 3600, path = "/", httpOnly = true,
                    secure = call.isHttps(), extensions = mapOf("SameSite" to "Lax"),
                )
            )
            call.respond(HttpStatusCode.OK)
        }
        post("/web/logout") {
            call.response.cookies.append(Cookie(COOKIE, "", maxAge = 0, path = "/", httpOnly = true))
            call.respond(HttpStatusCode.OK)
        }

        get("/web/api/session") {
            if (!prefs.enabled) return@get call.respond(HttpStatusCode.NotFound)
            call.json("""{"ok":${call.authed()}}""")
        }
        get("/web/api/config") {
            if (!call.guard()) return@get
            call.json(buildJsonObject { put("bases", buildJsonArray { StremioServer.webBases().forEach { add(it) } }) }.toString())
        }
        get("/web/api/rows") {
            if (!call.guard()) return@get
            call.json(rows())
        }
        get("/web/api/search") {
            if (!call.guard()) return@get
            val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100)
            if (q.isEmpty()) return@get call.json("[]")
            val r = StremioServer.tmdb("search/multi?include_adult=false&query=" + java.net.URLEncoder.encode(q, "UTF-8"))
            call.json(items(r, null).toString())
        }
        get("/web/api/tv") {
            if (!call.guard()) return@get
            val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100).lowercase()
            val letter = call.request.queryParameters["l"]?.trim().orEmpty().take(1).uppercase()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val words = q.split(' ').filter { it.isNotEmpty() }
            val found = channelGroups().filter { c ->
                (words.isEmpty() || words.all { c.key.contains(it) }) && (letter.isEmpty() || c.letter == letter)
            }
            call.json(buildJsonObject {
                put("total", found.size)
                put("items", buildJsonArray {
                    found.drop(offset).take(TV_PAGE).forEach { c ->
                        add(buildJsonObject {
                            put("name", c.name)
                            c.poster?.let { put("poster", it) }
                            put("ids", buildJsonArray { c.ids.forEach { add(it) } })
                        })
                    }
                })
            }.toString())
        }
        // Live results of a title: start=1 begins the search, later calls return the links found so far (and how far the search is)
        get("/web/api/streams") {
            if (!call.guard()) return@get
            val type = call.request.queryParameters["type"]
            val id = call.request.queryParameters["id"].orEmpty()
            if ((type != "movie" && type != "series") || !ID_OK.matches(id)) return@get call.respond(HttpStatusCode.BadRequest)
            call.json(StremioServer.webStreamsJson(type, id, call.request.queryParameters["start"] == "1"))
        }
        get("/web/api/title") {
            if (!call.guard()) return@get
            val type = call.request.queryParameters["type"]
            val id = call.request.queryParameters["id"].orEmpty()
            if ((type != "movie" && type != "tv") || !DIGITS.matches(id)) return@get call.respond(HttpStatusCode.BadRequest)
            val o = StremioServer.tmdb("$type/$id") ?: return@get call.respond(HttpStatusCode.BadGateway)
            val date = o.str("release_date") ?: o.str("first_air_date") ?: ""
            val runtime = o.str("runtime")?.toIntOrNull()
                ?: (o.arr("episode_run_time")?.firstOrNull() as? JsonPrimitive)?.intOrNull
            call.json(buildJsonObject {
                put("id", id); put("type", type)
                put("title", o.str("title") ?: o.str("name") ?: "")
                put("year", date.take(4))
                put("overview", o.str("overview") ?: "")
                img(o.str("backdrop_path"), "w1280")?.let { put("backdrop", it) }
                img(o.str("poster_path"), "w342")?.let { put("poster", it) }
                o.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { put("rating", Math.round(it * 10) / 10.0) }
                runtime?.takeIf { it > 0 }?.let { put("runtime", it) }
                put("genres", buildJsonArray { o.arr("genres")?.forEach { g -> (g as? JsonObject)?.str("name")?.let { add(it) } } })
                if (type == "tv") put("seasons", buildJsonArray {
                    o.arr("seasons")?.forEach { s ->
                        val so = s as? JsonObject ?: return@forEach
                        val n = so.str("season_number")?.toIntOrNull() ?: return@forEach
                        if (n > 0) add(buildJsonObject {
                            put("n", n); put("name", so.str("name") ?: "Season $n")
                            put("episodes", so.str("episode_count")?.toIntOrNull() ?: 0)
                        })
                    }
                })
            }.toString())
        }
        get("/web/api/season") {
            if (!call.guard()) return@get
            val id = call.request.queryParameters["id"].orEmpty()
            val n = call.request.queryParameters["n"]?.toIntOrNull()
            if (!DIGITS.matches(id) || n == null || n < 0 || n > 999) return@get call.respond(HttpStatusCode.BadRequest)
            val o = StremioServer.tmdb("tv/$id/season/$n") ?: return@get call.respond(HttpStatusCode.BadGateway)
            call.json(buildJsonArray {
                o.arr("episodes")?.forEach { e ->
                    val eo = e as? JsonObject ?: return@forEach
                    val num = eo.str("episode_number")?.toIntOrNull() ?: return@forEach
                    add(buildJsonObject {
                        put("e", num); put("name", eo.str("name") ?: "Episode $num")
                        put("overview", eo.str("overview") ?: "")
                        put("date", eo.str("air_date") ?: "")
                        img(eo.str("still_path"), "w300")?.let { put("still", it) }
                    })
                }
            }.toString())
        }
    }
}
