package org.polycare.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.ai.LlmProvider
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.governor.DegradationLadder
import org.polycare.governor.DeviceProbe
import org.polycare.governor.Rung
import javax.inject.Inject

/**
 * What is installed on this phone, for the Home "On this phone" card — and, since Home is the
 * app's start destination, the natural place to start warming everything Ask/Triage/Search will
 * need, so the model is already resident by the time a question is typed instead of paying its
 * ~6s load cost on top of the first answer's generation time.
 */
@HiltViewModel
class HomeStatusViewModel @Inject constructor(
    knowledgeRepository: KnowledgeRepository,
    embedderProvider: EmbedderProvider,
    llmProvider: LlmProvider,
    deviceProbe: DeviceProbe,
) : ViewModel() {
    val knowledge = knowledgeRepository.state
    val embedder = embedderProvider.state

    init {
        viewModelScope.launch {
            knowledgeRepository.open()
            embedderProvider.get()
        }
        // Invariant 6: every inference path respects the rung. RECALL means "no LLM" for this
        // device (too little RAM, thermal-critical, or non-arm64) — loading a 1.1GB model there
        // would fight the rung's own decision instead of honouring it, so only warm it up when
        // the rung says this device can actually carry it.
        val rung = DegradationLadder.choose(deviceProbe.snapshot())
        if (rung != Rung.RECALL) {
            viewModelScope.launch { llmProvider.get() }
        }
    }
}
