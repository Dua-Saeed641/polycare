package org.polycare.common.sync

import org.polycare.common.radar.OutbreakRadar

/** A team tip already known on this phone, as the gate sees it: an id and its embedding. */
class KnownTip(val id: String, val vector: FloatArray)

sealed interface TipDecision {
    /** A near-duplicate already exists: vote for it instead of sending a copy. */
    data class VoteFor(val tipId: String, val similarity: Float) : TipDecision

    /** New enough to share. [priority] = novelty x (1 + rknn); higher goes first and survives a metered link. */
    data class Share(val novelty: Float, val rknn: Int, val priority: Double) : TipDecision
}

/**
 * Novelty and hubness scoring for team tips (ARCHITECTURE.md §5.7):
 *
 *  - `novelty = 1 - max cosine to any known tip`. A tip that is nearly the same as one already
 *    shared is not sent; the ASHA's vote goes to the existing one, so the team's memory does not
 *    fill up with paraphrases and the outbox stays small.
 *  - `rknn(p)` (hubness) counts known tips that would list `p` among their nearest neighbours: a tip
 *    that many others resemble sits in a well-trodden region and is a hub worth sending early.
 *  - `priority = novelty x (1 + rknn)`.
 *
 * Vectors are only compared with vectors of the same model; the caller filters by model id first.
 */
object TipGate {
    /** Cosine at or above which a candidate counts as a duplicate of an existing tip. */
    const val DUPLICATE_COSINE = 0.92f

    /** How many nearest neighbours define a tip's "kNN radius". */
    const val K = 3

    /** Fallback radius for a tip that has fewer than [K] neighbours. */
    const val DEFAULT_KNN_RADIUS = 0.75f

    fun decide(candidate: FloatArray, known: List<KnownTip>): TipDecision {
        val same = known.filter { it.vector.size == candidate.size }
        var best: KnownTip? = null
        var bestSim = -1f
        for (t in same) {
            val sim = OutbreakRadar.cosine(candidate, t.vector)
            if (sim > bestSim) { bestSim = sim; best = t }
        }
        if (best != null && bestSim >= DUPLICATE_COSINE) return TipDecision.VoteFor(best.id, bestSim)

        val novelty = if (best == null) 1f else (1f - bestSim).coerceIn(0f, 1f)
        val rknn = same.count { t ->
            OutbreakRadar.cosine(candidate, t.vector) >= knnRadius(t, same)
        }
        return TipDecision.Share(novelty, rknn, novelty * (1.0 + rknn))
    }

    /** Cosine to `t`'s [K]-th nearest neighbour among [pool] (excluding itself). */
    private fun knnRadius(t: KnownTip, pool: List<KnownTip>): Float {
        val sims = pool.filter { it !== t }.map { OutbreakRadar.cosine(t.vector, it.vector) }.sortedDescending()
        return sims.getOrNull(K - 1) ?: DEFAULT_KNN_RADIUS
    }
}
