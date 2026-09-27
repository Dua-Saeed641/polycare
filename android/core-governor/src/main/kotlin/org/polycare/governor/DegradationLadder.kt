package org.polycare.governor

import org.polycare.common.PolyCareConfig.Governor

/** Invariant 6: every inference path respects the current rung. The app always answers. */
enum class Rung(val label: String, val description: String) {
    FULL("Full", "1.5B model, two blended skills, speculative decoding"),
    LEAN("Lean", "1.5B model, two skills, shorter context"),
    SINGLE("Single skill", "1.5B model, one skill"),
    BASE("Base", "Small model, no skills"),
    RECALL("Recall", "No LLM: shows the best protocol passages"),
}

/** Mirrors PowerManager.THERMAL_STATUS_* so the ladder stays testable without Android. */
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class DeviceSnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowMemory: Boolean,
    val batteryPct: Int,
    val charging: Boolean,
    val thermal: Thermal,
    val freeStorageMb: Long,
    val cpuCores: Int,
    val abis: List<String>,
    val sdkInt: Int,
    val model: String,
)

object DegradationLadder {

    fun choose(d: DeviceSnapshot): Rung {
        val lowBattery = d.batteryPct < Governor.lowBatteryPct && !d.charging
        return when {
            !supportsArm64(d) -> Rung.RECALL
            d.thermal >= Thermal.CRITICAL || d.totalRamMb < Governor.baseRamMb -> Rung.RECALL
            d.thermal == Thermal.SEVERE || d.totalRamMb < Governor.leanRamMb || d.lowMemory -> Rung.BASE
            d.thermal == Thermal.MODERATE || lowBattery -> Rung.SINGLE
            d.totalRamMb < Governor.fullRamMb -> Rung.LEAN
            else -> Rung.FULL
        }
    }

    /** llama.cpp, whisper.cpp and Qdrant Edge are built for arm64-v8a only. */
    fun supportsArm64(d: DeviceSnapshot): Boolean = "arm64-v8a" in d.abis

    fun canHoldFullKnowledgeSlice(d: DeviceSnapshot): Boolean = d.freeStorageMb >= Governor.fullStorageMb
}
