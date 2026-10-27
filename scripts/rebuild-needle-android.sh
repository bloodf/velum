#!/usr/bin/env bash
# Rebuild libvelum_needle.so (arm64-v8a + armeabi-v7a) with 16 KB ELF alignment.
# Requires: Android NDK r28+, cmake, scripts/fetch-needle-android.sh prebuilts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP="$ROOT/ai/src/androidMain/cpp"
JNI="$ROOT/ai/src/androidMain/jniLibs"
API="${ANDROID_API:-26}"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for cand in \
    "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/"* \
    "$HOME/Android/Sdk/ndk/"*; do
    if [ -d "$cand" ]; then
      ANDROID_NDK_HOME="$cand"
      break
    fi
  done
fi
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an NDK r28+ install}"

if [ ! -f "$CPP/prebuilt/arm64-v8a/libneedle.a" ]; then
  bash "$ROOT/scripts/fetch-needle-android.sh"
fi

build_abi() {
  local abi="$1"
  local build="/tmp/velum-needle-${abi}"
  rm -rf "$build"
  cmake -S "$CPP" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$build" -j"$(nproc)"
  mkdir -p "$JNI/$abi"
  cp -f "$build/libvelum_needle.so" "$JNI/$abi/libvelum_needle.so"
  echo "Built $JNI/$abi/libvelum_needle.so"
  llvm-readelf -lW "$JNI/$abi/libvelum_needle.so" 2>/dev/null \
    || readelf -lW "$JNI/$abi/libvelum_needle.so" \
    || true
}

build_abi arm64-v8a
build_abi armeabi-v7a
echo "Done. Commit updated jniLibs/*.so (keep existing libc++_shared.so)."
