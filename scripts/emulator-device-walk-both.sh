#!/usr/bin/env bash
# Run debug then release device walks. Prefer this single entry from CI so
# android-emulator-runner's `sh -c` wrapper never sees a multi-line script.
#
# Always attempt both builds so a debug flake does not hide release R8 failures.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ARTIFACT_DIR="${1:-device-walk-artifacts}"
debug_code=0
release_code=0
bash scripts/emulator-device-walk.sh debug "$ARTIFACT_DIR" || debug_code=$?
bash scripts/emulator-device-walk.sh release "$ARTIFACT_DIR" || release_code=$?
if [[ "$debug_code" -ne 0 || "$release_code" -ne 0 ]]; then
  echo "Device walk failed (debug=$debug_code release=$release_code)"
  exit 1
fi
