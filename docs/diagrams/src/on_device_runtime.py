"""Generates docs/diagrams/on-device-runtime.svg — how the model sits on the phone."""
import os
import textwrap
from svg import Svg, C, TEXT, MUTED, CARD

W, H = 2400, 1500
s = Svg(W, H)

s.text(40, 52, "Polymath — On-Device Runtime", 30, TEXT, 800)
s.text(40, 78, "How one frozen 1.5B model becomes many experts: Qdrant Edge picks the LoRA skills, the memory and the drafts for every single request.", 14, MUTED)

# ------------------------------------------------------------ A. lifecycle
s.panel(30, 100, 720, 1000, "① Request lifecycle", "target budgets*", C["edge"])
steps = [
    ("ui", "Voice → text", "whisper.cpp tiny.en q5_1", "~0.4 s / 5 s clip"),
    ("ai", "Embed query", "bge-small int8 · ONNX RT", "~15 ms"),
    ("ai", "Sparse encode", "BM25 tokenizer", "< 1 ms"),
    ("qdrant", "Route skill", "Qdrant Edge · skills", "~2 ms"),
    ("store", "Load / blend LoRA", "mmap GGUF · Adapter Store", "0 warm · ~100 ms cold"),
    ("qdrant", "Hybrid retrieve", "memory · dense+sparse · RRF", "~5 ms"),
    ("qdrant", "Fetch drafts", "drafts → n-gram cache", "~3 ms"),
    ("ai", "Prefill ≤ 512 tok", "llama.cpp · base + LoRA", "~1–2 s"),
    ("ai", "Decode + verify", "speculative lookup", "12 → 25+ tok/s"),
    ("store", "Confidence + log", "citations · op-log append", "< 5 ms"),
]
for i, (k, t, tech, tm) in enumerate(steps):
    y = 150 + i * 90
    s.rect(55, y, 670, 72, fill=CARD, stroke=C[k], sw=1.4)
    s.badge(82, y + 36, i + 1, C[k])
    s.text(108, y + 31, t, 15, TEXT, 700)
    s.text(108, y + 53, tech, 11.5, C[k], 500, mono=True)
    s.chip(520, y + 22, 190, tm, C[k], 28, 12)
    if i < len(steps) - 1:
        s.arrow([(82, y + 72), (82, y + 88)], k, 1.6)
s.text(55, 1082, "* design targets for a mid-range 2023+ Android (8 GB RAM); validated in the Day-1 spike, not yet measured.", 11, MUTED, italic=True)

# ------------------------------------------------------------ B. inside llama.cpp
s.panel(780, 100, 900, 1000, "② Inside llama.cpp — how the model sits", "JNI · NDK · arm64", C["ai"])

# router feeding alphas
s.comp(810, 150, 400, 118, "Skill Router  (Qdrant Edge)", "search(skills, q, k=3)",
       ["s = [0.82 inverter-faults, 0.77 solar-wiring, 0.41 hvac]", "s₁ ≥ τ(0.6) and s₁ − s₂ < δ(0.1) ⇒ blend top-2",
        "α = softmax(s / T) → α₁ 0.62 · α₂ 0.38"], C["qdrant"], 13)
s.comp(1240, 150, 410, 118, "Adapter Store", "filesDir/skills/<sha256>.gguf",
       ["inverter-faults v3 · 8.8 MB · ✓ sha256", "solar-wiring v1 · 8.8 MB · ✓ sha256",
        "hvac v2 · evicted (LRU) → re-fetch on sync"], C["store"], 13)

# model stack
s.rect(810, 295, 840, 520, fill="#101826", stroke=C["ai"], sw=1.2, rx=14, dash="6 5")
s.text(830, 322, "Qwen2.5-1.5B-Instruct · Q4_K_M · 28 transformer blocks · weights mmap'd from flash, frozen", 13, C["ai"], 700)
s.rect(830, 340, 800, 40, fill=CARD, stroke=C["ai"], sw=1.2)
s.text(1230, 366, "token embeddings  +  prompt = [system · skill card · retrieved memory (cited) · question]", 12.5, TEXT, 600, "middle")
# one block expanded
s.rect(830, 398, 800, 300, fill=CARD, stroke=C["ai"], sw=1.4)
s.text(848, 422, "Transformer block  (× 28)", 14, TEXT, 700)
s.text(1612, 422, "what LoRA touches", 11.5, MUTED, anchor="end")
for i, w in enumerate(["W_q", "W_k", "W_v", "W_o"]):
    x = 850 + i * 190
    s.rect(x, 440, 110, 56, fill="#1C2433", stroke="#475569", sw=1.2, rx=8)
    s.text(x + 55, 466, w, 15, TEXT, 700, "middle")
    s.text(x + 55, 484, "frozen · 4-bit", 10.5, MUTED, 400, "middle")
    s.text(x + 120, 473, "+", 18, C["ai"], 800)
    s.rect(x + 135, 440, 42, 26, fill="#2A1F0A", stroke=C["ai"], sw=1.2, rx=6)
    s.text(x + 156, 458, "α₁BA", 10, C["ai"], 700, "middle")
    s.rect(x + 135, 470, 42, 26, fill="#2A1F0A", stroke=C["ai"], sw=1.2, rx=6)
    s.text(x + 156, 488, "α₂BA", 10, C["ai"], 700, "middle")
s.rect(850, 515, 760, 40, fill="#1C2433", stroke="#475569", sw=1.2, rx=8)
s.text(1230, 540, "Attention  (GQA · 12 q-heads / 2 kv-heads)  ⟷  KV cache", 12.5, TEXT, 600, "middle")
s.rect(850, 570, 760, 40, fill="#1C2433", stroke="#475569", sw=1.2, rx=8)
s.text(1230, 595, "MLP (SwiGLU) · frozen 4-bit — untouched by skills", 12.5, TEXT, 600, "middle")
s.text(848, 640, "W' = W + α₁·B₁A₁ + α₂·B₂A₂      (r = 16 · applied on the fly · no weight copy · swap = pointer change)", 12.5, C["ai"], 600, mono=True)
s.text(848, 666, "Router output becomes adapter scales → llama_set_adapter_lora(ctx, adapter, α) before each request.", 12, MUTED)
s.text(848, 686, "No adapter above τ ⇒ base model only + query logged to gaps (skill fetched next sync).", 12, MUTED)
s.arrow([(1010, 268), (1010, 438)], "qdrant", 1.8, "5 4")
s.arrow([(1445, 268), (1445, 438)], "store", 1.8, "5 4")

# decode + speculative
s.rect(830, 712, 800, 88, fill=CARD, stroke=C["ai"], sw=1.4)
s.text(848, 736, "Decode loop with retrieval-drafted speculation", 14, TEXT, 700)
toks = [("The", "ok"), ("E-21", "ok"), ("fault", "ok"), ("means", "ok"), ("DC", "ok"), ("isolator", "ok"), ("open", "no"), ("tripped", "new")]
for i, (t, st) in enumerate(toks):
    col = {"ok": C["store"], "no": C["qdrant"], "new": C["ai"]}[st]
    s.chip(848 + i * 96, 752, 88, t, col, 26, 11.5)
s.text(1620, 790, "green = draft accepted in 1 pass · red = rejected · amber = model's own token", 11, MUTED, anchor="end")

# kv + drafts side
s.comp(810, 835, 410, 115, "KV Cache", "n_ctx 4096 · f16 · ~115 MB",
       ["28 layers × 2 × 2 kv-heads × 128 × 2 B", "≈ 28 KB / token · context-shift on overflow",
        "reset per conversation; pinned system prefix"], C["ai"], 13)
s.comp(1240, 835, 410, 115, "Draft source", "Qdrant Edge · drafts shard",
       ["top-3 verified answers for this skill+query", "→ n-gram cache (common_ngram_cache)",
        "acceptance rate reported back → Draft Curator"], C["qdrant"], 13)
s.comp(810, 970, 840, 105, "Threads & scheduling", "llama.cpp threadpool · big cores only · mlock off",
       ["4 threads pinned to performance cores · generation on Dispatchers.Default-bound native thread",
        "embedder + Qdrant run on a separate coroutine pool so UI and search stay < 16 ms while generating"], C["ops"], 13)

# ------------------------------------------------------------ C. RAM budget
s.panel(1710, 100, 660, 1000, "③ Memory budget", "8 GB phone", C["store"])
bar_x, bar_y, bar_w, total_h = 1740, 150, 120, 900
segs = [  # (GB, label, key)
    (4.6, "Android OS + other apps", "ops"),
    (0.99, "Base weights (mmap, page-cache)", "ai"),
    (0.25, "Compute buffers", "ai"),
    (0.115, "KV cache 4k ctx", "ai"),
    (0.02, "2 × LoRA slots", "ai"),
    (0.2, "Qdrant Edge segments (mmap)", "qdrant"),
    (0.035, "bge-small int8", "ai"),
    (0.031, "whisper tiny.en q5_1", "ai"),
    (0.25, "App heap + Compose UI", "ui"),
    (1.5, "Headroom (OS kill safety)", "store"),
]
tot = sum(g for g, _, _ in segs)
y = bar_y
for g, lbl, k in segs:
    h = max(total_h * g / tot, 14)
    fill = "#1C2433" if k == "ops" else C[k]
    op = 0.35 if k in ("ops", "store") else 0.85
    s.rect(bar_x, y, bar_w, h - 2, fill=fill, stroke=C[k], sw=1, rx=4, opacity=op)
    s.line([(bar_x + bar_w + 4, y + h / 2), (bar_x + bar_w + 26, y + h / 2)], C[k], 1)
    s.text(bar_x + bar_w + 32, y + h / 2 + 4, f"{lbl}", 12.5, TEXT, 600)
    s.text(2350, y + h / 2 + 4, f"{g:.2f} GB" if g >= 0.1 else f"{g*1000:.0f} MB", 12, C[k], 600, "end", mono=True)
    y += h
s.text(1740, 1082, "Polymath working set ≈ 1.9 GB; OS reclaims mmap pages first.", 11, MUTED, italic=True)

# ------------------------------------------------------------ D. degradation ladder + flash
s.panel(30, 1125, 1650, 350, "④ Degradation ladder — the app always answers", "chosen by Resource Governor", C["ops"])
rungs = [
    ("FULL", "2 LoRA blend + speculative + 4k ctx", "normal"),
    ("LEAN", "no speculation · 2k ctx", "battery < 20 % or thermal ≥ MODERATE"),
    ("SINGLE", "1 adapter · 1k ctx · 2 threads", "thermal ≥ SEVERE or onTrimMemory(RUNNING_LOW)"),
    ("BASE", "base model only · no adapters", "adapter missing / hash fail / RAM critical"),
    ("RECALL", "no LLM · extractive answer from memory", "thermal CRITICAL · model file corrupt · OOM"),
]
for i, (n, what, when) in enumerate(rungs):
    x = 55 + i * 322
    y = 1175 + i * 34
    col = [C["store"], C["edge"], C["ai"], C["qdrant"], C["ops"]][i]
    s.rect(x, y, 300, 250 - i * 34, fill=CARD, stroke=col, sw=1.5)
    s.text(x + 16, y + 30, n, 18, col, 800)
    s.text(x + 16, y + 56, what, 12, TEXT, 600)
    for j, ln in enumerate(textwrap.wrap("when: " + when, 40)):
        s.text(x + 16, y + 80 + j * 17, ln, 11.5, MUTED)
    if i < 4:
        s.arrow([(x + 300, y + 20), (x + 320, y + 40)], "ops", 1.5)
s.text(55, 1462, "Every rung still writes to the op-log and still syncs — degraded answers carry a lower confidence badge.", 12, MUTED, italic=True)

s.panel(1710, 1125, 660, 350, "⑤ Flash storage", "internal app storage", C["store"])
fl = [("models/qwen2.5-1.5b-q4_k_m.gguf", "~1.0 GB"), ("skills/<sha256>.gguf × N", "~9 MB each"),
      ("qdrant/{memory,skills,drafts,gaps,atlas}/", "WAL + segments"), ("oplog.db (SQLite WAL)", "append-only"),
      ("models/bge-small-int8.onnx", "~34 MB"), ("models/ggml-tiny.en-q5_1.bin", "~31 MB"),
      ("keys/device.ed25519 (Android Keystore-wrapped)", "32 B")]
for i, (p, sz) in enumerate(fl):
    y = 1180 + i * 38
    s.text(1740, y, p, 12.5, TEXT, 500, mono=True)
    s.text(2350, y, sz, 12, C["store"], 600, "end")
s.text(1740, 1460, "Quota guard: consolidate cold memory + evict LRU skills.", 11, MUTED, italic=True)

out = os.path.join(os.path.dirname(__file__), "..", "on-device-runtime.svg")
with open(out, "w", encoding="utf-8") as f:
    f.write(s.render())
print("wrote", os.path.abspath(out))
