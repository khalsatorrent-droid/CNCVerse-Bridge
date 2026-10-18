package com.cncverse.stremiobridge.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderNeedTest {
    private lateinit var server: HttpServer
    private var base = ""

    private fun reply(x: HttpExchange, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        x.responseHeaders.add("Content-Type", type)
        x.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) x.responseBody.use { it.write(b) }
        x.close()
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val m3u8 = "application/vnd.apple.mpegurl"
        // an open master playlist -> variant playlist -> segment
        server.createContext("/open.m3u8") { x -> reply(x, 200, m3u8, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv/index.m3u8\n") }
        server.createContext("/v/index.m3u8") { x -> reply(x, 200, m3u8, "#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n") }
        server.createContext("/v/seg0.ts") { x -> reply(x, 206, "video/mp2t", "0123456789") }
        // the playlist itself needs a Referer
        server.createContext("/locked.m3u8") { x ->
            if (x.requestHeaders.getFirst("Referer") == null) reply(x, 403, "text/plain", "no") else reply(x, 200, m3u8, "#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n")
        }
        // an open playlist whose segments need a Referer
        server.createContext("/seglock.m3u8") { x -> reply(x, 200, m3u8, "#EXTM3U\n#EXTINF:4.0,\nlocked/s1.ts\n") }
        server.createContext("/locked/s1.ts") { x ->
            if (x.requestHeaders.getFirst("Referer") == null) reply(x, 403, "text/html", "<html>forbidden</html>") else reply(x, 200, "video/mp2t", "0123")
        }
        // a page that answers 200 where a playlist should be
        server.createContext("/page.m3u8") { x -> reply(x, 200, "text/html", "<html>please log in</html>") }
        server.createContext("/ok.mp4") { x -> reply(x, 206, "video/mp4", "ab") }
        server.createContext("/gate.mp4") { x -> reply(x, 200, "text/html", "<html>blocked</html>") }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() { server.stop(0) }

    @Test
    fun `an open playlist plays without the extension's headers`() {
        assertTrue(HeaderNeed.plays("$base/open.m3u8")) // master -> variant -> first segment
    }

    @Test
    fun `a playlist or a segment that needs a Referer is reported as needing headers`() {
        assertFalse(HeaderNeed.plays("$base/locked.m3u8"))
        assertFalse(HeaderNeed.plays("$base/seglock.m3u8"))
        assertFalse(HeaderNeed.plays("$base/page.m3u8"))
    }

    @Test
    fun `files`() {
        assertTrue(HeaderNeed.plays("$base/ok.mp4"))
        assertFalse(HeaderNeed.plays("$base/gate.mp4"))
        assertFalse(HeaderNeed.plays("$base/missing.mp4"))
    }

    @Test
    fun `the first line that is not a tag`() {
        assertEquals("v/index.m3u8", HeaderNeed.firstUri("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n\n  v/index.m3u8 \n"))
        assertNull(HeaderNeed.firstUri("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }

    @Test
    fun `the key ignores the order and case of header names and the values`() {
        assertEquals(
            HeaderNeed.keyOf("CDN.Example.com", mapOf("Referer" to "https://a/", "User-Agent" to "x")),
            HeaderNeed.keyOf("cdn.example.com", mapOf("user-agent" to "y", "referer" to "https://b/")),
        )
        assertNull(HeaderNeed.verdict("https://never-checked.example/x.m3u8", mapOf("Referer" to "r")))
        HeaderNeed.record(HeaderNeed.keyOf("checked.example", mapOf("Referer" to "r")), needed = false)
        assertEquals(false, HeaderNeed.verdict("https://checked.example/a/b.m3u8", mapOf("referer" to "other")))
    }
}
