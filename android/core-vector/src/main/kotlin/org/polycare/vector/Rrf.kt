package org.polycare.vector

import org.polycare.common.PolyCareConfig

/** Reciprocal Rank Fusion for hybrid (dense + sparse) search when Edge cannot fuse natively. */
object Rrf {
    fun fuse(
        rankings: List<List<ScoredPoint>>,
        limit: Int,
        k: Int = PolyCareConfig.Retrieval.rrfK,
    ): List<ScoredPoint> {
        val scores = HashMap<String, Float>()
        val payloads = HashMap<String, Map<String, String>>()
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, point ->
                scores.merge(point.id, 1f / (k + rank + 1), Float::plus)
                payloads.putIfAbsent(point.id, point.payload)
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { ScoredPoint(it.key, it.value, payloads.getValue(it.key)) }
    }
}
