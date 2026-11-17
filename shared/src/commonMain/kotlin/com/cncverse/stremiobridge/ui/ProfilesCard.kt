package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.server.BridgeProfile
import com.cncverse.stremiobridge.server.Profiles
import com.cncverse.stremiobridge.server.StremioServer
import com.cncverse.stremiobridge.state.ServerState
import com.cncverse.stremiobridge.state.ServerStatus
import com.cncverse.stremiobridge.tunnel.TunnelSettings

/** Settings > Profiles: off = one main profile; on = extra profiles, each with its own add-on link and its own extensions. */
@Composable
fun ProfilesCard() {
    var on by remember { mutableStateOf(Profiles.enabled) }
    var tick by remember { mutableStateOf(0) }
    val profiles = remember(tick, on) { Profiles.all() }
    val status by ServerState.status.collectAsState()
    val tunnel by ServerState.activeTunnelUrl.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    val running = status as? ServerStatus.Running
    val publicBase = tunnel?.takeIf { it.isNotBlank() } ?: TunnelSettings.publicUrl
    val localBase = running?.let { "http://${it.ipAddress}:${it.port}" }
    fun links(path: String): List<Pair<String, String>> = buildList {
        publicBase?.let { add("Internet" to "$it$path") }
        localBase?.let { add("This network" to "$it$path") }
    }

    @Composable
    fun LinkRows(path: String) {
        links(path).forEach { (label, url) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = TextMuted, fontSize = 10.sp)
                    Text(url, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)); copied = url }) {
                    Text(if (copied == url) "Copied" else "Copy", color = Violet400, fontSize = 12.sp)
                }
            }
        }
        if (links(path).isEmpty()) Text("Start the server to get the link", color = TextMuted, fontSize = 11.sp)
    }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Profiles", "One bridge, several add-on links, each with its own extensions (for example one for the family and one for you).")
        SwitchRow(
            title = "Use several profiles",
            description = "Off: one main profile only. On: you can make more, each with its own link",
            checked = on,
            onCheckedChange = { on = it; Profiles.setEnabled(it); tick++ },
        )
        if (on) {
            Text("Main profile", color = TextPrimary, fontSize = 13.sp)
            Text("Uses the extensions switched on in the Extensions screen", color = TextMuted, fontSize = 11.sp)
            LinkRows("/manifest.json")
            Spacer(Modifier.height(8.dp))
            profiles.forEach { p -> key(p.id) { ProfileEditor(p, onChange = { tick++ }, linkRows = { LinkRows("/u/${p.id}/manifest.json") }) } }
            ActionButton("New profile", primary = true) { Profiles.create(""); tick++ }
        }
    }
}

@Composable
private fun ProfileEditor(p: BridgeProfile, onChange: () -> Unit, linkRows: @Composable () -> Unit) {
    var name by remember(p.id) { mutableStateOf(p.name) }
    var open by remember(p.id) { mutableStateOf(false) }
    var confirmDelete by remember(p.id) { mutableStateOf(false) }
    val choices = remember(open) {
        // NSFW extensions belong to the NSFW add-on only
        StremioServer.loadedApis.filter { !it.isNsfw && !it.isMusic }.distinctBy { it.pluginInternalName }
            .map { it.pluginInternalName to it.name }.sortedBy { it.second.lowercase() }
    }
    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it; Profiles.update(Profiles.all().first { x -> x.id == p.id }.copy(name = it.ifBlank { p.name })) },
        label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = amoledFieldColors(),
    )
    linkRows()
    val cur = Profiles.all().firstOrNull { it.id == p.id } ?: return
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide extensions" else "Extensions: ${choices.count { it.first in cur.plugins }} of ${choices.size} on", color = Violet400, fontSize = 12.sp)
    }
    if (open) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            ActionButton("All on") { Profiles.update(cur.copy(plugins = choices.map { it.first }.toSet())); onChange() }
            ActionButton("All off") { Profiles.update(cur.copy(plugins = emptySet())); onChange() }
        }
        var tick by remember { mutableStateOf(0) }
        val now = remember(tick) { Profiles.all().firstOrNull { it.id == p.id }?.plugins ?: emptySet() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (key, label) ->
                val sel = key in now
                FilterChip(
                    selected = sel,
                    onClick = {
                        val c = Profiles.all().first { it.id == p.id }
                        Profiles.update(c.copy(plugins = if (sel) c.plugins - key else c.plugins + key)); tick++; onChange()
                    },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet600, selectedLabelColor = TextPrimary,
                        containerColor = AmoledCard2, labelColor = TextSecondary,
                    ),
                )
            }
        }
    }
    ActionButton(if (confirmDelete) "Tap again to delete" else "Delete profile", danger = true) {
        if (confirmDelete) { Profiles.remove(p.id); onChange() } else confirmDelete = true
    }
}

/**
 * Settings > NSFW: extensions marked NSFW (per extension, by their repo entry) are hidden from the app unless shown, and are
 * never part of the main add-on or the profiles. With "NSFW add-on" on they get a link of their own; off by default.
 */
@Composable
fun NsfwCard() {
    val prefs by Profiles.nsfwPrefs.collectAsState()
    val status by ServerState.status.collectAsState()
    val tunnel by ServerState.activeTunnelUrl.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    val running = status as? ServerStatus.Running
    val publicBase = tunnel?.takeIf { it.isNotBlank() } ?: TunnelSettings.publicUrl
    val localBase = running?.let { "http://${it.ipAddress}:${it.port}" }
    val path = "/u/${Profiles.NSFW_ID}/manifest.json"
    val installed = remember(running) { StremioServer.loadedApis.count { it.isNsfw } }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("NSFW", "Extensions marked NSFW by their repo. They are never in the main add-on or the profiles.")
        SwitchRow(
            title = "Show NSFW extensions",
            description = "List them in the Extensions and Server tabs (to install, switch on / off and see them)",
            checked = prefs.first,
            onCheckedChange = { Profiles.setShowNsfw(it) },
        )
        SwitchRow(
            title = "NSFW add-on",
            description = "A separate add-on link with only the NSFW extensions. Off: that link does not answer",
            checked = prefs.second,
            onCheckedChange = { Profiles.setNsfwAddon(it) },
        )
        if (prefs.second) {
            if (installed == 0) {
                Text("No NSFW extension is installed: the link appears once one is", color = TextMuted, fontSize = 11.sp)
            } else {
                Text("$installed NSFW extension(s) in this add-on", color = TextMuted, fontSize = 11.sp)
                val links = buildList {
                    publicBase?.let { add("Internet" to "$it$path") }
                    localBase?.let { add("This network" to "$it$path") }
                }
                links.forEach { (label, url) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = TextMuted, fontSize = 10.sp)
                            Text(url, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(url)); copied = url }) {
                            Text(if (copied == url) "Copied" else "Copy", color = Violet400, fontSize = 12.sp)
                        }
                    }
                }
                if (links.isEmpty()) Text("Start the server to get the link", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Settings > Music: the music extensions (Monochrome, MusicBox...) are never part of the main add-on or the profiles. They have
 * an add-on link of their own, made for the Eclipse music app (its add-on protocol): search, albums, artists and playback.
 */
@Composable
fun MusicCard() {
    val status by ServerState.status.collectAsState()
    val tunnel by ServerState.activeTunnelUrl.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    val running = status as? ServerStatus.Running
    val publicBase = tunnel?.takeIf { it.isNotBlank() } ?: TunnelSettings.publicUrl
    val localBase = running?.let { "http://${it.ipAddress}:${it.port}" }
    val path = "/music/manifest.json"
    val installed = remember(running) { StremioServer.loadedApis.count { it.isMusic } }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle("Music", "Music extensions have a separate add-on link for the Eclipse app. They are never in the main add-on or the profiles.")
        if (installed == 0) {
            Text("No music extension is installed (Monochrome and MusicBox are in the Watchuu repository)", color = TextMuted, fontSize = 11.sp)
        } else {
            Text("$installed music extension(s) in this add-on", color = TextMuted, fontSize = 11.sp)
        }
        val links = buildList {
            publicBase?.let { add("Internet" to "$it$path") }
            localBase?.let { add("This network" to "$it$path") }
        }
        links.forEach { (label, url) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = TextMuted, fontSize = 10.sp)
                    Text(url, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)); copied = url }) {
                    Text(if (copied == url) "Copied" else "Copy", color = Violet400, fontSize = 12.sp)
                }
            }
        }
        if (links.isEmpty()) Text("Start the server to get the link", color = TextMuted, fontSize = 11.sp)
    }
}
