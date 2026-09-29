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
import org.polycare.app.sync.OpLogStore
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Persisted as authenticated AES-GCM ciphertext in app-private no-backup storage. The key is
 * non-exportable and held by Android Keystore. A legacy plaintext file is migrated only after
 * the encrypted replacement has been atomically written. Every mutation is also appended to the
 * op-log first (invariant 1); this file is still the source the views load from at startup.
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
    private val storeFile: File? = context?.let { File(it.noBackupFilesDir, "households_store.enc") }
    private val legacyStoreFile: File? = context?.let { File(it.filesDir, "households_store.json") }
    private val storeCipher = if (context != null) HouseholdStoreCipher() else null
    @Volatile private var storageHealthy = true

    private val _households = MutableStateFlow<List<Household>>(emptyList())
    val households: StateFlow<List<Household>> = _households.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _visits = MutableStateFlow<List<Visit>>(emptyList())
    val visits: StateFlow<List<Visit>> = _visits.asStateFlow()

    private val _dueItems = MutableStateFlow<List<DueItem>>(emptyList())
    val dueItems: StateFlow<List<DueItem>> = _dueItems.asStateFlow()

    private val _storageWarning = MutableStateFlow<String?>(null)
    val storageWarning: StateFlow<String?> = _storageWarning.asStateFlow()

    init {
        if (!loadFromDisk()) {
            val hasExistingStore = storeFile?.exists() == true || legacyStoreFile?.exists() == true
            if (hasExistingStore) {
                // Never write over data that could not be decrypted or parsed. The Households screen
                // offers "Rebuild from the change log" ([recoverFromOpLog]) as an explicit choice.
                storageHealthy = false
                _storageWarning.value = "Saved household records could not be opened. Changes will not be saved on this device."
            }
        }
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
        /** If set, a follow-up due item is scheduled this many days from [date]'s today. */
        followUpInDays: Int? = null,
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
                "householdId" to householdId, "memberId" to (memberId ?: ""), "memberName" to visit.memberName.orEmpty(),
                "type" to type.name, "notes" to notes, "date" to date, "highRisk" to highRisk.toString(),
                "incentive" to incentiveRupees.toString(),
            ),
        )
        _visits.value = listOf(visit) + _visits.value

        if (dueItemId != null) {
            _dueItems.value = _dueItems.value.map { item ->
                if (item.id == dueItemId) item.copy(completed = true).also { done ->
                    opLog?.append(OpEntity.DUE_ITEM, Op.UPSERT, done.id, dueOpPayload(done))
                } else item
            }
        }
        if (followUpInDays != null) {
            scheduleFollowUp(householdId, memberId, visit.memberName ?: household.headOfHousehold, type, followUpInDays, highRisk, persistNow = false)
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

    // ---------------------------------------------------------------------------------------
    // Follow-ups, editing, deletion, consent withdrawal, recovery

    private fun dueOpPayload(d: DueItem): Map<String, String> = mapOf(
        "householdId" to d.householdId, "householdHead" to d.householdHead, "memberId" to (d.memberId ?: ""),
        "memberName" to d.memberName, "village" to d.village, "visitType" to d.visitType.name,
        "dueDate" to d.dueDate, "priority" to d.priority.name, "reason" to d.reason, "completed" to d.completed.toString(),
    )

    /**
     * Schedules a follow-up visit [inDays] from today (0 = today). `dueDate` is a real ISO date; the
     * Due list derives "Overdue / Today / Upcoming" from it every time it is shown. Consent-gated.
     */
    fun scheduleFollowUp(
        householdId: String,
        memberId: String?,
        memberName: String,
        type: VisitType,
        inDays: Int,
        highRisk: Boolean = false,
        reason: String? = null,
        persistNow: Boolean = true,
    ): DueItem? {
        val household = _households.value.firstOrNull { it.id == householdId } ?: return null
        if (!household.consentGiven) return null
        val date = java.time.LocalDate.now().plusDays(inDays.toLong().coerceAtLeast(0)).toString()
        val item = DueItem(
            id = idGen.next().toString(), householdId = householdId, householdHead = household.headOfHousehold,
            memberId = memberId, memberName = memberName, village = household.village, visitType = type,
            dueDate = date,
            priority = when { highRisk -> DuePriority.HIGH; inDays <= 0 -> DuePriority.TODAY; else -> DuePriority.UPCOMING },
            reason = reason ?: "Follow-up: ${type.label}",
        )
        opLog?.append(OpEntity.DUE_ITEM, Op.UPSERT, item.id, dueOpPayload(item))
        _dueItems.value = listOf(item) + _dueItems.value
        events.record(Category.HOUSEHOLDS, "Follow-up scheduled", mapOf("type" to type.name, "inDays" to inDays))
        if (persistNow) persist()
        return item
    }

    /** Edits a household's head and village (op-first). Consent is changed only by [withdrawConsent]. */
    fun updateHousehold(householdId: String, head: String, village: String): Boolean {
        val h = _households.value.firstOrNull { it.id == householdId } ?: return false
        if (head.isBlank() || village.isBlank()) return false
        if (h.headOfHousehold != head.trim()) setHouseholdField(householdId, "headOfHousehold", head.trim())
        if (h.village != village.trim()) setHouseholdField(householdId, "village", village.trim())
        // Keep denormalised copies on due items in step.
        _dueItems.value = _dueItems.value.map {
            if (it.householdId == householdId) it.copy(householdHead = head.trim(), village = village.trim()) else it
        }
        persist()
        return true
    }

    /** Edits a member (op-first). Returns false for a blank name or unknown member. */
    fun updateMember(memberId: String, name: String, age: Int, relation: String): Boolean {
        val m = _members.value.firstOrNull { it.id == memberId } ?: return false
        if (name.isBlank()) return false
        if (m.name != name.trim()) setMemberField(memberId, "name", name.trim())
        if (m.age != age) setMemberField(memberId, "age", age.toString())
        if (m.relation != relation.trim()) setMemberField(memberId, "relation", relation.trim().ifBlank { "Member" })
        return true
    }

    /**
     * Erases a member and everything recorded about them (visits, due items). Each removal is a
     * delete op, so a rebuild from the change log agrees with what the ASHA sees.
     */
    fun deleteMember(memberId: String): Boolean {
        val m = _members.value.firstOrNull { it.id == memberId } ?: return false
        _visits.value.filter { it.memberId == memberId }.forEach { opLog?.append(OpEntity.VISIT, Op.DELETE, it.id, emptyMap()) }
        _dueItems.value.filter { it.memberId == memberId }.forEach { opLog?.append(OpEntity.DUE_ITEM, Op.DELETE, it.id, emptyMap()) }
        opLog?.append(OpEntity.MEMBER, Op.DELETE, m.id, mapOf("householdId" to m.householdId))
        _visits.value = _visits.value.filterNot { it.memberId == memberId }
        _dueItems.value = _dueItems.value.filterNot { it.memberId == memberId }
        _members.value = _members.value.filterNot { it.id == memberId }
        events.record(Category.HOUSEHOLDS, "Member deleted", emptyMap())
        persist()
        return true
    }

    /** Erases a household and all its members, visits and due items. */
    fun deleteHousehold(householdId: String): Boolean {
        val h = _households.value.firstOrNull { it.id == householdId } ?: return false
        eraseRecordsOf(householdId)
        opLog?.append(OpEntity.HOUSEHOLD, Op.DELETE, h.id, emptyMap())
        _households.value = _households.value.filterNot { it.id == householdId }
        events.record(Category.HOUSEHOLDS, "Household deleted", emptyMap())
        persist()
        return true
    }

    /**
     * The family withdrew consent: everything recorded about them is erased and no further member or
     * visit can be added. The household entry itself stays (marked without consent) so the ASHA can
     * see who declined and does not ask again by mistake.
     */
    fun withdrawConsent(householdId: String): Boolean {
        val h = _households.value.firstOrNull { it.id == householdId } ?: return false
        eraseRecordsOf(householdId)
        opLog?.append(OpEntity.HOUSEHOLD, Op.UPSERT, h.id, mapOf("field" to "consent", "value" to "false"))
        _households.value = _households.value.map { if (it.id == householdId) it.copy(consentGiven = false, hlc = clock.now()) else it }
        events.record(Category.HOUSEHOLDS, "Consent withdrawn", emptyMap(), EventLog.Level.WARN)
        persist()
        return true
    }

    private fun eraseRecordsOf(householdId: String) {
        _members.value.filter { it.householdId == householdId }.forEach { opLog?.append(OpEntity.MEMBER, Op.DELETE, it.id, mapOf("householdId" to householdId)) }
        _visits.value.filter { it.householdId == householdId }.forEach { opLog?.append(OpEntity.VISIT, Op.DELETE, it.id, emptyMap()) }
        _dueItems.value.filter { it.householdId == householdId }.forEach { opLog?.append(OpEntity.DUE_ITEM, Op.DELETE, it.id, emptyMap()) }
        _members.value = _members.value.filterNot { it.householdId == householdId }
        _visits.value = _visits.value.filterNot { it.householdId == householdId }
        _dueItems.value = _dueItems.value.filterNot { it.householdId == householdId }
    }

    /**
     * Explicit recovery when the encrypted store cannot be opened: rebuilds households, members,
     * visits and due items by replaying the op-log (creations, field patches, deletions), then
     * makes storage writable again. Records that were created before the change log existed are not
     * in it and cannot be recovered. Returns the number of households rebuilt.
     */
    fun recoverFromOpLog(): Int {
        val log = opLog ?: return 0
        val hh = LinkedHashMap<String, Household>()
        val mm = LinkedHashMap<String, Member>()
        val vv = LinkedHashMap<String, Visit>()
        val dd = LinkedHashMap<String, DueItem>()
        for (stored in log.all()) {
            val op = stored.op
            val p = op.payload
            when (op.entity) {
                OpEntity.HOUSEHOLD -> if (op.action == Op.DELETE) hh.remove(op.entityId) else {
                    val cur = hh[op.entityId]
                    if (p["field"] != null) {
                        if (cur != null) hh[op.entityId] = when (p["field"]) {
                            "headOfHousehold" -> cur.copy(headOfHousehold = p["value"].orEmpty(), hlc = op.hlc)
                            "village" -> cur.copy(village = p["value"].orEmpty(), hlc = op.hlc)
                            "consent" -> cur.copy(consentGiven = p["value"] == "true", hlc = op.hlc)
                            else -> cur
                        }
                    } else hh[op.entityId] = Household(op.entityId, p["headOfHousehold"].orEmpty(), p["village"].orEmpty(), p["consent"] == "true", op.hlc)
                }
                OpEntity.MEMBER -> if (op.action == Op.DELETE) mm.remove(op.entityId) else {
                    val cur = mm[op.entityId]
                    if (p["field"] != null) {
                        if (cur != null) mm[op.entityId] = when (p["field"]) {
                            "name" -> cur.copy(name = p["value"].orEmpty(), hlc = op.hlc)
                            "age" -> cur.copy(age = p["value"]?.toIntOrNull() ?: cur.age, hlc = op.hlc)
                            "relation" -> cur.copy(relation = p["value"].orEmpty(), hlc = op.hlc)
                            else -> cur
                        }
                    } else mm[op.entityId] = Member(op.entityId, p["householdId"].orEmpty(), p["name"].orEmpty(), p["age"]?.toIntOrNull() ?: 0, p["relation"].orEmpty(), op.hlc)
                }
                OpEntity.VISIT -> if (op.action == Op.DELETE) vv.remove(op.entityId) else {
                    val type = runCatching { VisitType.valueOf(p["type"].orEmpty()) }.getOrNull() ?: continue
                    vv[op.entityId] = Visit(
                        id = op.entityId, householdId = p["householdId"].orEmpty(),
                        memberId = p["memberId"]?.ifBlank { null }, memberName = p["memberName"]?.ifBlank { null },
                        type = type, notes = p["notes"].orEmpty(), date = p["date"].orEmpty(),
                        highRisk = p["highRisk"] == "true", incentiveRupees = p["incentive"]?.toIntOrNull() ?: type.defaultIncentiveRupees,
                        hlc = op.hlc,
                    )
                }
                OpEntity.DUE_ITEM -> if (op.action == Op.DELETE) dd.remove(op.entityId) else {
                    val type = runCatching { VisitType.valueOf(p["visitType"].orEmpty()) }.getOrNull() ?: continue
                    dd[op.entityId] = DueItem(
                        id = op.entityId, householdId = p["householdId"].orEmpty(), householdHead = p["householdHead"].orEmpty(),
                        memberId = p["memberId"]?.ifBlank { null }, memberName = p["memberName"].orEmpty(), village = p["village"].orEmpty(),
                        visitType = type, dueDate = p["dueDate"].orEmpty(),
                        priority = runCatching { DuePriority.valueOf(p["priority"].orEmpty()) }.getOrDefault(DuePriority.UPCOMING),
                        reason = p["reason"].orEmpty(), completed = p["completed"] == "true",
                    )
                }
                else -> Unit
            }
        }
        _households.value = hh.values.reversed()
        _members.value = mm.values.filter { it.householdId in hh }.reversed()
        _visits.value = vv.values.filter { it.householdId in hh }.reversed()
        _dueItems.value = dd.values.filter { it.householdId in hh }.reversed()
        storageHealthy = true
        _storageWarning.value = null
        events.record(Category.HOUSEHOLDS, "Rebuilt from change log", mapOf("households" to hh.size, "members" to mm.size, "visits" to vv.size))
        persist()
        return hh.size
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
        if (!storageHealthy) return
        val snapshot = JSONObject().apply {
            put("households", JSONArray(_households.value.map { it.toJson() }))
            put("members", JSONArray(_members.value.map { it.toJson() }))
            put("visits", JSONArray(_visits.value.map { it.toJson() }))
            put("dueItems", JSONArray(_dueItems.value.map { it.toJson() }))
        }
        scope.launch(writeDispatcher) {
            runCatching {
                val encrypted = requireNotNull(storeCipher).encrypt(snapshot.toString().toByteArray(Charsets.UTF_8))
                val tmp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(tmp).use { stream ->
                    stream.write(encrypted)
                    stream.fd.sync()
                }
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                // Remove the old plaintext copy only after the encrypted write is durable.
                if (legacyStoreFile?.exists() == true && !legacyStoreFile.delete()) {
                    _storageWarning.value = "Records are encrypted, but an older plaintext copy could not be removed."
                } else {
                    _storageWarning.value = null
                }
            }
                .onFailure {
                    _storageWarning.value = "Household changes could not be saved securely on this device."
                    events.record(Category.HOUSEHOLDS, "Failed to persist households store", mapOf("error" to it.javaClass.simpleName), EventLog.Level.ERROR)
                }
        }
    }

    /** Returns true if a persisted store was found and loaded (skips demo-data seeding). */
    private fun loadFromDisk(): Boolean {
        val encryptedFile = storeFile ?: return false
        val legacyFile = legacyStoreFile
        if (!encryptedFile.exists() && legacyFile?.exists() != true) return false
        return runCatching {
            val plaintext = if (encryptedFile.exists()) {
                requireNotNull(storeCipher).decrypt(encryptedFile.readBytes())
            } else {
                requireNotNull(legacyFile).readBytes()
            }
            val root = JSONObject(String(plaintext, Charsets.UTF_8))
            _households.value = root.getJSONArray("households").toObjectList(::householdFromJson)
            _members.value = root.getJSONArray("members").toObjectList(::memberFromJson)
            _visits.value = root.getJSONArray("visits").toObjectList(::visitFromJson)
            _dueItems.value = root.getJSONArray("dueItems").toObjectList(::dueItemFromJson)
            if (!encryptedFile.exists()) {
                persist()
            } else if (legacyFile?.exists() == true && !legacyFile.delete()) {
                _storageWarning.value = "Records are encrypted, but an older plaintext copy could not be removed."
            }
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
