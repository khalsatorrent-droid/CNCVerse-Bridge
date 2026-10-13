package com.cncverse.stremiobridge.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Every site that showed a Cloudflare check, and where its check stands:
 *  - NEEDS   : challenged and not solved yet (the "queue" - the user can solve it in Settings, ahead of time)
 *  - SOLVED  : a clearance cookie is saved and works
 *  - EXPIRED : the saved cookie stopped working (its lifetime is remembered); needs solving again
 *  - HEADERS : the "challenge" was only a block of requests that do not look like a browser; browser headers get through
 *  - CLEAR   : the site no longer challenges (a plain request got through) - the saved cookie was dropped
 *
 * What the cookie looks like: `cf_clearance=<random>-<unix time it was issued>-<version>-<signature>`. It is tied to the
 * User-Agent and the IP address it was solved with, and lives as long as the site's owner set (30 minutes to a year,
 * default 30 minutes to a few hours), so it is used until it fails, and the lifetime it really had is written down.
 */
@Serializable
data class CfSite(
    val host: String,
    val status: String = "NEEDS",
    val firstSeen: Long = 0,
    val lastChallenge: Long = 0,
    val solvedAt: Long = 0,
    /** Time the cookie says it was issued (taken from the token), 0 when unknown. */
    val cookieIssuedAt: Long = 0,
    /** How long the previous cookie worked, ms. */
    val lastLifetimeMs: Long = 0,
    val lastClear: Long = 0,
    val lastProbe: Long = 0,
    /** Background requests do not ask for this site again before this time (the user cancelled / was away). */
    val skipUntil: Long = 0,
    val challengeCount: Int = 0,
)

object CfSites {
    private val json = Json { ignoreUnknownKeys = true }
    private val map = ConcurrentHashMap<String, CfSite>()
    @Volatile private var file: File? = null

    /** All known sites, newest challenge first - what the Settings card shows. */
    val sites = MutableStateFlow<List<CfSite>>(emptyList())

    /** Set by the Android WebView solver: (host, onDone(success)). Null where the platform has no interactive solver. */
    @Volatile var solver: ((String, (Boolean) -> Unit) -> Unit)? = null

    /** A site whose challenge is queued is not asked again by background searches for this long after a failed ask. */
    const val SKIP_MS = 10L * 60 * 1000
    /** A site with a saved clearance is checked without it this often, to notice a challenge that is gone. */
    private const val PROBE_EVERY_MS = 12L * 60 * 60 * 1000

    fun init(dir: String) {
        if (file != null) return
        file = File(dir, "cf_sites.json")
        runCatching {
            val f = file ?: return
            if (f.exists()) json.decodeFromString<List<CfSite>>(f.readText()).forEach { map[it.host] = it }
        }
        publish()
    }

    /** Loads the platform's solver (the Android one registers itself when its class is loaded). */
    fun ensureSolver() {
        if (solver == null) runCatching { Class.forName("com.lagradost.cloudstream3.network.CloudflareKiller") }
    }

    fun get(host: String): CfSite? = map[host.lowercase()]

    fun pending(): List<CfSite> = map.values.filter { it.status == "NEEDS" || it.status == "EXPIRED" }.sortedBy { it.firstSeen }

    private fun update(host: String, f: (CfSite) -> CfSite) {
        val h = host.lowercase()
        val now = System.currentTimeMillis()
        map[h] = f(map[h] ?: CfSite(host = h, firstSeen = now))
        save()
    }

    private fun save() {
        publish()
        runCatching { file?.writeText(json.encodeToString(map.values.toList())) }
    }

    private fun publish() { sites.value = map.values.sortedByDescending { maxOf(it.lastChallenge, it.solvedAt, it.firstSeen) } }

    /** A request to [host] was challenged. */
    fun noteChallenge(host: String) = update(host) { s ->
        val now = System.currentTimeMillis()
        s.copy(status = if (s.status == "SOLVED" || s.status == "EXPIRED") s.status else "NEEDS",
            lastChallenge = now, challengeCount = s.challengeCount + 1)
    }

    /** Only the missing browser headers were the problem: nothing to solve. */
    fun noteHeadersOk(host: String) = update(host) { s ->
        if (s.status == "SOLVED") s else s.copy(status = "HEADERS", lastChallenge = System.currentTimeMillis(), challengeCount = s.challengeCount + 1)
    }

    /** The challenge was solved and [cookie] saved. */
    fun noteSolved(host: String, cookie: String) = update(host) { s ->
        val now = System.currentTimeMillis()
        s.copy(status = "SOLVED", solvedAt = now, cookieIssuedAt = issuedAt(cookie), skipUntil = 0)
    }.also {
        val at = issuedAt(cookie)
        com.cncverse.stremiobridge.state.ServerState.info("Cloudflare: check solved for $host" + if (at > 0) " (cookie issued ${(System.currentTimeMillis() - at) / 60_000} min ago)" else "")
    }

    /** A cookie was saved but the site still challenged with it (stale, other address): not solved, queued again. */
    fun noteSolveFailed(host: String) = update(host) { s ->
        s.copy(status = "NEEDS", lastChallenge = System.currentTimeMillis(), skipUntil = System.currentTimeMillis() + SKIP_MS)
    }

    /** The saved cookie was refused: remember how long it lasted. */
    fun noteExpired(host: String) = update(host) { s ->
        val now = System.currentTimeMillis()
        val started = if (s.solvedAt > 0) s.solvedAt else s.cookieIssuedAt
        s.copy(status = "EXPIRED", lastLifetimeMs = if (started > 0) now - started else s.lastLifetimeMs)
    }

    /** The user closed the check, or nobody was there to do it: leave the site queued, do not nag. */
    fun noteNotSolved(host: String) = update(host) { it.copy(skipUntil = System.currentTimeMillis() + SKIP_MS) }

    /** A plain request got through: the site no longer challenges. */
    fun noteClear(host: String) {
        val s = get(host)
        if (s != null && s.status == "CLEAR") return
        CfCookieStore.forget(host)
        update(host) { it.copy(status = "CLEAR", lastClear = System.currentTimeMillis(), skipUntil = 0) }
    }

    fun forget(host: String) {
        CfCookieStore.forget(host)
        map.remove(host.lowercase()); save()
    }

    fun clearAll() { CfCookieStore.clear(); map.clear(); save() }

    /** The issue time written in the cookie token (`…-1760000000-1.2.1.1-…`), in ms, or 0. */
    fun issuedAt(cookie: String): Long {
        val value = Regex("cf_clearance=([^;\\s]+)").find(cookie)?.groupValues?.get(1) ?: return 0
        val sec = Regex("-(\\d{10})-").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: return 0
        return sec * 1000
    }

    /** True when the site with a working clearance has not been checked without it for a long time. */
    fun probeDue(host: String): Boolean {
        val s = get(host) ?: return false
        return s.status == "SOLVED" && System.currentTimeMillis() - maxOf(s.lastProbe, s.solvedAt) > PROBE_EVERY_MS
    }

    /**
     * Asks the site once without any cookie, like a first-time visitor with a browser's headers.
     * true = no challenge (marks the site CLEAR), false = challenged, null = no answer. Blocking.
     */
    fun probe(host: String): Boolean? {
        update(host) { it.copy(lastProbe = System.currentTimeMillis()) }
        return try {
            val dns = runCatching { Class.forName("com.cncverse.stremiobridge.network.AdaptiveHostDns").getField("INSTANCE").get(null) as okhttp3.Dns }
                .getOrDefault(okhttp3.Dns.SYSTEM)
            val client = okhttp3.OkHttpClient.Builder().dns(dns).followRedirects(true)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
            val req = okhttp3.Request.Builder().url("https://$host/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                .header("Sec-Fetch-Dest", "document").header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Site", "none")
                .build()
            client.newCall(req).execute().use { r ->
                val cf = r.header("Server")?.let { it.contains("cloudflare", true) || it.contains("ddos-guard", true) } == true ||
                    r.header("cf-mitigated") != null
                val blocked = r.code == 403 || r.code == 503 || r.code == 429
                val body = if (r.code == 200) r.body?.string()?.take(40_000).orEmpty().lowercase() else ""
                val challengePage = body.contains("just a moment") || (body.length < 30_000 && (body.contains("challenge-platform") || body.contains("cf-turnstile")))
                if (blocked && cf || challengePage) false
                else if (r.code in 200..399) { noteClear(host); true }
                else null
            }
        } catch (_: Exception) { null }
    }
}
