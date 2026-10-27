#!/usr/bin/env bash
# Copy Roborazzi captures into docs/screenshots/{light,dark}/ and fastlane phoneScreenshots.
# Optionally build B&W framed hero variants for the README.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/composeApp/build/outputs/roborazzi"
LIGHT="$ROOT/docs/screenshots/light"
DARK="$ROOT/docs/screenshots/dark"
FRAMED="$ROOT/docs/screenshots/framed"
EN="$ROOT/fastlane/metadata/android/en-US/images/phoneScreenshots"
PT="$ROOT/fastlane/metadata/android/pt-BR/images/phoneScreenshots"

if [[ ! -d "$SRC" ]]; then
  echo "Missing $SRC — run: ./gradlew :composeApp:recordRoborazziDebug" >&2
  exit 1
fi

mkdir -p "$LIGHT" "$DARK" "$FRAMED" "$EN" "$PT"
rm -f "$LIGHT"/*.png "$DARK"/*.png "$FRAMED"/*.png "$EN"/*.png "$PT"/*.png

# Map Roborazzi names → gallery names (light unless dark-*).
declare -A LIGHT_MAP=(
  [velum-01-onboarding.png]=01-onboarding.png
  [velum-02-home-recordings.png]=02-home-recordings.png
  [velum-03-fab-overlay.png]=03-fab-overlay.png
  [velum-04-recording-in-progress.png]=04-recording-in-progress.png
  [velum-05-device-connected-wifi.png]=05-device-connected-wifi.png
  [velum-06-import-hub.png]=06-import-hub.png
  [velum-07-transcript-speakers.png]=07-transcript-speakers.png
  [velum-08-summary-template.png]=08-summary-template.png
  [velum-09-name-speakers.png]=09-name-speakers.png
  [velum-10-generate-sheet.png]=10-generate-sheet.png
  [velum-11-template-picker.png]=11-template-picker.png
  [velum-12-voice-profiles.png]=12-voice-profiles.png
  [velum-13-ai-processing.png]=13-ai-processing.png
  [velum-14-explore.png]=14-explore.png
  [velum-15-autoflow.png]=15-autoflow.png
  [velum-16-integrations.png]=16-integrations.png
  [velum-17-settings.png]=17-settings.png
  [velum-18-model-manager.png]=18-model-manager.png
  [velum-19-mind-map.png]=19-mind-map.png
  [velum-20-brand-launcher-light.png]=20-brand-launcher-light.png
)

declare -A DARK_MAP=(
  [velum-dark-02-home-recordings.png]=02-home-recordings.png
  [velum-dark-05-device-connected-wifi.png]=05-device-connected-wifi.png
  [velum-dark-07-transcript-speakers.png]=07-transcript-speakers.png
  [velum-dark-08-summary-template.png]=08-summary-template.png
  [velum-dark-13-ai-processing.png]=13-ai-processing.png
  [velum-21-brand-splash-dark.png]=21-brand-splash-dark.png
)

for src in "${!LIGHT_MAP[@]}"; do
  if [[ -f "$SRC/$src" ]]; then
    cp "$SRC/$src" "$LIGHT/${LIGHT_MAP[$src]}"
  else
    echo "warn: missing $src" >&2
  fi
done

for src in "${!DARK_MAP[@]}"; do
  if [[ -f "$SRC/$src" ]]; then
    cp "$SRC/$src" "$DARK/${DARK_MAP[$src]}"
  else
    echo "warn: missing $src" >&2
  fi
done

# Fastlane store shots (Play allows up to 8 phone screenshots) — pick the strongest light set.
FASTLANE_ORDER=(
  01-onboarding.png
  02-home-recordings.png
  04-recording-in-progress.png
  05-device-connected-wifi.png
  07-transcript-speakers.png
  08-summary-template.png
  11-template-picker.png
  13-ai-processing.png
)
i=1
for name in "${FASTLANE_ORDER[@]}"; do
  if [[ -f "$LIGHT/$name" ]]; then
    printf -v dest "%02d_%s" "$i" "$name"
    cp "$LIGHT/$name" "$EN/$dest"
    cp "$LIGHT/$name" "$PT/$dest"
    i=$((i + 1))
  fi
done

# B&W 3D-style framed heroes for README (charcoal bezel, soft vignette).
python3 - <<'PY'
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

root = Path("/workspace")
light = root / "docs/screenshots/light"
framed = root / "docs/screenshots/framed"
framed.mkdir(parents=True, exist_ok=True)

heroes = [
    "01-onboarding.png",
    "02-home-recordings.png",
    "07-transcript-speakers.png",
    "08-summary-template.png",
    "05-device-connected-wifi.png",
]

# Target phone aspect ~9:19.5; bezel chrome in B&W brand.
W, H = 1080, 2340
BEZEL = 36
RADIUS = 72
BG = (18, 18, 18)
CHROME = (245, 245, 245)

def round_paste(base, img, box, radius):
    img = img.convert("RGBA")
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((0, 0, img.size[0], img.size[1]), radius=radius, fill=255)
    base.paste(img, box, mask)

for name in heroes:
    src = light / name
    if not src.exists():
        continue
    shot = Image.open(src).convert("RGB")
    # Fit into inner area
    inner_w, inner_h = W - BEZEL * 2, H - BEZEL * 2 - 40
    shot = ImageOps.fit(shot, (inner_w, inner_h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (W, H), BG)
    # Outer chrome plate
    plate = Image.new("RGB", (W - 16, H - 16), CHROME)
    canvas.paste(plate, (8, 8))
    # Inner charcoal body
    body = Image.new("RGB", (W - 28, H - 28), BG)
    canvas.paste(body, (14, 14))
    # Screen
    round_paste(canvas, shot, (BEZEL, BEZEL + 20), RADIUS - 12)
    # Subtle top speaker bar
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(
        (W // 2 - 80, 22, W // 2 + 80, 38),
        radius=8,
        fill=(40, 40, 40),
    )
    out = framed / f"framed-{name}"
    canvas.save(out, "PNG", optimize=True)
    print("framed", out.name)
PY

# Gallery index
cat > "$ROOT/docs/screenshots/README.md" <<'MD'
# App screenshots

Captured from the real Compose UI via Roborazzi (`w360dp × h800dp @ xxhdpi` ≈ 1080×2400).

```bash
./gradlew :composeApp:recordRoborazziDebug
./scripts/package-screenshots.sh
```

| Folder | Contents |
|--------|----------|
| `light/` | Light-theme product shots |
| `dark/` | Dark variants of key screens |
| `framed/` | Optional B&W 3D-style phone frames for README heroes |

No Plaud marketing art — Velum UI only, seeded with realistic demo recordings.
MD

echo "Packaged light=$(ls "$LIGHT" | wc -l) dark=$(ls "$DARK" | wc -l) framed=$(ls "$FRAMED" | wc -l) fastlane=$((i-1))"
