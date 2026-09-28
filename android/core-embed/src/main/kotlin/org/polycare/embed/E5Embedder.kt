package org.polycare.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * multilingual-e5-small (int8 ONNX) sentence embedder: mean pooling over the last hidden state,
 * L2-normalised, 384 dimensions. e5 expects "query: " / "passage: " prefixes; use
 * [embedQuery] and [embedPassages] rather than [embed] directly.
 *
 * Blocking; call from a background dispatcher. OrtSession.run is thread-safe.
 */
class E5Embedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val tokenizer: E5Tokenizer,
) : Closeable {

    val modelId: String = MODEL_ID
    val dim: Int = DIM

    fun embedQuery(text: String): FloatArray = embed(listOf("query: $text")).first()

    fun embedPassages(texts: List<String>): List<FloatArray> = embed(texts.map { "passage: $it" })

    /**
     * Embeds already-prefixed texts one at a time. The int8 model quantises activations with a
     * range taken over the whole input tensor, so batching (even without padding) lets texts
     * nudge each other's vectors; single runs make every vector reproducible on phone and cloud.
     */
    fun embed(texts: List<String>): List<FloatArray> = texts.map { runBatch(listOf(tokenizer.encode(it))).single() }

    private fun runBatch(encoded: List<IntArray>): List<FloatArray> {
        val len = encoded.first().size
        val batch = encoded.size
        val ids = LongArray(batch * len) { tokenizer.padId.toLong() }
        val mask = LongArray(batch * len)
        encoded.forEachIndexed { b, tokens ->
            tokens.forEachIndexed { t, id ->
                ids[b * len + t] = id.toLong()
                mask[b * len + t] = 1
            }
        }
        val shape = longArrayOf(batch.toLong(), len.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(batch * len)), shape).use { typeT ->
                    session.run(mapOf("input_ids" to idsT, "attention_mask" to maskT, "token_type_ids" to typeT)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = out[0].value as Array<Array<FloatArray>>
                        return List(batch) { b -> meanPoolNormalize(hidden[b], mask, b * len, encoded[b].size) }
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    private fun meanPoolNormalize(tokens: Array<FloatArray>, mask: LongArray, maskOffset: Int, count: Int): FloatArray {
        val v = FloatArray(DIM)
        for (t in 0 until count) {
            if (mask[maskOffset + t] == 0L) continue
            val h = tokens[t]
            for (d in 0 until DIM) v[d] += h[d]
        }
        var norm = 0f
        for (d in 0 until DIM) {
            v[d] /= count
            norm += v[d] * v[d]
        }
        val inv = 1f / sqrt(norm)
        for (d in 0 until DIM) v[d] *= inv
        return v
    }

    companion object {
        /** Stamped on every vector (CLAUDE.md invariant 4). Changes if the model file changes. */
        const val MODEL_ID = "multilingual-e5-small/int8@761b726"
        const val DIM = 384

        fun open(model: File, tokenizer: E5Tokenizer, threads: Int = 4): E5Embedder {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return E5Embedder(env, env.createSession(model.absolutePath, options), tokenizer)
        }
    }
}
