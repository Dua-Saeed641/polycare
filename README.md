# Polymath

**A team of experts in your pocket. No signal required.**

Polymath is an offline-first AI copilot for field technicians, built on **Qdrant Edge** and **Qdrant Server**.
One small LLM runs entirely on an Android phone. For each question, Qdrant Edge retrieves the model's **expertise**: fine-tuned LoRA *skills*, the team's *memory*, and *draft tokens* that speed up generation. When a connection appears, Qdrant Server's geometry decides what to sync, detects conflicts, raises alerts on emerging issues, and **trains new skills** that flow back to the phones.

> Geek Room × Qdrant Hackathon · Problem Statement 03: *AI-Powered Edge Memory & Intelligence Platform*

![System architecture](docs/diagrams/system-architecture.png)

## Why it's different

| Most edge-AI demos | Polymath |
|---|---|
| Qdrant stores documents for RAG | Qdrant also stores **which fine-tuned version of the model should answer**, and blends two skills using the similarity scores |
| Generation speed is fixed by hardware | **Speculative decoding from memory**: the phone gets faster as the team uses it |
| Sync = "upload everything newer than X" | **Merkle tree over semantic regions**: sync reports *which topics* diverged and only transfers those |
| Everything syncs, or a hard-coded rule decides | **Sync Gate**: hubness × novelty decides what is team knowledge, what is personal, and what is a +1 vote |
| Conflicts resolved by last-write-wins | Contradictions detected by vector proximity + NLI, kept visible, merged reversibly |
| Cloud is a backup | Cloud spots **emerging issues across the fleet** and **creates new skills** when knowledge accumulates |

## How the model runs on the phone

![On-device runtime](docs/diagrams/on-device-runtime.png)

- **Base:** Qwen2.5-1.5B-Instruct, Q4_K_M GGUF (~1 GB, memory-mapped), llama.cpp via JNI.
- **Skills:** LoRA r=16 adapters (~9 MB), routed by Qdrant Edge and hot-swapped per request.
- **Memory:** Qdrant Edge hybrid search (bge-small dense + BM25 sparse, RRF).
- **Voice:** whisper.cpp tiny.en, on device.
- **Always answers:** a degradation ladder steps down from full blending to retrieval-only under heat, low battery or memory pressure.

## Tech stack

| Layer | Technologies |
|---|---|
| Android | Kotlin 2, Jetpack Compose, Hilt, Room (SQLite), WorkManager, OkHttp, Wire/Protobuf, zstd, Nearby Connections, Android Keystore |
| On-device AI | llama.cpp, whisper.cpp, ONNX Runtime Mobile, Qwen2.5-1.5B, bge-small-en-v1.5 |
| Vector search | **Qdrant Edge** (Kotlin SDK / UniFFI), **Qdrant Server** |
| Cloud | FastAPI, PostgreSQL 16, MinIO, Redis + ARQ, Ollama/vLLM (Qwen2.5-7B), Caddy, Docker Compose |
| Skill Factory | Unsloth / PEFT, llama.cpp GGUF conversion, HDBSCAN |
| Dashboard / Ops | Next.js 15, shadcn/ui, Recharts, Prometheus, Grafana, OpenTelemetry, Toxiproxy |

## Repository

```
android/        Android app + core modules (llm, embed, memory, oplog, sync, governor)
cloud/          gateway · workers · skill-factory · dashboard · docker-compose.yml
proto/          sync.proto (wire format)
tools/          seed data, chaos scripts, benchmarks
docs/diagrams/  architecture diagrams (Python → SVG/PNG, run render.sh)
```

## Getting started *(work in progress)*

```bash
# Cloud (Qdrant Server, Postgres, MinIO, Redis, gateway, workers, dashboard)
cd cloud && docker compose up -d

# Android (arm64 device, Android 10+, 6 GB+ RAM recommended)
cd android && ./gradlew :app:installDebug
```

Model files are downloaded on first run and verified by sha256.

## Documents

- [PROJECT_DESCRIPTION.md](PROJECT_DESCRIPTION.md): who it's for, the problem, business model, demo script, milestones
- [ARCHITECTURE.md](ARCHITECTURE.md): components, data model, algorithms, flows, failure matrix
- [CLAUDE.md](CLAUDE.md): engineering invariants and conventions

## Status

Design complete · Day-1 technical checks next (see `CLAUDE.md`).
