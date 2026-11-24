package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting

/**
 * What the user chose for music: which extension's link is trusted most (a set order, or all equal so only the sound decides)
 * and the wanted sound - at least [minRank] and at most [maxRank] (a [MusicAddon.qualityRank]: 3000 hi-res FLAC, 2000 lossless,
 * otherwise kbps; 0 = no limit).
 */
object MusicPrefs {
    private const val KEY_EQUAL = "KEY_MUSIC_EQUAL_TRUST"
    private const val KEY_ORDER = "KEY_MUSIC_ORDER"
    private const val KEY_MIN = "KEY_MUSIC_MIN_RANK"
    private const val KEY_MAX = "KEY_MUSIC_MAX_RANK"

    private const val KEY_SEARCH_S = "KEY_MUSIC_SEARCH_SECONDS"
    private const val KEY_LINK_S = "KEY_MUSIC_LINK_SECONDS"

    /** Seconds offered for the two time limits. */
    val SECONDS = listOf(2, 3, 4, 6, 8, 10, 15)

    /** How long a search waits for the extensions (all searched at once) before it answers with what has arrived. */
    var searchSeconds: Int
        get() = (getExtensionSetting(KEY_SEARCH_S)?.toIntOrNull() ?: 6).coerceIn(1, 30)
        set(v) = setExtensionSetting(KEY_SEARCH_S, v.coerceIn(1, 30).toString())

    /** How long playing a track waits for the other extensions' links before it plays the best one in. */
    var linkSeconds: Int
        get() = (getExtensionSetting(KEY_LINK_S)?.toIntOrNull() ?: 8).coerceIn(1, 30)
        set(v) = setExtensionSetting(KEY_LINK_S, v.coerceIn(1, 30).toString())

    /** The sound levels offered: label to rank. */
    val LEVELS: List<Pair<String, Int>> = listOf(
        "Hi-res FLAC" to 3000, "FLAC / lossless" to 2000, "320 kbps" to 320, "256 kbps" to 256, "192 kbps" to 192, "128 kbps" to 128,
    )

    var equalTrust: Boolean
        get() = getExtensionSetting(KEY_EQUAL) == "true"
        set(v) = setExtensionSetting(KEY_EQUAL, if (v) "true" else "false")

    /** Extension names, most trusted first; empty = the built-in order (Octave, Arcod, Monochrome, others, MusicBox). */
    var order: List<String>
        get() = getExtensionSetting(KEY_ORDER).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = setExtensionSetting(KEY_ORDER, v.joinToString("\n"))

    /** Wanted at least this sound; 0 = the best there is. */
    var minRank: Int
        get() = getExtensionSetting(KEY_MIN)?.toIntOrNull() ?: 0
        set(v) = setExtensionSetting(KEY_MIN, v.toString())

    /** Wanted at most this sound (to save data); 0 = no limit. */
    var maxRank: Int
        get() = getExtensionSetting(KEY_MAX)?.toIntOrNull() ?: 0
        set(v) = setExtensionSetting(KEY_MAX, v.toString())
}
