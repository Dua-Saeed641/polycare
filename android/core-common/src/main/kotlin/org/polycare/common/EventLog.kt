package org.polycare.common

/**
 * On-device activity log: what the app did and how it went (model verified, search took 12 ms,
 * benchmark results, sync steps...). It is for understanding and debugging the system.
 *
 * Privacy rule: never record query text, household data or anything identifying. Record
 * metadata only (timings, counts, sizes, outcomes).
 */
interface EventLog {
    fun record(category: Category, message: String, fields: Map<String, Any?> = emptyMap(), level: Level = Level.INFO)

    enum class Category { APP, DEVICE, MODEL, VECTOR, SEARCH, KNOWLEDGE, BENCHMARK, SYNC, ASK, TRIAGE, GAPS, HOUSEHOLDS }

    enum class Level { INFO, WARN, ERROR }

    /**
     * @property wallMs display time only; ordering uses the log's append order (invariant 3
     * concerns data ordering, which this log does not take part in).
     */
    data class Event(
        val wallMs: Long,
        val category: Category,
        val level: Level,
        val message: String,
        val fields: Map<String, Any?>,
    )
}

/** For tests and previews. */
class InMemoryEventLog : EventLog {
    val events = mutableListOf<EventLog.Event>()

    override fun record(category: EventLog.Category, message: String, fields: Map<String, Any?>, level: EventLog.Level) {
        events += EventLog.Event(0, category, level, message, fields)
    }
}
