#!/usr/bin/env bash
# Verifies the signed release outputs and copies them, renamed, into a release folder with checksums.
#   bash scripts/release-collect.sh <version> <out-dir>
# Run after `:composeApp:assembleRelease -Pvelum.abiSplits=true` and `:composeApp:bundleRelease`.
# Fails when an APK is unsigned, signed with the committed debug key, signed with different keys, signed with a key
# other than $EXPECTED_CERT_SHA256 (when set), or carries AGP's encrypted dependency-metadata block (store scanners
# such as IzzyOnDroid's flag it). VELUM_ROOT overrides the repository root (tests).
set -euo pipefail
root="${VELUM_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
version="${1:-}"
out="${2:-}"
[[ -n "$version" && -n "$out" ]] || { echo "usage: release-collect.sh <version> <out-dir>" >&2; exit 2; }
fail() { echo "release-collect: $*" >&2; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
apksigner="${APKSIGNER:-${tools}apksigner}"
[[ -x "$apksigner" ]] || fail "apksigner not found (set ANDROID_HOME or APKSIGNER)"

apk_dir="$root/composeApp/build/outputs/apk/release"
aab="$root/composeApp/build/outputs/bundle/release/composeApp-release.aab"
mkdir -p "$out"
rm -f "$out"/velum-* "$out"/SHA256SUMS "$out"/SIGNING-CERT.txt

normalize() { tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }
expected=$(printf '%s' "${EXPECTED_CERT_SHA256:-}" | normalize)

debug_cert=""
if [[ -f "$root/composeApp/debug.keystore" ]] && command -v keytool >/dev/null; then
  debug_cert=$(keytool -list -v -keystore "$root/composeApp/debug.keystore" -storepass android 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | normalize)
fi

cert=""
for abi in universal arm64-v8a armeabi-v7a; do
  src="$apk_dir/composeApp-$abi-release.apk"
  [[ -f "$src" ]] || fail "missing ${src#"$root"/} (build with -Pvelum.abiSplits=true)"
  report=$("$apksigner" verify --verbose --print-certs "$src" 2>&1) || fail "$(basename "$src") does not verify: $report"
  grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$report" \
    || fail "$(basename "$src") is not signed with APK Signature Scheme v2"
  this=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$report" | head -1 | normalize)
  [[ -n "$this" ]] || fail "no signer certificate in $(basename "$src")"
  (( $(grep -c '^Signer #[0-9]* certificate SHA-256 digest' <<<"$report") == 1 )) || fail "$(basename "$src") has more than one signer"
  [[ -z "$debug_cert" || "$this" != "$debug_cert" ]] || fail "$(basename "$src") is signed with the committed debug key"
  [[ -z "$cert" || "$this" == "$cert" ]] || fail "$(basename "$src") is signed with a different key than the others"
  cert="$this"
  python3 "$root/scripts/apk-signing-blocks.py" --forbid-dependency-metadata "$src" \
    || fail "$(basename "$src") carries the dependency-metadata signing block (set dependenciesInfo.includeInApk = false)"
  cp "$src" "$out/velum-$version-$abi.apk"
done
[[ -z "$expected" || "$cert" == "$expected" ]] \
  || fail "APKs are signed with $cert, not the release key $expected (VELUM_RELEASE_CERT_SHA256)"

if [[ -f "$aab" ]]; then
  jarsigner -verify "$aab" >/dev/null 2>&1 || fail "composeApp-release.aab is not signed"
  cp "$aab" "$out/velum-$version.aab"
else
  echo "release-collect: no App Bundle at ${aab#"$root"/}; continuing with APKs only" >&2
fi

printf 'SHA-256 of the Velum release signing certificate:\n%s\n' "$cert" > "$out/SIGNING-CERT.txt"
(cd "$out" && sha256sum velum-* SIGNING-CERT.txt > SHA256SUMS)
echo "release-collect: $(ls "$out" | tr '\n' ' ')"
