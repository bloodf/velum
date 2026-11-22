#!/usr/bin/env python3
"""Write WebP variants of the website screenshots for responsive <picture> sources.

    python3 scripts/site-screenshot-variants.py [website/public/screenshots]

For every PNG: `<name>.webp` at full size and `<name>-sm.webp` at half width (desktop 2880 -> 1440, phone 1080 -> 540).
The PNGs stay as the fallback and for the README. Needs Pillow (`pip install pillow`). Output is deterministic for a
given input and Pillow version, so unchanged screenshots produce unchanged files.
"""
import sys
from pathlib import Path

from PIL import Image

QUALITY = 82


def main() -> int:
    folder = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "website/public/screenshots")
    pngs = sorted(folder.glob("*.png"))
    if not pngs:
        print(f"no PNGs in {folder}", file=sys.stderr)
        return 1
    for png in pngs:
        with Image.open(png) as image:
            rgb = image.convert("RGB")
            rgb.save(png.with_suffix(".webp"), "WEBP", quality=QUALITY, method=6)
            half = rgb.resize((rgb.width // 2, rgb.height // 2), Image.LANCZOS)
            half.save(png.with_name(png.stem + "-sm.webp"), "WEBP", quality=QUALITY, method=6)
        print(f"ok {png.name}: {png.stem}.webp, {png.stem}-sm.webp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
