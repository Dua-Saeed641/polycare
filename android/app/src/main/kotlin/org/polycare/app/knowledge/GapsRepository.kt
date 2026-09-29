package org.polycare.app.knowledge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.polycare.app.sync.OpLogStore
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.PolyCareConfig
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import javax.inject.Inject
import javax.inject.Singleton

data class Gap(val id: String, val query: String, val hlc: Hlc, val confidence: Float)

/**
 * Questions the on-device knowledge base could not answer with confidence ("unanswered
 * questions saved as gaps"). Each gap is an op in the op-log (invariant 1) and the list below is
 * a view rebuilt from it, so gaps survive a restart and the Sync Gate can send them to a
 * supervisor, whose answer comes back as team guidance.
 *
 * The query text lives here, not in [EventLog]: the event log's own privacy rule is metadata
 * only, but a gap's whole purpose is the question itself, to be answered later.
 */
@Singleton
class GapsRepository @Inject constructor(
    private val opLog: OpLogStore,
    private val events: EventLog,
) {
    private val _gaps = MutableStateFlow(rebuild())
    val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()

    fun log(query: String, confidence: Float) {
        val text = query.trim().take(PolyCareConfig.Gaps.maxQueryChars)
        if (text.isEmpty()) return
        // Same question already waiting? Don't stack duplicates, the count matters to the cloud not the phone.
        if (_gaps.value.any { it.query.equals(text, ignoreCase = true) }) return
        val stored = opLog.append(
            OpEntity.GAP, Op.UPSERT, entityId = "gap-" + System.nanoTime().toString(36),
            payload = mapOf("query" to text, "confidence" to "%.2f".format(confidence)),
        )
        _gaps.value = listOf(Gap(stored.op.entityId, text, stored.op.hlc, confidence)) + _gaps.value
        events.record(Category.GAPS, "Gap logged", mapOf("confidence" to "%.2f".format(confidence)))
    }

    /** Removes a gap once an answer arrived for it. */
    fun resolve(query: String) {
        val match = _gaps.value.firstOrNull { it.query.equals(query, ignoreCase = true) } ?: return
        opLog.append(OpEntity.GAP, Op.DELETE, match.id, emptyMap())
        _gaps.value = _gaps.value.filterNot { it.id == match.id }
    }

    private fun rebuild(): List<Gap> {
        val live = LinkedHashMap<String, Gap>()
        for (s in opLog.byEntity(OpEntity.GAP)) {
            val op = s.op
            if (op.action == Op.DELETE) live.remove(op.entityId)
            else live[op.entityId] = Gap(op.entityId, op.payload["query"].orEmpty(), op.hlc, op.payload["confidence"]?.toFloatOrNull() ?: 0f)
        }
        return live.values.sortedByDescending { it.hlc }
    }
}
