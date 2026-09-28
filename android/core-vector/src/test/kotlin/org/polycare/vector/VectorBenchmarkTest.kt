package org.polycare.vector

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorBenchmarkTest {

    @Test
    fun `exact store has perfect recall`() = runTest {
        val store = InMemoryVectorStore("bench")
        val result = VectorBenchmark(dim = 16, queries = 5, recallQueries = 5).run(store, points = 300)
        assertEquals(300L, store.count())
        assertEquals(1.0, result.recallAtK)
    }

    @Test
    fun `hybrid default fuses dense and sparse`() = runTest {
        val store = InMemoryVectorStore("m")
        store.upsert(
            listOf(
                Point("a", floatArrayOf(1f, 0f), "m", SparseVector(intArrayOf(1), floatArrayOf(1f))),
                Point("b", floatArrayOf(0f, 1f), "m", SparseVector(intArrayOf(2), floatArrayOf(1f))),
            ),
        )
        val hits = store.hybrid(
            DenseQuery(floatArrayOf(1f, 0f), "m", 2),
            SparseQuery(SparseVector(intArrayOf(1), floatArrayOf(1f)), 2),
            limit = 2,
        )
        assertEquals("a", hits.first().id)
    }
}
