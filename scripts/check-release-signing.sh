#!/usr/bin/env bash
# Regression check for #90: actual shipping tasks must reject absent/partial
# credentials and accept an explicit disposable test key. Never use real secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
positive_tasks=(:composeApp:validateSigningRelease)
case "${1:-}" in
  '') ;;
  --package) positive_tasks=(:composeApp:assembleRelease :composeApp:bundleRelease) ;;
  *) echo 'Usage: bash scripts/check-release-signing.sh [--package]'; exit 2 ;;
esac

expect_missing_signing() {
  local task="$1"
  shift
  if env -u VELUM_UPLOAD_STORE_FILE -u VELUM_UPLOAD_STORE_PASSWORD \
      -u VELUM_UPLOAD_KEY_ALIAS -u VELUM_UPLOAD_KEY_PASSWORD \
      "$@" ./gradlew "$task" --console=plain >"$check_dir/gradle.log" 2>&1; then
    echo "FAIL: $task accepted missing release signing"
    exit 1
  fi
  if ! grep -q 'Release signing is not configured' "$check_dir/gradle.log"; then
    echo "FAIL: $task failed before the release-signing guard; see Gradle output"
    cat "$check_dir/gradle.log"
    exit 1
  fi
  echo "PASS: $task rejected incomplete release signing"
}

expect_missing_signing :composeApp:assembleRelease
expect_missing_signing :composeApp:bundleRelease VELUM_UPLOAD_STORE_FILE="$check_dir/synthetic.jks"

# Use one disposable key for the blank-only and positive configurations, so the
# blank-password case cannot pass merely because other settings are absent.
keytool -genkeypair -keystore "$check_dir/synthetic.jks" -storetype JKS \
  -alias signing-check -storepass signing-check -keypass signing-check \
  -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Velum signing regression' \
  >"$check_dir/keytool.log" 2>&1
expect_missing_signing :composeApp:assembleRelease \
  VELUM_UPLOAD_STORE_FILE="$check_dir/synthetic.jks" \
  VELUM_UPLOAD_STORE_PASSWORD=' ' VELUM_UPLOAD_KEY_ALIAS=signing-check \
  VELUM_UPLOAD_KEY_PASSWORD=signing-check

# Verify the positive path without publishing or using the committed debug key.
# --package also checks shipping APK/AAB packaging and resource shrinking. Those
# disposable-key artifacts must never be distributed as production releases.
if ! VELUM_UPLOAD_STORE_FILE="$check_dir/synthetic.jks" \
    VELUM_UPLOAD_STORE_PASSWORD=signing-check VELUM_UPLOAD_KEY_ALIAS=signing-check \
    VELUM_UPLOAD_KEY_PASSWORD=signing-check \
    ./gradlew "${positive_tasks[@]}" --console=plain >"$check_dir/gradle.log" 2>&1; then
  echo 'FAIL: explicit disposable release signing was rejected'
  cat "$check_dir/gradle.log"
  exit 1
fi
echo 'PASS: explicit disposable release signing accepted'
