package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertTrue

class SubtitleAssTest {
    @Test
    fun srtBecomesAssWithTheCleanStyle() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello <i>there</i>\nsecond {line}\n\n2\n00:01:00,000 --> 00:01:02,250\nBye &amp; thanks\n"
        val ass = SubtitleTools.toAss(SubtitleTools.shiftText(srt, 0))
        assertTrue(ass.contains("Style: Default,Roboto,40,&H00F5F5F5"), ass)
        assertTrue(ass.contains("Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,Hello {\\i1}there{\\i0}\\Nsecond (line)"), ass)
        assertTrue(ass.contains("Dialogue: 0,0:01:00.00,0:01:02.25,Default,,0,0,0,,Bye & thanks"), ass)
    }

    @Test
    fun offsetIsApplied() {
        val ass = SubtitleTools.toAss(SubtitleTools.shiftText("1\n00:00:01,000 --> 00:00:02,000\nHi\n", 500))
        assertTrue(ass.contains("Dialogue: 0,0:00:01.50,0:00:02.50"), ass)
    }
}
