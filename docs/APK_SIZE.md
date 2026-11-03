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
| `lib/arm64-v8a` (onnxruntime 21.2, litertlm 20.5, sherpa-onnx 9.3, sqlite3x 1.8, c++ 1.7, rest) | 56.2 |
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
fails above baseline + 10% and below half the baseline (empty or truncated file):

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
