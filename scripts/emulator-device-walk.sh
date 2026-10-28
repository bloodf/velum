#!/usr/bin/env bash
# Run Compose/UIAutomator device walk on a booted emulator (debug or release).
# Used by .github/workflows/ci.yml — must be bash (emulator-runner wraps sh -c).
#
# DO NOT add minifiedWalk / R8 instrumented legs here — keep-whack never
# converged; shipping R8 is covered by assembleRelease in jvm-android.
set -euo pipefail

BUILD_TYPE="${1:-debug}" # debug | release
ARTIFACT_DIR="${2:-device-walk-artifacts}"
mkdir -p "$ARTIFACT_DIR"

echo "=== Device walk ($BUILD_TYPE) ==="
adb logcat -c || true

GRADLE_PROPS=()
case "$BUILD_TYPE" in
  debug)
    TASK=":composeApp:connectedDebugAndroidTest"
    ;;
  release)
    # Creates connectedReleaseAndroidTest via android.testBuildType=release.
    # Minify is OFF for this instrumented leg (see composeApp/build.gradle.kts).
    TASK=":composeApp:connectedReleaseAndroidTest"
    GRADLE_PROPS+=(-PvelumTestBuildType=release)
    ;;
  *)
    echo "Unknown build type: $BUILD_TYPE (expected debug|release)"
    exit 1
    ;;
esac

set +e
# Runner args (class filter + velumDeviceWalk) come from composeApp/build.gradle.kts DSL.
./gradlew "${GRADLE_PROPS[@]}" "$TASK" --stacktrace
CODE=$?
set -e

echo "=== Collecting logcat + screenshots ==="
adb logcat -d -v threadtime > "$ARTIFACT_DIR/logcat-${BUILD_TYPE}.txt" || true
adb shell "logcat -d -b crash" > "$ARTIFACT_DIR/logcat-crash-${BUILD_TYPE}.txt" || true

# Screenshots land in app external files (no root required on API 35 emulator).
REMOTE_SHOTS="/sdcard/Android/data/dev.velum.app/files/Pictures/device-walk"
mkdir -p "$ARTIFACT_DIR/screenshots-${BUILD_TYPE}"
adb pull "$REMOTE_SHOTS" "$ARTIFACT_DIR/screenshots-${BUILD_TYPE}/" 2>/dev/null || \
  echo "No screenshots at $REMOTE_SHOTS (tests may have failed before capture)"

# Also pull orchestrator / test results if present
if ls composeApp/build/outputs/androidTest-results/**/*.xml >/dev/null 2>&1; then
  mkdir -p "$ARTIFACT_DIR/test-results-${BUILD_TYPE}"
  cp -R composeApp/build/outputs/androidTest-results/. "$ARTIFACT_DIR/test-results-${BUILD_TYPE}/" || true
fi
if ls composeApp/build/reports/androidTests/**/* >/dev/null 2>&1; then
  mkdir -p "$ARTIFACT_DIR/reports-${BUILD_TYPE}"
  cp -R composeApp/build/reports/androidTests/. "$ARTIFACT_DIR/reports-${BUILD_TYPE}/" || true
fi

if grep -E 'FATAL EXCEPTION|StrictMode|AndroidRuntime: FATAL' "$ARTIFACT_DIR/logcat-${BUILD_TYPE}.txt" \
  "$ARTIFACT_DIR/logcat-crash-${BUILD_TYPE}.txt" 2>/dev/null | head -50; then
  echo "NOTE: crash/StrictMode lines present in logcat (see artifacts)"
fi

exit "$CODE"
