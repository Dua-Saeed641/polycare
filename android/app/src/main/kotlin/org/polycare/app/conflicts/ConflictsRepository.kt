package org.polycare.app.conflicts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.households.HouseholdsRepository
import org.polycare.app.security.SecureBox
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.conflict.ConflictDetector
import org.polycare.common.conflict.FieldVersion
import org.polycare.common.conflict.Resolution
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ConflictRecord(
    val id: String,
    /** "household" or "member". */
    val kind: String,
    val entityId: String,
    /** Human label of the record, e.g. the member's name. */
    val subject: String,
    val field: String,
    val fieldLabel: String,
    val localValue: String,
    val localAuthor: String,
    val incomingValue: String,
    val incomingAuthor: String,
    val numeric: Boolean,
    val resolution: Resolution? = null,
    /** The local value just before a resolution changed it, so [ConflictsRepository.reopen] can restore it. */
    val restoreValue: String? = null,
) {
    val open: Boolean get() = resolution == null
}

data class ImportSummary(val added: Int, val unchanged: Int, val conflicts: Int, val refusedNoConsent: Int, val invalid: Boolean = false)

/**
 * Conflict Inbox: when two workers recorded different details for the same family, neither
 * silently wins. Each disagreement becomes a [ConflictRecord] holding *both* values, shown side
 * by side, resolved by the ASHA — and every resolution can be undone.
 *
 * The other worker's records arrive through an explicit file the ASHA chooses to import (and
 * that the other ASHA chose to export, only for households that gave consent). Nothing here is
 * automatic sync: household data still never enters the outbox (invariant 7).
 */
@Singleton
class ConflictsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val households: HouseholdsRepository,
    private val clock: HlcClock,
    private val events: EventLog,
) {
    private val file = File(context.filesDir, "conflicts.json")

    private val _conflicts = MutableStateFlow(load())
    val conflicts: StateFlow<List<ConflictRecord>> = _conflicts.asStateFlow()

    /** Serialises this phone's consented households and their members for a teammate. */
    fun exportJson(): String {
        val node = clock.now().node
        val hh = JSONArray()
        for (h in households.households.value.filter { it.consentGiven }) {
            val members = JSONArray()
            households.membersOf(h.id).forEach { m ->
                members.put(
                    JSONObject().put("id", m.id).put("name", m.name).put("age", m.age).put("relation", m.relation).put("hlc", hlcJson(m.hlc)),
                )
            }
            hh.put(
                JSONObject().put("id", h.id).put("head", h.headOfHousehold).put("village", h.village)
                    .put("consent", true).put("hlc", hlcJson(h.hlc)).put("members", members),
            )
        }
        events.record(Category.CONFLICTS, "Households exported", mapOf("households" to hh.length()))
        return JSONObject().put("format", FORMAT).put("v", 1).put("author", node).put("households", hh).toString()
    }

    /** Merges a teammate's export: new records are added, differing fields become conflicts. */
    fun importJson(text: String): ImportSummary {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT) return ImportSummary(0, 0, 0, 0, invalid = true)
        val author = root.optString("author", "teammate")
        var added = 0
        var unchanged = 0
        var newConflicts = 0
        var refused = 0
        val created = ArrayList<ConflictRecord>()

        val arr = root.optJSONArray("households") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val ih = arr.getJSONObject(i)
            if (!ih.optBoolean("consent", false)) { refused++; continue }
            val head = ih.optString("head")
            val village = ih.optString("village")
            val localH = households.households.value.firstOrNull { it.id == ih.optString("id") }
                ?: households.households.value.firstOrNull { same(it.headOfHousehold, head) && same(it.village, village) }

            val hhId: String
            if (localH == null) {
                hhId = households.addHousehold(head, village, consentGiven = true).id
                added++
            } else {
                hhId = localH.id
                val incoming = mapOf("headOfHousehold" to fv(head, ih, author), "village" to fv(village, ih, author))
                val local = mapOf(
                    "headOfHousehold" to FieldVersion(localH.headOfHousehold, localH.hlc, localH.hlc.node),
                    "village" to FieldVersion(localH.village, localH.hlc, localH.hlc.node),
                )
                val found = ConflictDetector.detect(local, incoming)
                if (found.isEmpty()) unchanged++
                found.forEach { c ->
                    if (!alreadyOpen("household", hhId, c.field, c.incoming.value)) {
                        created += ConflictRecord(
                            id = newId(), kind = "household", entityId = hhId, subject = "Household of ${localH.headOfHousehold}",
                            field = c.field, fieldLabel = if (c.field == "village") "Village" else "Head of household",
                            localValue = c.local.value, localAuthor = c.local.author,
                            incomingValue = c.incoming.value, incomingAuthor = author, numeric = false,
                        )
                    }
                }
            }

            val members = ih.optJSONArray("members") ?: JSONArray()
            for (j in 0 until members.length()) {
                val im = members.getJSONObject(j)
                val name = im.optString("name")
                val localM = households.membersOf(hhId).firstOrNull { it.id == im.optString("id") }
                    ?: households.membersOf(hhId).firstOrNull { same(it.name, name) }
                if (localM == null) {
                    households.addMember(hhId, name, im.optInt("age"), im.optString("relation"))
                    added++
                    continue
                }
                val incoming = mapOf(
                    "name" to fv(name, im, author),
                    "age" to fv(im.optInt("age").toString(), im, author),
                    "relation" to fv(im.optString("relation"), im, author),
                )
                val local = listOf("name", "age", "relation").associateWith {
                    FieldVersion(households.memberFieldValue(localM, it).orEmpty(), localM.hlc, localM.hlc.node)
                }
                val found = ConflictDetector.detect(local, incoming)
                if (found.isEmpty()) unchanged++
                found.forEach { c ->
                    if (!alreadyOpen("member", localM.id, c.field, c.incoming.value)) {
                        created += ConflictRecord(
                            id = newId(), kind = "member", entityId = localM.id, subject = localM.name,
                            field = c.field, fieldLabel = when (c.field) { "age" -> "Age"; "relation" -> "Relation"; else -> "Name" },
                            localValue = c.local.value, localAuthor = c.local.author,
                            incomingValue = c.incoming.value, incomingAuthor = author, numeric = c.field == "age",
                        )
                    }
                }
            }
        }
        newConflicts = created.size
        if (created.isNotEmpty()) {
            _conflicts.value = created + _conflicts.value
            save()
        }
        events.record(
            Category.CONFLICTS, "Import merged",
            mapOf("added" to added, "unchanged" to unchanged, "conflicts" to newConflicts, "refused" to refused),
        )
        return ImportSummary(added, unchanged, newConflicts, refused)
    }

    fun resolve(id: String, choice: Resolution) {
        val c = _conflicts.value.firstOrNull { it.id == id && it.open } ?: return
        var restore: String? = null
        when (choice) {
            Resolution.KEEP_LOCAL -> Unit
            Resolution.KEEP_INCOMING -> { restore = current(c); apply(c, c.incomingValue) }
            Resolution.KEEP_BOTH -> if (!c.numeric) { restore = current(c); apply(c, "${c.localValue} / ${c.incomingValue}") }
        }
        replace(c.copy(resolution = choice, restoreValue = restore))
        events.record(Category.CONFLICTS, "Conflict resolved", mapOf("choice" to choice.name, "field" to c.field))
    }

    /** Undoes a resolution: restores the local value it changed and puts the conflict back in the inbox. */
    fun reopen(id: String) {
        val c = _conflicts.value.firstOrNull { it.id == id && !it.open } ?: return
        c.restoreValue?.let { apply(c, it) }
        replace(c.copy(resolution = null, restoreValue = null))
        events.record(Category.CONFLICTS, "Conflict reopened", mapOf("field" to c.field))
    }

    private fun current(c: ConflictRecord): String? = when (c.kind) {
        "member" -> households.members.value.firstOrNull { it.id == c.entityId }?.let { households.memberFieldValue(it, c.field) }
        else -> households.households.value.firstOrNull { it.id == c.entityId }?.let { households.householdFieldValue(it, c.field) }
    }

    private fun apply(c: ConflictRecord, value: String) {
        if (c.kind == "member") households.setMemberField(c.entityId, c.field, value)
        else households.setHouseholdField(c.entityId, c.field, value)
    }

    private fun replace(updated: ConflictRecord) {
        _conflicts.value = _conflicts.value.map { if (it.id == updated.id) updated else it }
        save()
    }

    private fun alreadyOpen(kind: String, entityId: String, field: String, incoming: String) =
        _conflicts.value.any { it.open && it.kind == kind && it.entityId == entityId && it.field == field && it.incomingValue == incoming }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    private fun newId() = "cf-" + System.nanoTime().toString(36)

    private fun fv(value: String, o: JSONObject, author: String): FieldVersion {
        val h = o.optJSONObject("hlc")
        val hlc = if (h != null) Hlc(h.optLong("w"), h.optInt("l"), h.optString("n", author)) else Hlc(0, 0, author)
        return FieldVersion(value, hlc, author)
    }

    private fun hlcJson(h: Hlc) = JSONObject().put("w", h.wallMs).put("l", h.logical).put("n", h.node)

    // Conflicts hold names and ages, so they are sealed like every other personal record.
    private fun save() = runCatching {
        val arr = JSONArray()
        _conflicts.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("kind", it.kind).put("eid", it.entityId).put("subject", it.subject)
                    .put("field", it.field).put("label", it.fieldLabel).put("lv", it.localValue).put("la", it.localAuthor)
                    .put("iv", it.incomingValue).put("ia", it.incomingAuthor).put("num", it.numeric)
                    .put("res", it.resolution?.name ?: JSONObject.NULL).put("restore", it.restoreValue ?: JSONObject.NULL),
            )
        }
        file.writeText(SecureBox.seal(arr.toString()))
    }

    private fun load(): List<ConflictRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(SecureBox.open(file.readText()) ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ConflictRecord(
                id = o.getString("id"), kind = o.getString("kind"), entityId = o.getString("eid"), subject = o.getString("subject"),
                field = o.getString("field"), fieldLabel = o.getString("label"), localValue = o.getString("lv"),
                localAuthor = o.optString("la"), incomingValue = o.getString("iv"), incomingAuthor = o.optString("ia"),
                numeric = o.optBoolean("num"),
                resolution = o.optString("res").takeIf { it.isNotBlank() && it != "null" }?.let { Resolution.valueOf(it) },
                restoreValue = o.optString("restore").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val FORMAT = "polycare-households"
    }
}
