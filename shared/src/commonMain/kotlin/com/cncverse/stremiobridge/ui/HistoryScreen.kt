package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.HistoryEntry
import com.cncverse.stremiobridge.state.StreamHistory

private fun clock(ms: Long): String {
    val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${p(t.get(java.util.Calendar.DAY_OF_MONTH))}/${p(t.get(java.util.Calendar.MONTH) + 1)} " +
        "${p(t.get(java.util.Calendar.HOUR_OF_DAY))}:${p(t.get(java.util.Calendar.MINUTE))}:${p(t.get(java.util.Calendar.SECOND))}"
}

/** Every answered stream request: title, episode, what each extension found and the exact list the client got. */
@Composable
fun HistoryScreen(onCopy: (String) -> Unit = {}) {
    val entries by StreamHistory.entries.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }
    var report by remember { mutableStateOf<HistoryEntry?>(null) }
    report?.let { SearchReportDialog(it) { report = null } }

    Column(Modifier.fillMaxSize().background(AmoledBlack).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("${entries.size} request(s) answered", fontSize = 12.sp, color = TextSecondary)
            }
            TextButton(onClick = { StreamHistory.clear() }, enabled = entries.isNotEmpty()) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing yet - open a movie, series or anime in your player.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(entries, key = { i, it -> "${it.time}|${it.id}|$i" }) { _, e -> HistoryCard(e, open == e.time, { open = if (open == e.time) null else e.time }, onCopy, { report = e }) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** The whole list of a request as text: the title line, then every result with its link. */
private fun listText(e: HistoryEntry): String = buildString {
    appendLine("${e.title} ${e.episode} (${e.id})".replace("  ", " "))
    e.lines.forEachIndexed { i, l ->
        appendLine("${i + 1}. $l")
        e.urls.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { appendLine(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(e: HistoryEntry, expanded: Boolean, toggle: () -> Unit, onCopy: (String) -> Unit, onReport: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AmoledCard)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            // tap: open / close; long press on the card or its title: copy the whole list as text
            .combinedClickable(onClick = toggle, onLongClick = { onCopy(listText(e)) }).padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                e.title + if (e.episode.isNotEmpty()) "  ·  ${e.episode}" else "",
                color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Text(clock(e.time), color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        val color = if (e.shown > 0) Green400 else Red400
        Text(
            "${e.type} · found ${e.found} · shown ${e.shown} · ${"%.1f".format(e.tookMs / 1000.0)}s",
            color = color, fontSize = 12.sp,
        )
        if (e.providers.isNotEmpty()) {
            Text(e.providers.joinToString("  •  "), color = TextSecondary, fontSize = 11.sp)
        }
        Text(e.id, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        TextButton(onClick = onReport, contentPadding = PaddingValues(0.dp)) { Text("Search report: who found what, what was dropped and why", fontSize = 12.sp) }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(Modifier.height(8.dp))
            if (e.lines.isEmpty()) Text("No links were sent.", color = TextMuted, fontSize = 12.sp)
            if (e.lines.isNotEmpty()) Text("Long-press a result to copy it with its link", color = TextMuted, fontSize = 10.sp)
            var shown by remember(e.time) { mutableStateOf(30) }
            e.lines.take(shown).forEachIndexed { i, l ->
                val url = e.urls.getOrNull(i).orEmpty()
                Column(
                    Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { onCopy(if (url.isNotEmpty()) "$l\n$url" else l) })
                        .padding(vertical = 4.dp),
                ) {
                    Text("${i + 1}. $l", color = TextPrimary, fontSize = 12.sp)
                    if (url.isNotEmpty()) Text(url, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (e.lines.size > shown) TextButton(onClick = { shown += 60 }) { Text("Show more (${e.lines.size - shown} left)") }
            TextButton(onClick = { onCopy(listText(e)) }) { Text("Copy list") }
        } else {
            Text("Tap to see the list as shown - long press to copy it all", color = TextMuted, fontSize = 10.sp)
        }
    }
}

/** "Search completed": per extension how many links it found, how many were dropped (and for which reason) and how many were the same link. */
@Composable
private fun SearchReportDialog(e: HistoryEntry, onDismiss: () -> Unit) {
    fun count(t: String) = t.substringAfterLast(": ").trim().toIntOrNull() ?: 0
    val rejected = e.ext.sumOf { x -> x.rejected.sumOf { count(it) } }
    val merged = e.ext.sumOf { it.merged }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text("Search completed", fontWeight = FontWeight.Bold)
                Text(e.title + if (e.episode.isNotEmpty()) "  ·  ${e.episode}" else "", fontSize = 13.sp, color = TextSecondary)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Found ${e.found}  ·  sent ${e.shown}  ·  dropped $rejected  ·  same link merged $merged  ·  ${"%.1f".format(e.tookMs / 1000.0)}s",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                if (e.ext.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (e.providers.isEmpty()) "No extension found a link." else "This request was answered by an older version, which did not keep the details.\n" + e.providers.joinToString("\n"),
                        fontSize = 12.sp, color = TextMuted,
                    )
                }
                e.ext.forEach { x ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(8.dp))
                    Text(x.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    val lost = x.found > 0 && x.shown == 0
                    Text("found ${x.found}  ·  sent ${x.shown}", fontSize = 12.sp, color = if (lost) Red400 else if (x.shown > 0) Green400 else TextSecondary)
                    x.rejected.forEach { Text("dropped  ${it.substringAfterLast(": ")}  -  ${it.substringBeforeLast(": ")}", fontSize = 12.sp, color = TextSecondary) }
                    if (x.merged > 0) Text("merged  ${x.merged}  -  same link as one that was already in the list", fontSize = 12.sp, color = TextSecondary)
                    if (x.found > 0 && x.rejected.isEmpty() && x.merged == 0 && x.shown < x.found) Text("${x.found - x.shown} not in the list for another reason", fontSize = 12.sp, color = TextMuted)
                }
            }
        },
    )
}
