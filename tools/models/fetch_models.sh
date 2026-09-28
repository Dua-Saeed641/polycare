#!/usr/bin/env bash
# Downloads pinned on-device models into tools/models/ and verifies sha256.
#
#   bash tools/models/fetch_models.sh
#
# The same hashes are checked on the phone before anything is loaded (CLAUDE.md invariant 5).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# name|url|sha256
FILES=(
  "multilingual-e5-small/model_quantized.onnx|https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/onnx/model_quantized.onnx|f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
  "multilingual-e5-small/tokenizer.json|https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/tokenizer.json|0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
)

for entry in "${FILES[@]}"; do
  IFS='|' read -r name url sha <<<"$entry"
  path="$HERE/$name"
  mkdir -p "$(dirname "$path")"
  if [ -f "$path" ] && [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$sha" ]; then
    echo "ok       $name"
    continue
  fi
  echo "download $name"
  curl -sSfL -o "$path.part" "$url"
  actual="$(sha256sum "$path.part" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then
    echo "sha256 mismatch for $name: $actual" >&2
    rm -f "$path.part"
    exit 1
  fi
  mv "$path.part" "$path"
done

# Same ONNX Runtime version as the app (android/gradle/libs.versions.toml) so vectors match.
"${PYTHON:-python}" "$HERE/build_e5_assets.py"
