package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.writeTextAtomic
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** An extra profile: its own add-on link (/u/{id}/manifest.json) and its own choice of extensions. */
@Serializable
data class BridgeProfile(
    val id: String,
    val name: String,
    /** Extensions (plugin internal names) this profile uses. */
    val plugins: Set<String> = emptySet(),
)

@Serializable
private data class ProfilesFile(
    val enabled: Boolean = false,
    val profiles: List<BridgeProfile> = emptyList(),
    /** Extensions marked NSFW are listed in the app (Extensions / Server tabs). */
    val showNsfw: Boolean = false,
    /** The separate NSFW add-on link (/u/nsfw/manifest.json) answers. */
    val nsfwAddon: Boolean = false,
)

/**
 * Several add-on links from one bridge. Off by default: the bridge is then one main profile (the extensions switched
 * on in the Extensions screen). When switched on, extra profiles can be made, each with its own link and its own set of extensions.
 */
object Profiles {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var file: File? = null
    @Volatile var enabled: Boolean = false
        private set
    private val list = CopyOnWriteArrayList<BridgeProfile>()

    // ── NSFW ─────────────────────────────────────────────────────────────────
    // An extension is NSFW when its repo entry (or the extension itself) lists the "NSFW" type - per extension, so a repo
    // can mix both. NSFW extensions are never part of the main add-on or of the profiles: they have an add-on link of their
    // own (/u/nsfw/manifest.json), off by default.

    const val NSFW_ID = "nsfw"
    val NSFW_PROFILE = BridgeProfile(NSFW_ID, "NSFW")

    /** (show NSFW extensions in the app, NSFW add-on on) - observable by the screens. */
    val nsfwPrefs = kotlinx.coroutines.flow.MutableStateFlow(false to false)
    val showNsfw: Boolean get() = nsfwPrefs.value.first
    val nsfwAddon: Boolean get() = nsfwPrefs.value.second

    // ── Music ────────────────────────────────────────────────────────────────
    // Music extensions (Monochrome, MusicBox: the "Music" type) are never part of the main add-on, the profiles or the NSFW link:
    // they are answered by the music add-on (/music/manifest.json, the Eclipse add-on protocol) and the website's Music page.

    const val MUSIC_ID = "music"
    val MUSIC_PROFILE = BridgeProfile(MUSIC_ID, "Music")

    private val VIDEO_TYPES = setOf("movie", "tvseries", "series", "anime", "animemovie", "ova", "cartoon", "asiandrama", "documentary", "live", "tv", "torrent", "nsfw")

    /** True when the types name music (or audio) and no video type: an extension that lists both is a video one. */
    fun isMusicTypes(types: List<String>?): Boolean {
        val t = types?.map { it.trim().lowercase() }.orEmpty()
        return t.any { it == "music" || it == "audio" } && t.none { it in VIDEO_TYPES }
    }

    fun isNsfwTypes(types: List<String>?): Boolean = types?.any { it.trim().equals("NSFW", ignoreCase = true) } == true

    fun setShowNsfw(on: Boolean) { nsfwPrefs.value = on to nsfwAddon; save() }
    fun setNsfwAddon(on: Boolean) { nsfwPrefs.value = showNsfw to on; save() }

    /** The profile of the request being served (null = the main profile). Carried into the work started for the request. */
    val current = ThreadLocal<BridgeProfile?>()

    fun all(): List<BridgeProfile> = list.toList()
    fun find(id: String?): BridgeProfile? = when {
        id == NSFW_ID -> if (nsfwAddon) NSFW_PROFILE else null
        !enabled || id == null -> null
        else -> list.firstOrNull { it.id == id }
    }

    fun load(cacheDir: String) {
        val f = File(cacheDir, "profiles.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val d = json.decodeFromString<ProfilesFile>(f.readText())
            enabled = d.enabled
            list.clear(); list.addAll(d.profiles.filter { it.id != NSFW_ID })
            nsfwPrefs.value = d.showNsfw to d.nsfwAddon
        }.onFailure { ServerState.warn("Could not read profiles.json: ${it.message}") }
    }

    private fun save() {
        val f = file ?: return
        runCatching { f.writeTextAtomic(json.encodeToString(ProfilesFile(enabled, list.toList(), showNsfw, nsfwAddon))) }
            .onFailure { ServerState.warn("Could not save profiles.json: ${it.message}") }
    }

    fun setEnabled(on: Boolean) { enabled = on; save() }

    /** A new profile starts with the extensions that are switched on now. */
    fun create(name: String): BridgeProfile {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val id = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        val on = StremioServer.loadedApis.filter { !StremioServer.isGloballyDisabled(it) }.map { it.pluginInternalName }.toSet()
        return BridgeProfile(id, name.trim().ifBlank { "Profile ${list.size + 1}" }, on).also { list.add(it); save() }
    }

    fun update(p: BridgeProfile) {
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) { list[i] = p; save() }
    }

    fun remove(id: String) { list.removeAll { it.id == id }; save() }

    /** True when [api] is one of the extensions of [p]. */
    fun allows(p: BridgeProfile, api: MainApiWrapper): Boolean =
        api.pluginInternalName in p.plugins || api.internalName in p.plugins

    /** Runs [block] as [p] (null = main profile): every extension check inside it, in the work it starts, follows that profile. */
    suspend fun <T> run(p: BridgeProfile?, block: suspend () -> T): T = withContext(current.asContextElement(p)) { block() }

    /** The context element of the profile being served, for work started in a scope of its own. */
    fun element(): kotlin.coroutines.CoroutineContext = current.asContextElement(current.get())
}
