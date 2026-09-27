package org.polycare.vector

/**
 * One Qdrant shard as seen by the app. The production implementation wraps Qdrant Edge
 * (Rust crate via UniFFI); [InMemoryVectorStore] exists for unit tests only.
 */
interface VectorStore {
    /** Embedding model every dense vector in this shard was produced by. */
    val modelId: String

    suspend fun upsert(points: List<Point>)

    suspend fun delete(ids: Collection<String>)

    suspend fun search(query: DenseQuery, filter: Filter? = null): List<ScoredPoint>

    suspend fun searchSparse(query: SparseQuery, filter: Filter? = null): List<ScoredPoint>

    suspend fun count(): Long
}

data class SparseVector(val indices: IntArray, val values: FloatArray) {
    init {
        require(indices.size == values.size) { "indices and values must be the same length" }
    }

    override fun equals(other: Any?): Boolean =
        other is SparseVector && indices.contentEquals(other.indices) && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * indices.contentHashCode() + values.contentHashCode()
}

data class Point(
    val id: String,
    val dense: FloatArray,
    val modelId: String,
    val sparse: SparseVector? = null,
    val payload: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        other is Point && id == other.id && dense.contentEquals(other.dense) && modelId == other.modelId &&
            sparse == other.sparse && payload == other.payload

    override fun hashCode(): Int = id.hashCode()
}

data class DenseQuery(val vector: FloatArray, val modelId: String, val limit: Int) {
    override fun equals(other: Any?): Boolean =
        other is DenseQuery && vector.contentEquals(other.vector) && modelId == other.modelId && limit == other.limit

    override fun hashCode(): Int = 31 * vector.contentHashCode() + limit
}

data class SparseQuery(val vector: SparseVector, val limit: Int)

/** Exact-match payload filter: every key must equal one of its allowed values. */
data class Filter(val must: Map<String, Set<String>>) {
    fun matches(payload: Map<String, String>): Boolean =
        must.all { (key, allowed) -> payload[key] in allowed }
}

data class ScoredPoint(val id: String, val score: Float, val payload: Map<String, String>)

/** Invariant 4: vectors from different embedding models are never compared or merged. */
class ModelIdMismatchException(expected: String, actual: String) :
    IllegalArgumentException("Vector from model '$actual' used with a shard of model '$expected'")
