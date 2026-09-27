# CLAUDE.md — Polymath

Guidance for Claude Code (and humans) working in this repository.

## What this project is
**Polymath** is an offline-first Android app for field technicians. A single frozen on-device LLM (Qwen2.5-1.5B, llama.cpp) becomes a domain expert per request, because **Qdrant Edge** retrieves its **LoRA skills**, **memory** and **speculative-decoding drafts**. **Qdrant Server** in the cloud drives geometric sync, conflict resolution, a Fleet Radar and a Skill Factory that trains new skills.

Built for the Geek Room × Qdrant hackathon (PS-03). Online round **Oct 3**, offline round **Oct 11**.

Read first: `PROJECT_DESCRIPTION.md` (why/who), `ARCHITECTURE.md` (how), `docs/diagrams/*.png`. The original brief is in `qdrant-edge-hackathon-brief.md` and `problem_explanation_*.pdf`.

## Repository layout
```
android/   Kotlin + Compose app; core-* modules (llm, embed, memory, oplog, sync, governor)
cloud/     gateway (FastAPI), workers (ARQ), skill-factory, dashboard (Next.js), docker-compose.yml
proto/     sync.proto — the ONLY definition of the wire format (edge + cloud generate from it)
tools/     seed data, chaos scripts, benchmarks
docs/      diagrams (generated — edit src/*.py, then run docs/diagrams/render.sh)
```

## Non-negotiable invariants
Every change must keep these true. If a change needs to break one, stop and ask.

1. **Op-log first.** Every mutation is an `Op` appended to the op-log *before* touching Qdrant. Qdrant shards are rebuildable views. Never write to a device-owned shard (`memory`, `drafts`, `gaps`) directly.
2. **Idempotent everywhere.** Ops are keyed by UUIDv7 `op_id`. Applying an op twice must be a no-op on both edge and cloud (`ON CONFLICT DO NOTHING`).
3. **HLC, not wall clock.** Order and compare with the Hybrid Logical Clock. Never use `System.currentTimeMillis()` or `datetime.now()` for ordering.
4. **`model_id` guard.** Never compare, search or merge vectors produced by different embedding models.
5. **Verify before load.** Models, adapters and snapshots are loaded only after sha256 + signature checks. On failure, quarantine and fall back. Never crash.
6. **Always answer.** Inference code paths must respect the Resource Governor's degradation-ladder rung (FULL → LEAN → SINGLE → BASE → RECALL).
7. **Private stays private.** `visibility = private` never leaves the device. Enforce it at the Sync Gate *and* in gateway queries.
8. **Restart-safe background work.** Any WorkManager job or sync step can be killed at any line. Persist cursors and make resumption correct.
9. **Cloud-owned vs device-owned shards.** `skills` and `atlas` change only via Qdrant partial snapshots from the server. `memory`, `drafts` and `gaps` change only via the op-log.

## Conventions
- **Kotlin:** coroutines + Flow, Hilt DI, no blocking calls on Main. Native (JNI) calls run on dedicated dispatchers. Module boundaries follow `core-*`.
- **Native:** llama.cpp and whisper.cpp are git submodules built with CMake via the NDK (arm64-v8a only). JNI surfaces stay thin; logic lives in Kotlin.
- **Python:** 3.12, FastAPI + Pydantic v2, async everywhere, `ruff` + `mypy --strict` on `cloud/`.
- **Wire format:** change `proto/sync.proto` first, then regenerate (Wire for Kotlin, `protoc`/betterproto for Python). Never hand-edit generated code.
- **Config:** thresholds (τ, δ, T, half-life, stable window, chunk size) live in one config object per side. No magic numbers inline.
- **Tests:** every sync or op-log change needs a test that kills the process mid-operation and asserts no loss or duplication.
- **Numbers:** performance figures in docs are *targets* until measured. Label them honestly in UI and pitch.

## Common commands (fill in as modules land)
```bash
# cloud
cd cloud && docker compose up -d            # qdrant, postgres, minio, redis, gateway, workers, dashboard
cd cloud/gateway && pytest -q

# android
cd android && ./gradlew :app:installDebug
cd android && ./gradlew test

# diagrams
bash docs/diagrams/render.sh
```

## Day-1 checks (status)
| Check | Status |
|---|---|
| Qdrant Edge Kotlin SDK: upsert + search on a real phone | ☐ |
| Hybrid query (prefetch + RRF) on Edge | ☐ |
| llama.cpp base + 2 LoRA adapters, per-request scale switch on Android | ☐ |
| Speculative lookup speed-up on phone CPU | ☐ |
| Nearby Connections 1 MB transfer between 2 phones | ☐ |
| One LoRA skill trained on Colab + converted to GGUF | ☐ |

## Things to avoid
- Adding a cloud dependency to the ask path. Ask must work in airplane mode.
- Last-write-wins merges on memory content. Conflicts become `disputed` + a merge proposal.
- Storing large binaries in Qdrant payloads. Adapters live in the Adapter Store / MinIO, and Qdrant stores the reference + sha256.
- Scope creep beyond the demo script in `PROJECT_DESCRIPTION.md` §7 before the Oct 3 milestone is met.
