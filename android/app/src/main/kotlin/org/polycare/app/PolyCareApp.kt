package org.polycare.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.polycare.app.ai.LlmProvider
import org.polycare.app.sync.OpLogStore
import org.polycare.app.sync.SyncScheduler
import org.polycare.common.EventLog
import org.polycare.governor.DegradationLadder
import org.polycare.governor.DeviceProbe
import org.polycare.governor.Rung
import javax.inject.Inject

@HiltAndroidApp
class PolyCareApp : Application() {

    @Inject lateinit var eventLog: EventLog
    @Inject lateinit var deviceProbe: DeviceProbe
    @Inject lateinit var llmProvider: LlmProvider
    @Inject lateinit var opLog: OpLogStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Failure injection (System -> Chaos) exists in debuggable builds only.
        org.polycare.app.chaos.Chaos.enabled = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val d = deviceProbe.snapshot()
        eventLog.record(
            EventLog.Category.APP,
            "App started",
            mapOf(
                "version" to packageManager.getPackageInfo(packageName, 0).versionName,
                "mode" to org.polycare.app.chaos.Chaos.rung(DegradationLadder.choose(d)).name,
                "ramMb" to d.totalRamMb,
                "freeStorageMb" to d.freeStorageMb,
                "battery" to d.batteryPct,
                "thermal" to d.thermal.name,
                "sdk" to d.sdkInt,
            ),
        )
        // Background sync via WorkManager: survives the app being closed and killed.
        SyncScheduler.schedule(this)
        // Fold old history so the change log stays small (a no-op until it is large).
        appScope.launch { runCatching { opLog.compact() } }
        // Load and warm the LLM in the background so the first question doesn't pay for it.
        if (org.polycare.app.chaos.Chaos.rung(DegradationLadder.choose(d)) != Rung.RECALL) {
            appScope.launch { runCatching { llmProvider.get() } }
        }
    }
}
