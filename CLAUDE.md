# CLAUDE.md — PolyCare

Guidance for Claude Code (and humans) working in this repository.

## What this project is
**PolyCare** is an offline-first Android app (native Kotlin) for India's ASHA community health workers. A single frozen on-device LLM (Qwen2.5-1.5B, llama.cpp) becomes a health expert per request, because **Qdrant Edge** retrieves its **LoRA skills**, **knowledge** (up to ~1 M passages), **household memory** and **speculative-decoding drafts**. **Qdrant Cloud** drives semantic sync, conflict resolution, Outbreak Radar, knowledge slicing and a Skill Factory.

Built for the Geek Room × Qdrant hackathon (PS-03).

Read first: `PROJECT_DESCRIPTION.md` (why/who/features), `ARCHITECTURE.md` (how), `MILESTONES.md` (what to build, in order). The original brief is in `qdrant-edge-hackathon-brief.md` and `problem_explanation_*.pdf`.

## Repository layout
```
android/   Kotlin + Compose app; core-* modules (common, llm, embed, vector, memory, ocr, oplog, sync, governor)
cloud/     gateway (FastAPI + Qdrant: signed sync, team features, artifact server, supervisor dashboard),
           skill-factory (supervisor answers + knowledge -> LoRA skill -> signed update)
native/    qdrant-edge Rust crate + UniFFI bindings, built with cargo-ndk
proto/     sync.proto — the ONLY definition of the wire format
tools/     seed data, chaos scripts, benchmarks
assets/    banner, logo, Tenor Sans font
```

## Non-negotiable invariants
Every change must keep these true. If a change needs to break one, stop and ask.

1. **Op-log first.** Every mutation is an `Op` appended to the op-log *before* touching Qdrant. Device-owned shards are rebuildable views. Never write to `households`, `memory`, `signals`, `drafts` or `gaps` directly.
2. **Idempotent everywhere.** Ops are keyed by UUIDv7 `op_id`. Applying an op twice is a no-op on edge and cloud (`ON CONFLICT DO NOTHING`).
3. **HLC, not wall clock.** Order and compare with the Hybrid Logical Clock. Never use `System.currentTimeMillis()` or `datetime.now()` for ordering.
4. **`model_id` guard.** Never compare, search or merge vectors produced by different embedding models.
5. **Verify before load.** Models, adapters and snapshots are loaded only after sha256 + signature checks. On failure, quarantine and fall back. Never crash.
6. **Always answer.** Inference paths respect the Resource Governor's rung (FULL → LEAN → SINGLE → BASE → RECALL).
7. **Personal health data never leaves the phone.** `households` is never placed in the outbox or mesh. Only de-identified `Signal`s and team-visible knowledge sync. Enforce it at the Sync Gate *and* in the gateway.
8. **Restart-safe background work.** Any WorkManager job or sync step can be killed at any line. Persist cursors and make resumption correct.
9. **Cloud-owned vs device-owned shards.** `knowledge`, `skills` and `atlas` change only via Qdrant partial snapshots. Device-owned shards change only via the op-log.
10. **Decision support, not diagnosis.** Referral decisions come from the rule table; the LLM only explains. Clinical answers cite a source; low confidence adds referral advice.

## Conventions
- **Kotlin:** coroutines + Flow, Hilt DI, no blocking calls on Main. Native (JNI/UniFFI) calls run on dedicated dispatchers. Module boundaries follow `core-*`.
- **Native:** llama.cpp and whisper.cpp are git submodules built with CMake via the NDK (arm64-v8a only). Qdrant Edge is built from the upstream `qdrant-edge-ffi` crate by `native/build-qdrant-edge.sh`; the generated Kotlin in `android/qdrant-edge/src/main/kotlin/tech/qdrant/edge/ffi` is never hand-edited, and the `.so` is not committed. JNI surfaces stay thin; logic lives in Kotlin.
- **Python:** 3.12, FastAPI + Pydantic v2, async everywhere, `ruff` + `mypy --strict` on `cloud/`.
- **Wire format:** change `proto/sync.proto` first, then regenerate. Never hand-edit generated code.
- **Config:** thresholds (τ, δ, T, half-life, stable window, chunk size) live in one config object per side. No magic numbers inline.
- **Tests:** every sync or op-log change needs a test that kills the process mid-operation and asserts no loss or duplication.
- **Numbers:** performance figures are *targets* until measured. Label them honestly.
- **Embeddings:** one text per ONNX run (the int8 model's dynamic quantisation makes batch members affect each other). The Python tools pin the same `onnxruntime` version as the app (`tools/requirements.txt` ↔ `libs.versions.toml`) so cloud-built vectors match the phone's. Always use `embedQuery` / `embedPassages` (e5 prefixes).
- **Docs:** no generated diagram images. Mermaid diagrams in Markdown are fine (the README uses them).
- **Brand / UI:** the app follows `assets/banner.png`: paper background `#F8F8F8` with soft orchid/pink/red orbs and film grain, deep plum clover (`assets/logo.png`), Tenor Sans (`assets/fonts/`, bundled as `R.font.tenor_sans`) for headings and tracked uppercase labels, system sans for body text. Light theme only. Colours live in `app/.../ui/theme/Color.kt` (`Brand.*`); reuse `BrandBackground`, `GlassCard`, `SectionLabel`, `StatusPill`, `MetricRow` instead of ad-hoc styling. `Brand.Rose`/`Brand.Red` fail WCAG AA as body text (3.48:1 and 3.73:1 on paper): use them for fills, dots, borders and accent edges, and `Brand.RoseInk`/`Brand.RedInk` (6.6:1, 5.9:1) wherever the colour is read as text. Features that are not built yet must say so (e.g. "arrives in M2"), never fake results.

## Common commands
```bash
# android (Windows: gradlew.bat)
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:installDebug
cd android && ./gradlew test
cd android && ./gradlew :qdrant-edge:connectedAndroidTest   # Qdrant Edge on the phone
adb devices

# Qdrant Edge native build (pinned upstream commit; ~15 min first time)
# Windows needs MinGW-w64: winget install BrechtSanders.WinLibs.POSIX.UCRT
bash native/build-qdrant-edge.sh

# on-phone vector benchmark (debug build), results in logcat tag PolyCareBench
adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000

# embedding model (multilingual-e5-small int8): download + verify, build tokenizer/fixtures, push to phone
python -m venv tools/.venv && tools/.venv/Scripts/pip install -r tools/requirements.txt
PYTHON=tools/.venv/Scripts/python bash tools/models/fetch_models.sh
bash tools/models/push_models.sh
# on-phone parity check vs Python reference (debug build), results in logcat tag PolyCareEmbed
adb shell am start -n org.polycare.app/.MainActivity --ez embed_check true

# cloud
cd cloud && docker compose up -d
cd cloud/gateway && pytest -q
```

## First checks (status)
| Check | Status |
|---|---|
| Android project builds and installs on the phone | ☑ |
| Qdrant Edge built for arm64 (cargo-ndk + UniFFI): upsert + search on a real phone | ☑ |
| Hybrid query (prefetch + RRF) on Edge | ☑ |
| Qdrant Edge: 100k measured (p50 9.8 ms, recall 97%); 1 M points with compact storage still to measure | ☐ |
| Multilingual embedder (e5-small int8, ONNX Runtime): Kotlin tokenizer = HF token-for-token; JVM vectors = Python (cos > 0.9999) | ☑ |
| Embedder on the phone (ARM): tokens exact, same nearest neighbour 25/25, min cos 0.9983, query p50 9.6 ms | ☑ |
| llama.cpp base + 2 LoRA adapters, per-request switch | ☐ |
| Hindi quality of the base model; Hindi speech-to-text | ☐ |
| ML Kit OCR on an MCP card | ☐ |
| One health LoRA skill trained + converted to GGUF | ☐ |

## Things to avoid
- Adding a cloud dependency to the ask path. Ask must work in airplane mode.
- Last-write-wins merges on memory content. Conflicts become `disputed` + a merge proposal.
- Storing large binaries in Qdrant payloads. Blobs live in the Artifact Store / object storage.
- Putting other people's health records on a phone. Only the ASHA's own households are local.
- Giving phones Qdrant Cloud write keys. Phones talk to the gateway.
