package org.polycare.common.radar

import org.polycare.common.PolyCareConfig
import kotlin.math.sqrt

/**
 * One de-identified symptom observation: which danger signs were marked, in which (coarse)
 * village, when. No name, no household, no free text. [vector] is only comparable with vectors
 * of the same [modelId] (invariant 4).
 */
class RadarSignal(
    val id: String,
    val modelId: String,
    val vector: FloatArray,
    val village: String,
    val category: String,
    val label: String,
    val wallMs: Long,
    /** How many identical reports this signal stands for (a "+1" adds to it). */
    val count: Int = 1,
)

enum class AlertLevel(val label: String) {
    /** A cluster is forming but has not yet reached the multi-village threshold. */
    WATCH("Watch"),

    /** Similar symptoms are appearing in several villages at once. */
    ALERT("Alert"),
}

data class RadarAlert(
    val key: String,
    val label: String,
    val category: String,
    val level: AlertLevel,
    val signalCount: Int,
    val villages: List<String>,
    val firstMs: Long,
    val lastMs: Long,
)

/**
 * Outbreak Radar (ARCHITECTURE.md §5.9): a *new dense region of vector space* across several
 * villages is the alert, not a keyword count. Signals in the recent window are grouped by
 * leader clustering on cosine similarity; a group with enough signals from enough distinct
 * villages becomes an [AlertLevel.ALERT], a large single-village group a [AlertLevel.WATCH].
 * Runs identically on the phone (own signals plus those pulled from the cloud) and in the gateway.
 */
object OutbreakRadar {

    fun detect(
        signals: List<RadarSignal>,
        nowMs: Long,
        windowMs: Long = PolyCareConfig.Radar.windowMs,
        clusterCosine: Float = PolyCareConfig.Radar.clusterCosine,
        minSignals: Int = PolyCareConfig.Radar.minSignals,
        minVillages: Int = PolyCareConfig.Radar.minVillages,
    ): List<RadarAlert> {
        val recent = signals.filter { nowMs - it.wallMs in 0..windowMs || it.wallMs > nowMs }
        val alerts = ArrayList<RadarAlert>()
        for ((modelId, group) in recent.groupBy { it.modelId }) {
            val clusters = cluster(group.sortedBy { it.wallMs }, clusterCosine)
            for (c in clusters) {
                val total = c.members.sumOf { it.count }
                val villages = c.members.map { it.village }.filter { it.isNotBlank() }.distinct().sorted()
                val level = when {
                    total >= minSignals && villages.size >= minVillages -> AlertLevel.ALERT
                    total >= minSignals * 2 -> AlertLevel.WATCH
                    else -> null
                } ?: continue
                val label = c.members.groupingBy { it.label }.eachCount().maxByOrNull { it.value }?.key ?: "Unspecified symptoms"
                alerts += RadarAlert(
                    key = "$modelId:${c.members.minOf { it.id }}",
                    label = label,
                    category = c.members.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: "",
                    level = level,
                    signalCount = total,
                    villages = villages,
                    firstMs = c.members.minOf { it.wallMs },
                    lastMs = c.members.maxOf { it.wallMs },
                )
            }
        }
        return alerts.sortedWith(compareByDescending<RadarAlert> { it.level }.thenByDescending { it.signalCount })
    }

    private class Cluster(first: RadarSignal) {
        val members = arrayListOf(first)
        var centroid: FloatArray = normalized(first.vector)
        fun add(s: RadarSignal) {
            members += s
            val n = members.size
            val unit = normalized(s.vector)
            val sum = FloatArray(centroid.size) { i -> centroid[i] * (n - 1) + unit[i] }
            centroid = normalized(sum)
        }
    }

    private fun cluster(signals: List<RadarSignal>, threshold: Float): List<Cluster> {
        val clusters = ArrayList<Cluster>()
        for (s in signals) {
            if (s.vector.isEmpty()) continue
            var best: Cluster? = null
            var bestSim = threshold
            for (c in clusters) {
                if (c.centroid.size != s.vector.size) continue
                val sim = cosine(c.centroid, normalized(s.vector))
                if (sim >= bestSim) { bestSim = sim; best = c }
            }
            if (best != null) best.add(s) else clusters += Cluster(s)
        }
        return clusters
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (d == 0.0) 0f else (dot / d).toFloat()
    }

    private fun normalized(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val d = sqrt(n.toDouble()).toFloat()
        return if (d == 0f) v else FloatArray(v.size) { v[it] / d }
    }
}
