package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualitySplitAudioTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:URI="audio/jpn.m3u8",TYPE=AUDIO,GROUP-ID="aud",NAME="Japanese",DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028",AUDIO="aud"
        v1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="aud"
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=854x480,CODECS="avc1.64001e",AUDIO="aud"
        v480.m3u8
    """.trimIndent()

    @Test
    fun `an audio group is found whatever the order of its attributes`() {
        assertTrue(QualityProbe.hasAudioGroup(master))
        assertTrue(QualityProbe.hasAudioGroup("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"a.m3u8\""))
        assertFalse(QualityProbe.hasAudioGroup("#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",URI=\"s.m3u8\""))
        assertFalse(QualityProbe.hasAudioGroup("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"muxed\""))
    }

    @Test
    fun `a pinned master keeps one resolution and its audio group`() {
        val out = MediaServer.keepBestVariant(master, 720)
        assertTrue(out.contains("v720.m3u8"))
        assertFalse(out.contains("v1080.m3u8"))
        assertFalse(out.contains("v480.m3u8"))
        assertTrue(out.contains("audio/jpn.m3u8"))
        val best = MediaServer.keepBestVariant(master)
        assertTrue(best.contains("v1080.m3u8") && !best.contains("v720.m3u8") && best.contains("audio/jpn.m3u8"))
    }

    @Test
    fun `the pin mark is added and taken off`() {
        val u = MediaServer.withPin("https://h/x/index.m3u8?sig=1", 720)
        assertEquals(720, MediaServer.pinOf(u))
        assertEquals("https://h/x/index.m3u8?sig=1", MediaServer.stripPin(u))
        assertEquals(1080, MediaServer.pinOf(MediaServer.withPin(u, 1080)))
        assertNull(MediaServer.pinOf("https://h/x/index.m3u8"))
    }

    @Test
    fun `a video-only rendition is marked`() {
        val v = QualityProbe.parseMaster(master, "https://h/x/master.m3u8")
        assertTrue(v.all { it.videoOnly })
        val muxed = QualityProbe.parseMaster(master.replace("avc1.640028", "avc1.640028,mp4a.40.2"), "https://h/x/master.m3u8")
        assertFalse(muxed.first { it.height == 1080 }.videoOnly)
    }
}
