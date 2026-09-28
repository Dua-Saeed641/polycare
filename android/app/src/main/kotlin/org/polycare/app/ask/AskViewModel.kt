package org.polycare.app.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.PolyCareConfig
import javax.inject.Inject

sealed interface AskUi {
    data object Idle : AskUi
    data object Asking : AskUi
    data class Answered(val hit: KnowledgeHit, val confidence: Float, val gapLogged: Boolean) : AskUi
    data class NoAnswer(val gapLogged: Boolean) : AskUi
    data class Unavailable(val reason: String) : AskUi
}

/**
 * M2 "Ask": the best matching protocol passage, with a source and a confidence badge, exactly
 * the Resource Governor's RECALL rung ("no LLM: shows the best protocol passages") — that is
 * genuinely what this does today, since llama.cpp is not yet integrated (M0). A generated,
 * conversational explanation will replace/augment this once that lands; never fabricate one now.
 */
@HiltViewModel
class AskViewModel @Inject constructor(
    private val knowledge: KnowledgeRepository,
    private val gaps: GapsRepository,
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
            _ui.value = if (top == null) {
                gaps.log(value, 0f)
                AskUi.NoAnswer(gapLogged = true)
            } else {
                val confidence = termOverlap(value, top.text)
                val lowConfidence = confidence < PolyCareConfig.Routing.minSkillScore
                if (lowConfidence) gaps.log(value, confidence)
                events.record(Category.ASK, "Ask answered", mapOf("confidence" to "%.2f".format(confidence), "lowConfidence" to lowConfidence))
                AskUi.Answered(top, confidence, gapLogged = lowConfidence)
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
