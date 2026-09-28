// Thin JNI bridge to whisper.cpp (native/whisper.cpp, pinned tag — native/fetch-whisper-cpp.sh).
// Mirrors jni_bridge.cpp's shape: minimal surface, all logic (language choice, UI) in Kotlin.
// Shares its ggml build with llama.cpp (see CMakeLists.txt) — never assume a second ggml exists.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "PolyCareWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
jlong toHandle(void *p) { return reinterpret_cast<jlong>(p); }

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_polycare_whisper_WhisperNative_loadModel(JNIEnv *env, jobject, jstring modelPath) {
    try {
        auto path = jstringToUtf8(env, modelPath);
        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = false;
        whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
        if (!ctx) LOGE("model load failed: %s", path.c_str());
        return toHandle(ctx);
    } catch (...) {
        LOGE("loadModel exception");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_org_polycare_whisper_WhisperNative_freeModel(JNIEnv *, jobject, jlong handle) {
    if (auto *ctx = reinterpret_cast<whisper_context *>(handle)) whisper_free(ctx);
}

// samples: mono, 16 kHz, float32 in [-1, 1] (WhisperEngine converts from the recorder's PCM16).
JNIEXPORT jstring JNICALL
Java_org_polycare_whisper_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");

    try {
        jsize n = env->GetArrayLength(samples);
        std::vector<float> pcm(n);
        env->GetFloatArrayRegion(samples, 0, n, pcm.data());
        auto lang = jstringToUtf8(env, language);

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = nThreads;
        // "auto" (or "", or nullptr) makes whisper_full auto-detect the language and then
        // transcribe, all in the one call. `detect_language=true` is a DIFFERENT, detection-only
        // mode: whisper.cpp returns immediately after detecting, before transcribing anything —
        // leave it false, or "auto" silently produces zero segments (found by logging rc/n_segments
        // on a real device: rc=0, n_segments=0, no error, no crash, just no text).
        params.language = lang.c_str(); // "auto", "en", "hi", ...
        params.detect_language = false;
        params.translate = false;
        params.no_context = true;
        params.single_segment = false;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_special = false;
        params.print_timestamps = false;
        params.suppress_blank = true;

        if (whisper_full(ctx, params, pcm.data(), (int) pcm.size()) != 0) {
            LOGE("whisper_full failed");
            return env->NewStringUTF("");
        }

        std::string text;
        int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; i++) {
            const char *seg = whisper_full_get_segment_text(ctx, i);
            if (seg) text += seg;
        }
        return env->NewStringUTF(text.c_str());
    } catch (const std::exception &e) {
        LOGE("transcribe exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("transcribe unknown exception");
        return env->NewStringUTF("");
    }
}

} // extern "C"
