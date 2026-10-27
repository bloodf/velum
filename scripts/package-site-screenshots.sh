#!/usr/bin/env bash
# Collect marketing site screenshots into docs/site-screenshots/ and zip them.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/site-screenshots"
ROBO="$ROOT/composeApp/build/outputs/roborazzi"
DESK="$ROOT/composeApp/build/outputs/site-desktop"

mkdir -p "$OUT"

copy_if() {
  local src="$1" dest="$2"
  if [[ -f "$src" ]]; then
    cp "$src" "$OUT/$dest"
    echo "ok  $dest"
  else
    echo "miss $src" >&2
  fi
}

# Mobile / import (Roborazzi)
copy_if "$ROBO/site-06-mobile-library.png" "06-mobile-library.png"
copy_if "$ROBO/site-06-mobile-library-dark.png" "06-mobile-library-dark.png"
copy_if "$ROBO/site-07a-mobile-recording.png" "07a-mobile-recording.png"
copy_if "$ROBO/site-07b-mobile-note.png" "07b-mobile-note.png"
copy_if "$ROBO/site-08-plaud-import.png" "08-plaud-import.png"

# Desktop (ImageComposeScene harness)
copy_if "$DESK/01-desktop-library.png" "01-desktop-library.png"
copy_if "$DESK/01-desktop-library-dark.png" "01-desktop-library-dark.png"
copy_if "$DESK/02-desktop-recording.png" "02-desktop-recording.png"
copy_if "$DESK/03-desktop-transcript.png" "03-desktop-transcript.png"
copy_if "$DESK/04-desktop-ai-note.png" "04-desktop-ai-note.png"
copy_if "$DESK/05-desktop-search-ask.png" "05-desktop-search-ask.png"

# Zip of whatever is present
(
  cd "$ROOT/docs"
  rm -f site-screenshots.zip
  zip -r -q site-screenshots.zip site-screenshots -x '*.DS_Store'
)
echo "zip docs/site-screenshots.zip ($(ls "$OUT" | wc -l) pngs)"

cat > "$OUT/README.md" <<'MD'
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
MD
