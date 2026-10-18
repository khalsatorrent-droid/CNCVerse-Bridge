package com.cncverse.stremiobridge.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads the parts of a link the media server decides by (host, port, file name). `java.net.URI` is strict: a link whose path has a
 * space or a square bracket ("[Fibwatch.Com]Show.S01E01.1080p.mkv", "Show S01E01 [Hindi DDP 5.1] x265.mkv" - very common for file
 * names) throws, the host then read as unknown and the link was skipped by the media server. OkHttp's parser takes them (it is also
 * what the download requests use), with the strict parser as the fallback.
 */
object LinkUrl {
    private val EXPLICIT_PORT = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*:([0-9]{1,5})(?:[/?#]|$)")

    /** Lower-case host without brackets ("::1" for the IPv6 loopback), or null when the link has none. */
    fun host(url: String?): String? {
        val u = url?.trim() ?: return null
        u.toHttpUrlOrNull()?.host?.let { return it.lowercase() }
        return runCatching { java.net.URI(u).host?.removeSurrounding("[", "]")?.lowercase() }.getOrNull()
    }

    /** The port written in the link, -1 when there is none (the scheme's default). */
    fun port(url: String?): Int = url?.trim()?.let { EXPLICIT_PORT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    /** The decoded last part of the path ("" when none). */
    fun lastSegment(url: String?): String {
        val u = url?.trim() ?: return ""
        u.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.let { return it }
        return runCatching { java.net.URI(u).path.orEmpty() }.getOrDefault("").substringAfterLast('/')
    }

    /** The decoded path of the link ("" when it has none). */
    fun path(url: String?): String {
        val u = url?.trim() ?: return ""
        u.toHttpUrlOrNull()?.pathSegments?.let { return "/" + it.joinToString("/") }
        return runCatching { java.net.URI(u).path.orEmpty() }.getOrDefault("")
    }
}
