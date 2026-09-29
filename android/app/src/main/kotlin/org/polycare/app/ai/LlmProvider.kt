package org.polycare.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.polycare.common.ArtifactVerifier
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.Verification
import org.polycare.llm.LlamaEngine
import org.polycare.llm.LlmArtifacts
import org.polycare.llm.PromptFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-device base model (Qwen2.5-1.5B, GGUF, ~1.1 GB). Verified by sha256 before load
 * (invariant 5); a missing or bad file leaves the app in [State.Unavailable], never crashes —
 * Ask and Triage fall back to their retrieval-only / rule-only behaviour (invariant 6).
 */
@Singleton
class LlmProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val events: EventLog,
    private val settings: org.polycare.app.settings.AppSettings,
) {
    sealed interface State {
        data object NotLoaded : State
        data object Loading : State
        data class Ready(val engine: LlamaEngine, val loadMs: Long) : State
        data class Unavailable(val reason: String) : State
    }

    val modelsRoot: File = File(context.filesDir, "models")

    private val _state = MutableStateFlow<State>(State.NotLoaded)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun get(): State.Ready? = mutex.withLock {
        (_state.value as? State.Ready)?.let { return it }
        _state.value = State.Loading
        _state.value = withContext(Dispatchers.IO) { load() }
        _state.value as? State.Ready
    }

    private suspend fun load(): State {
        val start = System.nanoTime()
        when (val v = ArtifactVerifier.verify(modelsRoot, LlmArtifacts.baseModel)) {
            Verification.Ok -> Unit
            Verification.Missing -> {
                events.record(Category.MODEL, "LLM file missing", mapOf("file" to LlmArtifacts.baseModel.path), Level.WARN)
                return State.Unavailable("Model not installed")
            }
            is Verification.Quarantined -> {
                events.record(Category.MODEL, "LLM file quarantined", mapOf("reason" to v.reason), Level.ERROR)
                return State.Unavailable("Model file failed verification")
            }
        }
        val engine = runCatching { LlamaEngine.load(File(modelsRoot, LlmArtifacts.baseModel.path), gpuLayers = if (settings.useGpu.value) -1 else 0) }
            .getOrElse { e ->
                events.record(Category.MODEL, "LLM failed to load", mapOf("error" to e.javaClass.simpleName), Level.ERROR)
                return State.Unavailable("Model failed to load")
            }
        if (engine == null) {
            events.record(Category.MODEL, "LLM failed to load", mapOf("error" to "native load returned null"), Level.ERROR)
            return State.Unavailable("Model failed to load")
        }
        // Pre-fill the constant system prompt so the first real question skips that prefill.
        val warmStart = System.nanoTime()
        runCatching { engine.warmUp(PromptFormat.askPrefix) }
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000
        val loadMs = (System.nanoTime() - start) / 1_000_000
        events.record(Category.MODEL, "LLM ready", mapOf("model" to LlmArtifacts.MODEL_ID, "loadMs" to loadMs, "warmMs" to warmMs))
        return State.Ready(engine, loadMs)
    }
}
