# PolyCare — Work Log

Append-only, newest at the bottom. Every entry: what was done, why, what went wrong, and results.
Live status of every feature is in [STATUS.md](STATUS.md).

---

## 2026-09-27 — Direction and design

- **Idea.** Started as *Polymath* (offline copilot for field technicians). Judged not serious enough; an enterprise industrial-safety idea was also considered. **Chosen: PolyCare**, an offline-first copilot for India's ~1 M ASHA community health workers.
- **Stack.** React Native was considered and dropped. **Native Kotlin** Android app. All AI runs on the phone; **Qdrant Cloud** is the Qdrant Server for sync (required by the problem statement).
- **Architecture kept** from Polymath: LoRA skills routed by Qdrant Edge, op-log, Sync Gate, semantic Merkle sync, drafts, gaps, conflicts, radar. New for PolyCare: OCR, households, danger-sign triage, Outbreak Radar, knowledge slices of ~1 M points.
- **Docs rewritten:** README, PROJECT_DESCRIPTION, ARCHITECTURE (text only, no images), MILESTONES (M0–M11 project phases), CLAUDE.md. Old diagram images deleted.
- **Problem statement PDF** re-read (single image page); every requirement mapped to a milestone in MILESTONES.md.

## 2026-09-27 — Android scaffold

- Gradle project: `app`, `core-common` (HLC, UUIDv7, config), `core-vector` (VectorStore interface, RRF, in-memory store), `core-governor` (device probe, degradation ladder). AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12, Hilt 2.53.1, compileSdk 35, minSdk 29, arm64 only.
- 19 unit tests passing.
- Toolchain found on the PC: JDK 17, Android SDK 35/36, NDK 27.1, cached Gradle 8.13. No Rust at the time.

## 2026-09-27 — Brand and UI

- User supplied `assets/banner.png`, `assets/logo.png`, Tenor Sans font. Colours sampled from the banner (paper #F8F8F8, plum #5E0B53, magenta #BC16A6, rose #FB2E66, blush #F9DCF1).
- Built `BrandBackground` (drifting orbs + film grain), `GlassCard`, `Wordmark`, `StatusPill`, `MetricRow`; Home and System screens; floating tab bar; adaptive launcher icon from the clover.
- Logo cleaned (near-transparent stray pixels removed, trimmed to square).
- README rebuilt with banner, badges and two Mermaid workflow diagrams.

## 2026-09-28 — First run on the phone

- Phone: Xiaomi 2406ERN9CI, Android 16, arm64, 5.3 GB RAM reported (6 GB class).
- Fixes after seeing it on the device: ask-bar hint was truncated; tab bar needed a fade and shadow.
- **RAM thresholds were wrong:** a "6 GB" phone reports ~5.3 GB, so it was put in LEAN. Thresholds now by phone class (FULL ≥ 5,000 MB reported, LEAN ≥ 3,400, BASE ≥ 2,500).
- Xiaomi blocks `adb input` (needs "USB debugging (Security settings)") and new-package installs (needs "Install via USB"). Workarounds: debug launch extras (`bench_points`, `embed_check`) that run checks inside the app and log to logcat.

## 2026-09-28 — Qdrant Edge on Android

- Qdrant Edge officially ships Python and Rust only; the Kotlin SDK is still an upstream PR. Upstream already has the `qdrant-edge-ffi` UniFFI crate, so we build it ourselves.
- Installed Rust 1.98.1 (GNU host), `aarch64-linux-android` target, cargo-ndk 4.1.2.
- `native/build-qdrant-edge.sh` builds the pinned commit `934c22441b71` with the upstream `release-mobile` profile and generates Kotlin bindings.
- **Problems and fixes:**
  1. `dlltool` from an old 32-bit `C:\MinGW` shadowed Rust's → dropped from PATH.
  2. Rust's bundled dlltool can't run without `as` → installed MinGW-w64 (WinLibs via winget, user scope).
  3. `uniffi-bindgen` found no metadata because `release-mobile` strips symbols → build with `CARGO_PROFILE_RELEASE_MOBILE_STRIP=none` (AGP strips at packaging).
  4. cargo-ndk also copied stray `libcrc_fast-*.so` → deleted by the script (FFI lib only needs libc/libm/libdl).
- `QdrantEdgeVectorStore` adapter: named vectors `dense` (cosine) + `sparse` (IDF), UUID ids (original id kept in payload), keyword filters, native hybrid prefetch + RRF, int8 / 2-bit binary quantization options, dedicated FFI dispatcher.
- **On-phone tests (3/3 pass):** upsert/search/filter/sparse/hybrid/delete, persistence across reopen, model-id guard.
- **Benchmark** (in-app Vector engine card): with uniform random vectors recall was only 54% (unrealistic worst case) → switched to clustered synthetic data and `hnsw_ef=128`.

  | Points | p50 | p95 | Recall@10 | Disk used |
  |---|---|---|---|---|
  | 10k | 6.9 ms | 12.8 ms | 99% | 58 MB |
  | 100k | 9.8 ms | 37.8 ms | 97% | 574 MB |

- Disk looked like 334 MB for 10k at first: Qdrant pre-sizes 32 MB WAL/page files as sparse files. Now measured with `st_blocks`. At 100k, ~300 MB is WAL from upserts → knowledge slices must arrive as snapshots, and originals should be float16/uint8 for 1 M points.

## 2026-09-28 — Multilingual embedder

- Model: `Xenova/multilingual-e5-small` int8 ONNX (118 MB), pinned to revision `761b726`, sha256-verified. `tools/models/fetch_models.sh`, `build_e5_assets.py`, `push_models.sh`.
- Kotlin tokenizer (`core-embed`): exact port of SentencePiece's precompiled `nmt_nfkc` charsmap (Darts trie, grapheme quirk included) + Metaspace + Unigram Viterbi. **Token-for-token identical** to HF `tokenizers` on 25 sentences (English + 8 Indian scripts, nuqta, emoji, full-width, zero-width, odd whitespace, empty).
- Compact tokenizer file `e5_tokenizer.bin` (4.3 MB) instead of parsing the 17 MB JSON on the phone.
- **Problems and fixes:**
  1. Padded batches changed vectors (cos 0.9965): the int8 model quantises activations over the whole batch → embed one text per run.
  2. JVM vs Python still differed (0.9988): ORT 1.30 vs 1.27 kernels → pinned the same ORT (1.30.0) in `tools/requirements.txt` and a `tools/.venv`. JVM now matches Python > 0.9999.
  3. ARM vs x86 kernels differ slightly (min cos 0.9983) → on-device check uses cos > 0.995 **and** "same nearest neighbour for every sentence".
  4. Reinstalls and the test runner wiped pushed models → `leaveApksInstalledAfterRun=true`.
- Sparse encoder: BM25 term weights over the same tokens (k1 1.2, b 0.75); Qdrant's IDF modifier supplies IDF.
- `ArtifactVerifier`: size + sha256 before load, bad files moved to `*.quarantine`, never crash (invariant 5).
- **On phone:** tokens identical, same nearest neighbour 25/25, min cos 0.9983, query embed p50 9.6 ms / p95 11.0 ms, load ~3 s.
- Tests: 37 unit tests + on-phone suites (embedder, Qdrant Edge) all passing.

## 2026-09-28 — Tracking

- User asked for everything to be tracked and logged. Added `STATUS.md` (live dashboard) and this `WORKLOG.md`, updated after every step.
- **On-device activity log** (`EventLog` in core-common, `FileEventLog` in app): logcat tag `PolyCareEvent` + `files/logs/events.jsonl` (rotates at 512 KB) + "Activity" card on the System screen. Records app start (mode, RAM, storage, battery, heat), model verification/load, self-checks, benchmarks, knowledge install/open, searches. **Never** query text or health data, only metadata.

## 2026-09-28 — Models moved to private storage

- Problem: the app reported the embedder "missing" although the files were on the phone. Folders created by `adb` under `Android/data/<pkg>/` belong to the `shell` user, and the app cannot read them (it only worked earlier by accident of creation order).
- Fix: models now live in the app's **internal** `files/models/`. `push_models.sh` stages files in `/data/local/tmp` and copies them in with `run-as` (debug builds). This is also where the first-run downloader will write.

## 2026-09-28 — M1: official knowledge on the phone

- **Sources** (all official NHM/MoHFW, public): ASHA Module 6 and 7 (English + Hindi), ASHA Induction Module, National Immunization Schedule. Listed with URLs in `tools/knowledge/sources.json`; every passage keeps its document and page.
- **Build pipeline** `tools/knowledge/build_knowledge.py`: PDF → clean → ~120–180-word passages (one sentence overlap) → e5 passage vectors (same model + ORT 1.30 as the phone) + BM25 sparse vectors (same formula as `SparseEncoder`) → Qdrant Edge shard built with `qdrant-edge-py 0.8.0` (same engine version and layout as the phone) → zip + manifest (sha256, points, model id, source hashes) + review report with sample queries.
- **Immunization schedule table:** extracted row by row. Rows where the PDF prints several vaccines in one row (e.g. Vitamin A + DPT booster + MR-2 + OPV booster) are kept *as printed* and flagged `table-ambiguous`; the app shows "check the printed schedule" instead of presenting a possibly wrong pairing as fact.
- **Hindi modules use a legacy font (Walkman-Chanakya / Kruti Dev):** the PDF stores Latin letters ("xHkZorh efgyk") that only look like Hindi. Wrote `tools/knowledge/krutidev.py`: glyph table + reordering of ि (typed before its cluster) and reph र् (typed after). Iterated on real text: `kW`→ॉ, `}`→द्व, `è`→ध्, `#`→रु, `|`→द्य, `¼`→द्ध, `:`→रू, `/k`→ध, `.` after digits stays a full stop, and clean-ups for invalid sequences (ि before ा, half letter + ा, े+ा → ो, पफ → फ). Result: ~78% of converted words are whole words in the model vocabulary; invalid matra sequences 51 → 19 across both books.
- **English PDF artefacts:** ligatures at odd code points (Ĵ = tt, Ğ = ft, ﬃ), Wingdings bullets in the private-use area, and "ft" as control char 0x04 + space ("a\x04 er" → "after"). All mapped.
- **Problems hit:** a passage of 522 tokens broke the 512-token model → over-long "sentences" are split by length, and truncation matches the Kotlin tokenizer (first 511 + `</s>`). Python `count()` needs a `CountRequest`. `EdgeShard.create` needs the directory to exist.
- **Result (v1):** 1,240 passages from 6 documents, 5.7 MB zip. Sample queries return the right pages: "how to prepare ORS" → Module 7 p28; "how many antenatal check-ups" → Module 6 p24 ("Four antenatal visits…"); "गर्भावस्था में खतरे के लक्षण" → Hindi Module 6 p32; "बच्चे को दस्त हो तो क्या करें" → Hindi Module 7 p30 (Plan B). Before Hindi sources were added, Hindi queries were poor (cross-lingual only).
- **Phone side:** `KnowledgeRepository` installs a package from `files/knowledge/incoming/` (verify sha256 + size, refuse a different embedding model, unzip with zip-slip guard into `<version>.tmp`, rename when complete, keep only the current version), opens it as a `QdrantEdgeVectorStore`, and runs hybrid search. Logs install/open/search metadata.
- **A Python-built shard opens on Android** unchanged (same Qdrant Edge 0.8 engine): installed in 2.4 s, 1,240 points.
- **Search screen** (Home ask bar → Search): pill search field, English/Hindi suggestion chips, result cards with source · page · language tags, ambiguous-row warning, disclaimer. First query on the phone: embed 27 ms, search 26 ms (cold). Verified on screen for English and Hindi queries.
- Debug launch extra `--es search_query "…"` opens Search with a query (the phone blocks adb taps).
- v2 (in progress): rebuild with the latest legacy-font and ligature fixes; push script now picks the newest build.
