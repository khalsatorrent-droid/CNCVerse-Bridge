package com.cncverse.stremiobridge.tunnel

/**
 * Decides, from one check every ~30 s, whether the Cloudflare tunnel of Stremio Mode has failed and must be restarted
 * (Stremio Mode switched off and on again). Pure logic so it can be tested; the checks themselves are done by the caller.
 *
 * A tunnel counts as failed when
 *  - its process is gone, or
 *  - it has no public link [NO_LINK_MS] after it was started, or
 *  - it has a link but the link has not answered [PROBE_FAILS] checks in a row while the internet itself is reachable.
 * Restarts are spaced out (1, 2, 4 ... up to 10 minutes) so a long outage does not turn into a restart loop.
 */
class TunnelHealth(private val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val NO_LINK_MS = 120_000L
        const val PROBE_FAILS = 3
        const val MIN_GAP_MS = 60_000L
        const val MAX_GAP_MS = 10 * 60_000L
    }

    private var startedAt = nowMs()
    private var probeFails = 0
    private var lastRestart = 0L
    private var gap = MIN_GAP_MS

    /** Call right after the tunnel was (re)started by anyone. */
    fun started() { startedAt = nowMs(); probeFails = 0 }

    /**
     * @param alive the cloudflared process is running
     * @param hasLink the tunnel has announced its public link
     * @param linkAnswers the link answered a request (null = not checked / internet unreachable, which counts for nothing)
     * @return the reason to restart, or null when everything is fine (or a restart is not allowed yet)
     */
    fun check(alive: Boolean, hasLink: Boolean, linkAnswers: Boolean?): String? {
        val now = nowMs()
        if (hasLink && linkAnswers == true) { probeFails = 0; gap = MIN_GAP_MS }
        var reason: String? = null
        if (!alive) reason = "the tunnel process stopped"
        else if (!hasLink && now - startedAt > NO_LINK_MS) reason = "the tunnel did not get a public link"
        else if (hasLink && linkAnswers == false) {
            probeFails++
            if (probeFails >= PROBE_FAILS) reason = "the tunnel link stopped answering"
        }
        if (reason == null) return null
        if (lastRestart != 0L && now - lastRestart < gap) return null
        lastRestart = now
        gap = minOf(gap * 2, MAX_GAP_MS)
        probeFails = 0
        startedAt = now
        return reason
    }
}
