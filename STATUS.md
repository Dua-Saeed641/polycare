# PolyCare — Status

Live dashboard. Updated after every step; history and reasoning are in [WORKLOG.md](WORKLOG.md).

**Last updated:** 2026-09-28 · **Current:** M0 Foundations (5 / 10) and M1 Knowledge & search (4 / 5) · **Test phone:** Xiaomi 2406ERN9CI, Android 16, 6 GB class

---

## Milestones

| Milestone | Progress | State |
|---|---|---|
| M0 Foundations | 5 / 10 | 🟣 In progress |
| M1 On-device knowledge and search | 4 / 5 | 🟣 In progress |
| M2 Offline health assistant | 0 / 8 | ⚪ Not started |
| M3 Households, OCR, daily work | 0 / 6 | ⚪ Not started |
| M4 Evolving memory | 0 / 5 | ⚪ Not started |
| M5 Conflicting information | 0 / 4 | ⚪ Not started |
| M6 Sync with Qdrant Cloud | 0 / 7 | ⚪ Not started |
| M7 Edge-to-cloud AI workflows | 0 / 6 | ⚪ Not started |
| M8 A million points on the phone | 0 / 3 | ⚪ Not started |
| M9 Reliability and low-end phones | 0 / 4 | ⚪ Not started |
| M10 Complete product | 0 / 7 | ⚪ Not started |
| M11 Optional extras | 0 / 3 | ⚪ Not started |

### M0 checklist

| Item | State | Evidence |
|---|---|---|
| Android project builds and installs on a real phone | ✅ | installs on 2406ERN9CI |
| Qdrant Edge on arm64: create, upsert, search from Kotlin | ✅ | `QdrantEdgeVectorStoreTest` 3/3 on phone |
| Hybrid search (dense + sparse + RRF) | ✅ | native prefetch + RRF, tested on phone |
| Multilingual embedder under ~30 ms per query | ✅ | p50 9.6 ms (warm) on phone |
| Embedder parity with reference on the phone | ✅ | `embed_check` PASS, 25/25 neighbours |
| llama.cpp runs Qwen2.5-1.5B; tokens/sec measured | ⬜ | — |
| Two LoRA adapters switched per request | ⬜ | — |
| whisper.cpp transcribes Hindi offline | ⬜ | — |
| ML Kit reads an MCP card | ⬜ | — |
| Qdrant Cloud cluster; partial snapshot applied on phone | ⬜ | — |
| One health LoRA skill trained → GGUF | ⬜ | — |

### M1 checklist

| Item | State | Evidence |
|---|---|---|
| Knowledge base from official ASHA modules, immunisation schedule | ✅ | 1,240 passages, 6 documents (EN + HI) |
| Hybrid search with filters | ✅ | dense + BM25 sparse + RRF; keyword indexes on source/lang/programme/quality |
| **Search** screen with results, sources and scores | ✅ | on phone, English and Hindi |
| **Memory Inspector** screen | ⬜ | — |
| Search latency measured | ✅ (first) | cold: embed 27 ms, search 26 ms; warm numbers to collect |

---

## What works today

| Part | How it works | Code | Verified by |
|---|---|---|---|
| App shell and brand UI | Compose, Hilt; Home, System and Search screens; floating tab bar; banner orbs + grain; Tenor Sans | `app/.../ui`, `home`, `device`, `knowledge` | Screenshots on phone |
| Device probe + degradation ladder | RAM, battery, heat, storage, ABI → FULL / LEAN / SINGLE / BASE / RECALL | `core-governor` | unit tests; System screen |
| HLC + UUIDv7 + config | Hybrid logical clock; UUIDv7 op ids; all thresholds in `PolyCareConfig` | `core-common` | unit tests |
| Artifact verification | Size + sha256 before loading; bad file → `*.quarantine`; app keeps running | `core-common/ArtifactVerifier` | unit tests |
| Activity log | Events to logcat `PolyCareEvent` + `files/logs/events.jsonl`; System → Activity card; metadata only | `core-common/EventLog`, `app/.../log` | seen on phone |
| Qdrant Edge | Upstream `qdrant-edge-ffi` built for arm64; UniFFI Kotlin; `QdrantEdgeVectorStore` | `android/qdrant-edge`, `native/` | 3 on-phone tests, benchmark |
| Vector benchmark | Clustered synthetic vectors → p50/p95, recall, real disk use | `core-vector/VectorBenchmark` | 10k and 100k on phone |
| Embedder | multilingual-e5-small int8, ORT 1.30, Kotlin SentencePiece tokenizer, one text per run | `core-embed` | parity tests (JVM + phone) |
| Sparse encoder | BM25 over e5 tokens; Qdrant IDF | `core-embed/SparseEncoder` | unit test |
| Knowledge build | Official PDFs → passages with page citations → vectors → Qdrant Edge shard → zip + manifest; Hindi legacy-font converter | `tools/knowledge` | review report, sample queries |
| Knowledge on phone | Verified install (sha256, model id, zip-slip guard, atomic rename) → hybrid search | `app/.../knowledge` | installed + searched on phone |

## Not built yet

Ask (LLM answers), Triage, Scan (OCR), Households, Due list, Sync, Outbreak Radar, Conflict Inbox, Memory Inspector, speech, LoRA skills, op-log, Qdrant Cloud, gateway, dashboard. Home tiles say "arrives in M…".

---

## Measured numbers (real phone)

| What | Result |
|---|---|
| Qdrant Edge search, 10k synthetic points | p50 6.9 ms · p95 12.8 ms · recall@10 99% · 58 MB |
| Qdrant Edge search, 100k synthetic points | p50 9.8 ms · p95 37.8 ms · recall@10 97% · 574 MB (≈300 MB WAL) |
| Query embedding (e5-small int8) | warm p50 9.6 ms · p95 11.0 ms (a cold run right after install measured 34.9 ms) |
| Embedder load (verify + open) | 3–7 s once per start |
| Phone vs Python embeddings | tokens identical · min cos 0.9983 · same nearest neighbour 25/25 |
| Knowledge install (5.7 MB, 1,240 passages) | 2.4 s |
| Knowledge search, first query | embed 27 ms + search 26 ms |

## Known issues and risks

| Issue | Impact | Plan |
|---|---|---|
| Hindi legacy-font conversion is heuristic | 19 invalid sequences left in ~40k words; occasional misspelled word | Keep fixing from the review report; prefer Unicode sources when available |
| 100k upserts leave ~300 MB WAL | Too big for 1 M points | Knowledge ships as built shards (done); float16/uint8 originals (M8) |
| Models and knowledge are pushed with adb (debug) | Not how users get them | First-run downloader + signed manifests (M6/M10) |
| Knowledge manifests are trusted by hash only | No signature yet | ed25519-signed manifests with the downloader |
| Xiaomi blocks adb taps | Can't drive the UI from the PC | Enable "USB debugging (Security settings)"; debug launch extras meanwhile |
| Qdrant Edge Android SDK not official | Built from a pinned upstream commit | Switch to the official artifact when published |
| Hindi quality of a 1.5B LLM unknown | Answers may be weak in Hindi | Evaluate in the LLM step |

---

## How to verify

```bash
cd android && ./gradlew test                                            # unit tests
cd android && ./gradlew :app:connectedDebugAndroidTest :qdrant-edge:connectedDebugAndroidTest
bash tools/models/push_models.sh                                        # embedder → phone
tools/.venv/Scripts/python tools/knowledge/build_knowledge.py           # build knowledge
bash tools/knowledge/push_knowledge.sh                                  # knowledge → phone
adb shell am start -n org.polycare.app/.MainActivity --es search_query "how to prepare ORS"
adb shell am start -n org.polycare.app/.MainActivity --ez embed_check true    # logcat PolyCareEmbed
adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000  # logcat PolyCareBench
adb logcat -s PolyCareEvent                                             # activity log
```
