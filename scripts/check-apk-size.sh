#!/usr/bin/env bash
# Fail when the arm64-v8a release APK is more than 10% above, or less than half of, the baseline (#91).
# The lower bound catches an empty, truncated or wrong file.
# Usage: scripts/check-apk-size.sh <apk> [baseline-file]    scripts/check-apk-size.sh --self-test
# The baseline file holds one number: the target size in bytes (scripts/apk-size-baseline.txt).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

check() {
  local apk="$1" baseline_file="${2:-$here/apk-size-baseline.txt}"
  local size baseline limit floor
  size=$(wc -c < "$apk" | tr -d ' ')
  baseline=$(tr -d ' \n' < "$baseline_file")
  limit=$((baseline + baseline / 10))
  floor=$((baseline / 2))
  echo "apk=$size baseline=$baseline limit(+10%)=$limit floor=$floor"
  if [ "$size" -lt "$floor" ]; then
    echo "FAIL: $apk is smaller than half the baseline (empty or truncated?)" >&2
    return 1
  fi
  if [ "$size" -gt "$limit" ]; then
    echo "FAIL: $apk is $((size - limit)) bytes over the limit" >&2
    return 1
  fi
}

if [ "${1:-}" = "--self-test" ]; then
  d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
  echo 1000 > "$d/b"
  head -c 1100 /dev/zero > "$d/ok.apk"
  head -c 1101 /dev/zero > "$d/big.apk"
  : > "$d/empty.apk"
  head -c 499 /dev/zero > "$d/small.apk"
  check "$d/ok.apk" "$d/b" >/dev/null
  for bad in empty small; do
    if check "$d/$bad.apk" "$d/b" >/dev/null 2>&1; then echo "self-test FAILED: $bad APK accepted" >&2; exit 1; fi
  done
  if check "$d/big.apk" "$d/b" >/dev/null 2>&1; then echo "self-test FAILED: oversize APK accepted" >&2; exit 1; fi
  echo "self-test ok"
  exit 0
fi
[ $# -ge 1 ] || { echo "usage: $0 <apk> [baseline-file] | --self-test" >&2; exit 2; }
check "$@"
