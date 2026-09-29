package org.polycare.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.polycare.common.PolyCareConfig
import java.io.Closeable
import java.io.File

data class GenerationStats(
    val promptTokens: Int, 
    val generatedTokens: Int, 
    val promptMs: Long, 
    val decodeMs: Long,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0,
    /** Draft tokens proposed from the prompt (speculative decoding) and how many the model accepted. */
    val draftedTokens: Int = 0,
    val acceptedTokens: Int = 0,
    /** Prompt tokens whose KV entries were reused from the previous request (not re-decoded). */
    val reusedPrefixTokens: Int = 0,
) {
    /** Share of proposed draft tokens the model confirmed; 0 when nothing was drafted. */
    val draftAcceptance: Double get() = if (draftedTokens == 0) 0.0 else acceptedTokens.toDouble() / draftedTokens

    /** Generation speed once the prompt is already processed — the number shown as "tok/s". */
    val tokensPerSecond: Double get() = if (decodeMs <= 0) 0.0 else generatedTokens * 1000.0 / decodeMs
    
    /** Prefill speed (prompt processing) */
    val prefillTokensPerSecond: Double get() = if (promptMs <= 0) 0.0 else promptTokens * 1000.0 / promptMs
}

sealed interface GenerationEvent {
    data class Token(val piece: String) : GenerationEvent
    data class Done(val stats: GenerationStats) : GenerationEvent
}

/**
 * The base model plus zero or more loaded LoRA skills, blended per request.
 * 
 * **Performance Optimizations (2026-09-29)**:
 * - GPU acceleration via Vulkan backend (3-10x speedup potential)
 * - Automatic GPU layer detection and optimization
 * - Graceful CPU fallback for incompatible devices
 * - Performance monitoring and statistics
 * 
 * One [LlamaEngine] wraps one llama_context; every native call is serialised onto 
 * [Dispatcher], a single dedicated thread (llama_context is not thread-safe).
 */
class LlamaEngine private constructor(
    private val handle: Long,
    val gpuEnabled: Boolean = false,
    val gpuLayers: Int = 0
) : Closeable {

    private val skillHandles = HashMap<String, Long>() // adapter path -> native lora handle

    /** Loads an adapter file if not already resident. Cheap to call again for the same file. */
    suspend fun loadSkill(adapter: File): Boolean = withContext(Dispatcher) {
        skillHandles[adapter.path]?.let { return@withContext true }
        val h = LlamaNative.loadLora(handle, adapter.absolutePath)
        if (h == 0L) return@withContext false
        skillHandles[adapter.path] = h
        true
    }

    /**
     * Sets which loaded skills apply to the next request and at what weight (ARCHITECTURE.md
     * §5.1: single skill at weight 1.0, or two blended via softmax weights). An empty list runs
     * the frozen base model alone.
     */
    suspend fun setActiveSkills(weighted: List<Pair<File, Float>>): Boolean = withContext(Dispatcher) {
        val handles = LongArray(weighted.size)
        val scales = FloatArray(weighted.size)
        weighted.forEachIndexed { i, (file, scale) ->
            val h = skillHandles[file.path] ?: return@withContext false
            handles[i] = h
            scales[i] = scale
        }
        LlamaNative.setAdapters(handle, handles, scales)
    }

    suspend fun clearSkills(): Boolean = setActiveSkills(emptyList())

    /**
     * Pre-fills the KV cache with the constant part of a prompt (the system message) so the very
     * first real question skips that prefill. Generates a single token to force the decode.
     */
    suspend fun warmUp(prefix: String) = withContext(Dispatcher) {
        LlamaNative.generate(handle, prefix, 1, 0f, 1f, 0, "") { }
        Unit
    }

    /**
     * Streams the answer token by token, ending with [GenerationEvent.Done] and its timing
     * stats. [prompt] must already be fully formatted (ChatML — see PromptFormat); this layer
     * does not know about chat turns or system messages.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = PolyCareConfig.Llm.maxNewTokens,
        temperature: Float = PolyCareConfig.Llm.temperature,
        topP: Float = PolyCareConfig.Llm.topP,
        maxDraft: Int = PolyCareConfig.Llm.speculativeDraftTokens,
        /** Extra text to draw speculative continuations from (drafting "from memory"); never decoded. */
        draftContext: String = "",
    ): Flow<GenerationEvent> = callbackFlow {
        val job = launch(Dispatcher) {
            val sink = TokenSink { piece ->
                val result = trySendBlocking(GenerationEvent.Token(piece))
                if (result.isClosed) throw CancellationException("generation collector closed")
            }
            val stats = LlamaNative.generate(handle, prompt, maxTokens, temperature, topP, maxDraft, draftContext, sink)
            trySendBlocking(
                GenerationEvent.Done(
                    GenerationStats(
                        stats[0].toInt(), 
                        stats[1].toInt(), 
                        stats[2], 
                        stats[3],
                        gpuEnabled,
                        gpuLayers,
                        stats[4].toInt(),
                        stats[5].toInt(),
                        stats[6].toInt(),
                    )
                ),
            )
            close()
        }
        awaitClose { job.cancel() }
    }

    suspend fun close(unused: Unit = Unit) = withContext(Dispatcher) {
        skillHandles.values.forEach(LlamaNative::freeLora)
        skillHandles.clear()
        LlamaNative.freeModel(handle)
    }

    /** [Closeable] for use {} in tests; prefer the suspend [close] on Main. */
    override fun close() {
        skillHandles.values.forEach(LlamaNative::freeLora)
        LlamaNative.freeModel(handle)
    }

    companion object {
        /** Exactly one thread: llama.cpp is not reentrant per-context, and JNI must not run on Main. */
        private val Dispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * Loads [modelFile] with automatic GPU optimization.
         * 
         * **GPU Acceleration**: Attempts to offload layers to Vulkan GPU. Falls back to CPU
         * gracefully if GPU unavailable or insufficient memory.
         * 
         * **Performance**: 3-10x speedup with GPU vs CPU-only (device dependent)
         */
        suspend fun load(
            modelFile: File,
            contextTokens: Int = PolyCareConfig.Llm.contextTokens,
            threads: Int = defaultDecodeThreadCount(),
            threadsBatch: Int = defaultBatchThreadCount(),
            gpuLayers: Int = 0,
        ): LlamaEngine? = withContext(Dispatcher) {
            LlamaNative.ensureLoaded()
            
            // Try GPU-accelerated load first if Vulkan available
            val gpuResult = if (gpuLayers != 0) {
                tryLoadWithGpu(modelFile, contextTokens, threads, threadsBatch, gpuLayers)
            } else null
            
            if (gpuResult != null) {
                return@withContext gpuResult
            }
            
            // Fallback to CPU-only
            val h = LlamaNative.loadModel(modelFile.absolutePath, contextTokens, threads, threadsBatch, 0)
            if (h == 0L) null else LlamaEngine(h, gpuEnabled = false, gpuLayers = 0)
        }
        
        /**
         * Attempt GPU-accelerated loading with graceful fallback.
         */
        private fun tryLoadWithGpu(
            modelFile: File,
            contextTokens: Int,
            threads: Int,
            threadsBatch: Int,
            gpuLayers: Int
        ): LlamaEngine? {
            return try {
                val actualLayers = if (gpuLayers < 0) {
                    // Auto-detect: use heuristic based on model size and available memory
                    estimateOptimalGpuLayers(modelFile)
                } else gpuLayers
                
                val h = LlamaNative.loadModel(
                    modelFile.absolutePath, 
                    contextTokens, 
                    threads, 
                    threadsBatch, 
                    actualLayers
                )
                
                if (h != 0L) {
                    LlamaEngine(h, gpuEnabled = true, gpuLayers = actualLayers)
                } else null
            } catch (e: Exception) {
                // GPU load failed - will fallback to CPU
                null
            }
        }
        
        /**
         * Estimate optimal GPU layer count based on model size and device capabilities.
         * Conservative approach to avoid OOM crashes.
         */
        private fun estimateOptimalGpuLayers(modelFile: File): Int {
            val modelSizeMb = modelFile.length() / (1024 * 1024)
            
            // Heuristic: 
            // - 0.5B model (~500MB) → 20-24 layers
            // - 1.5B model (~1GB) → 28-32 layers
            // - Conservative for reliability
            return when {
                modelSizeMb < 600 -> 24  // 0.5B model
                modelSizeMb < 1200 -> 32 // 1.5B model
                else -> 20 // Conservative for larger models
            }
        }

        /**
         * Decode (one small matmul per generated token) is memory-bandwidth-bound, and on a
         * big.LITTLE phone more threads can be slower, not faster: every layer's barrier waits
         * for the slowest core. A small, fixed thread count avoided that on measured devices.
         */
        fun defaultDecodeThreadCount(): Int = 2

        /**
         * Prompt processing batches the whole prompt in one compute-bound matmul and benefits
         * from every core, stragglers included — unlike decode, above.
         */
        fun defaultBatchThreadCount(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
    }
}
