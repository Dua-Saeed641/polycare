package org.polycare.common.sync

import java.security.MessageDigest

/**
 * Semantic Merkle index (ARCHITECTURE.md §5.6): a 16-ary tree, four levels deep, over the **SimHash
 * region** of every team-visible tip. Because SimHash puts similar meanings in the same prefix,
 * two devices that disagree about "dengue guidance" disagree in one small subtree, and finding it
 * takes a handful of hash comparisons instead of exchanging every op id.
 *
 * The gateway (`cloud/gateway/app/merkle.py`) implements exactly the same rules; the two must never
 * drift or every comparison will report a difference:
 *
 *  - `region(simhash)` = the 16-bit value as 4 lowercase hex digits (`0x0a3f` -> `"0a3f"`).
 *  - `EMPTY` = `sha256("empty")` as lowercase hex; any subtree containing no ops hashes to it.
 *  - leaf (a 4-digit region): if there are no ops, `EMPTY`; otherwise `sha256("leaf|" + join("\n", sorted(opId + "@" + wall + "." + logical)))`.
 *  - inner (a prefix of 0-3 digits): the 16 child hashes for digits `0..f`; if all are `EMPTY`, `EMPTY`;
 *    otherwise `sha256("node|" + join(",", child hashes))`.
 */
class SemanticMerkle(entries: List<Entry>) {

    data class Entry(val opId: String, val simhash: Int, val wallMs: Long, val logical: Int)

    private val byRegion: Map<String, List<Entry>> = entries.groupBy { region(it.simhash) }
    private val memo = HashMap<String, String>()

    /** The 16 child hashes of [prefix] (0-3 hex digits), for digits `0..f` in order. */
    fun children(prefix: String): List<String> {
        require(prefix.length in 0..3) { "prefix must be 0-3 digits" }
        return HEX.map { hash(prefix + it) }
    }

    /** Op ids in a 4-digit [region], sorted. */
    fun leafOps(region: String): List<String> {
        require(region.length == 4) { "region must be 4 digits" }
        return (byRegion[region] ?: emptyList()).map { it.opId }.sorted()
    }

    private fun hash(prefix: String): String = memo.getOrPut(prefix) {
        if (prefix.length == 4) leaf(prefix) else inner(prefix)
    }

    private fun leaf(region: String): String {
        val ops = byRegion[region] ?: return EMPTY
        val lines = ops.map { "${it.opId}@${it.wallMs}.${it.logical}" }.sorted()
        return sha256Hex("leaf|" + lines.joinToString("\n"))
    }

    private fun inner(prefix: String): String {
        val kids = HEX.map { hash(prefix + it) }
        return if (kids.all { it == EMPTY }) EMPTY else sha256Hex("node|" + kids.joinToString(","))
    }

    companion object {
        private val HEX = "0123456789abcdef".map { it.toString() }

        val EMPTY: String = sha256Hex("empty")

        fun region(simhash: Int): String = "%04x".format(simhash and 0xFFFF)

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
