package org.polycare.app.radar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.ai.EmbedderProvider
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
import org.polycare.common.sync.SignalCodec
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
    private val embedders: EmbedderProvider,
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

    /** Outcome of [record]: nothing was reported, or it was, and whether it can be shared with the team. */
    enum class Recorded { NOTHING, THIS_PHONE_ONLY, SHAREABLE }

    /** Default age band for a triage category; the ASHA can refine it before reporting. */
    fun defaultAgeBand(category: TriageCategory): String = when (category) {
        TriageCategory.NEWBORN -> "0-1"
        TriageCategory.CHILD -> "1-4"
        TriageCategory.POSTPARTUM -> "20-29"
    }

    /**
     * Records one de-identified signal for a triage case. Returns [Recorded.NOTHING] if there is
     * nothing to report or no village to attach it to (care-at-home cases with no signs produce no
     * signal: the radar is about danger signs).
     *
     * Two vectors are kept. A small multi-hot vector over the danger-sign vocabulary drives this
     * phone's own radar and needs nothing installed. The gateway's process needs an e5 embedding
     * of a fixed, de-identified sentence (dimension 384, float16), a 16-bit SimHash, a village
     * code, the ISO week, an age band and a sex ([SignalCodec]); those are computed **now** and
     * stored in the op, so a retried push is byte-identical and its signature stays valid. If the
     * search model is not installed the signal is kept on this phone only.
     */
    suspend fun record(
        category: TriageCategory,
        signIds: Set<String>,
        village: String,
        ageBand: String = defaultAgeBand(category),
        sex: String = if (category == TriageCategory.POSTPARTUM) "F" else "U",
    ): Recorded {
        val ids = signIds.filter { it in vocabulary }.toSortedSet()
        val place = village.trim()
        if (ids.isEmpty() || place.isEmpty()) return Recorded.NOTHING

        val labels = TriageEngine.signs(category).filter { it.id in ids }.map { it.label }
        val label = labels.joinToString("; ").take(200)
        val now = clock.now().wallMs
        val payload = linkedMapOf(
            "modelId" to PolyCareConfig.Radar.signalModelId,
            "vector" to encode(ids).joinToString(",") { "%.0f".format(it) },
            "village" to place,
            "category" to category.name,
            "label" to label,
            "wallMs" to now.toString(),
        )

        // The sentence embedded contains only the marked danger-sign wording and the category,
        // never a name, never free text typed by the ASHA.
        val ready = embedders.get()
        val embedding = ready?.let { r ->
            runCatching { r.embedder.embedPassages(listOf("${category.label}. Danger signs: $label")).first() }.getOrNull()
        }
        if (ready != null && embedding != null) {
            payload["dense_f16"] = SignalCodec.denseF16Base64(embedding)
            payload["emb_model_id"] = ready.embedder.modelId
            payload["simhash"] = SignalCodec.simhash16(embedding).toString()
            payload["village_code"] = SignalCodec.villageCode(place)
            payload["week"] = SignalCodec.isoWeek(now).toString()
            payload["age_band"] = ageBand
            payload["sex"] = sex
        }

        val stored = opLog.append(
            OpEntity.SIGNAL, Op.UPSERT,
            entityId = "sig-" + java.lang.Long.toString(now, 36) + "-" + ids.hashCode().toString(36),
            payload = payload,
        )
        events.record(Category.RADAR, "Signal recorded", mapOf("category" to category.name, "signs" to ids.size, "shareable" to (embedding != null)))
        _signals.value = listOf(toSignal(stored.op)!!) + _signals.value
        _items.value = computeItems()
        return if (embedding != null) Recorded.SHAREABLE else Recorded.THIS_PHONE_ONLY
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
