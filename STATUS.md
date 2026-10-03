# PolyCare — Status

Detailed implementation log; the live completion overview is in [MILESTONES.md](MILESTONES.md). History and reasoning are in [WORKLOG.md](WORKLOG.md).

**Last updated:** 2026-10-03 · MILESTONES.md is the live completion overview. This file retains implementation detail; older milestone references are historical. · **Test phones:** Xiaomi 2406ERN9CI, Android 16, 6 GB class; Realme RMX2151, Android 12, 6 GB class

---

## Latest verification: Hindi text layout, colour contrast and doc integrity (2026-10-03)

- Fixed a visible layout defect seen on the phone: on the Ask card the Hindi source line
  ("आशा मॉड्यूल 7 - ... · p9") was painted on top of the "Source" label. `MetricRow` measured its
  value `Text` unbounded inside an `Arrangement.SpaceBetween` row, so a long Devanagari string
  overflowed its slot instead of wrapping. Hindi titles are routinely longer than their English
  counterparts, so this hit the primary Hindi reader on the most-used screen. The value is now
  bounded with `weight` and wraps; a new `stacked` variant puts the label above the value and is
  used for sentence-length values (Ask/Triage source, Device Check phone model). English is
  unaffected.
- Audited brand contrast against WCAG AA and fixed the failures. `Brand.Rose` (3.48:1 on paper)
  and `Brand.Red` (3.73:1) are brand-accurate but below the 4.5:1 body-text minimum. Added
  `Brand.RoseInk` (6.6:1) and `Brand.RedInk` (5.9:1) and switched the 13 places where those colours
  are *read as text* (overdue dates, "High risk", warning and consent lines, sync errors, the
  `REFER_NOW` triage decision). The vivid `Rose`/`Red` are kept for fills, dots, borders and
  accent edges, where they are decorative or always sit beside a text label. Triage now carries
  two colours per severity, `color` for the dot/accent and `inkColor` for the sentence.
- Restored `PROJECT_DESCRIPTION.md`, which had been emptied to 0 bytes in the working tree while
  `CLAUDE.md` tells every contributor to read it first. Verified byte-exact against
  `git cat-file` (blob `04e6e40`), so it is the committed text and not a re-typed approximation.
- Not fixed, deliberately: `res/values-hi/strings.xml` holds 19 correct Hindi strings, but nothing
  in the app calls `R.string.*` - the UI is hardcoded English, so the Hindi overlay only ever
  changed the launcher label. Localising the whole UI is the real M10 "Hindi and English UI"
  work, not a drive-by fix, and the file is kept so the translations are not lost.
- Verified with `gradlew.bat :app:compileDebugKotlin` (BUILD SUCCESSFUL). Contrast ratios are
  computed from the palette, not measured on-device, and a real TalkBack pass and a
  large-font pass are still open - see MILESTONES M10.


## Latest verification: OCR, device tests, navigation and accessibility (2026-09-30)

- Follow-up audit found the phone still had 1.5B-era LoRA files while the shipped base model is
  Qwen2.5-0.5B. Rebuilt both skills from the pinned 0.5B base and the tracked knowledge report,
  fixed `train_skill.py` to discard stale entries when the base changes, verified sizes and hashes,
  and installed both adapters on Xiaomi 2406ERN9CI. The device skill-check loaded and generated with
  both adapters without a manifest rejection. On its single generic prompt both adapters returned
  the same answer; domain-specific evaluation remains open. Household data and the op-log were not
  touched.

- The connected Android test suite passes **2/2** on Xiaomi 2406ERN9CI: E5 embedding parity and
  offline English + Devanagari PaddleOCR PP-OCRv5 recognition.
- The OCR test initially failed before recognition: OpenCV 4.5.3's native library referenced
  `__sfp_handle_exceptions`, which Android 16 could not load. Switched from the old QuickBird
  package to the official `org.opencv:opencv:4.10.0` Android artifact; both recognizers now run.
  The model files are the project's revision- and SHA-256-pinned PaddlePaddle exports.
- Refocused bottom navigation on five common jobs: Today, Ask, Triage, Families, and More. Deep
  tool destinations keep More selected; the grouped More screen gives every secondary tool a
  plain-language title and explanation. Home now prioritizes asking, danger-sign triage, today's
  visits, and scanning instead of a nine-tile tool grid. The Home menu action opens the same More
  hub, avoiding two competing navigation systems.
- Visually checked Home and More on the phone; Android's UI hierarchy exposes all five bottom labels
  and More's section labels, descriptions, and scrollable destinations. At the current system text
  scale, destination rows expose focusable/clickable parent nodes. An attempt to raise the phone's
  font scale was denied by Android's WRITE_SETTINGS policy, so the original 1.0 setting was retained.
  A TalkBack user review and formal contrast/font-scale audit have not been completed.
- `gradlew.bat test :app:assembleDebug` passes after these changes. Connected tests are recorded in
  `android/app/build/outputs/androidTest-results/connected/debug`; the 17 gateway tests had passed
  in the earlier live gateway repair.
- Added seven Android core-common regression cases for Sync Gate privacy/wire-shape decisions and
  concurrent field conflict behavior. The follow-up `gradlew.bat test` run passes with them.

---

## Latest verification: Qdrant gateway and live cluster

- Repaired `cloud/gateway/app/team.py`: restored `build_router`, the device-or-supervisor dependency,
  pagination helpers, and the gap-answer, radar, Merkle, guidance, vote, and dashboard routes.
- Added an in-memory integration test for supervisor answers, authenticated answer retrieval,
  village-filtered guidance, and Merkle route construction. All **17 gateway tests pass**; `uv lock --check` passes.
- Verified the live cluster from gateway startup. It now contains the seven empty collections the
  gateway expects: `answers`, `auth_challenges`, `devices`, `guidance`, `knowledge`, `signals`, and
  `sync_ops`.
- Live `/healthz`, `/v1/radar/alerts`, and `/v1/supervisor/stats` requests returned 200. The cluster
  is empty (zero devices, signals, gaps, or answers); phone enrollment/sync and populated radar flows
  still need end-to-end verification.
- The Android unit tests and debug/test APK builds passed earlier; instrumentation still needs a handset.

## Latest verification: real phone sync over USB (2026-09-29)

- The connected Xiaomi 2406ERN9CI (Android 16) was updated in place with the current debug APK,
  preserving its app data. A debug-only `sync_check` launch extra now invokes the injected
  `SyncRepository` so device sync can be verified even when MIUI blocks ADB touch injection.
- The app reached the local gateway through `adb reverse`, registered its Ed25519 key, completed
  signed challenge authentication, and completed `syncNow()` against the live Qdrant Cloud cluster.
  Gateway logs confirm successful 200 responses for radar alerts, answers, Merkle, votes, and
  village-filtered guidance.
- This was a no-op push: the handset had no pending operations, and the cloud collections contained
  no signals, gaps, answers, or guidance. Thus auth and pull transport are verified; signed op push,
  populated alerts/gap answering, and two-phone convergence are still open.
- The temporary settings file and USB reverse tunnel have been removed. The phone's pre-existing app
  data was preserved; the successful device registration remains in Qdrant. `:app:assembleDebug`
  passed after adding the sync check; gateway unit tests and `uv lock --check` passed in the earlier
  gateway repair step.

## Earlier verification snapshot (2026-09-29 late): merged with the OCR/sync-gateway work; later superseded by the USB sync verification above.

**Nothing in this section has been run or tested on a phone.** It compiles (`:core-common:compileKotlin`, `:app:compileDebugKotlin`, a clean `:core-llm:buildCMakeDebug[arm64-v8a]`, unit-test sources) and that is all that was checked.

**Merge rule used:** where our work overlapped LovekeshAnand's commits (`ocr-package`, `fastembed-fix`), theirs won; where it did not, ours stayed. Concretely: household encryption is **their** `HouseholdStoreCipher` (encrypted file in the no-backup dir, storage-health warning); the gateway is **their** Qdrant-backed one (`cloud/gateway/app`) and `proto/sync.proto` is the wire contract; GPU-layer plumbing and CMake flags in `core-llm` are theirs (Vulkan is still OFF, so it does nothing yet); OCR is their PaddleOCR module. Our first, SQLite-based gateway and its docker-compose were deleted.

- **Phone sync client for their protocol** (`sync/`): registers the phone's Ed25519 key (enrollment token), authenticates by signing the challenge nonce, and pushes **signed, hash-chained** ops. Signals are shaped exactly as their gateway demands: a 384-value float16 e5 embedding, embedding model id, 16-bit SimHash, village code, ISO week, age band, sex. Only signals and gaps ever leave; households, members and visits are kept local by the Sync Gate and refused again by the gateway. The op-log cursor and chain head advance together only after the gateway acks the exact head we computed. Ed25519 comes from BouncyCastle (the platform only has it from API 33).
- **Op-log** (`sync/OpLogStore.kt`): every household/member/visit/gap/signal mutation is appended (encrypted, fsynced) before the view changes. Households still load from their encrypted store; only gaps and signals are rebuilt from the log.
- **Outbreak Radar** (`radar/`, `core-common/.../OutbreakRadar.kt`): Triage can log a de-identified case (danger signs, village, week, age band, sex); the phone clusters its own signals, and the gateway clusters everyone's embeddings and returns alerts.
- **Team features added to their gateway** (`cloud/gateway/app/team.py`, kept separate so their sync code is unchanged): a `GAP` op kind, supervisor answers (`POST /v1/supervisor/answers`, phones fetch `GET /v1/answers`), `GET /v1/radar/alerts`, and a supervisor dashboard at `/dashboard` (needs `POLYCARE_SUPERVISOR_TOKEN`). Tests in `tests/test_team.py`.
- **Conflict Inbox** (`conflicts/`): import/export a teammate's consented-household file, field-level concurrent-edit detection, both values kept, every resolution undoable.
- **Medicines & counselling cards** (`medicine/`): 15 topics, each a query into the cited knowledge base (no invented clinical text).
- **Faster answers** (`jni_bridge.cpp`): KV-cache prefix reuse, greedy decoding, exact prompt-lookup speculative decoding, model loaded and warmed at app start. Speedup is *unmeasured*; measure with `--ez llm_check true`.
- **UI accessibility**: shared controls (`ui/components/Controls.kt`: 48 dp targets, roles, labels, live regions), an always-labelled bottom bar, real field labels, a real CSV export for the monthly report (counts only), real ISO visit dates.
- **Build fix (Windows):** a space in the user name broke the NDK link (`clang++` short path). Local fix: junction `C:\androidsdk` to the SDK and `android/local.properties` with `sdk.dir=C\:/androidsdk` (gitignored).

**Second pass (same day): the remaining feature gaps, all still untested on a phone.**

- **Households can be edited, deleted and have consent withdrawn** (everything about a family is erased, as delete ops); visits can be logged from the Households screen with a follow-up date; the demo households are gone, so a new install starts empty; the Due list is driven by real ISO dates (Overdue / Today / Tomorrow); the household store can be rebuilt from the op-log after a storage failure (explicit button); the op-log can be compacted (`OpLogStore.compact`, sequence numbers preserved).
- **Background sync** through WorkManager (survives the app being closed and killed), with a small data budget on metered connections. Release builds are **https only**; debug builds still allow a LAN `http://` gateway (`src/debug/res/xml/network_security_config.xml`).
- **Team memory:** share a tip (`Team tips`), novelty gate turns near-duplicates into votes, tips from other phones arrive via semantic-Merkle reconciliation and are signature-checked, tips show next to answers in Ask, possible contradictions go to the Conflict Inbox (optional on-device model check).
- **Guidance cards** from supervisors reach the villages an alert names (Home banner); **signed updates** (skills and knowledge) download with resume, are verified against a pinned publisher key, and install atomically (Sync -> Updates). `cloud/skill-factory/factory.py` turns supervisor answers plus knowledge into a new LoRA skill and publishes it.
- **Speculative decoding** now also drafts from the other retrieved passages and tips ("from memory"). **Vulkan** is an opt-in build flag (`-PpolycareVulkan=true`) plus a System toggle; default builds are unchanged.
- **Chaos panel** (System, debug builds only): gateway unreachable, connection lost or process killed right after the gateway accepts a chunk, clock 10 min fast/slow, forced degradation rung.
- Gateway: `TIP` kind, merkle/fetch/votes endpoints, guidance, artifact server with `Range`, rate limit, `kind` payload index.

**Known limits:** their gateway must run as a single replica and its pull scrolls and sorts (their note); the merkle tree is rebuilt per request from a scan. Signals recorded while the search model is missing stay on the phone. The phone does not use `/v1/ops/pull`. A tip conflict resolved on one phone is not propagated (no `Merge` op). No Qdrant partial-snapshot transfer. No web dashboard beyond the single supervisor page. Phone-to-phone transfer exists only as the Conflict Inbox file exchange. LoRA adapters still have to be trained. The 1 M-point scale measurement and a low-end-phone pass have not been done (they are measurements, not code).

---

## Latest (2026-09-29 night): persistence for Households, and a corrected UI direction

- **Households/Members/Visits/DueItems now persist locally** — first introduced as app-private JSON, then upgraded to authenticated AES-GCM ciphertext in no-backup storage using an Android Keystore key. A legacy plaintext file migrates only after the encrypted replacement is atomically written. Sequential writes fixed a real lost-update race; encryption migration still needs physical-device verification. Full detail in WORKLOG.
- **UI correction:** the glass-card/orb-background look was right all along — the actual complaint was that the *animated* version was heavy (a 24-second infinite-loop redraw on every screen, real CPU/battery cost). Reverted to the original translucent glass cards and orbs, but drawn once, statically, instead of animated. Also: removed Ask/Triage/Search from Home's tile grid (redundant with the bottom nav), replaced with a real "Recent activity" feed of logged visits, and removed the "Offline ready" pill per direct instruction. Kept the tighter corner radii and rounded-square icon badges from the earlier pass (not complained about).
- **LLM swapped from Qwen2.5-1.5B to Qwen2.5-0.5B-Instruct.** Measured on-device (`--ez llm_check true`): **17.50 tok/s decode** (was 4.00-5.42 tok/s — a ~3.5x speedup), load time 3.6s (was 6-8.6s). Quality check: the same grounded test question ("How many antenatal check-ups...") still answered correctly and coherently. Old 1.5B GGUF kept on disk unused, not deleted.
- **Historical note:** milestone tracking was paused at this point; it has since been restored as the live completion overview by the latest project direction.
- **2026-09-29 completion audit:** the Android debug APK builds from a clean tree (`:app:assembleDebug`, including native llama/whisper and Kotlin compilation). Reviewed the new query cache and fixed its key generation: query results now vary by query, knowledge version, and result limit; persisted embeddings vary by query and embedder model. Restored the zip-slip rejection detail. Removed a forced `armv8.2-a+dotprod` native target because supported phones do not guarantee that CPU feature. Vulkan remains disabled; GPU acceleration and the claimed speedups are not implemented or verified. The debug build is compile-verified, but no test suite or live-phone run was performed in this audit.
- **Household data protection:** replaced plaintext writes with AES-GCM ciphertext under a non-exportable Android Keystore key in `noBackupFilesDir`. Existing plaintext JSON is migrated only after an atomic encrypted write succeeds. If existing data cannot be decrypted or parsed, the repository warns and refuses to seed/overwrite it. Physical-device migration/recovery verification remains outstanding.
- **OCR now uses PaddleOCR PP-OCRv5 as the primary engine.** Added the Apache-2.0 upstream Android ONNX SDK, SHA-256-pinned PP-OCRv5 detector + Latin + Devanagari models, and a bilingual offline instrumented test. ML Kit is a fallback. The debug APK contains all six model/config assets and is 251 MB; phone accuracy, memory, and latency still need measurement because no ADB device is available here.
- **Cloud sync foundation:** added `proto/sync.proto` and a FastAPI gateway backed only by Qdrant Cloud for device registrations, one-time Ed25519 challenges, signed op records, and de-identified signal vectors. Household/member/visit records are rejected. Added FastEmbed multilingual MiniLM indexing for an isolated shared-knowledge collection. Python privacy tests and lockfile checks pass. The Android sync client/op-log is not wired yet; a Qdrant Cloud account and live integration check are still needed. The gateway currently requires a single writer and scrolls/sorts ops for pull; it is not a scalable production deployment yet.

---

## Milestones (historical snapshot; the live checklist is maintained in MILESTONES.md)

| Milestone | Progress | State |
|---|---|---|
| M0 Foundations | 8 / 10 | 🟣 In progress |
| M1 On-device knowledge and search | 5 / 5 | ✅ Complete |
| M2 Offline health assistant | 2 / 8 (+3 partial) | 🟣 In progress |
| M3 Households, OCR, daily work | 5 / 6 | 🟣 In progress (encryption deferred to M4) |
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
| llama.cpp runs Qwen2.5-1.5B; tokens/sec measured | ✅ | 5.55 tok/s decode, 10.9 tok/s prefill, on phone (after fixing a Debug-build bug, see below) |
| Two LoRA adapters switched per request | ✅ | `LlamaEngineTest.loadsTwoSkillsAndSwitchesBetweenThem` passes on phone (59.4s), both real trained adapters — see "In progress" |
| whisper.cpp transcribes Hindi offline | 🟣 | English clip verified word-for-word on phone; multilingual model, Hindi audio not yet tried |
| ML Kit reads an MCP card | ✅ | synthetic English+Devanagari test card, both scripts read correctly on phone |
| Qdrant Cloud cluster; partial snapshot applied on phone | ⬜ | not started — needs a Qdrant Cloud account |
| One health LoRA skill trained → GGUF | ✅ | `maternal-newborn` trained, retrained after an overfitting fix, and verified via `skill_check` on phone; `child-health` trained too — see "In progress" |

### M1 checklist

| Item | State | Evidence |
|---|---|---|
| Knowledge base from official ASHA modules, immunisation schedule | ✅ | 1,240 passages, 6 documents (EN + HI) |
| Hybrid search with filters | ✅ | dense + BM25 sparse + RRF; keyword indexes on source/lang/programme/quality |
| **Search** screen with results, sources and scores | ✅ | on phone, English and Hindi |
| **Memory Inspector** screen | ✅ | `MemoryScreen`: overview (totals, per-source, per-language, ambiguous-row count), filter chips, paginated browse; built on new `QdrantEdgeVectorStore.facets()`/`.scroll()` (on-phone tested) |
| Search latency measured | ✅ | cold: embed 27 ms, search 26 ms; warm: embed ~20 ms, search ~10–17 ms |

### M2 checklist

| Item | State | Evidence |
|---|---|---|
| Text question → retrieval → answer | ✅ | `AskScreen`/`AskViewModel`, on-phone: `ask_query "baby has fast breathing"` → confidence 1.00, source `asha-induction` |
| Voice question | 🟣 | mic button + `VoiceRecorder` + transcribe-then-ask wiring built and compiling; permission-denied path verified graceful; live capture itself not yet verified — see "In progress" |
| Skill routing / blending | ✅ | `SkillRouter`, ARCHITECTURE.md §5.1 (cosine on skill cards, τ/δ/softmax blend); verified on-device both directions — see "In progress" |
| Sources + confidence badge; low confidence adds referral advice | ✅ | real per-answer source/page + term-overlap confidence; low-confidence banner + referral text shown |
| Streamed, generated answer | ✅ | real Qwen2.5-1.5B, grounded in the retrieved passage (`PromptFormat.ask`), streamed live with a tok/s readout; falls back to passage-only (RECALL rung) when the model isn't installed |
| **Danger-sign triage** decision (Refer now / 24 h / Care at home) | ✅ | `TriageEngine`, rule table for newborn/child/postpartum, 5/5 unit tests, verified rendering on phone (`open_triage true`, no crash) |
| Triage explanation "by the LLM" | ✅ | real generated explanation of the rule-decided outcome (`PromptFormat.triageExplanation`), shown separately below the rule's own template text; the model never sees or can change the decision |
| Medicine helper and counselling cards | 🟡 | content is indexed and searchable via Ask/Search; no dedicated card UI |
| Unanswered questions saved as **gaps** | ✅ | `GapsRepository`, HLC-timestamped, exercised by the low-confidence/no-answer paths |
| Models and skills verified by sha256 before loading | ✅ | `ArtifactVerifier`, now also covering the LLM (1.1 GB GGUF) and whisper (60 MB) models |
| **Speculative decoding** toggle + tokens/sec gauge | 🟡 | tok/s gauge shown for real generation (5.55 tok/s measured); this is plain decoding — speculative/draft-token decoding itself is not built |

### M3 checklist

| Item | State | Evidence |
|---|---|---|
| Household and member records with consent capture | ✅ | `HouseholdsRepository`/`HouseholdsScreen`; encrypted app-private persistence with legacy plaintext migration; consent gate verified on-device (`--ez household_check true`: blocked without consent, allowed with) — see "In progress" |
| OCR scan → confirmed fields in the household record | ✅ | `McpFieldExtractor` (keyword+regex on English+Devanagari: name/age/village/docType/clinicalNotes); `McpConfirmationCard` in `ScanScreen` shows pre-filled editable fields; `ScanViewModel.saveAsHousehold()` creates household + member + visit; consent gate enforced here too; 4 unit tests pass |
| Visit notes searchable by meaning | ✅ | `HouseholdsRepository.searchVisits()`: cosine-similarity on stored `FloatArray` embeddings if embedder ready, term-overlap fallback; wired into `DueListScreen`'s "Search notes" tab |
| Due list and visit planner | ✅ | `DueListScreen`/`DueListViewModel`; seeded with realistic due items (ANC/PNC/immunization); filter chips; expand-to-record with visit notes + high-risk flag + incentive claim; wired into `PolyCareRoot` + drawer + Home tile; `--ez due_list_check true` debug hook verifies visit→due-item completion and semantic search on hardware |
| Monthly report and incentive tracker | ✅ | `HouseholdsRepository.monthlyReport()` aggregates visits by type; `DueListScreen` "Monthly report" tab shows per-type counts and total incentive (₹); export confirmation UI |
| Household data encrypted on the phone, never synced | 🟡 | AES-GCM at rest with a non-exportable Android Keystore key; no-backup storage; migration from the previous plaintext JSON file. Never synced. Migration and recovery still need on-device verification. |

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
| Ask | Text question → hybrid search → passage retrieved → on-device LLM explains it (grounded, streamed) → answer + source + confidence badge; low confidence or no hit logs a **gap**; falls back to passage-only if the LLM isn't installed | `app/.../ask`, `app/.../knowledge/GapsRepository.kt` | on phone: `ask_query "baby has fast breathing"` → confidence 1.00, source `asha-induction`, event logged; `llm_check` → 5.55 tok/s, correct grounded answer |
| Danger-sign triage | Rule table (newborn / child / postpartum) decides Refer now / Refer within 24 h / Care at home; on-device LLM explains that decision in plain language without ever changing it | `app/.../triage`, `app/.../knowledge/TriageEngine.kt` | 5/5 unit tests; renders on phone (`open_triage true`, no crash) |
| On-device LLM | Qwen2.5-1.5B-Instruct Q4_K_M (llama.cpp, JNI), ChatML prompts, streaming tokens, LoRA adapter slots (blend by scale) | `core-llm` (`jni_bridge.cpp`, `LlamaEngine`, `PromptFormat`) | real generation on phone, 5.55 tok/s decode / 10.9 tok/s prefill |
| On-device speech | whisper.cpp, multilingual `ggml-base` q5_1, shares its ggml build with llama.cpp (one copy, no APK collision) | `core-llm` (`whisper_bridge.cpp`, `WhisperEngine`) | `whisper_wav_path` → English clip transcribed **word-for-word** on phone |
| OCR / Scan | PaddleOCR PP-OCRv5 ONNX pipeline with separate Latin and Devanagari recognizers; ML Kit fallback | `app/.../ocr`, `android/ocr-paddle` | Instrumented offline bilingual scan added; still needs connected-device execution |

## Remaining verification gates

Voice capture in the Ask UI (engine + permissions wiring built; live capture unverified), signed op
push with a non-empty queue, populated Radar/gap/guidance workflows, and two-phone convergence remain
incomplete. The Android sync client is now connected to the live Qdrant-backed gateway and has
completed registration, signed authentication, and an empty-data pull cycle over USB. PaddleOCR
PP-OCRv5 Android runtime and SHA-256-pinned English/Devanagari models are integrated; connected-device
accuracy/performance verification remains. GPU acceleration is not built (Vulkan is disabled).
Household data is encrypted at rest; migration needs a physical-device check.

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
| Ask, first query (Realme RMX2151) | embed 32.7 ms + search 42.7 ms, confidence 1.00 |
| LLM prefill (Qwen2.5-1.5B Q4_K_M, 150 tokens) | 13.8 s (≈10.9 tok/s) — Debug native build measured 350 s here, see Known issues |
| LLM decode | **5.55 tok/s**, correct grounded answer ("A pregnant woman needs four antenatal check-ups.") |
| LLM load (verify 1.1 GB + open) | 6.3 s |
| Whisper load | 346–1023 ms |
| Whisper transcribe (11 s English clip, `ggml-base` q5_1) | 17.2 s, **word-for-word correct** |
| OCR (synthetic English+Devanagari card, both scripts) | 1.1 s |

## Known issues and risks

| Issue | Impact | Plan |
|---|---|---|
| Hindi legacy-font conversion is heuristic | 19 invalid sequences left in ~40k words; occasional misspelled word | Keep fixing from the review report; prefer Unicode sources when available |
| 100k upserts leave ~300 MB WAL | Too big for 1 M points | Knowledge ships as built shards (done); float16/uint8 originals (M8) |
| Models and knowledge are pushed with adb (debug) | Not how users get them | First-run downloader + signed manifests (M6/M10) |
| Knowledge manifests are trusted by hash only | No signature yet | ed25519-signed manifests with the downloader |
| Xiaomi/Realme (ColorOS) block adb taps | Can't drive the UI from the PC on either test phone | Enable "USB debugging (Security settings)"; debug launch extras meanwhile (`search_query`, `ask_query`, `open_triage`) |
| Same Xiaomi (MIUI/HyperOS, Android 16) also blocks `adb shell pm grant` and `adb install -g` | Can't pre-grant a runtime permission (e.g. `RECORD_AUDIO`) without a human tap on the system dialog | Grant by hand once, or use the Realme for permission-gated features until this is worked around |
| `E5ParityTest.\`embeddings match onnxruntime python\`` fails: cosine 0.99945 vs required >0.9999 for one fixture case | JVM/Python embedding parity is marginally out of spec, not catastrophically wrong | Pre-existing before this session (`e5_reference.json` already showed modified in git status at session start); likely an onnxruntime version drift between `tools/requirements.txt` and `libs.versions.toml` (CLAUDE.md flags this exact risk) — needs its own investigation, not fixed blind |
| Qdrant Edge Android SDK not official | Built from a pinned upstream commit | Switch to the official artifact when published |
| Hindi quality of a 1.5B LLM unknown | Answers may be weak in Hindi | Evaluate in the LLM step |
| Ask/Triage confidence is a simple term-overlap heuristic | Works well for keyword-heavy protocol text; not a calibrated probability | Revisit once there's a judged relevance set to calibrate against |
| ML Kit fallback Devanagari model may need one online moment | Only affects fallback if PP-OCR model assets are absent/fail; first-use Play Services download | Normal APK includes PP-OCRv5 English + Devanagari assets and stays offline |
| whisper.cpp Hindi transcription untested against a real human voice | `base` model was genuinely poor (wrong-script garbage); switched to `small`, retested, Devanagari came back correct and readable — see "In progress" | TTS audio is cleaner than a phone mic in the field; still worth a real recorded Hindi clip before fully trusting field accuracy |
| First LoRA skill only demonstration-scale (rank 4, ~48 examples, CPU) | Proves the pipeline; not representative of trained-skill quality | Real Skill Factory training is GPU/Colab-scale (ARCHITECTURE.md), tracked as future work, not hidden |

---

## In progress (2026-09-28 evening)

**llama.cpp, whisper.cpp, ML Kit OCR (M0) — all now working and verified on-device.** See the M0 checklist above and "What works today" for specifics. Remaining M0 gaps: Qdrant Cloud (not started, needs an account) and the trained LoRA skill (training below).

**LoRA skill training — real training, running in the background:**
- `tools/skills/train_skill.py`: a genuine PEFT LoRA (rank 4, q/v proj, ~48 examples) on real ASHA Module 6/7 passages, in the exact ChatML format `PromptFormat.ask` uses at inference, converted to GGUF with llama.cpp's own `convert_lora_to_gguf.py`. Its own docstring is explicit this is a small demonstration-scale run (CPU-only), not a substitute for the GPU/Colab-scale training ARCHITECTURE.md's Skill Factory describes.
- **Overfitting found and fixed, then verified on-device.** The first `maternal-newborn` run (3 epochs = 144 steps, lr 2e-4) drove the loss to ~0.0001–0.0003 — the adapter had essentially memorised the 48 (prompt → passage) pairs rather than learning the domain. Proved with the new `--ez skill_check true` debug hook (loads a skill, generates the *same* prompt with it off vs. on): the base model gave a sensible answer, but with the overfit skill active the model **echoed its own system-prompt text back** instead of answering — a real, on-phone-observed failure mode, not a guess. Fixed: `EPOCHS` 3→1, `LEARNING_RATE` 2e-4→1e-4 (loss now settles ~0.01–0.04, not ~0.0002). Retrained and reran `skill_check`: base — *"An ASHA should remember that care during pregnancy requires regular check-ups and attention to danger signs."* — skill active — *"Care during pregnancy requires regular check-ups and attention to danger signs."* — coherent, correct, and measurably more concise. The bad first `maternal-newborn.gguf` was discarded, never shipped.
- `child-health` retrained with the corrected settings too and **finished successfully**: loss settled in the 0.19–0.55 range across the run (never collapsing toward zero the way the overfit `maternal-newborn` run did), sha256 `9b458aeee5633b475229447b09d9222bc59f1aba87e882856ca2267eca05ac3b`. `tools/skills/out/manifest.json` now lists both skills against `baseModel` `Qwen2.5-1.5B-Instruct/Q4_K_M@91cad51`.
- `core-llm`'s `LlamaEngineTest.loadsAndGeneratesAGroundedAnswer` run and **passing** on-device (49.4 s real generation). Needed pushing the 1.1 GB model into `org.polycare.llm.test`'s own storage, not `org.polycare.app`'s: a library module's `connectedAndroidTest` runs as its own separate app package (confirmed via `adb shell pm list packages`: `org.polycare.llm.test`, `org.polycare.vector.edge.test`, `org.polycare.app` all installed side-by-side), so `InstrumentationRegistry.getInstrumentation().targetContext` there is a different `filesDir` than the real app's.
- **Phone reconnected; two-skill hot-swap test run and passing.** Pushed both skills to `org.polycare.app` (`push_skills.sh`) and, via the same stage-in-`/data/local/tmp`-then-`run-as cp` pattern used for the base model, into `org.polycare.llm.test`'s storage (Git Bash's MSYS path conversion mangled the device-side `/data/local/tmp/...` paths on the first attempt — turned them into a Windows path under `C:/Program Files/Git/...` — fixed with `MSYS_NO_PATHCONV=1`). `./gradlew :core-llm:connectedDebugAndroidTest` — 2/2 tests passed, `skipped="0"` confirming the hot-swap test genuinely ran (59.4s), not `Assume`-skipped.
- **Honest note on what it showed:** the test's own logcat (`PolyCareLlmTest`) recorded `base="An ASHA should remember that care during pregnancy requires regular check-ups and attention to danger signs."`, `skillA="Care during pregnancy requires regular check-ups and attention to danger signs."`, `skillB="Care during pregnancy requires regular check-ups and attention to danger signs."` — both skills changed the output from base (the hot-swap mechanism works and is exercised with two distinct real adapters), but `skillA` and `skillB` did not differ from each other on this one generic test prompt. Expected at this training scale (rank 4, ~48 examples per skill, same prompt template) and not something the test asserts against, but recorded here rather than glossed over. A prompt specific to each skill's domain would be a better way to show a visible difference, if that's wanted for a demo.

**Skill routing into Ask — done.** `SkillRouter` (`android/app/.../ai/SkillRouter.kt`) implements ARCHITECTURE.md §5.1 exactly: each skill's short `card` description (added to `manifest.json`, embedded once via the same on-device multilingual-e5 embedder already used for Search) is compared to the question's embedding by cosine similarity; below τ=0.60 the base model answers alone, within δ=0.10 of the top two the answer blends both adapters by softmax(·/T=0.05), otherwise the single best skill loads at scale 1.0. All three thresholds already existed in `PolyCareConfig.Routing` (written ahead of this feature, per the "no magic numbers" convention) — this just makes them do something. `AskViewModel.ask()` calls it before generating and shows the winning skill's name next to the tok/s line. **Verified on-device both directions:** "What danger signs should I watch for during pregnancy?" → `maternal-newborn=0.75, child-health=0.25` (blended, generation completed, 142 tokens, coherent); "What should I feed a child who has diarrhoea?" → `child-health=0.81, maternal-newborn=0.19`. MILESTONES.md item 77 ("skill" badge) is now real, not just sources/confidence.

**Looked at and rejected: [laya](https://github.com/NandhaKishorM/laya) for "faster model output".** It's an encoder-only (ModernBERT/mmBERT) *non-autoregressive classifier* — single-forward-pass yes/no/choice/score decisions, with no text generation at all — served via a Python/FastAPI/PyTorch stack (CPU/CUDA/MPS/XPU) with no mobile or Android target. It can't speed up Ask's actual bottleneck (streaming a generated explanation, which needs real generation) because it doesn't generate text. It's architecturally the same *idea* as skill routing (typed classification instead of generation) but not something to depend on: adding a second ~300–400M-parameter PyTorch model server-side would violate "no cloud dependency in the ask path" (CLAUDE.md, "Things to avoid") and add real RAM/storage cost for a job the already-loaded on-device embedder does for free via cosine similarity — which is exactly what `SkillRouter` above does instead, with zero new dependencies.

**Voice input into Ask — built, partially verified.** Added `VoiceRecorder` (`android/app/.../ai/VoiceRecorder.kt`: raw `AudioRecord` at 16kHz/mono/16-bit, matching `WhisperEngine`'s exact expected format — one suspend function, `recordUntilStopped()`, owns the whole lifecycle open→loop→stop→release so a separate `requestStop()` call can never race a `release()`), `RECORD_AUDIO` permission + a mic button in `AskScreen` (records → transcribes via the already-verified `WhisperProvider` → calls `ask()` with the result, same as typing), and removed the stale "Text only for now — voice arrives once whisper.cpp is integrated" copy (whisper.cpp landed as of the M0 work earlier this session; that line hadn't been updated). **Verified:** builds clean; the permission-denied path is graceful (logs and shows "Microphone permission needed", no crash) — confirmed via a new `--ez voice_check true` debug hook that exercises `VoiceRecorder` directly. **Not verified:** an actual live recording. Granting `RECORD_AUDIO` needs a human tap on the system permission dialog — this MIUI build (2406ERN9CI) blocks both `adb input` (already known) and, newly discovered, `adb shell pm grant` / `adb install -g` (`SecurityException: Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS` / `INSTALL_GRANT_RUNTIME_PERMISSIONS`) — a device-level lockdown this session has no way around. Try the Realme phone (a lighter Android skin may not have the same restriction), or grant it by hand once.
- **Also fixed a stale doc-sync gap:** `MILESTONES.md`'s M1 checklist had never actually been checked off, even though `STATUS.md` has said M1 was complete (5/5) for a while — all five boxes were still `[ ]`. Checked them off with the same evidence STATUS.md already had recorded. A reminder to grep both files for the same milestone after a big feature lands, not just update the one being actively edited.

**Remaining for M0/M2:** verify live voice recording once a permission-tap workaround exists (see above); decide the Hindi audio test fixture for whisper.cpp (English-only verified so far); Qdrant Cloud integration (not started, needs an account — the one still-open M0 item). **Test phone (2406ERN9CI) is thermally throttled** from this session's extensive back-to-back generation testing (big cores down to ~1.5 GHz of a 2.3 GHz max) — let it cool before trusting any further tok/s measurements on it.

## UI redesign (2026-09-28 night)

User feedback: same font everywhere, wanted a bottom nav *and* a left side nav, wanted it to look better overall. Full writeup and reasoning in WORKLOG; summary:
- **Typography:** Tenor Sans (the brand face) was applied at 9 type-scale roles including card/list titles — rationed down to just display/headline moments and small tracked-uppercase micro-labels; everything else (including `titleLarge/Medium/Small`) now uses the system sans, SemiBold, so the brand face reads as intentional again.
- **Depth:** `GlassCard` had `shadowElevation = 0.dp` — literally flat everywhere. Added a soft colour-tinted shadow and an optional `accent` colour (a thin left-edge bar).
- **Per-screen identity:** Ask=Plum, Triage=Rose, Search=Magenta, Memory=PlumDeep, Scan=Positive — drives that screen's back button, header label, drawer icon and bottom-nav highlight consistently, plus each of Home's 8 tiles got its own accent instead of one repeated colour.
- **Navigation:** `ModalNavigationDrawer` wraps the app (left-edge swipe works anywhere), hamburger button on Home/System; lists all 7 real screens plus a "Coming later" section (Households/Due list/Sync) that snackbars the milestone rather than faking navigation. Bottom nav expanded 2→4 tabs (Home/Ask/Triage/Search — System moved to drawer-only as a diagnostics screen, not a daily destination).
- **Verified with real screenshots** (`adb shell screencap`), not just a clean build — Home, Ask, and the drawer (via a temporary `--ez open_drawer true` debug extra, since the hamburger can't be tapped on this device). Caught and fixed a real bug this way: the floating bottom nav's opaque lower half was sitting over Home's last tile row with too little scroll clearance — added a +40dp buffer to `PolyCareRoot`'s shared content padding. Triage/Search/Memory/Scan compile clean and share the same now-verified components but weren't individually screenshotted.

## In progress (2026-09-28 night) — repo hygiene + real perf fixes

- **`.gitignore`:** added `bin/` (stray IDE-generated `.class` dirs found untracked in `core-common`/`core-embed`/`core-vector`), `*.safetensors` + `tools/models/qwen2.5-1.5b-instruct-hf/` (the HF-format base model used for LoRA training), `tools/skills/out/` (trained adapters), and `native/whisper.cpp/`.
- **whisper.cpp is a fetched source tree, not a git submodule** — same as llama.cpp (`native/fetch-whisper-cpp.sh` pins its tag/commit; the checked-out tree is gitignored). Chose this over a submodule for consistency: the repo already has one working pattern (pinned-tag fetch script + gitignore) for exactly this kind of "vendor a large upstream C++ project, build from source with CMake, never commit it" situation, and a submodule would be a second, different mechanism for the same problem with no real benefit (submodules still need an explicit init/update step; the fetch script already does that plus the pin, in one place, in a form `bash native/fetch-whisper-cpp.sh` documents directly).
- **Real bug found and fixed: Search ran every query's embed + vector search twice, concurrently.** `SearchViewModel.searchNow()` both called `run(q)` immediately *and* updated the `_query` `StateFlow`, which a separate `debounce(350ms)` pipeline was also collecting — so any explicit search (submit, a suggestion chip, or the debug `search_query` launch) raced two concurrent calls into `KnowledgeRepository.search()` for the same query. Confirmed on-device via the existing `PolyCareEvent` logcat (`cat":"SEARCH"` fired twice, ~3–30ms apart, for one query). A first fix (skip if `_ui.value` already held a `Results` for that query) didn't close the race, because both calls could still be mid-flight (both seeing `Searching`, neither yet `Results`) — root-caused and fixed properly with an `activeQuery` guard set *synchronously before* the first suspension point in `run()`, so the second caller sees it immediately (both coroutines share one dispatcher, so the check-then-set is effectively atomic). Verified fixed: re-ran the same debug search, now exactly one `"Knowledge search"` event. (Separately: my first repro used `adb shell am start ... --es search_query "how to prepare ORS"` from Git Bash, whose local quoting doesn't survive to the remote shell — `am start` silently only got `"how"` as the value. Not an app bug, but a reminder to always wrap the whole `am start ...` invocation in outer single-quotes when testing multi-word extras: `adb shell 'am start ... --es key "multi word value"'`.)
- **llama.cpp: prefill and decode now use separate thread counts.** `n_threads` (per-token decode) and `n_threads_batch` (prompt prefill) were both hard-set to the same value; split them through the JNI boundary (`LlamaNative.loadModel` now takes both, `LlamaEngine.load()` exposes `defaultDecodeThreadCount()`/`defaultBatchThreadCount()`). Rationale, tested on-device (2406ERN9CI, 6×A55 @1.96GHz + 2×A76 @2.3GHz): decode is one small matmul per token — memory-bandwidth-bound, and on a big.LITTLE phone every layer's thread barrier waits for the slowest core, so *fewer, faster* threads can beat *more, mixed* threads; prefill batches the whole prompt into one compute-bound matmul and benefits from every core, stragglers included. Measured nThreads=2 vs 6 for decode: 5.42 and 5.20 tok/s (two separate runs) vs 4.00 tok/s baseline — a consistent direction across repeated trials. **Caveat, stated honestly:** these specific numbers are noisy — `/sys/.../cpufreq/scaling_cur_freq` showed all cores running well below `cpuinfo_max_freq` during testing (e.g. the two fast cores at 1651/2304MHz), i.e. DVFS/thermal scaling from several back-to-back heavy runs this session, and a same-config prefill re-run (nThreads_batch=6 both times) measured 14.4s once and 25.7s later — a swing purely from thermal/clock state, not code. The *direction* (small thread count helps decode, large helps prefill) is architecturally sound and llama.cpp's own documented reason for having two separate parameters, and is worth keeping; the *exact* default of 2 decode threads should be re-measured with a cooldown between runs, and on the second test phone (Realme RMX2151), before being trusted as final tuning rather than a reasonable first guess.
- **Not yet done, flagged rather than attempted under time pressure:** a GPU (Vulkan) or ARM-ISA-dispatch (`GGML_CPU_ALL_VARIANTS`, dotprod/i8mm) backend would likely give a much bigger speedup than thread tuning, but both are real native-build-system changes with real fleet-safety risk (a hard-coded ISA target can SIGILL-crash on a phone that lacks it — a direct violation of invariant 6/"never crash" for a project explicitly targeting a low-cost, heterogeneous device fleet). `GGML_CPU_ALL_VARIANTS` (build every CPU variant, dispatch to the best one at runtime) is the architecturally-correct fix and was already flagged for later in `core-llm/CMakeLists.txt`'s own comment; it needs its own dedicated session (Android `.so` packaging for `GGML_BACKEND_DL`'s dynamically-loaded variants is untested territory here) rather than a rushed attempt now.
- **LLM pre-warmed on Home load**, gated by the Resource Governor's rung (skipped on RECALL, where the app has already decided the device can't carry the model) — verified on-device: `LLM ready` logs ~10s after `App started`, entirely in the background, before any Ask/Triage screen opens. First question of a session no longer pays the ~6-8s load cost on top of generation time.
- **`whisper-base-multilingual` swapped for `whisper-small-multilingual`, and it fixed the Hindi problem.** Downloaded `ggml-small-q5_1.bin` (181MB, same upstream commit as base), pinned its sha256 in `tools/models/fetch_models.sh` and `WhisperArtifacts.kt`, pushed it, reran `--ez hindi_check true`. Base: `"बच्चे को दस्त हो तो क्या करें"` → `"Bっちy kudas thu to kya kare?"` (wrong script entirely). Small: → `"बच्छि को दस्थ हो तो क्या करें?"` — correct Devanagari, correct word boundaries, only minor spelling variants on two words (बच्चे/बच्छि, दस्त/दस्थ) that a human reader would still understand. A real, measured fix, not a guess — confirms ARCHITECTURE.md's own note that base's multilingual quality is the weaker tier. Cost: ~3.2x the download/storage (181MB vs 57MB) and slower on-device inference (this run took ~91s wall-clock total, though that included the new LLM pre-warm also running concurrently on the same CPU — a real resource-contention interaction worth knowing about, not yet isolated/measured cleanly). Old `base` model files are left in place on `tools/models/` and any already-pushed phone (harmless, just unused) rather than actively cleaned up.

## M3 Households, OCR, daily work (2026-09-29)

- **First real M3 feature: household and member records with consent capture.** New `org.polycare.app.households` package — `HouseholdsRepository` (in-memory `StateFlow`s, same honest MVP pattern as `GapsRepository`: its own doc comment says exactly why and what M4's op-log changes about it, rather than pretending this is the final architecture), `HouseholdsViewModel`, `HouseholdsScreen`. A household's consent checkbox must be set when it's registered before any member can be added to it — enforced in the *repository*, not just as a UI nicety (`addMember` returns `null` and logs a `WARN` event if consent wasn't given), so the rule holds even if a future caller skips the screen entirely.
- **Wired into real navigation:** Home's "Households" tile and "Due list" tile now have working routes (were previously "arrives in M3" snackbars); both are in the nav drawer's real destinations list.
- **Verified two ways (household).** Visually: screenshotted the screen — form renders correctly, "Add household" button is visibly disabled until both fields are filled and consent is checked. Functionally: `--ez household_check true` — result: `noConsentBlocked=true withConsentAllowed=true households=2 members=1`.
- **OCR → confirmed household fields.** `McpFieldExtractor` (new pure-Kotlin object, no deps) runs keyword+regex matching on both Latin and Devanagari OCR output to extract `name`, `age`, `village`, `docType`, and `clinicalNotes` fields. `McpConfirmationCard` in `ScanScreen` displays these as pre-filled editable fields with a consent checkbox before saving. `ScanViewModel.saveAsHousehold()` creates a household + optional member + optional ROUTINE visit (with a note from the scan) when the ASHA worker confirms. 4 unit tests (`McpFieldExtractorTest`): English MCP card, Hindi Devanagari card, prescription+medicine strip, and graceful empty-text handling.
- **Visit notes searchable by meaning.** `HouseholdsRepository.recordVisit()` asynchronously embeds the note text using the on-device e5-small embedder, storing the vector in the `Visit` object. `searchVisits(query)` does cosine-similarity lookup when vectors are available, falls back to term-overlap if the embedder hasn't loaded yet. Wired into `DueListScreen`'s "Search notes" tab.
- **Due list and visit planner.** `DueListScreen`/`DueListViewModel`: repository is seeded with 4 realistic due items (high-risk ANC, Day-7 PNC, overdue immunization, upcoming family planning); filter chips narrow by visit type; tapping a card expands it to enter visit notes, flag high-risk, and claim the incentive; `recordDueVisit()` marks the item completed and updates the `StateFlow`. Three summary cards at the top show pending count, high-risk count, and total incentive earned. `--ez due_list_check true` debug hook creates a household, records an ANC visit, checks the due-item completion, runs semantic search, and logs `visitRecorded`/`incentiveEarned`/`dueCompleted`/`searchMatches` to `PolyCareEvent`.
- **Monthly report and incentive tracker.** `HouseholdsRepository.monthlyReport()` aggregates all recorded visits by type and sums incentives. `DueListScreen`'s "Monthly report" tab renders per-type count + incentive lines, a total box, and an export-for-PHC-meeting confirmation button.
- **9 new unit tests total.** `HouseholdsRepositoryTest` (5): consent gate blocks members without consent, consent gate blocks visits without consent, visit recording completes a due-item, monthly incentive totals correct, term-based visit note search. `McpFieldExtractorTest` (4): English MCP, Hindi Devanagari, prescription+medicines, empty text.
- **Data privacy invariant holds.** `HouseholdsRepository`'s doc comment explicitly notes the in-memory MVP pattern and that nothing in the class is ever placed in an outbox; the consent gate is enforced at the repository layer, not just UI, so it holds regardless of caller.

## How to verify

```bash
cd android && ./gradlew test                                            # unit tests
cd android && ./gradlew :app:connectedDebugAndroidTest :qdrant-edge:connectedDebugAndroidTest
bash tools/models/push_models.sh                                        # embedder → phone
tools/.venv/Scripts/python tools/knowledge/build_knowledge.py           # build knowledge
bash tools/knowledge/push_knowledge.sh                                  # knowledge → phone
adb shell am start -n org.polycare.app/.MainActivity --es search_query "how to prepare ORS"
adb shell am start -n org.polycare.app/.MainActivity --es ask_query "baby has fast breathing"  # M2 Ask
adb shell am start -n org.polycare.app/.MainActivity --ez open_triage true    # M2 Triage (renders, no crash)
adb shell am start -n org.polycare.app/.MainActivity --ez embed_check true    # logcat PolyCareEmbed
adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000  # logcat PolyCareBench
adb shell am start -n org.polycare.app/.MainActivity --ez llm_check true      # logcat PolyCareLlm (real generation)
adb shell am start -n org.polycare.app/.MainActivity --es whisper_wav_path "<app-internal path to a mono/16-bit/16kHz .wav>"  # logcat PolyCareWhisper
adb shell am start -n org.polycare.app/.MainActivity --es ocr_image_path "<app-internal path to an image>"  # opens Scan, logcat PolyCareEvent "OCR ran"
adb shell am start -n org.polycare.app/.MainActivity --ez household_check true  # M3 consent gate (logcat PolyCareEvent)
adb shell am start -n org.polycare.app/.MainActivity --ez due_list_check true   # M3 visit+due list+search+report (logcat PolyCareEvent)
adb shell am start -n org.polycare.app/.MainActivity --es open_route due-list   # M3 Due list screen directly
adb logcat -s PolyCareEvent                                             # activity log

# push a model file: bash tools/models/push_models.sh stages via /data/local/tmp and `run-as`
# cp into files/models/; the same run-as pattern works for any test fixture (see WORKLOG for
# the jfk.wav / mcp_sample.png examples) — adb push alone cannot reach the app's private dir.
```
