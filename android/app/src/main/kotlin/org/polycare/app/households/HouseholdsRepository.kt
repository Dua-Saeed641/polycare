package org.polycare.app.households

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.security.SecureBox
import org.polycare.app.sync.OpLogStore
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import java.io.File
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
 * Household and daily work repository ("Household and member records with consent capture",
 * "Due list and visit planner", "Visit notes searchable by meaning", "Monthly report and
 * incentive tracker").
 *
 * Every mutation is appended to the encrypted op-log first (invariant 1); the JSON store in
 * app-private storage (`files/households_store.json`, AES-GCM sealed with a Keystore key) is a
 * rebuildable view of it that keeps app start fast.
 * Invariant 7: personal health records never leave the phone. The Sync Gate marks every
 * household, member and visit op keep-local, so none of them is ever sent.
 */
@Singleton
class HouseholdsRepository internal constructor(
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
    private val embedders: EmbedderProvider?,
    private val context: Context?,
    private val opLog: OpLogStore?,
    @Suppress("UNUSED_PARAMETER") forTestingOnly: Boolean,
) {
    @Inject
    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
        embedders: EmbedderProvider,
        @ApplicationContext context: Context,
        opLog: OpLogStore,
    ) : this(clock, idGen, events, embedders, context, opLog, false)

    /** Test-only: no context means no persistence, so tests stay fast and hermetic. */
    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
    ) : this(clock, idGen, events, null, null, null, true)

    private val scope = CoroutineScope(Dispatchers.Default)

    // Every persist() call snapshots current state synchronously, then writes asynchronously —
    // but multiple persist() calls in quick succession (e.g. addHousehold then addMember, as the
    // debug hook does) would otherwise launch concurrent writes to the same file on Default's
    // thread pool with no ordering guarantee: an *earlier* snapshot's write finishing *after* a
    // later one silently loses the later update. limitedParallelism(1) makes writes strictly
    // sequential, in call order, so the last call to persist() always wins on disk.
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val storeFile: File? = context?.let { File(it.filesDir, "households_store.json") }

    private val _households = MutableStateFlow<List<Household>>(emptyList())
    val households: StateFlow<List<Household>> = _households.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _visits = MutableStateFlow<List<Visit>>(emptyList())
    val visits: StateFlow<List<Visit>> = _visits.asStateFlow()

    private val _dueItems = MutableStateFlow<List<DueItem>>(emptyList())
    val dueItems: StateFlow<List<DueItem>> = _dueItems.asStateFlow()

    init {
        if (!loadFromDisk()) seedInitialDataIfEmpty()
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
        persist()
    }

    fun addHousehold(headOfHousehold: String, village: String, consentGiven: Boolean): Household {
        val household = Household(idGen.next().toString(), headOfHousehold, village, consentGiven, clock.now())
        // Invariant 1: the op is appended before the view changes.
        opLog?.append(
            OpEntity.HOUSEHOLD, Op.UPSERT, household.id,
            mapOf("headOfHousehold" to headOfHousehold, "village" to village, "consent" to consentGiven.toString()),
        )
        _households.value = listOf(household) + _households.value
        events.record(Category.HOUSEHOLDS, "Household added", mapOf("consentGiven" to consentGiven))
        persist()
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
        opLog?.append(
            OpEntity.MEMBER, Op.UPSERT, member.id,
            mapOf("householdId" to householdId, "name" to name, "age" to age.toString(), "relation" to relation),
        )
        _members.value = listOf(member) + _members.value
        events.record(Category.HOUSEHOLDS, "Member added", mapOf("relation" to relation))
        persist()
        return member
    }

    fun membersOf(householdId: String): List<Member> = _members.value.filter { it.householdId == householdId }

    /**
     * Records an ASHA home visit ("Due list and visit planner").
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
        date: String = java.time.LocalDate.now().toString(),
        dueItemId: String? = null,
    ): Visit? {
        val household = _households.value.firstOrNull { it.id == householdId } ?: return null
        if (!household.consentGiven) {
            events.record(Category.HOUSEHOLDS, "Visit record refused: no consent", emptyMap(), EventLog.Level.WARN)
            return null
        }

        val visit = Visit(
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

        opLog?.append(
            OpEntity.VISIT, Op.UPSERT, visit.id,
            mapOf(
                "householdId" to householdId, "memberId" to (memberId ?: ""), "type" to type.name,
                "notes" to notes, "date" to date, "highRisk" to highRisk.toString(),
            ),
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
        persist()

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
                        persist()
                    }
                }
            }
        }

        return visit
    }

    /**
     * Searches recorded visits by semantic meaning ("Visit notes searchable by meaning").
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

    /** Current value of an editable member field, for conflict detection. */
    fun memberFieldValue(member: Member, field: String): String? = when (field) {
        "name" -> member.name
        "age" -> member.age.toString()
        "relation" -> member.relation
        else -> null
    }

    /** Sets one member field (op-first). Returns false if the member or field is unknown. */
    fun setMemberField(memberId: String, field: String, value: String): Boolean {
        val m = _members.value.firstOrNull { it.id == memberId } ?: return false
        val updated = when (field) {
            "name" -> m.copy(name = value)
            "age" -> m.copy(age = value.trim().toIntOrNull() ?: return false)
            "relation" -> m.copy(relation = value)
            else -> return false
        }.copy(hlc = clock.now())
        opLog?.append(OpEntity.MEMBER, Op.UPSERT, m.id, mapOf("householdId" to m.householdId, "field" to field, "value" to value))
        _members.value = _members.value.map { if (it.id == memberId) updated else it }
        events.record(Category.HOUSEHOLDS, "Member field updated", mapOf("field" to field))
        persist()
        return true
    }

    fun householdFieldValue(h: Household, field: String): String? = when (field) {
        "headOfHousehold" -> h.headOfHousehold
        "village" -> h.village
        else -> null
    }

    fun setHouseholdField(householdId: String, field: String, value: String): Boolean {
        val h = _households.value.firstOrNull { it.id == householdId } ?: return false
        val updated = when (field) {
            "headOfHousehold" -> h.copy(headOfHousehold = value)
            "village" -> h.copy(village = value)
            else -> return false
        }.copy(hlc = clock.now())
        opLog?.append(OpEntity.HOUSEHOLD, Op.UPSERT, h.id, mapOf("field" to field, "value" to value))
        _households.value = _households.value.map { if (it.id == householdId) updated else it }
        events.record(Category.HOUSEHOLDS, "Household field updated", mapOf("field" to field))
        persist()
        return true
    }

    /** Distinct villages of this ASHA's own households, for choosing where a signal is reported. */
    fun villages(): List<String> = _households.value.map { it.village.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /**
     * Computes monthly report and incentive tracker summary from recorded visits
     * ("Monthly report and incentive tracker filled from visits").
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

    // --- Persistence: plain JSON, app-private storage, never synced. See class doc comment. ---

    private fun persist() {
        val file = storeFile ?: return
        val snapshot = JSONObject().apply {
            put("households", JSONArray(_households.value.map { it.toJson() }))
            put("members", JSONArray(_members.value.map { it.toJson() }))
            put("visits", JSONArray(_visits.value.map { it.toJson() }))
            put("dueItems", JSONArray(_dueItems.value.map { it.toJson() }))
        }
        scope.launch(writeDispatcher) {
            runCatching {
                // Encrypted at rest (Android Keystore AES-GCM); temp + rename so a kill mid-write
                // leaves the previous complete file, never a truncated one.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(SecureBox.seal(snapshot.toString()))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
                .onFailure { events.record(Category.HOUSEHOLDS, "Failed to persist households store", mapOf("error" to it.javaClass.simpleName), EventLog.Level.ERROR) }
        }
    }

    /** Returns true if a persisted store was found and loaded (skips demo-data seeding). */
    private fun loadFromDisk(): Boolean {
        val file = storeFile ?: return false
        if (!file.exists()) return false
        return runCatching {
            val raw = file.readText()
            // A store written before encryption existed starts with '{'; read it once, then
            // rewrite it sealed below.
            val legacyPlaintext = raw.trimStart().startsWith("{")
            val root = JSONObject(if (legacyPlaintext) raw else (SecureBox.open(raw) ?: error("store failed to decrypt")))
            _households.value = root.getJSONArray("households").toObjectList(::householdFromJson)
            _members.value = root.getJSONArray("members").toObjectList(::memberFromJson)
            _visits.value = root.getJSONArray("visits").toObjectList(::visitFromJson)
            _dueItems.value = root.getJSONArray("dueItems").toObjectList(::dueItemFromJson)
            if (legacyPlaintext) persist()
            true
        }.getOrElse {
            events.record(Category.HOUSEHOLDS, "Failed to load persisted households store", mapOf("error" to it.javaClass.simpleName), EventLog.Level.ERROR)
            false
        }
    }

    private fun Hlc.toJson() = JSONObject().put("wallMs", wallMs).put("logical", logical).put("node", node)
    private fun hlcFromJson(o: JSONObject) = Hlc(o.getLong("wallMs"), o.getInt("logical"), o.getString("node"))

    private fun Household.toJson() = JSONObject()
        .put("id", id).put("headOfHousehold", headOfHousehold).put("village", village)
        .put("consentGiven", consentGiven).put("hlc", hlc.toJson())

    private fun householdFromJson(o: JSONObject) = Household(
        o.getString("id"), o.getString("headOfHousehold"), o.getString("village"),
        o.getBoolean("consentGiven"), hlcFromJson(o.getJSONObject("hlc")),
    )

    private fun Member.toJson() = JSONObject()
        .put("id", id).put("householdId", householdId).put("name", name)
        .put("age", age).put("relation", relation).put("hlc", hlc.toJson())

    private fun memberFromJson(o: JSONObject) = Member(
        o.getString("id"), o.getString("householdId"), o.getString("name"),
        o.getInt("age"), o.getString("relation"), hlcFromJson(o.getJSONObject("hlc")),
    )

    private fun Visit.toJson() = JSONObject()
        .put("id", id).put("householdId", householdId)
        .put("memberId", memberId).put("memberName", memberName)
        .put("type", type.name).put("notes", notes).put("date", date)
        .put("highRisk", highRisk).put("incentiveRupees", incentiveRupees)
        .put("hlc", hlc.toJson())
        .apply { if (embedding != null) put("embedding", JSONArray(embedding.map { it.toDouble() })) }

    private fun visitFromJson(o: JSONObject) = Visit(
        id = o.getString("id"),
        householdId = o.getString("householdId"),
        memberId = o.optString("memberId").ifBlank { null },
        memberName = o.optString("memberName").ifBlank { null },
        type = VisitType.valueOf(o.getString("type")),
        notes = o.getString("notes"),
        date = o.getString("date"),
        highRisk = o.getBoolean("highRisk"),
        incentiveRupees = o.getInt("incentiveRupees"),
        hlc = hlcFromJson(o.getJSONObject("hlc")),
        embedding = o.optJSONArray("embedding")?.let { arr -> FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() } },
    )

    private fun DueItem.toJson() = JSONObject()
        .put("id", id).put("householdId", householdId).put("householdHead", householdHead)
        .put("memberId", memberId).put("memberName", memberName).put("village", village)
        .put("visitType", visitType.name).put("dueDate", dueDate).put("priority", priority.name)
        .put("reason", reason).put("completed", completed)

    private fun dueItemFromJson(o: JSONObject) = DueItem(
        id = o.getString("id"),
        householdId = o.getString("householdId"),
        householdHead = o.getString("householdHead"),
        memberId = o.optString("memberId").ifBlank { null },
        memberName = o.getString("memberName"),
        village = o.getString("village"),
        visitType = VisitType.valueOf(o.getString("visitType")),
        dueDate = o.getString("dueDate"),
        priority = DuePriority.valueOf(o.getString("priority")),
        reason = o.getString("reason"),
        completed = o.getBoolean("completed"),
    )

    private fun <T> JSONArray.toObjectList(from: (JSONObject) -> T): List<T> = List(length()) { i -> from(getJSONObject(i)) }
}
