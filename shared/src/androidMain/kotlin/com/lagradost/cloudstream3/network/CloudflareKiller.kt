package com.lagradost.cloudstream3.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cncverse.stremiobridge.state.ServerState
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareKiller : Interceptor {
    private val sites = com.cncverse.stremiobridge.network.CfSites

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host
        appContext()?.let { com.cncverse.stremiobridge.network.CfCookieStore.initIfNeeded(it.cacheDir.path) }

        // A site solved before: send its saved clearance straight away (no challenge, no WebView)
        val saved = com.cncverse.stremiobridge.network.CfCookieStore.get(host)
        if (saved != null && request.header("Cookie").isNullOrBlank()) {
            request = request.newBuilder().header("Cookie", saved.cookie).header("User-Agent", saved.userAgent).build()
        }
        var response = chain.proceed(request)

        // A site that was queued / expired and now answers without a challenge: the check is gone
        if (saved == null && !isChallenged(response) && response.code in 200..399) {
            sites.get(host)?.takeIf { it.status == "NEEDS" || it.status == "EXPIRED" }?.let {
                ServerState.info("Cloudflare: $host no longer shows a challenge")
                sites.noteClear(host)
            }
        }
        // A site with a working clearance is looked at without it now and then, to notice a challenge that went away
        if (saved != null && !isChallenged(response) && sites.probeDue(host)) {
            Thread({ if (sites.probe(host) == true) ServerState.info("Cloudflare: $host no longer shows a challenge") }, "cf-probe").apply { isDaemon = true }.start()
        }

        val challenged = (response.code == 503 || response.code == 403 || response.code == 429) &&
            (response.header("Server")?.let { it.contains("cloudflare", true) || it.contains("ddos-guard", true) } == true ||
                response.header("cf-mitigated")?.contains("challenge", true) == true)
        if (challenged) {
            // The saved clearance did not work (expired / other UA / other IP): drop it and solve again
            if (saved != null) { com.cncverse.stremiobridge.network.CfCookieStore.forget(host); sites.noteExpired(host) }

            // Step 1: many "challenges" are only a block of requests that do not look like a browser. Same request
            // with the full header set of a Chrome page load (what Raghav's TheMoviesFlix does) often passes at once.
            if (!triedBrowserHeaders(request)) {
                val browserReq = browserLike(request)
                response.close()
                response = chain.proceed(browserReq)
                if (!isChallenged(response)) { sites.noteHeadersOk(host); return response }
                request = browserReq
            }
            sites.noteChallenge(host)

            // The user cancelled this site's check a moment ago (or nobody was there): it stays in the queue
            // (Settings -> Cloudflare checks) and background searches do not wait for it again
            if ((sites.get(host)?.skipUntil ?: 0L) > System.currentTimeMillis()) {
                ServerState.debug("Cloudflare: $host is queued for a check - skipped for now")
                return response
            }
            ServerState.info("Cloudflare challenge on $host - solving it in a browser (${sites.pending().size} site(s) waiting)")
            // One check on screen at a time; a request that cannot get its turn in 30 s leaves the site in the queue
            if (!solverLock.tryLock(30, TimeUnit.SECONDS)) {
                ServerState.warn("Cloudflare: $host is waiting for its turn (queued)")
                return response
            }
            val result = try {
                // Another request may have solved it while this one waited
                com.cncverse.stremiobridge.network.CfCookieStore.get(host)?.takeIf { it.cookie != saved?.cookie }?.let { CfResult(it.cookie, it.userAgent, null) }
                    ?: bypassCloudflare(request.url.toString(), interactive = false)
            } finally { solverLock.unlock() }
            if (result == null) sites.noteNotSolved(host)
            if (result != null) {
                if (result.cookie.isNotBlank()) {
                    com.cncverse.stremiobridge.network.CfCookieStore.put(host, result.cookie, result.userAgent)
                    sites.noteSolved(host, result.cookie)
                    val newRequest = request.newBuilder()
                        .header("Cookie", result.cookie)
                        .header("User-Agent", result.userAgent)
                        .build()
                    response.close()
                    response = chain.proceed(newRequest)
                    if (!isChallenged(response)) return response
                    // The fresh cookie was refused at once (other address / fingerprint): that is not "solved"
                    com.cncverse.stremiobridge.network.CfCookieStore.forget(host)
                    sites.noteSolveFailed(host)
                    ServerState.warn("Cloudflare: $host refused the cookie right after the check was solved - it stays in the queue")
                }
                // The site lets a real browser in but not this app's HTTP client (it checks the TLS handshake, not a
                // cookie): hand back the page the browser itself loaded
                val html = result.html
                if (html != null && request.method == "GET") {
                    ServerState.info("Cloudflare: $host answered the browser only - using the page the browser loaded")
                    response.close()
                    return Response.Builder()
                        .request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                }
            }
            ServerState.warn("Cloudflare challenge on $host could not be solved in time")
        }
        return response
    }

    private fun isChallenged(r: Response): Boolean =
        (r.code == 503 || r.code == 403 || r.code == 429) &&
            (r.header("Server")?.let { it.contains("cloudflare", true) || it.contains("ddos-guard", true) } == true ||
                r.header("cf-mitigated")?.contains("challenge", true) == true)

    private fun triedBrowserHeaders(r: okhttp3.Request) = r.header("Sec-Fetch-Mode") != null && r.header("sec-ch-ua") != null

    private fun browserLike(r: okhttp3.Request): okhttp3.Request {
        val b = r.newBuilder()
        val ua = r.header("User-Agent")?.takeIf { it.contains("Mozilla") && !it.contains("okhttp", true) }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        b.header("User-Agent", ua)
        fun set(k: String, v: String) { if (r.header(k) == null) b.header(k, v) }
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.9")
        b.header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        b.header("sec-ch-ua-mobile", "?0")
        b.header("sec-ch-ua-platform", "\"Windows\"")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", if (r.header("Referer") != null) "same-origin" else "none")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
        return b.build()
    }

    private fun lockFor(host: String): Any = locks.getOrPut(host) { Any() }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (e: Exception) { null }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        /** Only one check is shown at a time; the others wait in the queue. */
        private val solverLock = java.util.concurrent.locks.ReentrantLock()

        init {
            // Settings -> Cloudflare checks: "Solve now" / "Solve all" for sites queued earlier
            com.cncverse.stremiobridge.network.CfSites.solver = { host, done ->
                Thread({
                    var ok = false
                    solverLock.lock()
                    try {
                        val r = CloudflareKiller().bypassCloudflare("https://$host/", interactive = true)
                        if (r != null && r.cookie.isNotBlank()) {
                            com.cncverse.stremiobridge.network.CfCookieStore.put(host, r.cookie, r.userAgent)
                            com.cncverse.stremiobridge.network.CfSites.noteSolved(host, r.cookie)
                            ok = true
                        } else if (r != null) {
                            // the browser got in without a cookie: the check is gone
                            com.cncverse.stremiobridge.network.CfSites.noteClear(host)
                            ok = true
                        } else com.cncverse.stremiobridge.network.CfSites.noteNotSolved(host)
                    } finally { solverLock.unlock() }
                    done(ok)
                }, "cf-solve").apply { isDaemon = true }.start()
            }
        }
    }

    data class CfResult(val cookie: String, val userAgent: String, val html: String?)

    /**
     * Solves the challenge in a real WebView (like Raghav's extensions do). First invisibly for ~15 s (JS
     * challenges pass by themselves); if the site wants a tap (Turnstile), the same WebView is shown in a
     * dialog over the app so the user can finish it. The cookies are returned with this WebView's User-Agent.
     */
    private fun bypassCloudflare(url: String, interactive: Boolean): CfResult? {
        val appCtx = appContext() ?: return null
        val host = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<CfResult?>(null)
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        val maxMs = 110_000L
        val headlessMs = if (interactive) 0L else 15_000L

        main.post {
            var dialog: android.app.Dialog? = null
            var webView: WebView? = null
            fun finish() {
                runCatching { dialog?.dismiss() }
                runCatching { webView?.stopLoading(); webView?.destroy() }
                webView = null
                latch.countDown()
            }
            try {
                val wv = WebView(appCtx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                @Suppress("DEPRECATION") wv.settings.databaseEnabled = true
                wv.settings.javaScriptCanOpenWindowsAutomatically = true
                wv.settings.setSupportMultipleWindows(false)
                val userAgent = wv.settings.userAgentString
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                var pageFinished = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) { pageFinished = true }
                    override fun onPageStarted(view: WebView?, u: String?, f: android.graphics.Bitmap?) { pageFinished = false }
                }

                // The WebView keeps cookies between checks. A cf_clearance left from an earlier solve (the one the site just
                // refused) would be taken for the new answer at once - drop it before the page loads, and never accept an
                // old one afterwards (its issue time is inside the token)
                val staleBefore = CookieManager.getInstance().getCookie(url)
                    ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("cf_clearance=") }
                run {
                    val parts = host.split('.')
                    val domains = (0..maxOf(0, parts.size - 2)).map { parts.drop(it).joinToString(".") }
                    domains.forEach { d ->
                        listOf("https://$d/", "https://$d").forEach { u ->
                            CookieManager.getInstance().setCookie(u, "cf_clearance=; Max-Age=0; Path=/; Domain=$d")
                            CookieManager.getInstance().setCookie(u, "cf_clearance=; Max-Age=0; Path=/; Domain=.$d")
                        }
                    }
                    CookieManager.getInstance().flush()
                }

                fun cleared(): String? {
                    CookieManager.getInstance().flush()
                    val c = CookieManager.getInstance().getCookie(url) ?: return null
                    if (!c.contains("cf_clearance")) return null
                    val clearance = c.split(';').map { it.trim() }.firstOrNull { it.startsWith("cf_clearance=") } ?: return null
                    if (clearance == staleBefore) return null
                    val issued = com.cncverse.stremiobridge.network.CfSites.issuedAt(c)
                    if (issued > 0 && issued < started - 10 * 60_000L) return null // issued long before this check began: not its answer
                    return c
                }

                /** True when the check is on screen; false when the app is not in front (the site stays queued). */
                fun showDialog(): Boolean {
                    val act = com.lagradost.cloudstream3.CommonActivity.activity
                    if (act == null || act.isFinishing || act.isDestroyed) return false
                    return runCatching {
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        val dp = act.resources.displayMetrics.density
                        val box = android.widget.LinearLayout(act).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
                            val title = android.widget.TextView(act).apply {
                                val waiting = com.cncverse.stremiobridge.network.CfSites.pending().count { it.host != host }
                                text = "Complete the check for $host - tap the box if one appears" + if (waiting > 0) "  ($waiting more site(s) waiting)" else ""
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 14f
                                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                            }
                            addView(title)
                            addView(wv, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
                            val cancel = android.widget.Button(act).apply {
                                text = "Cancel"
                                setOnClickListener { finish() }
                            }
                            addView(cancel)
                        }
                        dialog = android.app.Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                            setContentView(box)
                            setCancelable(true)
                            setOnCancelListener { finish() }
                            show()
                        }
                        ServerState.info("Cloudflare: showing the check for $host so it can be completed")
                        true
                    }.getOrElse { ServerState.warn("Cloudflare dialog failed: ${it.message}"); false }
                }

                var shown = false
                var okPolls = 0
                fun poll() {
                    if (webView == null) return
                    val cookie = cleared()
                    if (cookie != null) { resultRef.set(CfResult(cookie, userAgent, null)); finish(); return }
                    if (System.currentTimeMillis() - started > maxMs) { finish(); return }
                    // No clearance cookie needed: a page that is no longer a challenge page means the browser got in
                    wv.evaluateJavascript("(function(){return document.title+'\\u0001'+document.documentElement.outerHTML})()") { raw ->
                        if (webView == null) return@evaluateJavascript
                        val text = try { org.json.JSONTokener(raw ?: "null").nextValue() as? String } catch (_: Exception) { null } ?: ""
                        val title = text.substringBefore('\u0001').lowercase()
                        val html = text.substringAfter('\u0001', "")
                        val challengeTitle = listOf("just a moment", "attention required", "checking your browser", "verify you are human",
                            "ddos-guard", "security check", "one more step", "access denied").any { title.contains(it) } || title.isBlank()
                        val challengeBody = html.length < 30_000 && (html.contains("challenge-platform") || html.contains("cf-turnstile") || html.contains("challenge-form"))
                        if (pageFinished && !challengeTitle && !challengeBody && html.length > 800) {
                            okPolls++
                            if (okPolls >= 2) {
                                CookieManager.getInstance().flush()
                                resultRef.set(CfResult(CookieManager.getInstance().getCookie(url).orEmpty(), userAgent, html))
                                finish()
                                return@evaluateJavascript
                            }
                        } else okPolls = 0
                        if (!shown && System.currentTimeMillis() - started > headlessMs) {
                            shown = true
                            // App in the background: nobody can tap the box - give up, the site stays queued for Settings
                            if (!showDialog()) { finish(); return@evaluateJavascript }
                        }
                        main.postDelayed({ poll() }, 1000)
                    }
                }
                wv.loadUrl(url)
                main.postDelayed({ poll() }, 1500)
            } catch (e: Exception) {
                ServerState.warn("WebView failed: ${e.message}")
                finish()
            }
        }
        latch.await(maxMs + 5_000, TimeUnit.MILLISECONDS)
        return resultRef.get()
    }
}
