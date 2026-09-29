package org.polycare.app.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.polycare.app.settings.AppSettings
import org.polycare.common.PolyCareConfig
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun sync(): SyncRepository
    fun settings(): AppSettings
}

/**
 * Background sync that survives the app being closed and the process being killed (invariant 8):
 * WorkManager persists the request, and the sync itself is restart-safe (see [SyncRepository]).
 *
 * The job runs only with a network and a battery that is not low. On a **metered** connection it
 * sends at most [PolyCareConfig.Sync.meteredBudgetBytes] per run, so a phone on a small data plan
 * is never drained by a backlog; the rest waits for the next run or an unmetered network.
 * A failed run asks WorkManager to retry with exponential back-off.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val settings = entry.settings()
        if (!settings.autoSync.value || settings.gatewayUrl.value.isBlank()) return Result.success()

        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val budget = if (cm?.isActiveNetworkMetered == true) PolyCareConfig.Sync.meteredBudgetBytes else null

        return when (entry.sync().syncNow(byteBudget = budget)) {
            is SyncState.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Schedules [SyncWorker]. All calls are cheap and idempotent. */
object SyncScheduler {
    private const val PERIODIC = "polycare-sync-periodic"
    private const val SOON = "polycare-sync-soon"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** A sync every few hours whenever the constraints hold, plus a first run soon. Call at app start. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(PolyCareConfig.Sync.periodicHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        enqueueSoon(context)
    }

    /**
     * One run as soon as the constraints hold, after the steady-connection window: called whenever
     * something shareable (a signal or a question) is recorded. Already-queued runs are kept, so a
     * burst of records causes one sync, not many.
     */
    fun enqueueSoon(context: Context) {
        val once = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(PolyCareConfig.Sync.stableWindowMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, once) }
    }
}
