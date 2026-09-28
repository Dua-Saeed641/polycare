package org.polycare.vector

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Measures a [VectorStore] with synthetic unit vectors: load speed, index build time,
 * query latency and recall@k against exact brute force. Vectors are regenerated from
 * (seed, index) so recall can be checked without holding the whole set in memory.
 *
 * Points are grouped around [clusters] topic centres (spread [spread]) because real text
 * embeddings cluster by meaning; uniform random vectors are an unrealistic worst case for
 * approximate search. Set [clusters] to 0 for uniform data.
 * Numbers from this are measurements, not targets.
 */
class VectorBenchmark(
    private val dim: Int = 384,
    private val seed: Long = 42,
    private val batchSize: Int = 1_000,
    private val queries: Int = 50,
    private val recallQueries: Int = 10,
    private val k: Int = 10,
    private val clusters: Int = 500,
    private val spread: Float = 0.6f,
    private val queryNoise: Float = 0.02f,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Result(
        val points: Int,
        val loadMs: Long,
        val optimizeMs: Long,
        val p50Ms: Double,
        val p95Ms: Double,
        val recallAtK: Double,
        val k: Int,
        val diskBytes: Long?,
    )

    suspend fun run(store: VectorStore, points: Int, onProgress: (String) -> Unit = {}): Result {
        val modelId = store.modelId

        val loadStart = nanoTime()
        var i = 0
        while (i < points) {
            val end = minOf(i + batchSize, points)
            store.upsert((i until end).map { Point(id(it), vector(it), modelId, payload = mapOf("n" to "$it")) })
            i = end
            if (i % (batchSize * 10) == 0 || i == points) onProgress("Loaded $i / $points")
        }
        val loadMs = (nanoTime() - loadStart) / 1_000_000

        onProgress("Building index")
        val optStart = nanoTime()
        store.optimize()
        val optimizeMs = (nanoTime() - optStart) / 1_000_000

        onProgress("Searching")
        val rnd = Random(seed xor 0x5EED)
        val latencies = DoubleArray(queries)
        var recallHits = 0
        for (q in 0 until queries) {
            val query = noisy(vector(rnd.nextInt(points)), rnd)
            val t0 = nanoTime()
            val hits = store.search(DenseQuery(query, modelId, k))
            latencies[q] = (nanoTime() - t0) / 1_000_000.0
            if (q < recallQueries) {
                val exact = exactTopK(query, points).toSet()
                recallHits += hits.count { it.id in exact }
            }
        }
        latencies.sort()
        return Result(
            points = points,
            loadMs = loadMs,
            optimizeMs = optimizeMs,
            p50Ms = latencies[latencies.size / 2],
            p95Ms = latencies[((latencies.size - 1) * 95) / 100],
            recallAtK = recallHits.toDouble() / (minOf(recallQueries, queries) * k),
            k = k,
            diskBytes = store.diskBytes(),
        )
    }

    /** Stable UUID-shaped id so stores that require UUIDs (Qdrant) accept it. */
    fun id(n: Int): String = "00000000-0000-4000-8000-%012x".format(n)

    fun vector(n: Int): FloatArray {
        val r = Random(seed * 1_000_003 + n)
        if (clusters <= 0) return normalize(FloatArray(dim) { r.nextFloat() * 2 - 1 })
        val centre = centre(n % clusters)
        return normalize(FloatArray(dim) { centre[it] + (r.nextFloat() * 2 - 1) * spread / sqrt(dim.toFloat()) * 1.7f })
    }

    private fun centre(c: Int): FloatArray {
        val r = Random(seed * 7_919 + c + 1)
        return normalize(FloatArray(dim) { r.nextFloat() * 2 - 1 })
    }

    private fun noisy(v: FloatArray, r: Random): FloatArray =
        normalize(FloatArray(dim) { v[it] + (r.nextFloat() * 2 - 1) * queryNoise })

    private fun exactTopK(query: FloatArray, points: Int): List<String> {
        val best = java.util.PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        for (n in 0 until points) {
            val v = vector(n)
            var dot = 0f
            for (d in 0 until dim) dot += query[d] * v[d]
            if (best.size < k) best.add(dot to n) else if (dot > best.peek().first) {
                best.poll()
                best.add(dot to n)
            }
        }
        return best.map { id(it.second) }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        val inv = 1f / sqrt(norm)
        for (d in v.indices) v[d] *= inv
        return v
    }
}
