package org.polycare.common.sync

/** What the Sync Gate decided for one op. */
sealed interface GateDecision {
    /** Team knowledge: send it, signed, in its wire shape. */
    data object Push : GateDecision

    /** Personal or purely local: never leaves the phone (invariant 7). */
    data class KeepLocal(val reason: String) : GateDecision

    /**
     * Shareable, but not worth this connection: stays pending and is retried on an unmetered link
     * (a data-plan phone sends only what matters most).
     */
    data class Defer(val reason: String) : GateDecision
}

/**
 * Decides what is team knowledge (push) and what is personal (keep on the phone), ARCHITECTURE.md
 * §5.7. Pure and deterministic. The gateway enforces the same rule on its side
 * (`_validate_cloud_op`), so a bug here can never leak a household.
 *
 * Four things can leave, and nothing else: a de-identified `SIGNAL` (with its embedding, so it is
 * shareable only if one was computed when it was recorded), a `GAP` (a question the ASHA could not
 * get answered offline), a `TIP` the ASHA chose to share, and a `VOTE` for someone else's tip.
 */
object SyncGate {

    /** Payload keys that identify a person or a household and must never appear in a pushed op. */
    private val forbiddenKeys = setOf(
        "name", "headofhousehold", "membername", "phone", "address", "notes", "householdid", "memberid",
        "household", "member", "patient", "aadhaar",
    )

    /** Payload fields a wire-ready signal must already carry (see [SignalCodec]). */
    val signalWireFields = listOf("dense_f16", "emb_model_id", "simhash", "village_code", "week", "age_band", "sex")

    /** Payload fields a wire-ready tip must already carry. */
    val tipWireFields = listOf("text", "dense_f16", "emb_model_id", "simhash", "village_code")

    /** Below this [TipGate] priority a tip waits for an unmetered connection. */
    const val METERED_MIN_PRIORITY = 0.05

    fun decide(op: Op, metered: Boolean = false): GateDecision {
        val leak = op.payload.keys.firstOrNull { it.lowercase() in forbiddenKeys }
        return when (op.entity) {
            OpEntity.SIGNAL -> when {
                leak != null -> GateDecision.KeepLocal("payload field '$leak' could identify a person")
                signalWireFields.any { it !in op.payload } ->
                    GateDecision.KeepLocal("recorded without the search model, so it has no shareable embedding")
                else -> GateDecision.Push
            }
            OpEntity.GAP -> when {
                op.action != Op.UPSERT -> GateDecision.KeepLocal("local housekeeping")
                leak != null -> GateDecision.KeepLocal("payload field '$leak' could identify a person")
                op.payload["query"].isNullOrBlank() -> GateDecision.KeepLocal("empty question")
                else -> GateDecision.Push
            }
            OpEntity.TIP -> when {
                op.action != Op.UPSERT -> GateDecision.KeepLocal("local housekeeping")
                leak != null -> GateDecision.KeepLocal("payload field '$leak' could identify a person")
                tipWireFields.any { it !in op.payload } -> GateDecision.KeepLocal("kept on this phone: no shareable embedding")
                metered && (op.payload["priority"]?.toDoubleOrNull() ?: 1.0) < METERED_MIN_PRIORITY ->
                    GateDecision.Defer("low priority on a metered connection")
                else -> GateDecision.Push
            }
            OpEntity.VOTE -> when {
                op.action != Op.UPSERT -> GateDecision.KeepLocal("local housekeeping")
                op.payload["cloud_point_id"].isNullOrBlank() -> GateDecision.KeepLocal("no tip to vote for")
                else -> GateDecision.Push
            }
            else -> GateDecision.KeepLocal("${op.entity.wire} records are personal health data")
        }
    }
}
