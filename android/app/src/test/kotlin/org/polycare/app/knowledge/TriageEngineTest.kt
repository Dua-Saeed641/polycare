package org.polycare.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class TriageEngineTest {

    @Test
    fun `no signs selected means care at home`() {
        val result = TriageEngine.evaluate(TriageCategory.CHILD, emptySet())
        assertEquals(TriageDecision.CARE_AT_HOME, result.decision)
    }

    @Test
    fun `one urgent newborn sign means refer now`() {
        val result = TriageEngine.evaluate(TriageCategory.NEWBORN, setOf("nb-convulsions"))
        assertEquals(TriageDecision.REFER_NOW, result.decision)
        assertEquals(1, result.matched.size)
    }

    @Test
    fun `child with only a 24h sign is referred within 24 hours, not urgently`() {
        val result = TriageEngine.evaluate(TriageCategory.CHILD, setOf("ch-fast-breathing"))
        assertEquals(TriageDecision.REFER_24H, result.decision)
    }

    @Test
    fun `an urgent sign always wins over a 24h sign`() {
        val result = TriageEngine.evaluate(TriageCategory.CHILD, setOf("ch-fast-breathing", "ch-convulsions"))
        assertEquals(TriageDecision.REFER_NOW, result.decision)
    }

    @Test
    fun `postpartum heavy bleeding means refer now`() {
        val result = TriageEngine.evaluate(TriageCategory.POSTPARTUM, setOf("pp-bleeding"))
        assertEquals(TriageDecision.REFER_NOW, result.decision)
    }
}
