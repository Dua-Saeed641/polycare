package org.polycare.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HlcClockTest {

    @Test
    fun `now is strictly increasing even when the physical clock goes backwards`() {
        var pt = 1_000L
        val clock = HlcClock("a", { pt })
        val t1 = clock.now()
        pt = 500 // user changed the phone clock
        val t2 = clock.now()
        val t3 = clock.now()
        assertTrue(t1 < t2 && t2 < t3)
        assertEquals(1_000L, t3.wallMs)
    }

    @Test
    fun `receive orders after both local and remote`() {
        val clock = HlcClock("a", { 1_000L })
        val local = clock.now()
        val remote = Hlc(2_000L, 7, "b")
        val merged = clock.receive(remote)
        assertTrue(merged > local && merged > remote)
        assertEquals(Hlc(2_000L, 8, "a"), merged)
    }

    @Test
    fun `receive rejects a remote clock too far ahead`() {
        val clock = HlcClock("a", { 0L }, maxDriftMs = 1_000)
        assertThrows<ClockDriftException> { clock.receive(Hlc(5_000L, 0, "b")) }
    }

    @Test
    fun `ties break on node id`() {
        assertTrue(Hlc(1, 1, "a") < Hlc(1, 1, "b"))
    }
}
