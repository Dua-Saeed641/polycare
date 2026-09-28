package org.polycare.embed

import org.polycare.common.PolyCareConfig.Retrieval
import org.polycare.vector.SparseVector

/**
 * BM25 term weights over the e5 tokenizer's pieces, for exact matches on drug names, IDs and
 * lab values. Qdrant's IDF modifier supplies the document-frequency half of BM25, so this only
 * computes the saturated term frequency. Queries use weight 1 per distinct term.
 */
class SparseEncoder(private val tokenizer: E5Tokenizer) {

    fun encodeDocument(text: String): SparseVector {
        val terms = terms(text)
        val tf = terms.groupingBy { it }.eachCount()
        val lengthNorm = 1 - Retrieval.bm25B + Retrieval.bm25B * terms.size / Retrieval.bm25AvgDocTokens
        return build(tf.mapValues { (_, n) -> n * (Retrieval.bm25K1 + 1) / (n + Retrieval.bm25K1 * lengthNorm) })
    }

    fun encodeQuery(text: String): SparseVector = build(terms(text).distinct().associateWith { 1f })

    private fun terms(text: String): List<Int> =
        tokenizer.encode(text).filter { it != tokenizer.bosId && it != tokenizer.eosId && it != tokenizer.unkId && it != tokenizer.padId }

    private fun build(weights: Map<Int, Float>): SparseVector {
        val sorted = weights.entries.sortedBy { it.key }
        return SparseVector(IntArray(sorted.size) { sorted[it].key }, FloatArray(sorted.size) { sorted[it].value })
    }
}
