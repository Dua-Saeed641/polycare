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

data class GenerationStats(val promptTokens: Int, val generatedTokens: Int, val promptMs: Long, val decodeMs: Long) {
    /** Generation speed once the prompt is already processed — the number shown as "tok/s". */
    val tokensPerSecond: Double get() = if (decodeMs <= 0) 0.0 else generatedTokens * 1000.0 / decodeMs
}

sealed interface GenerationEvent {
    data class Token(val piece: String) : GenerationEvent
    data class Done(val stats: GenerationStats) : GenerationEvent
}

/**
 * The base model plus zero or more loaded LoRA skills, blended per request. One [LlamaEngine]
 * wraps one `llama_context`; every native call — including [loadSkill] and [setActiveSkills] —
 * is serialised onto [Dispatcher], a single dedicated thread (a `llama_context` is not safe for
 * concurrent use, and JNI calls must never run on Main).
 *
 * A skill is identified by its adapter file; loading it is a one-time cost (~9 MB, matches
 * ARCHITECTURE.md's skill size), after which [setActiveSkills] switches the blend per request
 * with no reload — the "hot-swap" M0 asks for.
 */
class LlamaEngine private constructor(private val handle: Long) : Closeable {

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
     * Streams the answer token by token, ending with [GenerationEvent.Done] and its timing
     * stats. [prompt] must already be fully formatted (ChatML — see `PromptFormat`); this layer
     * does not know about chat turns or system messages.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = PolyCareConfig.Llm.maxNewTokens,
        temperature: Float = PolyCareConfig.Llm.temperature,
        topP: Float = PolyCareConfig.Llm.topP,
    ): Flow<GenerationEvent> = callbackFlow {
        val job = launch(Dispatcher) {
            val sink = TokenSink { piece ->
                val result = trySendBlocking(GenerationEvent.Token(piece))
                // The native generate loop checks for a pending JVM exception after every
                // callback and stops cleanly if one is set — this is how a cancelled collector
                // (e.g. the user left the Ask screen) actually stops mid-generation.
                if (result.isClosed) throw CancellationException("generation collector closed")
            }
            val stats = LlamaNative.generate(handle, prompt, maxTokens, temperature, topP, sink)
            trySendBlocking(
                GenerationEvent.Done(GenerationStats(stats[0].toInt(), stats[1].toInt(), stats[2], stats[3])),
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

    /** [Closeable] for `use {}` in tests; prefer the suspend [close] on Main. */
    override fun close() {
        skillHandles.values.forEach(LlamaNative::freeLora)
        LlamaNative.freeModel(handle)
    }

    companion object {
        /** Exactly one thread: llama.cpp is not reentrant per-context, and JNI must not run on Main. */
        private val Dispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Loads [modelFile] (verify its sha256 with `ArtifactVerifier` before calling this). */
        suspend fun load(
            modelFile: File,
            contextTokens: Int = PolyCareConfig.Llm.contextTokens,
            threads: Int = defaultThreadCount(),
        ): LlamaEngine? = withContext(Dispatcher) {
            LlamaNative.ensureLoaded()
            val h = LlamaNative.loadModel(modelFile.absolutePath, contextTokens, threads)
            if (h == 0L) null else LlamaEngine(h)
        }

        /** Leaves a core free for the UI/system rather than saturating every core with decode threads. */
        fun defaultThreadCount(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
    }
}
