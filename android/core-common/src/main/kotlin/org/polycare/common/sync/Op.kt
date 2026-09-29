package org.polycare.common.sync

import org.polycare.common.Hlc

/** What kind of record an [Op] changes. Only [SIGNAL] and [GAP] may ever leave the phone. */
enum class OpEntity(val wire: String) {
    HOUSEHOLD("household"),
    MEMBER("member"),
    VISIT("visit"),
    DUE_ITEM("due_item"),
    GAP("gap"),
    SIGNAL("signal"),
    ;

    companion object {
        fun fromWire(value: String): OpEntity? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One mutation, appended to the op-log *before* any view of the data changes (invariant 1).
 * [opId] is a UUIDv7, the idempotency key: applying the same op twice is a no-op (invariant 2).
 * [payload] is flat string key/values so the wire format never depends on a JSON library here.
 */
data class Op(
    val opId: String,
    val hlc: Hlc,
    val entity: OpEntity,
    val action: String,
    val entityId: String,
    val payload: Map<String, String>,
) {
    companion object {
        const val UPSERT = "upsert"
        const val DELETE = "delete"
    }
}

/** An [Op] as stored: [seq] is its position in this phone's log and is only ever local. */
data class StoredOp(val seq: Long, val op: Op)
