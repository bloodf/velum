#!/usr/bin/env bash
# Run debug then release device walks. Prefer this single entry from CI so
# android-emulator-runner's `sh -c` wrapper never sees a multi-line script.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ARTIFACT_DIR="${1:-device-walk-artifacts}"
bash scripts/emulator-device-walk.sh debug "$ARTIFACT_DIR"
bash scripts/emulator-device-walk.sh release "$ARTIFACT_DIR"
