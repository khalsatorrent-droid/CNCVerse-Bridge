package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioStream

/**
 * Builds the multi-line "card" Stremio shows for every stream:
 *
 *   name:   CNCVerse ❄️ 4K • HDHub4U
 *   title:  🍿 Interstellar
 *           🏷️ Interstellar.2014.IMAX.2160p.BluRay.HEVC.10bit.HDR.DDP5.1.Atmos [18.5GB]
 *           📼 BluRay • HDR • 10bit • IMAX • HEVC • Atmos • DD+
 *           📡 Source: HDHub4U • HubCloud
 *           💾 18.5 GB
 *           🎧 Audio: Hindi, English
 *           💬 Subs: English
 *
 * The file / release name the plugin found is always shown (🏷️), and when that line is not the
 * entry that was matched on the site, the matched entry is listed as "📂 Found:".
 * Lines whose data is unknown are left out, so a live channel only gets the minimal card.
 */
internal object StreamCards {

    private const val BRAND = "CNCVerse"

    private val RESOLUTION = Regex("(?<![0-9])(2160|1440|1080|720|576|480)p", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("(\\d+(?:\\.\\d+)?)\\s?(GB|MB)", RegexOption.IGNORE_CASE)

    /** Order matters: this is the order the tags are shown in. */
    private val TAGS: List<Pair<Regex, String>> = listOf(
        Regex("blu-?ray|bdrip|brrip", RegexOption.IGNORE_CASE) to "BluRay",
        Regex("web-?dl", RegexOption.IGNORE_CASE) to "WEB-DL",
        Regex("web-?rip", RegexOption.IGNORE_CASE) to "WEBRip",
        Regex("hdrip", RegexOption.IGNORE_CASE) to "HDRip",
        Regex("hdtv", RegexOption.IGNORE_CASE) to "HDTV",
        Regex("dvdrip", RegexOption.IGNORE_CASE) to "DVDRip",
        Regex("\\b(?:hdcam|cam|hdts|telesync)\\b", RegexOption.IGNORE_CASE) to "CAM",
        Regex("dolby ?vision|\\bdv\\b", RegexOption.IGNORE_CASE) to "DV",
        Regex("hdr10\\+|hdr10plus", RegexOption.IGNORE_CASE) to "HDR10+",
        Regex("hdr", RegexOption.IGNORE_CASE) to "HDR",
        Regex("10[ -]?bit", RegexOption.IGNORE_CASE) to "10bit",
        Regex("imax", RegexOption.IGNORE_CASE) to "IMAX",
        Regex("hevc|x\\.?265|h\\.?265", RegexOption.IGNORE_CASE) to "HEVC",
        Regex("av1", RegexOption.IGNORE_CASE) to "AV1",
        Regex("x\\.?264|h\\.?264|\\bavc\\b", RegexOption.IGNORE_CASE) to "AVC",
        Regex("atmos", RegexOption.IGNORE_CASE) to "Atmos",
        Regex("ddp|dd\\+|e-?ac-?3", RegexOption.IGNORE_CASE) to "DD+",
        Regex("truehd", RegexOption.IGNORE_CASE) to "TrueHD",
        Regex("dts", RegexOption.IGNORE_CASE) to "DTS",
        Regex("\\bdd ?[257]\\.?[01]\\b|\\bac-?3\\b", RegexOption.IGNORE_CASE) to "DD",
        Regex("\\baac\\b", RegexOption.IGNORE_CASE) to "AAC",
    )

    private val LANGUAGES: List<Pair<Regex, String>> = listOf(
        Regex("\\b(?:hindi|hin)\\b", RegexOption.IGNORE_CASE) to "Hindi",
        Regex("\\b(?:english|eng)\\b", RegexOption.IGNORE_CASE) to "English",
        Regex("\\btamil\\b", RegexOption.IGNORE_CASE) to "Tamil",
        Regex("\\btelugu\\b", RegexOption.IGNORE_CASE) to "Telugu",
        Regex("\\bmalayalam\\b", RegexOption.IGNORE_CASE) to "Malayalam",
        Regex("\\bkannada\\b", RegexOption.IGNORE_CASE) to "Kannada",
        Regex("\\bpunjabi\\b", RegexOption.IGNORE_CASE) to "Punjabi",
        Regex("\\bbengali\\b", RegexOption.IGNORE_CASE) to "Bengali",
        Regex("\\bmarathi\\b", RegexOption.IGNORE_CASE) to "Marathi",
        Regex("\\bjapanese\\b", RegexOption.IGNORE_CASE) to "Japanese",
        Regex("\\bkorean\\b", RegexOption.IGNORE_CASE) to "Korean",
        Regex("\\bspanish\\b", RegexOption.IGNORE_CASE) to "Spanish",
        Regex("\\bfrench\\b", RegexOption.IGNORE_CASE) to "French",
    )

    private val SUBS = Regex("e-?subs?|eng(?:lish)?[ ._-]?subs?|\\bsubs?\\b|subbed|softsub|hardsub", RegexOption.IGNORE_CASE)

    fun build(
        s: StremioStream,
        source: String,
        wantedTitle: String?,
        episodeTag: String?,
        foundName: String?,
        variant: String?,
    ): StremioStream {
        val linkName = s.title?.trim()?.takeIf { it.isNotEmpty() }
        val found = foundName?.trim()?.takeIf { it.isNotEmpty() }
        val url = s.url.orEmpty()

        // The plugin's own link name is either a release / file name ("Movie.2014.1080p.BluRay…")
        // or just a host / server name ("HubCloud"). Only the former can be the 🏷️ line.
        val linkIsRelease = linkName != null && releaseScore(linkName) >= 2
        val fileLabel = if (linkIsRelease) linkName else found ?: linkName
        val serverName = if (!linkIsRelease && found != null && linkName != null && !linkName.equals(found, true)) linkName else null

        // Everything we can read the details from: the file name and the found entry.
        val haystack = listOfNotNull(linkName, found).joinToString(" ")

        val resText = resolutionLabel(s.quality, haystack)
        val protocol = protocolOf(url)
        val tags = TAGS.filter { (re, _) -> re.containsMatchIn(haystack) }.map { it.second }.distinct()
        val size = SIZE.find(haystack)?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }
        val audio = LANGUAGES.filter { (re, _) -> re.containsMatchIn(haystack) }.map { it.second }
        val subs = if (SUBS.containsMatchIn(haystack)) {
            if (Regex("eng", RegexOption.IGNORE_CASE).containsMatchIn(haystack)) "English" else "Yes"
        } else null

        val heading = buildString {
            append(wantedTitle ?: "$BRAND stream")
            if (episodeTag != null) append(" • ").append(episodeTag)
        }
        val icon = if (episodeTag != null) "📡" else "🍿"

        val lines = ArrayList<String>()
        lines += "$icon $heading"
        if (fileLabel != null) lines += "🏷️ $fileLabel"
        if (found != null && !found.equals(fileLabel, true)) lines += "📂 Found: $found"
        lines += "📼 " + (tags.ifEmpty { listOf(resText, protocol) } + listOfNotNull(variant)).joinToString(" • ")
        lines += "📡 Source: " + listOfNotNull(source, serverName).joinToString(" • ")
        if (size != null) lines += "💾 $size"
        if (audio.isNotEmpty()) lines += "🎧 Audio: ${audio.joinToString(", ")}"
        if (subs != null) lines += "💬 Subs: $subs"

        val card = lines.joinToString("\n")
        val name = "$BRAND ${resolutionEmoji(resText)} $resText • $source" +
            (if (variant != null) " [$variant]" else "")

        return s.copy(name = name, title = card, description = card)
    }

    /** How many release-name markers (resolution, source, codec ...) the text contains. */
    private fun releaseScore(text: String): Int {
        var n = if (RESOLUTION.containsMatchIn(text) || text.contains("4k", true)) 1 else 0
        n += TAGS.count { (re, _) -> re.containsMatchIn(text) }
        return n
    }

    private fun resolutionLabel(quality: Int?, text: String): String {
        quality?.let { q ->
            return when {
                q >= 2160 -> "4K"
                q > 0 -> "${q}p"
                else -> "Auto"
            }
        }
        if (Regex("2160p|\\b4k\\b|\\buhd\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "4K"
        RESOLUTION.find(text)?.let { return "${it.groupValues[1]}p" }
        return "Auto"
    }

    private fun resolutionEmoji(res: String): String = when (res) {
        "4K" -> "❄️"
        "1440p" -> "✨"
        "1080p" -> "🧊"
        "720p" -> "💧"
        else -> "🎬"
    }

    private fun protocolOf(url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".m3u8") || u.contains("/proxy/mpd/") -> "HLS"
            u.endsWith(".mpd") -> "DASH"
            else -> "Direct"
        }
    }
}
