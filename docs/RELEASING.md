# Releasing Velum (Android)

A release is a version tag plus signed files on a GitHub Release: per-ABI and universal APKs for sideloading and
IzzyOnDroid, and an App Bundle (workflow artifact) for the Play Console. F-Droid builds from source on its own
(`metadata/dev.velum.app.yml`).

The workflow is `.github/workflows/release.yml`. It is **manual** (`workflow_dispatch`), like every workflow here
(`docs/cloud-ci-cost-control.md`): pushing a tag does not start a build.

## One-time setup (owner)

1. **Create the release key** on your own machine and keep the file and passwords in a password manager. Never commit it.

   ```sh
   keytool -genkeypair -v -keystore velum-release.jks -alias velum -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Velum, O=Velum contributors"
   ```

   The same key must sign every future release: Android refuses an update signed by another key, and IzzyOnDroid
   pins the certificate.

2. **Play App Signing.** Play re-signs apps with its own key by default, so a Play install could not be updated from a
   GitHub APK (or the other way round). To keep one signature everywhere, choose *"Use my own app signing key"* in the
   Play Console and upload this same key (Play's PEPK tool). If you let Play generate the app signing key instead,
   GitHub/IzzyOnDroid and Play become separate channels; say so in the README.

3. **Repository secrets** (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `VELUM_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 velum-release.jks` |
   | `VELUM_UPLOAD_STORE_PASSWORD` | keystore password |
   | `VELUM_UPLOAD_KEY_ALIAS` | `velum` |
   | `VELUM_UPLOAD_KEY_PASSWORD` | key password |

4. **Repository variable** `VELUM_RELEASE_CERT_SHA256`: the certificate fingerprint, so a build signed with any other
   key fails instead of shipping:

   ```sh
   keytool -list -v -keystore velum-release.jks -alias velum | grep 'SHA256:'
   ```

## Each release

1. Pick the version. Betas are `X.Y.Z-beta.N`; finals are `X.Y.Z`.
2. In `composeApp/build.gradle.kts` set `versionName` to it and raise `versionCode` by 10 (it must stay a multiple of
   10: the ABI APKs add 1 and 2). Betas need their own, lower versionCode than the final that follows them.
3. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and the `pt-BR` one (500 characters max).
4. For a final release also update `metadata/dev.velum.app.yml` (`Builds`, `CurrentVersion`, `CurrentVersionCode`).
5. Check locally, then commit, tag and push the tag:

   ```sh
   bash scripts/release-preflight.sh v1.0.0-beta.1 build/release-notes.md
   git tag -a v1.0.0-beta.1 -m "Velum 1.0.0-beta.1" && git push origin v1.0.0-beta.1
   ```

6. Actions → **Release** → *Run workflow* → tag `v1.0.0-beta.1`. `draft` (default on) creates a draft release.
7. The run checks the tag and changelogs, runs the JVM tests, builds the APKs (`-Pvelum.abiSplits=true`) and the
   AAB, and `scripts/release-collect.sh` verifies every APK (v2 signature, one signer, not the debug key, the pinned
   certificate, no dependency-metadata block) and writes `SHA256SUMS` and `SIGNING-CERT.txt`.
8. Open the draft release, read the notes, install `velum-…-arm64-v8a.apk` on a phone, then **Publish**. A tag with a
   `-` suffix is marked as a pre-release.
9. Play: download the `velum-<version>` artifact from the run and upload `velum-<version>.aab` to the internal
   testing track.

Re-running the workflow for a tag that already has a release replaces its files and leaves its notes alone.

## Release files

| File | For |
|---|---|
| `velum-<v>-arm64-v8a.apk` | Most phones (versionCode + 2) |
| `velum-<v>-armeabi-v7a.apk` | Older 32-bit phones (versionCode + 1) |
| `velum-<v>-universal.apk` | Any ABI incl. x86 / ChromeOS (versionCode + 0) |
| `SHA256SUMS`, `SIGNING-CERT.txt` | Verifying downloads and the key |
| `velum-<v>.aab` (artifact only) | Play Console |

## IzzyOnDroid

IzzyOnDroid serves the APKs attached to GitHub Releases, so nothing is built there. Readiness in this repository:

- Signed APKs on GitHub Releases with stable names (above); the signing certificate is published with each release.
- Fastlane metadata (EN + PT-BR titles, descriptions, changelogs per versionCode, icon, feature graphic,
  screenshots) in `fastlane/metadata/android/`, which IzzyOnDroid reads from the repository.
- No encrypted dependency-metadata block in the APKs (`dependenciesInfo.includeInApk = false`), checked by
  `scripts/apk-signing-blocks.py` in the release run.
- No trackers, analytics or proprietary Google libraries in the app (`docs/PRIVACY.md`, `docs/DEPENDENCY_AUDIT.md`).

To apply (owner, after the first published release): open a request at
<https://gitlab.com/IzzyOnDroid/repo/-/issues> with the app id `dev.velum.app`, the repository URL, the APK to pick
(`velum-.*-arm64-v8a\.apk`, or the universal one), and the certificate SHA-256 from `SIGNING-CERT.txt`. Their
scanner then reviews the APK; answer its findings on the issue.

## Checks you can run without secrets

```sh
bash scripts/test-release-scripts.sh        # preflight + signing-block parser on synthetic inputs
bash scripts/check-release-signing.sh        # shipping tasks reject missing / partial signing
bash scripts/check-release-signing.sh --package   # builds with a disposable key: splits, versionCodes, alignment
```
