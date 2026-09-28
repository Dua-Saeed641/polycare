package org.polycare.llm

/**
 * Direct JNI surface over llama.cpp (src/main/cpp/jni_bridge.cpp). Intentionally thin and not
 * meant to be called directly — use [LlamaEngine], which adds prompt formatting, dispatching to
 * a dedicated thread, and error handling around this.
 *
 * All handles are opaque native pointers (as `Long`), owned by the C++ side; `0L` means null /
 * failed. Every call here blocks the calling thread until llama.cpp returns.
 */
internal object LlamaNative {
    /** Loads the native libraries in dependency order. Safe to call more than once. */
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        // Android resolves a library's own DT_NEEDED deps from the same apk lib directory since
        // API 23, but we load explicitly and in order anyway: it fails fast and clearly if one
        // native lib is missing, rather than surfacing as a confusing symbol-not-found later.
        for (lib in listOf("c++_shared", "ggml-base", "ggml-cpu", "ggml", "llama", "polycare_llm")) {
            System.loadLibrary(lib)
        }
        backendInit()
        loaded = true
    }

    external fun backendInit()

    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    external fun freeModel(handle: Long)

    external fun loadLora(handle: Long, path: String): Long

    external fun freeLora(loraHandle: Long)

    external fun setAdapters(handle: Long, loraHandles: LongArray, scales: FloatArray): Boolean

    /** Returns `[promptTokens, generatedTokens, promptMs, decodeMs]`. */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        sink: TokenSink,
    ): LongArray
}

/** Called once per generated token piece, on the native call's own thread (see [LlamaEngine]). */
fun interface TokenSink {
    fun onToken(piece: String)
}
