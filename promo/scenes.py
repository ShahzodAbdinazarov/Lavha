"""The five screens, each drawn for an arbitrary moment in its own scene.

Every function takes a local time in seconds and returns a finished 1080x1920 frame. Nothing is
stateful: frame 431 can be rendered on its own, which is what makes the whole thing re-runnable and
a caption change cheap.
"""
from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter

from ui import (ACCENT, BAND_TOP, clip_scrim, fake_footage, AVATAR_COLORS, BG, CHATS, FAINT, FG, H, LINE, MUTED, RED, SURFACE,
                SURFACE_2, TAB_BAR_H, TG_BLUE, W, VIDEO_TINTS, caption, centered, clip_bg, ease,
                ease_out, finger, font, gradient, lerp, mix, rr, status_bar, tab_bar, text_w)



# ---------------------------------------------------------------------------------------------
# Every word the film says, in one place. A second language is then a render flag rather than a
# second edit — and the two versions can never drift apart in timing, only in wording.
# ---------------------------------------------------------------------------------------------

STRINGS = {
    "en": {
        "unofficial_1": "An unofficial",
        "unofficial_2": "Telegram client",
        "unofficial_sub": "your account, a different app",
        "chats": "Your chats. Your account.",
        "clips": "Clips",
        "clips_sub_1": "every video on Telegram, in one feed",
        "clips_sub_2": "straight from public Telegram channels",
        "video": "Video",
        "video_sub": "long videos, the way you expect them",
        "up": "Swipe up",
        "up_sub": "full screen",
        "down": "Swipe down",
        "down_sub": "right back to the feed",
        "end_sub": "unofficial Telegram client",
        "clip_cap_0": "morning by the lake",
        "clip_cap_1": "old town, golden hour",
        "clip_cap_2": "the light before sunrise",
        "clip_cap_3": "rooftops after the rain",
    },
    "uz": {
        "unofficial_1": "Norasmiy",
        "unofficial_2": "Telegram klienti",
        "unofficial_sub": "akkauntingiz o‘sha, ilova boshqa",
        "chats": "Chatlaringiz. Akkauntingiz.",
        "clips": "Clips",
        "clips_sub_1": "Telegramdagi barcha videolar bitta lentada",
        "clips_sub_2": "to‘g‘ridan-to‘g‘ri ochiq Telegram kanallaridan",
        "video": "Video",
        "video_sub": "uzun videolar, o‘zingiz kutgandek",
        "up": "Tepaga suring",
        "up_sub": "to‘liq ekran",
        "down": "Pastga suring",
        "down_sub": "lentaga qaytdingiz",
        "end_sub": "norasmiy Telegram klienti",
        "clip_cap_0": "ko‘l bo‘yida tong",
        "clip_cap_1": "eski shahar, oltin soat",
        "clip_cap_2": "quyosh chiqishidan oldingi yorug‘lik",
        "clip_cap_3": "yomg‘irdan keyingi tomlar",
    },
}

LANG = "en"


def S(key: str) -> str:
    return STRINGS[LANG][key]


# ---------------------------------------------------------------------------------------------
# 1. Opening — the two marks, and the word that has to be there
# ---------------------------------------------------------------------------------------------

def telegram_mark(d, cx, cy, r, alpha=255):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(*TG_BLUE, alpha))
    s = r / 100.0
    plane = [(cx - 52 * s, cy + 6 * s), (cx + 56 * s, cy - 40 * s), (cx + 30 * s, cy + 44 * s),
             (cx + 4 * s, cy + 16 * s), (cx - 20 * s, cy + 34 * s), (cx - 16 * s, cy + 8 * s)]
    d.polygon(plane, fill=(255, 255, 255, alpha))


def svipe_mark(d, cx, cy, r, alpha=255):
    """Our own mark: the rounded square and the play triangle the app uses for its feed."""
    d.rounded_rectangle([cx - r, cy - r, cx + r, cy + r], radius=int(r * 0.42),
                        fill=(*ACCENT, alpha))
    s = r / 100.0
    d.polygon([(cx - 26 * s, cy - 44 * s), (cx + 46 * s, cy), (cx - 26 * s, cy + 44 * s)],
              fill=(255, 255, 255, alpha))


def scene_open(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), (10, 13, 18))
    d = ImageDraw.Draw(img, "RGBA")

    # Telegram alone, then it slides left and ours arrives beside it: the whole pitch in one move.
    split = ease(min(1.0, max(0.0, (t - 0.75) / 0.85)))
    gap = lerp(0, 200, split)
    a2 = int(255 * ease_out(min(1.0, max(0.0, (t - 0.95) / 0.6))))
    cy = 760
    telegram_mark(d, int(W / 2 - gap), cy, 132)
    if a2 > 4:
        svipe_mark(d, int(W / 2 + gap), cy, 132, a2)

    if split > 0.5:
        f = font(46, 500)
        s = "+"
        d.text(((W - text_w(s, f)) / 2, cy - 34), s, font=f, fill=(*FAINT, int(255 * split)))

    ta = ease_out(min(1.0, max(0.0, (t - 1.6) / 0.5)))
    if ta > 0:
        f = font(78, 700)
        centered(img.__class__ and d, 1060, S("unofficial_1"), f, (*FG, int(255 * ta)))
        centered(d, 1060 + 92, S("unofficial_2"), f, (*FG, int(255 * ta)))
        f2 = font(40, 500)
        centered(d, 1060 + 220, S("unofficial_sub"), f2, (*MUTED, int(210 * ta)))
    return img


# ---------------------------------------------------------------------------------------------
# 2. Chats — "this really is your Telegram"
# ---------------------------------------------------------------------------------------------

def draw_chats(img, scroll=0.0, dim=0.0):
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle([0, 0, W, H], fill=BG)
    status_bar(d)

    d.text((56, 150), "Svipe", font=font(64, 700), fill=FG)
    # search pill
    rr(d, [56, 254, W - 56, 254 + 96], 26, fill=SURFACE)
    d.ellipse([96, 286, 128, 318], outline=FAINT, width=4)
    d.line([(124, 314), (142, 332)], fill=FAINT, width=4)
    d.text((166, 282), "Search", font=font(38, 500), fill=FAINT)

    y = 396 - scroll
    for i, (name, msg, when, unread) in enumerate(CHATS):
        if y > H:
            break
        col = AVATAR_COLORS[i % len(AVATAR_COLORS)]
        d.ellipse([56, y, 56 + 108, y + 108], fill=col)
        d.text((56 + 54 - text_w(name[0], font(50, 700)) / 2, y + 26), name[0],
               font=font(50, 700), fill=(255, 255, 255))
        d.text((200, y + 14), name, font=font(42, 600), fill=FG)
        d.text((200, y + 68), msg, font=font(36, 400), fill=MUTED)
        d.text((W - 56 - text_w(when, font(32, 500)), y + 18), when, font=font(32, 500), fill=FAINT)
        if unread:
            s = str(unread)
            bw = max(56, text_w(s, font(32, 700)) + 34)
            rr(d, [W - 56 - bw, y + 62, W - 56, y + 62 + 56], 28, fill=ACCENT)
            d.text((W - 56 - bw / 2 - text_w(s, font(32, 700)) / 2, y + 72), s,
                   font=font(32, 700), fill=(255, 255, 255))
        y += 148
    if dim:
        d.rectangle([0, 0, W, H], fill=(0, 0, 0, int(150 * dim)))
    return img


def scene_chats(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), BG)
    draw_chats(img, scroll=min(60.0, t * 26))
    # The thumb travels to Clips and presses it — the cut that follows is then earned, not abrupt.
    press = max(0.0, min(1.0, (t - 1.5) / 0.9))
    tab_bar(img, active=0, highlight=press)
    if t > 1.2:
        x0, y0 = W * 0.5, H * 0.72
        x1, y1 = W / 5 * 1.5, H - TAB_BAR_H + 66
        k = ease(max(0.0, min(1.0, (t - 1.2) / 0.8)))
        finger(img, lerp(x0, x1, k), lerp(y0, y1, k), alpha=min(1.0, (t - 1.1) / 0.3))
    caption(img, [S("chats")], t - 0.2)
    return img


# ---------------------------------------------------------------------------------------------
# 3. Clips — the feed, and where the videos come from
# ---------------------------------------------------------------------------------------------

def draw_clip(img, idx: int, offset: float, liked=False, like_pop=0.0, overlay=1.0):
    """The footage of one clip at a vertical offset, so a swipe can be animated between two."""
    img.paste(clip_bg(idx), (0, int(offset)))
    if overlay > 0.01:
        draw_clip_overlay(img, idx, offset, liked, like_pop)


def draw_clip_overlay(img, idx: int, offset: float, liked=False, like_pop=0.0):
    """Channel line, caption and the action rail — everything that sits ON the video."""
    d = ImageDraw.Draw(img, "RGBA")
    y = int(offset)

    # Kept above the caption band: the band is opaque, so anything under it is simply gone.
    ty = y + BAND_TOP - 210
    d.ellipse([56, ty, 56 + 84, ty + 84], fill=AVATAR_COLORS[idx % 8])
    d.text((166, ty + 8), ["@nature_uz", "@street_food", "@city_walks", "@fun_clips"][idx % 4],
           font=font(38, 600), fill=(255, 255, 255))
    d.text((56, ty + 104), S(f"clip_cap_{idx % 4}"), font=font(36, 400), fill=(235, 240, 246))

    rail_x = W - 110
    ry = y + BAND_TOP - 700
    heart = RED if liked else (255, 255, 255)
    hs = int(34 * (1.0 + 0.28 * like_pop))
    d.ellipse([rail_x - hs, ry - hs, rail_x + hs, ry + hs], outline=heart, width=6)
    d.polygon([(rail_x, ry + hs * 0.55), (rail_x - hs * 0.72, ry - hs * 0.12),
               (rail_x, ry - hs * 0.62), (rail_x + hs * 0.72, ry - hs * 0.12)], fill=heart)
    lbl = "1.3K" if liked else "1.2K"
    d.text((rail_x - text_w(lbl, font(30, 600)) / 2, ry + 46), lbl,
           font=font(30, 600), fill=(255, 255, 255))
    for dy, l2 in ((160, "84"), (300, "12")):
        d.ellipse([rail_x - 32, ry + dy - 32, rail_x + 32, ry + dy + 32], outline=(255, 255, 255), width=6)
        d.text((rail_x - text_w(l2, font(30, 600)) / 2, ry + dy + 46), l2,
               font=font(30, 600), fill=(255, 255, 255))


def scene_clips(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), (0, 0, 0))

    sw = [(1.1, 0.55), (2.6, 0.55)]
    idx, off, moving = 0, 0.0, False
    for i, (start, dur) in enumerate(sw):
        if t >= start:
            k = ease(min(1.0, (t - start) / dur))
            if k >= 1.0:
                idx, off = i + 1, 0.0
            else:
                idx, off, moving = i, -H * k, True

    # While a swipe is in flight the overlay is off: two sets of channel names and like counts
    # sliding past each other is noise, and no real feed shows them mid-gesture either.
    # Footage first, then the scrim, then the overlay. In that order and no other: with the scrim
    # applied last it dimmed the channel name and the caption along with the picture, and the text
    # it exists to make readable was the one thing it washed out.
    if moving:
        draw_clip(img, idx, off, overlay=0.0)
        draw_clip(img, idx + 1, off + H, overlay=0.0)
        img = clip_scrim(img)
    else:
        draw_clip(img, idx, 0, overlay=0.0)
        img = clip_scrim(img)
        liked = t >= 4.35
        pop = max(0.0, 1.0 - (t - 4.35) / 0.45) if liked else 0.0
        draw_clip_overlay(img, idx, 0, liked=liked, like_pop=pop)

    tab_bar(img, active=1)

    for start, dur in sw:
        if start - 0.25 <= t <= start + dur:
            k = ease(max(0.0, min(1.0, (t - start + 0.25) / (dur + 0.25))))
            finger(img, W * 0.5, lerp(H * 0.74, H * 0.34, k), alpha=1.0, trail=90 * (1 - k))
    if 4.1 <= t <= 4.75:
        finger(img, W - 110, BAND_TOP - 700, alpha=1.0)

    # The sub-line switches halfway: first what Clips IS, then where the videos come from. Two
    # facts, one band, no second box fighting the clip's own caption for the same pixels.
    sub = S("clips_sub_1") if t < 3.4 else S("clips_sub_2")
    caption(img, [S("clips")], t - 0.15, sub=sub)
    return img


# ---------------------------------------------------------------------------------------------
# 4. Video tab — the grid, then the player and the two gestures that are the point
# ---------------------------------------------------------------------------------------------

CHIPS = ["All", "New", "Popular", "Long"]
GRID_TITLES = [
    ("Tashkent from above, 4K", "@drone_uz · 82K"),
    ("Everything about the new metro", "@city_talks · 41K"),
    ("One week, one backpack", "@road_notes · 128K"),
    ("How they build these bridges", "@how_it_works · 96K"),
]


def draw_video_grid(img, scroll=0.0, skip=0):
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle([0, 0, W, H], fill=BG)
    status_bar(d)
    d.text((56, 150), "Video", font=font(60, 700), fill=FG)

    # Category chips. They never change during the shot — switching one is what would show the
    # film catalogue, and that is not what this advert is about.
    x = 56
    for i, c in enumerate(CHIPS):
        w = text_w(c, font(34, 600)) + 68
        rr(d, [x, 250, x + w, 250 + 74], 37,
           fill=ACCENT if i == 0 else SURFACE)
        d.text((x + 34, 268), c, font=font(34, 600),
               fill=(255, 255, 255) if i == 0 else MUTED)
        x += w + 20

    y = 372 - scroll
    for i, (title, meta) in enumerate(GRID_TITLES):
        if i < skip:            # the item now playing above; drawing it twice read as a glitch
            continue
        if y > H:
            break
        th = 320
        thumb = fake_footage(i, (W - 112, th)).copy()
        img.paste(thumb, (56, int(y)))
        d = ImageDraw.Draw(img, "RGBA")
        rr(d, [W - 220, y + th - 66, W - 72, y + th - 12], 12, fill=(0, 0, 0, 190))
        d.text((W - 206, y + th - 60), "12:04", font=font(30, 600), fill=(255, 255, 255))
        d.text((56, y + th + 22), title, font=font(40, 600), fill=FG)
        d.text((56, y + th + 76), meta, font=font(32, 400), fill=MUTED)
        y += th + 176
    return img


def draw_player(img, expand: float, playing_idx=0, controls=1.0):
    """The player between inline (expand=0) and fullscreen (expand=1).

    One continuous geometry rather than two screens, because that is what the gesture actually does:
    the video grows out of the feed and shrinks back into it, and the feed never went anywhere.
    """
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle([0, 0, W, H], fill=BG)

    # The feed carries on BELOW the player — which is what makes "swipe down, right back to the
    # feed" true rather than a slogan. The item being played is skipped: drawn twice, its title
    # landed on the player's own and looked like a rendering fault.
    if expand < 1.0:
        draw_video_grid(img, scroll=-380, skip=1)
        d = ImageDraw.Draw(img, "RGBA")
        d.rectangle([0, 0, W, H], fill=(8, 10, 14, int(252 * min(1.0, expand * 2.4))))

    # inline rect -> full screen
    x0 = lerp(56, 0, expand)
    y0 = lerp(300, (H - (W * 16 / 9)) / 2 if W * 16 / 9 < H else 0, expand)
    w = lerp(W - 112, W, expand)
    h = lerp(320, H, expand) if expand > 0.98 else lerp(320, H * 0.92, expand)
    radius = int(lerp(22, 0, expand))

    frame = fake_footage(playing_idx, (int(w), int(h))).copy()
    mask = Image.new("L", (int(w), int(h)), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, int(w), int(h)], radius=radius, fill=255)
    img.paste(frame, (int(x0), int(y0)), mask)

    d = ImageDraw.Draw(img, "RGBA")
    # play/pause + scrubber, only while there is room for them
    if controls > 0.02:
        a = int(255 * controls)
        cx, cy = x0 + w / 2, y0 + h / 2
        r = lerp(46, 74, expand)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0, 0, 0, int(90 * controls)))
        d.polygon([(cx - r * 0.28, cy - r * 0.42), (cx + r * 0.44, cy), (cx - r * 0.28, cy + r * 0.42)],
                  fill=(255, 255, 255, a))
        bar_y = y0 + h - lerp(26, 74, expand)
        d.line([(x0 + 28, bar_y), (x0 + w - 28, bar_y)], fill=(255, 255, 255, int(70 * controls)), width=6)
        d.line([(x0 + 28, bar_y), (x0 + 28 + (w - 56) * 0.38, bar_y)], fill=(255, 255, 255, a), width=6)
        d.ellipse([x0 + 28 + (w - 56) * 0.38 - 12, bar_y - 12,
                   x0 + 28 + (w - 56) * 0.38 + 12, bar_y + 12], fill=(255, 255, 255, a))

    if expand < 0.5:
        a = int(255 * (1 - expand * 2))
        d.text((56, y0 + 344), GRID_TITLES[playing_idx][0], font=font(40, 600), fill=(*FG, a))
        d.text((56, y0 + 398), GRID_TITLES[playing_idx][1], font=font(32, 400), fill=(*MUTED, a))
    return img


def scene_video(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), BG)

    TAP = 1.15          # tap a video in the grid
    UP0, UP1 = 2.15, 2.95    # swipe up -> fullscreen
    HOLD = 4.3          # sit in fullscreen
    DN0, DN1 = 4.3, 5.05     # swipe down -> back inline

    if t < TAP:
        draw_video_grid(img, scroll=0)
        tab_bar(img, active=2)
        if t > 0.45:
            finger(img, W * 0.5, 372 + 160, alpha=min(1.0, (t - 0.45) / 0.3))
        caption(img, [S("video")], t - 0.1, sub=S("video_sub"))
        return img

    if t < UP0:
        draw_player(img, 0.0, 0)
        tab_bar(img, active=2)
        caption(img, [S("video")], 1.0, sub=S("video_sub"))
        return img

    if t < UP1:
        k = ease((t - UP0) / (UP1 - UP0))
        draw_player(img, k, 0, controls=1 - k * 0.4)
        if k < 0.9:
            tab_bar(img, active=2)
        finger(img, W * 0.5, lerp(H * 0.62, H * 0.24, k), alpha=1.0, trail=90 * (1 - k))
        caption(img, [S("up")], t - UP0, sub=S("up_sub"))
        return img

    if t < DN0:
        draw_player(img, 1.0, 0, controls=0.9)
        caption(img, [S("up")], 1.0, sub=S("up_sub"))
        return img

    if t < DN1:
        k = ease((t - DN0) / (DN1 - DN0))
        draw_player(img, 1.0 - k, 0, controls=0.6 + 0.4 * k)
        if k > 0.35:
            tab_bar(img, active=2)
        finger(img, W * 0.5, lerp(H * 0.24, H * 0.62, k), alpha=1.0, trail=-90 * (1 - k))
        caption(img, [S("down")], t - DN0, sub=S("down_sub"))
        return img

    draw_player(img, 0.0, 0)
    tab_bar(img, active=2)
    caption(img, [S("down")], 1.0, sub=S("down_sub"))
    return img


# ---------------------------------------------------------------------------------------------
# 5. Close
# ---------------------------------------------------------------------------------------------

def scene_end(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), (10, 13, 18))
    d = ImageDraw.Draw(img, "RGBA")
    a = ease_out(min(1.0, t / 0.5))
    svipe_mark(d, W // 2, 720, 150, int(255 * a))
    centered(d, 960, "Svipe", font(96, 700), (*FG, int(255 * a)))
    a2 = ease_out(max(0.0, min(1.0, (t - 0.35) / 0.5)))
    centered(d, 1096, "svipe.uz", font(52, 600), (*ACCENT, int(255 * a2)))
    a3 = ease_out(max(0.0, min(1.0, (t - 0.7) / 0.5)))
    centered(d, 1230, S("end_sub"), font(38, 500), (*MUTED, int(220 * a3)))
    return img
