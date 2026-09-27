package org.polycare.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UuidV7Test {

    @Test
    fun `has version 7, IETF variant and the timestamp in the top 48 bits`() {
        val ms = 0x0192_3456_789AL
        val id = UuidV7({ ms }).next()
        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(ms, id.mostSignificantBits ushr 16)
    }

    @Test
    fun `later milliseconds sort later`() {
        var ms = 1_000L
        val gen = UuidV7({ ms })
        val a = gen.next()
        ms = 1_001L
        val b = gen.next()
        assertTrue(a.toString() < b.toString())
    }
}
