# Release APK size (#91)

Measured 2026-11-03 on `067fb9c7` with
`./gradlew :composeApp:assembleRelease -Pvelum.abiSplits=true -Pvelum.unsignedRelease=true`
(R8 on, native libs stored uncompressed for 16 KB page alignment).

| APK | Bytes | MB |
|---|---|---|
| universal (arm64-v8a, armeabi-v7a, x86, x86_64) | 206,657,609 | 197.1 |
| arm64-v8a | 77,139,223 | 73.6 |
| armeabi-v7a | 44,353,719 | 42.3 |

The universal APK is what a single-APK build produced before the ABI splits (the ~200 MB in the
issue); F-Droid keeps using it. A phone downloading its own ABI APK gets 63% less.

## arm64-v8a contributors

| Part | Size in APK |
|---|---|
| `lib/arm64-v8a` (onnxruntime 22.2, litertlm 21.5, sherpa-onnx 9.3, sqlite3x 1.8, c++ 1.8, rest) | 58.9 MB |
| `plaud_templates_full.jsonl.gz` (already gzip) | 7.9 MB |
| `classes.dex` | 3.6 MB |
| `res/` | 3.9 MB |
| `plaud_templates.jsonl` | 1.3 MB |
| compose resources, resources.arsc, other | 1.5 MB |

Native libraries dominate (about 76%). They are stored uncompressed on purpose (`useLegacyPackaging = false`) so
`zipalign -P 16` applies; store downloads are compressed in transit. No models are bundled.

## Target and check

Target for the arm64-v8a release APK: 77,139,223 bytes (`scripts/apk-size-baseline.txt`). The check fails above
target + 10%:

```
scripts/check-apk-size.sh composeApp/build/outputs/apk/release/composeApp-arm64-v8a-release-unsigned.apk
scripts/check-apk-size.sh --self-test
```

Wiring this into CI is not done (needs a `.github` change).

## Not done

Loading the 9.2 MB of template data on demand from local storage would save about 12% of the arm64 APK but
changes first-run behaviour and the bundled-catalog tests; left for a decision.
