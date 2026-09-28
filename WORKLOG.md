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

## 2026-09-28 — New machine setup and independent M0/M1 re-verification

- Continuing on a second PC (previously bare: no adb, no Android SDK). Second test phone: **Realme RMX2151, Android 12, 6 GB class** — same code, different hardware/OEM than the Xiaomi used so far.
- **Toolchain from scratch:** Android SDK command-line tools + `platform-tools` + `platforms;android-35` + `build-tools;35.0.0` via `sdkmanager`. JDK 21 (Temurin) already present; the project needed a JDK 17 *toolchain*, fixed by adding the `org.gradle.toolchains.foojay-resolver-convention` plugin to `settings.gradle.kts` so Gradle provisions it itself rather than requiring a pre-installed JDK 17.
- **Native Qdrant Edge build, fresh:** installed NDK `27.1.12297006`, Rust via `winget install Rustlang.Rustup`, `cargo-ndk`, MinGW-w64 (WinLibs) via winget.
  - **Problem:** the Windows account's profile path contains a space (`C:\Users\Dua Saeed\...`). MinGW's `gcc`/`ld`/`collect2` chain breaks on this in two independent ways: (1) the default rustup toolchain host is MSVC, not GNU — switched to `rustup default stable-x86_64-pc-windows-gnu`; (2) even with the GNU toolchain and `RUSTUP_HOME`/`CARGO_HOME` relocated to `C:\rust-tmp\`, linking still failed with `C:/Users/Dua: file not recognized` — traced to MinGW-w64 itself being installed under the space-containing WinGet package path, which GCC resolves its own internal tool paths from. **Fix:** copied `mingw64/` to `C:\mingw64` (space-free) and pointed `PATH` there instead.
  - With that fixed, `native/build-qdrant-edge.sh`'s `cargo ndk` step built cleanly: 33m 42s, `libqdrant_edge_ffi.so` (36 MB, arm64-v8a). Kotlin bindings were already committed (pinned upstream commit `934c22441b71`) and needed no regeneration.
- **Verification, not assumption:** before touching any code, ran the actual repo state rather than trusting prior status text —
  - `./gradlew test`: all unit tests pass (fresh checkout, ~10 min first run for dependency downloads).
  - `:qdrant-edge:connectedDebugAndroidTest` on the Realme: **3/3 pass** — matches the Xiaomi's 3/3, confirms Qdrant Edge is genuinely device-independent, not tuned to one phone.
  - Embedder JVM parity test: passes against the committed `e5_reference.json` on this machine too (also sanity-checked by regenerating it fresh from this machine's Python/ONNX build, confirming only expected float noise, then reverting the regenerated file — the colleague's committed fixture is untouched).
  - Pushed the embedder model (`tools/models/push_models.sh`) and knowledge v2 (`tools/knowledge/push_knowledge.sh`) to the Realme. On-device `embed_check`: **PASS**, minCos 0.998317, 25/25 nearest-neighbour agreement — matches the Xiaomi's numbers. Real search (`search_query "how to prepare ORS"`): 5 hits, embed ~20–22 ms, search ~28–45 ms.
  - Conclusion: **M0 is genuinely 5/10 and M1 genuinely 4/5**, independently confirmed on a second phone, not just re-stated from STATUS.md. Nothing in the unchecked items (llama.cpp, LoRA, whisper.cpp, ML Kit, Qdrant Cloud, Memory Inspector) blocks M2 — Memory Inspector's own backend (`KnowledgeRepository.browse()`/`.stats()`) already exists, it's only missing a screen.

## 2026-09-28 — M2: Ask and Triage

- Repo's own `MILESTONES.md` scopes M2 to the offline Ask/Triage assistant with **zero cloud dependency** — kept to that scope; a separate, later request about location-aware sync is M6/M7/M8 territory and was intentionally not touched this session (no `cloud/` gateway or op-log exists yet to build real sync against).
- **Ask** (`app/.../ask`): wraps the real `KnowledgeRepository` (same hybrid search Search screen uses). Shows the best-matching passage with its source, page and a confidence badge. Confidence is deliberately simple and explainable — fraction of the question's words that appear in the answer — rather than reusing the RRF rank score, which isn't a bounded similarity measure. Below `PolyCareConfig.Routing.minSkillScore` (0.60, the existing τ threshold, reused rather than duplicated), the UI shows a low-confidence warning and logs a **gap**.
- There is no on-device LLM yet (M0). Rather than fake a generated answer, Ask shows the retrieved passage directly and labels it as such — this is exactly the Resource Governor's own **RECALL** rung ("no LLM: shows the best protocol passages"), so it isn't a workaround, it's the degradation ladder's own documented behaviour, arrived at naturally.
- **Triage** (`app/.../triage`, `TriageEngine`): rule table for newborn / child / postpartum danger signs, citing the same official sources already indexed for Search/Ask (`asha-module-6`, `asha-module-7`) rather than inventing separate citations. The decision always comes from the rule table (invariant 10: "the LLM only explains"); the explanation text is a template today, clearly labelled in the UI as pending the LLM. 5 unit tests cover every branch (no signs, one urgent sign, 24 h-only sign, urgent-beats-24h, postpartum bleeding).
- **Gaps** (`GapsRepository`): in-memory, HLC-timestamped (new `HlcClock`/`UuidV7` Hilt providers added to `AppModule`), logged whenever Ask has no result or low confidence. Per its own doc comment, this moves behind the op-log in M4 and syncs to the cloud in M7 — not built now, just designed not to block that later.
- Added debug launch extras `ask_query` and `open_triage`, matching the existing `search_query`/`embed_check` pattern, since this phone (like the Xiaomi) blocks `adb input` taps and there was no other way to drive the new screens from the PC.
- **Verified on the Realme:** `ask_query "baby has fast breathing"` → confidence 1.00, source `asha-induction`, correctly not flagged low-confidence, event logged. `open_triage true` → screen renders, no crash (`dumpsys activity top` confirms `MainActivity` in foreground, no `FATAL`/`AndroidRuntime` in logcat).
- **Deliberately not built:** voice input, skill routing/blending, LLM-generated explanations, speculative decoding gauge — all genuinely need llama.cpp (M0, not yet integrated by design, per the colleague's own status). Medicine/counselling "cards" — the content is indexed and searchable, but no dedicated card UI was added, to avoid scope creep beyond what M2 lists as required for triage/ask. Memory Inspector — untouched, per the earlier finding that it doesn't block M2.
- Docs updated to match: `MILESTONES.md` M2 checklist (2 fully done, 5 explicitly annotated with what's real vs. deferred and why), `STATUS.md` (M2 table, "what works today", "not built yet", known issues, verify commands).
