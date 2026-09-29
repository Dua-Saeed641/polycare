package org.polycare.common

/**
 * Hybrid Logical Clock timestamp. All ordering in PolyCare uses this, never the wall clock.
 * Compares by [wallMs], then [logical], then [node] so that every timestamp is totally ordered.
 */
data class Hlc(val wallMs: Long, val logical: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMs, Hlc::logical, Hlc::node)

    override fun toString(): String = "$wallMs.$logical@$node"
}

class ClockDriftException(val remote: Hlc, val driftMs: Long) :
    IllegalStateException("Remote HLC $remote is ${driftMs}ms ahead of the local physical clock")

/**
 * HLC generator (Kulkarni et al.). [physicalClock] is only an input to the algorithm; callers
 * must compare [Hlc] values, not physical times.
 */
class HlcClock(
    /** This device's id. Also the `device_id` the gateway sees: an op's HLC node must equal it. */
    val node: String,
    private val physicalClock: () -> Long,
    private val maxDriftMs: Long = PolyCareConfig.Sync.maxClockDriftMs,
) {
    private var last = Hlc(0, 0, node)

    /** Timestamp for a local event (e.g. appending an op). */
    @Synchronized
    fun now(): Hlc {
        val pt = physicalClock()
        last = if (pt > last.wallMs) Hlc(pt, 0, node) else Hlc(last.wallMs, last.logical + 1, node)
        return last
    }

    /** Merge a timestamp received from a peer; the result is greater than both. */
    @Synchronized
    fun receive(remote: Hlc): Hlc {
        val pt = physicalClock()
        val drift = remote.wallMs - pt
        if (drift > maxDriftMs) throw ClockDriftException(remote, drift)
        val wall = maxOf(pt, last.wallMs, remote.wallMs)
        val logical = when {
            wall == last.wallMs && wall == remote.wallMs -> maxOf(last.logical, remote.logical) + 1
            wall == last.wallMs -> last.logical + 1
            wall == remote.wallMs -> remote.logical + 1
            else -> 0
        }
        last = Hlc(wall, logical, node)
        return last
    }
}
