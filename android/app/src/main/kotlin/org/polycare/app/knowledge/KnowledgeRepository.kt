package org.polycare.app.knowledge

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.polycare.app.ai.EmbedderProvider
import org.polycare.common.Artifact
import org.polycare.common.ArtifactVerifier
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import org.polycare.common.PolyCareConfig
import org.polycare.common.Verification
import org.polycare.embed.E5Embedder
import org.polycare.vector.DenseQuery
import org.polycare.vector.SparseQuery
import org.polycare.vector.edge.QdrantEdgeVectorStore
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class KnowledgeManifest(
    val version: String,
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    val points: Long,
    val modelId: String,
    val dim: Int,
    val builtAt: String,
    val sources: List<Source>,
) {
    data class Source(val id: String, val title: String, val lang: String, val url: String)

    companion object {
        fun parse(json: String): KnowledgeManifest {
            val o = JSONObject(json)
            val src = o.getJSONArray("sources")
            return KnowledgeManifest(
                version = o.getString("version"),
                file = o.getString("file"),
                sha256 = o.getString("sha256"),
                sizeBytes = o.getLong("size_bytes"),
                points = o.getLong("points"),
                modelId = o.getString("model_id"),
                dim = o.getInt("dim"),
                builtAt = o.optString("built_at"),
                sources = List(src.length()) { i ->
                    src.getJSONObject(i).let { Source(it.getString("id"), it.getString("title"), it.getString("lang"), it.getString("url")) }
                },
            )
        }
    }
}

data class KnowledgeHit(
    val id: String,
    val score: Float,
    val text: String,
    val title: String,
    val sourceId: String,
    val page: Int,
    val lang: String,
    /** "prose", "table", or "table-ambiguous" (several vaccines share one printed row). */
    val quality: String,
)

data class KnowledgeResult(val hits: List<KnowledgeHit>, val embedMs: Double, val searchMs: Double)

data class KnowledgeItem(
    val id: String,
    val text: String,
    val title: String,
    val sourceId: String,
    val page: Int,
    val lang: String,
    val quality: String,
)

data class KnowledgePage(val items: List<KnowledgeItem>, val nextCursor: String?)

/** Counts of installed passages per source and per language, for the Memory Inspector overview. */
data class KnowledgeStats(val bySource: List<Pair<String, Long>>, val byLang: List<Pair<String, Long>>, val ambiguous: Long)

/**
 * The cloud-owned `knowledge` shard (invariant 9): it is only ever installed whole from a
 * verified package, never edited on the phone. Until Qdrant Cloud snapshots exist (M6) packages
 * come from tools/knowledge/build_knowledge.py and arrive in `files/knowledge/incoming/`.
 *
 * Install is restart-safe: it unpacks into `<version>.tmp` and renames when complete.
 */
@Singleton
class KnowledgeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val embedders: EmbedderProvider,
    private val events: EventLog,
) {
    sealed interface State {
        data object NotInstalled : State
        data object Loading : State
        data class Ready(val manifest: KnowledgeManifest, val points: Long) : State
        data class Failed(val reason: String) : State
    }

    private val root = File(context.filesDir, "knowledge")
    private val incoming = File(root, "incoming")
    private val installed = File(root, "installed")
    private val current = File(root, "current.json")

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var store: QdrantEdgeVectorStore? = null

    /** Installs any verified incoming package, then opens the current shard. Safe to call repeatedly. */
    suspend fun open(): State = mutex.withLock {
        if (store != null) return _state.value
        _state.value = State.Loading
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                installIncoming()
                if (!current.exists()) return@runCatching State.NotInstalled
                val manifest = KnowledgeManifest.parse(current.readText())
                val dir = File(installed, manifest.version)
                val s = QdrantEdgeVectorStore.open(dir, manifest.modelId, manifest.dim)
                store = s
                val points = s.count()
                events.record(Category.KNOWLEDGE, "Knowledge opened", mapOf("version" to manifest.version, "points" to points))
                State.Ready(manifest, points)
            }.getOrElse { e ->
                events.record(Category.KNOWLEDGE, "Knowledge failed to open", mapOf("error" to e.javaClass.simpleName), Level.ERROR)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        _state.value
    }

    suspend fun search(query: String, limit: Int = PolyCareConfig.Retrieval.resultLimit): KnowledgeResult? {
        open()
        val s = store ?: return null
        val ready = embedders.get() ?: return null
        val manifest = (state.value as? State.Ready)?.manifest ?: return null
        if (manifest.modelId != ready.embedder.modelId) return null // invariant 4

        return withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val dense = ready.embedder.embedQuery(query)
            val sparse = ready.sparse.encodeQuery(query)
            val t1 = System.nanoTime()
            val hits = s.hybrid(
                DenseQuery(dense, ready.embedder.modelId, PolyCareConfig.Retrieval.prefetchLimit),
                SparseQuery(sparse, PolyCareConfig.Retrieval.prefetchLimit),
                limit,
            )
            val t2 = System.nanoTime()
            val result = KnowledgeResult(
                hits = hits.map { h ->
                    KnowledgeHit(
                        id = h.id,
                        score = h.score,
                        text = h.payload["text"].orEmpty(),
                        title = h.payload["title"].orEmpty(),
                        sourceId = h.payload["source"].orEmpty(),
                        page = h.payload["page"]?.toIntOrNull() ?: 0,
                        lang = h.payload["lang"].orEmpty(),
                        quality = h.payload["quality"].orEmpty(),
                    )
                },
                embedMs = (t1 - t0) / 1e6,
                searchMs = (t2 - t1) / 1e6,
            )
            // Metadata only: never the query text (privacy rule of the event log).
            events.record(
                Category.SEARCH,
                "Knowledge search",
                mapOf(
                    "hits" to result.hits.size,
                    "embedMs" to "%.1f".format(result.embedMs),
                    "searchMs" to "%.1f".format(result.searchMs),
                    "topSource" to result.hits.firstOrNull()?.sourceId,
                ),
            )
            result
        }
    }

    /** Counts by source and language, for the Memory Inspector overview. Null while not open. */
    suspend fun stats(): KnowledgeStats? {
        open()
        val s = store ?: return null
        return KnowledgeStats(
            bySource = s.facets("source"),
            byLang = s.facets("lang"),
            ambiguous = s.facets("quality").firstOrNull { it.first == "table-ambiguous" }?.second ?: 0,
        )
    }

    /**
     * One page of installed passages, optionally filtered by source/lang, for browsing what the
     * phone knows. Pass a page's [KnowledgePage.nextCursor] back in as [cursor] to continue.
     */
    suspend fun browse(sourceId: String? = null, lang: String? = null, cursor: String? = null, limit: Int = 20): KnowledgePage? {
        open()
        val s = store ?: return null
        val must = buildMap {
            sourceId?.let { put("source", setOf(it)) }
            lang?.let { put("lang", setOf(it)) }
        }
        val filter = if (must.isEmpty()) null else org.polycare.vector.Filter(must)
        val page = s.scroll(filter = filter, limit = limit, offset = cursor)
        return KnowledgePage(
            items = page.points.map { p ->
                KnowledgeItem(
                    id = p.id,
                    text = p.payload["text"].orEmpty(),
                    title = p.payload["title"].orEmpty(),
                    sourceId = p.payload["source"].orEmpty(),
                    page = p.payload["page"]?.toIntOrNull() ?: 0,
                    lang = p.payload["lang"].orEmpty(),
                    quality = p.payload["quality"].orEmpty(),
                )
            },
            nextCursor = page.nextOffset,
        )
    }

    private fun installIncoming() {
        val manifests = incoming.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        for (mf in manifests) {
            val manifest = runCatching { KnowledgeManifest.parse(mf.readText()) }.getOrNull()
            if (manifest == null) {
                events.record(Category.KNOWLEDGE, "Knowledge manifest unreadable", mapOf("file" to mf.name), Level.ERROR)
                mf.delete()
                continue
            }
            if (manifest.modelId != E5Embedder.MODEL_ID || manifest.dim != E5Embedder.DIM) {
                events.record(
                    Category.KNOWLEDGE, "Knowledge package rejected: different embedding model",
                    mapOf("package" to manifest.modelId, "phone" to E5Embedder.MODEL_ID), Level.ERROR,
                )
                continue
            }
            val artifact = Artifact(manifest.file, manifest.sha256, manifest.sizeBytes)
            when (val v = ArtifactVerifier.verify(incoming, artifact)) {
                Verification.Missing -> continue
                is Verification.Quarantined -> {
                    events.record(Category.KNOWLEDGE, "Knowledge package quarantined", mapOf("reason" to v.reason), Level.ERROR)
                    continue
                }
                Verification.Ok -> Unit
            }
            val t0 = System.nanoTime()
            val target = File(installed, manifest.version)
            val tmp = File(installed, manifest.version + ".tmp")
            tmp.deleteRecursively()
            unzip(File(incoming, manifest.file), tmp)
            store?.close()
            store = null
            target.deleteRecursively()
            check(tmp.renameTo(target)) { "rename failed" }
            current.writeText(mf.readText())
            File(incoming, manifest.file).delete()
            mf.delete()
            installed.listFiles()?.filter { it.name != manifest.version }?.forEach { it.deleteRecursively() }
            events.record(
                Category.KNOWLEDGE, "Knowledge installed",
                mapOf(
                    "version" to manifest.version, "points" to manifest.points, "sources" to manifest.sources.size,
                    "sizeKb" to manifest.sizeBytes / 1024, "ms" to (System.nanoTime() - t0) / 1_000_000,
                ),
            )
        }
    }

    private fun unzip(zip: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val out = File(into, entry.name)
                require(out.canonicalPath.startsWith(base)) { "zip entry outside target: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
    }
}
