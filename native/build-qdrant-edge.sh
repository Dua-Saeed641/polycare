#!/usr/bin/env bash
# Builds Qdrant Edge (upstream `qdrant-edge-ffi`) for Android arm64 and generates its
# UniFFI Kotlin bindings into the :qdrant-edge Gradle module.
#
#   bash native/build-qdrant-edge.sh
#
# Needs: rustup (stable >= 1.98, target aarch64-linux-android), cargo-ndk, Android NDK.
# Output: android/qdrant-edge/src/main/jniLibs/arm64-v8a/libqdrant_edge_ffi.so (not committed)
#         android/qdrant-edge/src/main/kotlin/tech/qdrant/edge/ffi/*.kt (generated, committed)
set -euo pipefail

# Pinned upstream commit. Bump deliberately and re-run.
QDRANT_REV="934c22441b71fdb93eb1e1285ef4122eccb96b3c"
ANDROID_API=29
NDK_VERSION="27.1.12297006"
PROFILE="release-mobile"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SRC="$HERE/qdrant"
MODULE="$ROOT/android/qdrant-edge"

SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
export PATH="$HOME/.cargo/bin:$PATH"
# Windows host (x86_64-pc-windows-gnu): build scripts need a full 64-bit MinGW-w64
# (dlltool + as + gcc). Use WinLibs (`winget install BrechtSanders.WinLibs.POSIX.UCRT`)
# and drop the old 32-bit C:\MinGW, which shadows it and breaks the link.
if command -v cygpath >/dev/null; then
  PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vix '/c/MinGW/bin' | paste -sd: -)"
  WINLIBS="$(ls -d "$(cygpath -u "${LOCALAPPDATA}")"/Microsoft/WinGet/Packages/BrechtSanders.WinLibs.*/mingw64/bin 2>/dev/null | head -1 || true)"
  if [ -z "$WINLIBS" ]; then
    echo "MinGW-w64 not found. Install it: winget install BrechtSanders.WinLibs.POSIX.UCRT" >&2
    exit 1
  fi
  PATH="$WINLIBS:$PATH"
fi
export PATH

echo "==> Qdrant source @ ${QDRANT_REV:0:12}"
if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin https://github.com/qdrant/qdrant.git
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$QDRANT_REV" ]; then
  git -C "$SRC" fetch -q --depth 1 origin "$QDRANT_REV"
  git -C "$SRC" checkout -q FETCH_HEAD
fi

cd "$SRC"

echo "==> Building libqdrant_edge_ffi.so (arm64-v8a, API $ANDROID_API, profile $PROFILE)"
# --no-default-features drops `search_matrix`, as upstream does for the mobile SDKs.
# Keep symbols: uniffi-bindgen reads its metadata from them. The Android Gradle plugin
# strips the library when it packages the APK, so the app ships a stripped .so anyway.
export CARGO_PROFILE_RELEASE_MOBILE_STRIP=none
cargo ndk -t arm64-v8a -P "$ANDROID_API" -o "$MODULE/src/main/jniLibs" \
  build -p qdrant-edge-ffi --no-default-features --profile "$PROFILE"

LIB="$SRC/target/aarch64-linux-android/$PROFILE/libqdrant_edge_ffi.so"
# cargo-ndk also copies dependency cdylibs (e.g. crc_fast); the FFI library links them
# statically and only needs libc/libm/libdl, so drop the extras.
find "$MODULE/src/main/jniLibs" -name '*.so' ! -name 'libqdrant_edge_ffi.so' -delete

echo "==> Generating Kotlin bindings"
rm -rf "$MODULE/src/main/kotlin/tech/qdrant/edge/ffi"
cargo run -q -p qdrant-edge-ffi-bindgen --bin uniffi-bindgen -- \
  generate --library "$LIB" --language kotlin --no-format \
  --out-dir "$MODULE/src/main/kotlin"

echo "==> Done"
ls -la "$MODULE/src/main/jniLibs/arm64-v8a"
find "$MODULE/src/main/kotlin" -name '*.kt'
