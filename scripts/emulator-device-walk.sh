#!/usr/bin/env bash
# Run Compose/UIAutomator device walk on a booted emulator (debug or minifiedTest).
# Used by .github/workflows/ci.yml — must be bash (emulator-runner wraps sh -c).
set -euo pipefail

BUILD_TYPE="${1:-debug}" # debug | release | minifiedTest | minifiedWalk | r8
ARTIFACT_DIR="${2:-device-walk-artifacts}"
TEST_SCOPE="${3:-walk}"
mkdir -p "$ARTIFACT_DIR"

echo "=== Device walk ($BUILD_TYPE) ==="
adb logcat -c || true
# Timeout dumps (#160) are many 1 KB lines; a bigger ring buffer keeps them until the final logcat -d.
adb logcat -G 16M || true

GRADLE_PROPS=()
case "$BUILD_TYPE" in
  debug)
    TASK=":composeApp:connectedDebugAndroidTest"
    ;;
  release|minifiedTest|minifiedWalk|r8)
    # Genuinely R8-minified + obfuscated APK via android.testBuildType=minifiedTest (#106).
    # "release" / "minifiedWalk" / "r8" aliases kept for older CI / local invocations.
    TASK=":composeApp:connectedMinifiedTestAndroidTest"
    GRADLE_PROPS+=(-PvelumTestBuildType=minifiedTest)
    BUILD_TYPE="minifiedTest"
    ;;
  *)
    echo "Unknown build type: $BUILD_TYPE"
    exit 1
    ;;
esac

case "$TEST_SCOPE" in
  walk) ;;
  discover-catalog)
    # Run the real bundled catalog instead of the device-walk fixture.
    # CLI runner overrides need configuration caching disabled.
    GRADLE_PROPS+=(--no-configuration-cache
      -Pandroid.testInstrumentationRunnerArguments.class=dev.velum.app.ui.templates.DiscoverBundledCatalogDeviceTest
      -Pandroid.testInstrumentationRunnerArguments.velumDeviceWalk=false)
    ;;
  *)
    echo "Unknown test scope: $TEST_SCOPE"
    exit 1
    ;;
esac

# Isolate catalog results so an earlier variant cannot satisfy the gate.
RESULTS_DIR="composeApp/build/outputs/androidTest-results"
if [ "$TEST_SCOPE" = discover-catalog ] && [ -d "$RESULTS_DIR" ]; then
  mkdir -p "$ARTIFACT_DIR/prior-test-results-${BUILD_TYPE}"
  cp -R "$RESULTS_DIR/." "$ARTIFACT_DIR/prior-test-results-${BUILD_TYPE}/"
  rm -rf "$RESULTS_DIR"
fi

set +e
# Default runner arguments come from the DSL; catalog runs override them above.
./gradlew "${GRADLE_PROPS[@]}" "$TASK" --stacktrace
CODE=$?
set -e

if [ "$TEST_SCOPE" = discover-catalog ] && [ "$CODE" -eq 0 ]; then
  if ! python3 - "$RESULTS_DIR" <<'PY_CATALOG'
import pathlib
import sys
import xml.etree.ElementTree as ET

expected = {
    "count_matcher_accepts_current_english_labels_only",
    "bundled_catalog_tiers_filters_search_and_clear",
}
seen = set()
for path in pathlib.Path(sys.argv[1]).rglob("*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if case.get("classname") != "dev.velum.app.ui.templates.DiscoverBundledCatalogDeviceTest":
            continue
        if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
            raise SystemExit("Catalog gate contains a skipped or failed test: " + str(case.get("name")))
        seen.add(case.get("name"))
missing = expected - seen
if missing:
    raise SystemExit("Catalog gate did not execute required tests: " + ", ".join(sorted(missing)))
print("Catalog gate: required tests executed successfully")
PY_CATALOG
  then
    CODE=1
  fi
fi

echo "=== Collecting logcat + screenshots ==="
adb logcat -d -v threadtime > "$ARTIFACT_DIR/logcat-${BUILD_TYPE}.txt" || true
adb shell "logcat -d -b crash" > "$ARTIFACT_DIR/logcat-crash-${BUILD_TYPE}.txt" || true

# Screenshots land in app external files (no root required on API 35 emulator).
REMOTE_SHOTS="/sdcard/Android/data/dev.velum.app/files/Pictures/device-walk"
mkdir -p "$ARTIFACT_DIR/screenshots-${BUILD_TYPE}"
adb pull "$REMOTE_SHOTS" "$ARTIFACT_DIR/screenshots-${BUILD_TYPE}/" 2>/dev/null || \
  echo "No screenshots at $REMOTE_SHOTS (tests may have failed before capture)"

# Also pull orchestrator / test results if present
if [ -d composeApp/build/outputs/androidTest-results ]; then
  mkdir -p "$ARTIFACT_DIR/test-results-${BUILD_TYPE}"
  cp -R composeApp/build/outputs/androidTest-results/. "$ARTIFACT_DIR/test-results-${BUILD_TYPE}/" || true
fi
if [ -d composeApp/build/reports/androidTests ]; then
  mkdir -p "$ARTIFACT_DIR/reports-${BUILD_TYPE}"
  cp -R composeApp/build/reports/androidTests/. "$ARTIFACT_DIR/reports-${BUILD_TYPE}/" || true
fi
# Timeout dumps written to additionalTestOutputDir; AGP pulls these before uninstalling (#160).
if [ -d composeApp/build/outputs/connected_android_test_additional_output ]; then
  mkdir -p "$ARTIFACT_DIR/additional-output-${BUILD_TYPE}"
  cp -R composeApp/build/outputs/connected_android_test_additional_output/. \
    "$ARTIFACT_DIR/additional-output-${BUILD_TYPE}/" || true
fi

if grep -E 'FATAL EXCEPTION|StrictMode|AndroidRuntime: FATAL' "$ARTIFACT_DIR/logcat-${BUILD_TYPE}.txt" \
  "$ARTIFACT_DIR/logcat-crash-${BUILD_TYPE}.txt" 2>/dev/null | head -50; then
  echo "NOTE: crash/StrictMode lines present in logcat (see artifacts)"
fi

exit "$CODE"
