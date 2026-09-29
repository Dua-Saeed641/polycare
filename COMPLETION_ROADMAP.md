# PolyCare - Completion & Optimization Roadmap
# Current State: Well-architected foundation, needs performance + features

## CURRENT STATUS (as of 2026-09-29)

### ✅ What's Working Well
- Solid Kotlin/Compose architecture with Hilt DI
- Qdrant Edge integrated (hybrid search, 10k points: 6.9ms p50)
- Embedder optimized (e5-small int8, 9.6ms p50)
- LLM functional (Qwen2.5-0.5B, 17.5 tok/s)
- LoRA skill routing implemented and verified
- OCR working (ML Kit, English + Devanagari)
- Voice input (whisper.cpp, English verified, Hindi working)
- Households, visits, due list with persistence
- Professional UI with brand consistency

### ⚠️ Performance Gaps (vs Industry Standard)
- **LLM Speed**: 17.5 tok/s (need 50-100+ tok/s)
  - Industry leaders: 50-150 tok/s on mobile
  - Gap: 3-8x slower than target
- **No GPU acceleration** (Vulkan backend not enabled)
- **Sequential pipelines** (embed then search, not parallel)
- **No caching** (repeated queries re-compute everything)

### ⚠️ Missing Features (M4-M10)
- Op-log persistence architecture
- Sync engine with Qdrant Cloud
- Conflict resolution UI
- 1M point optimization
- Cloud gateway & knowledge slicing
- Outbreak radar clustering

## HIGH-IMPACT OPTIMIZATIONS (Immediate)

### 1. GPU Acceleration via Vulkan [3-10x speedup]
**Problem**: CPU-only inference, no GPU utilization
**Solution**:
\\\cmake
# In core-llm/src/main/cpp/CMakeLists.txt
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
\\\
**Implementation**:
- Enable GGML_VULKAN in llama.cpp build
- Add GPU layer offloading (auto-detect optimal count)
- Implement graceful CPU fallback
- Test on both phones (Xiaomi, Realme)
**Expected Result**: 50-150 tok/s (vs 17.5 current)

### 2. Async Pipeline Optimization [2-3x throughput]
**Problem**: Sequential embed→search→generate pipeline
**Solution**:
\\\kotlin
// Parallel embedding + search
coroutineScope {
    val embeddingDeferred = async(Dispatchers.Default) { embedder.embedQuery(query) }
    val cachedResults = async { cache.get(query) }
    // ... combine results
}
\\\
**Implementation**:
- Coroutine-based parallel execution
- Pipeline embedding with search prefetch
- Batch processing for multiple queries
**Expected Result**: <50ms first query, <10ms cached

### 3. Multi-Tier Caching [Instant repeated queries]
**Problem**: Every query re-computes embedding + search
**Solution**:
\\\kotlin
class QueryCache {
    private val embeddingCache = LruCache<String, FloatArray>(100)
    private val resultCache = LruCache<String, KnowledgeResult>(50)
    // Persistent disk cache for common queries
}
\\\
**Implementation**:
- LRU cache for embeddings (memory)
- Result cache with TTL
- Persistent common-query cache (disk)
**Expected Result**: Instant response for repeated queries

### 4. CPU Optimizations [20-30% boost]
**Problem**: Generic CPU build, no runtime dispatch
**Solution**:
\\\cmake
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "Runtime dispatch" FORCE)
\\\
**Implementation**:
- Build all CPU variants (NEON, dotprod, i8mm)
- Runtime ISA detection and dispatch
- Optimized thread scheduling per device
**Expected Result**: 20-30% speedup on compatible devices

## ARCHITECTURE COMPLETION

### M4: Op-Log Foundation
\\\kotlin
// SQLite-based append-only log
@Entity(tableName = "ops")
data class OpEntity(
    @PrimaryKey val opId: String,  // UUIDv7
    val hlc: Hlc,
    val kind: String,
    val payload: ByteArray,        // Protobuf
    val prevHash: ByteArray,
    val signature: ByteArray,
    val applied: Boolean
)
\\\

### M6: Sync Engine
\\\kotlin
class SyncEngine @Inject constructor(
    private val opLog: OpLogRepository,
    private val gateway: GatewayClient,
    private val syncGate: SyncGate
) {
    suspend fun sync() {
        // 1. Push new ops (de-identified, privacy-filtered)
        // 2. Pull cloud updates (knowledge, skills, gap answers)
        // 3. Handle conflicts
        // 4. Update local shards
    }
}
\\\

### M8: Million-Point Optimization
- Quantized vectors (int8/binary) → 4-8x storage reduction
- Memory-mapped files for efficient access
- Chunked loading with LRU eviction
- Target: <50ms p95 search over 1M points

## PRODUCTION POLISH

### UX Enhancements
- Streaming token display with smooth animations
- Optimistic updates (instant feedback)
- Professional loading states
- Graceful error recovery

### Reliability
- Comprehensive error handling (all code paths)
- Memory pressure management
- Battery-aware operations
- Thermal throttling detection
- Crash-free guarantee

## IMPLEMENTATION TIMELINE

**Phase 1 (Week 1): Performance Breakthrough**
- Day 1-2: GPU/Vulkan enablement + testing
- Day 3: Async pipeline optimization
- Day 4: Multi-tier caching
- Day 5-6: CPU optimizations + benchmarking
- Day 7: Performance validation on both phones

**Phase 2 (Week 2): Core Features**
- Day 1-3: Op-log + sync engine foundation
- Day 4-5: Cloud gateway integration
- Day 6-7: Million-point optimization

**Phase 3 (Week 3): Production Ready**
- Day 1-2: UX polish + advanced features
- Day 3-4: Comprehensive testing
- Day 5-6: Performance profiling + optimization
- Day 7: Documentation + deployment prep

## SUCCESS METRICS

**Performance Targets**:
- LLM: 50-150 tok/s (3-8x improvement) ✅
- First query: <100ms total
- Repeated query: <10ms (cached) ✅
- 1M points: <50ms p95 search ✅
- Battery: 8+ hours active use ✅

**Feature Completeness**:
- All M0-M10 milestones ✅
- Production error handling ✅
- Smooth UX on 4GB+ devices ✅
- Offline-first with intelligent sync ✅

## NEXT STEPS

1. **Enable GPU acceleration** (highest impact, 3-10x speedup)
2. **Implement caching** (instant repeated queries)
3. **Optimize pipelines** (async, parallel execution)
4. **Complete sync architecture** (M4-M8)
5. **Production polish** (UX, reliability, testing)

---

**Current State**: Solid foundation, 70% complete
**Target State**: Industry-leading performance, 100% feature-complete
**Key Gap**: Performance optimization (GPU, caching, async)
**Timeline**: 3 weeks to production-ready

The architecture is excellent. Focus now is on:
1. Performance (GPU + optimizations)
2. Completeness (sync + cloud)
3. Polish (UX + reliability)
