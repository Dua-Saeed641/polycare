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
  # 1.5B kept for reference/comparison; the app now ships 0.5B by default (see LlmArtifacts.kt)
  "qwen2.5-1.5b-instruct/qwen2.5-1.5b-instruct-q4_k_m.gguf|https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf|6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
  "qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf|https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf|74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
  "whisper/ggml-base-q5_1.bin|https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base-q5_1.bin|422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
  # "small" replaced "base" as the shipped voice model: base's Hindi transcription was tested
  # (tools/... on-device TTS round-trip, see WORKLOG) and came back as wrong-script garbage, not
  # just imperfect. base.bin is kept above for the size/RAM comparison, not deleted.
  "whisper/ggml-small-q5_1.bin|https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin|ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
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
