# PolyCare — Milestones

> Native **Kotlin** Android app. All AI runs **on the phone** (LLM, speech, OCR, embeddings, Qdrant Edge search). **Qdrant Cloud** is used to sync data when a connection is available.

---

## Problem statement checklist

Every requirement from the problem statement (PS-03) and where it is delivered.

| # | Requirement | Delivered in |
|---|---|---|
| 1 | Built on **Qdrant Edge** for local semantic memory and retrieval | M1 |
| 2 | Maintain searchable semantic memory directly on the device | M1, M4 |
| 3 | Low-latency vector **and hybrid** search without network access | M1, M8 |
| 4 | Dynamically decide what stays local and what gets synced | M6 |
| 5 | Support intermittent connectivity and keep working offline | M2, M6 |
| 6 | Sync data between edge devices and **Qdrant Server** (Qdrant Cloud) when connectivity returns | M6 |
| 7 | Handle evolving local memory, updates and conflicting information | M4, M5 |
| 8 | UI to inspect device memory, search results, sync status and system activity | M1, M6, M10 |
| 9 | A meaningful **edge-to-cloud AI workflow**, not just a local vector DB | M7 |
| 10 | Expected outcome: a complete edge-native AI product that remembers, retrieves, operates offline and syncs intelligently | M10 |
| — | Sensitive data cannot always leave the device | M3, M6 |
| — | Latency is critical; data changes continuously | M1, M8, M9 |

---

## What gets integrated

| Area | Integrations |
|---|---|
| App | Kotlin 2, Jetpack Compose, Material 3, Hilt, Coroutines/Flow, Navigation Compose |
| Vector search | **Qdrant Edge** (Rust crate built for Android with cargo-ndk + UniFFI), **Qdrant Cloud** |
| On-device LLM | llama.cpp (JNI), Qwen2.5-1.5B / 0.5B GGUF, LoRA health skills |
| Speech | whisper.cpp multilingual (Hindi + English) |
| OCR | Google ML Kit Text Recognition v2 (Latin + Devanagari), ML Kit Document Scanner |
| Embeddings | ONNX Runtime Mobile, multilingual-e5-small; BM25 sparse encoder |
| Storage | Room (SQLite), SQLCipher, zstd |
| Security | Android Keystore, Tink (ed25519), sha256 |
| Sync | WorkManager, OkHttp, Wire (Protobuf) |
| Device awareness | BatteryManager, thermal status, memory callbacks |
| Phone-to-phone (optional) | Google Nearby Connections |
| Cloud | Qdrant Cloud only for persistence, FastAPI gateway, Qdrant FastEmbed for shared knowledge; supervisor routes/dashboard are prototype code |
| Training (dev only) | Colab + Unsloth/PEFT, llama.cpp GGUF conversion |
| Testing | JUnit5, Turbine, Robolectric, pytest |

---

## Milestones

### Current completion overview (2026-09-29)

This checklist tracks implementation, not milestone order. **[x]** means implemented and verified,
**[~]** means code/UI exists but end-to-end or device/cloud verification is missing, and **[ ]** means
not implemented. A feature is not production complete just because a screen or endpoint exists.

| Milestone | Current state | Evidence / remaining gate |
|---|---|---|
| M0 Foundations | 9/10 verified | Qdrant Cloud is reachable and gateway collections are initialized; knowledge snapshot remains |
| M1 On-device knowledge/search | 5/5 verified | Offline search, memory inspector, and measured phone latency |
| M2 Offline assistant/triage | Core verified; voice and speculative paths partial | Android unit tests/build pass; live mic permission/device check outstanding |
| M3 Households/OCR/daily work | Partial verification | PP-OCRv5 English/Devanagari sample passes offline on the Xiaomi; household migration and camera/real-document checks remain |
| M4 Evolving memory | Partial implementation | Encrypted op-log and rebuild paths exist; op-log/recovery tests and full mutation coverage missing |
| M5 Conflicts | Partial implementation | Conflict inbox/file exchange exists; multi-device convergence and undo tests missing |
| M6 Qdrant Cloud sync | Partial implementation | A real phone registered, completed signed auth, and completed a pull-only sync through the USB tunnel; no queued ops or second phone to verify push/convergence |
| M7 Edge-to-cloud workflows | Partial implementation | Live phone polled radar, answers, Merkle, votes, and village guidance successfully against empty collections; populated radar/gap/supervisor workflows remain unverified |
| M8 Scale | Not implemented | No million-point device benchmark or cloud slice transfer |
| M9 Reliability | Partial implementation | Degradation/chaos controls exist; failure matrix and low-end-phone tests missing |
| M10 Complete product | In progress | Daily-task navigation and tool hub refreshed and visually checked on phone; Hindi text-overlap and WCAG AA text-contrast defects fixed; TalkBack audit, large-font pass, clinical test set, and release build remain |
| M11 Optional extras | Partial | Skill Factory exists; Nearby Connections and broader language coverage remain |

**Verification snapshot (2026-09-30):** `gradlew.bat test :app:assembleDebug` passes; the connected
Android suite passes **2/2 tests** on Xiaomi 2406ERN9CI (E5 reference parity and offline English /
Devanagari PaddleOCR). Gateway lockfile check and all 17 Python tests pass.
The supplied Qdrant Cloud credential was verified; gateway startup initialized the `answers`,
`auth_challenges`, `devices`, `guidance`, `knowledge`, `signals`, and `sync_ops` collections. On
2026-09-29, a Xiaomi 2406ERN9CI completed live health check, device registration, signed challenge
authentication, and pull requests for alerts, answers, Merkle, votes, and village-filtered guidance
through a USB reverse tunnel. The collections were empty, so no ops were pushed and all pulled lists
were empty. Populated clinical workflows and a two-phone integration run remain unverified.
The OCR suite uses the official pinned PP-OCRv5 models. The previous OpenCV 4.5.3 native library
could not load on Android 16; upgrading to OpenCV 4.10.0 fixed initialization and the physical-device
test now reads both scripts.

**Known discrepancy:** `STATUS.md`, `COMPLETION_ROADMAP.md`, and implementation summaries include
claims from untested code. This overview separates verified behavior from code that merely exists.

### M0 — Foundations
- [x] Android project: app + core modules, Compose, Hilt, builds and installs on a real phone over adb
- [x] **Qdrant Edge** compiled for arm64 and callable from Kotlin: create shard, upsert, search
- [x] Hybrid search (dense + sparse + RRF) on Qdrant Edge, or RRF in Kotlin
- [x] llama.cpp runs Qwen2.5-1.5B on the phone; tokens/sec measured *(5.55 tok/s decode after fixing a Debug-vs-Release native build bug — see WORKLOG)*
- [x] Two LoRA adapters loaded and switched per request *(both skills rebuilt for Qwen2.5-0.5B, SHA-256/size verified, installed on Xiaomi 2406ERN9CI, and loaded/generated through the device skill-check hook. The generic prompt produced matching skill answers, so domain-specific quality still needs evaluation.)*
- [x] Multilingual embedder runs under ~30 ms per query
- [x] whisper.cpp transcribes a clip offline *(English sample verified word-for-word; Hindi tested via an on-device TTS-synthesised fixture — the `base` model came back wrong-script garbage, swapped to `small` and Hindi now transcribes correctly in Devanagari; a real recorded human voice, not just TTS, is the one remaining gap — see STATUS)*
- [x] ML Kit reads a sample MCP card (English + Devanagari) *(synthetic test card; both scripts read correctly on-device)*
- [~] Qdrant Cloud cluster created and reachable; partial snapshot pulled and applied on the phone *(gateway collections initialized; knowledge snapshot not installed)*
- [x] One health LoRA skill trained and converted to GGUF *(two, in fact: `maternal-newborn` and `child-health`, real PEFT LoRA on real ASHA passages, converted to GGUF, verified on-device — see STATUS)*

**Done when:** every item works, or a fallback is chosen and written down.

### M1 — On-device knowledge and search
- [x] Knowledge base built from ASHA modules, immunisation schedule, drug list and health-education content *(1,240 passages, 6 documents, English + Hindi — see STATUS)*
- [x] Hybrid search with filters (topic, language, programme) *(dense + BM25 sparse + RRF, keyword indexes on source/lang/programme/quality)*
- [x] **Search** screen shows results, scores and sources *(on phone, English and Hindi)*
- [x] **Memory Inspector** screen: browse and filter what the phone knows *(overview, filter chips, paginated browse — `QdrantEdgeVectorStore.facets()`/`.scroll()`, on-phone tested)*
- [x] Search latency measured *(cold: embed 27ms/search 26ms; warm: embed ~20ms/search ~10–17ms)*

**Done when:** correct protocol passages come back in airplane mode. **M1 complete, 5/5** (this checklist was out of sync with STATUS.md, which already recorded it done — fixed 2026-09-28).

### M2 — Offline health assistant
- [~] Voice or text question → skill routing → retrieval → streamed answer *(text path verified on-device; current pulled code compiles and unit tests pass; live mic capture remains unverified)*
- [x] Skill blending when a question spans two areas *(verified on-device: a pregnancy question routed `maternal-newborn=0.75, child-health=0.25`, a blended generation completed cleanly — see STATUS)*
- [x] Answer shows skill, sources and confidence badge; low confidence adds referral advice *(`SkillRouter` picks the skill via cosine similarity on skill cards — ARCHITECTURE.md §5.1 — and Ask shows its name next to the tok/s line; verified on-device both directions, see STATUS)*
- [x] **Danger-sign triage**: Refer now / Refer within 24 h / Care at home, decided by rules, explained by the LLM *(rule decision real and tested — 5 unit tests, every branch; `TriageViewModel` calls the real LLM via `PromptFormat.triageExplanation`, shown separately below the rule engine's own template text, falls back to the template if the model isn't installed — verified on-device earlier this session, see STATUS)*
- [~] Medicine helper and counselling cards *(dedicated screen/view-model now exists; pulled changes still need Android build and UI/device verification)*
- [x] Unanswered questions saved as **gaps**
- [x] Models and skills verified by sha256 before loading; fallback on failure *(via `ArtifactVerifier`, already used for the embedder and knowledge base)*
- [~] **Speculative decoding from memory** with toggle and tokens/sec gauge *(implementation added; speedup and behavior are unmeasured)*

**Done when:** an ASHA gets a sourced answer and a triage decision with no signal. See [STATUS.md](STATUS.md) for exactly what runs today vs. what is templated pending the on-device LLM.

### M3 — Households, OCR and daily work
- [x] Household and member records with consent capture *(`HouseholdsRepository`/`HouseholdsScreen`; in-memory MVP, same honest pattern as `GapsRepository` — M4 moves both behind the op-log per invariant 1. A member cannot be added unless the household's consent checkbox was set when it was registered — enforced in the repository, not just the UI, and verified on-device via `--ez household_check true`: blocked without consent, allowed with consent. Wired into Home's tile and the nav drawer, no longer a "coming later" placeholder — see STATUS)*
- [~] **OCR scan** of MCP cards, lab reports, prescriptions and medicine strips → confirmed fields in the household record *(`McpFieldExtractor` handles English+Devanagari; PP-OCRv5 English and Hindi recognizers now pass an offline physical-device sample on Android 16; real camera capture, real-world documents, and end-to-end save still need checking)*
- [x] Visit notes searchable by meaning *(`HouseholdsRepository.searchVisits()`: cosine-similarity on embedded note vectors when the embedder is ready, term-overlap fallback when not; asynchronous embedding on every `recordVisit()` call; wired into `DueListScreen`'s "Search notes" tab — see STATUS)*
- [x] **Due list and visit planner** *(`DueListScreen`/`DueListViewModel`; seeded with realistic ASHA due items: ANC, PNC, immunization, family planning; filter chips by visit type; tap to expand → enter notes, flag high-risk, claim incentive; `recordDueVisit()` marks the item completed. Wired into `PolyCareRoot`, nav drawer, and Home tile. `--ez due_list_check true` debug hook exercises visit recording, due-item completion, search, and report on hardware. See STATUS)*
- [x] Monthly report and incentive tracker filled from visits *(`HouseholdsRepository.monthlyReport()` aggregates all recorded visits by type; `DueListScreen` "Monthly report" tab shows per-type visit counts and incentive lines, total ASHA incentive, and an export-for-PHC-meeting button. See STATUS)*
- [~] Household data encrypted on the phone and never synced *(AES-GCM Keystore-backed persistence exists; migration/recovery needs physical-device verification. Household data is filtered from sync in code, but no live sync test has been run.)*

**Done when:** a visit can be recorded from a scan and shows up in the due list and report.

### M4 — Evolving memory
- [~] Every change goes through the encrypted op-log first *(household/member/visit/gap/signal paths are wired; no dedicated op-log test suite yet)*
- [~] Hybrid logical clock ordering; wrong phone clocks are harmless *(HLC and skew hooks exist; sync convergence unverified)*
- [~] Edits and deletes handled correctly; deleted items never come back *(edit/delete/consent withdrawal and compaction code exists; replay/recovery tests missing)*
- [~] Private vs team visibility; De-identifier strips identity from anything shareable *(Sync Gate and strict gateway schemas exist; end-to-end privacy test missing)*
- [~] Search index rebuilt from the op-log if lost *(rebuild action exists; recovery test missing)*

**Done when:** memory can be edited freely and rebuilt from scratch with nothing lost.

### M5 — Conflicting information
- [~] Disagreeing near-duplicates detected and marked **disputed** *(detector and tip contradiction check exist; field behavior unverified)*
- [~] **Conflict Inbox** with side-by-side view and suggested merge *(screen and repository exist; no end-to-end test)*
- [~] Merges keep the originals and can be undone *(local resolution history exists; undo/convergence not tested)*
- [~] Answers built on disputed items show a warning *(Ask labels shared tips whose persisted status is DISPUTED; the import-to-conflict-to-answer path has not been exercised end-to-end.)*

### M6 — Sync with Qdrant Cloud
- [x] Device registration and signed authentication with the gateway *(verified from Xiaomi 2406ERN9CI against the live Qdrant-backed gateway)*
- [~] **Push** team knowledge, de-identified signals and gaps; **pull** answers and alerts *(live phone pull endpoints returned successfully with empty collections; no pending op existed to exercise push; phone does not use `/v1/ops/pull`)*
- [~] **Sync Gate**: private never leaves; redundant items send only a "+1"; new, widely useful knowledge goes first *(new core-common tests cover personal entities, PII keys, wire-shape requirements, metered deferral, and votes; two-device privacy verification remains open)*
- [~] Only topics that differ are exchanged; screen shows diverged topics and bytes saved *(Merkle/team memory code exists; no two-device verification)*
- [~] Stable-window wait, backoff, metered-data and low-battery rules *(WorkManager constraints exist; device validation needed)*
- [~] Killed mid-sync → resumes with no loss and no duplicates *(chaos hook exists; scenario not tested)*
- [~] **Sync & Activity** screen *(live connection/auth/sync completed; no-op queue and empty cloud response; UI button tap and non-empty queue behavior remain unverified)*

**Done when:** two phones edit offline, reconnect, and share team knowledge through Qdrant Cloud with household data untouched.

### M7 — Edge-to-cloud AI workflows
- [~] **Gap answering**: offline questions answered by the cloud and approved by a supervisor *(routes pass local tests; live supervisor publish/phone retrieval not exercised)*
- [~] **Outbreak Radar**: live phone alert poll succeeded against the cloud; no signals existed, so clustering and alert delivery remain unverified
- [ ] **Knowledge slicing**: partial snapshot selection/refresh is not implemented
- [~] **Skill delivery**: signed artifact server, publisher and client downloader exist; full deploy/install test missing
- [ ] Staleness lowers confidence on answers that rely on long-unsynced memory
- [~] **Supervisor dashboard** (web): gateway routes load and cloud stats were verified; populated supervisor workflows and dashboard browser flow remain unverified

**Done when:** an offline gap on Phone A is answered after sync, and a radar alert reaches phones in the affected villages.

### M8 — A million points on the phone
- [ ] Quantized knowledge slice of ~1 M points installed on the phone
- [ ] Storage, RAM and search latency measured and shown in the app
- [ ] Slice refreshed from the cloud by partial snapshot

**Done when:** a hybrid search over ~1 M points runs offline on the demo phone with measured numbers.

### M9 — Reliability and low-end phones
- [~] **Degradation ladder**: full → lean → single skill → base → retrieval-only *(ladder code exists; new Android changes not rebuilt in this environment)*
- [~] Low-RAM mode with the smaller model *(mode/configuration code exists; low-RAM handset verification missing)*
- [~] Storage quotas and resumable, verified downloads *(artifact HTTP range and client resume code exist; interruption tests missing)*
- [~] **Chaos Panel** *(several failure toggles exist; full matrix not executed)*

**Done when:** every Chaos Panel scenario recovers on its own.

### M10 — Complete product
- [ ] Screens: Ask, Triage, Scan, Households, Due List, Skills Shelf, Memory Inspector, Search, Sync & Activity, Conflict Inbox, Settings
- [ ] Hindi and English UI *(content, speech and retrieval are bilingual and verified; the UI chrome itself is still hardcoded English. `res/values-hi/strings.xml` carries the translations but is unreferenced, so only the launcher label localises today)*
- [ ] First-run setup: model download, device registration, consent, sample data
- [ ] Clinical safety test set passes
- [ ] Unit tests for op-log, sync and conflicts; end-to-end tests on a real phone
- [ ] Release APK, README and setup guide
- [ ] Problem statement checklist fully ticked

### M11 — Optional extras
- [ ] Phone-to-phone sync over Nearby Connections
- [ ] More Indian languages
- [ ] Automatic Skill Factory from field knowledge
