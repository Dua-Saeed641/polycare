package org.polycare.embed

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XLM-R SentencePiece Unigram tokenizer for multilingual-e5-small, matching HF `tokenizers`:
 * precompiled nmt_nfkc → collapse runs of spaces → Metaspace (▁, prefix, split) → Unigram
 * Viterbi per word (unknown characters scored min_score − 10, consecutive unknowns fused) →
 * `<s> … </s>`. Loaded from `e5_tokenizer.bin` (see tools/models/build_e5_assets.py).
 */
class E5Tokenizer private constructor(
    private val charsmap: PrecompiledCharsmap,
    private val pieceIds: HashMap<String, Int>,
    private val scores: FloatArray,
    val unkId: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
) {
    private val unkScore = scores.min() - UNK_PENALTY
    private val maxPieceChars = MAX_PIECE_CODEPOINTS

    val vocabSize: Int get() = scores.size

    /** Token ids including `<s>`/`</s>`, truncated to [maxLength]. */
    fun encode(text: String, maxLength: Int = MAX_LENGTH): IntArray {
        val ids = ArrayList<Int>()
        ids += bosId
        for (word in preTokenize(normalize(text))) {
            ids += viterbi(word)
            if (ids.size >= maxLength - 1) break
        }
        while (ids.size > maxLength - 1) ids.removeAt(ids.size - 1)
        ids += eosId
        return ids.toIntArray()
    }

    internal fun normalize(text: String): String = MULTI_SPACE.replace(charsmap.normalize(text), " ")

    /** Metaspace: spaces → ▁, prepend ▁, split so every word starts at a ▁. */
    internal fun preTokenize(normalized: String): List<String> {
        if (normalized.isEmpty()) return emptyList()
        var s = normalized.replace(' ', SPIECE)
        if (s[0] != SPIECE) s = SPIECE + s
        val words = ArrayList<String>()
        var start = 0
        for (i in 1 until s.length) {
            if (s[i] == SPIECE) {
                words += s.substring(start, i)
                start = i
            }
        }
        words += s.substring(start)
        return words
    }

    private fun viterbi(word: String): List<Int> {
        // Code point boundaries, so pieces never split a surrogate pair.
        val bounds = IntArray(word.codePointCount(0, word.length) + 1)
        run {
            var i = 0
            var n = 0
            while (i < word.length) {
                bounds[n++] = i
                i += Character.charCount(word.codePointAt(i))
            }
            bounds[n] = word.length
        }
        val n = bounds.size - 1
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }.also { it[0] = 0.0 }
        val bestStart = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1) { -1 }

        for (start in 0 until n) {
            if (bestScore[start] == Double.NEGATIVE_INFINITY) continue
            var hasSingle = false
            val maxEnd = minOf(n, start + maxPieceChars)
            for (end in start + 1..maxEnd) {
                val id = pieceIds[word.substring(bounds[start], bounds[end])] ?: continue
                val candidate = bestScore[start] + scores[id]
                if (bestStart[end] == -1 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestStart[end] = start
                    bestId[end] = id
                }
                if (end == start + 1) hasSingle = true
            }
            if (!hasSingle) {
                val end = start + 1
                val candidate = bestScore[start] + unkScore
                if (bestStart[end] == -1 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestStart[end] = start
                    bestId[end] = unkId
                }
            }
        }

        val reversed = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val id = bestId[pos]
            if (!(id == unkId && reversed.lastOrNull() == unkId)) reversed += id // fuse unknowns
            pos = bestStart[pos]
        }
        return reversed.asReversed()
    }

    companion object {
        const val MAX_LENGTH = 512
        private const val SPIECE = '▁'
        private const val UNK_PENALTY = 10f
        private const val MAX_PIECE_CODEPOINTS = 16
        private val MULTI_SPACE = Regex(" {2,}")

        fun load(input: InputStream): E5Tokenizer {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also(data::readFully)
            require(String(magic, Charsets.US_ASCII) == "PCTK") { "not an e5 tokenizer file" }
            val header = ByteBuffer.wrap(ByteArray(20).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN)
            val version = header.int
            require(version == 1) { "unsupported tokenizer version $version" }
            val unk = header.int
            val bos = header.int
            val eos = header.int
            val pad = header.int
            val charsmap = ByteArray(readU32(data)).also(data::readFully)
            val size = readU32(data)
            val ids = HashMap<String, Int>(size * 2)
            val scores = FloatArray(size)
            val scratch = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            for (id in 0 until size) {
                scratch.clear()
                data.readFully(scratch.array(), 0, 6)
                scores[id] = scratch.getFloat(0)
                val len = scratch.getShort(4).toInt() and 0xFFFF
                val bytes = ByteArray(len).also(data::readFully)
                if (id != unk && id != bos && id != eos && id != pad) ids[String(bytes, Charsets.UTF_8)] = id
            }
            return E5Tokenizer(PrecompiledCharsmap(charsmap), ids, scores, unk, bos, eos, pad)
        }

        private fun readU32(data: DataInputStream): Int {
            val b = ByteArray(4).also(data::readFully)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}
