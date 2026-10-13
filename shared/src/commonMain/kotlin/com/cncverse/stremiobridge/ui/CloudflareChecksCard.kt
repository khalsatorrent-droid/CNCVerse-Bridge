package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.network.CfSite
import com.cncverse.stremiobridge.network.CfSites

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

private fun span(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 60 -> "$m min"
        m < 48 * 60 -> "${m / 60} h ${m % 60} min"
        else -> "${m / 1440} days"
    }
}

/**
 * Sites that showed a Cloudflare check: solve them ahead of time, see how long each saved cookie lasts, re-check whether
 * a site still challenges at all, forget a cookie. A site whose check is waiting is also asked about when it is used.
 */
@Composable
fun CloudflareChecksCard() {
    val sites by CfSites.sites.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val solver = remember { CfSites.ensureSolver(); CfSites.solver }
    // the clock for "x min ago" labels
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); tick++ } }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Cloudflare checks",
            "Websites that asked for a Cloudflare check (\"Just a moment…\") are listed here. Solve one ahead of time and its cookie is saved and used for that site until Cloudflare refuses it; the bridge notes how long each cookie really lasted and re-checks now and then whether a site still challenges at all.",
        )
        val waiting = sites.count { it.status == "NEEDS" || it.status == "EXPIRED" }
        if (sites.isEmpty()) {
            Text("No site has asked for a check yet.", color = TextMuted, fontSize = 12.sp)
        } else if (waiting > 0 && solver != null) {
            ActionButton("Solve all waiting sites ($waiting)", primary = true, enabled = busy == null) {
                val queue = CfSites.pending().map { it.host }
                fun next(i: Int) {
                    if (i >= queue.size) { busy = null; message = "Done"; return }
                    busy = queue[i]
                    solver(queue[i]) { next(i + 1) }
                }
                message = null
                next(0)
            }
            Spacer(Modifier.height(8.dp))
        }
        val clock = tick // re-draws the "x min ago" labels
        sites.forEach { s -> key(s.host, clock) { SiteRow(s, solver, busy) { busy = it; if (it == null) message = null } } }
        busy?.let { Text("Waiting for $it …", color = Amber400, fontSize = 11.sp) }
        message?.let { Text(it, color = Green400, fontSize = 11.sp) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(8.dp))
        Text("About the cookie", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "It is named cf_clearance and looks like cf_clearance=<random>-<time it was issued>-<version>-<signature>.",
            "It only works with the same browser identity (User-Agent) and internet address it was made with, so it is saved together with the User-Agent.",
            "Each site decides how long it lasts (30 minutes up to a year). The lifetime shown is how long the last cookie really worked.",
            "If a site stops challenging, it is marked \"No check now\" and its cookie is dropped.",
        ).forEach { Text("• $it", color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp) }
    }
}

@Composable
private fun SiteRow(s: CfSite, solver: ((String, (Boolean) -> Unit) -> Unit)?, busy: String?, setBusy: (String?) -> Unit) {
    val (label, color) = when (s.status) {
        "SOLVED" -> "Solved" to Green400
        "EXPIRED" -> "Expired - solve again" to Amber400
        "CLEAR" -> "No check now" to Green400
        "HEADERS" -> "Passes with browser headers" to Green400
        else -> "Waiting for you" to Amber400
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(s.host, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    val details = buildList {
        add(if (s.status == "SOLVED" && s.solvedAt > 0) "Solved ${ago(s.solvedAt)}" else label)
        if (s.status == "SOLVED" && s.cookieIssuedAt > 0) add("cookie issued ${ago(s.cookieIssuedAt)}" + if (s.solvedAt - s.cookieIssuedAt > 30 * 60_000L) " (older than the check - may be refused)" else "")
        if (s.lastLifetimeMs > 0) add("last cookie worked ${span(s.lastLifetimeMs)}")
        if (s.lastChallenge > 0 && s.status != "CLEAR") add("challenged ${ago(s.lastChallenge)}")
        if (s.status == "CLEAR" && s.lastClear > 0) add("checked ${ago(s.lastClear)}")
    }.joinToString(" • ")
    Text(details, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (solver != null && s.status != "CLEAR") {
            ActionButton(if (s.status == "SOLVED") "Solve again" else "Solve now", enabled = busy == null) {
                setBusy(s.host)
                solver(s.host) { setBusy(null) }
            }
        }
        ActionButton("Re-check", enabled = busy == null) {
            setBusy(s.host)
            Thread({ CfSites.probe(s.host); setBusy(null) }, "cf-probe").apply { isDaemon = true }.start()
        }
        ActionButton("Forget", danger = true, enabled = busy == null) { CfSites.forget(s.host) }
    }
}
