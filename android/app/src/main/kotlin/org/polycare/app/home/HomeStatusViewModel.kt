package org.polycare.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.knowledge.KnowledgeRepository
import javax.inject.Inject

/** What is installed on this phone, for the Home "On this phone" card. */
@HiltViewModel
class HomeStatusViewModel @Inject constructor(
    knowledgeRepository: KnowledgeRepository,
    embedderProvider: EmbedderProvider,
) : ViewModel() {
    val knowledge = knowledgeRepository.state
    val embedder = embedderProvider.state

    init {
        viewModelScope.launch {
            knowledgeRepository.open()
            embedderProvider.get()
        }
    }
}
