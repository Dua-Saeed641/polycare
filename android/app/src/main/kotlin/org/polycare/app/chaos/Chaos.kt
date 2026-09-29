package org.polycare.app.chaos

import kotlinx.coroutines.flow.MutableStateFlow
import org.polycare.governor.Rung

/**
 * Failure injection for debug builds, driven from System -> Chaos panel. Every switch is inert
 * unless [enabled] (set once at app start from the debuggable flag), so a release build can never
 * misbehave because of a stray value.
 *
 * The point is to reproduce, on demand, the failures the architecture claims to survive
 * (ARCHITECTURE.md §7): a connection that drops *after* the gateway accepted a chunk, a process
 * killed in that same window, a phone with the wrong clock, a phone too weak for the full model.
 */
object Chaos {
    @Volatile var enabled = false

    /** Every gateway call fails as if there were no network. */
    val offline = MutableStateFlow(false)

    /** After the gateway accepts a chunk, fail *before* the cursor and chain head are saved. */
    val dropAckedChunk = MutableStateFlow(false)

    /** Same window as [dropAckedChunk], but kill the whole process instead of throwing. */
    val killAfterAck = MutableStateFlow(false)

    /** Added to the clock every op is stamped with (the gateway rejects ops from the far future). */
    val clockSkewMs = MutableStateFlow(0L)

    /** Pretend the device sits on this rung of the degradation ladder. */
    val forcedRung = MutableStateFlow<Rung?>(null)

    fun rung(real: Rung): Rung = if (enabled) forcedRung.value ?: real else real

    fun now(): Long = System.currentTimeMillis() + if (enabled) clockSkewMs.value else 0L

    /** Called at the risky moment in a sync; does nothing unless a switch is on. */
    fun afterGatewayAck() {
        if (!enabled) return
        if (killAfterAck.value) android.os.Process.killProcess(android.os.Process.myPid())
        if (dropAckedChunk.value) throw java.io.IOException("chaos: connection lost after the gateway accepted the chunk")
    }

    fun beforeGatewayCall() {
        if (enabled && offline.value) throw java.net.ConnectException("chaos: offline")
    }

    fun reset() {
        offline.value = false; dropAckedChunk.value = false; killAfterAck.value = false
        clockSkewMs.value = 0L; forcedRung.value = null
    }
}
