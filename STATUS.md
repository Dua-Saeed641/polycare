# PolyCare — Status

Live dashboard. Updated after every step; history and reasoning are in [WORKLOG.md](WORKLOG.md).

**Last updated:** 2026-09-28 night · **Current:** M0 Foundations (9 / 10, only Qdrant Cloud remains), M1 Knowledge & search (5 / 5, complete), M2 Offline health assistant (2 / 8, plus 3 partial) · **Test phones:** Xiaomi 2406ERN9CI, Android 16, 6 GB class; Realme RMX2151, Android 12, 6 GB class (all M0/M1 native claims re-verified independently on this second device this session)

---

## Milestones

| Milestone | Progress | State |
|---|---|---|
| M0 Foundations | 8 / 10 | 🟣 In progress |
| M1 On-device knowledge and search | 5 / 5 | ✅ Complete |
| M2 Offline health assistant | 2 / 8 (+3 partial) | 🟣 In progress |
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
| Voice question | 🟣 | `WhisperEngine` verified working (English clip, word-perfect) but not wired into Ask's UI — no mic button yet |
| Skill routing / blending | ⬜ | still nothing to route to at runtime — first trained skill not pushed/wired yet |
| Sources + confidence badge; low confidence adds referral advice | ✅ | real per-answer source/page + term-overlap confidence; low-confidence banner + referral text shown |
| Streamed, generated answer | ✅ | real Qwen2.5-1.5B, grounded in the retrieved passage (`PromptFormat.ask`), streamed live with a tok/s readout; falls back to passage-only (RECALL rung) when the model isn't installed |
| **Danger-sign triage** decision (Refer now / 24 h / Care at home) | ✅ | `TriageEngine`, rule table for newborn/child/postpartum, 5/5 unit tests, verified rendering on phone (`open_triage true`, no crash) |
| Triage explanation "by the LLM" | ✅ | real generated explanation of the rule-decided outcome (`PromptFormat.triageExplanation`), shown separately below the rule's own template text; the model never sees or can change the decision |
| Medicine helper and counselling cards | 🟡 | content is indexed and searchable via Ask/Search; no dedicated card UI |
| Unanswered questions saved as **gaps** | ✅ | `GapsRepository`, HLC-timestamped, exercised by the low-confidence/no-answer paths |
| Models and skills verified by sha256 before loading | ✅ | `ArtifactVerifier`, now also covering the LLM (1.1 GB GGUF) and whisper (60 MB) models |
| **Speculative decoding** toggle + tokens/sec gauge | 🟡 | tok/s gauge shown for real generation (5.55 tok/s measured); this is plain decoding — speculative/draft-token decoding itself is not built |

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
| OCR / Scan | ML Kit on-device text recognition, Latin + Devanagari, runs both over the whole image (no per-line script fusion yet) | `app/.../ocr` (`OcrEngine`, `ScanScreen`) | `ocr_image_path` → synthetic English+Hindi test card, both scripts read correctly on phone (screenshot) |

## Not built yet

Skill routing/blending (no trained skill wired in yet), voice capture in the Ask UI (engine works, no mic button), Households, Due list, Sync, Outbreak Radar, Conflict Inbox, op-log, Qdrant Cloud, gateway, dashboard. Home tiles say "arrives in M…" for these.

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
| Qdrant Edge Android SDK not official | Built from a pinned upstream commit | Switch to the official artifact when published |
| Hindi quality of a 1.5B LLM unknown | Answers may be weak in Hindi | Evaluate in the LLM step |
| Ask/Triage confidence is a simple term-overlap heuristic | Works well for keyword-heavy protocol text; not a calibrated probability | Revisit once there's a judged relevance set to calibrate against |
| ML Kit Devanagari model needs one online moment on a new phone | First OCR use may need connectivity to download the script model via Play services; then fully offline | Document for the demo; consider bundling if this becomes a blocker |
| whisper.cpp Hindi transcription unverified | English proven word-for-word; Hindi untested (no Hindi audio fixture yet) | Get/record a short Hindi clip and rerun `whisper_wav_path` |
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

**Remaining for M0/M2:** wire real skill routing into Ask (mechanism proven, but Ask doesn't yet pick a skill based on the question), decide the Hindi audio test fixture for whisper.cpp (English-only verified so far), Qdrant Cloud integration (not started, needs an account — the one still-open M0 item).

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
adb logcat -s PolyCareEvent                                             # activity log

# push a model file: bash tools/models/push_models.sh stages via /data/local/tmp and `run-as`
# cp into files/models/; the same run-as pattern works for any test fixture (see WORKLOG for
# the jfk.wav / mcp_sample.png examples) — adb push alone cannot reach the app's private dir.
```
