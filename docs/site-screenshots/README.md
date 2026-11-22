# Site screenshots

Marketing captures for the Velum website, rendered from the **real shared Compose UI** with rich English demo data (`MarketingDemoData`: ten finished recordings from ordinary work and study plus one in progress, no product or engineering jargon; each gets a quiet demo WAV so the players look real). No Plaud logos or branding. No stub / fake static screens.

## Generate

```bash
# Mobile + import (1080×2400)
./gradlew :composeApp:recordRoborazziDebug --tests '*SiteScreenshotsTest'

# Desktop adaptive (1440×900 @2x → 2880×1800)
./gradlew :composeApp:desktopTest --tests '*SiteDesktopScreenshotsTest'

./scripts/package-site-screenshots.sh   # also copies them to website/public/screenshots and writes the WebP variants
```

## Files

| File | Description |
|------|-------------|
| `01-desktop-library.png` | Wide library: nav rail + the full, unfiltered list + the note of the selected recording |
| `01-desktop-library-dark.png` | Dark variant of desktop library |
| `02-desktop-recording.png` | Live waveform, timer, Pause/Mark/Stop controls |
| `03-desktop-transcript.png` | Speakers, timestamps, playback bar, multi-paragraph transcript |
| `04-desktop-ai-note.png` | Summary, key points, decisions, action items with owners |
| `05-desktop-search-ask.png` | Ask across recordings: question, answer, cited timestamps |
| `06-mobile-library.png` | Phone library with completed notes |
| `06-mobile-library-dark.png` | Dark variant of mobile library |
| `07a-mobile-recording.png` | Live recording (waveform, timer, controls) |
| `07b-mobile-note.png` | AI note / summary with owners |
| `08-plaud-import.png` | Import hub / source selection flow |

Desktop frames are Compose Desktop `ImageComposeScene`. Mobile frames are Roborazzi (`w360dp × h800dp @ xxhdpi`).
