package com.cncverse.stremiobridge.tunnel

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TunnelHealthTest {
    private var now = 1_000_000L
    private val h = TunnelHealth { now }

    @Test fun healthyTunnelIsLeftAlone() {
        repeat(10) { now += 30_000; assertNull(h.check(true, true, true)) }
    }

    @Test fun deadProcessRestarts() {
        assertNotNull(h.check(false, true, null))
    }

    @Test fun noLinkOnlyAfterTheGracePeriod() {
        h.started()
        now += 60_000; assertNull(h.check(true, false, null))
        now += 70_000; assertNotNull(h.check(true, false, null))
    }

    @Test fun linkMustFailThreeTimesAndInternetDownCountsForNothing() {
        assertNull(h.check(true, true, false))
        assertNull(h.check(true, true, null))
        assertNull(h.check(true, true, false))
        assertNotNull(h.check(true, true, false))
    }

    @Test fun anAnswerResetsTheFailures() {
        h.check(true, true, false); h.check(true, true, false)
        h.check(true, true, true)
        assertNull(h.check(true, true, false))
    }

    @Test fun restartsAreSpacedOut() {
        assertNotNull(h.check(false, false, null))
        now += 90_000; assertNull(h.check(false, false, null))       // next one only after 2 min
        now += 40_000; assertNotNull(h.check(false, false, null))
        now += 200_000; assertNull(h.check(false, false, null))      // then 4 min
        now += 50_000; assertNotNull(h.check(false, false, null))
    }
}
