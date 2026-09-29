package org.polycare.app.households

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.polycare.common.HlcClock
import org.polycare.common.InMemoryEventLog
import org.polycare.common.UuidV7

class HouseholdsRepositoryTest {

    private lateinit var clock: HlcClock
    private lateinit var idGen: UuidV7
    private lateinit var events: InMemoryEventLog
    private lateinit var repo: HouseholdsRepository

    @Before
    fun setUp() {
        clock = HlcClock("test-node", { 1_700_000_000_000L })
        idGen = UuidV7({ 1_700_000_000_000L })
        events = InMemoryEventLog()
        repo = HouseholdsRepository(clock, idGen, events)
    }

    @Test
    fun consentGateBlocksAddingMembersWhenConsentNotGiven() {
        val noConsent = repo.addHousehold("Ramesh Kumar", "Rampur", consentGiven = false)
        val blocked = repo.addMember(noConsent.id, "Sonu", 4, "Child")
        assertNull("Member must not be added without household consent", blocked)

        val withConsent = repo.addHousehold("Sita Devi", "Rampur", consentGiven = true)
        val allowed = repo.addMember(withConsent.id, "Pooja", 3, "Child")
        assertNotNull("Member must be added when consent is given", allowed)
        assertEquals("Pooja", allowed?.name)
    }

    @Test
    fun consentGateBlocksRecordingVisitsWhenConsentNotGiven() {
        val noConsent = repo.addHousehold("Rajesh", "Kalyanpur", consentGiven = false)
        val blockedVisit = repo.recordVisit(
            householdId = noConsent.id,
            memberId = null,
            memberName = "Rajesh",
            type = VisitType.ROUTINE,
            notes = "Check up",
        )
        assertNull("Visit must not be recorded without consent", blockedVisit)
    }

    @Test
    fun recordsVisitAndCompletesDueListItem() {
        val hh = repo.addHousehold("Sunita Devi", "Rampur", consentGiven = true)
        val member = repo.addMember(hh.id, "Sunita Devi", 24, "Mother")!!
        repo.scheduleFollowUp(hh.id, member.id, member.name, VisitType.ANC, inDays = 0)
        val initialDueCount = repo.dueItems.value.count { !it.completed }
        val targetDue = repo.dueItems.value.first { !it.completed }

        val visit = repo.recordVisit(
            householdId = targetDue.householdId,
            memberId = targetDue.memberId,
            memberName = targetDue.memberName,
            type = targetDue.visitType,
            notes = "Completed scheduled visit successfully. All vitals normal.",
            highRisk = false,
            dueItemId = targetDue.id,
        )

        assertNotNull(visit)
        assertEquals(targetDue.visitType.defaultIncentiveRupees, visit?.incentiveRupees)

        val afterDueCount = repo.dueItems.value.count { !it.completed }
        assertEquals(initialDueCount - 1, afterDueCount)

        val updatedItem = repo.dueItems.value.first { it.id == targetDue.id }
        assertTrue(updatedItem.completed)
    }

    @Test
    fun calculatesMonthlyIncentiveReportCorrectly() {
        val hh = repo.addHousehold("Maya Devi", "Rampur", consentGiven = true)

        repo.recordVisit(hh.id, null, "Maya Devi", VisitType.ANC, "ANC 1", incentiveRupees = 300)
        repo.recordVisit(hh.id, null, "Maya Devi", VisitType.PNC, "PNC Day 3", incentiveRupees = 200)
        repo.recordVisit(hh.id, null, "Child of Maya", VisitType.IMMUNIZATION, "BCG & OPV", incentiveRupees = 150)
        repo.recordVisit(hh.id, null, "Maya Devi", VisitType.FAMILY_PLANNING, "Counselling", incentiveRupees = 150)

        val report = repo.monthlyReport()
        assertEquals(4, report.totalVisits)
        assertEquals(1, report.ancCount)
        assertEquals(1, report.pncCount)
        assertEquals(1, report.immunizationCount)
        assertEquals(1, report.familyPlanningCount)
        assertEquals(300 + 200 + 150 + 150, report.totalIncentiveRupees)
    }

    @Test
    fun searchesVisitNotesByTerm() = runBlocking {
        val hh = repo.addHousehold("Geeta Devi", "Rampur", consentGiven = true)
        repo.recordVisit(hh.id, null, "Geeta Devi", VisitType.ANC, "Patient reported swollen feet and mild dizziness", highRisk = true)
        repo.recordVisit(hh.id, null, "Geeta Devi", VisitType.ROUTINE, "Routine sanitation and clean drinking water advice")

        val searchSwollen = repo.searchVisits("swollen feet")
        assertTrue(searchSwollen.isNotEmpty())
        assertEquals("Geeta Devi", searchSwollen.first().householdName)
        assertTrue(searchSwollen.first().visit.notes.contains("swollen feet"))
    }
}
