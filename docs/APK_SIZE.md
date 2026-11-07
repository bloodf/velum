# Release APK size (#91)

All sizes below are MiB (1 MiB = 1,048,576 bytes) unless a byte count is given.

Measured 2026-11-03 on `067fb9c7` with
`./gradlew :composeApp:assembleRelease -Pvelum.abiSplits=true -Pvelum.unsignedRelease=true`
(R8 on, native libs stored uncompressed for 16 KB page alignment).

| APK | Bytes | MiB |
|---|---|---|
| universal (arm64-v8a, armeabi-v7a, x86, x86_64) | 206,657,609 | 197.1 |
| arm64-v8a | 77,139,223 | 73.6 |
| armeabi-v7a | 44,353,719 | 42.3 |

No x86 or x86_64 APK is built (the split config includes only the two ARM ABIs plus the universal APK). Their
native code inside the universal APK: x86 37.4 MiB, x86_64 61.1 MiB (arm64-v8a 56.2, armeabi-v7a 24.9).

The universal APK is what a single-APK build produced before the ABI splits (the ~200 MB in the issue);
F-Droid keeps using it. A phone downloading its own ABI APK gets 63% less.

## arm64-v8a contributors

| Part | MiB in APK |
|---|---|
| `lib/arm64-v8a` (onnxruntime 21.2, litertlm 20.5, sherpa-onnx 9.2, sqlite3x 1.8, c++ 1.7, rest) | 56.2 |
| `plaud_templates_full.jsonl.gz` (already gzip) | 7.6 |
| `res/` | 3.8 |
| `classes.dex` | 3.4 |
| `plaud_templates.jsonl` | 1.2 |
| compose resources, resources.arsc, other | 1.4 |

Native libraries dominate (about 76%). They are stored uncompressed on purpose (`useLegacyPackaging = false`) so
`zipalign -P 16` applies; store downloads are compressed in transit. No models are bundled.

## Proposed baseline and check

Proposed baseline for the arm64-v8a release APK: 77,139,223 bytes, the size measured above
(`scripts/apk-size-baseline.txt`). No owner has agreed a target yet; the owner decides the number. The check
fails above baseline + 10%, below half the baseline (truncated file), when the APK holds anything but arm64-v8a native libs (wrong ABI or universal), or when the baseline file is not a single positive integer:

```
scripts/check-apk-size.sh composeApp/build/outputs/apk/release/composeApp-arm64-v8a-release-unsigned.apk
scripts/check-apk-size.sh --self-test
```

**The check is manual until CI is changed.** It needs a `.github` edit, which agents do not make. Step for the
owner, after the release build in `ci.yml` (adjust the build step to produce the unsigned split APKs):

```yaml
- name: APK size check
  run: scripts/check-apk-size.sh composeApp/build/outputs/apk/release/composeApp-arm64-v8a-release-unsigned.apk
```

## Deferred

The issue's criterion to compress or load large assets on demand is deferred, not done. Loading the 8.8 MiB of
template data from local storage would save about 12% of the arm64 APK but changes first-run behaviour and the
bundled-catalog tests; it needs an owner decision.

## Retained artifact investigation, 2026-11-07

Source inspected at `8b1dc3251379614c2082d3cd558069dac34f38dc`. A bounded scan of
registered local worktrees checked `composeApp/build/outputs`, `androidApp/build/outputs`,
`build/outputs` and `artifacts` to five directory levels. It found one APK, and no release
APK or AAB. The local orchestration artifact directories also contained no APK. The release
measurements above remain historical; they were not reproduced in this investigation.

The retained artifact is **debug**, so these numbers cannot establish the current R8 release
size or an arm64 release baseline:

- Path: `/Users/heitor/.t3/worktrees/velum/t3-45db0fb4/composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
- File size: 229,189,132 bytes; 407 ZIP entries. The producing commit is unverified.
- Sum of ZIP compressed entry sizes: 228,818,128 bytes. The remaining 371,004 bytes
  cover ZIP headers, alignment, signing and other container overhead.
- Sum of uncompressed entry sizes: 285,850,871 bytes.

Python standard-library `zipfile.ZipFile.infolist()` supplied the entry sizes without
extracting, rebuilding or modifying the APK. Native entries all use ZIP method 0, stored.

| Group | Bytes in ZIP entries | Uncompressed bytes |
|---|---:|---:|
| `lib/x86_64/` | 64,019,120 | 64,019,120 |
| `lib/arm64-v8a/` | 58,896,968 | 58,896,968 |
| `lib/x86/` | 39,179,796 | 39,179,796 |
| `lib/armeabi-v7a/` | 26,123,704 | 26,123,704 |
| All DEX entries | 25,444,214 | 79,134,804 |
| `res/` | 4,316,910 | 4,337,793 |
| `assets/` | 931,303 | 931,303 |

Native libraries total 188,219,588 bytes. The largest native entries are
`lib/x86/libonnxruntime.so` at 26,491,148 bytes,
`lib/x86_64/liblitertlm_jni.so` at 25,649,544 bytes and
`lib/x86_64/libonnxruntime.so` at 25,581,120 bytes.
For arm64, ONNX Runtime is 22,249,560 bytes, LiteRT-LM is 21,529,648 bytes,
Sherpa JNI is 4,771,760 bytes and Sherpa C API is 4,465,168 bytes.
`ai/build.gradle.kts` declares Sherpa ONNX and LiteRT-LM Android dependencies;
`core/build.gradle.kts` declares Requery SQLite. Repeated library names across ABIs
represent different machine architectures and do not establish removable duplication.

The largest catalog entries are at the ZIP root, outside `assets/`:

| Entry | Bytes in ZIP | Uncompressed bytes |
|---|---:|---:|
| `plaud_templates_full.jsonl.gz` | 7,921,600 | 7,921,600 |
| `plaud_templates.jsonl` | 1,299,265 | 4,325,659 |
| `autoflow_templates.jsonl` | 12,545 | 83,364 |

The full catalog is already gzip-compressed and its reader supports streaming lines
for Discover import. The six Compose image assets total 931,303 bytes. No entry names
end in `.gguf`, `.onnx`, `.tflite` or `.pte`. No duplicate ZIP entry names were found;
this directory inspection does not prove that differently named entries have different contents.

### Packaging decision and remaining evidence

Current `composeApp/build.gradle.kts` defaults `velum.abiSplits` to false. Enabling it
produces arm64-v8a and armeabi-v7a splits plus a universal APK. Bundle tasks reject
the split flag. `useLegacyPackaging=false` preserves uncompressed native libraries
for page alignment. These existing controls already address per-ABI distribution.

No safe removable packaging duplication was confirmed, so packaging, native engines,
catalogs and licenses remain unchanged. No size target was chosen and the proposed
baseline remains unapproved. Run the existing size guard only against an arm64 release
APK after its owner agrees the target.

To finish current release attribution, supply a retained release APK and its producing
commit, then repeat the ZIP directory analysis below. Do not substitute this debug
snapshot for release evidence. No build, download, emulator, inference, hardware or
E2E check ran for this documentation-only investigation.

```sh
python3 - /absolute/path/to/retained-release.apk <<'PYZIP'
import collections
import pathlib
import sys
import zipfile

path = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(path) as archive:
    entries = archive.infolist()
    print("APK bytes:", path.stat().st_size)
    print("Entry bytes:", sum(e.compress_size for e in entries))
    print("Expanded bytes:", sum(e.file_size for e in entries))
    totals = collections.Counter()
    for entry in entries:
        name = entry.filename
        group = "/".join(name.split("/")[:2]) if name.startswith("lib/") else name.split("/")[0]
        totals[group] += entry.compress_size
    for group, size in totals.most_common():
        print(size, group)
    print("Top entries, compressed / expanded / method / name:")
    for entry in sorted(entries, key=lambda e: e.compress_size, reverse=True)[:25]:
        print(entry.compress_size, entry.file_size, entry.compress_type, entry.filename)
PYZIP
```
