#!/usr/bin/env bash
# Regenerate SVGs from the Python sources and rasterise them to PNG with headless Chrome.
set -euo pipefail
cd "$(dirname "$0")"
CHROME="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
render() {  # name width height
  python "src/${1//-/_}.py"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$2,$3" --screenshot="$(cygpath -w "$PWD/$1.png")" \
    "file:///$(cygpath -m "$PWD")/$1.svg"
}
render system-architecture 2600 1760
render on-device-runtime 2400 1500
