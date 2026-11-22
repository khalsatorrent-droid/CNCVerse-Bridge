package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TvChannelMergeTest {
    @Test
    fun sameChannelUnderDifferentSpellingsGetsOneKey() {
        val k = StremioServer.tvNameKey("Star Plus")
        assertEquals(k, StremioServer.tvNameKey("STAR PLUS HD"))
        assertEquals(k, StremioServer.tvNameKey("📺 Star-Plus (1080p)"))
        assertEquals(k, StremioServer.tvNameKey("Star Plus [Backup] FHD"))
    }

    @Test
    fun differentChannelsStayApart() {
        assertNotEquals(StremioServer.tvNameKey("Star Sports 1"), StremioServer.tvNameKey("Star Sports 2"))
        assertNotEquals(StremioServer.tvNameKey("Zee TV"), StremioServer.tvNameKey("Zee Cinema"))
    }

    @Test
    fun countryTagSpaceBeforeNumberAndEpgAreIgnored() {
        val k = StremioServer.tvNameKey("Sony MAX2")
        assertEquals(k, StremioServer.tvNameKey("IN| SONY MAX 2 (UK)"))
        assertEquals(k, StremioServer.tvNameKey("Sony MAX2 EPG"))
        val ten1 = StremioServer.tvNameKey("Sony Sports Ten 1")
        assertEquals(ten1, StremioServer.tvNameKey("IN| SONY TEN 1 FHD".replace("SONY TEN", "SONY SPORTS TEN")))
        assertEquals(ten1, StremioServer.tvNameKey("Sony Sports Ten 1 HD STB"))
        assertEquals(ten1, StremioServer.tvNameKey("Sony Sports Ten 1 (Sony Ten 1)"))
        assertEquals(StremioServer.tvNameKey("Sony Pix HD"), StremioServer.tvNameKey("IN| SONY PIX FHD"))
        assertEquals(StremioServer.tvNameKey("Sony Pal"), StremioServer.tvNameKey("IN| SONY PAL (USA)"))
    }

    @Test
    fun otherNumbersAndLanguagesStayApart() {
        assertNotEquals(StremioServer.tvNameKey("Sony Sports Ten 1"), StremioServer.tvNameKey("Sony Sports Ten 10"))
        assertNotEquals(StremioServer.tvNameKey("Sony Sports Ten 3"), StremioServer.tvNameKey("Sony Sports Ten 3 Hindi"))
        assertNotEquals(StremioServer.tvNameKey("Sony MAX"), StremioServer.tvNameKey("Sony MAX2"))
    }

    @Test
    fun mergedChannelIsShownUnderItsCleanName() {
        assertEquals("Sony MAX2", StremioServer.tvDisplayName(listOf("IN| SONY MAX 2 (UK)", "Sony MAX2", "Sony MAX2 EPG")))
    }
}
