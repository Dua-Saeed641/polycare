package org.polycare.common.sync

/** What the Sync Gate decided for one op. */
sealed interface GateDecision {
    /** Team knowledge: send the whole op. */
    data object Push : GateDecision

    /** Personal data: never leaves the phone (invariant 7). */
    data class KeepLocal(val reason: String) : GateDecision

    /** Already reported: send only a "+1" for [dedupKey] instead of the full record. */
    data class PlusOne(val dedupKey: String) : GateDecision
}

/**
 * Decides what is team knowledge (push), what is personal (keep on the phone) and what is
 * redundant (send a "+1" only) — ARCHITECTURE.md §5.7. Pure and deterministic; the gateway
 * enforces the same rule again on its side, so a bug here can never leak a household.
 */
object SyncGate {

    /** Payload keys that identify a person or a household and must never appear in a pushed op. */
    private val forbiddenKeys = setOf(
        "name", "headofhousehold", "membername", "phone", "address", "notes", "householdid", "memberid",
    )

    /** Entities that may leave the phone at all. */
    private val shareable = setOf(OpEntity.SIGNAL, OpEntity.GAP)

    /**
     * @param alreadyReported dedup keys of signals this phone has already pushed; a repeat of one
     *   is reduced to a "+1" so the cloud still counts it without receiving a near-duplicate.
     */
    fun decide(op: Op, alreadyReported: Set<String> = emptySet()): GateDecision {
        if (op.entity !in shareable) {
            return GateDecision.KeepLocal("${op.entity.wire} records are personal health data")
        }
        val leak = op.payload.keys.firstOrNull { it.lowercase() in forbiddenKeys }
        if (leak != null) return GateDecision.KeepLocal("payload field '$leak' could identify a person")
        if (op.entity == OpEntity.SIGNAL) {
            val key = op.payload["dedupKey"]
            if (key != null && key in alreadyReported) return GateDecision.PlusOne(key)
        }
        return GateDecision.Push
    }
}
