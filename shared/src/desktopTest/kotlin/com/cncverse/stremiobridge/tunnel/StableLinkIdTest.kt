package com.cncverse.stremiobridge.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StableLinkIdTest {
    @Test
    fun `a typed id is tidied and checked`() {
        assertEquals("merimarzi", StableLink.cleanId("  MeriMarzi "))
        assertEquals("abcd", StableLink.cleanId("abcd"))
        assertEquals("abcdefghij12", StableLink.cleanId("abcdefghij12"))
        assertNull(StableLink.cleanId("abc"))            // too short
        assertNull(StableLink.cleanId("abcdefghij123"))  // too long for the relay
        assertNull(StableLink.cleanId("meri-marzi"))     // hyphen would break the host name
        assertNull(StableLink.cleanId("meri marzi"))
        assertNull(StableLink.cleanId(""))
        assertNull(StableLink.cleanId("tunnel"))         // a path of the relay itself
        assertEquals("https://abcd-tunnel.pencarimovie.com", StableLink.Relay.PENCARIMOVIE.urlFor("abcd"))
    }

    @Test
    fun `a worker address is cleaned to its origin`() {
        assertEquals("https://rockon.me.workers.dev", StableLink.cleanWorkerBase("rockon.me.workers.dev"))
        assertEquals("https://rockon.me.workers.dev", StableLink.cleanWorkerBase("https://rockon.me.workers.dev/abc/manifest.json"))
        assertNull(StableLink.cleanWorkerBase("not an address"))
    }
}
