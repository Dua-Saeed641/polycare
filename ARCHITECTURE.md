# PolyCare — Architecture

> An offline-first Android app where **one frozen small LLM becomes many health experts**, because Qdrant Edge retrieves its **skills (LoRA adapters)**, **knowledge**, **household memory** and **draft tokens** for every request. **Qdrant Cloud** turns the field's de-identified knowledge into sync decisions, outbreak alerts, knowledge slices and new skills.

---

## 1. Design principles

| # | Principle | Consequence |
|---|-----------|-------------|
| P1 | **Offline is the normal state** | Every feature works with zero network. Sync is opportunistic background work. |
| P2 | **Op-log is the source of truth; Qdrant is a view** | Every mutation is first appended to a signed, append-only op-log (SQLite). Device-owned Qdrant shards can be deleted and rebuilt from it. |
| P3 | **Everything is idempotent** | Each op has a UUIDv7 `op_id`. Applying it twice is a no-op, on both edge and cloud. |
| P4 | **Never trust wall clocks** | Ordering uses Hybrid Logical Clocks (HLC). Phone clocks are allowed to be wrong. |
| P5 | **Never compare vectors across models** | Every vector carries `model_id`. A mismatch means re-embedding from the stored text. |
| P6 | **Never load an unverified artifact** | Models, adapters and knowledge snapshots are content-addressed (sha256) and signed (ed25519). |
| P7 | **Always answer** | A degradation ladder steps down to retrieval-only if the LLM can't run. |
| P8 | **Personal health data never leaves the phone** | Households and anything identifying stay local. Only de-identified signals and knowledge sync. |
| P9 | **Geometry makes the decisions** | Routing, knowledge slicing, sync priority, conflict detection, outbreak alerts and skill training are triggered by vector-space signals. |
| P10 | **Decision support, not diagnosis** | Clinical outputs always cite a protocol source and suggest referral when unsure. |

---

## 2. Component catalogue

### 2.1 Edge (Android)

| Component | Tech | Responsibility |
|---|---|---|
| UI | Kotlin 2, Jetpack Compose, Material 3, Hilt, StateFlow | Ask, Triage, Scan, Households, Due List, Skills Shelf, Memory Inspector, Sync & Activity, Conflict Inbox, Chaos Panel |
| Speech-to-text | whisper.cpp multilingual (`base` / `small` q5) via JNI; IndicConformer as candidate | Push-to-talk in Hindi and English, fully offline |
| OCR | Google ML Kit Text Recognition v2 (Latin + Devanagari, bundled models) + ML Kit Document Scanner | MCP cards, lab reports, prescriptions, medicine strips, register pages |
| Field extractor | Base LLM in grammar-constrained JSON mode + regex rules | OCR text → structured fields (BP, Hb, EDD, dose, expiry) |
| Dense embedder | ONNX Runtime Mobile, `multilingual-e5-small` int8 (384-d) | Query + document embeddings (Hindi + English), stamped with `model_id` |
| Sparse encoder | BM25 tokenizer (Kotlin, Indic-aware normalisation) → Qdrant sparse vector | Exact hits on drug names, IDs, lab values |
| Skill Router | Qdrant Edge `skills` shard | Top-k skill cards → load / blend decision + α weights |
| Hybrid Retriever | Qdrant Edge query (prefetch dense + sparse, RRF fusion)¹ | Knowledge + household retrieval with payload filters |
| Inference engine | llama.cpp (NDK/CMake, JNI), arm64 NEON / i8mm | Base model + LoRA hot-swap + lookup speculative decoding |
| Base model | `Qwen2.5-1.5B-Instruct` Q4_K_M GGUF (~1.0 GB, mmap); `Qwen2.5-0.5B` for low-RAM phones | Frozen generalist |
| Skills | LoRA r=16 on q/k/v/o, GGUF (~9 MB each) | Health-domain experts, blended at runtime |
| Vector store | **Qdrant Edge** (upstream `qdrant-edge-ffi` crate built for arm64 with cargo-ndk; UniFFI Kotlin bindings in `:qdrant-edge`) | Shards: `knowledge`, `households`, `memory`, `skills`, `drafts`, `gaps`, `signals`, `atlas` |
| Op-log / outbox | SQLite via Room (WAL mode) | Source of truth, HLC, hash chain, sync cursors |
| Crypto | Android Keystore + ed25519 (Tink), SHA-256 | Device identity, op signatures, artifact verification |
| Artifact Store | App-private files, content-addressed | Models, adapters, knowledge snapshots: verify, LRU eviction, resumable download |
| De-identifier | Kotlin rules + NER-lite (names, phones, addresses, IDs) | Strips identity before anything reaches the Sync Gate |
| Semantic Merkle Index | Kotlin, SimHash-16 with shared seed | Region hashes for anti-entropy |
| Sync Engine | WorkManager + foreground service, OkHttp, Wire (Protobuf), zstd | Gate, chunked transfer, pull, conflicts |
| P2P Mesh (optional) | Google Nearby Connections (`P2P_CLUSTER`) | Phone ↔ phone sync with no internet |
| Resource Governor | BatteryManager, PowerManager thermal status, `onTrimMemory`, StatFs | Chooses the degradation-ladder rung and enforces quotas |

¹ If the Edge API lacks `prefetch` + fusion, run dense and sparse searches separately and do RRF in Kotlin.

### 2.2 Cloud

| Component | Tech | Responsibility |
|---|---|---|
| Vector DB | **Qdrant Cloud** (managed Qdrant Server) | `fleet_memory`, `knowledge_atlas`, `skill_registry`, `draft_corpus`, `knowledge_gaps`, `signals`, `radar_regions`, `atlas` |
| Edge Gateway | FastAPI, Python 3.12, Uvicorn, Pydantic v2, `qdrant-client` | Device auth, op verify/dedupe, privacy enforcement, Merkle, pull, gaps, skills, knowledge slices |
| Relational DB | PostgreSQL 16 | Devices & keys, global op-log, HLC watermarks, tombstones + acks, skill versions |
| Object storage | MinIO / S3 | Adapter GGUF blobs, Qdrant partial snapshots, signed manifests |
| Job queue | Redis 7 + ARQ | Async workers |
| Cloud LLM | Qwen2.5-7B-Instruct via Ollama (dev) / vLLM (GPU) | Gap answers, conflict adjudication, alert labels, teacher data |
| Skill Factory | Unsloth / HF PEFT, llama.cpp `convert_lora_to_gguf.py` | Mine → synth → train → eval → convert → sign → publish |
| Knowledge Slicer | Python worker | Picks each device's ~1 M-point slice (district, language, programmes, recent gaps) and builds its partial snapshot |
| Dashboard | Next.js 15, shadcn/ui, Tailwind, Recharts, WebSocket | Supervisor view: alerts, referrals, gaps, coverage, conflicts |
| Observability | Prometheus, Grafana, OpenTelemetry | Sync, inference and skill metrics |
| Fault injection | Toxiproxy | Latency, flapping and cut links for tests and the chaos demo |

The gateway sits between phones and Qdrant Cloud: phones never hold Qdrant Cloud write keys, and the gateway enforces privacy and signature checks before anything is stored.

---

## 3. Data model

### 3.1 Qdrant Edge shards (on the phone)

| Shard | Owner | Vectors | Key payload (indexed ★) | Purpose |
|---|---|---|---|---|
| `knowledge` | cloud | `dense` 384 (quantized) + `sparse` | ★`topic`, ★`lang`, ★`programme`, `text_ref`, `source`, `version` | Protocols, drug info, health education (~1 M points) |
| `skills` | cloud | `card` 384 | `skill_id`, `version`, `sha256`, `size`, `base_model`, `eval_score`, `local` | Skill routing |
| `atlas` | cloud | `centroid` 384 | `region_id`, `label`, `radius`, `cloud_count` | Shadow of cloud clusters → novelty score |
| `households` | device, **never synced** | `dense` 384 + `sparse` | ★`household_id`, ★`member_id`, ★`kind` (visit, pregnancy, child, scan), `text`, `hlc` | Her own families and visits |
| `memory` | device | `dense` 384 + `sparse` | ★`simhash`, ★`visibility` (`private`/`team`), `text`, `author`, `hlc`, `model_id`, `knn_radius`, `status` | Tips, local notes, answers worth sharing |
| `signals` | device | `dense` 384 | ★`simhash`, `village_code`, `week`, `age_band`, `sex` | De-identified symptom signals for Outbreak Radar |
| `drafts` | device | `dense` 384 | ★`skill_id`, `text`, `accept_rate` | Speculative decoding source |
| `gaps` | device | `dense` 384 | `query`, `hlc`, `best_score`, `status` | Offline misses → cloud |

**Cloud-owned** shards change only through Qdrant partial snapshots from the server. **Device-owned** shards change only through the op-log.

### 3.2 Qdrant Cloud collections

| Collection | Content |
|---|---|
| `knowledge_atlas` | Full knowledge base (tens of millions of points), source of each phone's `knowledge` slice |
| `fleet_memory` | Team-visible memory from every device (+ `device_id`, `votes`) |
| `signals` | De-identified symptom signals from every device |
| `skill_registry` | Skill cards + blob URI + eval metrics |
| `draft_corpus` | Curated high-acceptance answer spans per skill |
| `knowledge_gaps` | Unanswered questions, deduped by region |
| `atlas` | k-means centroids over `fleet_memory` + `signals` |
| `radar_regions` | Watch-list of newly dense signal regions |

### 3.3 Operation (Protobuf, `proto/sync.proto`)

```proto
message Hlc { uint64 wall_ms = 1; uint32 logical = 2; string node = 3; }

message Op {
  bytes  op_id     = 1;   // UUIDv7, idempotency key
  string device_id = 2;
  Hlc    hlc       = 3;
  oneof body {
    Upsert upsert = 10;  Delete delete = 11;  Merge merge = 12;
    Vote   vote   = 13;  GapAsk gap    = 14;  DraftFeedback fb = 15;
    Signal signal = 16;
  }
  bytes prev_hash = 20;   // per-device hash chain
  bytes sig       = 21;   // ed25519 over canonical bytes of 1..20
}
message Upsert {
  string point_id = 1;  string shard = 2;  string text = 3;
  bytes dense_f16 = 4;  SparseVec sparse = 5;  string model_id = 6;
  uint32 simhash = 7;   Visibility vis = 8;  map<string,string> payload = 9;
}
message Signal { bytes dense_f16 = 1; string model_id = 2; uint32 simhash = 3;
                 string village_code = 4; uint32 week = 5; string age_band = 6; string sex = 7; }
message Merge  { repeated string supersedes = 1; Upsert result = 2; string rationale = 3; }
message Vote   { string cloud_point_id = 1; }
message Delete { string point_id = 1; }
```

`households` ops are never placed in the outbox. `Signal` carries no text and no identity.

### 3.4 Op-log tables (SQLite / Room)

| Table | Columns |
|---|---|
| `ops` | `op_id` PK, `hlc`, `kind`, `bytes`, `prev_hash`, `sig`, `applied` |
| `outbox` | `op_id`, `priority`, `state` (`queued`/`sent`/`acked`), `attempts`, `next_try_at` |
| `sync_cursor` | `peer`, `last_acked_hlc`, `in_flight_batch`, `chunk_idx` |
| `tombstones` | `point_id`, `hlc`, `acked_by` |
| `conflicts` | `id`, `a`, `b`, `kind`, `proposal`, `state` |
| `artifacts` | `sha256`, `kind`, `name`, `version`, `bytes`, `last_used`, `pinned`, `state` |
| `knowledge_text` | `text_ref` PK, zstd-compressed passage text |

---

## 4. A million points on a phone

The cloud holds the full knowledge base; each phone carries the slice most relevant to it. Estimated storage per **1 million** knowledge points (384-d vectors):

| Part | Plain | With our settings | Setting |
|---|---|---|---|
| Vectors | 1.54 GB (float32) | **~96–384 MB** | 2-bit binary or int8 scalar quantization; originals on disk only if rescoring needs them |
| HNSW graph | ~128 MB (m=16) | **~64 MB** | m=8 on the phone |
| Sparse vectors | ~120 MB | **~60 MB** | Top-terms only |
| Passage text | ~400 MB | **~120–150 MB** | zstd-compressed, in SQLite, fetched only for top results |
| Payload index | — | ~20 MB | `topic`, `lang`, `programme` |
| **Total** | ~2.2 GB | **~350–650 MB** | |

- **Is "100 MB for a million" possible?** Only for vectors alone at 1–2 bits per dimension. With text and graph, 100 MB holds roughly **150–300k passages**. A 1 M-point slice needs roughly **350–650 MB** of storage, which low-end phones (32–64 GB) can afford. RAM use stays low because everything is memory-mapped.
- **Latency target:** < 20 ms p95 for a 1 M-point hybrid search on a mid-range phone (to be measured).
- **Reasoning:** the LLM never reads a million points. Retrieval narrows it to the best 5–10 passages, and the model reasons over those.
- **Only knowledge scales to millions.** Personal records on a phone are limited to the ASHA's own ~1,000 people. Other people's health records are never placed on her phone.
- **Supported by Qdrant Edge:** scalar int8, product, binary 1 / 1.5 / 2-bit and Turbo quantization, with on-disk (cold) or in-RAM (pinned) storage per component. Real numbers come from the in-app benchmark.

---

## 5. Core algorithms

### 5.1 Skill routing and blending
```
s  = search(skills, q, k=3)                     # cosine on skill cards
if s1 < τ (0.60):   base model only, log gap(q)
elif s1 - s2 < δ (0.10):  blend top-2, α = softmax([s1, s2] / T=0.05)
else:               single adapter, α1 = 1.0
llama_set_adapter_lora(ctx, A_i, α_i)            # per request, no weight copy
```
Skills: `maternal-care`, `newborn-care`, `child-illness` (IMNCI), `immunisation`, `nutrition`, `communicable-disease` (TB, malaria, dengue), `ncd-screening`, `family-planning`.

### 5.2 Hybrid retrieval and confidence
- `prefetch(dense, k=20) + prefetch(sparse, k=20) → RRF → top-5`, filtered by language, programme and `status ≠ superseded`.
- Household questions search `households` filtered by `household_id` first.
- `confidence = retrieval_score × skill_fit × exp(-age_since_reconciled / half_life)` → High / Medium / Low badge.
- Low confidence on a clinical question always adds "refer to ANM / PHC".

### 5.3 Danger-sign triage
Symptoms (voice, text or checklist) → retrieve matching danger-sign rules from `knowledge` → rule table decides *Refer now* / *24 h* / *Home care* → LLM writes the explanation citing the rule. The rule table, not the LLM, makes the referral decision.

### 5.4 OCR pipeline
ML Kit Document Scanner → Text Recognition v2 (Latin + Devanagari) → field extraction (regex for BP, Hb, dates; LLM JSON mode for the rest) → ASHA confirms fields → `Upsert` op into `households`.

### 5.5 Speculative decoding from memory
Top-3 `drafts` for (skill, query) plus top retrieved passages → llama.cpp n-gram lookup cache → the model verifies up to *k* = 5 draft tokens per pass. Acceptance rates are logged as `DraftFeedback` ops.

### 5.6 Semantic Merkle anti-entropy
- Every node derives 16 Gaussian hyperplanes from a fleet seed. `simhash(v)` = 16 sign bits; each prefix is a semantic region.
- 16-ary tree, 4 levels. Leaf hash = `SHA-256(sorted(op_id ‖ hlc))`. Empty subtrees get a constant hash.
- Compared only over team-visible points in regions the device subscribes to.
- Exchange level-1 hashes, descend into mismatches, then exchange `op_id` lists at the leaves.
- Each mismatched prefix is labelled with its nearest `atlas` centroid (e.g. *"diverged: dengue guidance"*).

### 5.7 Sync Gate
1. `households` and anything the De-identifier flags → **never leaves**.
2. Symptom observations → de-identified `Signal` (vector + village code + week + age band + sex only).
3. For team-visible memory: `novelty = 1 − max cos to atlas`. Low novelty → send a `Vote` only.
4. High novelty → `hubness = rknn(p) = |{n ∈ kNN(p) : cos(p,n) ≥ n.knn_radius}|`. Hubs push first; leaves wait for review.
5. Outbox priority = `novelty × (1 + rknn)`, under a byte budget on metered links.

### 5.8 Conflict detection and merge
1. On apply, neighbours with `cos ≥ 0.90`, a different author and a different content hash → candidate pair.
2. Cloud NLI (Qwen2.5-7B) or on-device JSON mode: `entails | contradicts | neutral`.
3. `contradicts` → both `disputed` + conflict record + proposed `Merge`.
4. A person accepts it in the Conflict Inbox. Originals are kept; undo = supersede the merge.

### 5.9 Outbreak Radar
Every 5 minutes, cloud: take `signals` from the last 14 days with low `atlas` similarity, cluster them (HDBSCAN). A cluster with **≥ 3 devices** and **≥ 2 villages** raises an alert labelled by the 7B model, shown on the dashboard and pushed to phones in those villages as a guidance card.

### 5.10 Knowledge slicing
Each device's slice = programmes it serves ∪ its languages ∪ regions near its recent queries and gaps ∪ current alerts. Built as a partial snapshot of `knowledge_atlas` and applied with `update_from_snapshot()`.

### 5.11 Skill Factory
A region becomes a skill candidate when `count ≥ 50`, `max cos(region centroid, any skill card) < 0.55` and `votes ≥ 10`. Pipeline: teacher Q/A synthesis → LoRA r=16 on the base model → eval gate (must beat base + Δ, clinical-safety test set must pass) → GGUF → sha256 + ed25519 → publish.

---

## 6. Flows

### 6.1 Ask
1. Push-to-talk → whisper.cpp → text (or typed text).
2. Embed dense + sparse.
3. Search `skills` (k=3) → choose / blend adapters; verify sha256 if cold.
4. Hybrid search `knowledge` (+ `households` if a family is selected) and `drafts`.
5. llama.cpp generates with LoRA α weights and speculative drafts; tokens stream to the UI.
6. Append `AnswerLogged`, `DraftFeedback`, and `GapAsk` on a miss, to the op-log → apply to views.

### 6.2 Scan
Document Scanner → OCR → field extraction → ASHA confirms → `Upsert` op into `households`.

### 6.3 Sync with Qdrant Cloud
1. Wait for a stable network (30 s), check metered status and battery.
2. Authenticate with the gateway (ed25519 challenge).
3. Push outbox chunks (256 KB, resumable); gateway verifies signature + chain, inserts `ON CONFLICT (op_id) DO NOTHING`, writes to Qdrant Cloud, acks; cursor saved after every ack.
4. Merkle diff over subscribed regions → pull missing ops, gap answers, merges, tombstones, alerts.
5. Apply ops idempotently → views → Merkle updated.
6. Pull partial snapshots for `skills`, `atlas` and the `knowledge` slice.

### 6.4 Artifact delivery (skills, models, knowledge)
Resumable HTTP Range download to `.part` → fsync → sha256 + signature verify → atomic rename, or quarantine and fall back.

### 6.5 Outbreak alert
Phones sync `Signal` ops → Radar clusters → alert on dashboard → guidance card pushed to phones in the affected villages on their next sync.

### 6.6 Phone ↔ phone (optional)
Nearby Connections → exchange device keys and subscribed prefixes → Merkle level-1 over the intersection → exchange signed team ops (never `households`). Either phone relays the other's ops to the cloud later.

---

## 7. Resilience: failure matrix

| # | Failure | Detection | Handling |
|---|---|---|---|
| 1 | App killed during a write | Room transaction | Op append is atomic; unapplied ops replayed on start |
| 2 | App killed mid-sync | `sync_cursor.in_flight_batch` | Resume from last acked chunk; server dedupes by `op_id` |
| 3 | Network flaps | NetworkCallback | 30 s stable window, exponential backoff with jitter, circuit breaker |
| 4 | Metered / expensive link | `isActiveNetworkMetered` | Budget mode: signals, gaps and hub ops only; no large downloads |
| 5 | Phone clock wrong | HLC skew check | HLC ordering; warning if skew > 5 min |
| 6 | Duplicate / replayed ops | `op_id` PK | `ON CONFLICT DO NOTHING` and edge existence check |
| 7 | Tampered / forged op | ed25519 + `prev_hash` | Reject, quarantine the peer, show in Activity |
| 8 | Deleted item resurrects | tombstone vs upsert HLC | Newer tombstone wins; GC only after all peers ack |
| 9 | Two workers record different details | near-dup + NLI | `disputed` + merge proposal; nothing overwritten |
| 10 | Bad merge | human review | Originals kept; undo supported |
| 11 | Embedding model upgraded | `model_id` mismatch | Separate named vectors; background re-embed; never compared across models |
| 12 | Device-owned shard corrupted | open error | Rebuild from op-log |
| 13 | Knowledge slice corrupted | open error / sha256 | Re-apply last verified snapshot or re-download |
| 14 | Op-log DB corrupted | `integrity_check` on boot | Restore own ops from cloud; hash chain shows gaps. `households` backed up encrypted on device only |
| 15 | Download interrupted | `.part` file | HTTP Range resume, atomic rename after verify |
| 16 | Adapter / model corrupt | sha256 | Quarantine, fall back, re-fetch |
| 17 | Low RAM phone | `onTrimMemory`, total RAM | Smaller base model, drop 2nd adapter, shrink context, then retrieval-only |
| 18 | Thermal throttling | thermal listener | LEAN → SINGLE → RECALL, fewer threads |
| 19 | Low battery | BatteryManager | Pause background sync; ask still works |
| 20 | Storage full | StatFs | Shrink knowledge slice, evict unpinned skills, block large downloads |
| 21 | OS kills background work | WorkManager | Restart-safe jobs; long downloads in a foreground service |
| 22 | Cloud down | health check | Nothing changes for the user; outbox compacted |
| 23 | Offline for weeks | outbox size | Compaction, priority order, gaps deduped by region |
| 24 | Gateway overloaded | 429 | `Retry-After`, jitter, adaptive chunk size |
| 25 | Partial snapshot fails mid-apply | SDK error | Retry or full restore |
| 26 | Unknown / revoked device | registry | 401 → re-registration |
| 27 | Unsupported clinical answer | retrieval score < τ | "Low confidence, no source" + refer advice + logged as gap |
| 28 | Identity leaks into a signal | De-identifier + gateway check | Gateway rejects ops with identity patterns; logged |
| 29 | Poisoned knowledge from one device | votes + author diversity | Radar needs ≥ 3 devices; Skill Factory needs multiple sources; supervisor review for gap answers |

The **Chaos Panel** (debug build) triggers #2, #3, #5, #16, #17 and #18 live. Toxiproxy injects #3, #22 and #24 on the cloud side.

---

## 8. Security and privacy

- Device key pair in Android Keystore; public key registered with the gateway; auth by signed challenge.
- Every op is signed and hash-chained per device.
- `households` is encrypted at rest (SQLCipher / Keystore-wrapped key) and never synced.
- The De-identifier runs before the Sync Gate; the gateway re-checks and rejects identity patterns.
- Consent is recorded per household before any data is stored.
- Artifacts verified by sha256 + fleet signing key before use.
- TLS 1.3 everywhere; short-lived presigned URLs; phones never hold Qdrant Cloud keys.

---

## 9. Performance budgets (targets, to be measured)

| Stage | Target |
|---|---|
| Qdrant Edge hybrid search, 1 M knowledge points | < 20 ms p95 |
| Qdrant Edge search, households (≤ 50k points) | < 10 ms p95 |
| Query embedding | < 30 ms p95 |
| OCR, one card | < 1.5 s |
| Adapter switch (warm / cold) | < 5 ms / < 150 ms |
| Time to first token (512-token prompt) | < 2.5 s |
| Decode (no spec / with spec) | ≥ 10 / ≥ 18 tok/s |
| Sync: 1,000 ops | < 5 s on 4G, resumable |
| Knowledge slice on disk (1 M points) | < 650 MB |
| Peak app RAM | < 2.2 GB (full), < 1.2 GB (low-RAM mode) |

### Measured on a real phone

Qdrant Edge on a 6 GB-class Android 16 phone (model 2406ERN9CI, arm64), 384-d clustered synthetic vectors, int8 scalar quantization, HNSW m=8, `hnsw_ef`=128. Measured 2026-09-28 with the in-app Vector engine benchmark.

| Points | Search p50 | Search p95 | Recall@10 | Load | Index build | Disk used |
|---|---|---|---|---|---|---|
| 10,000 | 6.9 ms | 12.8 ms | 99% | 4.6 s | 2.2 s | 58 MB |
| 100,000 | 9.8 ms | 37.8 ms | 97% | 30.8 s | 14.4 s | 574 MB |

Where the 100k disk goes: float32 originals 147 MB, int8 copies 37 MB, HNSW graph ~3 MB, and ~300 MB of write-ahead log from inserting through upserts. For the ~1 M-point knowledge slice this means:
- Deliver the slice as a Qdrant snapshot, not as upserts, so there is no WAL.
- Store originals as `uint8` or `float16` (or keep int8/binary copies only) instead of float32. At 1 M points: float32 originals would be ~1.5 GB, float16 ~0.75 GB, uint8 ~0.38 GB.

Embedder (multilingual-e5-small int8, 118 MB, ONNX Runtime 1.30, same phone):

| Measure | Result |
|---|---|
| Tokens vs HF `tokenizers` (25 sentences, 8 Indian scripts + English) | identical |
| Vectors vs Python reference (x86) | min cosine 0.9983; every sentence keeps the same nearest neighbour (25/25) |
| Query embedding | p50 9.6 ms, p95 11.0 ms |
| Load (sha256 verify + open) | ~3 s, once per app start |

---

## 10. Tech stack

**Android:** Kotlin 2 · Jetpack Compose · Material 3 · Hilt · Coroutines/Flow · Room (SQLite WAL) · SQLCipher · WorkManager · OkHttp · Wire (Protobuf) · zstd-jni · Google ML Kit (Text Recognition v2, Document Scanner) · Google Nearby Connections · Android Keystore · Tink · NDK + CMake · **llama.cpp** · **whisper.cpp** · **ONNX Runtime Mobile** · **Qdrant Edge** (Rust crate + UniFFI, built with cargo-ndk) · JUnit5 · Turbine · Robolectric

**Models:** Qwen2.5-1.5B-Instruct and Qwen2.5-0.5B-Instruct (Q4_K_M GGUF) · LoRA r=16 health skills (GGUF) · multilingual-e5-small (int8 ONNX) · whisper base/small multilingual (q5) · Qwen2.5-7B-Instruct (cloud only)

**Cloud:** **Qdrant Cloud** · FastAPI / Uvicorn / Pydantic v2 · qdrant-client · PostgreSQL 16 · MinIO / S3 · Redis 7 + ARQ · Ollama / vLLM · Unsloth / PEFT · HDBSCAN · Next.js 15 · shadcn/ui · Recharts · Prometheus · Grafana · Toxiproxy · Docker Compose

---

## 11. Repository layout

```
polycare/
├── android/
│   ├── app/              Compose UI, navigation, Hilt wiring, Chaos Panel
│   ├── core-common/      HLC, UUIDv7, config, shared types
│   ├── core-llm/         llama.cpp + whisper.cpp JNI, LoRA + speculative APIs
│   ├── core-embed/       ONNX embedder, BM25 sparse encoder
│   ├── core-vector/      Qdrant Edge binding (UniFFI) behind a VectorStore interface
│   ├── core-memory/      shards, router, retriever, confidence, triage rules
│   ├── core-ocr/         ML Kit scanner + OCR + field extraction
│   ├── core-oplog/       Room DB, signing, outbox, view rebuild
│   ├── core-sync/        Merkle index, Sync Gate, De-identifier, transfer, pull, mesh
│   └── core-governor/    battery/thermal/memory → degradation ladder
├── cloud/                gateway · workers · skill-factory · knowledge-slicer · dashboard
├── native/               qdrant-edge UniFFI crate + build scripts
├── proto/sync.proto      single source of truth for the wire format
└── tools/                seed data, chaos scripts, benchmarks
```

---

## 12. Open risks and first checks

| Risk | Check | Fallback |
|---|---|---|
| Qdrant Edge has no released Android SDK yet | Upstream ships the `qdrant-edge-ffi` UniFFI crate; we build it with cargo-ndk (`native/build-qdrant-edge.sh`, pinned commit) and run instrumented tests on a real phone | Switch to the official Kotlin SDK artifact once it is published |
| Size and latency of 1 M quantized points | Qdrant Edge FFI supports scalar int8, product, binary (1, 1.5, 2-bit) and Turbo quantization plus cold/cached/pinned memory; measure with the in-app Vector engine benchmark | Smaller slice (250–500k) or Matryoshka-truncated vectors |
| Hybrid query on Edge | Test prefetch + RRF | RRF in Kotlin |
| Hindi quality of a 1.5B model | Eval set of 100 ASHA questions in Hindi | Gemma-class 1–2B multilingual model; answer in English with Hindi summary |
| Hindi speech-to-text offline | whisper base/small vs IndicConformer on real recordings | Text input + large on-screen checklist |
| llama.cpp LoRA hot-swap on Android | Load base + 2 adapters, switch per request | Single-adapter mode |
| Low-end phones (3–4 GB RAM) | Run on a budget phone | 0.5B model or retrieval-only mode |
| Clinical safety | Safety test set reviewed against official ASHA modules | Rule table decides referrals; LLM only explains |
