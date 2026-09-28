package org.polycare.embed

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Kotlin tokenizer and embedder vs the Python reference (HF `tokenizers` + onnxruntime on the
 * same int8 model). Needs tools/models/fetch_models.sh; skipped when the assets are absent.
 */
class E5ParityTest {
    private val modelDir = File(System.getProperty("polycare.models") ?: "", "multilingual-e5-small")
    private val tokenizerFile = File(modelDir, "e5_tokenizer.bin")
    private val modelFile = File(modelDir, "model_quantized.onnx")

    private val cases = JSONObject(javaClass.getResource("/e5_reference.json")!!.readText()).getJSONArray("cases").let { arr ->
        List(arr.length()) { arr.getJSONObject(it) }
    }

    private fun tokenizer(): E5Tokenizer {
        assumeTrue(tokenizerFile.exists(), "run tools/models/fetch_models.sh")
        return tokenizerFile.inputStream().use(E5Tokenizer::load)
    }

    @Test
    fun `token ids match HF tokenizers exactly`() {
        val tokenizer = tokenizer()
        assertEquals(250_002, tokenizer.vocabSize)
        for (case in cases) {
            val expected = case.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertArrayEquals(expected, tokenizer.encode(case.getString("text")), "text: ${case.getString("text")}")
        }
    }

    @Test
    fun `embeddings match onnxruntime python`() {
        val tokenizer = tokenizer()
        assumeTrue(modelFile.exists(), "run tools/models/fetch_models.sh")
        E5Embedder.open(modelFile, tokenizer).use { embedder ->
            val texts = cases.map { it.getString("text") }
            val actual = embedder.embed(texts)
            cases.forEachIndexed { i, case ->
                val expected = case.getJSONArray("embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val cos = expected.indices.sumOf { (expected[it] * actual[i][it]).toDouble() }
                // Same ONNX Runtime version as the Python reference (tools/requirements.txt).
                assertTrue(cos > 0.9999, "cosine $cos for: ${case.getString("text")}")
            }
        }
    }

    @Test
    fun `hindi and english questions find the matching passage`() {
        val tokenizer = tokenizer()
        assumeTrue(modelFile.exists(), "run tools/models/fetch_models.sh")
        E5Embedder.open(modelFile, tokenizer).use { e ->
            val passages = e.embedPassages(
                listOf(
                    "Every pregnant woman should have at least four antenatal check-ups.",
                    "Mix one packet of ORS in one litre of clean drinking water.",
                    "Give BCG, OPV-0 and Hepatitis B vaccines at birth.",
                ),
            )
            fun best(q: String): Int {
                val v = e.embedQuery(q)
                return passages.indices.maxBy { p -> v.indices.sumOf { (v[it] * passages[p][it]).toDouble() } }
            }
            assertEquals(0, best("how many ANC visits are needed?"))
            assertEquals(1, best("ORS घोल कैसे बनाएं"))
            assertEquals(2, best("जन्म के समय कौन से टीके लगते हैं?"))
        }
    }

    @Test
    fun `sparse encoder weights repeated terms and ignores specials`() {
        val tokenizer = tokenizer()
        val sparse = SparseEncoder(tokenizer)
        val doc = sparse.encodeDocument("ORS ORS ORS zinc")
        val orsTerms = sparse.encodeQuery("ORS").indices.toSet()
        val weight = doc.indices.indices.associate { doc.indices[it] to doc.values[it] }
        val orsMin = orsTerms.minOf { weight.getValue(it) }
        val zincMax = weight.filterKeys { it !in orsTerms }.values.max()
        assertTrue(orsMin > zincMax, "repeated terms should outweigh single ones")
        assertTrue(tokenizer.bosId !in doc.indices && tokenizer.eosId !in doc.indices)
    }
}
