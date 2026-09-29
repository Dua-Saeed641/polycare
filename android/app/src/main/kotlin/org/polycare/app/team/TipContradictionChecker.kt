package org.polycare.app.team

import org.polycare.app.ai.LlmProvider
import org.polycare.llm.GenerationEvent
import org.polycare.llm.PromptFormat
import javax.inject.Inject
import javax.inject.Singleton

enum class Verdict(val label: String) {
    CONTRADICT("The on-device model thinks these disagree."),
    CONSISTENT("The on-device model thinks these agree."),
    UNSURE("The on-device model could not tell. Please read both."),
    UNAVAILABLE("The on-device model is not installed. Please read both."),
}

/**
 * Optional help for the Conflict Inbox: asks the on-device model whether two similar tips
 * contradict each other. It only ever *advises*: a person decides, and a small model is often
 * wrong, so the answer is shown as "thinks", never applied automatically. Run on demand (a tap),
 * never during a background sync, because loading the model costs memory and battery.
 */
@Singleton
class TipContradictionChecker @Inject constructor(private val llm: LlmProvider) {

    suspend fun check(a: String, b: String): Verdict {
        val ready = llm.get() ?: return Verdict.UNAVAILABLE
        val prompt = PromptFormat.chatMl(
            "You compare two short health tips written by community health workers. Answer with exactly " +
                "one word: yes if they give conflicting advice, no if they agree or are about different things.",
            "Tip 1: $a\n\nTip 2: $b\n\nDo these two tips give conflicting advice?",
        )
        val text = StringBuilder()
        runCatching {
            ready.engine.generate(prompt, maxTokens = 4, temperature = 0f, maxDraft = 0).collect { e ->
                if (e is GenerationEvent.Token) text.append(e.piece)
            }
        }.onFailure { return Verdict.UNSURE }
        val answer = text.toString().trim().lowercase()
        return when {
            answer.startsWith("yes") -> Verdict.CONTRADICT
            answer.startsWith("no") -> Verdict.CONSISTENT
            else -> Verdict.UNSURE
        }
    }
}
