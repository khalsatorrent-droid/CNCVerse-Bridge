package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting
import com.cncverse.stremiobridge.state.ServerState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A fixed address for the free temporary Cloudflare link, through a relay: this device gets a permanent id, and the
 * relay's address for that id redirects to whatever `….trycloudflare.com` link the tunnel got this time. Every time
 * the tunnel starts, the new link is registered with the relay (`{shortId, tunnelUrl}`).
 *
 * Three relays speak the same protocol ([Relay]):
 * - Rockon (https://github.com/khalsatorrent-droid/Rockon, on Vercel): `https://rockon-xi.vercel.app/<id>` - the default.
 * - the same Rockon relay on the user's own Cloudflare Worker: `https://<worker>.workers.dev/<id>`.
 * - pencarimovie-server (https://github.com/aiskendi/pencarimovie-server): `https://<id>-tunnel.pencarimovie.com`.
 *   It belongs to its author, who allows it for personal use only (no monetising).
 *
 * OFF until the user turns it on in Settings.
 */
object StableLink {
    /** [refreshEveryMs]: how often a link the relay already took is registered again while the same tunnel runs. */
    enum class Relay(val label: String, val refreshEveryMs: Long) {
        // all: a new tunnel link is registered at once, the same one again only every 6 hours (a safety round in case
        // the relay lost or forgot the entry); a refused registration is retried every 15 minutes
        ROCKON("Rockon", 6 * 3_600_000L),
        /** The Rockon relay on the user's own Cloudflare Worker (Rockon repo, worker/rockon-worker.js): no wake-up delay. */
        WORKER("Your Worker", 6 * 3_600_000L),
        PENCARIMOVIE("pencarimovie", 6 * 3_600_000L);

        val endpoints: List<String>
            get() = when (this) {
                ROCKON -> listOf("https://rockon-xi.vercel.app/api/tunnel/register")
                WORKER -> StableLink.workerBase?.let { listOf("$it/api/tunnel/register") }.orEmpty()
                PENCARIMOVIE -> listOf(
                    "https://pencarimovie.com/wp-json/pencarimovie-server/v1/tunnel/register",
                    "https://tunnel.pencarimovie.com/api/tunnel/register",
                )
            }

        fun urlFor(id: String): String = when (this) {
            ROCKON -> "https://rockon-xi.vercel.app/$id"
            WORKER -> "${StableLink.workerBase ?: "https://<your-worker>.workers.dev"}/$id"
            PENCARIMOVIE -> "https://$id-tunnel.pencarimovie.com"
        }
    }

    private const val KEY_WORKER = "KEY_STABLE_LINK_WORKER"

    /** The address of the user's Rockon Worker (https://name.account.workers.dev), null until set. */
    val workerBase: String? get() = getExtensionSetting(KEY_WORKER)?.takeIf { it.isNotBlank() }

    /** "rockon.me.workers.dev", "https://rockon.me.workers.dev/" or a link under it -> https://rockon.me.workers.dev; null when not an address. */
    fun cleanWorkerBase(text: String): String? {
        val t = text.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
        val u = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        val host = u.host?.lowercase()?.takeIf { it.contains('.') && !it.endsWith('.') } ?: return null
        return "https://$host" + (if (u.port > 0 && u.port != 443) ":${u.port}" else "")
    }

    /** Saves the Worker address; false when it is not one. */
    fun setWorkerBase(text: String): Boolean {
        val clean = cleanWorkerBase(text) ?: return false
        setExtensionSetting(KEY_WORKER, clean)
        return true
    }

    private const val KEY_ENABLED = "KEY_STABLE_LINK_ENABLED"
    private const val KEY_ID = "KEY_STABLE_LINK_ID"
    private const val KEY_RELAY = "KEY_STABLE_LINK_RELAY"

    /** The relay in use. Installs that had the fixed link on before Rockon was added keep pencarimovie until changed (their link stays the same). */
    var relay: Relay
        get() = getExtensionSetting(KEY_RELAY)?.let { s -> Relay.entries.firstOrNull { it.name == s } }
            ?: if (enabled) Relay.PENCARIMOVIE else Relay.ROCKON
        set(v) = setExtensionSetting(KEY_RELAY, v.name)

    /** Paths of the Rockon site itself: never an id there. */
    private val RESERVED = setOf("api", "links", "register", "tunnel", "favicon", "robots")

    var enabled: Boolean
        get() = getExtensionSetting(KEY_ENABLED) == "true"
        set(v) {
            // the relay is fixed before the switch changes, so turning it on for the first time picks Rockon
            if (getExtensionSetting(KEY_RELAY) == null) setExtensionSetting(KEY_RELAY, relay.name)
            setExtensionSetting(KEY_ENABLED, if (v) "true" else "false")
        }

    /**
     * Permanent per-install id: "cb" + 10 random hex characters (40 random bits, 12 characters - the longest the relay
     * accepts), made from a secure random source the first time and then kept. Two devices therefore never share an id
     * (the "cb" prefix is the only fixed part; it keeps the id apart from the relay's own six-character ids). Ids made
     * by earlier versions (cb + 6 characters) or typed by the user ([setCustomId]) stay as they are, so existing links keep working.
     */
    val id: String
        get() {
            val saved = getExtensionSetting(KEY_ID)?.takeIf { cleanId(it) != null }
            if (saved != null) return saved
            return newId()
        }

    private fun newId(): String {
        val bytes = ByteArray(5).also { java.security.SecureRandom().nextBytes(it) }
        val fresh = "cb" + bytes.joinToString("") { "%02x".format(it) }
        setExtensionSetting(KEY_ID, fresh)
        return fresh
    }

    /**
     * Gives this device a new id, so the fixed address changes (the old address stops leading here). Used when the old
     * link got out, or someone else is using it. The Stremio add-on has to be installed again with the new address.
     */
    fun reset(): String = newId()

    /** An id the user typed: 4-12 lower-case letters or digits (what the relay accepts), or null. Spaces and capitals are tidied first. */
    fun cleanId(text: String): String? = text.trim().lowercase().takeIf { Regex("^[a-z0-9]{4,12}$").matches(it) && it !in RESERVED }

    /** Uses an id of the user's own choice (e.g. "merimarzi" gives https://rockon-xi.vercel.app/merimarzi). Returns false when it is not a valid id. */
    fun setCustomId(text: String): Boolean {
        val v = cleanId(text) ?: return false
        setExtensionSetting(KEY_ID, v)
        return true
    }

    val url: String get() = relay.urlFor(id)

    // The relays can be slow (pencarimovie notes 8-9 s per registration; Vercel starts cold): generous time limits
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    }

    /** One registration round on the relay's addresses; true when one accepted it. */
    private fun registerOnce(quickUrl: String): Boolean {
        val json = """{"shortId":"$id","tunnelUrl":"$quickUrl"}"""
        var accepted = false
        for (endpoint in relay.endpoints) {
            val code = runCatching {
                client.newCall(okhttp3.Request.Builder().url(endpoint).post(json.toRequestBody("application/json".toMediaType())).build())
                    .execute().use { it.code }
            }.getOrElse { -1 }
            if (code in 200..299) accepted = true
            else ServerState.debug("Fixed link: ${endpoint.substringAfter("//").substringBefore('/')} answered ${if (code < 0) "nothing (timed out)" else "HTTP $code"}")
        }
        return accepted
    }

    /** True when [url]/manifest.json answers. */
    private fun fixedLinkAnswers(): Boolean = runCatching {
        // a query of its own each time: Rockon's CDN caches a redirect per address for a few seconds, and right after a
        // registration that cached answer can still lead to the previous tunnel (the query reaches the bridge, which ignores it)
        client.newCall(okhttp3.Request.Builder().url("$url/manifest.json?check=${System.currentTimeMillis()}").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /**
     * Registers [quickUrl] and returns the fixed address once it really leads to this bridge (its /manifest.json answers),
     * or null when the relay did not take it - the caller keeps the temporary link (and may call again later).
     * A refused or slow registration is tried again (after 5, 15 and 30 s) before giving up. Blocking: background thread.
     */
    fun register(quickUrl: String): String? {
        var accepted = false
        for ((i, wait) in listOf(0L, 5_000L, 15_000L, 30_000L).withIndex()) {
            if (wait > 0) Thread.sleep(wait)
            if (registerOnce(quickUrl)) { accepted = true; break }
            if (i < 3) ServerState.info("Fixed link: the relay did not take the registration (try ${i + 1} of 4) - trying again")
        }
        if (!accepted) { ServerState.warn("Fixed link: the relay did not accept the registration after 4 tries - keeping the temporary link (tried again in 15 minutes)"); return null }
        // The quick tunnel and the relay need a moment: check that the fixed address answers before it is shown
        repeat(8) { attempt ->
            if (fixedLinkAnswers()) return url
            if (attempt < 7) Thread.sleep(2_500)
        }
        ServerState.warn("Fixed link: $url does not answer yet - keeping the temporary link (tried again in 15 minutes)")
        return null
    }

    /**
     * Keeps the relay pointed at [quickUrl] while the tunnel runs: registers again (quietly) every [Relay.refreshEveryMs] (15 minutes until it is taken), and when
     * the fixed link does not lead here any more. Returns the fixed address when it works, null otherwise.
     */
    fun refresh(quickUrl: String): String? {
        if (!registerOnce(quickUrl)) return null
        repeat(4) { attempt ->
            if (fixedLinkAnswers()) return url
            if (attempt < 3) Thread.sleep(2_500)
        }
        return null
    }
}
