package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cncverse.stremiobridge.state.*

private data class CardRow(
    val id: String,
    val name: String,
    val requests: Int,
    val okPct: Int,
    val avgLinks: Double,
    val avgMs: Long,
    val lastOkMs: Long,
    val lastError: String?,
    val grade: String,
    val noMatch: Int,
    val off: Boolean,
)

private fun gradeOf(requests: Int, okPct: Int, avgMs: Long): String = when {
    requests < 3 -> "?"
    okPct >= 80 && (avgMs < 0 || avgMs < 8_000) -> "A"
    okPct >= 60 -> "B"
    okPct >= 35 -> "C"
    okPct >= 10 -> "D"
    else -> "F"
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val d = (currentTimeMillis() - ms) / 1000
    return when {
        d < 90 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} d ago"
    }
}

private fun buildRows(plugins: List<LoadedPluginInfo>): List<CardRow> =
    plugins.filter { it.apiRegistered }.map { p ->
        val st = StreamTracker.statOf(p.internalName)
        val nm = st?.noMatch ?: 0
        // Only answers where the extension had the title count: a title that is not in its catalog is not its fault
        val req = ((st?.totalRequests ?: 0) - nm).coerceAtLeast(0)
        val ok = st?.successRequests ?: 0
        val pct = if (req > 0) ok * 100 / req else 0
        val avgMs = st?.avgMs() ?: -1L
        CardRow(
            id = p.internalName,
            name = p.displayName,
            requests = req,
            okPct = pct,
            avgLinks = if (ok > 0) (st?.totalStreams ?: 0).toDouble() / ok else 0.0,
            avgMs = avgMs,
            lastOkMs = st?.lastSuccessTime ?: 0L,
            lastError = st?.lastError,
            grade = gradeOf(req, pct, avgMs),
            noMatch = nm,
            off = com.cncverse.stremiobridge.server.StremioServer.disabledPlugins.contains(p.internalName),
        )
    }.sortedWith(compareBy({ it.off }, { it.grade == "?" }, { -it.okPct }, { if (it.avgMs < 0) Long.MAX_VALUE else it.avgMs }))

private fun gradeColor(g: String): Color = when (g) {
    "A" -> Green400
    "B" -> Color(0xFFA3E635)
    "C" -> Amber400
    "D" -> Color(0xFFFB923C)
    "F" -> Red400
    else -> TextMuted
}

/** Which extensions give links, how often, how many and how fast. */
@Composable
fun ReportCardDialog(onDismiss: () -> Unit) {
    val plugins by ServerState.globalLoadedPlugins.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }
    val rows = remember(plugins, tick) { buildRows(plugins) }
    val dead = rows.filter { (it.grade == "F" || it.grade == "D") && it.requests >= 3 && !it.off }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = AmoledCard, modifier = Modifier.border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(16.dp))) {
            Column(Modifier.padding(16.dp).heightIn(max = 560.dp)) {
                Text("Extension report card", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Based on every time the extension was asked for links. A = gives links almost always and quickly. F = rarely gives links. ? = asked fewer than 3 times. Titles an extension does not have are not counted against it.",
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                if (rows.isEmpty()) {
                    Text("No extension is loaded yet.", color = TextMuted, fontSize = 13.sp)
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(rows) { r ->
                        Row(
                            Modifier.fillMaxWidth().background(AmoledBlack, RoundedCornerShape(10.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(34.dp).background(gradeColor(r.grade).copy(alpha = 0.18f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                Text(r.grade, color = gradeColor(r.grade), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val speed = if (r.avgMs < 0) "speed unknown" else if (r.avgMs < 1000) "${r.avgMs} ms" else "${r.avgMs / 100 / 10.0} s"
                                Text(
                                    if (r.requests == 0) (if (r.noMatch > 0) "Never had a title it was asked for (${r.noMatch}x)" else "Not asked yet")
                                    else "${r.okPct}% gave links (${r.requests} asks) · about ${(r.avgLinks * 10).toInt() / 10.0} links · $speed",
                                    color = TextMuted, fontSize = 11.sp,
                                )
                                Text("Last links: ${ago(r.lastOkMs)}" + (if (r.noMatch > 0 && r.requests > 0) " · title not found ${r.noMatch}x" else "") + (if (r.off) " · switched off" else ""), color = TextMuted, fontSize = 11.sp)
                                if (r.grade == "F" || r.grade == "D") r.lastError?.takeIf { it.isNotBlank() }?.let {
                                    Text(it.take(90), color = Red400, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    if (dead.isNotEmpty()) TextButton(onClick = {
                        dead.forEach { com.cncverse.stremiobridge.server.StremioServer.setPluginDisabled(it.id, disabled = true) }
                        tick++
                    }) { Text("Switch off ${dead.size} dead", color = Red400) }
                    TextButton(onClick = { tick++ }) { Text("Refresh", color = Violet400) }
                    TextButton(onClick = onDismiss) { Text("Close", color = TextPrimary) }
                }
            }
        }
    }
}
