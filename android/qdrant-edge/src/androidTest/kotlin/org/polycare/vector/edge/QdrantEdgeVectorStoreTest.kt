package org.polycare.vector.edge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.polycare.vector.DenseQuery
import org.polycare.vector.Filter
import org.polycare.vector.ModelIdMismatchException
import org.polycare.vector.Point
import org.polycare.vector.SparseQuery
import org.polycare.vector.SparseVector
import java.io.File

/** Runs against the real libqdrant_edge_ffi.so on an arm64 phone. */
@RunWith(AndroidJUnit4::class)
class QdrantEdgeVectorStoreTest {
    private val model = "test-model"
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "edge-test-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun open() = QdrantEdgeVectorStore.open(dir, model, dim = 4, EdgeStoreOptions(keywordIndexes = listOf("lang")))

    private fun docs() = listOf(
        Point("anc-visits", floatArrayOf(1f, 0f, 0f, 0f), model, SparseVector(intArrayOf(10), floatArrayOf(1f)), mapOf("lang" to "hi")),
        Point("ifa-dose", floatArrayOf(0f, 1f, 0f, 0f), model, SparseVector(intArrayOf(20), floatArrayOf(1f)), mapOf("lang" to "en")),
        Point("ors-mix", floatArrayOf(0f, 0f, 1f, 0f), model, SparseVector(intArrayOf(30), floatArrayOf(1f)), mapOf("lang" to "hi")),
    )

    @Test
    fun upsertSearchFilterDelete() = runTest {
        open().use { store ->
            store.upsert(docs())
            store.upsert(docs()) // idempotent
            assertEquals(3L, store.count())

            val top = store.search(DenseQuery(floatArrayOf(0.9f, 0.1f, 0f, 0f), model, 2))
            assertEquals("anc-visits", top.first().id)
            assertEquals("hi", top.first().payload["lang"])

            val english = store.search(DenseQuery(floatArrayOf(1f, 0f, 0f, 0f), model, 3), Filter(mapOf("lang" to setOf("en"))))
            assertEquals(listOf("ifa-dose"), english.map { it.id })

            val sparse = store.searchSparse(SparseQuery(SparseVector(intArrayOf(30), floatArrayOf(1f)), 3))
            assertEquals("ors-mix", sparse.first().id)

            val hybrid = store.hybrid(
                DenseQuery(floatArrayOf(0f, 1f, 0f, 0f), model, 3),
                SparseQuery(SparseVector(intArrayOf(20), floatArrayOf(1f)), 3),
                limit = 3,
            )
            assertEquals("ifa-dose", hybrid.first().id)

            store.delete(listOf("ors-mix"))
            assertEquals(2L, store.count())
        }
    }

    @Test
    fun facetsAndScrollBrowseTheShard() = runTest {
        open().use { store ->
            store.upsert(docs())

            val byLang = store.facets("lang").toMap()
            assertEquals(2L, byLang["hi"])
            assertEquals(1L, byLang["en"])

            val page1 = store.scroll(limit = 2)
            assertEquals(2, page1.points.size)
            assertTrue(page1.nextOffset != null)

            val page2 = store.scroll(limit = 2, offset = page1.nextOffset)
            assertEquals(1, page2.points.size)
            assertEquals(null, page2.nextOffset)

            val allIds = (page1.points + page2.points).map { it.id }.toSet()
            assertEquals(setOf("anc-visits", "ifa-dose", "ors-mix"), allIds)

            val hiOnly = store.scroll(filter = Filter(mapOf("lang" to setOf("hi"))), limit = 10)
            assertEquals(setOf("anc-visits", "ors-mix"), hiOnly.points.map { it.id }.toSet())
        }
    }

    @Test
    fun dataSurvivesReopen() = runTest {
        open().use { it.upsert(docs()) }
        open().use { store ->
            assertEquals(3L, store.count())
            assertTrue(store.diskBytes() > 0)
        }
    }

    @Test
    fun rejectsVectorsFromAnotherModel() = runTest {
        open().use { store ->
            try {
                store.upsert(listOf(Point("x", floatArrayOf(1f, 0f, 0f, 0f), "other-model")))
                fail("expected ModelIdMismatchException")
            } catch (_: ModelIdMismatchException) {
            }
        }
    }
}
