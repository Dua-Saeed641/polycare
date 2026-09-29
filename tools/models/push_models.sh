#!/usr/bin/env bash
# Copies verified on-device models to the connected phone (debug builds only).
#
#   bash tools/models/push_models.sh
#
# Files are staged in /data/local/tmp and copied *as the app* (run-as) into its private
# internal storage: files/models/. Folders adb creates under Android/data belong to the shell
# user and the app cannot read them, so that location is not used.
# The app re-checks every file's sha256 before loading it.
set -euo pipefail
# Keep Git Bash on Windows from rewriting /data/... into a Windows path.
export MSYS_NO_PATHCONV=1

# Native path for adb.exe on Windows (pwd -W), plain pwd elsewhere.
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
PKG="org.polycare.app"
STAGE="/data/local/tmp/polycare"

push() {
  local rel="$1"
  echo "push $rel"
  adb shell mkdir -p "$STAGE/$(dirname "$rel")"
  adb push "$HERE/$rel" "$STAGE/$rel" >/dev/null
  adb shell chmod 644 "$STAGE/$rel"
  adb shell run-as "$PKG" mkdir -p "files/models/$(dirname "$rel")"
  adb shell run-as "$PKG" cp "$STAGE/$rel" "files/models/$rel"
  adb shell rm "$STAGE/$rel"
}

# Only pushed when present (fetch_models.sh) and not already on the phone at the same size —
# for the ~1 GB LLM this is the difference between a few seconds and half a minute over USB.
push_if_present() {
  local rel="$1"
  [ -f "$HERE/$rel" ] || return 0
  local local_size remote_size
  local_size="$(stat -c%s "$HERE/$rel" 2>/dev/null || stat -f%z "$HERE/$rel")"
  remote_size="$(adb shell run-as "$PKG" stat -c%s "files/models/$rel" 2>/dev/null | tr -d '\r')" || remote_size=""
  if [ "$remote_size" = "$local_size" ]; then
    echo "ok (unchanged) $rel"
  else
    push "$rel"
  fi
}

push multilingual-e5-small/model_quantized.onnx
push multilingual-e5-small/e5_tokenizer.bin
adb shell run-as "$PKG" ls -l files/models/multilingual-e5-small

push_if_present qwen2.5-0.5b-instruct/qwen2.5-0.5b-instruct-q4_k_m.gguf
adb shell run-as "$PKG" ls -l "files/models/qwen2.5-0.5b-instruct" 2>/dev/null || true

push_if_present whisper/ggml-small-q5_1.bin
adb shell run-as "$PKG" ls -l "files/models/whisper" 2>/dev/null || true

echo "done"
