#!/usr/bin/env python3
"""Render res/raw/tab_*.json Lottie tab icons to a PNG contact sheet with rlottie.

Uses the same renderer the app uses (rlottie), so even-odd fill rules, path morphs and
layer in/out points are evaluated exactly as on device -- unlike eyeballing the JSON.

    pip3 install rlottie-python pillow
    python3 scripts/preview_tab_icon.py tab_video tab_search

Output: /tmp/tab_icon_preview.png  (one row per icon, one column per frame,
        white glyph on the dark glass-ish background the real tab bar uses)
"""

import os
import sys

from PIL import Image, ImageDraw
from rlottie_python import LottieAnimation

CELL = 96
PAD = 8
BG = (26, 26, 28, 255)
RAW = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "TMessagesProj", "src", "main", "res", "raw",
)


def render(name):
    path = os.path.join(RAW, name + ".json")
    anim = LottieAnimation.from_file(path)
    total = anim.lottie_animation_get_totalframe()
    frames = []
    for f in range(total + 1):
        try:
            img = anim.render_pillow_frame(frame_num=f, width=CELL, height=CELL)
        except Exception:
            break
        frames.append((f, img.convert("RGBA")))
    return frames


def main():
    names = sys.argv[1:] or ["tab_video"]
    rows = [(n, render(n)) for n in names]
    cols = max(len(f) for _, f in rows)
    W = PAD + cols * (CELL + PAD)
    H = PAD + len(rows) * (CELL + PAD + 14)
    sheet = Image.new("RGBA", (W, H), BG)
    draw = ImageDraw.Draw(sheet)
    for r, (name, frames) in enumerate(rows):
        y = PAD + r * (CELL + PAD + 14)
        for c, (fnum, img) in enumerate(frames):
            x = PAD + c * (CELL + PAD)
            sheet.alpha_composite(img, (x, y))
            draw.text((x + 2, y + CELL + 1), "%s f%d" % (name, fnum), fill=(150, 150, 155))
    out = "/tmp/tab_icon_preview.png"
    sheet.save(out)
    print("wrote %s  (%dx%d, %d icons)" % (out, W, H, len(rows)))


if __name__ == "__main__":
    main()
