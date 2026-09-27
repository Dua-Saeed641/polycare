package org.polycare.governor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DegradationLadderTest {
    private val flagship = DeviceSnapshot(
        totalRamMb = 8_000, availableRamMb = 4_000, lowMemory = false,
        batteryPct = 80, charging = false, thermal = Thermal.NONE,
        freeStorageMb = 20_000, cpuCores = 8, abis = listOf("arm64-v8a"), sdkInt = 34, model = "test",
    )

    @Test
    fun `healthy 8 GB phone runs everything`() =
        assertEquals(Rung.FULL, DegradationLadder.choose(flagship))

    @Test
    fun `6 GB class phone runs everything`() =
        assertEquals(Rung.FULL, DegradationLadder.choose(flagship.copy(totalRamMb = 5_400)))

    @Test
    fun `4 GB class phone runs lean`() =
        assertEquals(Rung.LEAN, DegradationLadder.choose(flagship.copy(totalRamMb = 3_700)))

    @Test
    fun `3 GB budget phone gets the small model`() =
        assertEquals(Rung.BASE, DegradationLadder.choose(flagship.copy(totalRamMb = 2_800)))

    @Test
    fun `2 GB phone still answers from retrieval`() =
        assertEquals(Rung.RECALL, DegradationLadder.choose(flagship.copy(totalRamMb = 2_000)))

    @Test
    fun `heat steps down`() {
        assertEquals(Rung.SINGLE, DegradationLadder.choose(flagship.copy(thermal = Thermal.MODERATE)))
        assertEquals(Rung.BASE, DegradationLadder.choose(flagship.copy(thermal = Thermal.SEVERE)))
        assertEquals(Rung.RECALL, DegradationLadder.choose(flagship.copy(thermal = Thermal.CRITICAL)))
    }

    @Test
    fun `low battery steps down unless charging`() {
        assertEquals(Rung.SINGLE, DegradationLadder.choose(flagship.copy(batteryPct = 10)))
        assertEquals(Rung.FULL, DegradationLadder.choose(flagship.copy(batteryPct = 10, charging = true)))
    }

    @Test
    fun `32-bit only phone falls back to recall`() =
        assertEquals(Rung.RECALL, DegradationLadder.choose(flagship.copy(abis = listOf("armeabi-v7a"))))
}
