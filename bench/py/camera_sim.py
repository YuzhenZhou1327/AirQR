"""Camera-view simulation: what does the phone's camera actually see?

Models: desktop screen (e.g. 1920px wide, ~31.5" diag ≈ 0.70m wide) viewed by
a phone camera at distance D. Phone preview width W_preview px covers the
screen width. Compute pixels-per-QR-module at various (grid, version, distance,
preview-resolution) combos, then actually downscale the rendered PNG with
nearest sampling (camera-realistic) and attempt zxing decode.
"""
import sys
from pathlib import Path

import numpy as np
import zxingcpp
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))

# physical screen: common 24" 1080p ≈ 0.53m wide; 27" 4K ≈ 0.60m wide; 31.5" 4K ≈ 0.70m wide
SCREEN_W_M = {1080: 0.53, 2160: 0.70}

PHONE_PREVIEW_W = [1440, 1920, 2560]  # typical camera preview widths
DISTANCES = [0.3, 0.4, 0.6]

# camera FOV: typical phone horizontal ~65°
import math
FOV_H = math.radians(65)

def screen_fraction(width_m, dist):
    """fraction of preview width occupied by the screen at distance dist."""
    view_w = 2 * dist * math.tan(FOV_H / 2)
    return min(1.0, width_m / view_w)

def simulate(png_path, screen_px_w, dist, preview_w):
    img = Image.open(png_path).convert("L")
    sw, sh = img.size
    width_m = SCREEN_W_M[screen_px_w]
    frac = screen_fraction(width_m, dist)
    # screen occupies frac of preview width
    target_w = int(preview_w * frac)
    scale = target_w / sw
    small = img.resize((max(1, int(sw * scale)), max(1, int(sh * scale))), Image.NEAREST)
    results = zxingcpp.read_barcodes(small)
    return len(results), scale

def main():
    combos = [
        ("temp/sim-g1v40/p000-f00000.png", 2160, "grid1 v40"),
        ("temp/sim-g2v40/p000-f00000.png", 2160, "grid2 v40"),
        ("temp/sim-g2v25/p000-f00000.png", 2160, "grid2 v25"),
        ("temp/sim-g4v25/p000-f00000.png", 2160, "grid4 v25"),
    ]
    print(f"{'case':<12} {'dist':>5} {'prevW':>6} {'px/module':>9} {'codes decoded':>14}")
    for path, screen_px, name in combos:
        for dist in DISTANCES:
            for pw in PHONE_PREVIEW_W:
                n, scale = simulate(Path(__file__).parent.parent / ".." / path if not Path(path).exists() else path, screen_px, dist, pw)
                # px/module: v40 card = 185 modules wide; grid2 → each card gets (target_w - gap)/2
                print(f"{name:<12} {dist:>5.1f} {pw:>6} {'':>9} {n:>14}")

if __name__ == "__main__":
    main()
