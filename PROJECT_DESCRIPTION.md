# PolyCare — Project Description

> **A health expert in every ASHA worker's pocket. No signal required.**
> Geek Room × Qdrant Hackathon · Problem Statement 03 — *AI-Powered Edge Memory & Intelligence Platform*

---

## 1. Who we are building for

**Primary user: the ASHA worker.**
India has about **1 million ASHAs** (Accredited Social Health Activists). Each one looks after roughly 1,000 people in a village or urban slum. She tracks every pregnancy, newborn and child, gives first-line advice for fever, diarrhoea, TB, malaria and malnutrition, and decides who must be referred to a health centre *today*. She does this from memory, printed modules and paper registers, often with little or no mobile signal.

**Around her:**
- **ANM / supervisors** at the sub-centre who review her work and handle referrals.
- **Block and district health teams** who need early warning of outbreaks and a true picture of the field.

**Buyers:** State health missions (NHM), NGOs and implementation partners running community-health programmes, CSR health programmes, and private hospital networks and insurers running community outreach.

## 2. The problem

| # | Pain | Why today's tools fail |
|---|------|------------------------|
| 1 | **No connectivity where the work is.** Remote villages, forest and hill areas, and homes where the phone has one bar or none. | Online apps and cloud chatbots fail exactly at the doorstep. |
| 2 | **Hundreds of protocols, one worker.** Pregnancy danger signs, newborn care, immunisation schedules, child illness (IMNCI), TB, malaria, dengue, nutrition, NCD screening. | Printed modules are thick and hard to search. A small generic on-device model is not reliable enough on specialist protocols. |
| 3 | **Missed danger signs cost lives.** Late referral in pregnancy, newborn sepsis, severe dehydration and severe malnutrition. | Decisions depend on what the worker remembers under pressure. |
| 4 | **Paperwork eats the day.** Multiple registers and monthly reports; incentive payments depend on them. | Data is entered twice (paper, then app) and often lost. |
| 5 | **Outbreaks are seen late.** A cluster of fever-with-rash across four villages is only noticed when reports are compiled weeks later. | Field observations stay scattered across thousands of phones and registers. |
| 6 | **Health data is sensitive.** Pregnancy, HIV/TB status and family planning must not leak. | "Upload everything to the cloud" conflicts with consent and privacy rules (ABDM). |

## 3. The solution — PolyCare

An Android app that runs a **single small LLM entirely on the phone** (Qwen2.5-1.5B, 4-bit, llama.cpp). It behaves like a specialist in whatever the ASHA is dealing with, because **Qdrant Edge stores the model's expertise as well as the facts**.

| What Qdrant Edge stores on the phone | What it gives the ASHA |
|---|---|
| **Skills**: LoRA adapters (~9 MB each) for maternal care, newborn care, child illness, immunisation, nutrition, communicable disease, NCD screening | Every question is routed to the right expert. Two skills can be **blended** by similarity score. |
| **Knowledge**: up to ~1 million protocol, drug and health-education passages | Instant answers with the source shown, fully offline. |
| **Households**: her own families, visits, pregnancies and children (private) | "What did I note last time at this house?" and a daily due list. |
| **Drafts**: verified past answers | **Speculative decoding from memory**: answers get faster the more the team uses the app. |
| **Gaps**: questions the phone could not answer | Answered by the cloud on the next sync and reviewed by a supervisor. |

**Qdrant Cloud** (managed Qdrant Server) makes team- and district-level decisions:
- **Semantic sync.** Only topics that actually differ are exchanged, and only **de-identified** knowledge leaves the phone.
- **Sync Gate.** Decides what is team knowledge (push), what is personal (keep on the phone) and what is redundant (send a "+1" only).
- **Outbreak Radar.** De-identified symptom signals from several ASHAs landing in the same *new* region of vector space, across several villages, raise an alert for the district and push updated guidance back to the phones in that area.
- **Knowledge atlas.** The cloud holds the full knowledge base (tens of millions of points). Each phone holds the **~1 million points most relevant to its district, languages and programmes**, and the cloud refreshes that slice as needs change.
- **Skill Factory.** When the field builds up dense knowledge in an area no skill covers, a new LoRA skill is trained, checked and shipped to the phones.

## 4. Features

### Care at the doorstep
1. **Ask by voice or text** in Hindi or English (more languages later). The answer shows which skill answered, the protocol source and a confidence badge.
2. **Danger-sign triage.** Guided check → *Refer now* / *Refer within 24 h* / *Care at home*, with the protocol reference. Decision support only; it never claims to diagnose.
3. **Medicine helper.** Dose by age/weight band, common side effects, what the ASHA may and may not give.
4. **Counselling cards.** Short spoken and visual explanations for families (breastfeeding, ORS, danger signs, family planning).

### Less paperwork
5. **OCR scan.** Photograph a Mother & Child Protection (MCP) card, lab report, prescription, medicine strip or register page. Text (English and Devanagari) is read **on the phone** and filled into the right household record.
6. **Household memory.** Every visit, note and reading is searchable by meaning: *"which houses had a child with fever this week?"*
7. **Due list and visit planner.** Today's visits in priority order: due deliveries, missed vaccines, high-risk pregnancies and follow-ups.
8. **Monthly report and incentive tracker.** Reports are filled from recorded visits, not re-typed.

### Team and district intelligence
9. **Sync with Qdrant Cloud** whenever a connection appears; nothing is lost if it drops halfway.
10. **Gap answering.** Questions asked offline are answered from the cloud and checked by a supervisor.
11. **Outbreak Radar.** Early alert when similar symptoms cluster across villages.
12. **Conflict Inbox.** When two workers record different details for the same family, both are kept and shown side by side for review; nothing is silently overwritten.
13. **Phone-to-phone sync** at sub-centre meetings with no internet (optional).
14. **Supervisor dashboard** (web) for ANMs and block teams: referrals, alerts, gaps, coverage.

### Trust
15. **Private stays private.** Names, phone numbers, addresses and sensitive conditions never leave the phone; only de-identified signals sync.
16. **Always answers.** On low battery, heat or low memory the app steps down to lighter modes and, at worst, shows the exact protocol passage.
17. **Memory Inspector and Activity log.** See what the phone knows, what synced and why.

## 5. Why this is new

1. **Vector search as a router over model weights.** Qdrant chooses *which fine-tuned version of the model* answers, and the scores become blend weights.
2. **Vector search as a source for speculative decoding.** Retrieved answers become draft tokens the model verifies in one pass.
3. **A million-point knowledge base on a phone, chosen by geometry.** The cloud decides which slice of vector space each phone should carry.
4. **Outbreak detection from vector geometry.** New dense regions of de-identified symptom signals across devices and villages are the alert.
5. **Sync by meaning.** "Your phone and the cloud disagree about *dengue guidance*", not "412 rows differ". Built on Qdrant Edge's official partial snapshots and extended with semantic anti-entropy.

## 6. How this maps to the brief

| Requirement | PolyCare |
|---|---|
| Searchable semantic memory on-device | Qdrant Edge shards: `knowledge`, `households`, `memory`, `skills`, `drafts`, `gaps` |
| Low-latency vector + hybrid search offline | Dense multilingual embeddings + sparse BM25, fused with RRF |
| Decide what stays local vs. syncs | Sync Gate (hubness × novelty) + privacy rules; personal data never leaves |
| Intermittent connectivity | Offline-first; op-log outbox; stable window + backoff; resumable |
| Sync edge ↔ Qdrant Server | Qdrant Cloud: semantic Merkle diff, chunked resumable ops, partial snapshots |
| Evolving memory & conflicting info | Hybrid logical clocks, tombstones, Conflict Inbox with reversible merges |
| UI for memory / search / sync / activity | Memory Inspector, Search, Sync & Activity, Conflict Inbox, Chaos Panel + web dashboard |
| Meaningful edge-to-cloud AI workflow | Offline gaps → cloud answers; field signals → Outbreak Radar → guidance back to phones; knowledge → new skills |

## 7. Business model

- **Per-worker licence** for health missions, NGOs and CSR programmes (priced for scale: tens of thousands of workers per state).
- **Analytics tier** for district and state teams: Outbreak Radar, coverage and referral insights.
- **Content partnerships**: certified skill packs from medical bodies and programmes.
- **Why they would pay:** earlier referrals, fewer missed vaccinations, less paperwork, faster outbreak response, and data that stays on the device.

## 8. Demo script (≈4 min, phones in airplane mode)

1. **Doorstep.** Airplane mode. The ASHA asks by voice, in Hindi, about a pregnant woman with swelling and headache. Screen: `Skill: maternal-care v2 · routed in 3 ms · 1.02 M passages searched in 9 ms` → *Refer now: possible pre-eclampsia*, with the protocol source.
2. **Scan.** She photographs the MCP card; blood pressure and due date are read by OCR and filed into the household record.
3. **Speed.** Toggle memory-assisted decoding; tokens/sec rises on the live gauge.
4. **Sync.** Reconnect. The Sync screen shows *"2 topics diverged"*, what was pushed, what stayed private and the bytes saved.
5. **Chaos.** Kill the app mid-sync and skew the clock; it resumes with nothing lost or duplicated.
6. **Radar.** Several phones report fever-with-rash from different villages; the dashboard raises an alert and new guidance arrives on the phones.
7. **Gap answered.** A question asked offline earlier is now answered from the cloud.

*All performance figures are targets until measured on our demo phones.*
