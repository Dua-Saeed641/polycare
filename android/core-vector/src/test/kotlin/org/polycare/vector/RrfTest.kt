package org.polycare.vector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RrfTest {
    private fun ranking(vararg ids: String) = ids.map { ScoredPoint(it, 0f, emptyMap()) }

    @Test
    fun `items found by both searches rank above items found by one`() {
        val fused = Rrf.fuse(listOf(ranking("a", "b", "c"), ranking("c", "d")), limit = 4)
        assertEquals("c", fused.first().id)
        assertEquals(setOf("a", "b", "c", "d"), fused.map { it.id }.toSet())
    }

    @Test
    fun `limit is respected`() {
        assertEquals(2, Rrf.fuse(listOf(ranking("a", "b", "c")), limit = 2).size)
    }
}
