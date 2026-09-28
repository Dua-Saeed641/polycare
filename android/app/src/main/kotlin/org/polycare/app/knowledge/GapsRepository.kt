package org.polycare.app.knowledge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import javax.inject.Inject
import javax.inject.Singleton

data class Gap(val id: String, val query: String, val hlc: Hlc, val confidence: Float)

/**
 * Questions the on-device knowledge base could not answer with confidence (M2: "unanswered
 * questions saved as gaps"). In-memory for now — M4 moves this behind the op-log so gaps
 * survive a restart, and M7 syncs them to the cloud for a supervisor to answer. Never write the
 * `gaps` shard directly outside this seam once the op-log lands (invariant 1, invariant 9).
 *
 * The query text lives here, not in [EventLog]: the event log's own privacy rule is metadata
 * only, but a gap's whole purpose is the question itself, to be answered later.
 */
@Singleton
class GapsRepository @Inject constructor(
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
) {
    private val _gaps = MutableStateFlow<List<Gap>>(emptyList())
    val gaps: StateFlow<List<Gap>> = _gaps.asStateFlow()

    fun log(query: String, confidence: Float) {
        val gap = Gap(idGen.next().toString(), query, clock.now(), confidence)
        _gaps.value = listOf(gap) + _gaps.value
        events.record(Category.GAPS, "Gap logged", mapOf("confidence" to "%.2f".format(confidence)))
    }
}
