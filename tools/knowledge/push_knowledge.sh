#!/usr/bin/env bash
# Copies the built knowledge package to the connected phone (debug builds only).
#
#   bash tools/knowledge/push_knowledge.sh [version]     # default: newest build in out/
#
# The app installs it on next open: verifies sha256 + embedding model, unpacks, switches over.
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
LATEST="$(ls "$(dirname "$0")"/out/knowledge-v*.json 2>/dev/null | sed -E 's/.*knowledge-(v[0-9]+)\.json/\1/' | sort -V | tail -1)"
VERSION="${1:-$LATEST}"
PKG="org.polycare.app"
STAGE="/data/local/tmp/polycare"

adb shell mkdir -p "$STAGE"
adb shell run-as "$PKG" mkdir -p files/knowledge/incoming
# Zip first, manifest last: the app only acts once the manifest is present.
for f in "knowledge-$VERSION.zip" "knowledge-$VERSION.json"; do
  echo "push $f"
  adb push "$HERE/out/$f" "$STAGE/$f" >/dev/null
  adb shell chmod 644 "$STAGE/$f"
  adb shell run-as "$PKG" cp "$STAGE/$f" "files/knowledge/incoming/$f"
  adb shell rm "$STAGE/$f"
done
echo "done — open the app (or search) to install"
