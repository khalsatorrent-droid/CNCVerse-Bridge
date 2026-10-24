package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting
import com.cncverse.stremiobridge.state.ServerState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A fixed address for the free temporary Cloudflare link, through the pencarimovie-server relay
 * (https://github.com/aiskendi/pencarimovie-server): this device gets a permanent id, and
 * `https://<id>-tunnel.pencarimovie.com` redirects to whatever `….trycloudflare.com` link the tunnel got this time.
 * Every time the tunnel starts, the new link is registered with the relay (`{shortId, tunnelUrl}`).
 *
 * The relay belongs to the pencarimovie-server author, who allows it for personal use only (no monetising), so this is
 * OFF until the user turns it on in Settings.
 */
object StableLink {
    private const val KEY_ENABLED = "KEY_STABLE_LINK_ENABLED"
    private const val KEY_ID = "KEY_STABLE_LINK_ID"
    private const val PRIMARY = "https://pencarimovie.com/wp-json/pencarimovie-server/v1/tunnel/register"
    private const val FALLBACK = "https://tunnel.pencarimovie.com/api/tunnel/register"

    var enabled: Boolean
        get() = getExtensionSetting(KEY_ENABLED) == "true"
        set(v) = setExtensionSetting(KEY_ENABLED, if (v) "true" else "false")

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
    fun cleanId(text: String): String? = text.trim().lowercase().takeIf { Regex("^[a-z0-9]{4,12}$").matches(it) }

    /** Uses an id of the user's own choice (e.g. "merimarzi" gives https://merimarzi-tunnel.pencarimovie.com). Returns false when it is not a valid id. */
    fun setCustomId(text: String): Boolean {
        val v = cleanId(text) ?: return false
        setExtensionSetting(KEY_ID, v)
        return true
    }

    val url: String get() = "https://$id-tunnel.pencarimovie.com"

    private val client by lazy {
        okhttp3.OkHttpClient.Builder().dns(com.cncverse.stremiobridge.network.AppDns).connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    /**
     * Registers [quickUrl] and returns the fixed address once it really leads to this bridge (its /manifest.json answers),
     * or null when the relay could not be reached or the address does not work yet - the caller keeps the temporary link.
     * Blocking: call from a background thread.
     */
    fun register(quickUrl: String): String? {
        val body = """{"shortId":"$id","tunnelUrl":"$quickUrl"}""".toRequestBody("application/json".toMediaType())
        var accepted = false
        for (endpoint in listOf(PRIMARY, FALLBACK)) {
            val ok = runCatching {
                client.newCall(okhttp3.Request.Builder().url(endpoint).post(body).build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            accepted = accepted || ok
        }
        if (!accepted) { ServerState.warn("Fixed link: the relay did not accept the registration - keeping the temporary link"); return null }
        // The quick tunnel and the relay need a moment: check that the fixed address answers before it is shown
        repeat(6) { attempt ->
            val live = runCatching {
                client.newCall(okhttp3.Request.Builder().url("$url/manifest.json").build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (live) return url
            if (attempt < 5) Thread.sleep(2_500)
        }
        ServerState.warn("Fixed link: $url does not answer yet - keeping the temporary link")
        return null
    }
}
