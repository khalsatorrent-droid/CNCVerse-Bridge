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
    }
}
