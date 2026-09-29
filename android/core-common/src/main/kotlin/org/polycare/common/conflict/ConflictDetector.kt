package org.polycare.common.conflict

import org.polycare.common.Hlc

/** One version of one field of a record, as written by some device at some [hlc]. */
data class FieldVersion(val value: String, val hlc: Hlc, val author: String)

/** A field where two writers disagree and neither happened-after the other. */
data class FieldConflict(val field: String, val local: FieldVersion, val incoming: FieldVersion)

/** How a conflict was closed. Every choice is reversible because both versions are kept. */
enum class Resolution(val label: String) {
    KEEP_LOCAL("Keep mine"),
    KEEP_INCOMING("Use theirs"),
    KEEP_BOTH("Keep both"),
}

/**
 * Field-level conflict detection: a disagreement is a conflict only when the two writers were
 * concurrent. Never a silent last-write-wins on health records (CLAUDE.md, "Things to avoid").
 *
 * Two versions are *ordered* (no conflict) when they have the same value, or when the incoming
 * writer's node had already seen the local write. Without a full vector clock, "had seen" is
 * approximated conservatively: the incoming edit is treated as informed only if its HLC is
 * strictly after the local one **and** it was authored by the same device; a different device
 * writing a different value is always surfaced for review.
 */
object ConflictDetector {

    fun detect(local: Map<String, FieldVersion>, incoming: Map<String, FieldVersion>): List<FieldConflict> {
        val out = ArrayList<FieldConflict>()
        for ((field, inc) in incoming) {
            val loc = local[field] ?: continue
            if (loc.value.trim().equals(inc.value.trim(), ignoreCase = true)) continue
            val informed = inc.author == loc.author && inc.hlc > loc.hlc
            if (informed) continue
            out += FieldConflict(field, loc, inc)
        }
        return out
    }
}
