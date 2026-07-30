# Svipe video player — spec

Owner's brief (2026-07-30): *"a player that works like YouTube. Everything the YouTube mobile app's
player has, we should have too — except the features we don't support yet (e.g. Watch Later: not now,
we'll add it in future)."*

This is the **long-form / horizontal** player, opened from the Video tab's full-width cards
(`/v1/videos`). It is NOT the reels player — `ReelsActivity` stays the vertical swipe-up experience.

## Owner's explicit requirements

1. **Related videos below the player.** While the player is open, a list of similar videos underneath,
   like YouTube's watch page.
2. **Autoplay to the next video** when the current one ends — gated on an autoplay toggle switch in the
   player chrome, exactly like YouTube's.
3. **Swipe UP on the player → fullscreen.**
4. **Fullscreen must not break on rotation.** Telegram's own player has this bug: in fullscreen, turning
   the device back to portrait drops you out of fullscreen. Ours must not. In fullscreen the player
   adapts to the **two landscape orientations only** — a portrait rotation must be ignored, not exit.
5. **Swipe DOWN in fullscreen → back to inline.**
6. **Drag the player down → mini player**, floating above every screen and surviving navigation, like
   YouTube's. Drag it back up to restore the full watch page.
7. Anything else the YouTube player does that we can already support.

## Derived scope — SUPPORTED NOW

Player surface / states, and the transitions between them:

| State | Enter | Leave |
|---|---|---|
| Inline (16:9 at top of the watch page) | tap a card in the Video tab | back, or drag down, or swipe up |
| Fullscreen (landscape) | swipe up on the player, or the fullscreen button | swipe down, back, or the button |
| Mini (floating, over all screens) | drag the watch page / player down | drag up to restore, swipe sideways to dismiss, or X |

Controls overlay (auto-hides, tap toggles):
- play / pause, big centre play affordance
- seek bar with current + total time, draggable scrub
- double-tap left/right → seek ∓10 s with the ripple feedback
- long-press → temporary 2x speed while held
- fullscreen enter/exit button
- settings: playback speed, and quality when the post carries multiple renditions
  (`ReelsActivity.playbackQualitiesFor` already resolves Telegram's alt-document ladder)
- autoplay toggle (drives requirement 2)
- in fullscreen: vertical drag on the left half = brightness, right half = volume
- loop-video option

Watch page below the player:
- title, view count, relative upload date (same formatters the Video-tab cards use)
- channel row: avatar, name, subscribe/open-channel action
- action row: like, comment, share — the actions the reels player already implements
- full caption, expandable
- **download for offline** — Telegram has had this since long before YouTube did, and the fork already
  owns the whole path (`FileLoader.loadFile`, progress, cancel, the downloads list). Use it as-is.
  NOTE this is a USER-INITIATED download and must not be confused with the automatic long-form guards
  added to `ReelsActivity`: those only stop an *implicit* full-file pull after 3 s of dwell and stop
  long-form entering the offline reels cushion. An explicit Download tap is exactly what it says.
- related-videos list, paged/infinite
- "Up next" preview + countdown before autoplay advances

### Related videos — interim source
Owner: *"use that for now, later we'll do a different algorithm because the logic here is a bit
different."* So v1 feeds the related list from the **existing long-form pipe** (`/v1/videos`, seeded
with the video being watched so it continues from it) rather than a purpose-built related model. A
dedicated related algorithm — where the objective is "what pairs well with THIS video", not "what
should this user watch next" — comes later.

Behaviour to get right because Telegram's player gets it wrong:
- fullscreen is **orientation-sticky**: `sensorLandscape` while fullscreen, and a portrait sensor
  reading must never itself exit fullscreen
- rotation must not restart playback or lose position
- audio focus + pausing music, mirroring how the reels player already coexists with the Music tab
- the mini player must keep playing across fragment navigation and must not leak when the app is
  backgrounded

Telemetry: the same watch events the recsys already consumes (dwell, watch time, completion, skip),
so the long-form pipe's value model gets real signal. Long-form guards from `ReelsActivity` apply
(no looping by default, no full-file download after 3 s of dwell, never persisted to the offline
reels queue).

## DEFERRED — explicitly not now

- **Watch Later / "Keyinroq"** — owner: not supported yet, add in future.
- A purpose-built **related-videos algorithm** — the interim source above stands in until then.
- Playlists, chapters, captions/subtitles, ambient mode, background audio playback, system
  picture-in-picture (the in-app mini player is the equivalent for now), comments composer beyond
  what reels already has, thumbnail scrub previews (no sprite sheets).

## Notes

- Reuse the app's own machinery over hand-rolling: Telegram's `PhotoViewer` is the only place in the
  codebase that already does rotate-to-fullscreen video, and `ReelsActivity` owns the streaming,
  quality-ladder, telemetry and share/like/comment paths. Read both before designing.
- Owner: *"if something's missing I'll tell you"* — ship the surface, iterate on feedback.
