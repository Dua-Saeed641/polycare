package org.polycare.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.polycare.app.ai.LlmProvider
import org.polycare.app.sync.SyncRepository
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
    @Inject lateinit var syncRepository: SyncRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        // Sync when a connection has been steady for a while (only if a gateway is configured).
        syncRepository.startAutoSync()
        // Load and warm the LLM in the background so the first question doesn't pay for it.
        if (DegradationLadder.choose(d) != Rung.RECALL) {
            appScope.launch { runCatching { llmProvider.get() } }
        }
    }
}
