package org.polycare.app.ask

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.ai.LlmProvider
import org.polycare.app.ai.SkillRouter
import org.polycare.app.ai.VoiceRecorder
import org.polycare.app.ai.WhisperProvider
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.app.team.TeamAnswer
import org.polycare.app.team.TeamGuidanceRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.PolyCareConfig
import org.polycare.llm.GenerationEvent
import org.polycare.llm.PromptFormat
import javax.inject.Inject

sealed interface VoiceUi {
    data object Idle : VoiceUi
    data object Recording : VoiceUi
    data object Transcribing : VoiceUi
    data class Failed(val reason: String) : VoiceUi
}

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
        /** Which trained skill answered, if any (ARCHITECTURE.md §5.1); null means base model. */
        val skill: String? = null,
        /** Share of speculatively drafted tokens the model confirmed (0..1), null if none were drafted. */
        val draftAcceptance: Double? = null,
        /** Prompt tokens served from the KV cache instead of being recomputed. */
        val cachedPromptTokens: Int = 0,
        val promptMs: Long? = null,
        /** A supervisor's answer to this same question, received through sync. */
        val teamAnswer: TeamAnswer? = null,
    ) : AskUi

    data class NoAnswer(val gapLogged: Boolean, val teamAnswer: TeamAnswer? = null) : AskUi
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
    private val skillRouter: SkillRouter,
    private val whisper: WhisperProvider,
    private val teamGuidance: TeamGuidanceRepository,
    @ApplicationContext private val context: Context,
    private val events: EventLog,
) : ViewModel() {
    private val _question = MutableStateFlow("")
    val question: StateFlow<String> = _question.asStateFlow()

    private val _ui = MutableStateFlow<AskUi>(AskUi.Idle)
    val ui: StateFlow<AskUi> = _ui.asStateFlow()

    private val _voice = MutableStateFlow<VoiceUi>(VoiceUi.Idle)
    val voice: StateFlow<VoiceUi> = _voice.asStateFlow()

    private val recorder = VoiceRecorder(context)
    private var recordingJob: Job? = null

    init {
        viewModelScope.launch { knowledge.open() }
    }

    fun onQuestionChange(value: String) {
        _question.value = value
    }

    /** Mic tapped: start capturing. No-op if already recording. */
    fun startRecording() {
        if (recordingJob != null) return
        _voice.value = VoiceUi.Recording
        recordingJob = viewModelScope.launch {
            val pcm = recorder.recordUntilStopped()
            recordingJob = null
            if (pcm.isEmpty()) {
                _voice.value = VoiceUi.Failed(
                    if (!VoiceRecorder.hasPermission(context)) "Microphone permission needed" else "Could not record audio",
                )
                return@launch
            }
            _voice.value = VoiceUi.Transcribing
            val ready = whisper.get()
            if (ready == null) {
                _voice.value = VoiceUi.Failed("Voice model not installed — type your question instead")
                return@launch
            }
            val text = runCatching { ready.engine.transcribe(pcm, language = "auto") }.getOrElse {
                events.record(Category.ASK, "Voice transcription failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
                _voice.value = VoiceUi.Failed("Could not understand that — try typing instead")
                return@launch
            }
            events.record(Category.ASK, "Voice question transcribed", mapOf("chars" to text.length, "samples" to pcm.size))
            _voice.value = VoiceUi.Idle
            if (text.isNotBlank()) ask(text) else _voice.value = VoiceUi.Failed("Didn't catch that — try again")
        }
    }

    /** Mic released or tapped again: stop capturing (the in-flight [startRecording] job finishes the rest). */
    fun stopRecording() {
        recorder.requestStop()
    }

    fun ask(value: String = _question.value) {
        if (value.isBlank()) return
        _question.value = value
        viewModelScope.launch {
            _ui.value = AskUi.Asking
            val result = knowledge.search(value)
            val top = result?.hits?.firstOrNull()
            val team = teamGuidance.find(value)
            if (top == null) {
                if (team == null) gaps.log(value, 0f)
                _ui.value = AskUi.NoAnswer(gapLogged = team == null, teamAnswer = team)
                return@launch
            }

            val confidence = termOverlap(value, top.text)
            val lowConfidence = confidence < PolyCareConfig.Routing.minSkillScore
            if (lowConfidence && team == null) gaps.log(value, confidence)
            events.record(Category.ASK, "Ask answered", mapOf("confidence" to "%.2f".format(confidence), "lowConfidence" to lowConfidence))

            val ready = llm.get()
            if (ready == null) {
                _ui.value = AskUi.Answered(top, confidence, gapLogged = lowConfidence && team == null, teamAnswer = team)
                return@launch
            }

            // ARCHITECTURE.md §5.1: route to a trained skill by comparing the question's embedding
            // to each skill's card, blend the top two if they're close, or fall back to the base
            // model alone. Routing never touches which passage was retrieved or the confidence
            // badge above — it only picks which adapter, if any, explains that passage.
            val route = skillRouter.route(value)
            ready.engine.clearSkills()
            for (w in route.weights) ready.engine.loadSkill(w.file)
            ready.engine.setActiveSkills(route.weights.map { it.file to it.scale })
            if (route.weights.isNotEmpty()) {
                events.record(Category.ASK, "Skill routed", mapOf("skills" to route.weights.joinToString { "${it.id}=%.2f".format(it.scale) }))
            }

            _ui.value = AskUi.Answered(
                top, confidence, gapLogged = lowConfidence && team == null,
                generating = true, skill = route.label, teamAnswer = team,
            )
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
                            _ui.value = it.copy(
                                generated = text.toString(), generating = false, tokensPerSecond = event.stats.tokensPerSecond,
                                draftAcceptance = if (event.stats.draftedTokens > 0) event.stats.draftAcceptance else null,
                                cachedPromptTokens = event.stats.reusedPrefixTokens, promptMs = event.stats.promptMs,
                            )
                        }
                        events.record(
                            Category.ASK, "LLM explanation generated",
                            mapOf(
                                "promptTokens" to event.stats.promptTokens, "generatedTokens" to event.stats.generatedTokens,
                                "tokensPerSecond" to "%.1f".format(event.stats.tokensPerSecond),
                                "drafted" to event.stats.draftedTokens, "accepted" to event.stats.acceptedTokens,
                                "cachedPromptTokens" to event.stats.reusedPrefixTokens, "promptMs" to event.stats.promptMs,
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

    override fun onCleared() {
        recorder.requestStop() // leaving the screen mid-recording must not leak an open AudioRecord
    }
}
