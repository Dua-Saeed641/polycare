# Qdrant Edge Hackathon — Brief for Solution Design

## Event
- Geek Room x Qdrant hackathon, Problem Statement 03
- Online round: 3 Oct · Offline round: 11 Oct

## Problem Statement (as given)
Build an **offline-first AI application powered by Qdrant Edge** that can:
- Maintain searchable semantic memory directly on an edge device
- Perform low-latency vector and hybrid search without network access
- Dynamically decide what information should remain local vs. what should sync to the cloud
- Support intermittent connectivity and continue operating offline
- Synchronize data between edge devices and a Qdrant Server when connectivity returns
- Handle evolving local memory, updates, and conflicting information
- Provide a user-facing interface to inspect device memory, search results, sync status, and system activity
- Demonstrate a **meaningful edge-to-cloud AI workflow** — not just a local vector DB running standalone

**Expected outcome:** a complete edge-native AI product that can remember, retrieve, operate offline, and synchronize intelligently when connected.

## Hard Constraint
**The build target is a mobile phone.** Whatever gets designed must run within phone-class compute/memory/battery, use on-device inference (small quantized LLMs or none at all), and treat network loss as the normal state, not an edge case.

## Explicit Goal
Do something with Qdrant that hasn't been done publicly yet — use it beyond "local cache + REST sync," ideally by leaning on internals (HNSW graph structure, distance geometry) as active decision-making signals rather than just a passive store. The team wants judges/Qdrant engineers to see a mechanism they didn't expect.

## Non-negotiable framing
- **Not built on top of an existing project** (there's a prior voice+KG+vector-store system called SMAR, but this must stand alone).
- **Commercially viable direction preferred** — a product/mechanism a paying company or consumer would actually want, not a pure government/NGO demo.

---

## Technical mechanisms already explored (candidate building blocks)

1. **Merkle-tree anti-entropy sync**
   Both local and cloud (or peer) Qdrant collections maintain a hash tree over shards. On reconnect, compare tree roots top-down and only transfer data for branches that diverge — like Cassandra/DynamoDB anti-entropy, applied to vector index reconciliation instead of row data. Minimizes sync payload to exactly what changed.

2. **HNSW-degree-based sync prioritization**
   A new point's degree (connectivity) within the local HNSW graph indicates whether it's a generalizable "hub" (broadly relevant, worth sharing) or a device-specific "leaf/outlier" (idiosyncratic, keep local). Uses Qdrant's own graph structure as the sync-worthiness signal instead of external rules/tags.

3. **Novelty-gated sync**
   Keep a small local "shadow index" of known cloud cluster centroids. Before syncing a new point, check its distance to the nearest known centroid — if it's redundant with what the cloud already has, don't transmit it; only genuinely novel points get sent. Turns sync into an information-theoretic compression step.

4. **Conflict resolution as embedding-space merge**
   When reconciling devices find near-duplicate but textually diverged points, don't pick a winner (no last-write-wins) — synthesize a single canonical fact via a local LLM, re-embed, and push one merged point back to both sides. Conflicts become consolidation events.

5. **Memory consolidation / decay**
   Periodically cluster old, low-access points into summarized "gist" embeddings (hippocampus→cortex style compression), shrinking what needs to be stored, searched, or ever synced over time.

6. **Retrieval-augmented / external-memory attention (AirLLM / sparse-attention adjacent)**
   Instead of only using Qdrant for document RAG, use it as external memory for the local LLM's own context: when context window would overflow, embed and store the overflow, then retrieve only the top-k relevant past slices on the next turn (Landmark/Unlimiformer-style), rather than truncating. Can be paired with AirLLM-style layer streaming to run a larger model than device RAM would normally allow.

7. **Predictive pre-sync / pre-warming**
   Detect degrading connectivity trend before full disconnection; use the trajectory of recent query embeddings to predict what region of the embedding space will be needed next and pre-pull exactly that neighborhood from cloud before going offline (like offline maps, but computed from behavior, not manually selected).

8. **Staleness-as-confidence in the UI**
   Every local point carries a decay/staleness score for time-since-reconciled-with-cloud. Offline answers that rely on stale, unreconciled vectors are flagged as lower-confidence in the UI, rather than presenting all offline answers as equally certain. Directly serves the brief's "UI to inspect memory/sync status" requirement with real epistemic signal, not just a sync spinner.

9. **Peer-to-peer mesh sync (no cloud in the loop at all, or cloud as just one more peer)**
   Devices gossip vector deltas directly to each other over local radio (BLE/WiFi Direct) using the same Merkle-anti-entropy logic, without requiring backhaul internet. Any device with connectivity can act as a relay/supernode opportunistically.

10. **Pheromone-style decaying vector "scent"**
    Instead of a shared central map/state, devices leave decaying vector traces at contextually-anchored points (e.g., "this was slow/wrong/blocked") that other devices query directly via similarity search — no central coordinator, fully emergent multi-device behavior, decay handles staleness automatically.

11. **Cryptographically hash-chained local decision log**
    Each local inference/decision gets hash-chained (blockchain-style provenance, not a currency) so what the on-device model "believed" at a given moment is tamper-evident and auditable after the fact — relevant for any liability/compliance-sensitive vertical.

## Corrected misconceptions (for context, don't re-litigate)
- Google Authenticator (TOTP) offline codes work via a **shared secret established once at setup + a synchronized clock**, computed independently on each side — no live communication, ever. The reusable principle: deterministic derivation from a shared reference removes the need for constant syncing.
- Quantum entanglement **cannot** be used to make two systems "stay in sync" with zero communication (no-communication theorem) — this specific idea is a hard no, not just an engineering challenge. The Merkle-tree anti-entropy approach is the real, buildable analog of "minimal necessary communication to stay consistent."

## Candidate product directions discussed (commercial framing)
- Privacy-first personal "second brain" across a user's own phone/laptop/tablet (Rewind/Limitless-style category, but processing and storage stay fully on-device; own-device sync only, never third-party cloud)
- Offline-first sales/field-rep CRM copilot for conferences, client sites, flights
- Offline meeting/call intelligence that never uploads raw audio, only distilled insights, for compliance-sensitive industries
- Offline AI pair-programmer with cross-machine personal memory, code never leaves the user's own devices
- Warehouse/logistics fleet intelligence (mesh sync between scanners/robots in dead-zone environments)
- On-device fraud-pattern detection licensed to POS/payment terminal vendors
- Satellite/spacecraft downlink bottleneck — onboard vector memory decides what imagery is genuinely novel and worth the scarce downlink window
- Maritime/shipping predictive maintenance — bandwidth-metered satellite links, sync only true deltas

## What's needed from this exercise
Given the mobile-phone build constraint, the two-week timeline, and the mechanisms above: propose a specific, buildable product/demo that (a) uses at least one of the mechanisms above in a way that's genuinely novel for Qdrant, (b) is achievable on phone-class hardware in ~2 weeks, (c) has a clear, visually compelling live demo (ideally two phones, no wifi), and (d) has a believable path to being something people/companies would pay for.
