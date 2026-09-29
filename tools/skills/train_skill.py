"""Trains one LoRA skill adapter on a curated slice of PolyCare's own knowledge base.

    tools/.venv/Scripts/python tools/skills/train_skill.py maternal-newborn
    tools/.venv/Scripts/python tools/skills/train_skill.py child-health

This is a genuinely trained adapter, not a placeholder: it runs real supervised fine-tuning
(PEFT LoRA) over real official-source passages, in the same ChatML format the phone prompts with
(PromptFormat.ask, android/core-llm). Given CPU-only training on a single dev machine, the run is
intentionally small (rank 4, ~40 examples, a few dozen steps) — enough to measurably nudge the
model's phrasing toward the skill's domain and prove the whole pipeline end-to-end (mine → train
→ convert → verify → load → hot-swap on a real phone). A properly resourced run (GPU, thousands
of examples, held-out eval) is the ARCHITECTURE.md Skill Factory job description, not this script;
that gap is tracked honestly in STATUS.md rather than hidden.

Inputs:
  tools/knowledge/out/report-<version>.json   passages (tools/knowledge/build_knowledge.py)
  tools/models/qwen2.5-1.5b-instruct-hf/      base model in HF format (config+tokenizer+safetensors)
Outputs:
  tools/skills/out/<skill>-adapter/           PEFT adapter (HF format)
  tools/skills/out/<skill>.gguf               converted for llama.cpp
  tools/skills/out/manifest.json              sha256 + size per skill, for the phone to verify
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer

ROOT = Path(__file__).resolve().parents[2]
KNOWLEDGE_REPORT = ROOT / "tools" / "knowledge" / "out" / "report-v2.json"
BASE_MODEL_DIR = ROOT / "tools" / "models" / "qwen2.5-0.5b-instruct-hf"
OUT = ROOT / "tools" / "skills" / "out"
CONVERT_SCRIPT = ROOT / "native" / "llama.cpp" / "convert_lora_to_gguf.py"

# Must equal LlmArtifacts.MODEL_ID (android/core-llm) — the manifest records which base model
# this adapter was trained against, and the phone refuses to load a mismatched pair.
BASE_MODEL_ID = "Qwen2.5-0.5B-Instruct/Q4_K_M@9217f5d"

SEED = 7
MAX_EXAMPLES = 48
MAX_SEQ_LEN = 320
LORA_RANK = 4
LORA_ALPHA = 8
TARGET_MODULES = ["q_proj", "v_proj"]
LEARNING_RATE = 1e-4
# 3 epochs over only 48 examples drove the loss to ~0.0001-0.0003 — the adapter had essentially
# memorised the 48 (prompt -> passage) pairs rather than learning the domain, and at inference on
# an unseen question it echoed back boilerplate from the prompt template instead of answering
# (found by comparing base-vs-skill output on a real phone — see WORKLOG). 1 epoch keeps the loss
# well above zero, which is what we actually want from a demonstration-scale nudge.
EPOCHS = 1

# Matches org.polycare.llm.PromptFormat.ASK_SYSTEM — training and inference use the same prompt,
# so the adapter nudges behaviour it will actually be asked for on the phone.
ASK_SYSTEM = (
    "You are a careful health-information assistant for ASHA (community health) workers in "
    "India, used offline on a phone. Answer ONLY using the passage given below — never use "
    "outside knowledge and never guess. If the passage does not answer the question, say so "
    "plainly and suggest referring to the ANM or PHC. Keep the answer to 2–4 short sentences, in "
    "the same language as the question. Never state a diagnosis; only explain the guidance in "
    "the passage."
)

SKILLS = {
    "maternal-newborn": {
        "title": "Maternal & Newborn Care",
        "sources": {"asha-module-6"},
        "prompt": "Summarize the key guidance in this passage on maternal and newborn care.",
        # Short skill-card description embedded once (multilingual-e5, same as the knowledge
        # base) and compared to each question's embedding for routing — ARCHITECTURE.md §5.1's
        # cosine-similarity routing, not a keyword list or a second model.
        "card": "Maternal and newborn care: pregnancy check-ups, danger signs in pregnancy, "
                "labour and delivery, breastfeeding, and care of a newborn baby.",
    },
    "child-health": {
        "title": "Child Health & Nutrition",
        "sources": {"asha-module-7"},
        "prompt": "Summarize the key guidance in this passage on child health and nutrition.",
        "card": "Child health and nutrition: growth monitoring, immunisation schedule, common "
                "childhood illness such as diarrhoea and fever, and feeding a young child.",
    },
}


def chat_ml(system: str, user: str, assistant: str) -> str:
    return (
        f"<|im_start|>system\n{system}<|im_end|>\n"
        f"<|im_start|>user\n{user}<|im_end|>\n"
        f"<|im_start|>assistant\n{assistant}<|im_end|>"
    )


def load_examples(skill_id: str) -> list[str]:
    spec = SKILLS[skill_id]
    passages = json.loads(KNOWLEDGE_REPORT.read_text(encoding="utf-8"))["passages"]
    candidates = [
        p for p in passages
        if p["source"] in spec["sources"] and p["quality"] == "prose" and len(p["text"].split()) >= 40
    ]
    random.Random(SEED).shuffle(candidates)
    chosen = candidates[:MAX_EXAMPLES]
    if len(chosen) < 10:
        raise SystemExit(f"only {len(chosen)} usable passages for {skill_id}; check {KNOWLEDGE_REPORT}")
    return [chat_ml(ASK_SYSTEM, f"Passage (from {spec['title']}):\n{p['text']}\n\nQuestion: {spec['prompt']}", p["text"]) for p in chosen]


@dataclass
class Example:
    input_ids: torch.Tensor
    labels: torch.Tensor


def tokenize_examples(texts: list[str], tokenizer) -> list[Example]:
    out = []
    for t in texts:
        ids = tokenizer(t, truncation=True, max_length=MAX_SEQ_LEN, return_tensors="pt")["input_ids"][0]
        # Train on the assistant's tokens only; the prompt (system+user) is masked out so the LoRA
        # learns to *produce* this style of answer, not to memorise the fixed instruction text.
        marker = tokenizer("<|im_start|>assistant\n", add_special_tokens=False)["input_ids"]
        start = None
        for i in range(len(ids) - len(marker) + 1):
            if ids[i:i + len(marker)].tolist() == marker:
                start = i + len(marker)
        labels = ids.clone()
        if start is not None:
            labels[:start] = -100
        out.append(Example(ids, labels))
    return out


def collate(batch: list[Example], pad_id: int):
    max_len = max(e.input_ids.size(0) for e in batch)
    input_ids = torch.full((len(batch), max_len), pad_id, dtype=torch.long)
    labels = torch.full((len(batch), max_len), -100, dtype=torch.long)
    attn = torch.zeros((len(batch), max_len), dtype=torch.long)
    for i, e in enumerate(batch):
        n = e.input_ids.size(0)
        input_ids[i, :n] = e.input_ids
        labels[i, :n] = e.labels
        attn[i, :n] = 1
    return input_ids, attn, labels


def train(skill_id: str) -> Path:
    spec = SKILLS[skill_id]
    torch.manual_seed(SEED)
    print(f"== {skill_id}: {spec['title']}", file=sys.stderr)

    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_DIR)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token

    texts = load_examples(skill_id)
    print(f"{len(texts)} training examples", file=sys.stderr)
    examples = tokenize_examples(texts, tokenizer)

    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL_DIR, torch_dtype=torch.bfloat16)
    model.config.use_cache = False
    lora_config = LoraConfig(
        r=LORA_RANK, lora_alpha=LORA_ALPHA, target_modules=TARGET_MODULES,
        lora_dropout=0.0, bias="none", task_type="CAUSAL_LM",
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()
    model.train()

    optim = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=LEARNING_RATE)
    rng = random.Random(SEED)
    step = 0
    for epoch in range(EPOCHS):
        order = list(range(len(examples)))
        rng.shuffle(order)
        for i in order:
            input_ids, attn, labels = collate([examples[i]], tokenizer.pad_token_id)
            out = model(input_ids=input_ids, attention_mask=attn, labels=labels)
            out.loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optim.step()
            optim.zero_grad()
            step += 1
            if step % 5 == 0 or step == 1:
                print(f"  step {step:3d} (epoch {epoch}) loss {out.loss.item():.4f}", file=sys.stderr)

    adapter_dir = OUT / f"{skill_id}-adapter"
    adapter_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(adapter_dir)
    print(f"saved adapter -> {adapter_dir}", file=sys.stderr)
    return adapter_dir


def convert_to_gguf(skill_id: str, adapter_dir: Path) -> Path:
    out_gguf = OUT / f"{skill_id}.gguf"
    cmd = [
        sys.executable, str(CONVERT_SCRIPT),
        "--base", str(BASE_MODEL_DIR),
        "--outfile", str(out_gguf),
        "--outtype", "f16",
        str(adapter_dir),
    ]
    print("==", " ".join(cmd), file=sys.stderr)
    subprocess.run(cmd, check=True, cwd=ROOT / "native" / "llama.cpp")
    return out_gguf


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def update_manifest(entries: dict[str, dict]) -> None:
    manifest_path = OUT / "manifest.json"
    manifest = {"baseModel": BASE_MODEL_ID, "skills": []}
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    by_id = {s["id"]: s for s in manifest["skills"]}
    by_id.update(entries)
    manifest["skills"] = sorted(by_id.values(), key=lambda s: s["id"])
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"wrote {manifest_path}", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("skill", choices=sorted(SKILLS))
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    adapter_dir = train(args.skill)
    gguf_path = convert_to_gguf(args.skill, adapter_dir)
    entry = {
        "id": args.skill,
        "title": SKILLS[args.skill]["title"],
        "card": SKILLS[args.skill]["card"],
        "file": gguf_path.name,
        "sha256": sha256(gguf_path),
        "sizeBytes": gguf_path.stat().st_size,
    }
    update_manifest({args.skill: entry})
    print(json.dumps(entry, indent=2))


if __name__ == "__main__":
    main()
