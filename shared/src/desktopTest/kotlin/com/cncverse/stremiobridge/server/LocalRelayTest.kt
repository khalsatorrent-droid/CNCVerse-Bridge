package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalRelayTest {
    @Test
    fun `the relayed path keeps a URL inside a segment encoded`() {
        // GoTaku's child playlists: decoding the %2F made its server answer 404
        assertEquals(
            "5e69f89ebd/p/https%3A%2F%2Fdelta-91.example.top%2Fp%2F1%2Findex.m3u8",
            LocalRelay.rawPath("/proxy/local/33685/5e69f89ebd/p/https%3A%2F%2Fdelta-91.example.top%2Fp%2F1%2Findex.m3u8?x=1", 33685),
        )
        assertEquals("abc/master.m3u8", LocalRelay.rawPath("/proxy/local/41949/abc/master.m3u8", 41949))
    }

    @Test
    fun `warming skips links that are not an extension's local HLS`() {
        assertFalse(MediaServer.warmLocalNow("https://example.com/a/master.m3u8", emptyMap()))
        assertFalse(MediaServer.warmLocalNow("http://127.0.0.1:37341/abc/video.mp4", emptyMap()))
    }
}
