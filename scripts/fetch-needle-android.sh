#!/usr/bin/env bash
# Download Apache-2.0 Needle static libs (Cactus-Compute/needle3) for rebuilding
# libvelum_needle.so via ai/src/androidMain/cpp/CMakeLists.txt.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/ai/src/androidMain/cpp/prebuilt"
BASE="https://huggingface.co/Cactus-Compute/needle3/resolve/main"
mkdir -p "$DEST/arm64-v8a" "$DEST/armeabi-v7a"
curl -fsSL -o "$DEST/arm64-v8a/libneedle.a" "$BASE/android-arm64/libneedle.a"
curl -fsSL -o "$DEST/armeabi-v7a/libneedle.a" "$BASE/android-armv7/libneedle.a"
curl -fsSL -o "$ROOT/ai/src/androidMain/cpp/needle.h" "$BASE/android-arm64/needle.h"
echo "Needle prebuilts ready under $DEST"
