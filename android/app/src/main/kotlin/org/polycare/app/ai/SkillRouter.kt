package org.polycare.app.ai

import org.polycare.common.PolyCareConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/** A skill file with the request-specific LoRA scale it should be loaded at (ARCHITECTURE.md §5.1). */
data class SkillWeight(val id: String, val title: String, val file: File, val scale: Float)

data class SkillRoute(val weights: List<SkillWeight>) {
    /** For the UI's "skill" badge (M2: "Answer shows skill, sources and confidence badge"). */
    val label: String? get() = weights.maxByOrNull { it.scale }?.title
}

/**
 * Chooses which trained LoRA skill(s), if any, should answer a question — ARCHITECTURE.md §5.1:
 * embed the question with the same on-device embedder already used for knowledge retrieval,
 * compare it against each skill's short "card" description by cosine similarity, and either use
 * the base model alone (top score below τ), blend the top two by softmax (their scores within δ
 * of each other), or use the single best match. No extra model, no server call: the multilingual
 * embedder is already loaded for Search/Ask, and card embeddings are cheap enough (a handful of
 * short strings) to compute on demand and cache for the process lifetime.
 */
@Singleton
class SkillRouter @Inject constructor(
    private val skills: SkillsRepository,
    private val embedders: EmbedderProvider,
) {
    // Keyed by skill id; recomputed whenever the available skill set changes (e.g. a new skill
    // pushed and the app restarted — SkillsRepository.available() is re-read from disk each time).
    private var cachedForIds: Set<String>? = null
    private var cardEmbeddings: Map<String, FloatArray> = emptyMap()

    suspend fun route(question: String): SkillRoute {
        val available = skills.available()
        if (available.isEmpty()) return SkillRoute(emptyList())
        val ready = embedders.get() ?: return SkillRoute(emptyList())

        val ids = available.map { it.id }.toSet()
        if (cachedForIds != ids) {
            cardEmbeddings = available.associate { it.id to ready.embedder.embedPassages(listOf(it.card)).first() }
            cachedForIds = ids
        }

        val qVec = ready.embedder.embedQuery(question)
        val scored = available
            .mapNotNull { s -> cardEmbeddings[s.id]?.let { s to cosine(qVec, it) } }
            .sortedByDescending { it.second }
            .take(PolyCareConfig.Routing.skillCandidates)
        val top1 = scored.firstOrNull() ?: return SkillRoute(emptyList())
        if (top1.second < PolyCareConfig.Routing.minSkillScore) return SkillRoute(emptyList())

        val top2 = scored.getOrNull(1)
        if (top2 != null && (top1.second - top2.second) < PolyCareConfig.Routing.blendMargin) {
            val t = PolyCareConfig.Routing.blendTemperature
            val e1 = exp(top1.second / t)
            val e2 = exp(top2.second / t)
            val sum = e1 + e2
            return SkillRoute(
                listOf(
                    SkillWeight(top1.first.id, top1.first.title, top1.first.file, e1 / sum),
                    SkillWeight(top2.first.id, top2.first.title, top2.first.file, e2 / sum),
                ),
            )
        }
        return SkillRoute(listOf(SkillWeight(top1.first.id, top1.first.title, top1.first.file, PolyCareConfig.Llm.singleSkillScale)))
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
