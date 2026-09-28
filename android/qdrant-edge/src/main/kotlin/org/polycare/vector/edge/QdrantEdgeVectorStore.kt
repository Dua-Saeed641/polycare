package org.polycare.vector.edge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.polycare.vector.DenseQuery
import org.polycare.vector.Filter
import org.polycare.vector.ModelIdMismatchException
import org.polycare.vector.Point
import org.polycare.vector.ScoredPoint
import org.polycare.vector.SparseQuery
import org.polycare.vector.VectorStore
import tech.qdrant.edge.ffi.AnyVariants
import tech.qdrant.edge.ffi.BinaryQuantizationEncoding
import tech.qdrant.edge.ffi.BinaryQuantizationParams
import tech.qdrant.edge.ffi.Condition
import tech.qdrant.edge.ffi.Distance
import tech.qdrant.edge.ffi.EdgeConfig
import tech.qdrant.edge.ffi.EdgeShard
import tech.qdrant.edge.ffi.FacetRequest
import tech.qdrant.edge.ffi.FieldCondition
import tech.qdrant.edge.ffi.Fusion
import tech.qdrant.edge.ffi.HnswIndexConfig
import tech.qdrant.edge.ffi.Match
import tech.qdrant.edge.ffi.Memory
import tech.qdrant.edge.ffi.Modifier
import tech.qdrant.edge.ffi.NamedVector
import tech.qdrant.edge.ffi.PayloadSchemaType
import tech.qdrant.edge.ffi.PointId
import tech.qdrant.edge.ffi.Prefetch
import tech.qdrant.edge.ffi.QuantizationConfig
import tech.qdrant.edge.ffi.Query
import tech.qdrant.edge.ffi.QueryRequest
import tech.qdrant.edge.ffi.ScalarQuantizationParams
import tech.qdrant.edge.ffi.ScalarType
import tech.qdrant.edge.ffi.ScoringQuery
import tech.qdrant.edge.ffi.ScrollRequest
import tech.qdrant.edge.ffi.SearchParams
import tech.qdrant.edge.ffi.SparseVectorDataConfig
import tech.qdrant.edge.ffi.UpdateOperation
import tech.qdrant.edge.ffi.VectorDataConfig
import tech.qdrant.edge.ffi.WithPayload
import android.system.Os
import java.io.Closeable
import java.io.File
import java.util.UUID
import tech.qdrant.edge.ffi.Filter as EdgeFilter
import tech.qdrant.edge.ffi.Point as EdgePoint
import tech.qdrant.edge.ffi.ScoredPoint as EdgeScoredPoint
import tech.qdrant.edge.ffi.SparseVector as EdgeSparseVector
import tech.qdrant.edge.ffi.Vector as EdgeVector

/** How vectors are compressed on the phone. See ARCHITECTURE.md §4 for the size budget. */
enum class EdgeQuantization(val label: String) {
    NONE("float32"),
    INT8("int8"),
    BINARY_2BIT("2-bit binary"),
}

/** One page from [QdrantEdgeVectorStore.scroll]; pass [nextOffset] back in to continue, null = done. */
data class ScrollPage(val points: List<ScoredPoint>, val nextOffset: String?)

data class EdgeStoreOptions(
    val quantization: EdgeQuantization = EdgeQuantization.INT8,
    /** HNSW graph degree. 8 keeps the graph ~half the size of the default 16. */
    val hnswM: Int = 8,
    val efConstruct: Int = 100,
    /** Candidates explored per search; higher = better recall, slower. */
    val searchEf: Int = 128,
    /** Keep original vectors and graph on disk (mmap) so RAM stays low. */
    val onDisk: Boolean = true,
    /** Payload keys to index as keywords for fast filtering. */
    val keywordIndexes: List<String> = emptyList(),
)

/**
 * [VectorStore] backed by one Qdrant Edge shard in [dir].
 *
 * Layout: dense vector `dense` (cosine), sparse vector `sparse` (IDF). Qdrant needs UUID or
 * integer ids, so string ids that are not UUIDs are mapped with a name-based UUID and the
 * original id is kept in the payload under [ID_KEY]. All FFI calls run on [dispatcher].
 */
class QdrantEdgeVectorStore private constructor(
    private val shard: EdgeShard,
    private val dir: File,
    override val modelId: String,
    private val dispatcher: CoroutineDispatcher,
    private val searchParams: SearchParams,
) : VectorStore, Closeable {

    override suspend fun upsert(points: List<Point>) = io {
        points.forEach { if (it.modelId != modelId) throw ModelIdMismatchException(modelId, it.modelId) }
        shard.update(UpdateOperation.upsertPoints(points.map { it.toEdge() }))
    }

    override suspend fun delete(ids: Collection<String>) = io {
        shard.update(UpdateOperation.deletePoints(ids.map { pointId(it) }))
    }

    override suspend fun search(query: DenseQuery, filter: Filter?): List<ScoredPoint> = io {
        if (query.modelId != modelId) throw ModelIdMismatchException(modelId, query.modelId)
        shard.query(
            QueryRequest(
                limit = query.limit.toULong(),
                query = ScoringQuery.Vector(Query.Nearest(NamedVector.Dense(query.vector.toList()), DENSE)),
                filter = filter?.toEdge(),
                withPayload = WithPayload.Bool(true),
                params = searchParams,
            ),
        ).map { it.toPolyCare() }
    }

    override suspend fun searchSparse(query: SparseQuery, filter: Filter?): List<ScoredPoint> = io {
        shard.query(
            QueryRequest(
                limit = query.limit.toULong(),
                query = ScoringQuery.Vector(Query.Nearest(NamedVector.Sparse(query.vector.toEdge()), SPARSE)),
                filter = filter?.toEdge(),
                withPayload = WithPayload.Bool(true),
            ),
        ).map { it.toPolyCare() }
    }

    /** Native hybrid search: dense + sparse prefetch fused with RRF inside Qdrant Edge. */
    override suspend fun hybrid(dense: DenseQuery, sparse: SparseQuery, limit: Int, filter: Filter?): List<ScoredPoint> = io {
        if (dense.modelId != modelId) throw ModelIdMismatchException(modelId, dense.modelId)
        val edgeFilter = filter?.toEdge()
        shard.query(
            QueryRequest(
                limit = limit.toULong(),
                query = ScoringQuery.Fusion(Fusion.Rrf(k = RRF_K, weights = null)),
                prefetches = listOf(
                    Prefetch(
                        limit = dense.limit.toULong(),
                        query = ScoringQuery.Vector(Query.Nearest(NamedVector.Dense(dense.vector.toList()), DENSE)),
                        prefetches = emptyList(),
                        filter = edgeFilter,
                        scoreThreshold = null,
                        params = searchParams,
                    ),
                    Prefetch(
                        limit = sparse.limit.toULong(),
                        query = ScoringQuery.Vector(Query.Nearest(NamedVector.Sparse(sparse.vector.toEdge()), SPARSE)),
                        prefetches = emptyList(),
                        filter = edgeFilter,
                        scoreThreshold = null,
                        params = null,
                    ),
                ),
                withPayload = WithPayload.Bool(true),
            ),
        ).map { it.toPolyCare() }
    }

    override suspend fun count(): Long = io { shard.info().pointsCount.toLong() }

    /** Distinct values of a keyword payload field and how many points hold each (for browsing UIs). */
    suspend fun facets(key: String, filter: Filter? = null, limit: Int = 50): List<Pair<String, Long>> = io {
        shard.facet(FacetRequest(key = key, limit = limit.toULong(), exact = true, filter = filter?.toEdge()))
            .hits.map { it.value to it.count.toLong() }
    }

    /** Pages through points (no vectors) for browsing UIs. [offset] is the id [scroll] last returned, or null to start. */
    suspend fun scroll(filter: Filter? = null, limit: Int = 20, offset: String? = null): ScrollPage = io {
        val response = shard.scroll(
            ScrollRequest(
                offset = offset?.let { pointId(it) },
                limit = limit.toULong(),
                filter = filter?.toEdge(),
                withPayload = WithPayload.Bool(true),
            ),
        )
        ScrollPage(
            points = response.records.map { r ->
                val json = r.payload?.let(::JSONObject) ?: JSONObject()
                val map = buildMap { json.keys().forEach { k -> if (k != ID_KEY) put(k, json.optString(k)) } }
                val originalId = json.optString(ID_KEY).ifEmpty { idToString(r.id) }
                ScoredPoint(originalId, 0f, map)
            },
            nextOffset = response.nextOffset?.let(::idToString),
        )
    }

    override suspend fun optimize() = io {
        shard.flush()
        shard.optimize()
        Unit
    }

    /**
     * Bytes actually allocated on disk. Qdrant pre-sizes WAL and page files (32 MB each)
     * as sparse files, so file lengths overstate real usage; st_blocks does not.
     */
    override fun diskBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf {
        runCatching { Os.stat(it.absolutePath).st_blocks * 512 }.getOrDefault(it.length())
    }

    override fun close() {
        runCatching { shard.flush() }
        shard.close()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    private fun Point.toEdge(): EdgePoint {
        val named = buildMap {
            put(DENSE, NamedVector.Dense(dense.toList()))
            sparse?.let { put(SPARSE, NamedVector.Sparse(it.toEdge())) }
        }
        val json = JSONObject()
        payload.forEach { (k, v) -> json.put(k, v) }
        json.put(ID_KEY, id)
        return EdgePoint(id = pointId(id), vector = EdgeVector.Named(named), payload = json.toString())
    }

    private fun org.polycare.vector.SparseVector.toEdge() =
        EdgeSparseVector(indices = indices.map { it.toUInt() }, values = values.toList())

    private fun Filter.toEdge() = EdgeFilter(
        must = must.map { (key, allowed) ->
            Condition.Field(
                FieldCondition(
                    key = key,
                    match = Match.Any(AnyVariants.Strings(allowed.toList())),
                    range = null,
                    datetimeRange = null,
                    geoBoundingBox = null,
                    geoRadius = null,
                    geoPolygon = null,
                    valuesCount = null,
                ),
            )
        },
        should = null,
        mustNot = null,
        minShould = null,
    )

    private fun EdgeScoredPoint.toPolyCare(): ScoredPoint {
        val json = payload?.let(::JSONObject) ?: JSONObject()
        val map = buildMap { json.keys().forEach { k -> if (k != ID_KEY) put(k, json.optString(k)) } }
        val originalId = json.optString(ID_KEY).ifEmpty { idToString(id) }
        return ScoredPoint(originalId, score, map)
    }

    private fun idToString(id: PointId): String = when (id) {
        is PointId.Uuid -> id.value
        is PointId.NumId -> id.value.toString()
    }

    companion object {
        const val DENSE = "dense"
        const val SPARSE = "sparse"
        const val ID_KEY = "_pid"
        private val RRF_K = org.polycare.common.PolyCareConfig.Retrieval.rrfK.toULong()

        /** FFI calls block; they get their own small pool, never Main. */
        private val EdgeDispatcher = Dispatchers.IO.limitedParallelism(4)

        fun pointId(id: String): PointId = PointId.Uuid(
            runCatching { UUID.fromString(id) }.getOrNull()?.toString()
                ?: UUID.nameUUIDFromBytes(id.toByteArray()).toString(),
        )

        /** Opens the shard in [dir], creating it with [options] if it does not exist. */
        fun open(dir: File, modelId: String, dim: Int, options: EdgeStoreOptions = EdgeStoreOptions()): QdrantEdgeVectorStore {
            val existing = File(dir, "edge_config.json").exists() || (dir.exists() && dir.list()?.isNotEmpty() == true)
            val shard = if (existing) {
                EdgeShard.load(dir.absolutePath, null)
            } else {
                dir.mkdirs()
                EdgeShard.create(dir.absolutePath, config(dim, options)).also { s ->
                    options.keywordIndexes.forEach { key ->
                        s.update(UpdateOperation.createFieldIndex(key, PayloadSchemaType.KEYWORD))
                    }
                }
            }
            return QdrantEdgeVectorStore(shard, dir, modelId, EdgeDispatcher, SearchParams(hnswEf = options.searchEf.toULong()))
        }

        private fun config(dim: Int, o: EdgeStoreOptions): EdgeConfig {
            val memory = if (o.onDisk) Memory.COLD else Memory.PINNED
            val quantization = when (o.quantization) {
                EdgeQuantization.NONE -> null
                EdgeQuantization.INT8 -> QuantizationConfig.Scalar(
                    ScalarQuantizationParams(type = ScalarType.INT8, quantile = 0.99f, memory = Memory.PINNED),
                )
                EdgeQuantization.BINARY_2BIT -> QuantizationConfig.Binary(
                    BinaryQuantizationParams(memory = Memory.PINNED, encoding = BinaryQuantizationEncoding.TWO_BITS, queryEncoding = null),
                )
            }
            return EdgeConfig(
                vectorData = mapOf(
                    DENSE to VectorDataConfig(
                        size = dim.toULong(),
                        distance = Distance.COSINE,
                        quantizationConfig = quantization,
                        multivectorConfig = null,
                        datatype = null,
                        hnswConfig = HnswIndexConfig(
                            m = o.hnswM.toULong(),
                            efConstruct = o.efConstruct.toULong(),
                            fullScanThreshold = 10_000uL,
                            maxIndexingThreads = 0uL,
                            memory = memory,
                            payloadM = null,
                        ),
                    ),
                ),
                sparseVectorData = mapOf(
                    SPARSE to SparseVectorDataConfig(fullScanThreshold = null, datatype = null, modifier = Modifier.IDF),
                ),
            )
        }
    }
}
