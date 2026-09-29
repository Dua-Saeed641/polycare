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
 * inbound batch twice adds nothing. The sync cursor is a plain sequence number persisted
 * atomically (write temp, rename) *after* the server acknowledges — a kill between the network
 * ack and the cursor write just re-sends ops the server already has, which it ignores.
 */
@Singleton
class OpLogStore @Inject constructor(
    @ApplicationContext context: Context,
    private val clock: HlcClock,
    private val idGen: UuidV7,
    private val events: EventLog,
) {
    private val dir = File(context.filesDir, "oplog").apply { mkdirs() }
    private val logFile = File(dir, "ops.log")
    private val cursorFile = File(dir, "cursor")
    private val reportedFile = File(dir, "reported")

    private val lock = Any()
    private val ops = ArrayList<StoredOp>()
    private val ids = HashSet<String>()
    private val reported = HashSet<String>()
    private var cursor = 0L

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
        return synchronized(lock) { add(op, persist = true) }!!
    }

    /** Applies an op that came from elsewhere. Returns false if it was already in the log. */
    fun appendInbound(op: Op): Boolean = synchronized(lock) { add(op, persist = true) } != null

    fun all(): List<StoredOp> = synchronized(lock) { ops.toList() }

    fun byEntity(entity: OpEntity): List<StoredOp> = synchronized(lock) { ops.filter { it.op.entity == entity } }

    fun pending(): List<StoredOp> = synchronized(lock) { ops.filter { it.seq > cursor } }

    fun syncedThrough(): Long = synchronized(lock) { cursor }

    /** Called only after the gateway acknowledged everything up to and including [seq]. */
    fun markSynced(seq: Long) = synchronized(lock) {
        if (seq <= cursor) return@synchronized
        cursor = seq
        writeAtomic(cursorFile, cursor.toString())
        _pending.value = ops.count { it.seq > cursor }
    }

    fun isReported(dedupKey: String): Boolean = synchronized(lock) { dedupKey in reported }

    fun reportedKeys(): Set<String> = synchronized(lock) { reported.toSet() }

    fun markReported(dedupKey: String) = synchronized(lock) {
        if (reported.add(dedupKey)) runCatching { reportedFile.appendText(dedupKey + "\n") }
        Unit
    }

    // ---------------------------------------------------------------------------------------

    private fun add(op: Op, persist: Boolean): StoredOp? {
        if (!ids.add(op.opId)) return null
        val stored = StoredOp((ops.lastOrNull()?.seq ?: 0L) + 1, op)
        if (persist) {
            val ok = runCatching {
                FileOutputStream(logFile, true).use { out ->
                    out.write((SecureBox.seal(toJson(op).toString()) + "\n").toByteArray())
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

    private fun load() = synchronized(lock) {
        var skipped = 0
        if (logFile.exists()) {
            logFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val json = SecureBox.open(line)
                val op = json?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                if (op == null) skipped++ else add(op, persist = false)
            }
        }
        cursor = runCatching { cursorFile.readText().trim().toLong() }.getOrDefault(0L)
        if (reportedFile.exists()) reportedFile.forEachLine { if (it.isNotBlank()) reported += it.trim() }
        _pending.value = ops.count { it.seq > cursor }
        events.record(
            Category.OPLOG, "Op-log opened",
            mapOf("ops" to ops.size, "pending" to _pending.value, "unreadable" to skipped),
            if (skipped > 0) Level.WARN else Level.INFO,
        )
    }

    private fun writeAtomic(target: File, text: String) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }.onFailure { events.record(Category.OPLOG, "Cursor write failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR) }
    }

    companion object {
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
