#!/usr/bin/env bash
# Shallow-clones whisper.cpp at a pinned tag into native/whisper.cpp (gitignored, not committed —
# same pattern as native/qdrant and native/llama.cpp).
#
#   bash native/fetch-whisper-cpp.sh
set -euo pipefail

WHISPER_CPP_TAG="v1.9.4"
WHISPER_CPP_REV="927cfce34f31707e17f2bff35c349632fb9e2c3a"

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/whisper.cpp"

echo "==> whisper.cpp @ ${WHISPER_CPP_REV:0:12} ($WHISPER_CPP_TAG)"
if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin https://github.com/ggml-org/whisper.cpp.git
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$WHISPER_CPP_REV" ]; then
  git -C "$SRC" fetch -q --depth 1 origin "$WHISPER_CPP_REV"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
echo "==> Done: $SRC"
