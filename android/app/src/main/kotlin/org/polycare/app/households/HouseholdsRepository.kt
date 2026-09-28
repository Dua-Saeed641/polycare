package org.polycare.app.households

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedderProvider
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import javax.inject.Inject
import javax.inject.Singleton

data class Household(
    val id: String,
    val headOfHousehold: String,
    val village: String,
    val consentGiven: Boolean,
    val hlc: Hlc,
)

data class Member(
    val id: String,
    val householdId: String,
    val name: String,
    val age: Int,
    val relation: String,
    val hlc: Hlc,
)

enum class VisitType(val label: String, val defaultIncentiveRupees: Int) {
    ANC("ANC Check-up", 300),
    PNC("PNC Home Visit", 200),
    IMMUNIZATION("Immunization Follow-up", 150),
    CHILD_ILLNESS("Sick Child Care", 0),
    FAMILY_PLANNING("Family Planning Counselling", 150),
    ROUTINE("Routine Household Visit", 0),
}

data class Visit(
    val id: String,
    val householdId: String,
    val memberId: String? = null,
    val memberName: String? = null,
    val type: VisitType,
    val notes: String,
    val date: String,
    val highRisk: Boolean = false,
    val incentiveRupees: Int = type.defaultIncentiveRupees,
    val hlc: Hlc,
    val embedding: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Visit
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class DuePriority(val label: String, val order: Int) {
    HIGH("High Risk", 1),
    TODAY("Due Today", 2),
    UPCOMING("Upcoming", 3),
}

data class DueItem(
    val id: String,
    val householdId: String,
    val householdHead: String,
    val memberId: String? = null,
    val memberName: String,
    val village: String,
    val visitType: VisitType,
    val dueDate: String,
    val priority: DuePriority,
    val reason: String,
    val completed: Boolean = false,
)

data class MonthlyIncentiveReport(
    val totalIncentiveRupees: Int,
    val totalVisits: Int,
    val ancCount: Int,
    val pncCount: Int,
    val immunizationCount: Int,
    val familyPlanningCount: Int,
    val highRiskCount: Int,
    val visits: List<Visit>,
)

data class VisitSearchResult(
    val visit: Visit,
    val householdName: String,
    val village: String,
    val score: Float,
)

/**
 * Household and daily work repository (M3: "Household and member records with consent capture",
 * "Due list and visit planner", "Visit notes searchable by meaning", "Monthly report and incentive tracker").
 *
 * Invariant 1: In-memory MVP — M4 moves this behind the op-log.
 * Invariant 7: Personal health records never leave the phone. Nothing here is ever placed in an outbox.
 */
@Singleton
class HouseholdsRepository internal constructor(
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
    private val embedders: EmbedderProvider?,
    @Suppress("UNUSED_PARAMETER") forTestingOnly: Boolean,
) {
    @Inject
    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
        embedders: EmbedderProvider,
    ) : this(clock, idGen, events, embedders, false)

    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
    ) : this(clock, idGen, events, null, true)

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _households = MutableStateFlow<List<Household>>(emptyList())
    val households: StateFlow<List<Household>> = _households.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _visits = MutableStateFlow<List<Visit>>(emptyList())
    val visits: StateFlow<List<Visit>> = _visits.asStateFlow()

    private val _dueItems = MutableStateFlow<List<DueItem>>(emptyList())
    val dueItems: StateFlow<List<DueItem>> = _dueItems.asStateFlow()

    init {
        seedInitialDataIfEmpty()
    }

    private fun seedInitialDataIfEmpty() {
        if (_households.value.isNotEmpty()) return

        val h1 = Household(idGen.next().toString(), "Sunita Devi", "Rampur", consentGiven = true, clock.now())
        val h2 = Household(idGen.next().toString(), "Meena Kumari", "Rampur", consentGiven = true, clock.now())
        val h3 = Household(idGen.next().toString(), "Rekha Sharma", "Chandpur", consentGiven = true, clock.now())
        _households.value = listOf(h1, h2, h3)

        val m1 = Member(idGen.next().toString(), h1.id, "Sunita Devi", 24, "Mother (Pregnant)", clock.now())
        val m2 = Member(idGen.next().toString(), h2.id, "Baby of Meena", 0, "Newborn (7 days)", clock.now())
        val m3 = Member(idGen.next().toString(), h3.id, "Aarav Sharma", 1, "Child", clock.now())
        _members.value = listOf(m1, m2, m3)

        _dueItems.value = listOf(
            DueItem(
                id = idGen.next().toString(),
                householdId = h1.id,
                householdHead = h1.headOfHousehold,
                memberId = m1.id,
                memberName = m1.name,
                village = h1.village,
                visitType = VisitType.ANC,
                dueDate = "Today",
                priority = DuePriority.HIGH,
                reason = "3rd ANC checkup due; history of elevated blood pressure",
            ),
            DueItem(
                id = idGen.next().toString(),
                householdId = h2.id,
                householdHead = h2.headOfHousehold,
                memberId = m2.id,
                memberName = m2.name,
                village = h2.village,
                visitType = VisitType.PNC,
                dueDate = "Today",
                priority = DuePriority.TODAY,
                reason = "Day 7 home PNC visit; cord care & thermal check",
            ),
            DueItem(
                id = idGen.next().toString(),
                householdId = h3.id,
                householdHead = h3.headOfHousehold,
                memberId = m3.id,
                memberName = m3.name,
                village = h3.village,
                visitType = VisitType.IMMUNIZATION,
                dueDate = "Overdue",
                priority = DuePriority.HIGH,
                reason = "Missed Pentavalent-1 & Rotavirus scheduled vaccine",
            ),
            DueItem(
                id = idGen.next().toString(),
                householdId = h1.id,
                householdHead = h1.headOfHousehold,
                memberId = m1.id,
                memberName = m1.name,
                village = h1.village,
                visitType = VisitType.FAMILY_PLANNING,
                dueDate = "Next week",
                priority = DuePriority.UPCOMING,
                reason = "Post-delivery birth spacing counselling",
            ),
        )
    }

    fun addHousehold(headOfHousehold: String, village: String, consentGiven: Boolean): Household {
        val household = Household(idGen.next().toString(), headOfHousehold, village, consentGiven, clock.now())
        _households.value = listOf(household) + _households.value
        events.record(Category.HOUSEHOLDS, "Household added", mapOf("consentGiven" to consentGiven))
        return household
    }

    /** No-op (returns null) if the household hasn't given consent — invariant 7. */
    fun addMember(householdId: String, name: String, age: Int, relation: String): Member? {
        val household = _households.value.firstOrNull { it.id == householdId } ?: return null
        if (!household.consentGiven) {
            events.record(Category.HOUSEHOLDS, "Member add refused: no consent", emptyMap(), EventLog.Level.WARN)
            return null
        }
        val member = Member(idGen.next().toString(), householdId, name, age, relation, clock.now())
        _members.value = listOf(member) + _members.value
        events.record(Category.HOUSEHOLDS, "Member added", mapOf("relation" to relation))
        return member
    }

    fun membersOf(householdId: String): List<Member> = _members.value.filter { it.householdId == householdId }

    /**
     * Records an ASHA home visit (M3: "Due list and visit planner").
     * Enforces consent gate: personal health data cannot be recorded without consent.
     */
    fun recordVisit(
        householdId: String,
        memberId: String?,
        memberName: String?,
        type: VisitType,
        notes: String,
        highRisk: Boolean = false,
        incentiveRupees: Int = type.defaultIncentiveRupees,
        date: String = "Today",
        dueItemId: String? = null,
    ): Visit? {
        val household = _households.value.firstOrNull { it.id == householdId } ?: return null
        if (!household.consentGiven) {
            events.record(Category.HOUSEHOLDS, "Visit record refused: no consent", emptyMap(), EventLog.Level.WARN)
            return null
        }

        var visit = Visit(
            id = idGen.next().toString(),
            householdId = householdId,
            memberId = memberId,
            memberName = memberName ?: household.headOfHousehold,
            type = type,
            notes = notes,
            date = date,
            highRisk = highRisk,
            incentiveRupees = incentiveRupees,
            hlc = clock.now(),
        )

        _visits.value = listOf(visit) + _visits.value

        if (dueItemId != null) {
            _dueItems.value = _dueItems.value.map { item ->
                if (item.id == dueItemId) item.copy(completed = true) else item
            }
        }

        events.record(
            Category.HOUSEHOLDS,
            "Visit recorded",
            mapOf("type" to type.name, "highRisk" to highRisk, "incentive" to incentiveRupees),
        )

        // Asynchronously embed note text for on-device semantic search
        embedders?.let { provider ->
            scope.launch {
                val ready = provider.get() ?: return@launch
                runCatching {
                    val vec = ready.embedder.embedPassages(listOf(notes)).firstOrNull()
                    if (vec != null) {
                        _visits.value = _visits.value.map { v ->
                            if (v.id == visit.id) v.copy(embedding = vec) else v
                        }
                    }
                }
            }
        }

        return visit
    }

    /**
     * Searches recorded visits by semantic meaning (M3: "Visit notes searchable by meaning").
     * Uses vector similarity if the embedder is ready, with fallback to term matching.
     */
    suspend fun searchVisits(query: String): List<VisitSearchResult> {
        val allVisits = _visits.value
        if (query.isBlank()) {
            return allVisits.map { v ->
                val hh = _households.value.firstOrNull { it.id == v.householdId }
                VisitSearchResult(v, hh?.headOfHousehold ?: "Household", hh?.village ?: "", 1.0f)
            }
        }

        val ready = embedders?.get()
        val queryVec = if (ready != null) {
            runCatching { ready.embedder.embedQuery(query) }.getOrNull()
        } else null

        val tokens = query.lowercase().split("""\s+""".toRegex()).filter { it.length > 2 }

        return allVisits.mapNotNull { v ->
            val hh = _households.value.firstOrNull { it.id == v.householdId }
            val hhName = hh?.headOfHousehold ?: "Household"
            val village = hh?.village ?: ""

            var score = 0f
            if (queryVec != null && v.embedding != null) {
                score = cosine(queryVec, v.embedding)
            } else {
                // Keyword / term overlap fallback
                val noteLower = v.notes.lowercase()
                val matched = tokens.count { noteLower.contains(it) }
                if (matched > 0) {
                    score = matched.toFloat() / tokens.size
                }
            }

            // Keyword boost for type / name / village
            if (v.notes.contains(query, ignoreCase = true) ||
                (v.memberName != null && v.memberName.contains(query, ignoreCase = true)) ||
                v.type.label.contains(query, ignoreCase = true)
            ) {
                score = maxOf(score, 0.75f)
            }

            if (score > 0.15f) {
                VisitSearchResult(v, hhName, village, score)
            } else null
        }.sortedByDescending { it.score }
    }

    /**
     * Computes monthly report and incentive tracker summary from recorded visits
     * (M3: "Monthly report and incentive tracker filled from visits").
     */
    fun monthlyReport(): MonthlyIncentiveReport {
        val allVisits = _visits.value
        val anc = allVisits.count { it.type == VisitType.ANC }
        val pnc = allVisits.count { it.type == VisitType.PNC }
        val imm = allVisits.count { it.type == VisitType.IMMUNIZATION }
        val fp = allVisits.count { it.type == VisitType.FAMILY_PLANNING }
        val highRisk = allVisits.count { it.highRisk }
        val totalRupees = allVisits.sumOf { it.incentiveRupees }

        return MonthlyIncentiveReport(
            totalIncentiveRupees = totalRupees,
            totalVisits = allVisits.size,
            ancCount = anc,
            pncCount = pnc,
            immunizationCount = imm,
            familyPlanningCount = fp,
            highRiskCount = highRisk,
            visits = allVisits,
        )
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
