package org.polycare.app.triage

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.polycare.app.knowledge.TriageCategory
import org.polycare.app.knowledge.TriageEngine
import org.polycare.app.knowledge.TriageResult
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import javax.inject.Inject

data class TriageUiState(
    val category: TriageCategory = TriageCategory.CHILD,
    val selected: Set<String> = emptySet(),
    val result: TriageResult = TriageEngine.evaluate(TriageCategory.CHILD, emptySet()),
)

/** M2: danger-sign triage. The decision always comes from [TriageEngine]'s rule table (invariant 10). */
@HiltViewModel
class TriageViewModel @Inject constructor(private val events: EventLog) : ViewModel() {
    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    fun onCategoryChange(category: TriageCategory) {
        _state.value = TriageUiState(category = category, result = TriageEngine.evaluate(category, emptySet()))
    }

    fun toggle(signId: String) {
        val cur = _state.value
        val selected = if (signId in cur.selected) cur.selected - signId else cur.selected + signId
        val result = TriageEngine.evaluate(cur.category, selected)
        _state.value = cur.copy(selected = selected, result = result)
        // Metadata only: never the specific signs, which could identify a household's situation.
        events.record(Category.TRIAGE, "Triage evaluated", mapOf("category" to cur.category.name, "decision" to result.decision.name))
    }
}
