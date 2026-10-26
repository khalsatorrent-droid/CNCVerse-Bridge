package com.cncverse.stremiobridge.server

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
private data class ProfilesFile(val enabled: Boolean = false, val profiles: List<BridgeProfile> = emptyList())

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

    /** The profile of the request being served (null = the main profile). Carried into the work started for the request. */
    val current = ThreadLocal<BridgeProfile?>()

    fun all(): List<BridgeProfile> = list.toList()
    fun find(id: String?): BridgeProfile? = if (!enabled || id == null) null else list.firstOrNull { it.id == id }

    fun load(cacheDir: String) {
        val f = File(cacheDir, "profiles.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val d = json.decodeFromString<ProfilesFile>(f.readText())
            enabled = d.enabled
            list.clear(); list.addAll(d.profiles)
        }.onFailure { ServerState.warn("Could not read profiles.json: ${it.message}") }
    }

    private fun save() {
        val f = file ?: return
        runCatching { f.writeText(json.encodeToString(ProfilesFile(enabled, list.toList()))) }
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
