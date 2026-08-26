"""The phone mock: every screen the promo shows, drawn rather than recorded.

Recording the real app was the obvious route and the wrong one. It would have put a real account's
chats, real channel names and real people's faces into an advert, and it would have looked slightly
different every take. Everything here is drawn from code instead, so nothing real leaks, the timing
is exact, and changing a caption is one line rather than another twenty minutes of screen capture.

What IS real: the Svipe and Telegram marks. Nothing else needs to be, and nothing else is.
"""
from __future__ import annotations

import math
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1080, 1920
FPS = 30

# --- palette: the app's own dark theme -------------------------------------------------------
BG = (16, 20, 26)
SURFACE = (23, 29, 37)
SURFACE_2 = (30, 38, 48)
LINE = (44, 54, 66)
FG = (233, 239, 246)
MUTED = (140, 154, 170)
FAINT = (92, 104, 120)
ACCENT = (47, 129, 247)
TG_BLUE = (42, 171, 238)
RED = (240, 92, 84)

FONT_PATH = "/System/Library/Fonts/SFNS.ttf"
FONT_BOLD = "/System/Library/Fonts/SFNS.ttf"

_font_cache: dict[tuple[int, int], ImageFont.FreeTypeFont] = {}


def font(size: int, weight: int = 400) -> ImageFont.FreeTypeFont:
    key = (size, weight)
    if key not in _font_cache:
        f = ImageFont.truetype(FONT_PATH, size)
        try:                         # SFNS is variable; ask for the weight axis when available
            f.set_variation_by_axes([weight])
        except Exception:
            pass
        _font_cache[key] = f
    return _font_cache[key]


def ease(t: float) -> float:
    """Ease-in-out. Linear motion reads as a machine moving something; this reads as a hand."""
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def ease_out(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def mix(c1, c2, t: float):
    return tuple(int(lerp(c1[i], c2[i], t)) for i in range(3))


def rr(d: ImageDraw.ImageDraw, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def text_w(s: str, f) -> int:
    return int(f.getbbox(s)[2] - f.getbbox(s)[0])


def centered(d, y, s, f, fill):
    d.text(((W - text_w(s, f)) / 2, y), s, font=f, fill=fill)


# ---------------------------------------------------------------------------------------------
# Fake content. Names invented, colours deterministic — nothing here belongs to anybody.
# ---------------------------------------------------------------------------------------------

AVATAR_COLORS = [(47, 129, 247), (23, 176, 232), (155, 123, 255), (244, 114, 182),
                 (45, 212, 191), (234, 181, 74), (46, 203, 127), (242, 96, 79)]

CHATS = [
    ("Kamila", "see you at 7 ✨", "18:24", 2),
    ("Design team", "Bekzod: pushed the new icons", "17:03", 0),
    ("Doniyor", "sent a video", "16:41", 0),
    ("Football club", "match moved to Sunday", "15:12", 5),
    ("Saved Messages", "svipe.uz", "14:58", 0),
    ("Nilufar", "😂😂", "12:30", 0),
]


@lru_cache(maxsize=64)
def _gradient_cached(size, c1, c2, angle=90):
    w, h = size
    base = Image.new("RGB", (w, h), c1)
    top = Image.new("RGB", (w, h), c2)
    mask = Image.new("L", (w, h))
    md = ImageDraw.Draw(mask)
    for i in range(h):
        md.line([(0, i), (w, i)], fill=int(255 * i / max(1, h - 1)))
    if angle == 0:
        mask = mask.rotate(90, expand=False)
    base.paste(top, (0, 0), mask)
    return base


def gradient(size, c1, c2, angle=90):
    """A soft two-stop gradient — stands in for a video frame without being one.

    Always a COPY of the cached one. Handing the cached image itself out was a real bug and an
    instructive one: every scene draws its sun and its mountains straight onto the sky it was given,
    so each variant painted onto the same object and the frames ended up with two suns in them.
    A cache of mutable images is only safe if nobody can reach the original.
    """
    return _gradient_cached(size, c1, c2, angle).copy()


CLIP_TINTS = [
    ((28, 44, 92), (86, 44, 128)),
    ((16, 72, 84), (24, 40, 96)),
    ((92, 36, 60), (40, 22, 72)),
    ((20, 60, 54), (12, 28, 60)),
]

VIDEO_TINTS = [
    ((30, 52, 96), (70, 40, 110)), ((18, 70, 82), (26, 42, 92)),
    ((88, 40, 58), (44, 24, 70)),  ((22, 62, 52), (14, 30, 62)),
    ((60, 48, 100), (24, 34, 72)), ((36, 66, 76), (18, 36, 70)),
]


def _sky(size, top, bottom):
    return gradient(size, top, bottom)


@lru_cache(maxsize=64)
def fake_footage(i: int, size, variant: int = 0) -> Image.Image:
    """A frame that reads as VIDEO rather than as a coloured rectangle.

    The first cut used plain gradients and the advert looked like it had no content in it — which is
    a strange thing for an advert about content. These are flat illustrations, deliberately simple:
    at the size they are actually seen (a thumbnail, or behind a caption) a horizon, a skyline or a
    plate is enough for the eye to file it as footage and move on to the thing being demonstrated.

    Nothing here depicts anything real. That is the point.
    """
    w, h = size
    k = i % 4
    if k == 0:                                   # dawn over water
        img = _sky(size, (38, 52, 104), (232, 150, 118))
        d = ImageDraw.Draw(img, "RGBA")
        sunx = (0.60, 0.24, 0.44)[variant % 3]
        d.ellipse([w * sunx, h * 0.34, w * sunx + w * 0.20, h * 0.34 + w * 0.20],
                  fill=(255, 226, 178))
        for j, (yy, col) in enumerate(((0.52, (46, 40, 72)), (0.56, (32, 28, 54)))):
            pts = [(0, h * (yy + 0.10))]
            for x in range(0, w + 1, w // 6):
                pts.append((x, h * yy - (h * 0.05 if (x // (w // 6) + j) % 2 else h * 0.015)))
            pts += [(w, h * (yy + 0.10))]
            d.polygon(pts, fill=col)
        d.rectangle([0, h * 0.60, w, h], fill=(28, 40, 78))
        for j in range(9):                        # broken reflection, under the sun
            yy = h * (0.63 + j * 0.035)
            d.line([(w * (sunx + 0.06), yy), (w * (sunx + 0.06) + w * 0.14 * (1 - j / 12), yy)],
                   fill=(255, 214, 170, 150 - j * 14), width=max(2, int(h * 0.006)))
    elif k == 1:                                 # city at dusk
        img = _sky(size, (18, 26, 52), (86, 52, 96))
        d = ImageDraw.Draw(img, "RGBA")
        moonx = (0.16, 0.68, 0.40)[variant % 3]
        d.ellipse([w * moonx, h * 0.20, w * moonx + w * 0.10, h * 0.20 + w * 0.10],
                  fill=(238, 236, 222))
        x = 0
        widths = [0.13, 0.09, 0.17, 0.11, 0.15, 0.10, 0.14, 0.12]
        heights = [0.30, 0.44, 0.24, 0.38, 0.20, 0.34, 0.28, 0.40]
        heights = heights[variant % 4:] + heights[:variant % 4]
        for bw, bh in zip(widths, heights):
            bx, by = x * w, h * (1 - bh)
            d.rectangle([bx, by, bx + bw * w, h], fill=(20, 24, 44))
            for row in range(int(bh * 12)):
                for col in range(3):
                    if (row * 3 + col + int(bw * 100)) % 4:
                        wx = bx + bw * w * (0.2 + col * 0.28)
                        wy = by + h * 0.03 + row * h * 0.028
                        d.rectangle([wx, wy, wx + w * 0.018, wy + h * 0.012],
                                    fill=(255, 214, 140, 190))
            x += bw
    elif k == 2:                                 # a plate, from above
        img = _sky(size, (48, 26, 30), (24, 16, 22))
        d = ImageDraw.Draw(img, "RGBA")
        cx, cy, r = w * 0.5, h * 0.5, min(w, h) * 0.30
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(238, 232, 222))
        d.ellipse([cx - r * 0.82, cy - r * 0.82, cx + r * 0.82, cy + r * 0.82], fill=(222, 214, 202))
        d.ellipse([cx - r * 0.55, cy - r * 0.5, cx + r * 0.45, cy + r * 0.55], fill=(196, 108, 58))
        d.ellipse([cx - r * 0.18, cy - r * 0.30, cx + r * 0.22, cy + r * 0.10], fill=(236, 196, 96))
        for j in range(3):                        # steam
            sx = cx - r * 0.3 + j * r * 0.3
            d.arc([sx - 40, cy - r - 150, sx + 40, cy - r + 10], 200, 340,
                  fill=(255, 255, 255, 70), width=8)
    else:                                        # road ahead
        img = _sky(size, (24, 40, 78), (150, 176, 200))
        d = ImageDraw.Draw(img, "RGBA")
        d.rectangle([0, h * 0.52, w, h], fill=(46, 50, 58))
        d.polygon([(w * 0.5, h * 0.52), (w * 1.25, h), (w * -0.25, h)], fill=(58, 62, 70))
        for j in range(6):                        # dashes, narrowing to the horizon
            t0 = j / 6.0
            yy = h * (0.56 + t0 * t0 * 0.44)
            ww = w * (0.010 + t0 * 0.030)
            hh = h * (0.010 + t0 * 0.030)
            d.rectangle([w * 0.5 - ww, yy, w * 0.5 + ww, yy + hh], fill=(226, 226, 216))
        d.rectangle([0, h * 0.40, w, h * 0.52], fill=(70, 92, 78))
    return img


#: Which illustrations survive FULL-BLEED, and it is only the two with a horizon. The plate is shot
#: from overhead — at thumbnail size it is food, filling a phone it is a circle inside a circle. The
#: road is drawn in perspective, and stretched to 9:16 the perspective flattens into stripes. Both
#: still earn their place in the video grid, where they are seen landscape and small.
#: Repeats are varied instead of hidden: same scene, different composition.
CLIP_FOOTAGE = ((0, 0), (1, 0), (0, 1), (1, 1))


@lru_cache(maxsize=16)
def clip_bg(i: int, size=(W, H)) -> Image.Image:
    """The footage for one clip. NO scrim — see clip_scrim."""
    kind, variant = CLIP_FOOTAGE[i % len(CLIP_FOOTAGE)]
    return fake_footage(kind, size, variant)


@lru_cache(maxsize=2)
def _scrim(size) -> Image.Image:
    w, h = size
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for y in range(h):
        f = y / h
        if f < 0.14:                       # a touch under the status bar
            a = int(80 * (1 - f / 0.14))
        elif f < 0.50:
            a = 0
        else:
            # Near-solid by the channel line at ~0.62h: the sun's reflection on the water is bright
            # enough to read straight through a gentle ramp, and the text sat in the middle of it.
            a = int(235 * min(1.0, ((f - 0.50) / 0.14) ** 1.2))
        if a:
            od.line([(0, y), (w, y)], fill=(0, 0, 0, a))
    return overlay


def clip_scrim(img: Image.Image) -> Image.Image:
    """Darken the bottom of the SCREEN, once, after everything is in place.

    Baked into each clip instead, it followed the clip during a swipe — so the outgoing frame slid
    up showing its own near-black bottom across the top of the screen, and a 0.55s transition read
    as a black wipe rather than as one video giving way to the next.
    """
    return Image.alpha_composite(img.convert("RGBA"), _scrim(img.size)).convert("RGB")


# ---------------------------------------------------------------------------------------------
# Chrome: status bar and the app's own tab bar
# ---------------------------------------------------------------------------------------------

TABS = ["Chats", "Clips", "Video", "Music", "Profile"]
TAB_BAR_H = 150


def status_bar(d: ImageDraw.ImageDraw, dark=True):
    c = FG if dark else (20, 20, 20)
    d.text((64, 52), "9:41", font=font(38, 600), fill=c)
    # signal / wifi / battery, drawn rather than iconographic — nobody reads these, they anchor.
    x = W - 220
    for i, h in enumerate((14, 20, 26, 32)):
        d.rounded_rectangle([x + i * 16, 74 - h, x + i * 16 + 10, 74], radius=3, fill=c)
    d.rounded_rectangle([W - 128, 46, W - 68, 76], radius=8, outline=c, width=3)
    d.rounded_rectangle([W - 124, 50, W - 86, 72], radius=5, fill=c)


def _tab_icon(d, cx, cy, name, col):
    s = 30
    if name == "Chats":
        d.rounded_rectangle([cx - s, cy - s + 4, cx + s, cy + s - 10], radius=12, outline=col, width=5)
        d.polygon([(cx - 10, cy + s - 12), (cx + 6, cy + s - 12), (cx - 4, cy + s + 6)], fill=col)
    elif name == "Clips":
        d.ellipse([cx - s, cy - s, cx + s, cy + s], outline=col, width=5)
        d.polygon([(cx - 8, cy - 14), (cx + 16, cy), (cx - 8, cy + 14)], fill=col)
    elif name == "Video":
        d.rounded_rectangle([cx - s - 4, cy - s + 6, cx + s + 4, cy + s - 6], radius=12, outline=col, width=5)
        d.polygon([(cx - 8, cy - 14), (cx + 16, cy), (cx - 8, cy + 14)], fill=col)
    elif name == "Music":
        d.ellipse([cx - s + 2, cy + 4, cx - s + 26, cy + 28], fill=col)
        d.ellipse([cx + 6, cy - 6, cx + 30, cy + 18], fill=col)
        d.line([(cx - s + 24, cy + 16), (cx - s + 24, cy - s + 4)], fill=col, width=5)
        d.line([(cx + 28, cy + 6), (cx + 28, cy - s - 6)], fill=col, width=5)
        d.line([(cx - s + 24, cy - s + 4), (cx + 28, cy - s - 6)], fill=col, width=5)
    else:  # Profile
        d.ellipse([cx - 16, cy - s + 2, cx + 16, cy - s + 34], outline=col, width=5)
        d.arc([cx - s, cy - 4, cx + s, cy + s + 26], 200, 340, fill=col, width=5)


def tab_bar(img: Image.Image, active: int, highlight: float = 0.0):
    """The app's real five-tab bar. Shown in full on purpose — hiding a tab would be the lie."""
    d = ImageDraw.Draw(img, "RGBA")
    y0 = H - TAB_BAR_H
    d.rectangle([0, y0, W, H], fill=(18, 23, 30, 255))
    d.line([(0, y0), (W, y0)], fill=LINE, width=2)
    step = W / len(TABS)
    for i, name in enumerate(TABS):
        cx = int(step * (i + 0.5))
        on = i == active
        col = ACCENT if on else FAINT
        if on and highlight > 0:
            rr(d, [cx - 86, y0 + 18, cx + 86, y0 + 118], 26,
               fill=(47, 129, 247, int(38 * highlight)))
        _tab_icon(d, cx, y0 + 52, name, col)
        f = font(26, 600 if on else 500)
        d.text((cx - text_w(name, f) / 2, y0 + 92), name, font=f, fill=col)


def finger(img: Image.Image, x: float, y: float, alpha: float = 1.0, trail: float = 0.0):
    """A ghost touch point. The video is silent, so the gesture has to be visible, not implied."""
    if alpha <= 0.01:
        return
    d = ImageDraw.Draw(img, "RGBA")
    a = int(255 * alpha)
    if trail:
        d.ellipse([x - 34, y - 34 + trail, x + 34, y + 34 + trail], fill=(255, 255, 255, int(a * 0.10)))
    d.ellipse([x - 62, y - 62, x + 62, y + 62], fill=(255, 255, 255, int(a * 0.16)))
    d.ellipse([x - 34, y - 34, x + 34, y + 34], fill=(255, 255, 255, int(a * 0.55)))
    d.ellipse([x - 34, y - 34, x + 34, y + 34], outline=(255, 255, 255, a), width=3)


#: Every caption lives in this band and nowhere else. The first cut placed them per-scene and they
#: landed on top of whatever the UI had there — like counts, grid titles, a clip's own caption. A
#: fixed band means the screens can be built to keep it clear, instead of each one being a surprise.
BAND_TOP = H - TAB_BAR_H - 360


def caption(img: Image.Image, lines, t: float, sub=None, band_top=None):
    """The headline for a scene. Silent video: if it is not on screen, it was not said.

    Nearly opaque, not a tint. A scrim you can read the interface through looks like a mistake, and
    the caption is the one thing in a soundless advert that must be legible in the first glance.
    """
    if t <= 0:
        return
    a = ease_out(min(1.0, t / 0.35))
    d = ImageDraw.Draw(img, "RGBA")
    f = font(64, 700)
    fs = font(38, 500)
    lh = 84
    y0 = (BAND_TOP if band_top is None else band_top) + 46
    block_h = lh * len(lines) + (56 if sub else 0)
    pad = 42
    rr(d, [48, y0 - pad, W - 48, y0 + block_h + pad - 12], 36, fill=(8, 11, 16, int(238 * a)))
    yy = y0 + int(18 * (1 - a))
    for ln in lines:
        d.text(((W - text_w(ln, f)) / 2, yy), ln, font=f, fill=(*FG, int(255 * a)))
        yy += lh
    if sub:
        d.text(((W - text_w(sub, fs)) / 2, yy + 8), sub, font=fs, fill=(*MUTED, int(240 * a)))
