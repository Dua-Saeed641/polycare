package org.polycare.governor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/** Reads the phone's current resources into a [DeviceSnapshot]. Cheap; safe to call on Main. */
class DeviceProbe(private val context: Context) {

    fun snapshot(): DeviceSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100

        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = Thermal.entries.getOrElse(pm.currentThermalStatus) { Thermal.SHUTDOWN }

        val stat = StatFs(context.filesDir.absolutePath)

        return DeviceSnapshot(
            totalRamMb = mem.totalMem / MB,
            availableRamMb = mem.availMem / MB,
            lowMemory = mem.lowMemory,
            batteryPct = pct,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            thermal = thermal,
            freeStorageMb = stat.availableBytes / MB,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(),
            sdkInt = Build.VERSION.SDK_INT,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
