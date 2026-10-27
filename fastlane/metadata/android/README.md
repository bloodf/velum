# Android store metadata (Play / F-Droid)

Fastlane supply / F-Droid-compatible layout.

- `en-US/`, `pt-BR/` — localized title, short/full description, changelogs
- `*/images/icon.png` — 512×512
- `*/images/featureGraphic.png` — 1024×500
- `*/images/phoneScreenshots/` — Roborazzi phone captures (also mirrored under `docs/screenshots/`)

```bash
./gradlew :composeApp:recordRoborazziDebug
./scripts/package-screenshots.sh
```
