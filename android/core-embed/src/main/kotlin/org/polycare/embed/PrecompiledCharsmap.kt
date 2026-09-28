package org.polycare.embed

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator

/**
 * SentencePiece's precompiled normalizer (`nmt_nfkc` charsmap): a Darts double-array trie over
 * UTF-8 bytes plus a blob of NUL-terminated replacement strings. This is a direct port of the
 * `spm_precompiled` crate used by HF `tokenizers`, including its grapheme quirk, so the phone
 * normalizes exactly like the Python reference.
 */
class PrecompiledCharsmap(bytes: ByteArray) {
    private val trie: IntArray
    private val normalized: ByteArray

    init {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val trieSize = buf.int
        trie = IntArray(trieSize / 4) { buf.int }
        normalized = bytes.copyOfRange(4 + trieSize, bytes.size)
    }

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val whole = if (grapheme.toByteArray(Charsets.UTF_8).size < 6) transform(grapheme) else null
            if (whole != null) {
                out.append(whole)
            } else {
                var i = 0
                while (i < grapheme.length) {
                    val cp = grapheme.codePointAt(i)
                    val ch = String(Character.toChars(cp))
                    out.append(transform(ch) ?: ch)
                    i += Character.charCount(cp)
                }
            }
            start = end
            end = graphemes.next()
        }
        return out.toString()
    }

    /** Replacement for the shortest trie prefix of [chunk], as upstream does; null if none. */
    private fun transform(chunk: String): String? {
        val index = firstPrefixValue(chunk.toByteArray(Charsets.UTF_8)) ?: return null
        var end = index
        while (end < normalized.size && normalized[end] != 0.toByte()) end++
        return String(normalized, index, end - index, Charsets.UTF_8)
    }

    private fun firstPrefixValue(key: ByteArray): Int? {
        var nodePos = 0
        var unit = trie[0]
        nodePos = nodePos xor offset(unit)
        for (b in key) {
            val c = b.toInt() and 0xFF
            if (c == 0) break
            nodePos = nodePos xor c
            if (nodePos !in trie.indices) return null
            unit = trie[nodePos]
            if (label(unit) != c) return null
            nodePos = nodePos xor offset(unit)
            if (hasLeaf(unit)) return value(trie[nodePos])
        }
        return null
    }

    private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)
    private fun label(unit: Int) = unit and (0x80000000.toInt() or 0xFF)
    private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
    private fun value(unit: Int) = unit and 0x7FFFFFFF
}
