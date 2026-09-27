package org.polycare.vector

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/** Brute-force [VectorStore] for tests. Cosine similarity for dense, dot product for sparse. */
class InMemoryVectorStore(override val modelId: String) : VectorStore {
    private val points = LinkedHashMap<String, Point>()
    private val mutex = Mutex()

    override suspend fun upsert(points: List<Point>) = mutex.withLock {
        points.forEach { if (it.modelId != modelId) throw ModelIdMismatchException(modelId, it.modelId) }
        points.forEach { this.points[it.id] = it }
    }

    override suspend fun delete(ids: Collection<String>) = mutex.withLock {
        ids.forEach { points.remove(it) }
    }

    override suspend fun search(query: DenseQuery, filter: Filter?): List<ScoredPoint> = mutex.withLock {
        if (query.modelId != modelId) throw ModelIdMismatchException(modelId, query.modelId)
        points.values
            .filter { filter?.matches(it.payload) ?: true }
            .map { ScoredPoint(it.id, cosine(query.vector, it.dense), it.payload) }
            .sortedByDescending { it.score }
            .take(query.limit)
    }

    override suspend fun searchSparse(query: SparseQuery, filter: Filter?): List<ScoredPoint> = mutex.withLock {
        points.values
            .filter { it.sparse != null && (filter?.matches(it.payload) ?: true) }
            .map { ScoredPoint(it.id, dot(query.vector, it.sparse!!), it.payload) }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(query.limit)
    }

    override suspend fun count(): Long = mutex.withLock { points.size.toLong() }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    private fun dot(a: SparseVector, b: SparseVector): Float {
        val weights = HashMap<Int, Float>(b.indices.size)
        b.indices.forEachIndexed { i, idx -> weights[idx] = b.values[i] }
        var sum = 0f
        a.indices.forEachIndexed { i, idx -> sum += a.values[i] * (weights[idx] ?: 0f) }
        return sum
    }
}
