package org.polycare.app.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedderProvider
import javax.inject.Inject

sealed interface SearchUi {
    data object Idle : SearchUi
    data object Searching : SearchUi
    data class Results(val query: String, val result: KnowledgeResult) : SearchUi
    data class Unavailable(val reason: String) : SearchUi
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val knowledge: KnowledgeRepository,
    private val embedders: EmbedderProvider,
) : ViewModel() {

    val knowledgeState: StateFlow<KnowledgeRepository.State> = knowledge.state
    val embedderState: StateFlow<EmbedderProvider.State> = embedders.state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ui = MutableStateFlow<SearchUi>(SearchUi.Idle)
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    init {
        // Warm both up so the first search is fast.
        viewModelScope.launch {
            knowledge.open()
            embedders.get()
        }
        _query.debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .onEach { run(it) }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(value: String) {
        _query.value = value
        if (value.isBlank()) _ui.value = SearchUi.Idle
    }

    fun searchNow(value: String = _query.value) {
        _query.value = value
        if (value.isNotBlank()) viewModelScope.launch { run(value) }
    }

    private suspend fun run(q: String) {
        _ui.value = SearchUi.Searching
        val result = knowledge.search(q)
        _ui.value = when {
            result != null -> SearchUi.Results(q, result)
            knowledge.state.value is KnowledgeRepository.State.NotInstalled -> SearchUi.Unavailable("Knowledge base not installed yet")
            embedders.state.value is EmbedderProvider.State.Unavailable -> SearchUi.Unavailable("Language model not installed yet")
            else -> SearchUi.Unavailable("Search is not available right now")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}
