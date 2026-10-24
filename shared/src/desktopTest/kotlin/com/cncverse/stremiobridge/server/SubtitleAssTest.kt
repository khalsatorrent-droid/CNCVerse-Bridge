package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleAssTest {
    private val ass = """
        [Script Info]
        Title: x

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\an8}Hello, world\NSecond line
        Dialogue: 0,1:02:03.04,1:02:05.00,Default,,0,0,0,,Late one
    """.trimIndent()

    @Test
    fun `ass becomes webvtt`() {
        val v = SubtitleTools.shiftText(ass, 0)
        assertTrue(v.startsWith("WEBVTT"))
        assertTrue(v.contains("00:00:01.500 --> 00:00:03.000\nHello, world\nSecond line"))
        assertTrue(v.contains("01:02:03.040 --> 01:02:05.000\nLate one"))
    }

    @Test
    fun `ass is shifted`() {
        assertTrue(SubtitleTools.shiftText(ass, 1000).contains("00:00:02.500 --> 00:00:04.000"))
    }

    @Test
    fun `srt still works`() {
        val v = SubtitleTools.shiftText("1\n00:00:01,000 --> 00:00:02,000\nHi\n", 500)
        assertEquals(true, v.contains("00:00:01.500 --> 00:00:02.500"))
    }

    @Test
    fun srtBecomesStyledAss() {
        val a = SubtitleTools.toStyledAss("1\n00:00:01,000 --> 00:00:02,500\n<i>Hi</i> there\nline two\n", 500)!!
        assertTrue(a.contains("Style: Default,Arial,64,&H00FFFFFF"))
        assertTrue(a.contains("Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hi{\\i0} there\\Nline two"))
    }

    @Test
    fun boldSrtCopy() {
        val s = SubtitleTools.toBoldSrt("1\n00:00:01,000 --> 00:00:02,500\n<i>Hi</i> there\nline two\n", 0)!!
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\n<b><i>Hi</i> there\nline two</b>\n\n", s)
    }

    @Test
    fun assInputKeepsTextDropsDrawings() {
        val a = SubtitleTools.toStyledAss(ass + "\nDialogue: 0,0:00:05.00,0:00:06.00,Sign,,0,0,0,,{\\p1}m 0 0 l 5 5", 0)!!
        assertTrue(a.contains("Hello, world\\NSecond line"))
        assertTrue(!a.contains("m 0 0"))
    }
}

class DohWireTest {
    @Test
    fun queryAndAnswerRoundTrip() {
        val q = com.cncverse.stremiobridge.network.DohWire.buildQuery("a.io", 1)
        assertEquals(12 + 1 + 1 + 1 + 2 + 1 + 4, q.size)
        // response: header (1 question, 1 answer), question copy, answer with a compressed name -> 1.2.3.4, ttl 60
        val r = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } +
            byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        val a = com.cncverse.stremiobridge.network.DohWire.parseAnswers(r)
        assertEquals("1.2.3.4", a.single().first.hostAddress)
        assertEquals(60L, a.single().second)
    }
}
