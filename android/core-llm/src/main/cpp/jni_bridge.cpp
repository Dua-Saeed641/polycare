// Thin JNI bridge to llama.cpp (native/llama.cpp, pinned tag — see native/fetch-llama-cpp.sh).
//
// Deliberately minimal: tokenize, decode a prompt, sample tokens one at a time, stream each
// piece back to Kotlin through a callback. Prompt formatting (ChatML), skill routing/blending
// weights, and everything else stays in Kotlin (org.polycare.llm.LlamaEngine) — this file only
// does what must run in C++ to call llama.cpp. Every entry point catches all exceptions:
// invariant 5 ("never crash") applies here as much as anywhere else.

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "ggml.h"
#include "llama.h"

#define LOG_TAG "PolyCareLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    // llama_context is not thread-safe for concurrent calls; Kotlin already serialises through
    // a single-thread dispatcher (LlamaEngine.Dispatcher), this is a cheap extra guard.
    std::mutex mu;

    ~Engine() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::once_flag g_backend_init;

jlong toHandle(void *p) { return reinterpret_cast<jlong>(p); }

template<typename T>
T *fromHandle(jlong h) { return reinterpret_cast<T *>(h); }

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// llama_tokenize needs a size query pass then an allocation pass (its standard usage pattern).
std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true);
    return tokens;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return {}; // truncated; a 256-byte piece would be unusual and this must never crash
    return {buf, (size_t) n};
}

// Feeds `tokens` through llama_decode in chunks no larger than the context's batch size.
bool decodeAll(llama_context *ctx, std::vector<llama_token> &tokens) {
    const uint32_t n_batch = llama_n_batch(ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int32_t n = (int32_t) std::min((size_t) n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(ctx, batch) != 0) return false;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_polycare_llm_LlamaNative_backendInit(JNIEnv *, jobject) {
    std::call_once(g_backend_init, [] { llama_backend_init(); });
}

JNIEXPORT jlong JNICALL
Java_org_polycare_llm_LlamaNative_loadModel(JNIEnv *env, jobject, jstring modelPath, jint nCtx, jint nThreads) {
    try {
        auto path = jstringToUtf8(env, modelPath);
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0; // CPU only (M0 goal); GPU offload is a later optimisation, not correctness
        llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) {
            LOGE("model load failed: %s", path.c_str());
            return 0;
        }
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) nCtx;
        cparams.n_batch = std::min<uint32_t>(512, (uint32_t) nCtx);
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreads;
        llama_context *ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("context init failed");
            llama_model_free(model);
            return 0;
        }
        auto *engine = new Engine();
        engine->model = model;
        engine->ctx = ctx;
        engine->vocab = llama_model_get_vocab(model);
        LOGI("model loaded: %s (n_ctx=%d, n_threads=%d)", path.c_str(), nCtx, nThreads);
        return toHandle(engine);
    } catch (const std::exception &e) {
        LOGE("loadModel exception: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("loadModel unknown exception");
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_org_polycare_llm_LlamaNative_loadLora(JNIEnv *env, jobject, jlong handle, jstring path) {
    auto *engine = fromHandle<Engine>(handle);
    if (!engine) return 0;
    try {
        auto p = jstringToUtf8(env, path);
        llama_adapter_lora *adapter = llama_adapter_lora_init(engine->model, p.c_str());
        if (!adapter) LOGE("lora load failed: %s", p.c_str());
        return toHandle(adapter);
    } catch (...) {
        LOGE("loadLora exception");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_org_polycare_llm_LlamaNative_freeLora(JNIEnv *, jobject, jlong loraHandle) {
    if (auto *adapter = fromHandle<llama_adapter_lora>(loraHandle)) {
        llama_adapter_lora_free(adapter);
    }
}

JNIEXPORT jboolean JNICALL
Java_org_polycare_llm_LlamaNative_setAdapters(JNIEnv *env, jobject, jlong handle, jlongArray loraHandles, jfloatArray scales) {
    auto *engine = fromHandle<Engine>(handle);
    if (!engine) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(engine->mu);

    jsize n = env->GetArrayLength(loraHandles);
    std::vector<jlong> handles(n);
    std::vector<jfloat> scaleValues(n);
    env->GetLongArrayRegion(loraHandles, 0, n, handles.data());
    env->GetFloatArrayRegion(scales, 0, n, scaleValues.data());

    std::vector<llama_adapter_lora *> adapters(n);
    std::vector<float> floatScales(n);
    for (jsize i = 0; i < n; i++) {
        adapters[i] = fromHandle<llama_adapter_lora>(handles[i]);
        floatScales[i] = scaleValues[i];
    }
    int32_t rc = llama_set_adapters_lora(engine->ctx, adapters.data(), (size_t) n, floatScales.data());
    if (rc != 0) LOGE("setAdapters failed rc=%d", rc);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// Returns {promptTokens, generatedTokens, promptMs, decodeMs} for LlamaEngine to compute tok/s.
JNIEXPORT jlongArray JNICALL
Java_org_polycare_llm_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jobject sink) {
    jlongArray stats = env->NewLongArray(4);
    jlong zeros[4] = {0, 0, 0, 0};
    env->SetLongArrayRegion(stats, 0, 4, zeros);

    auto *engine = fromHandle<Engine>(handle);
    if (!engine) return stats;
    std::lock_guard<std::mutex> lock(engine->mu);

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(sinkClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("TokenSink.onToken(String) not found");
        return stats;
    }

    try {
        auto promptText = jstringToUtf8(env, prompt);
        auto tokens = tokenize(engine->vocab, promptText, /*add_special=*/true);

        // A fresh sequence per request: no cross-question context carried in the KV cache yet
        // (M2 doesn't do multi-turn chat memory), so start from a clean cache every time.
        llama_memory_seq_rm(llama_get_memory(engine->ctx), 0, -1, -1);

        auto t0 = ggml_time_us();
        if (!decodeAll(engine->ctx, tokens)) {
            LOGE("prompt decode failed");
            return stats;
        }
        auto t1 = ggml_time_us();

        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        llama_sampler *smpl = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

        int32_t generated = 0;
        llama_token token;
        for (; generated < maxTokens; generated++) {
            token = llama_sampler_sample(smpl, engine->ctx, -1);
            llama_sampler_accept(smpl, token);
            if (llama_vocab_is_eog(engine->vocab, token)) break;

            std::string piece = tokenToPiece(engine->vocab, token);
            if (!piece.empty()) {
                jstring jpiece = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(sink, onToken, jpiece);
                env->DeleteLocalRef(jpiece);
                if (env->ExceptionCheck()) { // the Kotlin side threw (e.g. coroutine cancelled)
                    env->ExceptionClear();
                    break;
                }
            }

            std::vector<llama_token> next = {token};
            if (!decodeAll(engine->ctx, next)) {
                LOGE("decode step failed at token %d", generated);
                break;
            }
        }
        llama_sampler_free(smpl);
        auto t2 = ggml_time_us();

        jlong result[4] = {
            (jlong) tokens.size(), (jlong) generated,
            (jlong) ((t1 - t0) / 1000), (jlong) ((t2 - t1) / 1000),
        };
        env->SetLongArrayRegion(stats, 0, 4, result);
        return stats;
    } catch (const std::exception &e) {
        LOGE("generate exception: %s", e.what());
        return stats;
    } catch (...) {
        LOGE("generate unknown exception");
        return stats;
    }
}

JNIEXPORT void JNICALL
Java_org_polycare_llm_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    delete fromHandle<Engine>(handle);
}

} // extern "C"
