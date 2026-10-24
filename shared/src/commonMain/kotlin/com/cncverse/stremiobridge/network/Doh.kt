package com.cncverse.stremiobridge.network

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A public DNS-over-HTTPS resolver. [bootstrap] are its fixed IPs, so reaching it needs no DNS of its own. */
data class DohProvider(val id: String, val name: String, val url: String, val bootstrap: List<String>, val note: String)

object DohProviders {
    val ALL: List<DohProvider> = listOf(
        DohProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1"), "Fast, no filtering"),
        DohProvider("cloudflare-security", "Cloudflare Security", "https://security.cloudflare-dns.com/dns-query", listOf("1.1.1.2", "1.0.0.2"), "Blocks malware"),
        DohProvider("cloudflare-family", "Cloudflare Family", "https://family.cloudflare-dns.com/dns-query", listOf("1.1.1.3", "1.0.0.3"), "Blocks malware and adult sites"),
        DohProvider("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"), "Fast, no filtering"),
        DohProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"), "Blocks malware, privacy focused"),
        DohProvider("quad9-unfiltered", "Quad9 (unfiltered)", "https://dns10.quad9.net/dns-query", listOf("9.9.9.10", "149.112.112.10"), "No blocking"),
        DohProvider("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15"), "Blocks ads and trackers"),
        DohProvider("adguard-unfiltered", "AdGuard (unfiltered)", "https://unfiltered.adguard-dns.com/dns-query", listOf("94.140.14.140", "94.140.14.141"), "No blocking"),
        DohProvider("adguard-family", "AdGuard Family", "https://family.adguard-dns.com/dns-query", listOf("94.140.14.15", "94.140.15.16"), "Ads, trackers and adult sites"),
        DohProvider("opendns", "OpenDNS", "https://doh.opendns.com/dns-query", listOf("208.67.222.222", "208.67.220.220"), "Cisco, light filtering"),
        DohProvider("nextdns", "NextDNS", "https://dns.nextdns.io/dns-query", emptyList(), "Customisable (use Custom with your own ID URL)"),
        DohProvider("cleanbrowsing", "CleanBrowsing (security)", "https://doh.cleanbrowsing.org/doh/security-filter/", emptyList(), "Blocks malware and phishing"),
        DohProvider("mullvad", "Mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2"), "No logging, no filtering"),
        DohProvider("dnssb", "DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11"), "No logging, no filtering"),
        DohProvider("controld", "Control D (free)", "https://freedns.controld.com/p0", listOf("76.76.2.0", "76.76.10.0"), "No filtering"),
        DohProvider("libredns", "LibreDNS", "https://doh.libredns.gr/dns-query", listOf("116.202.176.26"), "No logging, no filtering"),
    )
    const val CUSTOM = "custom"
    fun byId(id: String): DohProvider? = ALL.firstOrNull { it.id == id }
}

/** What the user chose in Settings > DNS over HTTPS. */
data class DohSettings(
    val enabled: Boolean = false,
    val providerId: String = "cloudflare",
    /** Used when [providerId] is "custom": a full https://host/path DoH address. */
    val customUrl: String = "",
    /** Optional IPs for the custom resolver (comma separated), so its name needs no lookup. */
    val customBootstrap: String = "",
    /** When the resolver cannot be reached the normal DNS answers instead of the request failing. */
    val fallbackToSystem: Boolean = true,
) {
    fun provider(): DohProvider? =
        if (providerId == DohProviders.CUSTOM) {
            customUrl.trim().takeIf { it.startsWith("https://", true) }?.let {
                DohProvider(DohProviders.CUSTOM, "Custom", it, customBootstrap.split(',', ' ', ';').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, "")
            }
        } else DohProviders.byId(providerId) ?: DohProviders.ALL.first()
}

object DohConfig {
    private const val KEY = "doh_settings_v1"
    @Volatile private var cur: DohSettings? = null

    val settings: DohSettings
        get() = cur ?: load().also { cur = it }

    val enabled: Boolean get() = settings.let { it.enabled && it.provider() != null }

    fun update(s: DohSettings) {
        cur = s
        runCatching { setExtensionSetting(KEY, "doh1;" + listOf(if (s.enabled) "1" else "0", s.providerId, if (s.fallbackToSystem) "1" else "0", s.customUrl, s.customBootstrap).joinToString(";") { URLEncoder.encode(it, "UTF-8") }) }
        DohResolver.reset()
    }

    private fun load(): DohSettings = runCatching {
        val raw = getExtensionSetting(KEY)?.takeIf { it.startsWith("doh1;") } ?: return@runCatching DohSettings()
        val p = raw.removePrefix("doh1;").split(';').map { URLDecoder.decode(it, "UTF-8") }
        DohSettings(p.getOrNull(0) == "1", p.getOrNull(1)?.ifBlank { null } ?: "cloudflare", p.getOrNull(2) != "0", p.getOrNull(3).orEmpty(), p.getOrNull(4).orEmpty())
    }.getOrDefault(DohSettings())
}

/** DNS over HTTPS (RFC 8484, binary DNS messages) for every lookup while the setting is on. */
object DohResolver {
    private class Entry(val addrs: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun reset() = cache.clear()

    private fun bootstrapDns(p: DohProvider): Dns {
        val providerHost = p.url.substringAfter("://").substringBefore('/').substringBefore(':')
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                // IP literals: no lookup happens
                if (p.bootstrap.isNotEmpty() && hostname.equals(providerHost, true)) p.bootstrap.map { InetAddress.getByName(it) }
                else Dns.SYSTEM.lookup(hostname)
        }
    }

    private val clients = ConcurrentHashMap<String, okhttp3.OkHttpClient>()
    private fun clientFor(p: DohProvider) = clients.getOrPut(p.url + p.bootstrap.joinToString()) {
        okhttp3.OkHttpClient.Builder().dns(bootstrapDns(p)).connectTimeout(5, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    }

    /** Resolves [host] through the chosen resolver; falls back to the normal DNS only when the user allows it. */
    fun lookup(host: String): List<InetAddress> {
        if (host.equals("localhost", true)) return Dns.SYSTEM.lookup(host)
        // an IP written in the URL needs no lookup
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return InetAddress.getAllByName(host).toList()
        val key = host.lowercase()
        cache[key]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.addrs }
        val s = DohConfig.settings
        val p = s.provider()
        if (p != null) {
            val found = runCatching { query(p, host) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
            com.cncverse.stremiobridge.state.ServerState.warn("DoH (${p.name}) gave no answer for $host")
        }
        if (s.fallbackToSystem || p == null) return Dns.SYSTEM.lookup(host)
        throw java.net.UnknownHostException("DNS over HTTPS (${p.name}) could not resolve $host")
    }

    private fun query(p: DohProvider, host: String): List<InetAddress> {
        val key = host.lowercase()
        val out = LinkedHashSet<InetAddress>()
        var ttl = 300L
        for (type in intArrayOf(1, 28)) { // A, AAAA
            val body = DohWire.buildQuery(host, type)
            val req = okhttp3.Request.Builder().url(p.url)
                .header("Accept", "application/dns-message")
                .post(body.toRequestBody("application/dns-message".toMediaType())).build()
            runCatching {
                clientFor(p).newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val bytes = r.body?.bytes() ?: return@use
                    val ans = DohWire.parseAnswers(bytes)
                    ans.forEach { out.add(it.first); ttl = minOf(ttl, it.second.coerceAtLeast(30L)) }
                }
            }
        }
        val list = out.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }.sortedByDescending { it is java.net.Inet4Address }
        if (list.isNotEmpty()) cache[key] = Entry(list, System.currentTimeMillis() + minOf(ttl, 900L) * 1000)
        return list
    }

}

/** The binary DNS message format (RFC 1035): one question out, A / AAAA answers in. */
object DohWire {
    fun buildQuery(host: String, type: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id 0 (as RFC 8484 advises), recursion desired, 1 question
        for (label in host.trimEnd('.').split('.')) {
            val l = label.toByteArray(Charsets.US_ASCII)
            b.write(l.size); b.write(l)
        }
        b.write(0)
        b.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1)) // QTYPE, QCLASS IN
        return b.toByteArray()
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)

    /** Offset after the (possibly compressed) name starting at [i]. */
    private fun skipName(d: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            val len = d[i].toInt() and 0xff
            if (len == 0) return i + 1
            if ((len and 0xc0) == 0xc0) return i + 2
            i += len + 1
        }
    }

    /** The address records (with their TTL in seconds) of a response; empty for errors / no records. */
    fun parseAnswers(d: ByteArray): List<Pair<InetAddress, Long>> {
        if (d.size < 12 || (d[3].toInt() and 0x0f) != 0) return emptyList() // RCODE != NOERROR
        val qd = u16(d, 4); val an = u16(d, 6)
        var i = 12
        repeat(qd) { i = skipName(d, i) + 4 }
        val out = ArrayList<Pair<InetAddress, Long>>()
        repeat(an) {
            if (i >= d.size) return out
            i = skipName(d, i)
            val type = u16(d, i)
            val ttl = (u16(d, i + 4).toLong() shl 16) or u16(d, i + 6).toLong()
            val len = u16(d, i + 8)
            i += 10
            if (i + len > d.size) return out
            if ((type == 1 && len == 4) || (type == 28 && len == 16)) {
                out.add(InetAddress.getByAddress(d.copyOfRange(i, i + len)) to ttl)
            }
            i += len
        }
        return out
    }
}

/**
 * The DNS every HTTP client of the app uses: DNS over HTTPS while it is switched on, otherwise the
 * adaptive resolver (system DNS first, with its own DoH fallback chain) where this platform has it.
 */
object AppDns : Dns {
    private val adaptive: Dns by lazy {
        runCatching { Class.forName("com.cncverse.stremiobridge.network.AdaptiveHostDns").getField("INSTANCE").get(null) as Dns }
            .getOrDefault(Dns.SYSTEM)
    }

    override fun lookup(hostname: String): List<InetAddress> =
        if (DohConfig.enabled) DohResolver.lookup(hostname) else adaptive.lookup(hostname)
}
