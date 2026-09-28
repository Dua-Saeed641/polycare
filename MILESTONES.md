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
- [ ] Android project: app + core modules, Compose, Hilt, builds and installs on a real phone over adb
- [ ] **Qdrant Edge** compiled for arm64 and callable from Kotlin: create shard, upsert, search
- [ ] Hybrid search (dense + sparse + RRF) on Qdrant Edge, or RRF in Kotlin
- [ ] llama.cpp runs Qwen2.5-1.5B on the phone; tokens/sec measured
- [ ] Two LoRA adapters loaded and switched per request
- [ ] Multilingual embedder runs under ~30 ms per query
- [ ] whisper.cpp transcribes a Hindi clip offline
- [ ] ML Kit reads a sample MCP card (English + Devanagari)
- [ ] Qdrant Cloud cluster created; partial snapshot pulled and applied on the phone
- [ ] One health LoRA skill trained and converted to GGUF

**Done when:** every item works, or a fallback is chosen and written down.

### M1 — On-device knowledge and search
- [ ] Knowledge base built from ASHA modules, immunisation schedule, drug list and health-education content
- [ ] Hybrid search with filters (topic, language, programme)
- [ ] **Search** screen shows results, scores and sources
- [ ] **Memory Inspector** screen: browse and filter what the phone knows
- [ ] Search latency measured

**Done when:** correct protocol passages come back in airplane mode.

### M2 — Offline health assistant
- [ ] Voice or text question → skill routing → retrieval → streamed answer *(text→retrieval→answer works; voice and skill routing need whisper.cpp/LoRA, M0)*
- [ ] Skill blending when a question spans two areas *(no skills exist yet, M0)*
- [ ] Answer shows skill, sources and confidence badge; low confidence adds referral advice *(sources, confidence badge and referral advice work; no "skill" name yet)*
- [ ] **Danger-sign triage**: Refer now / Refer within 24 h / Care at home, decided by rules, explained by the LLM *(the rule decision is real and tested; the LLM explanation is a template until M0's LLM lands)*
- [ ] Medicine helper and counselling cards *(the content is indexed and searchable via Ask/Search; no dedicated card UI yet)*
- [x] Unanswered questions saved as **gaps**
- [x] Models and skills verified by sha256 before loading; fallback on failure *(via `ArtifactVerifier`, already used for the embedder and knowledge base)*
- [ ] **Speculative decoding from memory** with toggle and tokens/sec gauge *(needs llama.cpp, M0)*

**Done when:** an ASHA gets a sourced answer and a triage decision with no signal. See [STATUS.md](STATUS.md) for exactly what runs today vs. what is templated pending the on-device LLM.

### M3 — Households, OCR and daily work
- [ ] Household and member records with consent capture
- [ ] **OCR scan** of MCP cards, lab reports, prescriptions and medicine strips → confirmed fields in the household record
- [ ] Visit notes searchable by meaning
- [ ] **Due list and visit planner**
- [ ] Monthly report and incentive tracker filled from visits
- [ ] Household data encrypted on the phone and never synced

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
