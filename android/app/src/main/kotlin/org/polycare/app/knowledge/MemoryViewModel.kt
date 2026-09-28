package org.polycare.app.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryFilter(val sourceId: String? = null, val lang: String? = null)

data class MemoryUiState(
    val stats: KnowledgeStats? = null,
    val filter: MemoryFilter = MemoryFilter(),
    val items: List<KnowledgeItem> = emptyList(),
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
)

/**
 * Backs the Memory Inspector (M1: "browse and filter what the phone knows"). Read-only: it
 * shows the cloud-owned `knowledge` shard as installed (invariant 9) — there is nothing here yet
 * to edit, since device-owned `memory` (the ASHA's own notes) does not exist until M3/M4.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(private val knowledge: KnowledgeRepository) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()
    private var cursor: String? = null

    init {
        viewModelScope.launch {
            knowledge.open()
            _state.value = _state.value.copy(stats = knowledge.stats())
            loadPage(reset = true)
        }
    }

    fun setSource(sourceId: String?) {
        _state.value = _state.value.copy(filter = _state.value.filter.copy(sourceId = sourceId))
        viewModelScope.launch { loadPage(reset = true) }
    }

    fun setLang(lang: String?) {
        _state.value = _state.value.copy(filter = _state.value.filter.copy(lang = lang))
        viewModelScope.launch { loadPage(reset = true) }
    }

    fun loadMore() {
        if (_state.value.loadingMore || !_state.value.hasMore) return
        viewModelScope.launch { loadPage(reset = false) }
    }

    private suspend fun loadPage(reset: Boolean) {
        if (reset) {
            cursor = null
            _state.value = _state.value.copy(items = emptyList(), hasMore = true)
        }
        _state.value = _state.value.copy(loadingMore = true)
        val filter = _state.value.filter
        val page = knowledge.browse(sourceId = filter.sourceId, lang = filter.lang, cursor = cursor, limit = PAGE_SIZE)
        cursor = page?.nextCursor
        _state.value = _state.value.copy(
            items = if (reset) page?.items.orEmpty() else _state.value.items + page?.items.orEmpty(),
            loadingMore = false,
            hasMore = page?.nextCursor != null,
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
