package com.cncverse.stremiobridge.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the saved Cloudflare clearance (cookie + the User-Agent it was solved with) on EVERY request to a site that was
 * solved once - not only the requests of extensions that bring their own CloudflareKiller. Without this the saved
 * cookie sat unused for most extensions and each search was challenged again.
 * A challenge seen on a site that has no saved clearance is put in the Settings -> Cloudflare checks queue, so it can be
 * solved ahead of time; a refused clearance is dropped and the site marked expired.
 */
object CfCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host
        val saved = runCatching { CfCookieStore.get(host) }.getOrNull()
        if (saved != null && request.header("Cookie").isNullOrBlank()) {
            request = request.newBuilder().header("Cookie", saved.cookie).header("User-Agent", saved.userAgent).build()
        }
        val response = chain.proceed(request)
        val challenged = CfDetect.isChallenge(response)
        if (challenged) runCatching {
            if (saved != null && CfCookieStore.get(host)?.cookie == saved.cookie) { CfCookieStore.forget(host); CfSites.noteExpired(host) }
            else if (saved == null && CfSites.get(host)?.status != "SOLVED") CfSites.noteChallenge(host)
        }
        return response
    }
}
