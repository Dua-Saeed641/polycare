package org.polycare.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.radar.SignalsRepository
import org.polycare.app.settings.AppSettings
import org.polycare.app.team.TeamAnswer
import org.polycare.app.team.TeamGuidanceRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.HlcClock
import org.polycare.common.PolyCareConfig
import org.polycare.common.radar.AlertLevel
import org.polycare.common.radar.RadarAlert
import org.polycare.common.sync.GateDecision
import org.polycare.common.sync.OpEntity
import org.polycare.common.sync.SyncGate
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync run did, in the terms the Sync screen shows. */
data class SyncStats(
    val pushedOps: Int = 0,
    val keptLocalOps: Int = 0,
    val plusOnes: Int = 0,
    val bytesSent: Long = 0,
    /** Bytes that were *not* sent: personal ops kept local plus the size difference of "+1"s. */
    val bytesNotSent: Long = 0,
    val answersReceived: Int = 0,
    val alertsReceived: Int = 0,
    val durationMs: Long = 0,
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val atMs: Long, val stats: SyncStats) : SyncState
    data class Failed(val atMs: Long, val reason: String, val partial: SyncStats) : SyncState
}

/**
 * Edge → gateway sync (ARCHITECTURE.md §6.3), safe to kill at any line (invariant 8):
 *
 *  1. Walk the op-log from the persisted cursor, in order.
 *  2. The Sync Gate decides each op: push, keep local, or "+1".
 *  3. Pushes go out in chunks of at most [PolyCareConfig.Sync.chunkBytes]. The cursor is
 *     advanced **only after the gateway acknowledged a chunk**, and only past ops decided so far.
 *     A kill between the ack and the cursor write re-sends that chunk; the gateway ignores
 *     op ids it already has (invariant 2), so nothing is lost or duplicated.
 *  4. Pull supervisor answers and the cloud's outbreak alerts.
 *
 * Personal records never reach step 3: [SyncGate] marks them keep-local, and the gateway
 * rejects them again on its side.
 */
@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opLog: OpLogStore,
    private val settings: AppSettings,
    private val signals: SignalsRepository,
    private val team: TeamGuidanceRepository,
    private val gaps: GapsRepository,
    private val clock: HlcClock,
    private val events: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("polycare_sync", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<SyncState>(loadLast())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Ops waiting for the next sync (a superset of what will actually be sent). */
    val pendingOps: StateFlow<Int> = opLog.pendingCount

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = settings.gatewayUrl.value
            require(base.isNotBlank()) { "Enter the gateway address first" }
            val body = request("GET", "$base/v1/health", null)
            JSONObject(body).optString("service", "gateway") + " " + JSONObject(body).optString("version")
        }
    }

    suspend fun syncNow(): SyncState = mutex.withLock {
        val base = settings.gatewayUrl.value
        if (base.isBlank()) return@withLock _state.value
        _state.value = SyncState.Running
        val started = System.nanoTime()
        var stats = SyncStats()
        try {
            stats = withContext(Dispatchers.IO) { push(base) }
            stats = withContext(Dispatchers.IO) { pull(base, stats) }
            val done = SyncState.Done(clock.now().wallMs, stats.copy(durationMs = (System.nanoTime() - started) / 1_000_000))
            _state.value = done
            saveLast(done)
            events.record(
                Category.SYNC, "Sync finished",
                mapOf(
                    "pushed" to stats.pushedOps, "keptLocal" to stats.keptLocalOps, "plusOnes" to stats.plusOnes,
                    "bytesSent" to stats.bytesSent, "answers" to stats.answersReceived, "alerts" to stats.alertsReceived,
                ),
            )
        } catch (e: Exception) {
            val failed = SyncState.Failed(clock.now().wallMs, friendly(e), stats)
            _state.value = failed
            events.record(Category.SYNC, "Sync failed", mapOf("error" to e.javaClass.simpleName), Level.WARN)
        }
        _state.value
    }

    /**
     * Watches connectivity and syncs once a connection has stayed up for
     * [PolyCareConfig.Sync.stableWindowMs] (a phone that flaps between two towers would otherwise
     * start and abandon a sync every few seconds).
     */
    fun startAutoSync() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        var pendingJob: Job? = null
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    pendingJob?.cancel()
                    pendingJob = scope.launch {
                        delay(PolyCareConfig.Sync.stableWindowMs)
                        if (settings.autoSync.value && settings.gatewayUrl.value.isNotBlank() && opLog.pending().isNotEmpty()) {
                            syncNow()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    pendingJob?.cancel()
                }
            })
        }.onFailure { events.record(Category.SYNC, "Auto-sync watcher not started", mapOf("error" to it.javaClass.simpleName), Level.WARN) }
    }

    // ---------------------------------------------------------------------------------------

    private fun push(base: String): SyncStats {
        var stats = SyncStats()
        val pending = opLog.pending()
        if (pending.isEmpty()) return stats

        val alreadyReported = opLog.reportedKeys().toMutableSet()
        val batch = JSONArray()
        val plusOnes = JSONArray()
        val reportedThisBatch = mutableListOf<String>()
        var batchBytes = 0
        var covered = 0L
        var batchPushed = 0
        var batchPlusOnes = 0
        var batchNotSent = 0L
        var batchKept = 0

        fun flush() {
            if (batch.length() == 0 && plusOnes.length() == 0) {
                // Nothing to send in this stretch (e.g. only personal ops): the decision itself is final.
                if (covered > opLog.syncedThrough()) opLog.markSynced(covered)
                stats = stats.copy(keptLocalOps = stats.keptLocalOps + batchKept, bytesNotSent = stats.bytesNotSent + batchNotSent)
                batchKept = 0; batchNotSent = 0
                return
            }
            val body = JSONObject()
                .put("device", clock.now().node)
                .put("ops", JSONArray(batch.toString()))
                .put("plusOnes", JSONArray(plusOnes.toString()))
                .toString()
            request("POST", "$base/v1/ops", body)
            // Acknowledged: only now may the cursor move past everything decided so far.
            opLog.markSynced(covered)
            reportedThisBatch.forEach { opLog.markReported(it) }
            stats = stats.copy(
                pushedOps = stats.pushedOps + batchPushed,
                plusOnes = stats.plusOnes + batchPlusOnes,
                keptLocalOps = stats.keptLocalOps + batchKept,
                bytesSent = stats.bytesSent + body.toByteArray().size,
                bytesNotSent = stats.bytesNotSent + batchNotSent,
            )
            // Reset chunk state.
            while (batch.length() > 0) batch.remove(0)
            while (plusOnes.length() > 0) plusOnes.remove(0)
            reportedThisBatch.clear()
            batchBytes = 0; batchPushed = 0; batchPlusOnes = 0; batchNotSent = 0; batchKept = 0
        }

        for (stored in pending) {
            val op = stored.op
            val json = OpLogStore.toJson(op)
            val size = json.toString().toByteArray().size
            when (val decision = SyncGate.decide(op, alreadyReported)) {
                is GateDecision.KeepLocal -> {
                    batchKept++
                    batchNotSent += size
                }
                is GateDecision.PlusOne -> {
                    plusOnes.put(
                        JSONObject().put("dedupKey", decision.dedupKey).put("village", op.payload["village"].orEmpty())
                            .put("wallMs", op.payload["wallMs"].orEmpty()),
                    )
                    batchPlusOnes++
                    batchNotSent += (size - PLUS_ONE_BYTES).coerceAtLeast(0)
                    batchBytes += PLUS_ONE_BYTES
                }
                GateDecision.Push -> {
                    batch.put(json)
                    batchPushed++
                    batchBytes += size
                    if (op.entity == OpEntity.SIGNAL) op.payload["dedupKey"]?.let {
                        alreadyReported += it
                        reportedThisBatch += it
                    }
                }
            }
            covered = stored.seq
            if (batchBytes >= PolyCareConfig.Sync.chunkBytes) flush()
        }
        flush()
        return stats
    }

    private fun pull(base: String, so: SyncStats): SyncStats {
        val since = prefs.getLong(KEY_PULL_CURSOR, 0L)
        val body = request("GET", "$base/v1/pull?device=${clock.now().node}&since=$since", null)
        val o = JSONObject(body)

        val alertArr = o.optJSONArray("alerts") ?: JSONArray()
        val alerts = List(alertArr.length()) { i ->
            val a = alertArr.getJSONObject(i)
            val v = a.optJSONArray("villages") ?: JSONArray()
            RadarAlert(
                key = a.getString("key"),
                label = a.getString("label"),
                category = a.optString("category"),
                level = runCatching { AlertLevel.valueOf(a.getString("level").uppercase()) }.getOrDefault(AlertLevel.WATCH),
                signalCount = a.optInt("count"),
                villages = List(v.length()) { v.getString(it) },
                firstMs = a.optLong("first"),
                lastMs = a.optLong("last"),
            )
        }
        signals.setCloudAlerts(alerts)

        val ansArr = o.optJSONArray("answers") ?: JSONArray()
        val answers = List(ansArr.length()) { i ->
            val a = ansArr.getJSONObject(i)
            TeamAnswer(a.getString("id"), a.getString("question"), a.getString("answer"), a.optString("author"), a.optLong("t"))
        }
        val added = team.merge(answers)
        answers.forEach { gaps.resolve(it.question) }

        prefs.edit().putLong(KEY_PULL_CURSOR, o.optLong("cursor", since)).apply()
        return so.copy(answersReceived = added, alertsReceived = alerts.size)
    }

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-PolyCare-Device", clock.now().node)
            settings.token.value.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Gateway answered $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Can't find the gateway. Check the address."
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Gateway not reachable right now. Your data is safe and will sync later."
        else -> e.message ?: "Sync failed"
    }

    private fun saveLast(done: SyncState.Done) {
        prefs.edit().putLong(KEY_LAST_MS, done.atMs)
            .putInt(KEY_LAST_PUSHED, done.stats.pushedOps).putInt(KEY_LAST_KEPT, done.stats.keptLocalOps)
            .putInt(KEY_LAST_PLUS, done.stats.plusOnes).putLong(KEY_LAST_BYTES, done.stats.bytesSent)
            .putLong(KEY_LAST_SAVED, done.stats.bytesNotSent).apply()
    }

    private fun loadLast(): SyncState {
        val at = prefs.getLong(KEY_LAST_MS, 0L)
        if (at == 0L) return SyncState.Idle
        return SyncState.Done(
            at,
            SyncStats(
                pushedOps = prefs.getInt(KEY_LAST_PUSHED, 0), keptLocalOps = prefs.getInt(KEY_LAST_KEPT, 0),
                plusOnes = prefs.getInt(KEY_LAST_PLUS, 0), bytesSent = prefs.getLong(KEY_LAST_BYTES, 0),
                bytesNotSent = prefs.getLong(KEY_LAST_SAVED, 0),
            ),
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        /** Approximate wire size of one "+1" entry, for the bytes-saved figure. */
        const val PLUS_ONE_BYTES = 90
        const val KEY_PULL_CURSOR = "pull_cursor"
        const val KEY_LAST_MS = "last_ms"
        const val KEY_LAST_PUSHED = "last_pushed"
        const val KEY_LAST_KEPT = "last_kept"
        const val KEY_LAST_PLUS = "last_plus"
        const val KEY_LAST_BYTES = "last_bytes"
        const val KEY_LAST_SAVED = "last_saved"
    }
}
