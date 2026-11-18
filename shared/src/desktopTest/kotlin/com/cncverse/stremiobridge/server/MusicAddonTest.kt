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

    @Test
    fun `links are ranked hi-res flac, flac, then kbps, previews last`() {
        fun s(name: String, url: String = "https://cdn.example/x") = StremioStream(name = name, url = url)
        val hiRes = s("FLAC 24-bit / 96 kHz")
        val flac = s("Monochrome FLAC (Lossless)")
        val k999 = s("999kbps")
        val k320 = s("SpotSaver 320kbps")
        val mp3 = s("Direct", "https://cdn.example/a.mp3")
        val preview = s("FLAC preview")
        val ranked = listOf(preview, mp3, k320, flac, k999, hiRes).sortedByDescending { MusicAddon.qualityRank(it) }
        assertEquals(listOf(hiRes, flac, k999, k320, mp3, preview), ranked)
        assertEquals(hiRes, MusicAddon.pickBest(listOf(k320, hiRes, flac)))
    }

    @Test
    fun `the same track is recognised across extensions`() {
        assertTrue(MusicAddon.sameTrack("Blinding Lights", "The Weeknd", "Blinding Lights (Remastered)", "Weeknd"))
        assertTrue(MusicAddon.sameTrack("Tum Hi Ho", "Arijit Singh", "Tum Hi Ho - 2013 Remaster", ""))
        assertFalse(MusicAddon.sameTrack("Blinding Lights", "The Weeknd", "Blinding Lights", "Some Cover Band"))
        assertFalse(MusicAddon.sameTrack("Hello", "Adele", "Hello Again", "Adele"))
        assertTrue(MusicAddon.accuracy("tum hi ho arijit", "Tum Hi Ho", "Arijit Singh") > MusicAddon.accuracy("tum hi ho arijit", "Tum Hi Ho Unplugged", "Cover"))
    }

    @Test
    fun `a track id carries its title, artist and search`() {
        val d = MusicAddon.trackData("https://site/t/1", "Song", "Singer", "song singer")
        assertEquals(listOf("https://site/t/1", "Song", "Singer", "song singer"), MusicAddon.splitTrack(d))
        assertEquals(listOf("https://site/t/1", "", "", ""), MusicAddon.splitTrack("https://site/t/1"))
    }
}
