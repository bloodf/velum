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
# ABI-split APKs (-Pvelum.abiSplits=true) and the AAB are separate invocations (#91).
with_test_key() {
  VELUM_UPLOAD_STORE_FILE="$check_dir/synthetic.jks" \
    VELUM_UPLOAD_STORE_PASSWORD=signing-check VELUM_UPLOAD_KEY_ALIAS=signing-check \
    VELUM_UPLOAD_KEY_PASSWORD=signing-check "$@"
}
for task in "${positive_tasks[@]}"; do
  props=()
  [[ "$task" == *assembleRelease ]] && props=(-Pvelum.abiSplits=true)
  if ! with_test_key ./gradlew "$task" ${props[@]+"${props[@]}"} --console=plain >"$check_dir/gradle.log" 2>&1; then
    echo "FAIL: explicit disposable release signing was rejected by $task"
    cat "$check_dir/gradle.log"
    exit 1
  fi
done
echo 'PASS: explicit disposable release signing accepted'

[[ "${1:-}" == --package ]] || exit 0

# Splits plus a bundle in one invocation must fail loudly, never fall back to universal.
if with_test_key ./gradlew :composeApp:bundleRelease -Pvelum.abiSplits=true --console=plain \
    >"$check_dir/gradle.log" 2>&1; then
  echo 'FAIL: -Pvelum.abiSplits=true was accepted together with bundleRelease'
  exit 1
fi
if ! grep -q 'cannot be combined with bundle tasks' "$check_dir/gradle.log"; then
  echo 'FAIL: splits+bundle failed for another reason; see Gradle output'
  cat "$check_dir/gradle.log"
  exit 1
fi
echo 'PASS: ABI splits rejected together with bundleRelease'

# Split outputs: universal + per-ABI APKs, versionCode = base*10 + {0,1,2}, 16 KB aligned.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[[ -x "${tools}aapt2" && -x "${tools}zipalign" ]] || { echo "FAIL: aapt2/zipalign not found under $sdk/build-tools"; exit 1; }
apk_dir=composeApp/build/outputs/apk/release
version_code() { "${tools}aapt2" dump badging "$1" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p"; }
native_code() { "${tools}aapt2" dump badging "$1" | sed -n "s/^native-code: //p"; }
for abi in universal arm64-v8a armeabi-v7a; do
  [[ -f "$apk_dir/composeApp-$abi-release.apk" ]] || { echo "FAIL: missing composeApp-$abi-release.apk"; ls "$apk_dir"; exit 1; }
done
universal_code=$(version_code "$apk_dir/composeApp-universal-release.apk")
(( universal_code % 10 == 0 )) || { echo "FAIL: universal versionCode $universal_code not base*10"; exit 1; }
for abi_digit in arm64-v8a:2 armeabi-v7a:1 universal:0; do
  abi=${abi_digit%%:*}
  apk="$apk_dir/composeApp-$abi-release.apk"
  code=$(version_code "$apk")
  [[ "$code" == $((universal_code + ${abi_digit##*:})) ]] || { echo "FAIL: $apk versionCode $code"; exit 1; }
  "${tools}zipalign" -c -P 16 4 "$apk" >/dev/null || {
    echo "FAIL: $apk not 16 KB aligned (zipalign from build-tools $(basename "$tools"); -P 16 needs 35+)"
    exit 1
  }
  echo "PASS: $(basename "$apk") versionCode=$code native-code=$(native_code "$apk")"
done
# Each split carries exactly its own ABI; the universal APK carries all four.
[[ "$(native_code "$apk_dir/composeApp-universal-release.apk")" == "'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'" ]] \
  || { echo 'FAIL: universal APK native-code is not arm64-v8a armeabi-v7a x86 x86_64'; exit 1; }
for abi in arm64-v8a armeabi-v7a; do
  [[ "$(native_code "$apk_dir/composeApp-$abi-release.apk")" == "'$abi'" ]] \
    || { echo "FAIL: composeApp-$abi-release.apk native-code is not only $abi"; exit 1; }
done

# Store metadata must track the universal versionCode.
for locale_dir in fastlane/metadata/android/*/; do
  [[ -f "${locale_dir}changelogs/$universal_code.txt" ]] \
    || { echo "FAIL: missing ${locale_dir}changelogs/$universal_code.txt"; exit 1; }
done
grep -q "versionCode: $universal_code\$" metadata/dev.velum.app.yml \
  || { echo "FAIL: metadata/dev.velum.app.yml versionCode is not $universal_code"; exit 1; }
echo 'PASS: release APK splits'

# F-Droid path: no signing variables plus the explicit property builds an unsigned universal APK.
env -u VELUM_UPLOAD_STORE_FILE -u VELUM_UPLOAD_STORE_PASSWORD -u VELUM_UPLOAD_KEY_ALIAS \
    -u VELUM_UPLOAD_KEY_PASSWORD ./gradlew :composeApp:assembleRelease -Pvelum.unsignedRelease=true \
    --console=plain >"$check_dir/gradle.log" 2>&1 || {
  echo 'FAIL: unsigned F-Droid release build was rejected'; cat "$check_dir/gradle.log"; exit 1; }
unsigned_apk="$apk_dir/composeApp-release-unsigned.apk"
[[ -f "$unsigned_apk" ]] || { echo "FAIL: missing $unsigned_apk"; ls "$apk_dir"; exit 1; }
[[ "$(version_code "$unsigned_apk")" == "$universal_code" ]] || { echo 'FAIL: unsigned APK versionCode differs from universal'; exit 1; }
# The property must never combine with real signing variables.
if with_test_key ./gradlew :composeApp:assembleRelease -Pvelum.unsignedRelease=true --console=plain \
    >"$check_dir/gradle.log" 2>&1; then
  echo 'FAIL: unsignedRelease accepted together with signing variables'; exit 1
fi
grep -q 'cannot be combined with VELUM_UPLOAD' "$check_dir/gradle.log" || {
  echo 'FAIL: unsignedRelease+signing failed for another reason'; cat "$check_dir/gradle.log"; exit 1; }
# F-Droid metadata and fastlane changelogs must follow the universal versionCode.
grep -q "^    versionCode: $universal_code\$" metadata/dev.velum.app.yml || { echo "FAIL: metadata versionCode != $universal_code"; exit 1; }
grep -q "^CurrentVersionCode: $universal_code\$" metadata/dev.velum.app.yml || { echo "FAIL: CurrentVersionCode != $universal_code"; exit 1; }
for lang_dir in fastlane/metadata/android/*/changelogs; do
  [[ -f "$lang_dir/$universal_code.txt" ]] || { echo "FAIL: missing $lang_dir/$universal_code.txt"; exit 1; }
done
echo 'PASS: unsigned F-Droid release path, metadata and changelogs match versionCode'
