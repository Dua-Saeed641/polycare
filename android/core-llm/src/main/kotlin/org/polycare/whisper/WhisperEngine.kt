package org.polycare.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Multilingual `ggml-base` (whisper.cpp), quantised q5_1, ~60 MB — see [WhisperArtifacts]. */
class WhisperEngine private constructor(private val handle: Long) : Closeable {

    /**
     * [pcm16] is 16-bit signed mono PCM at [SAMPLE_RATE_HZ] (what `AudioRecord` gives you at
     * that rate). [language] is a two-letter code ("en", "hi", ...) or "auto" to detect it.
     */
    suspend fun transcribe(pcm16: ShortArray, language: String = "auto"): String = withContext(Dispatcher) {
        val floats = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        WhisperNative.transcribe(handle, floats, language, threads()).trim()
    }

    override fun close() {
        WhisperNative.freeModel(handle)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        /** One dedicated thread: mirrors LlamaEngine's rule (JNI never on Main, one call at a time). */
        private val Dispatcher = Dispatchers.IO.limitedParallelism(1)

        suspend fun load(modelFile: File): WhisperEngine? = withContext(Dispatcher) {
            WhisperNative.ensureLoaded()
            val h = WhisperNative.loadModel(modelFile.absolutePath)
            if (h == 0L) null else WhisperEngine(h)
        }

        private fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
