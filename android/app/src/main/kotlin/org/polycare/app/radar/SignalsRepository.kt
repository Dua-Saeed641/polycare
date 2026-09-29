package org.polycare.app.radar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.knowledge.TriageCategory
import org.polycare.app.knowledge.TriageEngine
import org.polycare.app.sync.OpLogStore
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.HlcClock
import org.polycare.common.PolyCareConfig
import org.polycare.common.radar.AlertLevel
import org.polycare.common.radar.OutbreakRadar
import org.polycare.common.radar.RadarAlert
import org.polycare.common.radar.RadarSignal
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class AlertSource { THIS_PHONE, CLOUD }

data class RadarItem(val alert: RadarAlert, val source: AlertSource)

/**
 * Outbreak Radar's phone side. A *signal* is what a triage case says about the community and
 * nothing about the person: which danger signs were marked, the category, a coarse village and
 * the day. It carries no name, no household id and no free text, which is why the Sync Gate may
 * send it (invariant 7).
 *
 * Signals are encoded as a multi-hot vector over the danger-sign vocabulary
 * ([PolyCareConfig.Radar.signalModelId]); the same model id is what the cloud clusters on, so
 * vectors from different encodings are never compared (invariant 4). The phone runs the same
 * [OutbreakRadar] over its own signals, and merges in the alerts the cloud found across all
 * villages, so an alert is visible with or without signal.
 */
@Singleton
class SignalsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val opLog: OpLogStore,
    private val clock: HlcClock,
    private val events: EventLog,
) {
    private val vocabulary: List<String> =
        TriageCategory.entries.flatMap { TriageEngine.signs(it) }.map { it.id }.distinct().sorted()

    private val cloudFile = File(context.filesDir, "radar_cloud_alerts.json")
    private var cloudAlerts: List<RadarAlert> = loadCloud()

    private val _signals = MutableStateFlow(rebuildSignals())
    /** This phone's own signals, newest first. */
    val signals: StateFlow<List<RadarSignal>> = _signals.asStateFlow()

    private val _items = MutableStateFlow(computeItems())
    val items: StateFlow<List<RadarItem>> = _items.asStateFlow()

    /** Encodes marked signs as the fixed-length multi-hot vector the radar clusters on. */
    fun encode(signIds: Set<String>): FloatArray = FloatArray(vocabulary.size) { if (vocabulary[it] in signIds) 1f else 0f }

    /**
     * Records one de-identified signal for a triage case. Returns false (and records nothing) if
     * there is nothing to report or no village to attach it to. Care-at-home cases with no signs
     * produce no signal: the radar is about danger signs.
     */
    fun record(category: TriageCategory, signIds: Set<String>, village: String): Boolean {
        val ids = signIds.filter { it in vocabulary }.toSortedSet()
        val place = village.trim()
        if (ids.isEmpty() || place.isEmpty()) return false

        val labels = TriageEngine.signs(category).filter { it.id in ids }.map { it.label }
        val label = labels.joinToString("; ").take(200)
        val now = clock.now().wallMs
        val day = now / DAY_MS
        val dedupKey = "${category.name}|${ids.joinToString(",")}|${place.lowercase()}|$day"
        val vector = encode(ids)

        val stored = opLog.append(
            OpEntity.SIGNAL, Op.UPSERT, entityId = "sig-" + java.lang.Long.toString(now, 36) + "-" + ids.hashCode().toString(36),
            payload = mapOf(
                "modelId" to PolyCareConfig.Radar.signalModelId,
                "vector" to vector.joinToString(",") { "%.0f".format(it) },
                "village" to place,
                "category" to category.name,
                "label" to label,
                "wallMs" to now.toString(),
                "dedupKey" to dedupKey,
            ),
        )
        events.record(Category.RADAR, "Signal recorded", mapOf("category" to category.name, "signs" to ids.size))
        _signals.value = listOf(toSignal(stored.op)!!) + _signals.value
        _items.value = computeItems()
        return true
    }

    /** Replaces the alerts the cloud computed across all villages (sync pull). */
    fun setCloudAlerts(alerts: List<RadarAlert>) {
        cloudAlerts = alerts
        runCatching {
            val arr = JSONArray()
            alerts.forEach { a ->
                arr.put(
                    JSONObject().put("key", a.key).put("label", a.label).put("category", a.category)
                        .put("level", a.level.name).put("count", a.signalCount)
                        .put("villages", JSONArray(a.villages)).put("first", a.firstMs).put("last", a.lastMs),
                )
            }
            cloudFile.writeText(arr.toString())
        }
        _items.value = computeItems()
        events.record(Category.RADAR, "Cloud alerts updated", mapOf("alerts" to alerts.size))
    }

    /** Re-evaluates time windows (an old signal ages out) without any new input. */
    fun refresh() {
        _items.value = computeItems()
    }

    private fun computeItems(): List<RadarItem> {
        val now = clock.now().wallMs
        val local = OutbreakRadar.detect(_signalsOrEmpty(), now).map { RadarItem(it, AlertSource.THIS_PHONE) }
        val cloud = cloudAlerts.map { RadarItem(it, AlertSource.CLOUD) }
        // The cloud sees every village; when both describe the same syndrome show the cloud's.
        val cloudLabels = cloud.map { it.alert.label }.toSet()
        return (cloud + local.filterNot { it.alert.label in cloudLabels })
            .sortedWith(compareByDescending<RadarItem> { it.alert.level }.thenByDescending { it.alert.signalCount })
    }

    private fun _signalsOrEmpty(): List<RadarSignal> = runCatching { _signals.value }.getOrDefault(emptyList())

    private fun rebuildSignals(): List<RadarSignal> =
        opLog.byEntity(OpEntity.SIGNAL).mapNotNull { toSignal(it.op) }.sortedByDescending { it.wallMs }

    private fun toSignal(op: Op): RadarSignal? {
        val p = op.payload
        val vector = p["vector"]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: return null
        return RadarSignal(
            id = op.entityId,
            modelId = p["modelId"] ?: return null,
            vector = vector,
            village = p["village"].orEmpty(),
            category = p["category"].orEmpty(),
            label = p["label"].orEmpty(),
            wallMs = p["wallMs"]?.toLongOrNull() ?: op.hlc.wallMs,
        )
    }

    private fun loadCloud(): List<RadarAlert> = runCatching {
        if (!cloudFile.exists()) return emptyList()
        val arr = JSONArray(cloudFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val v = o.getJSONArray("villages")
            RadarAlert(
                key = o.getString("key"), label = o.getString("label"), category = o.optString("category"),
                level = AlertLevel.valueOf(o.getString("level")), signalCount = o.getInt("count"),
                villages = List(v.length()) { v.getString(it) }, firstMs = o.getLong("first"), lastMs = o.getLong("last"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
