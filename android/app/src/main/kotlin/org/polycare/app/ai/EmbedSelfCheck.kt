package org.polycare.app.ai

import org.json.JSONObject
import org.polycare.embed.E5Embedder

/**
 * Checks the on-device embedder against the Python reference fixture
 * (core-embed/src/test/resources/e5_reference.json) and measures query latency.
 * Used by the instrumented test and by the debug `embed_check` launch extra.
 *
 * Tokens must match exactly. Vectors cannot be bit-identical: the reference runs on x86 and the
 * phone's ARM int8 kernels round differently (measured min cosine 0.9983 on a 2406ERN9CI). What
 * retrieval needs is that every sentence keeps the same nearest neighbour, so that is checked
 * too, alongside a cosine floor.
 */
object EmbedSelfCheck {

    data class Result(
        val cases: Int,
        val tokenMismatches: List<String>,
        val minCosine: Double,
        val worstText: String,
        val neighbourAgreement: Int,
        val queryP50Ms: Double,
        val queryP95Ms: Double,
    ) {
        val passed: Boolean
            get() = tokenMismatches.isEmpty() && minCosine > MIN_COSINE && neighbourAgreement == cases
    }

    const val MIN_COSINE = 0.995

    fun run(embedder: E5Embedder, fixtureJson: String): Result {
        val cases = JSONObject(fixtureJson).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val mismatches = ArrayList<String>()
        val reference = ArrayList<FloatArray>()
        val device = ArrayList<FloatArray>()
        var minCos = 1.0
        var worst = ""
        for (case in cases) {
            val text = case.getString("text")
            val expectedIds = case.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            if (!expectedIds.contentEquals(embedder.tokenizer.encode(text))) mismatches += text

            val expected = case.getJSONArray("embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            val actual = embedder.embed(listOf(text)).single()
            reference += expected
            device += actual
            val cos = dot(expected, actual)
            if (cos < minCos) {
                minCos = cos
                worst = text
            }
        }
        val agreement = cases.indices.count { i -> nearest(reference, i) == nearest(device, i) }

        val queries = listOf(
            "how many ANC visits are needed?",
            "गर्भवती महिला को आयरन की गोली कब देनी चाहिए?",
            "newborn not feeding and has fever",
            "ORS घोल कैसे बनाएं",
        )
        repeat(3) { embedder.embedQuery(queries[0]) } // warm up
        val times = (0 until 20).map { i ->
            val t0 = System.nanoTime()
            embedder.embedQuery(queries[i % queries.size])
            (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()

        return Result(cases.size, mismatches, minCos, worst, agreement, times[10], times[18])
    }

    private fun nearest(vectors: List<FloatArray>, i: Int): Int =
        vectors.indices.filter { it != i }.maxBy { dot(vectors[i], vectors[it]) }

    private fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { (a[it] * b[it]).toDouble() }
}
