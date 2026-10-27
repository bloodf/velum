# Marketing site screenshots

Real Compose UI captures for the Velum marketing site (English, no Plaud branding).

| File | Source |
|------|--------|
| `01-desktop-*.png` | Compose Desktop `ImageComposeScene` @ 2880×1800 (1440×900 @2x) |
| `06-mobile-*.png`, `07*.png`, `08-*.png` | Roborazzi phone (`w360dp-h800dp` @ xxhdpi ≈ 1080×2400) |

```bash
./gradlew :composeApp:recordRoborazziDebug --tests '*SiteScreenshotsTest'
./gradlew :composeApp:desktopTest --tests '*SiteDesktopScreenshotsTest'
./scripts/package-site-screenshots.sh
```
