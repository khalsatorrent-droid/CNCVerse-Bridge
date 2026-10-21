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
}
