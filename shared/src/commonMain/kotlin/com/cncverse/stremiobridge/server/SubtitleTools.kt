package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.format.SubtitleLangs
import com.cncverse.stremiobridge.model.StremioSubtitle
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Subtitle tracks of a list: language names turned into codes, copies of the same file removed, the
 * user's languages first (or only), and an optional timing offset applied by serving the file through the bridge.
 */
object SubtitleTools {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(com.cncverse.stremiobridge.network.CfCookieInterceptor)
            .build()
    }

    private val TIME = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
    private const val MAX_BYTES = 6L * 1024 * 1024

    /** Codes the user asked for, in order ("English, Hindi" / "eng,hin" -> [eng, hin]). */
    fun wanted(raw: String): List<String> =
        raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { SubtitleLangs.normalize(it).lowercase() }.distinct()

    fun process(subs: List<StremioSubtitle>, p: StremioServer.StreamPrefs, base: String): List<StremioSubtitle> {
        val want = wanted(p.subtitleLangs)
        val seen = HashSet<String>()
        var out = subs.map { it.copy(lang = SubtitleLangs.normalize(it.lang)) }
            .filter { it.url.isNotBlank() && seen.add(it.url.substringBefore('#')) }
        if (want.isNotEmpty()) {
            val rank = { s: StremioSubtitle -> want.indexOf(s.lang.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
            if (p.subtitleOnlyPreferred) {
                val kept = out.filter { rank(it) != Int.MAX_VALUE }
                // never leave the player with no track at all just because none matched
                if (kept.isNotEmpty()) out = kept
            }
            out = out.sortedBy(rank) // stable: the original order stays inside a language
        }
        val ms = p.subtitleOffsetMs
        if (ms != 0 || p.subtitleStyle) out = out.map { s -> shiftedUrl(s.url, ms, p.subtitleStyle, base)?.let { s.copy(url = it) } ?: s }
        return out
    }

    private fun shiftedUrl(url: String, ms: Int, style: Boolean, base: String): String? {
        if (!url.startsWith("http", true)) return null
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        if (ext in setOf("ass", "ssa", "sub", "idx", "ttml", "dfxp")) return null // formats whose times are not shifted here
        // the clean look is an ASS file (the address ends in .ass so players pick the right reader); without it WebVTT stays
        return base.trimEnd('/') + (if (style) "/subshift.ass" else "/subshift") + "?ms=$ms&st=${if (style) 1 else 0}&u=" + URLEncoder.encode(url, "UTF-8")
    }

    /** The file at [url] with every cue moved by [ms]: WebVTT, or - with [style], the clean look - an ASS file; null when it cannot be read. */
    fun fetchShifted(url: String, ms: Int, style: Boolean): String? {
        val bytes = runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val src = r.body?.source() ?: return null
                src.request(MAX_BYTES + 1)
                if (src.buffer.size > MAX_BYTES) return null
                src.readByteArray()
            }
        }.getOrNull() ?: return null
        val text = decode(bytes).removePrefix("﻿")
        val shifted = shiftText(text, ms)
        return if (style) toAss(shifted) else shifted
    }

    /**
     * Clean look: the cues as an ASS file with its own style (bold white text, black outline, no box), which players apply
     * far more reliably than WebVTT's ::cue rule. Cues are read from the WebVTT text [shiftText] produced.
     */
    internal fun toAss(vtt: String): String {
        val sb = StringBuilder()
        sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n")
        sb.append("[V4+ Styles]\n")
        sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        sb.append("Style: Default,Arial,50,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2.5,0,2,40,40,38,1\n\n")
        sb.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        for (block in vtt.split("\n\n")) {
            val lines = block.split('\n')
            val t = lines.indexOfFirst { "-->" in it }
            if (t < 0) continue
            val times = TIME.findAll(lines[t]).toList()
            if (times.size < 2) continue
            val text = lines.drop(t + 1).filter { it.isNotBlank() }.joinToString("\\N") { assText(it) }
            if (text.isBlank()) continue
            sb.append("Dialogue: 0,").append(assTime(toMs(times[0]))).append(',').append(assTime(toMs(times[1])))
                .append(",Default,,0,0,0,,").append(text).append('\n')
        }
        return sb.toString()
    }

    private fun assTime(total: Long): String {
        val t = maxOf(0L, total)
        return "%d:%02d:%02d.%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
    }

    /** One cue line: italic / bold tags become ASS overrides, any other markup goes, entities are decoded. */
    private fun assText(line: String): String {
        // a literal brace would start an override block: replaced before the real overrides are added
        var x = line.replace("{", "(").replace("}", ")")
            .replace(Regex("(?i)<i>")) { "{\\i1}" }.replace(Regex("(?i)</i>")) { "{\\i0}" }
            .replace(Regex("(?i)<b>")) { "{\\b1}" }.replace(Regex("(?i)</b>")) { "{\\b0}" }
            .replace(Regex("(?i)<u>")) { "{\\u1}" }.replace(Regex("(?i)</u>")) { "{\\u0}" }
        x = x.replace(Regex("<[^>]*>"), "")
        return x.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    /** Cue look for players that honour WebVTT styling: bold white text, black outline, no box behind it. */
    private const val CUE_STYLE = "STYLE\n::cue {\n  color: #ffffff;\n  background-color: transparent;\n  font-weight: bold;\n" +
        "  text-shadow: -1px -1px 2px #000, 1px -1px 2px #000, -1px 1px 2px #000, 1px 1px 2px #000;\n}\n\n"

    internal fun withStyle(vtt: String): String {
        val i = vtt.indexOf("\n\n")
        if (i < 0) return vtt
        return vtt.substring(0, i + 2) + CUE_STYLE + vtt.substring(i + 2)
    }

    internal fun decode(b: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }
            .getOrElse { String(b, charset("windows-1252")) }
    }

    internal fun shiftText(text: String, ms: Int): String {
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        val shifted = body.split('\n').joinToString("\n") { line ->
            if ("-->" in line) TIME.replace(line) { m -> fmt(toMs(m) + ms) } else line
        }
        return if (shifted.trimStart().startsWith("WEBVTT")) shifted else "WEBVTT\n\n$shifted"
    }

    private fun toMs(m: MatchResult): Long {
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong()
    }

    private fun fmt(total: Long): String {
        val t = maxOf(0L, total)
        return "%02d:%02d:%02d.%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }
}
