package org.polycare.common.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.polycare.common.Hlc

class SyncGateTest {
    private fun op(entity: OpEntity, action: String = Op.UPSERT, payload: Map<String, String>) =
        Op("op-1", Hlc(1_000, 0, "phone-a"), entity, action, "record-1", payload)

    @Test
    fun householdAndMemberRecordsNeverLeaveThePhone() {
        for (entity in listOf(OpEntity.HOUSEHOLD, OpEntity.MEMBER, OpEntity.VISIT, OpEntity.DUE_ITEM)) {
            assertEquals(GateDecision.KeepLocal::class, SyncGate.decide(op(entity, payload = emptyMap()))::class)
        }
    }

    @Test
    fun aGapIsShareableOnlyWhenItIsAnUpsertWithoutPersonalFields() {
        assertEquals(
            GateDecision.Push,
            SyncGate.decide(op(OpEntity.GAP, payload = mapOf("query" to "newborn fast breathing"))),
        )
        assertEquals(
            GateDecision.KeepLocal::class,
            SyncGate.decide(op(OpEntity.GAP, payload = mapOf("query" to "question", "householdId" to "hh-1")))::class,
        )
        assertEquals(
            GateDecision.KeepLocal::class,
            SyncGate.decide(op(OpEntity.GAP, action = Op.DELETE, payload = mapOf("query" to "question")))::class,
        )
    }

    @Test
    fun signalRequiresFullWireShapeAndRejectsIdentityKeysCaseInsensitively() {
        val valid = SyncGate.signalWireFields.associateWith { "value" }
        assertEquals(GateDecision.Push, SyncGate.decide(op(OpEntity.SIGNAL, payload = valid)))
        assertEquals(
            GateDecision.KeepLocal::class,
            SyncGate.decide(op(OpEntity.SIGNAL, payload = valid + ("NaMe" to "person")))::class,
        )
        assertEquals(
            GateDecision.KeepLocal::class,
            SyncGate.decide(op(OpEntity.SIGNAL, payload = mapOf("simhash" to "1")))::class,
        )
    }

    @Test
    fun lowPriorityTipWaitsOnMeteredNetwork() {
        val payload = SyncGate.tipWireFields.associateWith { "value" } + ("priority" to "0.01")
        assertEquals(GateDecision.Defer::class, SyncGate.decide(op(OpEntity.TIP, payload = payload), metered = true)::class)
        assertEquals(GateDecision.Push, SyncGate.decide(op(OpEntity.TIP, payload = payload), metered = false))
    }

    @Test
    fun votesShareOnlyTheTipReference() {
        assertEquals(
            GateDecision.Push,
            SyncGate.decide(op(OpEntity.VOTE, payload = mapOf("cloud_point_id" to "tip-1"))),
        )
        assertEquals(
            GateDecision.KeepLocal::class,
            SyncGate.decide(op(OpEntity.VOTE, payload = mapOf("cloud_point_id" to "", "memberName" to "person")))::class,
        )
    }
}
