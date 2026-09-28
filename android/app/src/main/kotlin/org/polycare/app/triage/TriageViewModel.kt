package org.polycare.app.triage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.ai.LlmProvider
import org.polycare.app.knowledge.TriageCategory
import org.polycare.app.knowledge.TriageEngine
import org.polycare.app.knowledge.TriageResult
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.llm.GenerationEvent
import org.polycare.llm.PromptFormat
import javax.inject.Inject

data class TriageUiState(
    val category: TriageCategory = TriageCategory.CHILD,
    val selected: Set<String> = emptySet(),
    val result: TriageResult = TriageEngine.evaluate(TriageCategory.CHILD, emptySet()),
    /** Null until the on-device LLM explains [result] in the ASHA's own words; the decision
     *  above is never influenced by this — see invariant 10 and [PromptFormat.triageExplanation]. */
    val aiExplanation: String? = null,
    val generating: Boolean = false,
)

/** M2: danger-sign triage. The decision always comes from [TriageEngine]'s rule table (invariant 10). */
@HiltViewModel
class TriageViewModel @Inject constructor(
    private val llm: LlmProvider,
    private val events: EventLog,
) : ViewModel() {
    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()
    private var explanationJob: Job? = null

    fun onCategoryChange(category: TriageCategory) {
        explanationJob?.cancel()
        _state.value = TriageUiState(category = category, result = TriageEngine.evaluate(category, emptySet()))
    }

    fun toggle(signId: String) {
        explanationJob?.cancel()
        val cur = _state.value
        val selected = if (signId in cur.selected) cur.selected - signId else cur.selected + signId
        val result = TriageEngine.evaluate(cur.category, selected)
        _state.value = cur.copy(selected = selected, result = result, aiExplanation = null, generating = false)
        // Metadata only: never the specific signs, which could identify a household's situation.
        events.record(Category.TRIAGE, "Triage evaluated", mapOf("category" to cur.category.name, "decision" to result.decision.name))
        explain(result)
    }

    private fun explain(result: TriageResult) {
        explanationJob = viewModelScope.launch {
            val ready = llm.get() ?: return@launch // stays null: the template explanation (TriageEngine) is shown instead
            _state.value = _state.value.copy(generating = true)
            val prompt = PromptFormat.triageExplanation(result.decision.label, result.matched.map { it.label }, result.sourceTitle)
            val text = StringBuilder()
            ready.engine.generate(prompt, maxTokens = 120).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        text.append(event.piece)
                        _state.value = _state.value.copy(aiExplanation = text.toString(), generating = true)
                    }
                    is GenerationEvent.Done -> {
                        _state.value = _state.value.copy(aiExplanation = text.toString(), generating = false)
                        events.record(Category.TRIAGE, "AI explanation generated", mapOf("tokensPerSecond" to "%.1f".format(event.stats.tokensPerSecond)))
                    }
                }
            }
        }
    }
}
