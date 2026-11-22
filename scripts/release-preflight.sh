#!/usr/bin/env bash
# Checks that a version tag can be released, then writes the GitHub Release notes.
#   bash scripts/release-preflight.sh <tag> <notes-out.md>
# Prints `version=<x>` and `prerelease=<true|false>` (GitHub step outputs) on stdout; problems go to stderr.
# Checks: tag is v<semver>, equals "v" + versionName, versionCode is a multiple of 10, every fastlane locale has
# changelogs/<versionCode>.txt (≤500 chars, the Play / F-Droid limit), and a final (non-prerelease) version matches
# the F-Droid metadata. VELUM_ROOT overrides the repository root (tests).
set -euo pipefail
root="${VELUM_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
tag="${1:-}"
notes="${2:-}"
[[ -n "$tag" && -n "$notes" ]] || { echo "usage: release-preflight.sh <tag> <notes-out.md>" >&2; exit 2; }

fail() { echo "release-preflight: $*" >&2; exit 1; }

[[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]] \
  || fail "tag '$tag' is not v<major>.<minor>.<patch>[-prerelease], e.g. v1.0.0 or v1.0.0-beta.1"
version="${tag#v}"
prerelease=false
[[ "$version" == *-* ]] && prerelease=true

gradle="$root/composeApp/build.gradle.kts"
[[ -f "$gradle" ]] || fail "missing $gradle"
version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$gradle" | head -1)
version_code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$gradle" | head -1)
[[ -n "$version_name" && -n "$version_code" ]] || fail "versionName / versionCode not found in $gradle"
[[ "$version_name" == "$version" ]] \
  || fail "tag $tag does not match versionName \"$version_name\" (set versionName = \"$version\" first)"
(( version_code % 10 == 0 )) || fail "versionCode $version_code is not a multiple of 10 (ABI APKs add 1 and 2)"

locales=("$root"/fastlane/metadata/android/*/)
[[ -d "${locales[0]}" ]] || fail "no fastlane/metadata/android/<locale>/ directories"
for dir in "${locales[@]}"; do
  log="${dir}changelogs/$version_code.txt"
  [[ -s "$log" ]] || fail "missing or empty ${log#"$root"/}"
  chars=$(wc -m < "$log")
  (( chars <= 500 )) || fail "${log#"$root"/} has $chars characters; stores allow 500"
done

if [[ "$prerelease" == false ]]; then
  meta="$root/metadata/dev.velum.app.yml"
  grep -q "^CurrentVersion: $version\$" "$meta" || fail "metadata/dev.velum.app.yml CurrentVersion is not $version"
  grep -q "^CurrentVersionCode: $version_code\$" "$meta" || fail "metadata/dev.velum.app.yml CurrentVersionCode is not $version_code"
fi

mkdir -p "$(dirname "$notes")"
{
  en="$root/fastlane/metadata/android/en-US/changelogs/$version_code.txt"
  pt="$root/fastlane/metadata/android/pt-BR/changelogs/$version_code.txt"
  [[ -f "$en" ]] && { cat "$en"; echo; }
  [[ -f "$pt" ]] && { echo "<details><summary>Português (Brasil)</summary>"; echo; cat "$pt"; echo; echo "</details>"; echo; }
  echo "### Which file to install"
  echo
  echo "- \`velum-$version-arm64-v8a.apk\`: almost every phone from the last years."
  echo "- \`velum-$version-armeabi-v7a.apk\`: older 32-bit phones."
  echo "- \`velum-$version-universal.apk\`: any device (larger download), including x86 ChromeOS / emulators."
  echo
  echo "Check a download with \`sha256sum -c SHA256SUMS --ignore-missing\`. The signing certificate's SHA-256 is in"
  echo "\`SIGNING-CERT.txt\`; every Velum release is signed with the same key."
  if [[ "$prerelease" == true ]]; then
    echo
    echo "This is a pre-release for testing. It can have bugs; keep a backup of important recordings."
  fi
} > "$notes"

echo "version=$version"
echo "prerelease=$prerelease"
