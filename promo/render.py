"""Render the promo to an mp4.

    python3 promo/render.py                 # the whole 24s film
    python3 promo/render.py --stills        # one PNG per scene, for looking before committing
    python3 promo/render.py --scene clips   # just one scene, while iterating on it

Frames are drawn one at a time and piped straight into ffmpeg — 720 PNGs never touch the disk.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import imageio_ffmpeg
from ui import FPS, H, W

import scenes

# (name, seconds, renderer). The order is the film; the numbers are the pacing.
#
# Clips and the video player carry the two things nobody else has, so they get two thirds of the
# running time between them. Everything else is there to make those two land: the opening says what
# the app IS, chats says the account is really yours, the end says where to get it.
TIMELINE = [
    ("open",  3.0, scenes.scene_open),
    ("chats", 3.0, scenes.scene_chats),
    ("clips", 7.0, scenes.scene_clips),
    ("video", 8.0, scenes.scene_video),
    ("end",   3.0, scenes.scene_end),
]

OUT = Path(__file__).parent / "out"


def frames(only: str | None = None):
    for name, dur, fn in TIMELINE:
        if only and name != only:
            continue
        n = int(dur * FPS)
        for i in range(n):
            yield name, fn(i / FPS)


def render(path: Path, only: str | None = None) -> None:
    OUT.mkdir(exist_ok=True)
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [
        ff, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
        "-i", "-",
        # yuv420p + even dimensions, or half the players in the world show a green screen.
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "slow", "-crf", "20",
        # Instagram re-encodes anything it is given; handing it a clean high-bitrate source is the
        # only lever we have on how the second encode looks.
        "-movflags", "+faststart", str(path),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                            stderr=subprocess.PIPE)
    total = 0
    for name, img in frames(only):
        proc.stdin.write(img.tobytes())
        total += 1
        if total % 60 == 0:
            print(f"  {total} frames…", flush=True)
    proc.stdin.close()
    err = proc.stderr.read().decode()[-800:]
    if proc.wait() != 0:
        print(err, file=sys.stderr)
        sys.exit(1)
    print(f"{path}  —  {total} frames, {total / FPS:.1f}s")


def stills() -> None:
    """One frame from the middle of each scene, plus the moments that matter inside them."""
    OUT.mkdir(exist_ok=True)
    picks = [("open", 2.2), ("chats", 1.9), ("clips", 0.6), ("clips", 3.6), ("clips", 4.5),
             ("video", 0.8), ("video", 2.6), ("video", 3.6), ("video", 4.7), ("end", 1.4)]
    by_name = {n: fn for n, _, fn in TIMELINE}
    for i, (name, t) in enumerate(picks):
        p = OUT / f"still_{i:02d}_{name}_{t}.png"
        by_name[name](t).save(p)
        print(p)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--stills", action="store_true")
    ap.add_argument("--scene")
    ap.add_argument("--out", default=None)
    ap.add_argument("--lang", default="en", choices=("en", "uz"))
    a = ap.parse_args()
    scenes.LANG = a.lang
    if a.stills:
        stills()
    else:
        name = a.out or (f"svipe_promo_{a.lang}_{a.scene}.mp4" if a.scene else f"svipe_promo_{a.lang}.mp4")
        render(OUT / name, a.scene)
