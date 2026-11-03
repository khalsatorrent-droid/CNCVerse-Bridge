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
}
