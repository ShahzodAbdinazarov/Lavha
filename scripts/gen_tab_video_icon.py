#!/usr/bin/env python3
"""Generate the Svipe "Video" bottom-tab Lottie icon (YouTube-style play badge).

The Search tab used to show a magnifier (res/raw/tab_search.json). Now that the tab's
empty state is a mixed video feed (horizontal long-form + vertical reels), it shows a
YouTube-style rounded-rectangle play badge instead.

Why generate it instead of hand-writing JSON: the icon is a pure geometry problem
(rounded rect + triangle + an animated hole), so the shape is described once by the
constants below and the 800-line Lottie is derived. Tweak a constant, re-run, done.

    python3 scripts/gen_tab_video_icon.py

Output: TMessagesProj/src/main/res/raw/tab_video.json

Structure mirrors res/raw/tab_search.json exactly, because
GlassTabView.TabAnimation(int iconRes) (the single-file, no-_reverse constructor) drives
it: on select it plays 0 -> framesCount, on deselect framesCount -> 0.

  * layer "Video Outline" (frames 0..5, even-odd fill): outer rounded rect + an inner
    rounded rect that collapses to nothing + the play triangle. Even-odd means the
    inner rect punches the outline's hole, and the triangle -- sitting inside that hole
    -- is filled back in. As the hole collapses the badge fills solid.
  * layer "Video Active" (frames 5..7, even-odd fill): outer rounded rect + play
    triangle, i.e. a solid badge with the triangle knocked out. Same hard cut at the
    midpoint that tab_search.json / tab_reels.json use.

All fills are white; GlassTabView tints the drawable with a PorterDuff SRC_IN filter,
so the knocked-out triangle stays transparent and shows the glass background through.
"""

import json
import os

# ---------------------------------------------------------------------------
# Geometry (24x24 canvas, layer anchored at its centre, y grows downward)
# ---------------------------------------------------------------------------

CANVAS = 24
FPS = 60
MID_FRAME = 5           # outline -> active hard cut
END_FRAME = 6           # framesCount reported to RLottieDrawable

BADGE_W = 18.0          # outer badge width  (YouTube's badge is ~1.43:1)
BADGE_H = 12.6          # outer badge height
BADGE_R = 3.4           # outer corner radius
STROKE = 2.0            # outline thickness, matches tab_search.json's ring

TRI_H = 7.0             # play triangle height
TRI_W = 6.0             # play triangle width
TRI_DX = 0.5            # nudge right so the triangle reads optically centred

KAPPA = 0.5523          # circle-to-bezier constant


def rounded_rect(w, h, r, cx=0.0, cy=0.0):
    """8-vertex rounded rectangle as a Lottie bezier path (clockwise, y down)."""
    return rounded_box(cx - w / 2.0, cx + w / 2.0, cy - h / 2.0, cy + h / 2.0, r)


def rounded_box(x0, x1, y0, y1, r):
    """Same, from explicit edges -- lets the hole animate its edges independently."""
    r = min(r, (x1 - x0) / 2.0, (y1 - y0) / 2.0)
    k = r * KAPPA
    # A..H: top-edge start, top-edge end, right-edge start, right-edge end,
    #       bottom-edge end, bottom-edge start, left-edge end, left-edge start
    verts = [
        (x0 + r, y0),
        (x1 - r, y0),
        (x1, y0 + r),
        (x1, y1 - r),
        (x1 - r, y1),
        (x0 + r, y1),
        (x0, y1 - r),
        (x0, y0 + r),
    ]
    # in/out tangents: (0,0) on straight edges, kappa-scaled on the corner arcs
    ins = [(-k, 0), (0, 0), (0, -k), (0, 0), (k, 0), (0, 0), (0, k), (0, 0)]
    outs = [(0, 0), (k, 0), (0, 0), (0, k), (0, 0), (-k, 0), (0, 0), (0, -k)]
    return {
        "i": [[round(x, 3), round(y, 3)] for x, y in ins],
        "o": [[round(x, 3), round(y, 3)] for x, y in outs],
        "v": [[round(x, 3), round(y, 3)] for x, y in verts],
        "c": True,
    }


def triangle():
    """Right-pointing play triangle, sharp corners."""
    hh, hw = TRI_H / 2.0, TRI_W / 2.0
    verts = [
        (-hw + TRI_DX, -hh),
        (hw + TRI_DX, 0.0),
        (-hw + TRI_DX, hh),
    ]
    zero = [[0, 0]] * 3
    return {
        "i": zero,
        "o": zero,
        "v": [[round(x, 3), round(y, 3)] for x, y in verts],
        "c": True,
    }


def shape(name, path):
    return {"ind": 0, "ty": "sh", "ks": {"a": 0, "k": path}, "nm": name, "hd": False}


def animated_shape(name, keyframes):
    """keyframes: list of (frame, path). Bezier-eased like tab_search.json's hole."""
    ks = []
    for idx, (frame, path) in enumerate(keyframes):
        if idx == len(keyframes) - 1:
            ks.append({"t": frame, "s": [path]})
        else:
            ks.append({
                "i": {"x": 0.7, "y": 1},
                "o": {"x": 0.3, "y": 0},
                "t": frame,
                "s": [path],
            })
    return {"ind": 1, "ty": "sh", "ks": {"a": 1, "k": ks}, "nm": name, "hd": False}


def group(name, shapes, fill_rule):
    """fill_rule 1 = non-zero, 2 = even-odd (holes)."""
    items = list(shapes)
    items.append({
        "ty": "fl",
        "c": {"a": 0, "k": [1, 1, 1, 1]},
        "o": {"a": 0, "k": 100},
        "r": fill_rule,
        "bm": 0,
        "nm": "fill",
        "hd": False,
    })
    items.append({
        "ty": "tr",
        "p": {"a": 0, "k": [0, 0]},
        "a": {"a": 0, "k": [0, 0]},
        "s": {"a": 0, "k": [100, 100]},
        "r": {"a": 0, "k": 0},
        "o": {"a": 0, "k": 100},
        "nm": "tr",
    })
    for i, item in enumerate(items):
        if item.get("ty") == "sh":
            item["ind"] = i
    return {"ty": "gr", "it": items, "nm": name, "bm": 0, "hd": False}


def layer(index, name, groups, ip, op):
    c = CANVAS / 2.0
    return {
        "ddd": 0,
        "ind": index,
        "ty": 4,
        "nm": name,
        "sr": 1,
        "ks": {
            "o": {"a": 0, "k": 100},
            "r": {"a": 0, "k": 0},
            "p": {"a": 0, "k": [c, c, 0]},
            "a": {"a": 0, "k": [0, 0, 0]},
            "s": {"a": 0, "k": [100, 100, 100]},
        },
        "ao": 0,
        "shapes": groups,
        "ip": ip,
        "op": op,
        "st": 0,
        "bm": 0,
    }


def build():
    outer = rounded_rect(BADGE_W, BADGE_H, BADGE_R)
    tri = triangle()

    # The hole closes as a right-to-left wipe rather than shrinking onto its own centre.
    # Under even-odd, any hole/triangle overlap flips back to filled, so a concentric
    # collapse would eat the play mark from the middle outward -- visible as a flicker.
    # Sweeping the hole's right edge leftwards past the triangle's base instead reveals
    # the play mark as a hole monotonically, right to left, while the badge fills in.
    hx0 = -(BADGE_W / 2.0 - STROKE)
    hx1 = BADGE_W / 2.0 - STROKE
    hy = BADGE_H / 2.0 - STROKE
    hr = max(BADGE_R - STROKE, 0.1)
    tri_left = -TRI_W / 2.0 + TRI_DX
    hole_frames = [
        (0, rounded_box(hx0, hx1, -hy, hy, hr)),
        # clear of the triangle's base, full height: the play mark is now a clean hole
        (MID_FRAME - 1, rounded_box(hx0, tri_left - 0.7, -hy, hy, hr)),
        # zero area, so the outline's last frame matches the active layer exactly
        (MID_FRAME, rounded_box(hx0, hx0, 0, 0, 0)),
    ]

    outline = layer(
        2,
        "Video Outline",
        [group(
            "outline_group",
            [
                shape("badge_outer", outer),
                animated_shape("badge_hole", hole_frames),
                shape("play", tri),
            ],
            fill_rule=2,
        )],
        ip=0,
        op=MID_FRAME,
    )

    active = layer(
        1,
        "Video Active",
        [group(
            "active_group",
            [shape("badge_solid", outer), shape("play_hole", tri)],
            fill_rule=2,
        )],
        ip=MID_FRAME,
        op=END_FRAME + 1,
    )

    return {
        "v": "5.7.4",
        "fr": FPS,
        "ip": 0,
        "op": END_FRAME,
        "w": CANVAS,
        "h": CANVAS,
        "nm": "tab_video",
        "ddd": 0,
        "assets": [],
        # rlottie draws layers in array order; "Active" first so it composites on top
        # exactly the way tab_search.json orders its own two layers.
        "layers": [active, outline],
    }


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "TMessagesProj", "src", "main", "res", "raw", "tab_video.json")
    data = build()
    with open(out, "w") as fh:
        json.dump(data, fh, indent=1)
        fh.write("\n")
    print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
