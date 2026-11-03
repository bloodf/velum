#!/usr/bin/env bash
# Fail when the arm64-v8a release APK is more than 10% above, or less than half of, the baseline (#91).
# The lower bound catches an empty, truncated or wrong file.
# Usage: scripts/check-apk-size.sh <apk> [baseline-file]    scripts/check-apk-size.sh --self-test
# The baseline file holds one number: the target size in bytes (scripts/apk-size-baseline.txt).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

check() {
  local apk="$1" baseline_file="${2:-$here/apk-size-baseline.txt}"
  local size baseline limit floor libs
  baseline=$(cat "$baseline_file")
  [[ $baseline =~ ^[1-9][0-9]*$ ]] || { echo "FAIL: $baseline_file must hold exactly one positive integer" >&2; return 1; }
  # Only the arm64-v8a payload may be in the APK: a different ABI or the universal APK is the wrong file.
  libs=$(unzip -Z1 "$apk" 2>/dev/null | grep -E '^lib/[^/]+/' | cut -d/ -f2 | sort -u | tr '\n' ' ') \
    || { echo "FAIL: $apk is not a zip with native libs" >&2; return 1; }
  [ "$libs" = "arm64-v8a " ] || { echo "FAIL: $apk native ABIs are '${libs:-none}', expected only arm64-v8a" >&2; return 1; }
  size=$(wc -c < "$apk" | tr -d ' ')
  limit=$((baseline + baseline / 10))
  floor=$((baseline / 2))
  echo "apk=$size baseline=$baseline limit(+10%)=$limit floor=$floor"
  if [ "$size" -lt "$floor" ]; then
    echo "FAIL: $apk is smaller than half the baseline (truncated?)" >&2
    return 1
  fi
  if [ "$size" -gt "$limit" ]; then
    echo "FAIL: $apk is $((size - limit)) bytes over the limit" >&2
    return 1
  fi
}

if [ "${1:-}" = "--self-test" ]; then
  d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
  echo 1000000 > "$d/b"
  # mkapk <name> <payload bytes> <abi>: a stored zip with one native lib of that size.
  mkapk() { mkdir -p "$d/z/lib/$3"; head -c "$2" /dev/zero > "$d/z/lib/$3/libx.so"; (cd "$d/z" && zip -q0r "$d/$1.apk" lib); rm -rf "$d/z"; }
  mkapk ok 1050000 arm64-v8a
  mkapk big 1150000 arm64-v8a
  mkapk small 400000 arm64-v8a
  mkapk v7a 1050000 armeabi-v7a
  mkapk mixed 1050000 arm64-v8a; mkdir -p "$d/z/lib/x86"; head -c 10 /dev/zero > "$d/z/lib/x86/libx.so"; (cd "$d/z" && zip -q0r "$d/mixed.apk" lib); rm -rf "$d/z"
  : > "$d/empty.apk"
  printf '1000\n2000\n' > "$d/multi"; echo size > "$d/word"; : > "$d/blank"; echo 0 > "$d/zero"
  check "$d/ok.apk" "$d/b" >/dev/null || { echo "self-test FAILED: good APK rejected" >&2; exit 1; }
  for bad in big small v7a mixed empty; do
    if check "$d/$bad.apk" "$d/b" >/dev/null 2>&1; then echo "self-test FAILED: $bad APK accepted" >&2; exit 1; fi
  done
  for bad in multi word blank zero; do
    if check "$d/ok.apk" "$d/$bad" >/dev/null 2>&1; then echo "self-test FAILED: $bad baseline accepted" >&2; exit 1; fi
  done
  echo "self-test ok"
  exit 0
fi
[ $# -ge 1 ] || { echo "usage: $0 <apk> [baseline-file] | --self-test" >&2; exit 2; }
check "$@"
