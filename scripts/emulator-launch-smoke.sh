#!/usr/bin/env bash
# Install the debug APK on a booted emulator and assert cold start has no FATAL crash.
# Used by .github/workflows/ci.yml (must be bash — emulator-runner invokes via sh).
set -euo pipefail

APK_DIR="${1:-apk}"
APK="$(ls "$APK_DIR"/*.apk | head -1)"
echo "Installing $APK"
adb install -r "$APK"
# Fresh prefs + Keystore-bound ciphertext for this package (survives -r installs).
adb shell pm clear dev.velum.app >/dev/null 2>&1 || true
adb logcat -c
adb shell am force-stop dev.velum.app || true

START_OUT="$(adb shell am start -W -n dev.velum.app/.MainActivity 2>&1 || true)"
echo "$START_OUT"

# Give Application.attach / Activity a moment to crash if config is broken.
# Splash waits for IO AppContainer init — allow more than a few seconds on cold emulator.
sleep 20

PID="$(adb shell pidof -s dev.velum.app || true)"
echo "pid=$PID"

CRASH_LOG="$(adb logcat -d -b crash 2>/dev/null || true)"
FULL_LOG="$(adb logcat -d -t 400 2>/dev/null || true)"

if echo "$CRASH_LOG$FULL_LOG" | grep -E 'FATAL EXCEPTION|Nested domain-config|Unable to instantiate application|AndroidRuntime: FATAL'; then
  echo "==== crash buffer ===="
  echo "$CRASH_LOG"
  echo "==== recent logcat ===="
  echo "$FULL_LOG" | tail -200
  exit 1
fi

if [[ -z "$PID" ]]; then
  echo "App process not running after launch"
  echo "$START_OUT"
  echo "$FULL_LOG" | tail -200
  exit 1
fi

if echo "$START_OUT" | grep -qiE 'Error:|Exception'; then
  echo "am start failed: $START_OUT"
  exit 1
fi

echo "Launch smoke OK (pid=$PID)"
