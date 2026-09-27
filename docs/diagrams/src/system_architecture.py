"""Generates docs/diagrams/system-architecture.svg"""
import os
from svg import Svg, C, TEXT, MUTED, CARD

W, H = 2600, 1760
s = Svg(W, H)

# ---------------------------------------------------------------- title
s.text(40, 52, "Polymath — System Architecture", 30, TEXT, 800)
s.text(40, 78, "A 1.5B on-device LLM that retrieves its own expertise (LoRA skills), memory and drafts from Qdrant Edge — and syncs geometrically with Qdrant Server.", 14, MUTED)

# ================================================================ EDGE
EX, EY, EW, EH = 30, 100, 1300, 1300
s.panel(EX, EY, EW, EH, "EDGE · Android phone", "offline is the normal state · Kotlin + NDK", C["edge"])

# Row 1 — UI
s.comp(55, 150, 1250, 90, "Jetpack Compose UI", "Kotlin 2 · Material 3 · StateFlow", [], C["ui"])
for i, lbl in enumerate(["Ask (voice / text)", "Skills Shelf", "Memory Inspector", "Sync & Activity", "Conflict Inbox", "Chaos Panel"]):
    s.chip(300 + i * 168, 196, 156, lbl, C["ui"])

# Row 2 — input + governor
s.comp(55, 265, 615, 110, "Input Layer", "whisper.cpp · tiny.en q5_1 (31 MB) · JNI",
       ["Push-to-talk → on-device speech-to-text", "Text ask · \"Log a fix\" capture · job context (site, asset)"], C["ai"])
s.comp(690, 265, 615, 110, "Resource Governor", "BatteryManager · PowerManager thermal API · onTrimMemory",
       ["Picks a rung on the degradation ladder: full → no-spec → 1 adapter", "→ base-only → retrieval-only. Enforces storage quota."], C["ops"])

# Row 3 — orchestrator
s.group(55, 400, 1250, 190, "Query Orchestrator", "Kotlin Coroutines · structured concurrency", C["ai"])
bx = [70, 316, 562, 808, 1054]
s.comp(bx[0], 440, 234, 135, "Dense Embedder", "ONNX Runtime Mobile",
       ["bge-small-en-v1.5 int8", "384-d · ~15 ms / query", "model_id stamped on vector"], C["ai"])
s.comp(bx[1], 440, 234, 135, "Sparse Encoder", "BM25 · Kotlin tokenizer",
       ["exact hits on fault codes,", "part numbers (E-21, SKU-4471B)", "→ Qdrant sparse vector"], C["ai"])
s.comp(bx[2], 440, 234, 135, "Skill Router", "Qdrant Edge · skills shard",
       ["top-k skill cards by cosine", "load if s₁ ≥ τ, blend top-2", "α = softmax(s / T)"], C["qdrant"])
s.comp(bx[3], 440, 234, 135, "Hybrid Retriever", "Query API · prefetch + RRF",
       ["dense ⊕ sparse fusion", "filters: site, asset, visibility", "miss (score < τ) → gap"], C["qdrant"])
s.comp(bx[4], 440, 234, 135, "Prompt + Confidence", "citations · staleness decay",
       ["conf = retrieval × skill-fit", "        × e^(−age / half-life)", "shown as badge per answer"], C["ai"])

# Row 4 — llama.cpp + Qdrant Edge
s.group(55, 615, 615, 240, "Inference Engine", "llama.cpp · JNI · NDK · arm64 NEON/i8mm", C["ai"])
s.comp(70, 655, 285, 90, "Base Model (frozen)", "Qwen2.5-1.5B-Instruct Q4_K_M",
       ["GGUF · mmap · ~1.0 GB on flash", "loaded once, never re-trained"], C["ai"], 13)
s.comp(370, 655, 285, 90, "LoRA Slots ×2 (hot-swap)", "llama_adapter_lora · per-request",
       ["r=16 · ~9 MB each · α-weighted", "W' = W + α₁B₁A₁ + α₂B₂A₂"], C["ai"], 13)
s.comp(70, 755, 285, 90, "Speculative Decoder", "lookup / n-gram drafting",
       ["drafts = retrieved past answers", "model verifies k tokens / pass"], C["ai"], 13)
s.comp(370, 755, 285, 90, "KV Cache + Sampler", "4k ctx · f16 · ~115 MB",
       ["context shift on overflow", "grammar-constrained JSON mode"], C["ai"], 13)

s.group(690, 615, 615, 240, "Qdrant Edge (in-process)", "Kotlin SDK · UniFFI · qdrant-edge-ffi", C["qdrant"])
s.comp(705, 655, 285, 90, "memory", "EdgeShard · dense 384 + sparse",
       ["fixes, notes, answers · payload:", "hlc, device, simhash, visibility"], C["qdrant"], 13)
s.comp(1005, 655, 285, 90, "skills", "EdgeShard · skill cards",
       ["vector = card embedding", "payload → adapter sha256, ver"], C["qdrant"], 13)
s.comp(705, 755, 285, 90, "drafts", "EdgeShard · answer spans",
       ["verified answer continuations", "feed the speculative decoder"], C["qdrant"], 13)
s.comp(1005, 755, 285, 90, "gaps  +  atlas", "EdgeShard ×2 · misses · centroids",
       ["gaps: unanswered → cloud later", "atlas: cloud clusters → novelty"], C["qdrant"], 13)

# Row 5 — durability
s.comp(55, 880, 405, 135, "Op-Log & Outbox  (source of truth)", "SQLite · Room · WAL mode",
       ["append-only ops · op_id = UUIDv7 (idempotent)", "Hybrid Logical Clock — never wall clock",
        "ed25519-signed · hash-chained (prev_hash)", "Qdrant shards = rebuildable views"], C["store"], 13)
s.comp(477, 880, 405, 135, "Adapter Store", "content-addressed GGUF · filesDir/skills/",
       ["sha256 verified before load, else quarantine", "LRU eviction by RAM + flash budget",
        "resumable download (HTTP Range) + atomic rename", "pinned skills survive eviction"], C["store"], 13)
s.comp(899, 880, 406, 135, "Semantic Merkle Index", "SimHash-16 trie · shared seed · SHA-256",
       ["same seed ⇒ same hyperplanes on every device", "prefix = semantic region (coarse → fine)",
        "leaf hash = H(sorted op_id, version)", "diff ⇒ WHICH TOPICS diverged, not rows"], C["store"], 13)

# Row 6 — sync engine
s.group(55, 1040, 1250, 195, "Sync Engine", "WorkManager + foreground service · OkHttp · Wire (Protobuf) · zstd", C["sync"])
sx = [70, 378, 686, 994]
s.comp(sx[0], 1080, 296, 140, "Connectivity Sentinel", "ConnectivityManager · NetworkCallback",
       ["stable-window (30 s) before sync", "metered? → budget mode", "exp. backoff + jitter · circuit breaker"], C["sync"], 13)
s.comp(sx[1], 1080, 296, 140, "Sync Gate  (2 × 2)", "hubness × novelty",
       ["hub  + novel     → push first", "leaf + novel     → keep local", "any + redundant → send +1 vote only", "PII / private     → never leaves"], C["sync"], 13)
s.comp(sx[2], 1080, 296, 140, "Chunked Transfer", "256 KB chunks · per-chunk ack",
       ["persisted cursor → resume after kill", "server dedupes by op_id", "priority queue under byte budget"], C["sync"], 13)
s.comp(sx[3], 1080, 296, 140, "Conflict Handler", "near-dup ∧ divergent claim",
       ["originals kept, merge is reversible", "merge cards → Conflict Inbox", "tombstones until all peers ack"], C["sync"], 13)

# Row 7 — P2P + invariants
s.comp(55, 1260, 615, 120, "P2P Mesh", "Google Nearby Connections · P2P_CLUSTER · BLE + Wi-Fi Direct",
       ["phone ↔ phone with zero internet · same Merkle protocol", "any phone that later reaches cloud relays peers' signed ops",
        "peer ops verified with ed25519 before apply"], C["sync"], 13)
s.rect(690, 1260, 615, 120, fill=CARD, stroke=C["ops"], sw=1.2, dash="4 4")
s.text(706, 1284, "Edge invariants", 14, TEXT, 700)
for i, t in enumerate(["op-log is truth, Qdrant is a view", "every op idempotent (op_id)", "order by HLC, not clocks",
                       "no cross-model vectors (model_id)", "every artifact hash-verified", "always answer (degradation ladder)"]):
    s.chip(706 + (i % 2) * 297, 1298 + (i // 2) * 27, 287, t, C["ops"], 22, 11)

# edge internal arrows (flow 1 ask, 2 learn)
s.arrow([(250, 240), (250, 263)], "ui"); s.badge(228, 252, 1, C["ui"])
s.arrow([(250, 375), (250, 398)], "ai")
s.arrow([(300, 575), (300, 653)], "ai")                 # embedder → llama (prompt)
s.arrow([(679, 575), (679, 600), (800, 600), (800, 653)], "qdrant", both=True)   # router ↔ skills/memory
s.arrow([(925, 575), (925, 653)], "qdrant", both=True)
s.arrow([(1171, 575), (1171, 600), (1147, 600), (1147, 653)], "qdrant", both=True)
s.arrow([(600, 880), (600, 847)], "store")              # adapter store → lora slots
s.text(608, 862, "load / blend", 10.5, C["store"])
s.arrow([(258, 880), (258, 867), (720, 867), (720, 847)], "store"); s.badge(236, 868, 2, C["store"])
s.text(272, 862, "apply op → upsert view", 10.5, C["store"])
s.arrow([(1100, 855), (1100, 878)], "store")
s.arrow([(258, 1015), (258, 1078)], "sync")
s.arrow([(1100, 1015), (1100, 1078)], "sync")
s.arrow([(55, 195), (42, 195), (42, 700), (53, 700)], "ui", dash="5 4")  # answer back to UI
s.add('<text x="38" y="470" font-size="10.5" fill="#60A5FA" transform="rotate(-90 38 470)" text-anchor="middle">answer + confidence</text>')

# ================================================================ LINK COLUMN
LX = 1345
s.rect(LX, 100, 215, 1300, fill="#0C121D", stroke="#1E2A3C", sw=1, rx=16)
s.text(LX + 107, 132, "NETWORK", 14, C["sync"], 800, "middle")
s.text(LX + 107, 150, "intermittent · untrusted", 11, MUTED, 400, "middle")
s.chip(LX + 12, 162, 191, "HTTPS/2 · TLS 1.3", C["sync"], 22, 11)
s.chip(LX + 12, 190, 191, "Protobuf (Wire) + zstd", C["sync"], 22, 11)
BUS = LX + 107
s.line([(1305, 1150), (BUS, 1150), (BUS, 240)], C["sync"], 4)
s.arrow([(BUS, 240), (BUS, 232), (1603, 232)], "sync", 4)
for i, (n, lbl, sub) in enumerate([
    (3, "ops push ↑", "signed · chunked · resumable"),
    (4, "Merkle roots ↔", "descend only diverged regions"),
    (5, "pull ↓", "regions · gap answers · merges"),
    (6, "skills ↓", "manifest + GGUF (HTTP Range)"),
]):
    y = 330 + i * 150
    s.rect(LX + 12, y, 191, 74, fill="#111A2A", stroke=C["sync"], sw=1.3, rx=10)
    s.badge(LX + 32, y + 22, n, C["sync"])
    s.text(LX + 52, y + 27, lbl, 13, TEXT, 700)
    s.text(LX + 107, y + 54, sub, 10.5, MUTED, 400, "middle")
s.comp(LX + 12, 1262, 191, 118, "Peer Phone", "Polymath",
       ["offline too", "gossips via Nearby", "same protocol"], C["edge"], 13)
s.arrow([(400, 1380), (400, 1391), (1338, 1391), (1338, 1322), (LX + 10, 1322)], "sync", 2.5, "7 5", both=True)
s.badge(1000, 1391, 7, C["sync"])

# ================================================================ CLOUD
CX, CY, CW, CH = 1580, 100, 990, 1300
s.panel(CX, CY, CW, CH, "CLOUD · Qdrant Server + services", "Docker Compose · any VM / GPU box", C["cloud"])

s.comp(1605, 150, 940, 120, "Edge Gateway", "Caddy (TLS) → FastAPI · Python 3.12 · Uvicorn · Pydantic v2",
       ["device auth (ed25519 challenge) · op verify + dedupe by op_id · writes global op-log to Postgres"], C["sync"])
for i, e in enumerate(["POST /v1/merkle", "POST /v1/ops", "GET /v1/pull", "POST /v1/gaps", "GET /v1/skills", "WS /v1/live"]):
    s.chip(1620 + i * 153, 232, 143, e, C["sync"], 24, 11)

s.group(1605, 295, 940, 225, "Qdrant Server", "qdrant/qdrant (Docker) · HNSW · payload indexes", C["qdrant"])
cols = [("fleet_memory", "dense+sparse", ["all synced knowledge", "keyword idx: simhash"]),
        ("skill_registry", "skill cards", ["→ MinIO adapter URI", "base_model, eval score"]),
        ("draft_corpus", "answer spans", ["best verified answers", "per skill"]),
        ("knowledge_gaps", "fleet misses", ["queued questions", "dedup by region"]),
        ("atlas · radar", "centroids + watch", ["k-means over fleet", "new-region alerts"])]
for i, (t, tech, ln) in enumerate(cols):
    s.comp(1620 + i * 184, 335, 174, 110, t, tech, ln, C["qdrant"], 13)
s.text(1620, 472, "edge `skills` shard is refreshed via official partial snapshots:", 11.5, MUTED)
s.text(1620, 492, "edge.snapshot_manifest() → POST …/snapshot/partial/create → edge.update_from_snapshot()", 11.5, C["qdrant"], mono=True)

s.comp(1605, 545, 460, 120, "PostgreSQL 16", "source of truth (cloud side)",
       ["devices & keys · global op-log · HLC watermarks", "tombstones + per-peer ack · skill versions"], C["store"])
s.comp(2085, 545, 460, 120, "MinIO  (S3 API)", "object storage",
       ["adapter GGUF blobs (content-addressed)", "Qdrant partial snapshots · signed manifests"], C["store"])

s.group(1605, 690, 940, 200, "Workers", "Redis 7 + ARQ (async job queue)", C["ai"])
wk = [("Conflict Resolver", "Qwen2.5-7B · Ollama/vLLM", ["NLI on near-duplicate pairs", "writes merge proposals", "never deletes originals"]),
      ("Gap Answerer", "RAG over fleet_memory", ["answers queued offline", "questions → pushed down", "on next device sync"]),
      ("Fleet Radar", "density watch", ["≥3 devices land in an", "empty region within 48 h", "→ emerging-issue alert"]),
      ("Draft Curator", "acceptance-rate stats", ["promotes spans with high", "spec-decode acceptance", "→ draft_corpus"])]
for i, (t, tech, ln) in enumerate(wk):
    s.comp(1620 + i * 230, 730, 220, 145, t, tech, ln, C["ai"], 13)

s.group(1605, 915, 940, 150, "Skill Factory  —  Qdrant decides when a new skill is born", "GPU: RunPod / Colab", C["ai"])
steps = ["Region Miner", "Teacher Synth", "LoRA Train", "Eval Gate", "GGUF Convert", "Sign + Publish"]
subs = ["dense uncovered region", "Qwen2.5-7B SFT pairs", "Unsloth · PEFT r=16", "held-out ≥ base+Δ", "convert_lora_to_gguf", "ed25519 → registry"]
for i, (st, sb) in enumerate(zip(steps, subs)):
    x = 1620 + i * 152
    s.rect(x, 955, 140, 92, fill=CARD, stroke=C["ai"], sw=1.3)
    s.text(x + 70, 985, st, 13, TEXT, 700, "middle")
    s.text(x + 70, 1008, sb.split(" ")[0] if len(sb) > 22 else sb, 10.5, MUTED, 400, "middle")
    if len(sb) > 22:
        s.text(x + 70, 1024, " ".join(sb.split(" ")[1:]), 10.5, MUTED, 400, "middle")
    if i < 5:
        s.arrow([(x + 140, 1001), (x + 151, 1001)], "ai", 2)

s.comp(1605, 1090, 940, 140, "Fleet Dashboard", "Next.js 15 · shadcn/ui · Recharts · WebSocket",
       ["Live fleet map & per-device sync health · Radar alerts (emerging issues) · Skill catalogue + eval scores",
        "Semantic-diff viewer (which topics diverged) · bytes saved vs naive sync · Conflict review queue"], C["ui"])
s.comp(1605, 1255, 460, 125, "Observability", "Prometheus · Grafana · OpenTelemetry",
       ["sync latency, bytes/op, resume count", "adapter hit-rate, spec-accept rate"], C["ops"])
s.comp(2085, 1255, 460, 125, "Model Sources (build-time)", "Hugging Face Hub",
       ["Qwen2.5-1.5B/7B GGUF · bge-small ONNX", "whisper tiny.en · pinned by sha256"], C["ops"])

# cloud arrows
s.arrow([(1800, 270), (1800, 293)], "sync", both=True)
s.arrow([(2075, 520), (2075, 688)], "qdrant", both=True)
s.arrow([(1606, 1000), (1592, 1000), (1592, 420), (1603, 420)], "ai"); s.badge(1592, 700, 8, C["ai"])
s.arrow([(2545, 1000), (2558, 1000), (2558, 600), (2547, 600)], "ai")
s.arrow([(2420, 875), (2420, 900), (2558, 900), (2558, 1150), (2547, 1150)], "ai", dash="5 4"); s.badge(2558, 1080, 9, C["ai"])

# ================================================================ LEGEND
LY = 1425
s.rect(30, LY, 2540, 310, fill="#0C121D", stroke="#1E2A3C", sw=1, rx=16)
s.text(55, LY + 36, "Flows", 18, TEXT, 800)
flows = [
    (1, C["ui"], "Ask", "voice → whisper.cpp → embed (dense+sparse) → Skill Router picks & blends LoRA → hybrid retrieve memory → drafts → llama.cpp → answer + confidence"),
    (2, C["store"], "Learn", "\"Log a fix\" → op appended to Op-Log (HLC, signed) → applied to Qdrant Edge view → SimHash bucket → Merkle leaf updated"),
    (3, C["sync"], "Push", "Sync Gate ranks ops by hubness × novelty → private/leaf stays local → redundant sends +1 vote → chunks resume after crash"),
    (4, C["sync"], "Diff", "roots compared → descend only mismatched SimHash prefixes → result is a list of diverged TOPICS (shown in UI)"),
    (5, C["sync"], "Pull", "diverged regions, answers to offline gaps, merge proposals, tombstones ↓ ; skills shard via Qdrant partial snapshot"),
    (6, C["sync"], "Skills", "router predicts needed skills from gap + query trajectory → GGUF pre-fetched → sha256 verified → atomic install"),
    (7, C["sync"], "Mesh", "phone ↔ phone over Nearby Connections, zero internet; either phone relays the other's signed ops when it reaches cloud"),
    (8, C["ai"], "Birth", "Region Miner finds dense fleet knowledge no skill covers → teacher data → LoRA → eval gate → published to registry"),
    (9, C["ai"], "Radar", "independent devices landing in a previously-empty region → emerging-issue alert on dashboard (fleet-level novelty)"),
]
for i, (n, col, t, d) in enumerate(flows):
    cx = 55 + (i // 5) * 1265
    cy = LY + 70 + (i % 5) * 44
    s.badge(cx + 12, cy, n, col)
    s.text(cx + 34, cy + 5, t, 14, col, 700)
    s.text(cx + 100, cy + 5, d, 12.5, MUTED)
s.text(1320, LY + 70 + 4 * 44 + 5, "Colour key:", 13, TEXT, 700)
for i, (k, lbl) in enumerate([("ui", "UI"), ("ai", "AI / compute"), ("qdrant", "Qdrant"), ("store", "durability"), ("sync", "sync / network"), ("ops", "ops / runtime")]):
    s.chip(1410 + i * 150, LY + 70 + 4 * 44 - 12, 138, lbl, C[k], 24, 12)

out = os.path.join(os.path.dirname(__file__), "..", "system-architecture.svg")
with open(out, "w", encoding="utf-8") as f:
    f.write(s.render())
print("wrote", os.path.abspath(out))
