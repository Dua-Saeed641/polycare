package org.polycare.whisper

/** Direct JNI surface over whisper.cpp (src/main/cpp/whisper_bridge.cpp). Use [WhisperEngine]. */
internal object WhisperNative {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        // Shares ggml/ggml-base/ggml-cpu with LlamaNative (see core-llm's CMakeLists.txt); loading
        // an already-loaded library again is a harmless no-op, so both can call this independently.
        // "ggml-cpu" is deliberately absent: the native build sets GGML_CPU_ALL_VARIANTS, which
        // emits one shared lib per ARM variant (libggml-cpu-android_armv8.2_1.so and friends) and
        // no plain libggml-cpu.so. ggml dlopen()s the best one itself once the CPU is probed, so
        // naming "ggml-cpu" here would fail with UnsatisfiedLinkError. Same reasoning as
        // LlamaNative.ensureLoaded().
        for (lib in listOf("c++_shared", "ggml-base", "ggml", "whisper", "polycare_whisper")) {
            System.loadLibrary(lib)
        }
        loaded = true
    }

    external fun loadModel(modelPath: String): Long

    external fun freeModel(handle: Long)

    /** [samples]: mono, 16 kHz, float32 in [-1, 1]. [language]: "auto", "en", "hi", ... */
    external fun transcribe(handle: Long, samples: FloatArray, language: String, nThreads: Int): String
}
