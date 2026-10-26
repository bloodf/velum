# Velum brand assets

Black & white 3D brand kit matching the chosen app icon. **Keep originals**; web-optimized and exact-size exports live beside them.

## Layout

| Path | Purpose |
|------|---------|
| `originals/` | Unmodified masters from the brand zip |
| `*.jpg` / `velum-v.svg` | Same masters at `docs/brand/` root (easy README links) |
| `web/` | PNG + WebP (and SVG) for docs / sites |
| `sized/` | Exact listing sizes (GitHub social, Play feature, README banner) |

## Masters

| File | Use |
|------|-----|
| `icon-3d-light.jpg` | Default launcher (black V on white). Android home screen always uses this. |
| `icon-3d-dark.jpg` | Dark-theme in-app mark / marketing |
| `splash-light.jpg` / `splash-dark.jpg` | Android splash (A12+ icon + window background) and first-run / About hero |
| `wordmark-light.jpg` | About dialog wordmark (V + “Velum”) |
| `readme-banner.jpg` | GitHub README header |
| `social-preview-dark.jpg` | GitHub repository social preview source |
| `play-feature-graphic.jpg` | Play Store feature graphic source |
| `velum-v.svg` | Flat V → Android monochrome / notification VectorDrawable |

## Exact sizes (`sized/`)

| File | Spec |
|------|------|
| `github-social-preview-1280x640.{png,webp,jpg}` | GitHub Open Graph (**upload manually** in repo Settings → Social preview) |
| `play-feature-graphic-1024x500.{png,webp,jpg}` | Google Play feature graphic |
| `readme-banner-1600.{png,webp,jpg}` | README banner (~1600 px wide) |
| `splash-{light,dark}-icon-1024.png` | Center crop of splash art for splash icon tooling |

## Dark / light usage

- **Launcher:** light icon only (platform limitation).
- **Splash / hero / About art:** follow system theme (`splash-light` / `splash-dark`).
- **Wordmark:** light master in About (high-contrast on both themes when placed on a surface card).

Store listing copies also live under `fastlane/metadata/android/`.
