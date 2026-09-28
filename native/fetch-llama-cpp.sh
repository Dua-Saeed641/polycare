#!/usr/bin/env bash
# Shallow-clones llama.cpp at a pinned tag into native/llama.cpp (gitignored, not committed —
# same pattern as native/qdrant, see native/build-qdrant-edge.sh).
#
#   bash native/fetch-llama-cpp.sh
set -euo pipefail

LLAMA_CPP_TAG="b6935"                 # release v0.5.0, commit d2e54583c7452353eb35d40431281f6ee984332f
LLAMA_CPP_REV="d2e54583c7452353eb35d40431281f6ee984332f"

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/llama.cpp"

echo "==> llama.cpp @ ${LLAMA_CPP_REV:0:12} ($LLAMA_CPP_TAG)"
if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin https://github.com/ggml-org/llama.cpp.git
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$LLAMA_CPP_REV" ]; then
  git -C "$SRC" fetch -q --depth 1 origin "$LLAMA_CPP_REV"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
echo "==> Done: $SRC"
