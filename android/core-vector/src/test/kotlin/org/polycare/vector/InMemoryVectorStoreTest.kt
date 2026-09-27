package org.polycare.vector

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InMemoryVectorStoreTest {
    private val model = "multilingual-e5-small@int8"

    private fun point(id: String, vararg v: Float, lang: String = "hi") =
        Point(id, v, model, payload = mapOf("lang" to lang))

    @Test
    fun `nearest point ranks first and filters apply`() = runTest {
        val store = InMemoryVectorStore(model)
        store.upsert(listOf(point("a", 1f, 0f), point("b", 0f, 1f), point("c", 0.9f, 0.1f, lang = "en")))

        val all = store.search(DenseQuery(floatArrayOf(1f, 0f), model, limit = 3))
        assertEquals(listOf("a", "c", "b"), all.map { it.id })

        val hindi = store.search(DenseQuery(floatArrayOf(1f, 0f), model, 3), Filter(mapOf("lang" to setOf("hi"))))
        assertEquals(listOf("a", "b"), hindi.map { it.id })
    }

    @Test
    fun `upsert twice is idempotent`() = runTest {
        val store = InMemoryVectorStore(model)
        repeat(2) { store.upsert(listOf(point("a", 1f, 0f))) }
        assertEquals(1L, store.count())
    }

    @Test
    fun `vectors from another model are rejected`() = runTest {
        val store = InMemoryVectorStore(model)
        assertThrows<ModelIdMismatchException> {
            store.upsert(listOf(Point("x", floatArrayOf(1f), "bge-small-en")))
        }
        assertThrows<ModelIdMismatchException> {
            store.search(DenseQuery(floatArrayOf(1f), "bge-small-en", 1))
        }
    }

    @Test
    fun `sparse search finds exact term matches`() = runTest {
        val store = InMemoryVectorStore(model)
        store.upsert(
            listOf(
                Point("ifa", floatArrayOf(1f), model, SparseVector(intArrayOf(7, 9), floatArrayOf(1f, 0.5f))),
                Point("ors", floatArrayOf(1f), model, SparseVector(intArrayOf(3), floatArrayOf(1f))),
            ),
        )
        val hits = store.searchSparse(SparseQuery(SparseVector(intArrayOf(7), floatArrayOf(1f)), 5))
        assertEquals(listOf("ifa"), hits.map { it.id })
    }
}
