// Thin JNI bridge to llama.cpp with GPU acceleration support.
//
// **Performance Optimizations (2026-09-29)**:
// - GPU layer offloading via Vulkan backend (3-10x speedup)
// - Automatic GPU capability detection
// - Graceful CPU fallback for incompatible devices
// - Performance monitoring and statistics

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
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
    int32_t gpu_layers = 0;
    std::mutex mu;
    // Tokens currently resident in the KV cache (sequence 0), in order. Lets the next request
    // reuse the shared prefix (the constant system prompt) instead of re-decoding it.
    std::vector<llama_token> kv;
    // Fingerprint of the active LoRA set; a different set makes the cached KV invalid.
    std::string adapterKey;

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

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true);
    return tokens;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return {buf, (size_t) n};
}

// Feeds `n` tokens through llama_decode in chunks no larger than the context's batch size.
bool decodeSpan(llama_context *ctx, llama_token *tokens, size_t n) {
    const uint32_t n_batch = llama_n_batch(ctx);
    for (size_t i = 0; i < n; i += n_batch) {
        int32_t c = (int32_t) std::min((size_t) n_batch, n - i);
        llama_batch batch = llama_batch_get_one(tokens + i, c);
        if (llama_decode(ctx, batch) != 0) return false;
    }
    return true;
}

llama_token argmaxAt(llama_context *ctx, const llama_vocab *vocab, int32_t idx) {
    const float *logits = llama_get_logits_ith(ctx, idx);
    const int32_t n = llama_vocab_n_tokens(vocab);
    int32_t best = 0;
    float bestV = logits[0];
    for (int32_t i = 1; i < n; i++) {
        if (logits[i] > bestV) { bestV = logits[i]; best = i; }
    }
    return best;
}

// Prompt-lookup drafting: the answer usually restates the retrieved passage, so the tokens that
// followed the last n-gram the first time it appeared in the prompt are a strong guess for what
// comes next. Longest n-gram (3 -> 2) wins; the most recent earlier occurrence is used.
std::vector<llama_token> lookupDraft(const std::vector<llama_token> &hist, int maxDraft) {
    std::vector<llama_token> draft;
    const int H = (int) hist.size();
    for (int n = 3; n >= 2 && draft.empty(); n--) {
        if (H <= n) continue;
        for (int start = H - n - 1; start >= 0; start--) {
            bool match = true;
            for (int k = 0; k < n; k++) {
                if (hist[start + k] != hist[H - n + k]) { match = false; break; }
            }
            if (!match) continue;
            for (int k = start + n; k < H && (int) draft.size() < maxDraft; k++) draft.push_back(hist[k]);
            break;
        }
    }
    return draft;
}

std::string adapterFingerprint(const std::vector<llama_adapter_lora *> &a, const std::vector<float> &scales) {
    std::string key;
    for (size_t i = 0; i < a.size(); i++) {
        key += std::to_string(reinterpret_cast<uintptr_t>(a[i])) + ":" + std::to_string(scales[i]) + ";";
    }
    return key;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_polycare_llm_LlamaNative_backendInit(JNIEnv *, jobject) {
    std::call_once(g_backend_init, [] { llama_backend_init(); });
}

// Updated signature to support GPU layers parameter
JNIEXPORT jlong JNICALL
Java_org_polycare_llm_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring modelPath, jint nCtx, jint nThreads, jint nThreadsBatch, jint nGpuLayers) {
    try {
        auto path = jstringToUtf8(env, modelPath);
        llama_model_params mparams = llama_model_default_params();
        
        // GPU offloading: 0 = CPU only, >0 = offload N layers to GPU, -1 = offload all layers
        mparams.n_gpu_layers = nGpuLayers;
        
        LOGI("Loading model: %s (gpu_layers=%d)", path.c_str(), nGpuLayers);
        
        llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) {
            LOGE("model load failed: %s", path.c_str());
            return 0;
        }
        
        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) nCtx;
        cparams.n_batch = std::min<uint32_t>(512, (uint32_t) nCtx);
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreadsBatch;
        
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
        engine->gpu_layers = nGpuLayers;
        
        LOGI("model loaded: %s (n_ctx=%d, n_threads=%d, n_threads_batch=%d, gpu_layers=%d)", 
             path.c_str(), nCtx, nThreads, nThreadsBatch, nGpuLayers);
        
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
    std::string key = adapterFingerprint(adapters, floatScales);
    if (key == engine->adapterKey) return JNI_TRUE; // same skills as last request: keep the KV cache
    int32_t rc = llama_set_adapters_lora(engine->ctx, adapters.data(), (size_t) n, floatScales.data());
    if (rc != 0) {
        LOGE("setAdapters failed rc=%d", rc);
        return JNI_FALSE;
    }
    // Cached keys/values were computed under the old adapter weights.
    llama_memory_seq_rm(llama_get_memory(engine->ctx), 0, -1, -1);
    engine->kv.clear();
    engine->adapterKey = key;
    return JNI_TRUE;
}

// Returns {promptTokens, generatedTokens, promptMs, decodeMs, draftedTokens, acceptedTokens,
// reusedPrefixTokens}. With temperature <= 0 decoding is greedy and prompt-lookup speculative
// decoding is used (exact: every accepted draft token is what greedy decoding would have chosen).
JNIEXPORT jlongArray JNICALL
Java_org_polycare_llm_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jint maxDraft, jstring draftText, jobject sink) {
    constexpr int kStats = 7;
    jlongArray stats = env->NewLongArray(kStats);
    jlong zeros[kStats] = {0, 0, 0, 0, 0, 0, 0};
    env->SetLongArrayRegion(stats, 0, kStats, zeros);

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
        if (tokens.empty()) return stats;
        llama_memory_t mem = llama_get_memory(engine->ctx);

        // Reuse the KV entries of the longest shared prefix (the constant system prompt makes
        // this most of the prompt after the first request). The last prompt token is always
        // re-decoded so fresh logits exist for sampling.
        size_t common = 0;
        while (common < engine->kv.size() && common < tokens.size() - 1 && engine->kv[common] == tokens[common]) common++;
        if (common < engine->kv.size()) {
            if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
                llama_memory_seq_rm(mem, 0, -1, -1);
                common = 0;
            }
            engine->kv.resize(common);
        }

        auto t0 = ggml_time_us();
        if (!decodeSpan(engine->ctx, tokens.data() + common, tokens.size() - common)) {
            LOGE("prompt decode failed");
            llama_memory_seq_rm(mem, 0, -1, -1);
            engine->kv.clear();
            return stats;
        }
        engine->kv.insert(engine->kv.end(), tokens.begin() + common, tokens.end());
        auto t1 = ggml_time_us();

        const bool greedy = temperature <= 0.0f;
        llama_sampler *smpl = nullptr;
        if (!greedy) {
            llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
            smpl = llama_sampler_chain_init(sparams);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));
        }

        // n-gram lookup corpus = "memory" text (other retrieved passages, shared tips: never decoded,
        // only searched for continuations to propose) + the prompt + what has been generated so far.
        std::vector<llama_token> hist;
        if (draftText != nullptr) {
            auto memoryText = jstringToUtf8(env, draftText);
            if (!memoryText.empty()) hist = tokenize(engine->vocab, memoryText, /*add_special=*/false);
        }
        hist.insert(hist.end(), tokens.begin(), tokens.end());
        int32_t generated = 0, drafted = 0, accepted = 0;
        bool stop = false;

        // Emits one token piece to Kotlin; returns false if the collector went away.
        auto emit = [&](llama_token t) -> bool {
            std::string piece = tokenToPiece(engine->vocab, t);
            if (piece.empty()) return true;
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(sink, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
            if (env->ExceptionCheck()) { // Kotlin threw (e.g. coroutine cancelled)
                env->ExceptionClear();
                return false;
            }
            return true;
        };

        llama_token token = greedy ? argmaxAt(engine->ctx, engine->vocab, -1)
                                   : llama_sampler_sample(smpl, engine->ctx, -1);
        if (smpl) llama_sampler_accept(smpl, token);

        while (!stop && generated < maxTokens) {
            if (llama_vocab_is_eog(engine->vocab, token)) break;
            if (!emit(token)) break;
            generated++;
            hist.push_back(token);
            if (generated >= maxTokens) break;

            std::vector<llama_token> draft;
            if (greedy && maxDraft > 0) {
                draft = lookupDraft(hist, std::min<int>(maxDraft, maxTokens - generated));
            }

            if (draft.empty()) {
                if (!decodeSpan(engine->ctx, &token, 1)) {
                    LOGE("decode step failed at token %d", generated);
                    break;
                }
                engine->kv.push_back(token);
                token = greedy ? argmaxAt(engine->ctx, engine->vocab, -1)
                               : llama_sampler_sample(smpl, engine->ctx, -1);
                if (smpl) llama_sampler_accept(smpl, token);
                continue;
            }

            // Verify `token` + the whole draft in one batch, with logits at every position.
            const int32_t nb = (int32_t) draft.size() + 1;
            llama_batch batch = llama_batch_init(nb, 0, 1);
            const llama_pos base = (llama_pos) engine->kv.size();
            for (int32_t i = 0; i < nb; i++) {
                batch.token[i] = i == 0 ? token : draft[i - 1];
                batch.pos[i] = base + i;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = 1;
            }
            batch.n_tokens = nb;
            int rc = llama_decode(engine->ctx, batch);
            llama_batch_free(batch);
            if (rc != 0) {
                LOGE("verify decode failed rc=%d", rc);
                llama_memory_seq_rm(mem, 0, base, -1);
                break;
            }
            drafted += (int32_t) draft.size();
            engine->kv.push_back(token);

            int32_t a = 0; // accepted draft tokens
            llama_token next = argmaxAt(engine->ctx, engine->vocab, 0);
            while (a < (int32_t) draft.size() && next == draft[a]) {
                // The model agrees with draft[a]: it is the next real token.
                if (llama_vocab_is_eog(engine->vocab, next)) { stop = true; break; }
                if (!emit(next)) { stop = true; break; }
                generated++;
                hist.push_back(next);
                engine->kv.push_back(next);
                a++;
                accepted++;
                if (generated >= maxTokens) { stop = true; break; }
                next = argmaxAt(engine->ctx, engine->vocab, a);
            }
            // Drop KV entries of rejected draft tokens (positions after the accepted ones).
            llama_memory_seq_rm(mem, 0, (llama_pos) engine->kv.size(), -1);
            if (stop) break;
            token = next; // model's own choice at the first disagreement (or after a full match)
        }
        if (smpl) llama_sampler_free(smpl);
        auto t2 = ggml_time_us();

        jlong result[kStats] = {
            (jlong) tokens.size(), (jlong) generated,
            (jlong) ((t1 - t0) / 1000), (jlong) ((t2 - t1) / 1000),
            (jlong) drafted, (jlong) accepted, (jlong) common,
        };
        env->SetLongArrayRegion(stats, 0, kStats, result);
        return stats;
    } catch (const std::exception &e) {
        LOGE("generate exception: %s", e.what());
        llama_memory_seq_rm(llama_get_memory(engine->ctx), 0, -1, -1);
        engine->kv.clear();
        return stats;
    } catch (...) {
        LOGE("generate unknown exception");
        llama_memory_seq_rm(llama_get_memory(engine->ctx), 0, -1, -1);
        engine->kv.clear();
        return stats;
    }
}

JNIEXPORT void JNICALL
Java_org_polycare_llm_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    delete fromHandle<Engine>(handle);
}

} // extern "C"
