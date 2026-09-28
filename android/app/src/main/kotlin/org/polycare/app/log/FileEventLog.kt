package org.polycare.app.log

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Event
import org.polycare.common.EventLog.Level
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EventLog] written to logcat (tag `PolyCareEvent`) and to `files/logs/events.jsonl`, rotated
 * at [MAX_BYTES] (one previous file kept). The newest [KEEP_IN_MEMORY] events are exposed for
 * the System screen. File writes happen on a single background thread.
 */
@Singleton
class FileEventLog @Inject constructor(@ApplicationContext context: Context) : EventLog {

    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val file = File(dir, "events.jsonl")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "polycare-eventlog").apply { isDaemon = true } }

    private val _recent = MutableStateFlow(loadTail())
    val recent: StateFlow<List<Event>> = _recent.asStateFlow()

    override fun record(category: Category, message: String, fields: Map<String, Any?>, level: Level) {
        val event = Event(System.currentTimeMillis(), category, level, message, fields)
        val line = toJson(event)
        when (level) {
            Level.INFO -> Log.i(TAG, line)
            Level.WARN -> Log.w(TAG, line)
            Level.ERROR -> Log.e(TAG, line)
        }
        _recent.update { (listOf(event) + it).take(KEEP_IN_MEMORY) }
        writer.execute {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    File(dir, "events.1.jsonl").delete()
                    file.renameTo(File(dir, "events.1.jsonl"))
                }
                file.appendText(line + "\n")
            }.onFailure { Log.w(TAG, "event log write failed", it) }
        }
    }

    private fun toJson(e: Event): String = JSONObject().apply {
        put("t", e.wallMs)
        put("cat", e.category.name)
        put("lvl", e.level.name)
        put("msg", e.message)
        if (e.fields.isNotEmpty()) put("f", JSONObject(e.fields.mapValues { it.value?.toString() }))
    }.toString()

    private fun loadTail(): List<Event> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(KEEP_IN_MEMORY).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val f = o.optJSONObject("f")
                Event(
                    wallMs = o.getLong("t"),
                    category = Category.valueOf(o.getString("cat")),
                    level = Level.valueOf(o.getString("lvl")),
                    message = o.getString("msg"),
                    fields = f?.keys()?.asSequence()?.associateWith { f.optString(it) } ?: emptyMap(),
                )
            }.getOrNull()
        }.reversed()
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "PolyCareEvent"
        const val MAX_BYTES = 512 * 1024L
        const val KEEP_IN_MEMORY = 200
    }
}
