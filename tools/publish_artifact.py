"""Signs an artifact and adds it to a gateway's artifact directory.

    python tools/publish_artifact.py keygen  --key publisher.key            # once; prints the public key
    python tools/publish_artifact.py skill   --key publisher.key --dir cloud/data/artifacts \\
        --file tools/skills/out/maternal-newborn.gguf --name maternal-newborn --version 2 \\
        --title "Maternal and newborn health" --card "pregnancy, delivery, newborn care" \\
        --base-model "Qwen2.5-0.5B-Instruct/Q4_K_M@9217f5d"
    python tools/publish_artifact.py knowledge --key publisher.key --dir cloud/data/artifacts \\
        --file tools/knowledge/out/knowledge-v3.zip --manifest tools/knowledge/out/manifest-v3.json

The signature is Ed25519 over the canonical JSON (sorted keys, compact separators) of
`{kind, name, version, sha256, size}`. Paste the printed public key into the app (Sync -> Updates ->
Publisher key). The gateway holds no private key, so it cannot forge an update.

Needs `cryptography` (already a gateway dependency).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def load_key(path: Path) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(base64.b64decode(path.read_text().strip()))


def public_b64(key: Ed25519PrivateKey) -> str:
    raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return base64.b64encode(raw).decode()


def keygen(path: Path) -> None:
    if path.exists():
        sys.exit(f"{path} already exists; refusing to overwrite a signing key")
    key = Ed25519PrivateKey.generate()
    raw = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    path.write_text(base64.b64encode(raw).decode() + "\n")
    print("Private key written to", path, "(keep it offline, never commit it)")
    print("Public key (paste into the app):", public_b64(key))


def publish(args: argparse.Namespace) -> None:
    key = load_key(Path(args.key))
    src = Path(args.file)
    data = src.read_bytes()
    out = Path(args.dir)
    out.mkdir(parents=True, exist_ok=True)
    entry = {
        "kind": args.command,
        "name": args.name if args.command == "skill" else json.loads(Path(args.manifest).read_text())["version"],
        "version": str(args.version) if args.command == "skill" else json.loads(Path(args.manifest).read_text())["version"],
        "file": src.name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    if args.command == "skill":
        entry.update({"title": args.title, "card": args.card, "base_model": args.base_model})
    else:
        entry["manifest"] = json.loads(Path(args.manifest).read_text())
        entry["name"] = "knowledge"
    signed = {k: entry[k] for k in ("kind", "name", "version", "sha256", "size")}
    entry["signature"] = base64.b64encode(key.sign(canonical(signed))).decode()

    shutil.copyfile(src, out / src.name)
    index_path = out / "index.json"
    index = json.loads(index_path.read_text()) if index_path.exists() else []
    index = [e for e in index if not (e["name"] == entry["name"] and e["version"] == entry["version"])] + [entry]
    index_path.write_text(json.dumps(index, indent=2))
    print(f"Published {entry['kind']} {entry['name']} v{entry['version']} ({entry['size']} bytes) to {out}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    k = sub.add_parser("keygen")
    k.add_argument("--key", required=True)
    for kind in ("skill", "knowledge"):
        p = sub.add_parser(kind)
        p.add_argument("--key", required=True)
        p.add_argument("--dir", required=True)
        p.add_argument("--file", required=True)
        if kind == "skill":
            p.add_argument("--name", required=True)
            p.add_argument("--version", required=True)
            p.add_argument("--title", required=True)
            p.add_argument("--card", required=True)
            p.add_argument("--base-model", required=True, dest="base_model")
        else:
            p.add_argument("--manifest", required=True)
    args = ap.parse_args()
    keygen(Path(args.key)) if args.command == "keygen" else publish(args)


if __name__ == "__main__":
    main()
