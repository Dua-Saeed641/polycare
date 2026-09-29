package org.polycare.app.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.polycare.app.security.SecureBox
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.Hlc
import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import org.polycare.common.sync.Op
import org.polycare.common.sync.OpEntity
import org.polycare.common.sync.StoredOp
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The op-log (invariant 1): every mutation is appended here *before* any view of the data
 * changes, and each line is fsynced, so a process kill can lose at most the op being written —
 * never an acknowledged one. Lines are encrypted (see [SecureBox]) because household ops
 * carry names; only [org.polycare.common.sync.SyncGate]-approved ops are ever decrypted for the
 * network.
 *
 * Idempotent (invariant 2): an [Op.opId] that is already in the log is ignored, so replaying an
 * inbound batch twice adds nothing. Every line records its own sequence number (`q`), so [compact]
 * can drop history without renumbering anything the sync cursor refers to. The sync cursor is
 * persisted atomically *after* the server acknowledges — a kill between the network ack and the
 * cursor write just re-sends ops the server already has, which it ignores.
 */
@Singleton
class OpLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
) {
    private val dir = File(context.filesDir, "oplog").apply { mkdirs() }
    private val logFile = File(dir, "ops.log")
    private val cursorFile = File(dir, "cursor")

    private val lock = Any()
    private val ops = ArrayList<StoredOp>()
    private val ids = HashSet<String>()
    private var cursor = 0L
    private var chain = ByteArray(32)
    /** Highest sequence number ever issued; never goes down, even when [compact] drops the tail. */
    private var maxSeq = 0L

    private val _pending = MutableStateFlow(0)
    /** Ops appended since the last acknowledged sync (includes personal ones the gate keeps local). */
    val pendingCount: StateFlow<Int> = _pending.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    /** Bumps on every append, so views rebuilt from the log know when to refresh. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        load()
    }

    /** Appends a new local op and returns it. Never throws: a failed write is logged and the op still returned. */
    fun append(entity: OpEntity, action: String, entityId: String, payload: Map<String, String>): StoredOp {
        val op = Op(idGen.next().toString(), clock.now(), entity, action, entityId, payload)
        val stored = synchronized(lock) { add(op, persist = true, seq = null) }!!
        // Something the team can use was recorded: ask for a sync once the connection is steady.
        val shareable = entity == OpEntity.SIGNAL || entity == OpEntity.TIP || entity == OpEntity.VOTE ||
            (entity == OpEntity.GAP && action == Op.UPSERT)
        if (shareable) SyncScheduler.enqueueSoon(context)
        return stored
    }

    /** Applies an op that came from elsewhere. Returns false if it was already in the log. */
    fun appendInbound(op: Op): Boolean = synchronized(lock) { add(op, persist = true, seq = null) } != null

    fun all(): List<StoredOp> = synchronized(lock) { ops.toList() }

    fun size(): Int = synchronized(lock) { ops.size }

    fun byEntity(entity: OpEntity): List<StoredOp> = synchronized(lock) { ops.filter { it.op.entity == entity } }

    fun pending(): List<StoredOp> = synchronized(lock) { ops.filter { it.seq > cursor } }

    fun syncedThrough(): Long = synchronized(lock) { cursor }

    /**
     * Called only after the gateway acknowledged everything up to and including [seq]. [chainHead]
     * is the per-device hash-chain head the gateway returned; pass null when only personal ops were
     * skipped (the head did not move). The cursor and the head are stored in **one** atomically
     * replaced file: if they were separate, a kill between two writes could leave a head that
     * already includes ops the cursor still lists as pending, and the re-signed retry would then
     * differ from what the gateway stored (it answers 409, "operation id reused").
     */
    fun markSynced(seq: Long, chainHead: ByteArray? = null) = synchronized(lock) {
        if (seq <= cursor && chainHead == null) return@synchronized
        if (seq > cursor) cursor = seq
        if (chainHead != null) chain = chainHead.copyOf()
        writeState()
        _pending.value = ops.count { it.seq > cursor }
    }

    /**
     * The gateway's per-device hash-chain head after the last acknowledged push (32 bytes; all
     * zero before the first). The next pushed op must name it as `prev_hash`.
     */
    fun chainHead(): ByteArray = synchronized(lock) { chain.copyOf() }

    /**
     * Shrinks the log without changing what it *means*. Only ops the sync cursor has already passed
     * are touched (a pending op is never altered):
     *
     *  - an entity whose last settled op is a delete is a tombstone: its whole history is dropped;
     *  - every other entity's history is folded into a single op carrying its final fields (field
     *    patches are applied), keeping the last op's id, HLC and sequence number;
     *  - de-identified signals older than [SIGNAL_KEEP_MS] are dropped (the radar window is a week).
     *
     * A rebuild from the compacted log gives the same records. Ordering makes a kill safe: the
     * cursor file (which holds the highest sequence number) is written **before** the log is
     * replaced, so a sequence number can never be reused. Returns how many ops were removed.
     */
    fun compact(nowMs: Long = clock.now().wallMs): Int = synchronized(lock) {
        if (ops.size < COMPACT_MIN_OPS) return 0
        val settled = ops.filter { it.seq <= cursor }
        val groups = LinkedHashMap<Pair<OpEntity, String>, MutableList<StoredOp>>()
        for (s in settled) groups.getOrPut(s.op.entity to s.op.entityId) { mutableListOf() } += s

        val kept = ArrayList<StoredOp>()
        for ((key, history) in groups) {
            val last = history.last()
            if (last.op.action == Op.DELETE) continue
            if (key.first == OpEntity.SIGNAL) {
                val at = last.op.payload["wallMs"]?.toLongOrNull() ?: last.op.hlc.wallMs
                if (nowMs - at > SIGNAL_KEEP_MS) continue
            }
            kept += StoredOp(last.seq, last.op.copy(payload = materialize(history.map { it.op })))
        }
        kept += ops.filter { it.seq > cursor }
        kept.sortBy { it.seq }

        val removed = ops.size - kept.size
        if (removed <= 0) return 0
        val ok = runCatching {
            writeState() // highest seq first
            val tmp = File(dir, "ops.log.tmp")
            FileOutputStream(tmp).use { out ->
                kept.forEach { out.write((line(it) + "\n").toByteArray()) }
                out.fd.sync()
            }
            if (!tmp.renameTo(logFile)) { logFile.delete(); tmp.renameTo(logFile) }
        }.isSuccess
        if (!ok) {
            events.record(Category.OPLOG, "Op-log compaction failed", emptyMap(), Level.ERROR)
            return 0
        }
        ops.clear(); ops += kept
        ids.clear(); kept.forEach { ids += it.op.opId }
        _pending.value = ops.count { it.seq > cursor }
        events.record(Category.OPLOG, "Op-log compacted", mapOf("removed" to removed, "remaining" to kept.size))
        removed
    }

    /** Applies field patches (`field`/`value`) and full upserts in order to get an entity's final fields. */
    private fun materialize(history: List<Op>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (op in history) {
            val field = op.payload["field"]
            if (field != null) out[field] = op.payload["value"].orEmpty() else out.putAll(op.payload)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------

    private fun add(op: Op, persist: Boolean, seq: Long?): StoredOp? {
        if (!ids.add(op.opId)) return null
        val number = seq ?: (maxSeq + 1)
        if (number > maxSeq) maxSeq = number
        val stored = StoredOp(number, op)
        if (persist) {
            val ok = runCatching {
                FileOutputStream(logFile, true).use { out ->
                    out.write((line(stored) + "\n").toByteArray())
                    out.fd.sync()
                }
            }.isSuccess
            if (!ok) events.record(Category.OPLOG, "Op-log write failed", mapOf("entity" to op.entity.wire), Level.ERROR)
        }
        ops += stored
        _pending.value = ops.count { it.seq > cursor }
        _revision.value = _revision.value + 1
        return stored
    }

    private fun line(s: StoredOp): String = SecureBox.seal(toJson(s.op).put("q", s.seq).toString())

    private fun load() = synchronized(lock) {
        var skipped = 0
        if (logFile.exists()) {
            logFile.forEachLine { l ->
                if (l.isBlank()) return@forEachLine
                val json = SecureBox.open(l)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val op = json?.let { runCatching { fromJson(it) }.getOrNull() }
                if (op == null) skipped++ else add(op, persist = false, seq = json.optLong("q", 0L).takeIf { it > 0 })
            }
        }
        runCatching {
            val parts = cursorFile.readText().trim().split(" ")
            cursor = parts[0].toLong()
            parts.getOrNull(1)?.let { b64 -> java.util.Base64.getDecoder().decode(b64).takeIf { it.size == 32 }?.let { chain = it } }
            parts.getOrNull(2)?.toLongOrNull()?.let { if (it > maxSeq) maxSeq = it }
        }
        _pending.value = ops.count { it.seq > cursor }
        events.record(
            Category.OPLOG, "Op-log opened",
            mapOf("ops" to ops.size, "pending" to _pending.value, "unreadable" to skipped),
            if (skipped > 0) Level.WARN else Level.INFO,
        )
    }

    /** One atomically replaced file holds the cursor, the chain head and the highest sequence number. */
    private fun writeState() {
        writeAtomic(cursorFile, "$cursor ${java.util.Base64.getEncoder().encodeToString(chain)} $maxSeq")
    }

    private fun writeAtomic(target: File, text: String) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }.onFailure { events.record(Category.OPLOG, "Cursor write failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR) }
    }

    companion object {
        /** Do not bother compacting a log this small. */
        const val COMPACT_MIN_OPS = 500
        /** Radar signals older than this are no longer useful, locally or in the cloud. */
        const val SIGNAL_KEEP_MS = 30L * 24 * 60 * 60 * 1000

        fun toJson(op: Op): JSONObject = JSONObject()
            .put("id", op.opId)
            .put("hlc", JSONObject().put("w", op.hlc.wallMs).put("l", op.hlc.logical).put("n", op.hlc.node))
            .put("e", op.entity.wire)
            .put("a", op.action)
            .put("eid", op.entityId)
            .put("p", JSONObject(op.payload as Map<*, *>))

        fun fromJson(o: JSONObject): Op {
            val h = o.getJSONObject("hlc")
            val p = o.getJSONObject("p")
            val payload = LinkedHashMap<String, String>()
            p.keys().forEach { k -> payload[k] = p.getString(k) }
            return Op(
                opId = o.getString("id"),
                hlc = Hlc(h.getLong("w"), h.getInt("l"), h.getString("n")),
                entity = OpEntity.fromWire(o.getString("e")) ?: error("unknown entity"),
                action = o.getString("a"),
                entityId = o.getString("eid"),
                payload = payload,
            )
        }
    }
}
