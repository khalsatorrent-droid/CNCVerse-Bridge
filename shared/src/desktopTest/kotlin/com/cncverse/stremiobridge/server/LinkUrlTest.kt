package com.cncverse.stremiobridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkUrlTest {
    @Test
    fun `file names with square brackets and spaces still have a host`() {
        val a = "https://jertgf.b-cdn.net/s3/upload/videos/2026/10/[Fibwatch.Com]VisionQuest.S01E01.Dual.1080p.mkv"
        val b = "https://worker-patient-sun-ee53.laloma1409.workers.dev/8fb1/VisionQuest S01E01 2160p WEB-DL [Hindi DDP 5.1  English DDP 5.1] x265 (FLUX-UHDMovies).mkv"
        assertEquals("jertgf.b-cdn.net", LinkUrl.host(a))
        assertEquals("worker-patient-sun-ee53.laloma1409.workers.dev", LinkUrl.host(b))
        assertTrue(LinkUrl.lastSegment(a).endsWith("[Fibwatch.Com]VisionQuest.S01E01.Dual.1080p.mkv"))
        assertTrue(LinkUrl.lastSegment(b).endsWith("(FLUX-UHDMovies).mkv"))
    }

    @Test
    fun `ports, loopback and links without a host`() {
        assertEquals(8088, LinkUrl.port("http://192.168.29.182:8088/token/api/download/x/file.mkv"))
        assertEquals(-1, LinkUrl.port("https://example.com/a:80/b"))
        assertEquals(-1, LinkUrl.port("https://example.com"))
        assertEquals("::1", LinkUrl.host("http://[::1]:8080/x"))
        assertEquals("192.168.29.182", LinkUrl.host("HTTP://192.168.29.182:8080/x"))
        assertNull(LinkUrl.host("not a link"))
        assertNull(LinkUrl.host(null))
    }

    @Test
    fun `an archive link with brackets is still seen as an archive`() {
        assertTrue(SeekProbe.isArchiveUrl("https://cdn.example.com/files/[Group] Show S01 (1080p).zip"))
        assertEquals(false, SeekProbe.isArchiveUrl("https://jertgf.b-cdn.net/s3/[Fibwatch.Com]Show.S01E01.mkv"))
    }
}
