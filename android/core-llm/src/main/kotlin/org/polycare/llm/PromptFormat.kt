package org.polycare.llm

/**
 * ChatML prompts for Qwen2.5-Instruct (its tokenizer's default `chat_template`). Written out by
 * hand rather than using llama.cpp's `llama_chat_apply_template` — that function only recognises
 * a fixed, version-dependent list of template names, and ChatML is simple enough that hardcoding
 * it here keeps this working across llama.cpp updates without checking that list.
 *
 * Every prompt is grounded in a retrieved passage and told not to answer outside it (invariant
 * 10: the model explains, it never diagnoses or invents guidance).
 */
object PromptFormat {

    private const val ASK_SYSTEM = "You are a careful health-information assistant for ASHA " +
        "(community health) workers in India, used offline on a phone. Answer ONLY using the " +
        "passage given below — never use outside knowledge and never guess. If the passage does " +
        "not answer the question, say so plainly and suggest referring to the ANM or PHC. Keep " +
        "the answer to 1–3 short sentences, in the same language as the question. Never state a " +
        "diagnosis; only explain the guidance in the passage."

    private const val TRIAGE_SYSTEM = "You explain a health-worker's already-decided triage " +
        "outcome in plain, reassuring language. A fixed rule table made this decision, not you " +
        "— never change it, second-guess it, or suggest a different action. Cite the source. " +
        "Keep it to 2–3 short sentences."

    fun chatMl(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /**
     * The constant start of every Ask prompt (system message + opening of the user turn). Fed to
     * the model once at load time so its KV entries are ready and never recomputed per question.
     */
    val askPrefix: String = "<|im_start|>system\n$ASK_SYSTEM<|im_end|>\n<|im_start|>user\n"

    fun ask(question: String, passageText: String, sourceTitle: String): String = chatMl(
        ASK_SYSTEM,
        "Passage (from $sourceTitle):\n$passageText\n\nQuestion: $question",
    )

    fun triageExplanation(decisionLabel: String, matchedSigns: List<String>, sourceTitle: String): String = chatMl(
        TRIAGE_SYSTEM,
        "Decision: $decisionLabel\n" +
            "Matched danger signs: ${matchedSigns.joinToString("; ").ifEmpty { "none — no danger sign was marked" }}\n" +
            "Source: $sourceTitle\n\n" +
            "Explain this decision to the ASHA worker in plain language.",
    )
}
