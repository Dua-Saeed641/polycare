package org.polycare.app.team

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.settings.AppSettings
import org.polycare.app.sync.OpLogStore
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.HlcClock
import org.polycare.common.PolyCareConfig
import org.polycare.common.radar.OutbreakRadar
import org.polycare.common.sync.KnownTip
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import org.polycare.common.sync.SemanticMerkle
import org.polycare.common.sync.SignalCodec
import org.polycare.common.sync.TipDecision
import org.polycare.common.sync.TipGate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class TipStatus { OK, DISPUTED, SUPERSEDED }

/**
 * One piece of team knowledge: a tip an ASHA chose to share (or received from a teammate). [id] is
 * the tip's op id, which is also its id in the cloud, so a vote can name it.
 * [embedding] is null only for a tip whose vector could not be decoded; a tip is never compared
 * with vectors of a different [modelId] (invariant 4).
 */
data class TeamTip(
    val id: String,
    val text: String,
    val embedding: FloatArray?,
    val modelId: String,
    val simhash: Int,
    val village: String,
    val author: String,
    val wallMs: Long,
    val logical: Int,
    val mine: Boolean,
    val votes: Int = 0,
    val iVoted: Boolean = false,
    val status: TipStatus = TipStatus.OK,
) {
    override fun equals(other: Any?): Boolean = other is TeamTip && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

sealed interface ShareResult {
    data class Shared(val tip: TeamTip) : ShareResult
    /** A near-duplicate already existed, so the vote went to it instead. */
    data class Voted(val tip: TeamTip, val alreadyYours: Boolean) : ShareResult
    data object TooShort : ShareResult
    data class HasIdentifier(val what: String) : ShareResult
    data class Unavailable(val reason: String) : ShareResult
}

/**
 * The phone's copy of the team's shared knowledge ("evolving memory"). Tips are team-visible, never
 * personal, so unlike households they may be synced, but only through [TipGate]: a tip that is
 * nearly identical to one already known is turned into a **vote** for it instead of a second copy.
 *
 * Every local change is an op first (invariant 1). Received tips arrive already verified (see
 * [RemoteTipVerifier]) and are applied idempotently by id (invariant 2).
 */
@Singleton
class TeamMemoryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val opLog: OpLogStore,
    private val embedders: EmbedderProvider,
    private val settings: AppSettings,
    private val clock: HlcClock,
    private val events: EventLog,
) {
    private val file = File(context.filesDir, "team_memory.json")
    private val lock = Any()

    private val _tips = MutableStateFlow(load())
    /** Newest first. Superseded tips are kept (so the merkle index matches the cloud) but hidden by the UI. */
    val tips: StateFlow<List<TeamTip>> = _tips.asStateFlow()

    fun get(id: String): TeamTip? = _tips.value.firstOrNull { it.id == id }

    fun knownIds(): Set<String> = _tips.value.map { it.id }.toSet()

    /** One entry per tip, for the semantic Merkle index. */
    fun merkleEntries(): List<SemanticMerkle.Entry> = _tips.value.map { SemanticMerkle.Entry(it.id, it.simhash, it.wallMs, it.logical) }

    /**
     * Shares [text] with the team, unless it says nothing new: then the ASHA's vote goes to the tip
     * it repeats. Refuses text that looks like a phone number, Aadhaar number or e-mail address.
     */
    suspend fun share(text: String): ShareResult {
        val t = text.trim()
        if (t.length < MIN_CHARS) return ShareResult.TooShort
        identifierIn(t)?.let { return ShareResult.HasIdentifier(it) }
        val ready = embedders.get() ?: return ShareResult.Unavailable("The search model is not installed, so tips can't be matched or shared yet.")
        val vec = runCatching { ready.embedder.embedPassages(listOf(t)).first() }.getOrNull()
            ?: return ShareResult.Unavailable("Could not read the tip. Try again.")
        val model = ready.embedder.modelId

        val known = _tips.value.filter { it.modelId == model && it.embedding != null && it.status != TipStatus.SUPERSEDED }
        return when (val d = TipGate.decide(vec, known.map { KnownTip(it.id, it.embedding!!) })) {
            is TipDecision.VoteFor -> {
                val existing = get(d.tipId)!!
                if (existing.mine) ShareResult.Voted(existing, alreadyYours = true)
                else { vote(existing.id); ShareResult.Voted(get(existing.id)!!, alreadyYours = false) }
            }
            is TipDecision.Share -> {
                val village = SignalCodec.villageCode(settings.village.value)
                val stored = opLog.append(
                    OpEntity.TIP, Op.UPSERT, entityId = "tip",
                    payload = mapOf(
                        "text" to t, "dense_f16" to SignalCodec.denseF16Base64(vec), "emb_model_id" to model,
                        "simhash" to SignalCodec.simhash16(vec).toString(), "village_code" to village,
                        "priority" to "%.4f".format(d.priority),
                    ),
                )
                val tip = TeamTip(
                    id = stored.op.opId, text = t, embedding = vec, modelId = model, simhash = SignalCodec.simhash16(vec),
                    village = village, author = clock.node, wallMs = stored.op.hlc.wallMs, logical = stored.op.hlc.logical, mine = true,
                )
                add(tip)
                events.record(Category.TEAM, "Tip shared", mapOf("novelty" to "%.2f".format(d.novelty), "rknn" to d.rknn))
                ShareResult.Shared(tip)
            }
        }
    }

    /** "This tip was useful." One vote per tip, and never for your own. */
    fun vote(tipId: String): Boolean {
        val tip = get(tipId) ?: return false
        if (tip.mine || tip.iVoted) return false
        opLog.append(OpEntity.VOTE, Op.UPSERT, entityId = "vote-$tipId", payload = mapOf("cloud_point_id" to tipId))
        update(tipId) { it.copy(iVoted = true, votes = it.votes + 1) }
        events.record(Category.TEAM, "Voted for a tip", emptyMap())
        return true
    }

    /** Tips (same model, not superseded) closest to [query], for Ask. */
    suspend fun search(query: String, limit: Int = 2): List<TeamTip> {
        if (_tips.value.isEmpty() || query.isBlank()) return emptyList()
        val ready = embedders.get() ?: return emptyList()
        val q = runCatching { ready.embedder.embedQuery(query) }.getOrNull() ?: return emptyList()
        val model = ready.embedder.modelId
        return _tips.value
            .filter { it.modelId == model && it.embedding != null && it.status != TipStatus.SUPERSEDED }
            .map { it to OutbreakRadar.cosine(q, it.embedding!!) }
            .filter { it.second >= MIN_SEARCH_COSINE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Adds a verified tip from the cloud. Returns the existing tips it may contradict: same model,
     * a different author, very similar meaning, different words. Both sides are marked
     * [TipStatus.DISPUTED] until a person resolves them in the Conflict Inbox.
     */
    fun applyRemote(tip: TeamTip): List<TeamTip> {
        synchronized(lock) { if (_tips.value.any { it.id == tip.id }) return emptyList() }
        val candidates = if (tip.embedding == null) emptyList() else _tips.value.filter { other ->
            other.modelId == tip.modelId && other.embedding != null && other.author != tip.author &&
                other.status != TipStatus.SUPERSEDED &&
                normalized(other.text) != normalized(tip.text) &&
                OutbreakRadar.cosine(tip.embedding, other.embedding) >= PolyCareConfig.Conflicts.nearDuplicateCosine
        }
        add(if (candidates.isEmpty()) tip else tip.copy(status = TipStatus.DISPUTED))
        candidates.forEach { c -> setStatus(c.id, TipStatus.DISPUTED) }
        return candidates
    }

    /** Server-side vote counts (distinct devices). Never lower than this phone's own unsent vote. */
    fun setVotes(counts: Map<String, Int>) {
        if (counts.isEmpty()) return
        val next = _tips.value.map { t -> counts[t.id]?.let { t.copy(votes = maxOf(it, if (t.iVoted) 1 else 0)) } ?: t }
        _tips.value = next
        save()
    }

    fun setStatus(id: String, status: TipStatus) = update(id) { it.copy(status = status) }

    // ---------------------------------------------------------------------------------------

    private fun add(tip: TeamTip) {
        _tips.value = (listOf(tip) + _tips.value).sortedByDescending { it.wallMs }
        save()
    }

    private fun update(id: String, f: (TeamTip) -> TeamTip) {
        _tips.value = _tips.value.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun normalized(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun identifierIn(text: String): String? = when {
        PHONE.containsMatchIn(text) -> "a phone number"
        AADHAAR.containsMatchIn(text) -> "an ID number"
        EMAIL.containsMatchIn(text) -> "an e-mail address"
        else -> null
    }

    private fun save() = runCatching {
        val arr = JSONArray()
        _tips.value.forEach { t ->
            arr.put(
                JSONObject().put("id", t.id).put("text", t.text).put("model", t.modelId).put("simhash", t.simhash)
                    .put("village", t.village).put("author", t.author).put("wall", t.wallMs).put("logical", t.logical)
                    .put("mine", t.mine).put("votes", t.votes).put("voted", t.iVoted).put("status", t.status.name)
                    .apply { t.embedding?.takeIf { it.size == SignalCodec.DIM }?.let { put("vec", SignalCodec.denseF16Base64(it)) } },
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun load(): List<TeamTip> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TeamTip(
                id = o.getString("id"), text = o.getString("text"), embedding = o.optString("vec").takeIf { it.isNotBlank() }?.let(SignalCodec::decodeDenseF16),
                modelId = o.getString("model"), simhash = o.getInt("simhash"), village = o.optString("village"), author = o.optString("author"),
                wallMs = o.getLong("wall"), logical = o.optInt("logical"), mine = o.optBoolean("mine"), votes = o.optInt("votes"),
                iVoted = o.optBoolean("voted"), status = runCatching { TipStatus.valueOf(o.getString("status")) }.getOrDefault(TipStatus.OK),
            )
        }.sortedByDescending { it.wallMs }
    }.getOrDefault(emptyList())

    companion object {
        const val MIN_CHARS = 12
        /** A tip must be at least this similar to a question to be shown next to its answer. */
        const val MIN_SEARCH_COSINE = 0.80f
        private val PHONE = Regex("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{9}(?!\\d)")
        private val AADHAAR = Regex("(?<!\\d)\\d{12}(?!\\d)")
        private val EMAIL = Regex("\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b")
    }
}
