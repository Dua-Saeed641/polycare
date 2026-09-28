package org.polycare.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.polycare.common.EventLog
import org.polycare.governor.DegradationLadder
import org.polycare.governor.DeviceProbe
import javax.inject.Inject

@HiltAndroidApp
class PolyCareApp : Application() {

    @Inject lateinit var eventLog: EventLog
    @Inject lateinit var deviceProbe: DeviceProbe

    override fun onCreate() {
        super.onCreate()
        val d = deviceProbe.snapshot()
        eventLog.record(
            EventLog.Category.APP,
            "App started",
            mapOf(
                "version" to packageManager.getPackageInfo(packageName, 0).versionName,
                "mode" to DegradationLadder.choose(d).name,
                "ramMb" to d.totalRamMb,
                "freeStorageMb" to d.freeStorageMb,
                "battery" to d.batteryPct,
                "thermal" to d.thermal.name,
                "sdk" to d.sdkInt,
            ),
        )
    }
}
