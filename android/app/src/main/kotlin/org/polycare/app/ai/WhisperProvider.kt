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
import org.polycare.whisper.WhisperArtifacts
import org.polycare.whisper.WhisperEngine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the on-device speech model. Same verify-before-load / never-crash shape as [LlmProvider]. */
@Singleton
class WhisperProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val events: EventLog,
) {
    sealed interface State {
        data object NotLoaded : State
        data object Loading : State
        data class Ready(val engine: WhisperEngine, val loadMs: Long) : State
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
        when (val v = ArtifactVerifier.verify(modelsRoot, WhisperArtifacts.model)) {
            Verification.Ok -> Unit
            Verification.Missing -> {
                events.record(Category.MODEL, "Whisper file missing", mapOf("file" to WhisperArtifacts.model.path), Level.WARN)
                return State.Unavailable("Model not installed")
            }
            is Verification.Quarantined -> {
                events.record(Category.MODEL, "Whisper file quarantined", mapOf("reason" to v.reason), Level.ERROR)
                return State.Unavailable("Model file failed verification")
            }
        }
        val engine = runCatching { WhisperEngine.load(File(modelsRoot, WhisperArtifacts.model.path)) }
            .getOrElse { e ->
                events.record(Category.MODEL, "Whisper failed to load", mapOf("error" to e.javaClass.simpleName), Level.ERROR)
                return State.Unavailable("Model failed to load")
            }
        if (engine == null) {
            events.record(Category.MODEL, "Whisper failed to load", mapOf("error" to "native load returned null"), Level.ERROR)
            return State.Unavailable("Model failed to load")
        }
        val loadMs = (System.nanoTime() - start) / 1_000_000
        events.record(Category.MODEL, "Whisper ready", mapOf("model" to WhisperArtifacts.MODEL_ID, "loadMs" to loadMs))
        return State.Ready(engine, loadMs)
    }
}
