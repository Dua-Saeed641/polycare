"""Skill Factory (ARCHITECTURE.md §5.11): turn what the field has learned into a new LoRA skill and
publish it as a signed update phones can download.

    python cloud/skill-factory/factory.py \\
        --id dengue-fever --title "Dengue and fever" \\
        --card "Fever, rash, dengue warning signs and when to refer" \\
        --sources asha-module-7 \\
        --gateway https://gateway.example.org --supervisor-token "$POLYCARE_SUPERVISOR_TOKEN" \\
        --key publisher.key --artifact-dir cloud/data/artifacts --version 1

What it does, in order:
  1. Fetches the question/answer pairs supervisors wrote on the dashboard (`GET /v1/supervisor/qa`);
     `--keyword` keeps only the ones about this skill's topic.
  2. Writes a skill spec (knowledge sources + those answers as extra training examples).
  3. Trains the LoRA adapter and converts it to GGUF (`tools/skills/train_skill.py --spec ...`).
     This needs the ML environment (torch, peft, transformers) and the HF base model; it is the slow
     step and belongs on a machine with a GPU for anything beyond a demonstration run.
  4. Signs and publishes it (`tools/publish_artifact.py skill ...`) to the gateway's artifact directory.

Phones then see it under Sync -> Updates, verify its signature against the pinned publisher key and
its sha256, and install it only if its base model matches the phone's.

Only the standard library is used here; the heavy lifting is delegated to the two tools above.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYTHON = sys.executable


def fetch_qa(gateway: str, token: str) -> list[dict]:
    req = urllib.request.Request(f"{gateway.rstrip('/')}/v1/supervisor/qa", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 - operator-supplied gateway URL
        return json.loads(resp.read())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id", required=True, help="skill id, e.g. dengue-fever")
    ap.add_argument("--title", required=True)
    ap.add_argument("--card", required=True, help="one-sentence description used to route questions to this skill")
    ap.add_argument("--sources", nargs="+", required=True, help="knowledge source ids to train on (see tools/knowledge/sources.json)")
    ap.add_argument("--prompt", default=None, help="instruction used with knowledge passages")
    ap.add_argument("--keyword", nargs="*", default=[], help="keep only supervisor answers mentioning one of these words")
    ap.add_argument("--gateway", required=True)
    ap.add_argument("--supervisor-token", required=True)
    ap.add_argument("--key", required=True, help="publisher signing key (tools/publish_artifact.py keygen)")
    ap.add_argument("--artifact-dir", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--dry-run", action="store_true", help="write the spec and stop (no training, no publishing)")
    args = ap.parse_args()

    qa = fetch_qa(args.gateway, args.supervisor_token)
    words = [w.lower() for w in args.keyword]
    if words:
        qa = [x for x in qa if any(w in (x["question"] + " " + x["answer"]).lower() for w in words)]
    print(f"{len(qa)} supervisor answers selected", file=sys.stderr)

    out = ROOT / "tools" / "skills" / "out"
    out.mkdir(parents=True, exist_ok=True)
    spec = {
        "id": args.id,
        "title": args.title,
        "card": args.card,
        "sources": args.sources,
        "prompt": args.prompt or f"Summarize the key guidance in this passage on {args.title.lower()}.",
        "extra_examples": [{"question": x["question"], "answer": x["answer"]} for x in qa],
    }
    spec_path = out / f"{args.id}.spec.json"
    spec_path.write_text(json.dumps(spec, indent=2, ensure_ascii=False), encoding="utf-8")
    print("spec written to", spec_path, file=sys.stderr)
    if args.dry_run:
        return

    subprocess.run([PYTHON, str(ROOT / "tools" / "skills" / "train_skill.py"), args.id, "--spec", str(spec_path)], check=True)

    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    subprocess.run(
        [
            PYTHON, str(ROOT / "tools" / "publish_artifact.py"), "skill",
            "--key", args.key, "--dir", args.artifact_dir,
            "--file", str(out / f"{args.id}.gguf"), "--name", args.id, "--version", args.version,
            "--title", args.title, "--card", args.card, "--base-model", manifest["baseModel"],
        ],
        check=True,
    )


if __name__ == "__main__":
    main()
