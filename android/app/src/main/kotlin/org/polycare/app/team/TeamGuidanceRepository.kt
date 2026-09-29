package org.polycare.app.team

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** An answer a supervisor wrote in the cloud for a question some ASHA could not get answered offline. */
data class TeamAnswer(
    val id: String,
    val question: String,
    val answer: String,
    val author: String,
    val answeredAtMs: Long,
)

/**
 * A card a supervisor sent to the villages an outbreak alert named (ARCHITECTURE.md §6.5: "guidance
 * card pushed to phones in the affected villages"). [villages] empty means everywhere.
 */
data class GuidanceCard(
    val id: String,
    val title: String,
    val body: String,
    val villages: List<String>,
    val author: String,
    val sentAtMs: Long,
    val dismissed: Boolean = false,
)

/**
 * The return leg of "offline gaps -> cloud answers", plus guidance cards: supervisors answer
 * questions and send cards on the dashboard, and they arrive on the next sync. Ask consults answers
 * before falling back to a low-confidence protocol passage. They are team guidance, clearly
 * labelled as a supervisor's, never presented as an official protocol source.
 */
@Singleton
class TeamGuidanceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val events: EventLog,
) {
    private val file = File(context.filesDir, "team_guidance.json")
    private val cardsFile = File(context.filesDir, "guidance_cards.json")

    private val _answers = MutableStateFlow(load())
    val answers: StateFlow<List<TeamAnswer>> = _answers.asStateFlow()

    private val _cards = MutableStateFlow(loadCards())
    /** Newest first. */
    val cards: StateFlow<List<GuidanceCard>> = _cards.asStateFlow()

    /** Merges pulled answers by id (idempotent: pulling the same answer twice adds nothing). */
    fun merge(incoming: List<TeamAnswer>): Int {
        val known = _answers.value.map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isEmpty()) return 0
        _answers.value = (fresh + _answers.value).sortedByDescending { it.answeredAtMs }
        save()
        events.record(Category.TEAM, "Supervisor answers received", mapOf("count" to fresh.size))
        return fresh.size
    }

    /** Merges pulled guidance cards by id; a card the ASHA already dismissed stays dismissed. */
    fun mergeCards(incoming: List<GuidanceCard>): Int {
        val known = _cards.value.map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isEmpty()) return 0
        _cards.value = (fresh + _cards.value).sortedByDescending { it.sentAtMs }
        saveCards()
        events.record(Category.TEAM, "Guidance cards received", mapOf("count" to fresh.size))
        return fresh.size
    }

    fun dismiss(id: String) {
        _cards.value = _cards.value.map { if (it.id == id) it.copy(dismissed = true) else it }
        saveCards()
    }

    /** Best stored answer for [question] if enough of its words overlap; null otherwise. */
    fun find(question: String, minOverlap: Float = 0.6f): TeamAnswer? {
        val q = terms(question)
        if (q.isEmpty()) return null
        return _answers.value
            .map { it to (q.count { t -> t in terms(it.question) }.toFloat() / q.size) }
            .filter { it.second >= minOverlap }
            .maxByOrNull { it.second }?.first
    }

    private fun terms(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()

    private fun save() = runCatching {
        val arr = JSONArray()
        _answers.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("q", it.question).put("a", it.answer).put("by", it.author).put("t", it.answeredAtMs))
        }
        file.writeText(arr.toString())
    }

    private fun load(): List<TeamAnswer> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TeamAnswer(o.getString("id"), o.getString("q"), o.getString("a"), o.optString("by"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun saveCards() = runCatching {
        val arr = JSONArray()
        _cards.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("villages", JSONArray(it.villages))
                    .put("by", it.author).put("t", it.sentAtMs).put("dismissed", it.dismissed),
            )
        }
        cardsFile.writeText(arr.toString())
    }

    private fun loadCards(): List<GuidanceCard> = runCatching {
        if (!cardsFile.exists()) return emptyList()
        val arr = JSONArray(cardsFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val v = o.optJSONArray("villages") ?: JSONArray()
            GuidanceCard(
                o.getString("id"), o.getString("title"), o.getString("body"), List(v.length()) { v.getString(it) },
                o.optString("by"), o.optLong("t"), o.optBoolean("dismissed"),
            )
        }
    }.getOrDefault(emptyList())
}
