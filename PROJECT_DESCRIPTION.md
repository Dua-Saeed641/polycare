# Polymath — Project Description

> **A team of experts in your pocket. No signal required.**
> Geek Room × Qdrant Hackathon · Problem Statement 03 — *AI-Powered Edge Memory & Intelligence Platform*

---

## 1. Who we are building for

**Primary user — the field technician.**
People who repair and maintain equipment where the equipment lives: solar inverters on rooftops, HVAC plant rooms in basements, elevator shafts, telecom towers in rural areas, medical devices inside RF-shielded hospital rooms, and wind turbines.

**Primary buyer — the organisations behind them.**
- **Field-service companies** (maintenance contractors, facility-management firms) pay per technician seat.
- **Equipment manufacturers (OEMs)** publish certified *skill packs* for their own products and want early warning of field failures.

## 2. The problem

| # | Pain | Why today's tools fail |
|---|------|------------------------|
| 1 | **No connectivity where the work is.** Basements, rooftops, shielded rooms and rural sites. | Cloud copilots (ChatGPT, Copilot, vendor portals) stop working exactly when they are needed. |
| 2 | **One technician, hundreds of equipment models.** A single visit can involve an inverter, a battery management system (BMS), wiring and a building management system (BMS). | A small on-device LLM is a generalist that is too weak for specialist fault codes. A large expert model does not fit on a phone. |
| 3 | **Expertise lives in a few senior heads.** When they are unavailable or retire, the knowledge is gone. | Knowledge bases are written once and go stale. What a technician learns on site rarely reaches the next technician. |
| 4 | **Customer sites forbid data egress.** Hospitals, factories and defence sites. | Anything that "uploads everything to the cloud" is a non-starter. |
| 5 | **Manufacturers learn about defects late,** usually from warranty claims weeks later. | Field observations are scattered across thousands of phones and never aggregated. |

**The KPI everyone in field service tracks:** *first-time fix rate*, meaning the job is solved on the first visit without a callback. Every item above hurts it.

## 3. The solution — Polymath

An Android app that runs a **single small LLM entirely on the phone** (Qwen2.5-1.5B, 4-bit, llama.cpp). It still behaves like a specialist in whatever the technician is looking at, because **Qdrant stores more than facts. It also stores the model's expertise.**

| What Qdrant stores on the phone | What it gives the user |
|---|---|
| **Skills**: LoRA adapters (~9 MB each), indexed by a *skill card* embedding | Every question is routed to the right expert. Two skills can be **blended** with weights from the similarity scores. |
| **Memory**: fixes, notes and answers (dense + sparse vectors) | Hybrid search that works on meaning *and* exact fault codes such as `E-21`. |
| **Drafts**: verified past answer spans | **Speculative decoding from memory.** The phone generates faster the more the team uses it. |
| **Gaps**: questions memory could not answer | Queued for the cloud and answered automatically on the next sync. |

And **Qdrant Server** in the cloud makes fleet-level decisions:
- **Geometric sync.** A Merkle tree over *semantic regions* (SimHash prefixes derived from a shared seed) finds *which topics* diverged, so only those are sent.
- **Sync gate.** Hubness × novelty decides what is team knowledge (push it), what is personal (keep it local), and what is redundant (send a +1 vote only).
- **Skill Factory.** When the fleet builds up dense knowledge in a region no skill covers, **Qdrant triggers the training of a new LoRA skill**, which then syncs back to the phones.
- **Fleet Radar.** Several phones independently landing in a previously empty region raises an **emerging-issue alert**, e.g. a new defect pattern seen by 4 technicians at 3 sites.

## 4. Why this is new

1. **Vector search as a router over model weights.** Qdrant chooses *which fine-tuned version of the model* answers, and the scores become blend weights. We have not seen this published.
2. **Vector search as a source for speculative decoding.** Retrieved answers become draft tokens that the model verifies in one pass.
3. **A Merkle tree organised by meaning.** Sync reports "your phone and the cloud disagree about *warranty policy*", not "412 rows differ".
4. **Geometry decides when to train.** Density and coverage in Qdrant are the trigger for creating new skills.
5. **Uses Qdrant Edge's official sync (partial snapshots) and extends it** with semantic anti-entropy, rather than replacing it.

## 5. How this maps to the brief

| Requirement | Polymath |
|---|---|
| Searchable semantic memory on-device | Qdrant Edge shards `memory`, `skills`, `drafts`, `gaps` |
| Low-latency vector + hybrid search offline | dense bge-small + sparse BM25, fused with RRF |
| Decide what stays local vs. syncs | 2×2 Sync Gate (hubness × novelty) + privacy flags |
| Intermittent connectivity | offline-first; op-log outbox; stable-window + backoff |
| Sync edge ↔ Qdrant Server | semantic Merkle diff + chunked resumable ops + partial snapshots |
| Evolving memory & conflicting info | HLC ordering, tombstones, reversible LLM-assisted merges |
| UI for memory / search / sync / activity | Memory Inspector, Skills Shelf, Sync & Activity, Conflict Inbox, Chaos Panel + web Fleet Dashboard |
| Meaningful edge-to-cloud AI workflow | offline gaps → cloud answers; fleet knowledge → new skill → back to phones |

## 6. Business model

- **SaaS per technician seat** for field-service organisations: offline copilot, sync and dashboard.
- **Skill marketplace.** OEMs publish certified skill packs for their equipment, and we take a revenue share. Skills are small, signed, versioned and evaluated before release.
- **Fleet Radar for OEMs.** Early field-failure intelligence, sold as a separate analytics tier.
- **Why they would pay:** higher first-time fix rate, faster onboarding of junior technicians, knowledge captured before experts leave, and data that stays on the device.

## 7. Demo script (≈4 min, phones in airplane mode)

1. **"Impossible" moment.** Phone A is in airplane mode. A technician asks about an inverter fault by voice. The screen shows `Skill: inverter-faults v3 (8.8 MB) · routed in 3 ms · blended with solar-wiring 38%`.
2. **Speed.** Toggle *memory-assisted decoding* and watch tokens/sec roughly double on the live gauge.
3. **Learn.** The technician logs a new fix. It is added to the op-log and appears in the Memory Inspector with its semantic region.
4. **Mesh.** Phone B, also offline, syncs with Phone A over Nearby Connections. The Merkle view shows *2 topics diverged* and bytes transferred vs. naive sync.
5. **Chaos.** Press the Chaos Panel: kill the app mid-sync and skew the clock. It restarts, resumes from its cursor, and nothing is lost or duplicated.
6. **Cloud.** Reconnect. The dashboard shows each item's sync decision (pushed / kept local / +1 vote). An offline gap gets answered, and a **Radar alert** fires for an emerging fault pattern.
7. **Skill birth.** The Skill Factory publishes a new skill from fleet knowledge. Phone A downloads it (hash verified), and the same question now routes to the new expert.

## 8. Milestones

| Date | Milestone |
|---|---|
| **Sep 28** | Day-1 checks: Qdrant Edge Kotlin SDK on device · llama.cpp + LoRA hot-swap on Android · embedder latency |
| **Oct 3 (online round)** | Offline ask loop: voice → router → LoRA → hybrid retrieval → answer. Op-log + basic push sync to Qdrant Server. Pitch + architecture. |
| **Oct 4–8** | Semantic Merkle sync, Sync Gate, P2P mesh, speculative decoding, conflict flow, dashboard |
| **Oct 9–10** | Skill Factory (2–3 generated skills), Fleet Radar, Chaos Panel, rehearse demo |
| **Oct 11 (offline round)** | Full two-phone + cloud live demo |
