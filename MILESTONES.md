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
| Cloud | Qdrant Cloud, FastAPI gateway, PostgreSQL, MinIO/S3, Redis + ARQ, Qwen2.5-7B (Ollama/vLLM), Next.js dashboard |
| Training (dev only) | Colab + Unsloth/PEFT, llama.cpp GGUF conversion |
| Testing | JUnit5, Turbine, Robolectric, pytest |

---

## Milestones

### M0 — Foundations
- [x] Android project: app + core modules, Compose, Hilt, builds and installs on a real phone over adb
- [x] **Qdrant Edge** compiled for arm64 and callable from Kotlin: create shard, upsert, search
- [x] Hybrid search (dense + sparse + RRF) on Qdrant Edge, or RRF in Kotlin
- [x] llama.cpp runs Qwen2.5-1.5B on the phone; tokens/sec measured *(5.55 tok/s decode after fixing a Debug-vs-Release native build bug — see WORKLOG)*
- [x] Two LoRA adapters loaded and switched per request *(both real, trained adapters — `maternal-newborn` + `child-health` — loaded and hot-swapped on-device; `LlamaEngineTest.loadsTwoSkillsAndSwitchesBetweenThem` passes, 59.4s. Both changed the base model's output; the two skills didn't differ from each other on this one generic test prompt — see STATUS)*
- [x] Multilingual embedder runs under ~30 ms per query
- [x] whisper.cpp transcribes a clip offline *(English sample verified word-for-word; Hindi tested via an on-device TTS-synthesised fixture — the `base` model came back wrong-script garbage, swapped to `small` and Hindi now transcribes correctly in Devanagari; a real recorded human voice, not just TTS, is the one remaining gap — see STATUS)*
- [x] ML Kit reads a sample MCP card (English + Devanagari) *(synthetic test card; both scripts read correctly on-device)*
- [ ] Qdrant Cloud cluster created; partial snapshot pulled and applied on the phone *(not started — needs a Qdrant Cloud account)*
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
- [ ] Voice or text question → skill routing → retrieval → streamed answer *(text→skill routing→retrieval→streamed answer fully works and is verified on-device — see STATUS. Voice: mic button, `VoiceRecorder`/`AudioRecord` capture, and transcribe-then-ask wiring are built and compile; live on-device capture itself is NOT yet verified — this MIUI build blocks both `adb input` taps and `adb shell pm grant`/`install -g`, so granting RECORD_AUDIO needs a human tap this session couldn't perform. Try the second test phone or tap "Allow" once by hand.)*
- [x] Skill blending when a question spans two areas *(verified on-device: a pregnancy question routed `maternal-newborn=0.75, child-health=0.25`, a blended generation completed cleanly — see STATUS)*
- [x] Answer shows skill, sources and confidence badge; low confidence adds referral advice *(`SkillRouter` picks the skill via cosine similarity on skill cards — ARCHITECTURE.md §5.1 — and Ask shows its name next to the tok/s line; verified on-device both directions, see STATUS)*
- [x] **Danger-sign triage**: Refer now / Refer within 24 h / Care at home, decided by rules, explained by the LLM *(rule decision real and tested — 5 unit tests, every branch; `TriageViewModel` calls the real LLM via `PromptFormat.triageExplanation`, shown separately below the rule engine's own template text, falls back to the template if the model isn't installed — verified on-device earlier this session, see STATUS)*
- [ ] Medicine helper and counselling cards *(the content is indexed and searchable via Ask/Search; no dedicated card UI yet)*
- [x] Unanswered questions saved as **gaps**
- [x] Models and skills verified by sha256 before loading; fallback on failure *(via `ArtifactVerifier`, already used for the embedder and knowledge base)*
- [ ] **Speculative decoding from memory** with toggle and tokens/sec gauge *(needs llama.cpp, M0)*

**Done when:** an ASHA gets a sourced answer and a triage decision with no signal. See [STATUS.md](STATUS.md) for exactly what runs today vs. what is templated pending the on-device LLM.

### M3 — Households, OCR and daily work
- [x] Household and member records with consent capture *(`HouseholdsRepository`/`HouseholdsScreen`; in-memory MVP, same honest pattern as `GapsRepository` — M4 moves both behind the op-log per invariant 1. A member cannot be added unless the household's consent checkbox was set when it was registered — enforced in the repository, not just the UI, and verified on-device via `--ez household_check true`: blocked without consent, allowed with consent. Wired into Home's tile and the nav drawer, no longer a "coming later" placeholder — see STATUS)*
- [x] **OCR scan** of MCP cards, lab reports, prescriptions and medicine strips → confirmed fields in the household record *(`McpFieldExtractor`: keyword+regex on English+Devanagari OCR output extracts name/age/village/docType/clinicalNotes; `McpConfirmationCard` in `ScanScreen` shows pre-filled editable fields + consent gate; `ScanViewModel.saveAsHousehold()` creates household + member + ROUTINE visit on save; 4 unit tests. See STATUS)*
- [x] Visit notes searchable by meaning *(`HouseholdsRepository.searchVisits()`: cosine-similarity on embedded note vectors when the embedder is ready, term-overlap fallback when not; asynchronous embedding on every `recordVisit()` call; wired into `DueListScreen`'s "Search notes" tab — see STATUS)*
- [x] **Due list and visit planner** *(`DueListScreen`/`DueListViewModel`; seeded with realistic ASHA due items: ANC, PNC, immunization, family planning; filter chips by visit type; tap to expand → enter notes, flag high-risk, claim incentive; `recordDueVisit()` marks the item completed. Wired into `PolyCareRoot`, nav drawer, and Home tile. `--ez due_list_check true` debug hook exercises visit recording, due-item completion, search, and report on hardware. See STATUS)*
- [x] Monthly report and incentive tracker filled from visits *(`HouseholdsRepository.monthlyReport()` aggregates all recorded visits by type; `DueListScreen` "Monthly report" tab shows per-type visit counts and incentive lines, total ASHA incentive, and an export-for-PHC-meeting button. See STATUS)*
- [ ] Household data encrypted on the phone and never synced *(never synced: true today, trivially — nothing syncs yet. Encrypted at rest: not applicable yet since records are in-memory only and never touch disk; becomes a real, non-trivial requirement once M4's op-log persists them)*

**Done when:** a visit can be recorded from a scan and shows up in the due list and report.

### M4 — Evolving memory
- [ ] Every change goes through the op-log first
- [ ] Hybrid logical clock ordering; wrong phone clocks are harmless
- [ ] Edits and deletes handled correctly; deleted items never come back
- [ ] Private vs team visibility; De-identifier strips identity from anything shareable
- [ ] Search index rebuilt from the op-log if lost

**Done when:** memory can be edited freely and rebuilt from scratch with nothing lost.

### M5 — Conflicting information
- [ ] Disagreeing near-duplicates detected and marked **disputed**
- [ ] **Conflict Inbox** with side-by-side view and suggested merge
- [ ] Merges keep the originals and can be undone
- [ ] Answers built on disputed items show a warning

### M6 — Sync with Qdrant Cloud
- [ ] Device registration and signed authentication with the gateway
- [ ] **Push** team knowledge, de-identified signals and gaps; **pull** team knowledge, answers, alerts and skills
- [ ] **Sync Gate**: private never leaves; redundant items send only a "+1"; new, widely useful knowledge goes first
- [ ] Only topics that differ are exchanged; screen shows diverged topics and bytes saved
- [ ] Stable-window wait, backoff, metered-data and low-battery rules
- [ ] Killed mid-sync → resumes with no loss and no duplicates (tested)
- [ ] **Sync & Activity** screen: status, queue, what was pushed / kept private / voted, errors

**Done when:** two phones edit offline, reconnect, and share team knowledge through Qdrant Cloud with household data untouched.

### M7 — Edge-to-cloud AI workflows
- [ ] **Gap answering**: offline questions answered by the cloud and approved by a supervisor
- [ ] **Outbreak Radar**: similar symptom signals across villages raise an alert; guidance pushed to phones
- [ ] **Knowledge slicing**: the cloud chooses and refreshes each phone's knowledge slice
- [ ] **Skill delivery**: new skills downloaded, verified and routed to
- [ ] Staleness lowers confidence on answers that rely on long-unsynced memory
- [ ] **Supervisor dashboard** (web): alerts, referrals, gaps, coverage

**Done when:** an offline gap on Phone A is answered after sync, and a radar alert reaches phones in the affected villages.

### M8 — A million points on the phone
- [ ] Quantized knowledge slice of ~1 M points installed on the phone
- [ ] Storage, RAM and search latency measured and shown in the app
- [ ] Slice refreshed from the cloud by partial snapshot

**Done when:** a hybrid search over ~1 M points runs offline on the demo phone with measured numbers.

### M9 — Reliability and low-end phones
- [ ] **Degradation ladder**: full → lean → single skill → base → retrieval-only
- [ ] Low-RAM mode with the smaller model
- [ ] Storage quotas and resumable, verified downloads
- [ ] **Chaos Panel**: kill mid-sync, skew clock, corrupt a skill, drop network, simulate low memory / heat

**Done when:** every Chaos Panel scenario recovers on its own.

### M10 — Complete product
- [ ] Screens: Ask, Triage, Scan, Households, Due List, Skills Shelf, Memory Inspector, Search, Sync & Activity, Conflict Inbox, Settings
- [ ] Hindi and English UI
- [ ] First-run setup: model download, device registration, consent, sample data
- [ ] Clinical safety test set passes
- [ ] Unit tests for op-log, sync and conflicts; end-to-end tests on a real phone
- [ ] Release APK, README and setup guide
- [ ] Problem statement checklist fully ticked

### M11 — Optional extras
- [ ] Phone-to-phone sync over Nearby Connections
- [ ] More Indian languages
- [ ] Automatic Skill Factory from field knowledge
