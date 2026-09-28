package org.polycare.app.knowledge

enum class TriageCategory(val label: String) {
    NEWBORN("Newborn (0–2 months)"),
    CHILD("Child (2 months–5 years)"),
    POSTPARTUM("Mother, postpartum (first 6 weeks)"),
}

enum class TriageDecision(val label: String) {
    REFER_NOW("Refer now"),
    REFER_24H("Refer within 24 hours"),
    CARE_AT_HOME("Care at home"),
}

data class DangerSign(val id: String, val label: String)

data class TriageResult(
    val decision: TriageDecision,
    val matched: List<DangerSign>,
    val explanation: String,
    val sourceTitle: String,
)

/**
 * Danger-sign triage rule table (M2). The referral decision always comes from here, never from a
 * model: MILESTONES.md is explicit that "the LLM only explains." [TriageResult.explanation] is a
 * template today; once the on-device LLM is integrated (M0) it explains this same rule-decided
 * outcome in the ASHA's own words — it will never be allowed to change the decision.
 *
 * Sign wording follows the danger-sign lists in the ASHA modules already indexed in this build's
 * knowledge base (see [tools/knowledge/sources.json][sources]); cite the same titles here rather
 * than inventing separate ones, so "Search" and "Triage" point at the same official material.
 */
object TriageEngine {

    private const val MODULE_6 = "ASHA Module 6 — Skills that Save Lives: Maternal and Newborn Health"
    private const val MODULE_7 = "ASHA Module 7 — Skills that Save Lives: Child Health and Nutrition"

    private val newbornUrgent = listOf(
        DangerSign("nb-not-feeding", "Not feeding well, or has stopped feeding"),
        DangerSign("nb-convulsions", "Convulsions (fits)"),
        DangerSign("nb-fast-breathing", "Fast breathing (60+ breaths/min) or severe chest indrawing"),
        DangerSign("nb-temperature", "Feels hot or cold to touch — fever or low temperature"),
        DangerSign("nb-lethargy", "Moves only when stimulated, or does not move at all"),
        DangerSign("nb-jaundice", "Yellow skin spreading to palms and soles"),
        DangerSign("nb-cord", "Redness or pus discharge around the umbilical cord"),
    )

    private val childUrgent = listOf(
        DangerSign("ch-not-drinking", "Not able to drink or breastfeed, or vomits everything"),
        DangerSign("ch-convulsions", "Convulsions now, or during this illness"),
        DangerSign("ch-lethargy", "Lethargic or unconscious"),
        DangerSign("ch-chest-indrawing", "Chest indrawing when breathing in"),
        DangerSign("ch-sunken-eyes", "Sunken eyes with a skin pinch that goes back very slowly"),
    )
    private val child24h = listOf(
        DangerSign("ch-fast-breathing", "Fast breathing for age (50+/min under 1 year, 40+/min 1–5 years)"),
        DangerSign("ch-some-dehydration", "Restless, thirsty, sunken eyes, skin pinch goes back slowly"),
    )

    private val postpartumUrgent = listOf(
        DangerSign("pp-bleeding", "Heavy bleeding — more than 2–3 pads an hour, or large clots"),
        DangerSign("pp-fever", "High fever"),
        DangerSign("pp-convulsions", "Convulsions (fits)"),
        DangerSign("pp-headache", "Severe headache with blurred vision"),
        DangerSign("pp-discharge", "Foul-smelling vaginal discharge"),
        DangerSign("pp-abdo-pain", "Severe abdominal pain"),
    )

    /** All checkable signs for a category, most urgent first. */
    fun signs(category: TriageCategory): List<DangerSign> = when (category) {
        TriageCategory.NEWBORN -> newbornUrgent
        TriageCategory.CHILD -> childUrgent + child24h
        TriageCategory.POSTPARTUM -> postpartumUrgent
    }

    fun evaluate(category: TriageCategory, selectedIds: Set<String>): TriageResult {
        val urgent = when (category) {
            TriageCategory.NEWBORN -> newbornUrgent
            TriageCategory.CHILD -> childUrgent
            TriageCategory.POSTPARTUM -> postpartumUrgent
        }
        val sourceTitle = if (category == TriageCategory.CHILD) MODULE_7 else MODULE_6
        val matchedUrgent = urgent.filter { it.id in selectedIds }
        if (matchedUrgent.isNotEmpty()) {
            return TriageResult(
                decision = TriageDecision.REFER_NOW,
                matched = matchedUrgent,
                explanation = "${matchedUrgent.size} danger sign(s) marked need urgent care: " +
                    matchedUrgent.joinToString("; ") { it.label } + ". Refer now; do not wait.",
                sourceTitle = sourceTitle,
            )
        }
        if (category == TriageCategory.CHILD) {
            val matched24h = child24h.filter { it.id in selectedIds }
            if (matched24h.isNotEmpty()) {
                return TriageResult(
                    decision = TriageDecision.REFER_24H,
                    matched = matched24h,
                    explanation = "No urgent danger sign, but " +
                        matched24h.joinToString("; ") { it.label } +
                        ". Start ORS and zinc, refer within 24 hours if not improving.",
                    sourceTitle = sourceTitle,
                )
            }
        }
        return TriageResult(
            decision = TriageDecision.CARE_AT_HOME,
            matched = emptyList(),
            explanation = "No danger signs marked. Care at home and advise the family when to come back.",
            sourceTitle = sourceTitle,
        )
    }
}
