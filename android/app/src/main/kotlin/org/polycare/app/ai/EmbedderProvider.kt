package org.polycare.app.ai

import android.content.Context
import android.util.Log
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
import org.polycare.embed.E5Artifacts
import org.polycare.embed.E5Embedder
import org.polycare.embed.E5Tokenizer
import org.polycare.embed.SparseEncoder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-device embedder. Files are verified by sha256 before loading (invariant 5); a
 * missing or bad file leaves the app in [State.Unavailable] rather than crashing.
 *
 * Models root: the app's private `files/models/`. Development builds get them with
 * tools/models/push_models.sh; the first-run downloader will write here too.
 */
@Singleton
class EmbedderProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val events: EventLog,
) {

    sealed interface State {
        data object NotLoaded : State
        data object Loading : State
        data class Ready(val embedder: E5Embedder, val sparse: SparseEncoder, val loadMs: Long) : State
        data class Unavailable(val reason: String) : State
    }

    val modelsRoot: File = File(context.filesDir, "models")

    private val _state = MutableStateFlow<State>(State.NotLoaded)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Verifies and opens the model once; later calls return the same instance. */
    suspend fun get(): State.Ready? = mutex.withLock {
        (_state.value as? State.Ready)?.let { return it }
        _state.value = State.Loading
        _state.value = withContext(Dispatchers.IO) { load() }
        _state.value as? State.Ready
    }

    private fun load(): State {
        val start = System.nanoTime()
        for (artifact in E5Artifacts.all) {
            when (val v = ArtifactVerifier.verify(modelsRoot, artifact)) {
                Verification.Ok -> Unit
                Verification.Missing -> return State.Unavailable("Model not installed").also {
                    events.record(Category.MODEL, "Embedder file missing", mapOf("file" to artifact.path), Level.WARN)
                }
                is Verification.Quarantined -> return State.Unavailable("Model file failed verification").also {
                    events.record(Category.MODEL, "Embedder file quarantined", mapOf("file" to artifact.path, "reason" to v.reason), Level.ERROR)
                }
            }
        }
        return runCatching {
            val tokenizer = File(modelsRoot, E5Artifacts.tokenizer.path).inputStream().use(E5Tokenizer::load)
            val embedder = E5Embedder.open(File(modelsRoot, E5Artifacts.model.path), tokenizer)
            val loadMs = (System.nanoTime() - start) / 1_000_000
            events.record(Category.MODEL, "Embedder ready", mapOf("model" to embedder.modelId, "verifiedFiles" to E5Artifacts.all.size, "loadMs" to loadMs))
            State.Ready(embedder, SparseEncoder(tokenizer), loadMs)
        }.getOrElse { e ->
            Log.e(TAG, "embedder failed to load", e)
            events.record(Category.MODEL, "Embedder failed to load", mapOf("error" to e.javaClass.simpleName), Level.ERROR)
            State.Unavailable("Model failed to load")
        }
    }

    private companion object {
        const val TAG = "PolyCareEmbed"
    }
}
