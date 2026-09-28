package org.polycare.app.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.ai.LlmProvider
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.PolyCareConfig
import org.polycare.llm.GenerationEvent
import org.polycare.llm.PromptFormat
import javax.inject.Inject

sealed interface AskUi {
    data object Idle : AskUi
    data object Asking : AskUi

    /**
     * [generated] is null until the on-device LLM produces an explanation (or stays null if it
     * is not installed — this is the Resource Governor's RECALL rung in practice: the retrieved
     * passage alone is still a complete, sourced answer, invariant 6).
     */
    data class Answered(
        val hit: KnowledgeHit,
        val confidence: Float,
        val gapLogged: Boolean,
        val generated: String? = null,
        val generating: Boolean = false,
        val tokensPerSecond: Double? = null,
    ) : AskUi

    data class NoAnswer(val gapLogged: Boolean) : AskUi
    data class Unavailable(val reason: String) : AskUi
}

/**
 * M2 "Ask": retrieve the best matching protocol passage, then — if the on-device LLM (M0) is
 * installed — have it explain that passage in plain language, grounded and cited. The model
 * only ever explains a passage that was already retrieved; it is never asked to answer from its
 * own knowledge, which is the whole point of PromptFormat.ask's system prompt.
 */
@HiltViewModel
class AskViewModel @Inject constructor(
    private val knowledge: KnowledgeRepository,
    private val gaps: GapsRepository,
    private val llm: LlmProvider,
    private val events: EventLog,
) : ViewModel() {
    private val _question = MutableStateFlow("")
    val question: StateFlow<String> = _question.asStateFlow()

    private val _ui = MutableStateFlow<AskUi>(AskUi.Idle)
    val ui: StateFlow<AskUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { knowledge.open() }
    }

    fun onQuestionChange(value: String) {
        _question.value = value
    }

    fun ask(value: String = _question.value) {
        if (value.isBlank()) return
        _question.value = value
        viewModelScope.launch {
            _ui.value = AskUi.Asking
            val result = knowledge.search(value)
            val top = result?.hits?.firstOrNull()
            if (top == null) {
                gaps.log(value, 0f)
                _ui.value = AskUi.NoAnswer(gapLogged = true)
                return@launch
            }

            val confidence = termOverlap(value, top.text)
            val lowConfidence = confidence < PolyCareConfig.Routing.minSkillScore
            if (lowConfidence) gaps.log(value, confidence)
            events.record(Category.ASK, "Ask answered", mapOf("confidence" to "%.2f".format(confidence), "lowConfidence" to lowConfidence))

            val ready = llm.get()
            if (ready == null) {
                _ui.value = AskUi.Answered(top, confidence, lowConfidence)
                return@launch
            }

            _ui.value = AskUi.Answered(top, confidence, lowConfidence, generating = true)
            val prompt = PromptFormat.ask(value, top.text, top.title)
            val text = StringBuilder()
            ready.engine.generate(prompt).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        text.append(event.piece)
                        (_ui.value as? AskUi.Answered)?.let { _ui.value = it.copy(generated = text.toString(), generating = true) }
                    }
                    is GenerationEvent.Done -> {
                        (_ui.value as? AskUi.Answered)?.let {
                            _ui.value = it.copy(generated = text.toString(), generating = false, tokensPerSecond = event.stats.tokensPerSecond)
                        }
                        events.record(
                            Category.ASK, "LLM explanation generated",
                            mapOf(
                                "promptTokens" to event.stats.promptTokens, "generatedTokens" to event.stats.generatedTokens,
                                "tokensPerSecond" to "%.1f".format(event.stats.tokensPerSecond),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Simple, explainable confidence: the fraction of question words that appear in the answer. */
    private fun termOverlap(question: String, answerText: String): Float {
        val qTerms = tokenize(question)
        if (qTerms.isEmpty()) return 0f
        val aTerms = tokenize(answerText)
        return qTerms.count { it in aTerms }.toFloat() / qTerms.size
    }

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toSet()
}
