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

push multilingual-e5-small/model_quantized.onnx
push multilingual-e5-small/e5_tokenizer.bin
adb shell run-as "$PKG" ls -l files/models/multilingual-e5-small
echo "done"
