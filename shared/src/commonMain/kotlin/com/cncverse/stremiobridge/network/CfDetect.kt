package com.cncverse.stremiobridge.network

import okhttp3.Response

/**
 * Is this answer a Cloudflare (or DDoS-Guard) challenge page? Same test the Raghav Nepu extension uses: the
 * `cf-mitigated: challenge` header settles it; otherwise a 403 / 503 whose body is empty or shows the "Just a moment" /
 * challenge-platform page counts - the Server header alone is not trusted (proxied sites hide it, and a plain 403 from
 * a Cloudflare site is not always a challenge).
 */
object CfDetect {
    private val MARKERS = listOf("just a moment", "challenge-platform", "checking your browser", "verify you are human", "attention required", "cf-turnstile")

    fun isChallenge(r: Response): Boolean {
        val mitigated = r.header("cf-mitigated")
        if (mitigated != null) return mitigated.contains("challenge", true)
        if (r.code != 403 && r.code != 503 && r.code != 429) return false
        val server = r.header("Server").orEmpty()
        val cfServer = server.contains("cloudflare", true) || server.contains("ddos-guard", true)
        val body = runCatching { r.peekBody(4000).string().lowercase() }.getOrDefault("")
        if (MARKERS.any { body.contains(it) }) return true
        // a bare 403 / 503 / 429 from a Cloudflare server with no usable body: treat as a challenge (the old rule)
        return cfServer && body.isBlank()
    }
}
