package org.polycare.app.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.radar.SignalsRepository
import org.polycare.app.settings.AppSettings
import org.polycare.app.team.GuidanceCard
import org.polycare.app.team.TeamAnswer
import org.polycare.app.team.TeamMemoryRepository
import org.polycare.app.team.TeamGuidanceRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.HlcClock
import org.polycare.common.PolyCareConfig
import org.polycare.common.radar.AlertLevel
import org.polycare.common.radar.RadarAlert
import org.polycare.common.sync.GateDecision
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import org.polycare.common.sync.StoredOp
import org.polycare.common.sync.SignalCodec
import org.polycare.common.sync.SyncGate
import org.polycare.common.sync.WireCodec
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** What one sync run did, in the terms the Sync screen shows. */
data class SyncStats(
    val pushedOps: Int = 0,
    val keptLocalOps: Int = 0,
    val bytesSent: Long = 0,
    /** Bytes of personal records that were decided keep-local and therefore never sent. */
    val bytesNotSent: Long = 0,
    val answersReceived: Int = 0,
    val alertsReceived: Int = 0,
    /** Team tips fetched from the cloud, and short labels of the newest (the "topics that updated"). */
    val tipsReceived: Int = 0,
    val topicsUpdated: List<String> = emptyList(),
    /** Possible contradictions between tips, filed in the Conflict Inbox. */
    val tipConflicts: Int = 0,
    val guidanceReceived: Int = 0,
    val durationMs: Long = 0,
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val atMs: Long, val stats: SyncStats) : SyncState
    data class Failed(val atMs: Long, val reason: String, val partial: SyncStats) : SyncState
}

/**
 * Edge -> gateway sync (ARCHITECTURE.md §6.3) over the gateway's signed protocol
 * (`proto/sync.proto`), safe to kill at any line (invariant 8):
 *
 *  1. Register this phone's Ed25519 key and authenticate with a challenge ([GatewayClient]).
 *  2. Walk the op-log from the persisted cursor, in order. The Sync Gate decides each op: push it
 *     or keep it on the phone.
 *  3. Each pushed op is built in its wire shape, signed, and chained: `prev_hash` is the previous
 *     pushed op's hash, starting from the head the gateway last acknowledged.
 *  4. Pushes go out in chunks. The cursor **and** the chain head advance together, only after the
 *     gateway acknowledged the chunk and returned the head we computed. Ed25519 signatures are
 *     deterministic and a signal's embedding is stored in its op, so a retried chunk is
 *     byte-identical, and the gateway treats it as a duplicate (invariant 2).
 *  5. Pull the outbreak alerts and supervisor answers.
 *
 * Personal records never reach step 3: [SyncGate] keeps them local, and the gateway rejects them
 * again on its side.
 */
@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opLog: OpLogStore,
    private val settings: AppSettings,
    private val client: GatewayClient,
    private val identity: DeviceIdentity,
    private val signals: SignalsRepository,
    private val team: TeamGuidanceRepository,
    private val teamMemory: TeamMemoryRepository,
    private val antiEntropy: AntiEntropy,
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

    val deviceId: String get() = identity.deviceId

    /** Checks the address, then registers and authenticates, so a wrong token shows up here and not mid-sync. */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            client.health()
            client.authenticate()
            "gateway reachable, this phone is registered (${identity.deviceId.take(8)}…)"
        }.recoverCatching { throw IOException(friendly(it), it) }
    }

    /**
     * @param byteBudget stop pushing once this many bytes have been sent in this run (a metered
     *   connection); what is left stays pending for the next run. Null = no limit.
     */
    suspend fun syncNow(byteBudget: Long? = null): SyncState = mutex.withLock {
        if (settings.gatewayUrl.value.isBlank()) return@withLock _state.value
        _state.value = SyncState.Running
        val started = System.nanoTime()
        var stats = SyncStats()
        try {
            stats = withContext(Dispatchers.IO) { push(byteBudget) }
            stats = withContext(Dispatchers.IO) { pull(stats) }
            val done = SyncState.Done(clock.now().wallMs, stats.copy(durationMs = (System.nanoTime() - started) / 1_000_000))
            _state.value = done
            saveLast(done)
            events.record(
                Category.SYNC, "Sync finished",
                mapOf(
                    "pushed" to stats.pushedOps, "keptLocal" to stats.keptLocalOps, "bytesSent" to stats.bytesSent,
                    "answers" to stats.answersReceived, "alerts" to stats.alertsReceived,
                ),
            )
        } catch (e: Exception) {
            _state.value = SyncState.Failed(clock.now().wallMs, friendly(e), stats)
            events.record(Category.SYNC, "Sync failed", mapOf("error" to e.javaClass.simpleName), Level.WARN)
        }
        _state.value
    }

    // ---------------------------------------------------------------------------------------

    private fun push(byteBudget: Long?): SyncStats {
        // A byte budget is only set on a metered (data-plan) connection.
        val metered = byteBudget != null
        var stats = SyncStats()
        val pending = opLog.pending()
        if (pending.isEmpty()) return stats

        val b64 = Base64.getEncoder()
        val batch = JSONArray()
        var prev = opLog.chainHead()
        var batchHead = prev
        var covered = 0L
        var batchBytes = 0
        var batchKept = 0
        var batchNotSent = 0L

        fun flush() {
            if (batch.length() == 0) {
                // Only personal ops in this stretch: the decision itself is final, the head did not move.
                if (covered > opLog.syncedThrough()) opLog.markSynced(covered)
                stats = stats.copy(keptLocalOps = stats.keptLocalOps + batchKept, bytesNotSent = stats.bytesNotSent + batchNotSent)
            } else {
                val ack = client.push(batch)
                // Debug-only failure injection: the risky window, after the gateway stored the chunk and
                // before we save the cursor and chain head. A no-op unless a Chaos switch is on.
                org.polycare.app.chaos.Chaos.afterGatewayAck()
                if (!ack.chainHead.contentEquals(batchHead)) {
                    // Never advance on a head we did not compute: the next run rebuilds from the last good one.
                    throw IOException("The gateway's chain head differs from ours. Nothing was skipped; try again.")
                }
                opLog.markSynced(covered, ack.chainHead)
                stats = stats.copy(
                    pushedOps = stats.pushedOps + batch.length(),
                    keptLocalOps = stats.keptLocalOps + batchKept,
                    bytesSent = stats.bytesSent + batchBytes,
                    bytesNotSent = stats.bytesNotSent + batchNotSent,
                )
                while (batch.length() > 0) batch.remove(0)
            }
            batchBytes = 0; batchKept = 0; batchNotSent = 0
        }

        for (stored in pending) {
            when (SyncGate.decide(stored.op, metered)) {
                is GateDecision.Defer -> {
                    // Worth sending, but not on this connection. Everything before it is settled; stop here
                    // so the cursor never skips it, and it is retried on an unmetered link.
                    flush(); return stats
                }
                is GateDecision.KeepLocal -> {
                    batchKept++
                    batchNotSent += OpLogStore.toJson(stored.op).toString().toByteArray().size
                }
                GateDecision.Push -> {
                    // Metered link: never start a chunk that would push us past the budget.
                    if (byteBudget != null && stats.bytesSent + batchBytes >= byteBudget) { flush(); return stats }
                    val wire = wireOp(stored, prev, b64)
                    batch.put(wire.json)
                    batchBytes += wire.json.toString().toByteArray().size
                    prev = wire.hash
                    batchHead = wire.hash
                }
            }
            covered = stored.seq
            if (batch.length() >= MAX_OPS_PER_PUSH || batchBytes >= PolyCareConfig.Sync.chunkBytes) flush()
        }
        flush()
        return stats
    }

    private class Wire(val json: JSONObject, val hash: ByteArray)

    /** Builds, signs and chains one op in the exact shape the gateway verifies. */
    private fun wireOp(stored: StoredOp, prev: ByteArray, b64: Base64.Encoder): Wire {
        val op = stored.op
        val (kind, payload) = when (op.entity) {
            OpEntity.SIGNAL -> "SIGNAL" to mapOf(
                "dense_f16" to op.payload.getValue("dense_f16"),
                "model_id" to op.payload.getValue("emb_model_id"),
                "simhash" to op.payload.getValue("simhash").toInt(),
                "village_code" to op.payload.getValue("village_code"),
                "week" to op.payload.getValue("week").toInt(),
                "age_band" to op.payload.getValue("age_band"),
                "sex" to op.payload.getValue("sex"),
            )
            OpEntity.GAP -> "GAP" to mapOf("query" to op.payload.getValue("query"))
            OpEntity.TIP -> "TIP" to mapOf(
                "text" to op.payload.getValue("text"),
                "dense_f16" to op.payload.getValue("dense_f16"),
                "model_id" to op.payload.getValue("emb_model_id"),
                "simhash" to op.payload.getValue("simhash").toInt(),
                "village_code" to op.payload.getValue("village_code"),
            )
            OpEntity.VOTE -> "VOTE" to mapOf("cloud_point_id" to op.payload.getValue("cloud_point_id"))
            else -> error("only signals, gaps, tips and votes have a wire shape")
        }
        // The gateway requires the HLC node to equal the device id.
        val hlc = op.hlc.copy(node = identity.deviceId)
        val signed = WireCodec.signedBytes(op.opId, identity.deviceId, hlc, kind, payload, prev)
        val signature = identity.sign(signed)
        val json = JSONObject()
            .put("op_id", op.opId)
            .put("device_id", identity.deviceId)
            .put("hlc", JSONObject().put("wall_ms", hlc.wallMs).put("logical", hlc.logical).put("node", hlc.node))
            .put("kind", kind)
            .put("payload", JSONObject(payload))
            .put("prev_hash", b64.encodeToString(prev))
            .put("signature", b64.encodeToString(signature))
        return Wire(json, WireCodec.opHash(signed, signature))
    }

    private fun pull(so: SyncStats): SyncStats {
        val alertArr = client.radarAlerts()
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

        val since = prefs.getLong(KEY_ANSWER_CURSOR, 0L)
        val o = client.answers(since)
        val ansArr = o.optJSONArray("answers") ?: JSONArray()
        val answers = List(ansArr.length()) { i ->
            val a = ansArr.getJSONObject(i)
            TeamAnswer(a.getString("id"), a.getString("question"), a.getString("answer"), a.optString("author"), a.optLong("t"))
        }
        val added = team.merge(answers)
        answers.forEach { gaps.resolve(it.question) }
        prefs.edit().putLong(KEY_ANSWER_CURSOR, o.optLong("cursor", since)).apply()

        // Optional team features: a gateway that predates them answers 404 and the rest still counts.
        val ae = optional { antiEntropy.reconcile() } ?: AntiEntropy.Result()
        optional { teamMemory.setVotes(client.votes()) }
        val cards = optional { pullGuidance() } ?: 0

        return so.copy(
            answersReceived = added, alertsReceived = alerts.size,
            tipsReceived = ae.fetched, topicsUpdated = ae.topics, tipConflicts = ae.conflicts, guidanceReceived = cards,
        )
    }

    /** Pulls guidance cards for this phone's village (and cards for every village). Returns how many were new. */
    private fun pullGuidance(): Int {
        val since = prefs.getLong(KEY_GUIDANCE_CURSOR, 0L)
        val village = settings.village.value.takeIf { it.isNotBlank() }?.let(SignalCodec::villageCode).orEmpty()
        val o = client.guidance(since, village)
        val arr = o.optJSONArray("cards") ?: JSONArray()
        val cards = List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            val v = c.optJSONArray("villages") ?: JSONArray()
            GuidanceCard(c.getString("id"), c.getString("title"), c.getString("body"), List(v.length()) { v.getString(it) }, c.optString("author"), c.optLong("t"))
        }
        val added = team.mergeCards(cards)
        prefs.edit().putLong(KEY_GUIDANCE_CURSOR, o.optLong("cursor", since)).apply()
        return added
    }

    /** Runs an optional pull step; a missing endpoint (404/405) on an older gateway is not a sync failure. */
    private fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (e: GatewayException) {
        if (e.code == 404 || e.code == 405) null else throw e
    }

    private fun friendly(e: Throwable): String = when {
        e is GatewayException && e.code == 401 -> "The gateway didn't accept this phone. Check the enrollment token."
        e is GatewayException && e.code == 409 -> "The gateway already knows this phone under a different key or history (${e.message})."
        e is GatewayException -> e.message ?: "Gateway error ${e.code}"
        e is java.net.UnknownHostException -> "Can't find the gateway. Check the address."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
            "Gateway not reachable right now. Your data is safe and will sync later."
        else -> e.message ?: "Sync failed"
    }

    private fun saveLast(done: SyncState.Done) {
        prefs.edit().putLong(KEY_LAST_MS, done.atMs)
            .putInt(KEY_LAST_PUSHED, done.stats.pushedOps).putInt(KEY_LAST_KEPT, done.stats.keptLocalOps)
            .putLong(KEY_LAST_BYTES, done.stats.bytesSent).putLong(KEY_LAST_SAVED, done.stats.bytesNotSent).apply()
    }

    private fun loadLast(): SyncState {
        val at = prefs.getLong(KEY_LAST_MS, 0L)
        if (at == 0L) return SyncState.Idle
        return SyncState.Done(
            at,
            SyncStats(
                pushedOps = prefs.getInt(KEY_LAST_PUSHED, 0), keptLocalOps = prefs.getInt(KEY_LAST_KEPT, 0),
                bytesSent = prefs.getLong(KEY_LAST_BYTES, 0), bytesNotSent = prefs.getLong(KEY_LAST_SAVED, 0),
            ),
        )
    }

    private companion object {
        /** The gateway accepts at most 256 ops per push. */
        const val MAX_OPS_PER_PUSH = 100
        const val KEY_ANSWER_CURSOR = "answer_cursor"
        const val KEY_GUIDANCE_CURSOR = "guidance_cursor"
        const val KEY_LAST_MS = "last_ms"
        const val KEY_LAST_PUSHED = "last_pushed"
        const val KEY_LAST_KEPT = "last_kept"
        const val KEY_LAST_BYTES = "last_bytes"
        const val KEY_LAST_SAVED = "last_saved"
    }
}
