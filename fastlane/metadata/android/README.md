# Android store metadata (Play / F-Droid)

Fastlane supply / F-Droid-compatible layout.

- `en-US/`, `pt-BR/` — localized title, short/full description, changelogs
- `*/images/icon.png` — 512×512
- `*/images/featureGraphic.png` — 1024×500
- `*/images/phoneScreenshots/` — Roborazzi phone captures (also mirrored under `docs/screenshots/`)
- Privacy policy: [`docs/PRIVACY.md`](../../../docs/PRIVACY.md)
- F-Droid draft: [`metadata/dev.velum.app.yml`](../../../metadata/dev.velum.app.yml)
- Signed AAB: tag `v*` → `.github/workflows/release.yml` (needs `VELUM_UPLOAD_*` secrets)

```bash
./gradlew :composeApp:recordRoborazziDebug
./scripts/package-screenshots.sh
```
