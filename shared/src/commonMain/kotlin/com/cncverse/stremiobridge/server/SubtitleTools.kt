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
        if (ms != 0) out = out.map { s -> shiftedUrl(s.url, ms, base)?.let { s.copy(url = it) } ?: s }
        return out
    }

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
