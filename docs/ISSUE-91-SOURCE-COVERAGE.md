# Issue 91 source coverage inventory

Inventory date: 2026-11-06  
Repository: `bloodf/velum`  
Issue: https://github.com/bloodf/velum/issues/91  
Lease: `a92c58ed-8c47-40dd-a83e-d15fd5857943`  
HEAD inspected: `963663004e44f7a9b3816fc4c247a8053a2f6578` (`2026-11-05`, merge #494)  
Worktree was clean before this inventory. No builds, tests, APK generation, or device work were performed.

## Finding

Issue 91 is **partially implemented** at this HEAD. ABI APK split configuration and a local APK size checker exist. The repository contains a historical release size report and a proposed arm64 baseline. Concrete gaps remain: the target is explicitly unapproved, the checker is not wired into CI, large template assets remain bundled, and the measured breakdown is in the repository but is not attached to the issue. The source includes test hooks and workflow steps for native packaging, smoke, and device walks, but this inventory does not establish that they pass on a device or that current artifacts meet the recorded measurements.

## Requirement-to-source/test map

| Issue acceptance criterion | Current evidence at HEAD | Coverage / gap |
|---|---|---|
| APK Analyzer/equivalent size breakdown listing largest contributors | [`docs/APK_SIZE.md`](docs/APK_SIZE.md#L5) records measured APK totals and an arm64 breakdown at `067fb9c7` on 2026-11-03. It lists native `.so` libraries, assets, resources and dex. | A breakdown exists in source, but no issue comment or attachment is evidenced by the issue fetch. Measurements are historical and are not re-measured at this HEAD. |
| Per-ABI splits or AAB | [`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts#L64) opts in via `-Pvelum.abiSplits=true`, includes ARM ABIs and a universal APK; lines 275–281 define split outputs. [`release.yml`](../.github/workflows/release.yml#L59) produces an AAB on configured version-tag builds. | Source implements both packaging paths. `ci.yml` debug path remains universal for x86_64 emulator. No current APK/AAB artifact was found in the worktree (`composeApp/build` is absent; no tracked APK/AAB/APKS). No packaging command was run. |
| Large assets compressed or loaded on demand from local storage | `docs/APK_SIZE.md` lines 25–33 identifies `plaud_templates_full.jsonl.gz` (7.6 MiB in the historical APK) and `plaud_templates.jsonl` (1.2 MiB in that APK); lines 56–58 explicitly defer moving the 8.8 MiB of template data to local storage. `core/build.gradle.kts` lines 55–60 includes common resources in Android packaging. | Uncovered behavior: the full and featured template catalogs are still bundled. The full catalog is already gzip-compressed; the featured catalog is shipped as JSONL. No on-demand asset load is implemented per the source note. No account, telemetry, or cloud path was added in this work. |
| Agreed target and CI check that fails >10% above it | [`scripts/apk-size-baseline.txt`](../scripts/apk-size-baseline.txt) contains `77139223`; [`scripts/check-apk-size.sh`](../scripts/check-apk-size.sh#L9) validates APK ABI and size against baseline +10%; [`docs/APK_SIZE.md`](docs/APK_SIZE.md#L35) says this baseline is proposed and no owner has agreed a target. | Uncovered behavior: owner agreement is pending and the checker is not called by `.github/workflows/ci.yml` or `release.yml`. `release.yml` uploads AAB only. The size gate therefore does not currently block CI growth. |
| Emulator smoke and debug/R8 release device walks green | [`ci.yml`](../.github/workflows/ci.yml#L228) invokes API 35 emulator smoke; lines 241 onward define “Emulator device walk (debug + R8 release)”. | Source/workflow coverage exists. No workflow run/result was inspected, and no device was driven here; green status and on-device behavior remain unverified. |

## Declared dependencies and packaging contributors

**Measured APK contributors (historical; bytes from report, not this checkout):** `docs/APK_SIZE.md` says its APKs were measured at commit `067fb9c7` using `:composeApp:assembleRelease -Pvelum.abiSplits=true -Pvelum.unsignedRelease=true`, R8 enabled and native libraries uncompressed. Universal APK: 206,657,609 bytes (197.1 MiB); arm64: 77,139,223 bytes (73.6 MiB); armeabi-v7a: 44,353,719 bytes (42.3 MiB). Its arm64 breakdown assigns 56.2 MiB to native libs (about 76%), 7.6 MiB to gzip full templates, 3.8 MiB to `res/`, 3.4 MiB to `classes.dex`, 1.2 MiB to featured templates, and 1.4 MiB to compose/resources. It calls out ONNX Runtime (21.2 MiB), LiteRT-LM (20.5 MiB), sherpa-onnx (9.2 MiB), sqlite3x (1.8 MiB), and libc++ (1.7 MiB) within the arm64 native group. These values describe that historical APK only.

**Current declared source contributors (not APK measurements):** `ai/build.gradle.kts` lines 45–52 declares Android `sherpa-onnx`, `kopus`, `commons-compress`, `litertlm-android`, WorkManager and core-ktx; lines 70–85 limits the module's native ABI filter to arm64-v8a and armeabi-v7a, packages project JNI libraries and keeps Needle symbols. `gradle/libs.versions.toml` pins sherpa-onnx `v1.13.8` and LiteRT-LM `0.16.1`. `composeApp/build.gradle.kts` lines 158–176 pulls in core, AI, BLE, protocol, recorder, Compose, navigation, lifecycle, coroutines, serialization and markdown. Core Android dependencies include SQLDelight Android and requery SQLite (`core/build.gradle.kts` lines 33–34). Release enables minification and resource shrinking (`composeApp/build.gradle.kts` lines 317–327), and `jniLibs.useLegacyPackaging=false` is selected for 16 KB alignment (lines 283–287; also `ai/build.gradle.kts` lines 80–86).

**Local source payload byte counts (uncompressed source files; not APK estimates):** `wc -c` at this HEAD measured arm64 JNI files as 1,794,776 + 1,464,120 bytes; armeabi-v7a JNI files as 1,301,936 + 987,924 bytes. The source resource files measured 7,921,600 bytes for `plaud_templates_full.jsonl.gz`, 4,325,659 bytes for `plaud_templates.jsonl`, and 83,364 bytes for `autoflow_templates.jsonl`. These raw source counts must not be summed or represented as installed/download size: APK compression, dependency-transitive native payload, resource processing, alignment and ABI selection differ.

**Existing APK artifacts:** no tracked `*.apk`, `*.aab`, or `*.apks` files were found; `composeApp/build` is absent. Thus there is no current local artifact to byte-measure or inspect. The historical report is the sole APK byte evidence in this checkout.

## Existing regressions and limits

- `composeApp/src/androidUnitTest/kotlin/dev/velum/app/NativeAbiPackagingTest.kt` checks JNI file presence and arm64 Needle ELF PT_LOAD alignment from source files. `ci.yml` also checks that the debug APK contains arm64 Needle/libc++, the catalogs, `resources.arsc`, and has 16 KB alignment (lines 48–100). These checks cover presence/alignment, not release split size.
- `scripts/check-apk-size.sh --self-test` is the checker’s narrow self-test command; it exercises synthetic ZIPs and baseline parsing, not a real release APK. This inventory did not execute it.
- Template catalog source tests include `core/src/jvmTest/kotlin/dev/velum/core/templates/FullCatalogImportTest.kt`, `TemplateHygieneCatalogTest.kt`, and `TemplateDedupeRuleTest.kt`; Android instrumented catalog coverage includes `composeApp/src/androidInstrumentedTest/kotlin/dev/velum/app/templates/FullCatalogDeviceTest.kt` and `ui/templates/DiscoverBundledCatalogDeviceTest.kt`. Existing source tests may constrain future asset relocation, but their presence does not prove device success.
- Required CodeGraph 1.6.2 status was read: the index is not initialized. Initializing/syncing would create index files, contrary to the read-only constraint; bounded source search was used instead.

## Proposed narrow verification command (not run)

To check an actual arm64 release artifact after an authorized build, run:

```sh
scripts/check-apk-size.sh composeApp/build/outputs/apk/release/composeApp-arm64-v8a-release-unsigned.apk
```

That command checks the APK's only native ABI and size versus the proposed baseline plus 10%; it does not establish owner agreement, add the check to CI, validate an AAB's delivered size, or prove device behavior. Do not treat its current baseline as an agreed target until an owner resolves that explicitly.
