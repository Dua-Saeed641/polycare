package org.polycare.common.conflict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.polycare.common.Hlc

class ConflictDetectorTest {
    private fun version(value: String, wallMs: Long, author: String) =
        FieldVersion(value, Hlc(wallMs, 0, author), author)

    @Test
    fun sameValueAndLaterEditBySameAuthorAreNotConflicts() {
        val local = mapOf("age" to version("5", 10, "phone-a"))
        val incoming = mapOf("age" to version(" 5 ", 20, "phone-b"))
        assertTrue(ConflictDetector.detect(local, incoming).isEmpty())

        val informedEdit = mapOf("age" to version("6", 20, "phone-a"))
        assertTrue(ConflictDetector.detect(local, informedEdit).isEmpty())
    }

    @Test
    fun differingConcurrentValuesRemainSideBySideEvenWhenOneClockIsLater() {
        val local = mapOf("village" to version("North", 10, "phone-a"))
        val incoming = mapOf("village" to version("South", 20, "phone-b"))

        val conflicts = ConflictDetector.detect(local, incoming)
        assertEquals(1, conflicts.size)
        assertEquals("village", conflicts.single().field)
        assertEquals("North", conflicts.single().local.value)
        assertEquals("South", conflicts.single().incoming.value)
    }
}
