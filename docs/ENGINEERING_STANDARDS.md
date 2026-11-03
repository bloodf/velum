# Engineering standards

## Safety and preservation

Never send commands `104`, `5`, `0xFE20`, `128`, or `143` (OTA), or write device
settings `4` or `26`. Retain the forbidden-opcode/settings guards and their tests.
Protocol changes must follow [protocol documentation](protocol/README.md) and be
verified with synthetic fixtures before any explicitly authorized hardware work.

Identity values, API credentials, signatures, tokens and PEM material must never
be committed, included in issues/chat, logged, or copied into CI artifacts.
Synthetic fixtures must be clearly synthetic. User identity extraction and real
device confirmation remain separate owner-dependent work; see
[identity import](PLAUD_IMPORT.md) and [hardware validation](HARDWARE_VALIDATION.md).
Do not use secrets as exception messages or display raw parser/provider errors.

Treat recordings, encrypted profiles and secure-store entries as persistent user
data. A read/provider/authentication failure must not trigger reset, key rotation
or deletion. Preserve legacy formats; demonstrate compatibility with frozen
synthetic fixtures. Recovery must validate before publishing, avoid overwriting
an existing recording, and distinguish unavailable storage from missing data.
Cancellation must propagate and must not produce a successful capture/import.
These are rules for changes, not a claim that every existing path already meets
them; open integrity issues retain their acceptance criteria.

## Local checks

Use JDK 17 and the Android SDK specified by the version catalog. Run from the
repository root; supply `JAVA_HOME` and `ANDROID_HOME` through your environment.
These commands do not require production upload credentials:

```sh
./gradlew :plaud-protocol:jvmTest :ble:jvmTest :core:jvmTest :ai:jvmTest :recorder:jvmTest
./gradlew :core:testDebugUnitTest :composeApp:testDebugUnitTest
./gradlew :composeApp:desktopTest --tests 'dev.velum.app.platform.*'
./gradlew :composeApp:lintDebug :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:assembleMinifiedTest :composeApp:assembleMinifiedTestAndroidTest -PvelumTestBuildType=minifiedTest
bash scripts/emulator-device-walk-both.sh
```

Check the current Gradle task graph when selecting a narrower suite. Force a test
task to execute when investigating nondeterminism (`--rerun` for that task, or
`--rerun-tasks` for the entire requested graph), disable test-result cache reuse,
and retain fresh XMLs. A successful Gradle invocation alone does not establish
that a particular test executed. Read test counts, failures, errors and skips.

Use a dedicated disposable emulator with synthetic data for the device walk; do
not target an owner device or a profile containing real recordings/credentials.
The script installs test packages and clears the emulator log buffer.

The emulator walk must execute the required journey in English/Portuguese and
light/dark for both debug and minifiedTest. Preserve hard coverage assertions.
Inspect destination content in screenshots: a filename or marker alone does not
prove the destination rendered. A test-signed R8 variant is not a shipping release.
Robolectric/JVM checks do not replace device filesystem or physical hardware tests.
State Android API/device coverage precisely; API 34 success does not establish
Android 15 behavior. Activity recreation is not genuine process termination.

Shipping APK/AAB builds require all four upload-signing variables documented in
[CONTRIBUTING](../CONTRIBUTING.md). Never print their values. The build must reject
missing, partial and blank credentials and must not fall back to public debug
signing. `bash scripts/check-release-signing.sh --package` exercises negative cases
and generates disposable signing material for positive packaging verification.
Those artifacts are test packages and must not be distributed as production releases.
R8 diagnostics must be investigated rather than silenced with package-wide keeps.

## Integration workflow

The historical repository rule was direct-to-main commits referencing the issue,
with CI green. On 2026-11-01 the owner authorized cohesive PRs, merges and issue
closure, and explicitly selected local verification instead of dependence on CI.
For this maintenance work, create focused draft PRs, review the diff, validate the
exact source tree locally, mark ready and merge normally when evidence supports it.
Do not bypass branch protections or required checks. Verify the merged remote tree and preserve unrelated user edits.

Close issues only when their acceptance criteria have evidence. Keep partial and
owner/hardware/service-dependent work open with the precise missing requirement.
Test counts and closed-issue counts are not a zero-defect guarantee. Do not publish
releases, change security settings, buy services or create owner credentials merely
because a software implementation issue is authorized.

## Original product rules

The following block is copied verbatim from [issue #89](https://github.com/bloodf/velum/issues/89). Its final workflow/CI line
is historical; the current owner instruction above supersedes it for this work.
Local voice-recognition profiles are not hosted user accounts.

- No UI element without a working function (no placeholders, no "coming soon").
- No account, login, profile or telemetry. No cloud code paths in the open-source build.
- Never send device commands 104, 5, 0xFE20, 128, 143 or 30 (delete file); never write device settings 4 or 26 (ForbiddenOpcodes / ForbiddenSettings guard tests stay green).
- Never log or print identity values (token, PEMs, signatures). No Plaud screenshots or branding.
- Commit directly to main referencing this issue, with tests; CI green (JVM tests, assemble, OSV, emulator smoke, device walk debug + R8 release).

See [architecture](../ARCHITECTURE.md), [AI architecture](ARCHITECTURE_AI.md),
[contribution rules](../CONTRIBUTING.md), and [privacy](PRIVACY.md). Optional
user-configured AI endpoints are documented in the AI architecture; this is not
authorization to add a hosted Velum account, telemetry or subscription backend.
