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

    private val CC_WORDS = Regex("(?<![a-z0-9])(cc|sdh|hi|hearing[ _.-]?impaired|closed[ _.-]?captions?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private const val STYLED_MAX = 10

    private fun rawName(s: StremioSubtitle): String {
        val u = s.url.substringBefore('#')
        val inner = Regex("[?&]url=([^&]+)").find(u)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: u
        return java.net.URLDecoder.decode(inner.substringBefore('?').substringAfterLast('/'), "UTF-8")
    }

    private fun isCc(orig: String, s: StremioSubtitle): Boolean = CC_WORDS.containsMatchIn(orig + " " + s.id + " " + rawName(s))

    /** A readable label: "#2 English · CC · SRT · Show.S01E01.1080p.BluRay" so the tracks of one language can be told apart. */
    private fun label(n: Int, s: StremioSubtitle, cc: Boolean, styled: Boolean): String {
        val file = rawName(s)
        val ext = file.substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub", "ttml") }
        val rel = file.substringBeforeLast('.').replace('_', ' ').trim().take(48)
        val lang = com.cncverse.stremiobridge.format.SubtitleLangs.display(s.lang)
        return buildString {
            append('#').append(n).append(' ').append(lang)
            if (cc) append(" · CC")
            if (styled) append(" · ASS styled") else ext?.let { append(" · ").append(it.uppercase()) }
            if (rel.isNotEmpty() && !rel.all { it.isDigit() } && rel.length > 3) append(" · ").append(rel)
        }
    }

    fun process(subs: List<StremioSubtitle>, p: StremioServer.StreamPrefs, base: String, anime: Boolean = false): List<StremioSubtitle> {
        val want = wanted(p.subtitleLangs)
        val seen = HashSet<String>()
        val origLang = HashMap<String, String>()
        var out = subs.map { origLang[it.id + it.url] = it.lang; it.copy(lang = SubtitleLangs.normalize(it.lang)) }
            .filter { it.url.isNotBlank() && seen.add(it.url.substringBefore('#')) }
        val cc = HashSet<String>()
        out.forEach { if (isCc(origLang[it.id + it.url].orEmpty(), it)) cc.add(it.url) }
        if (want.isNotEmpty()) {
            val rank = { s: StremioSubtitle -> want.indexOf(s.lang.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
            if (p.subtitleOnlyPreferred) {
                val kept = out.filter { rank(it) != Int.MAX_VALUE }
                // never leave the player with no track at all just because none matched
                if (kept.isNotEmpty()) out = kept
            }
            out = out.sortedBy(rank) // stable: the original order stays inside a language
        }
        // Anime: closed-caption (CC / SDH) tracks on top
        if (anime) out = out.sortedBy { if (it.url in cc) 0 else 1 }
        val ms = p.subtitleOffsetMs
        val styled = p.styledSubtitles && base.isNotBlank()
        var english = 0
        val result = ArrayList<StremioSubtitle>()
        for (s in out) {
            var cur = s
            var isStyled = false
            if (styled && s.lang.equals("eng", true)) {
                if (english >= STYLED_MAX) continue // only the first 10 English tracks are handed out
                english++
                if (s.url.startsWith("http", true)) {
                    cur = s.copy(url = base.trimEnd('/') + "/subass/sub$english.ass?ms=$ms&u=" + URLEncoder.encode(s.url, "UTF-8"))
                    isStyled = true
                    prefetch(s.url, ms)
                }
            } else if (ms != 0) {
                cur = shiftedUrl(s.url, ms, base)?.let { s.copy(url = it) } ?: s
            }
            result.add(cur.copy(id = label(result.size + 1, s, s.url in cc, isStyled)))
        }
        return result
    }

    // ── Styled ASS ──────────────────────────────────────────────────────────────
    private class Cue(val start: Long, val end: Long, val text: String)

    private val styledCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 150
    }
    private val warm by lazy { java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "subtitle-warm").apply { isDaemon = true } } }
    private val warming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Downloads and converts in the background so the player's request is answered from the cache. */
    private fun prefetch(url: String, ms: Int) {
        val key = "$ms|$url"
        if (synchronized(styledCache) { styledCache.containsKey(key) } || !warming.add(key)) return
        runCatching { warm.execute { try { fetchStyled(url, ms) } finally { warming.remove(key) } } }.onFailure { warming.remove(key) }
    }

    /** The file at [url] as an ASS file in the house look, every cue moved by [ms]; null when it cannot be read or has no cues. */
    fun fetchStyled(url: String, ms: Int): String? {
        val key = "$ms|$url"
        synchronized(styledCache) { styledCache[key] }?.let { return it }
        val text = download(url)?.let { decode(it).removePrefix("\uFEFF") } ?: return null
        val ass = toStyledAss(text, ms) ?: return null
        synchronized(styledCache) { styledCache[key] = ass }
        return ass
    }

    internal fun toStyledAss(text: String, ms: Int): String? {
        val cues = parseCues(text.replace("\r\n", "\n").replace('\r', '\n'))
        if (cues.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("[Script Info]\nTitle: CNCVerse Bridge\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n")
        sb.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        sb.append("Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3.5,1.5,2,80,80,55,1\n\n")
        sb.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        for (c in cues) sb.append("Dialogue: 0,").append(assStamp(c.start + ms)).append(',').append(assStamp(c.end + ms)).append(",Default,,0,0,0,,").append(c.text).append('\n')
        return sb.toString()
    }

    private fun assStamp(total: Long): String {
        val t = maxOf(0L, total) / 10
        return "%d:%02d:%02d.%02d".format(t / 360_000, t / 6000 % 60, t / 100 % 60, t % 100)
    }

    private val HTML_TAG = Regex("<[^>]+>")

    private fun cleanCueText(raw: String): String {
        var s = raw.replace("<i>", "{\\i1}", true).replace("</i>", "{\\i0}", true)
            .replace("<b>", "{\\b1}", true).replace("</b>", "{\\b0}", true)
            .replace("<u>", "{\\u1}", true).replace("</u>", "{\\u0}", true)
        s = HTML_TAG.replace(s, "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"")
        return s.trim().replace("\n", "\\N")
    }

    private fun parseCues(body: String): List<Cue> {
        if (body.contains("[Script Info]", true) || (body.contains("[Events]", true) && body.contains("Dialogue:"))) return parseAss(body)
        val out = ArrayList<Cue>()
        for (block in body.split(Regex("\n\\s*\n"))) {
            val lines = block.split('\n')
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) continue
            val times = TIME.findAll(lines[i]).toList()
            if (times.size < 2) continue
            val txt = cleanCueText(lines.drop(i + 1).joinToString("\n").replace(Regex("\\{[^}]*\\}"), ""))
            if (txt.isEmpty()) continue
            out.add(Cue(toMs(times[0]), toMs(times[1]), txt))
        }
        return out
    }

    private fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var start = 1; var end = 2; var txt = 9; var fields = 10
        var inEvents = false
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                fields = cols.size
                cols.indexOf("start").takeIf { it >= 0 }?.let { start = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { end = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { txt = it }
            } else if (line.startsWith("Dialogue:", true)) {
                val parts = line.substringAfter(':').trim().split(',', limit = fields)
                if (parts.size <= maxOf(start, end, txt)) continue
                val t0 = assTime(parts[start]) ?: continue
                val t1 = assTime(parts[end]) ?: continue
                val rawText = parts[txt]
                if (Regex("\\\\p[1-9]").containsMatchIn(rawText)) continue // drawings (signs, shapes)
                val ital = rawText.contains("\\i1")
                val cue = rawText.replace(ASS_TAG, "").replace("\\N", "\\N").replace("\\n", "\\N").replace("\\h", " ").trim()
                if (cue.isEmpty()) continue
                out.add(Cue(t0, t1, if (ital) "{\\i1}$cue" else cue))
            }
        }
        return out.sortedBy { it.start }
    }

    private fun download(url: String): ByteArray? = runCatching {
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val src = r.body?.source() ?: return null
            src.request(MAX_BYTES + 1)
            if (src.buffer.size > MAX_BYTES) return null
            src.readByteArray()
        }
    }.getOrNull()

    private fun shiftedUrl(url: String, ms: Int, base: String): String? {
        if (!url.startsWith("http", true)) return null
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        if (ext in setOf("ass", "ssa", "sub", "idx", "ttml", "dfxp")) return null // formats whose times are not shifted here
        return base.trimEnd('/') + "/subshift?ms=$ms&u=" + URLEncoder.encode(url, "UTF-8")
    }

    /** The file at [url] as WebVTT with every cue moved by [ms] (SRT is converted); null when it cannot be read. */
    fun fetchShifted(url: String, ms: Int): String? {
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
        return shiftText(text, ms)
    }

    internal fun decode(b: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }
            .getOrElse { String(b, charset("windows-1252")) }
    }

    private val ASS_TAG = Regex("\\{[^}]*\\}")

    /** ASS / SSA (what many anime releases use) as plain WebVTT: the timings and the text, without the styling. */
    internal fun assToVtt(text: String, ms: Int): String {
        val sb = StringBuilder("WEBVTT\n\n")
        var start = 1; var end = 2; var txt = 9; var fields = 10
        var inEvents = false
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                fields = cols.size
                cols.indexOf("start").takeIf { it >= 0 }?.let { start = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { end = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { txt = it }
            } else if (line.startsWith("Dialogue:", true)) {
                val parts = line.substringAfter(':').trim().split(',', limit = fields)
                if (parts.size <= maxOf(start, end, txt)) continue
                val t0 = assTime(parts[start]) ?: continue
                val t1 = assTime(parts[end]) ?: continue
                val cue = parts[txt].replace(ASS_TAG, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                if (cue.isEmpty()) continue
                sb.append(fmt(t0 + ms)).append(" --> ").append(fmt(t1 + ms)).append('\n').append(cue).append("\n\n")
            }
        }
        return sb.toString()
    }

    /** "0:01:02.34" (h:mm:ss.cc) in milliseconds. */
    private fun assTime(t: String): Long? {
        val m = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})").find(t.trim()) ?: return null
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + frac
    }

    internal fun shiftText(text: String, ms: Int): String {
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        if (body.contains("[Script Info]", true) || (body.contains("[Events]", true) && body.contains("Dialogue:"))) return assToVtt(body, ms)
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
