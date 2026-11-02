# Contributing to Velum

Thanks for helping build a local-first, subscription-free recorder companion.

## Ground rules

1. **No secrets.** Never commit API keys, Plaud account tokens, device serial signatures, RSA private keys, Fernet keys, or anything from a decompiled APK’s assets. Device identity is imported by the user at runtime.
2. **No Plaud branding.** Do not copy Plaud logos or marketing copy. The curated Plaud template catalog is maintained separately (`docs/templates/plaud_templates.jsonl`); new in-app templates you contribute must be **original Velum** work (see Template contributions below).
3. **Interoperability only.** Reverse-engineering notes exist so Velum can talk to hardware the user owns. Prefer read-only BLE commands until download/decrypt is proven safe.
4. **Apache-2.0.** By contributing, you license your work under the Apache License 2.0.
5. **No unimplemented UI.** Any menu item, button, tab, row, sheet entry, or toggle whose feature is not actually implemented and working must be removed from the UI. No “coming soon”, disabled placeholders, or dead links — app-wide (library, FAB, Explore, Integrations, AutoFlow, Device, Templates, recording menus, share sheet, onboarding, desktop layout, Settings). Velum has **no telemetry / analytics / crash-reporting opt-in**; do not add usage-data toggles or similar.
6. **Use focused PRs with local verification.** The owner authorized PRs, merges and evidence-based issue closure on 2026-11-01, replacing the earlier direct-to-main rule for maintenance. Review each diff, validate the exact code locally, respect branch protections and verify the merged tree. See [engineering standards](docs/ENGINEERING_STANDARDS.md) for the current workflow and the historical Product rules.

## Dev setup

- JDK 17+
- Android Studio / SDK 35 for Android

**Android-first:** prefer changes that ship on Android. iOS source sets may exist for shared code, but there is no Xcode project or iOS CI yet.

```bash
./gradlew :plaud-protocol:jvmTest :ai:jvmTest
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:run   # desktop shell (optional)
```

Shipping `:composeApp:assembleRelease` and `:composeApp:bundleRelease` require all
four environment variables: `VELUM_UPLOAD_STORE_FILE` (path to your private upload
keystore), `VELUM_UPLOAD_STORE_PASSWORD`, `VELUM_UPLOAD_KEY_ALIAS`, and
`VELUM_UPLOAD_KEY_PASSWORD`. Supply them through your local secret manager or CI;
never commit them or print their values. Missing or blank values fail the release
build. A shipping release never falls back to the committed public debug key.

Release APKs: `./gradlew :composeApp:assembleRelease -Pvelum.abiSplits=true` writes
`composeApp-arm64-v8a-release.apk`, `composeApp-armeabi-v7a-release.apk` and
`composeApp-universal-release.apk` (all ABIs, incl. x86/x86_64 for sideload / ChromeOS)
under `composeApp/build/outputs/apk/release/`. Without the property you get one universal
`composeApp-release.apk`. Build the Play AAB in a separate invocation without the property
(`./gradlew :composeApp:bundleRelease`); combining the property with a bundle task fails.
Release versionCode is `defaultConfig.versionCode * 10 + ABI digit`: universal / AAB `0`,
`armeabi-v7a` `1`, `arm64-v8a` `2`, so a universal install can move to its ABI APK and the
next release outranks every output of this one.

To check R8 locally without upload credentials, run
`./gradlew :composeApp:assembleMinifiedTest`. This explicitly named test variant
inherits release minification and uses debug signing plus instrumented-test keeps;
do not distribute it as a production release. Run the device walk with
`bash scripts/emulator-device-walk-both.sh`. CI checks missing/partial signing with
`bash scripts/check-release-signing.sh --package` and builds this test variant
without secrets. The check generates a disposable key to verify shipping APK/AAB
packaging and resource shrinking; never distribute those test artifacts.

## Project layout

| Module | Role |
|--------|------|
| `plaud-protocol` | Pure Kotlin codec + unit tests (no Android/BLE deps) |
| `core` | Domain + settings + secure store interfaces |
| `ai` | Audio decoding, local/API engines, model management and processing jobs |
| `recorder` | Recorder driver interfaces and Plaud session/handshake logic |
| `ble` | Transport interface; Kable on mobile; stub on desktop |
| `composeApp` | Shared Compose UI + platform entry points |

See [ARCHITECTURE.md](ARCHITECTURE.md) for the verified module/dependency map and input-to-storage/UI flow.

## Pull requests

- Keep PRs focused; prefer small modules and tests for protocol/byte parsing.
- Add fixtures as hex dumps in tests — never as real encrypted user recordings with private keys.
- Update `docs/protocol/` when you learn something new about framing/opcodes, and credit sources.
- Run JVM tests before pushing.

## Code style

- Kotlin official style; exhaustive `when` on sealed types.
- Imports at the top of the file (no inline imports).
- Prefer interfaces at module boundaries (`BleTransport`, `SecureStore`, `FileDecryptor`, `SessionCipher`).

## AutoFlow automation templates

Ship a ready-made AutoFlow rule (triggers/conditions + note template + optional post-action) as a JSON file under `templates/autoflow/`. Full guide: [`docs/autoflow-templates.md`](docs/autoflow-templates.md).

Quick path:

1. Add `templates/autoflow/<category>/<slug>.json` (see schema in that folder).
2. Localize `name` / `description` / `preview` in **en** and **pt-BR**.
3. Set optional `author` for contributor credit in the app; omit it for official built-ins.
4. Run `./gradlew :core:validateAutoflowTemplates` (or `./scripts/sync-autoflow-templates.sh`) then `./gradlew :core:jvmTest`.

Propose ideas with the GitHub issue template **Automation template proposal**.

## Template contributions

Ship an original summary template via pull request. Plaud author/official fields exist only in the curated dump + `docs/TEMPLATES_CREDITS.md` — the app UI never shows them. **In-app credit is only for Velum PR contributors.**

### Where to add the entry

Append **one JSON object per line** to:

```text
docs/templates/velum_contributed.jsonl
```

Then run:

```bash
./scripts/sync-plaud-templates.sh
```

That copies the JSONL into `core/src/commonMain/resources/velum_contributed.jsonl` and refreshes `docs/TEMPLATES_CREDITS.md`.

Do **not** edit `docs/templates/plaud_templates.jsonl` for new Velum work (that file is the curated Plaud set).

### Required fields

```json
{
  "id": "standup-daily",
  "category": "meeting",
  "name": "Daily standup",
  "description": "Wins, blockers, and plans from the transcript.",
  "bodyType": "outline",
  "body": "Summarize wins, blockers, and plans as bullet lists. Omit empty sections.",
  "contributor": { "name": "Ada Lovelace", "github": "ada" }
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `id` | yes | Stable slug; stored as `velum.contrib.<id>` if it does not already start with `velum.` |
| `category` / `categoryId` | yes | One of: `general`, `meeting`, `call`, `sales`, `interview`, `consulting`, `education`, `speech`, `medical`, `legal`, `financial`, `estate`, `construction`, `it`, `extractive` |
| `name` | yes | Display name |
| `description` | yes | Short picker blurb |
| `bodyType` | yes | `outline` = instructions for the model; `prompt` = output skeleton to fill |
| `body` | yes | Verbatim instructions or skeleton (never invent Plaud/community text) |
| `contributor.name` | yes | Shown on template detail |
| `contributor.github` | no | Handle without `@`; shown as `Name (@handle)` |
| `author` / `official` / `attribution` | no | Unused for UI on contributed templates; prefer `contributor` |

### Review checklist

- [ ] Original wording (not copied from Plaud or other closed catalogs)
- [ ] `bodyType` matches intent (`outline` = instructions, `prompt` = skeleton)
- [ ] Category key is in the table above
- [ ] `contributor.name` set; `github` optional and accurate
- [ ] `./scripts/sync-plaud-templates.sh` run; credits doc updated
- [ ] `./gradlew :core:jvmTest` passes (catalog validation included)

## Security & privacy

- Treat voice as sensitive personal data (LGPD / similar). Opt-in for any speaker embeddings later.
- Log carefully: never log API keys, PEM material, or bind tokens.
