#!/usr/bin/env bash
# Self-test for release-preflight.sh and apk-signing-blocks.py on synthetic inputs (no Android SDK, no keys).
#   bash scripts/test-release-scripts.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0
ok() { pass=$((pass + 1)); echo "PASS: $*"; }
die() { echo "FAIL: $*"; exit 1; }

fixture() { # fixture <versionName> <versionCode> <metaVersion> <metaCode>
  local r="$work/repo-$RANDOM$RANDOM"
  mkdir -p "$r/composeApp" "$r/metadata" "$r/fastlane/metadata/android/en-US/changelogs" "$r/fastlane/metadata/android/pt-BR/changelogs"
  printf '    defaultConfig {\n        versionCode = %s // note\n        versionName = "%s"\n' "$2" "$1" > "$r/composeApp/build.gradle.kts"
  printf 'CurrentVersion: %s\nCurrentVersionCode: %s\n' "$3" "$4" > "$r/metadata/dev.velum.app.yml"
  echo "English notes" > "$r/fastlane/metadata/android/en-US/changelogs/$2.txt"
  echo "Notas em português" > "$r/fastlane/metadata/android/pt-BR/changelogs/$2.txt"
  echo "$r"
}
preflight() { VELUM_ROOT="$1" bash "$here/release-preflight.sh" "$2" "$1/notes.md" 2>"$1/err"; }
expect_fail() { # expect_fail <repo> <tag> <message fragment> <label>
  if preflight "$1" "$2" >/dev/null; then die "$4 was accepted"; fi
  grep -q -- "$3" "$1/err" || die "$4 failed for another reason: $(cat "$1/err")"
  ok "$4"
}

r=$(fixture 1.2.0 1200 1.2.0 1200)
out=$(preflight "$r" v1.2.0) || die "final release rejected: $(cat "$r/err")"
[[ "$out" == $'version=1.2.0\nprerelease=false' ]] || die "outputs: $out"
grep -q "English notes" "$r/notes.md" && grep -q "Notas em português" "$r/notes.md" && grep -q "velum-1.2.0-arm64-v8a.apk" "$r/notes.md" \
  || die "notes: $(cat "$r/notes.md")"
! grep -q "pre-release" "$r/notes.md" || die "final notes call it a pre-release"
ok "final release: outputs and bilingual notes"

r=$(fixture 1.2.0-beta.2 1190 1.1.0 1100)
out=$(preflight "$r" v1.2.0-beta.2) || die "beta rejected: $(cat "$r/err")"
[[ "$out" == *"prerelease=true"* ]] && grep -q "pre-release" "$r/notes.md" || die "beta not marked: $out"
ok "pre-release: F-Droid metadata may lag, notes say pre-release"

r=$(fixture 1.2.0 1200 1.2.0 1200); expect_fail "$r" 1.2.0 "is not v<major>" "tag without v"
r=$(fixture 1.2.0 1200 1.2.0 1200); expect_fail "$r" v1.2 "is not v<major>" "two-part version"
r=$(fixture 1.2.0 1200 1.2.0 1200); expect_fail "$r" "v1.2.0;rm" "is not v<major>" "shell characters in tag"
r=$(fixture 1.2.0 1200 1.2.0 1200); expect_fail "$r" v1.3.0 'does not match versionName' "tag / versionName mismatch"
r=$(fixture 1.2.0 1201 1.2.0 1201); expect_fail "$r" v1.2.0 "not a multiple of 10" "versionCode not a multiple of 10"
r=$(fixture 1.2.0 1200 1.2.0 1200); rm "$r/fastlane/metadata/android/pt-BR/changelogs/1200.txt"
expect_fail "$r" v1.2.0 "pt-BR/changelogs/1200.txt" "missing pt-BR changelog"
r=$(fixture 1.2.0 1200 1.2.0 1200); head -c 501 /dev/zero | tr '\0' 'a' > "$r/fastlane/metadata/android/en-US/changelogs/1200.txt"
expect_fail "$r" v1.2.0 "stores allow 500" "changelog over 500 characters"
r=$(fixture 1.2.0 1200 1.1.0 1100); expect_fail "$r" v1.2.0 "CurrentVersion is not 1.2.0" "final release with stale F-Droid metadata"

# apk-signing-blocks.py on synthetic zips: one with a v2 + dependency block, one without, one unsigned.
python3 - "$work" <<'PY'
import struct, sys, zipfile, io, os
work = sys.argv[1]
def apk(path, ids):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", b"synthetic")
    data = buf.getvalue()
    eocd = data.rfind(b"PK\x05\x06")
    cd = struct.unpack_from("<I", data, eocd + 16)[0]
    pairs = b"".join(struct.pack("<QI", 4 + 8, i) + b"\0" * 8 for i in ids)
    size = len(pairs) + 8 + 16
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
    if not ids:
        block = b""
    out = data[:cd] + block + data[cd:eocd] + data[eocd:eocd + 16] + struct.pack("<I", cd + len(block)) + data[eocd + 20:]
    open(os.path.join(work, path), "wb").write(out)
apk("with-deps.apk", [0x7109871A, 0x504B4453])
apk("clean.apk", [0x7109871A, 0xF05368C0])
apk("unsigned.apk", [])
PY
python3 "$here/apk-signing-blocks.py" "$work/clean.apk" | grep -q "v3 signature" || die "clean APK blocks not listed"
python3 "$here/apk-signing-blocks.py" --forbid-dependency-metadata "$work/clean.apk" >/dev/null || die "clean APK rejected"
ok "signing blocks: clean APK accepted and listed"
set +e
python3 "$here/apk-signing-blocks.py" --forbid-dependency-metadata "$work/with-deps.apk" >/dev/null 2>&1; code=$?
python3 "$here/apk-signing-blocks.py" "$work/unsigned.apk" >/dev/null 2>&1; unsigned=$?
set -e
[[ $code == 1 ]] || die "dependency-metadata block not flagged (exit $code)"
ok "signing blocks: dependency metadata flagged"
[[ $unsigned == 2 ]] || die "unsigned APK exit $unsigned"
ok "signing blocks: unsigned APK reported"
echo "release scripts: $pass checks passed"
