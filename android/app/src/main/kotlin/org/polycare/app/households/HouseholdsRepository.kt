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
 * the encrypted replacement has been atomically written. This file is a persistence bridge, not
 * the op-log architecture invariant 1 describes; the op-log is still future work.
 * Invariant 7: personal health records never leave the phone. Nothing here is ever placed in an
 * outbox — this file is never touched by anything sync-related.
 */
@Singleton
class HouseholdsRepository internal constructor(
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
    private val embedders: EmbedderProvider?,
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") forTestingOnly: Boolean,
) {
    @Inject
    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
        embedders: EmbedderProvider,
        @ApplicationContext context: Context,
    ) : this(clock, idGen, events, embedders, context, false)

    /** Test-only: no context means no persistence, so tests stay fast and hermetic. */
    constructor(
        clock: HlcClock,
        idGen: UuidV7,
        events: EventLog,
    ) : this(clock, idGen, events, null, null, true)

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
                // Never seed over data that could not be decrypted or parsed.
                storageHealthy = false
                _storageWarning.value = "Saved household records could not be opened. Changes will not be saved on this device."
            } else {
                seedInitialDataIfEmpty()
            }
        }
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
        date: String = "Today",
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
