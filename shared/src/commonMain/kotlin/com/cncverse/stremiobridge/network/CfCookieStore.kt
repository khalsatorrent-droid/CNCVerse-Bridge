package com.cncverse.stremiobridge.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloudflare clearance (cf_clearance and friends) of sites whose challenge was solved once.
 * Kept on disk so the next request - an extension's scrape, a stream download by the media
 * server - sends the same cookie and User-Agent instead of being challenged again.
 */
@Serializable
data class CfClearance(val host: String, val cookie: String, val userAgent: String, val savedAt: Long)

object CfCookieStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val map = ConcurrentHashMap<String, CfClearance>()
    @Volatile private var file: File? = null

    /**
     * A saved clearance is used until the site refuses it (its real lifetime is set by the site: 30 minutes to a year),
     * but not past this - the cookie is also tied to the IP address, which changes. A refusal is noted in [CfSites].
     */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    fun initIfNeeded(dir: String) { if (file == null) init(dir); CfSites.init(dir) }

    fun init(dir: String) {
        file = File(dir, "cf_cookies.json")
        map.clear()
        runCatching {
            val f = file ?: return
            if (f.exists()) json.decodeFromString<List<CfClearance>>(f.readText()).forEach { map[it.host] = it }
        }
    }

    /** The saved clearance for [host] (or its parent domain), or null when none / expired. */
    fun get(host: String): CfClearance? {
        val h = host.lowercase()
        val now = System.currentTimeMillis()
        var c = map[h]
        if (c == null) {
            // cookies of "example.com" also serve "www.example.com"
            c = map.values.firstOrNull { h == it.host || h.endsWith("." + it.host) || it.host.endsWith("." + h) }
        }
        if (c == null) {
            // another host of the same site (cdn.example.com / api.example.com): one Cloudflare zone, one clearance
            val apex = apexOf(h)
            c = map.values.filter { apexOf(it.host) == apex }.maxByOrNull { it.savedAt }
        }
        return c?.takeIf { now - it.savedAt < TTL_MS }
    }

    /** example.com for a.b.example.com, example.co.uk for a.example.co.uk. */
    fun apexOf(host: String): String {
        val l = host.lowercase().split('.')
        return if (l.size > 2 && l[l.size - 2].length <= 3 && l.last().length == 2) l.takeLast(3).joinToString(".")
        else l.takeLast(2).joinToString(".")
    }

    fun put(host: String, cookie: String, userAgent: String) {
        if (cookie.isBlank()) return
        map[host.lowercase()] = CfClearance(host.lowercase(), cookie, userAgent, System.currentTimeMillis())
        persist()
    }

    fun forget(host: String) {
        map.remove(host.lowercase()); persist()
    }

    fun clear() { map.clear(); persist() }

    fun count(): Int = map.values.count { System.currentTimeMillis() - it.savedAt < TTL_MS }

    private fun persist() {
        runCatching { file?.writeText(json.encodeToString(map.values.toList())) }
    }
}
