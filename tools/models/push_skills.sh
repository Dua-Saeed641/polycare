#!/usr/bin/env bash
# Copies trained LoRA skill adapters to the connected phone (debug builds only).
#
#   bash tools/models/push_skills.sh
#
# Source: tools/skills/out/ (tools/skills/train_skill.py). Target: files/models/skills/ in the
# app's private storage, verified by sha256 (see SkillsRepository) before anything loads them.
set -euo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "$0")/../skills/out" && (pwd -W 2>/dev/null || pwd))"
PKG="org.polycare.app"
STAGE="/data/local/tmp/polycare"

if [ ! -f "$HERE/manifest.json" ]; then
  echo "no skills built yet — run tools/skills/train_skill.py first" >&2
  exit 1
fi

push() {
  local rel="$1"
  echo "push $rel"
  adb shell mkdir -p "$STAGE"
  adb push "$HERE/$rel" "$STAGE/$rel" >/dev/null
  adb shell chmod 644 "$STAGE/$rel"
  adb shell run-as "$PKG" mkdir -p "files/models/skills"
  adb shell run-as "$PKG" cp "$STAGE/$rel" "files/models/skills/$rel"
  adb shell rm "$STAGE/$rel"
}

push manifest.json
"${PYTHON:-python}" -c "
import json
m = json.load(open('$HERE/manifest.json', encoding='utf-8'))
for s in m['skills']:
    print(s['file'])
" | tr -d '\r' | while read -r f; do
  push "$f"
done

adb shell run-as "$PKG" ls -l files/models/skills
echo "done"
