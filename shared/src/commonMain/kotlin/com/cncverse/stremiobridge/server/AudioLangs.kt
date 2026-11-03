package com.cncverse.stremiobridge.server

import java.util.Locale

/**
 * Audio language names from HLS playlists and MKV track lists (upstream HlsProbe.languageOf): a track's NAME when it says a
 * known language ("Hindi"), else its LANGUAGE code ("hi", "hin", bibliographic "ger"); null for "und", "Track 1"...
 */
object AudioLangs {
    private val KNOWN = listOf(
        "Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Punjabi",
        "Gujarati", "Urdu", "Japanese", "Korean", "Chinese", "Mandarin", "Cantonese", "Spanish", "French",
        "German", "Italian", "Portuguese", "Russian", "Arabic", "Turkish", "Thai", "Indonesian", "Vietnamese",
        "Filipino", "Tagalog", "Polish", "Dutch", "Swedish", "Persian", "Hebrew", "Greek", "Ukrainian",
        "Odia", "Assamese", "Bhojpuri", "Nepali", "Sinhala", "Malay",
    )

    private val BIBLIOGRAPHIC = mapOf(
        "ger" to "de", "fre" to "fr", "chi" to "zh", "dut" to "nl", "per" to "fa", "gre" to "el", "cze" to "cs",
        "rum" to "ro", "slo" to "sk", "ice" to "is", "mac" to "mk", "may" to "ms", "bur" to "my", "arm" to "hy",
        "geo" to "ka", "baq" to "eu", "alb" to "sq", "wel" to "cy", "tib" to "bo",
    )

    /** "Hindi" from a track NAME that says so, else from its LANGUAGE code ("hi", "tam"); null for "und", "Track 1"… */
    internal fun languageOf(name: String?, code: String?): String? {
        name?.let { n -> KNOWN.firstOrNull { k -> Regex("(?<![A-Za-z])${k}(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(n) }?.let { return it } }
        val raw = code?.lowercase()?.substringBefore('-')?.takeIf { it.length in 2..3 && it !in setOf("und", "mul", "mis", "zxx", "qaa") } ?: return null
        // MKV files often use the bibliographic ISO 639-2 codes, which Locale doesn't know
        val c = BIBLIOGRAPHIC[raw] ?: raw
        val display = Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH)
        return display.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
            ?.let { d -> KNOWN.firstOrNull { it.equals(d, ignoreCase = true) } ?: d }
    }

    /** A language name the user typed ("hindi", "HIN", "hi") as the name used everywhere ("Hindi"); null when unknown. */
    fun canonical(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        KNOWN.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        return languageOf(null, t) ?: t.replaceFirstChar { it.uppercase() }
    }
}
