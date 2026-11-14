package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.StremioIds
import com.cncverse.stremiobridge.model.StremioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicAddonTest {
    @Test
    fun `music types are told from video types`() {
        assertTrue(Profiles.isMusicTypes(listOf("Music")))
        assertTrue(Profiles.isMusicTypes(listOf("Music", "Others")))
        assertTrue(Profiles.isMusicTypes(listOf("audio")))
        assertFalse(Profiles.isMusicTypes(listOf("Music", "Movie")))
        assertFalse(Profiles.isMusicTypes(listOf("TvSeries", "Anime")))
        assertFalse(Profiles.isMusicTypes(null))
        assertFalse(Profiles.isMusicTypes(emptyList()))
    }

    @Test
    fun `lossless link is preferred and non-audio links are skipped`() {
        val mp3 = StremioStream(name = "MP3 320", url = "https://cdn.example/a.mp3")
        val flac = StremioStream(name = "FLAC (lossless)", url = "https://cdn.example/a.flac")
        val magnet = StremioStream(name = "Torrent", infoHash = "abc")
        val ext = StremioStream(name = "Open site", url = "https://x.example", externalUrl = "https://x.example")
        assertEquals(flac, MusicAddon.pickBest(listOf(mp3, magnet, flac, ext)))
        assertEquals(mp3, MusicAddon.pickBest(listOf(ext, mp3)))
        assertNull(MusicAddon.pickBest(listOf(magnet, ext)))
    }

    @Test
    fun `format comes from the file name or the label`() {
        assertEquals("flac", MusicAddon.formatOf("https://cdn.example/a.flac?token=1", ""))
        assertEquals("mp3", MusicAddon.formatOf("https://cdn.example/a.mp3", ""))
        assertEquals("m4a", MusicAddon.formatOf("https://cdn.example/a.mp4", ""))
        assertEquals("flac", MusicAddon.formatOf("https://cdn.example/stream/1", "FLAC (lossless)"))
        assertNull(MusicAddon.formatOf("https://cdn.example/stream/1", "Auto"))
    }

    @Test
    fun `ids keep the kind and the address`() {
        val id = StremioIds.encode("Monochrome", "t:https://monochrome.st/track/123456")
        val (ext, data) = StremioIds.decode(id)!!
        assertEquals("Monochrome", ext)
        assertEquals('t', data[0])
        assertEquals("https://monochrome.st/track/123456", data.substring(2))
    }
}
