<!-- Implementation plan for docs/video-player-spec.md. Derived from a full read of the fork
     (fullscreen/rotation, playback stack, watch-page chrome, download, telemetry) and verified
     against the tree. The spec says WHAT; this says HOW and WHY, with file:line evidence. -->

# Svipe long-form video player — implementation plan

Verified against the tree (not just the four reports). Corrections to the reports are flagged **[CORRECTION]**.

---

## 0. Two decisions that shape everything

**D1 — The player surface lives in exactly one place, forever: an app-level overlay view in `LaunchActivity.frameLayout`. It is never re-parented.**
`LaunchActivity.java:523` adds `drawerLayoutContainer`; `LaunchActivity.java:535` adds `bottomSheetTabsOverlay` as its sibling in the same root `frameLayout` (`getFrameLayout()` at `:329`, `public static LaunchActivity instance` at `:380`/`:405`). That is the exact precedent: an app-wide overlay above every fragment, above the floating bottom tab bar, in the same window, no `WindowManager` permission, `setClipChildren(false)` already set (`:462-463`).

Consequence: **inline → fullscreen → mini are pure geometry on one view.** No TextureView handover, no `SurfaceTexture` destroy/recreate, no black flash, no re-prepare, no seek, nothing to snapshot. The mini player surviving fragment navigation is free — the fragment dies, the overlay does not. It also makes the tablet `rebuildFragments(REBUILD_FLAG_REBUILD_LAST)` path (`LaunchActivity.java:1348/1355, 1373/1379, 7109`) harmless, which is the one recreation risk the fullscreen report identified.

The watch fragment owns **no player views**. It owns a `View placeholder` (a 16:9 hole at the top) and reports its window rect; the stage positions itself over it. Because YouTube *pins* the player (it does not collapse or scroll away), the placeholder rect never moves during scrolling — so there is no scroll-sync problem and **no nested scrolling at all**. This is why we do **not** inherit `ProfileStyleActivity` (`ui/ProfileStyleActivity.java:83`): its `MediaSectionView`/`NestedFrameLayout` machinery (`:224-279`, `:927-989`) exists to collapse an avatar-shaped header (`initialPaddingTop = displaySize.x + …`, `:633-634`) and it owns the pull-down gesture we need for mini. One flat `RecyclerListView` is strictly simpler and correct.

**D2 — `TextureView`, single instance, `VideoPlayer` never rebuilt across state changes.** `VideoPlayer.setTextureView` (`Components/VideoPlayer.java:1407`) forwards to `ExoPlayer.setVideoTextureView` and is only called **once per opened video**. `SurfaceView` (what PhotoViewer uses on API≥30, `:356`) is rejected: it forces PhotoViewer's whole `usedSurfaceView ? … : …` mess plus `AndroidUtilities.getBitmapFromSurface` snapshots for every transition.

---

## 1. Architecture — concrete files

### New (all paths under `TMessagesProj/src/main/java/`)

| File | Role |
|---|---|
| `org/telegram/svipe/video/SvipeVideoPlayerController.java` | **Process singleton** (`getInstance()`, like `PipVideoOverlay`/`PipRoundVideoView`). Owns the `VideoPlayer`, the current item + related list, the **explicit `int mode`** (`MODE_INLINE / MODE_FULLSCREEN / MODE_MINI`), orientation save/restore, autoplay, resume position, telemetry. All state transitions go through `enterFullscreen()/exitFullscreen()/toMini()/toInline()/close()`. |
| `org/telegram/svipe/video/SvipeVideoStage.java` | The overlay `FrameLayout` added once to `LaunchActivity.frameLayout`. Children: `AspectRatioFrameLayout` + `TextureView` + `BackupImageView cover` + `SvipeVideoControls` + mini chrome (title, play/pause, X). Lays itself out from `mode` + an `AnimatedFloat`-driven rect lerp between `inlineRect / fullRect / miniRect`. Nothing here reads the configuration to decide *what* it is — only *how big* it is. |
| `org/telegram/svipe/video/SvipeVideoControls.java` | Public controls overlay. Skeleton copied from `SecretMediaViewer.java:897-968` + `:2560-2650` (the PhotoViewer-free wiring), not from PhotoViewer. |
| `org/telegram/svipe/video/SvipeVideoGestures.java` | Single-tap toggle chrome, double-tap ±10 s, long-press 2×, vertical drag (up→fullscreen / down→mini or inline), fullscreen-only left/right half brightness/volume drags. |
| `org/telegram/ui/SvipeWatchActivity.java` | **Yes, a `BaseFragment`.** Placeholder + one `RecyclerListView`: `[placeholder spacer][title/meta][channel+subscribe][action row][caption][related header][related rows…]`. |
| `org/telegram/ui/Cells/SvipeWideVideoCell.java` | `SvipeExploreGrid.WideVideoCell` (`Components/SvipeExploreGrid.java:1497`) extracted **public**, together with its four private helpers `bindThumb` `:1471`, `chatFor` `:1573`, `captionOf` `:1585`, `metaLine` `:1597`, and `WideThumbView` `:1747`. One renderer for the Video tab and the related list. |
| `org/telegram/svipe/video/SvipeVideoLadder.java` | Extraction of ReelsActivity's ladder statics: `buildQualities` `:1617`, `qualitiesFor` `:1608`, `playbackQualitiesFor` `:1613`, `ladderVideoDocs` `:1648`, `ladderDocsWithManifests` `:1634`, `targetRendition` `:1665` (with the 720p cap **parameterised** — long-form fullscreen wants a higher cap than a reel tile), `cachedQualityOf` `:1686`, `videoAspect` `:1697`, `isLongForm` `:1725`. |
| `org/telegram/svipe/video/SvipeRefResolver.java` | Extraction of `ReelsActivity.resolveItem` `:1189-1259` / `resolveChatOnly` `:1262` (`TL_contacts_resolveUsername` → `TL_channels_getMessages` → `MessageObject`), with the in-flight-callback-queue discipline intact. |
| `org/telegram/svipe/video/SvipeVideoTelemetry.java` | Payload-capable event poster + watch clock + heartbeat timer. Mirrors `svipe/SvipeMusicTelemetry.java:93-113`. |
| `org/telegram/svipe/SvipeLongWatch.java` | Pure-Java long-form leave classifier (JVM-unit-testable, sibling of `SvipeWatchEvent.java`). |
| `org/telegram/svipe/video/SvipeDownloadButton.java` | Idle / progress ring / done / cancel, observing `fileLoadProgressChanged`. |
| `org/telegram/svipe/video/SvipeStallWatchdog.java` | Later step: extraction of `scheduleStuckWatchdog` `:1951` / `recoverStuckReel` `:2058` / `schedulePlaybackStartChecker` `:2158`, re-keyed on `(player, docs)` instead of `(pos)`. |

### Edits

- `LaunchActivity.java` — add `frameLayout.addView(svipeVideoStage = new SvipeVideoStage(this))` immediately after `:535` (so it sits above `bottomSheetTabsOverlay`); public getter; route back in `onBackPressed` using the `bottomSheetTabsOverlay` pattern at `:8341-8342`; forward `onConfigurationChanged` **after** `AndroidUtilities.checkDisplaySize` (`:7129`) and `checkLayout()` (`:7136`); pause on `onPause`, release on `onDestroy` (`:6936` region).
- `SvipeSearchActivity.java:50` and `svipe/SvipeReelsHistoryActivity.java:63` — branch the tap: long-form → `SvipeWatchActivity`, else `ReelsActivity.ofDiscoverSeed(...)` (unchanged). `SvipeDiscover.Item` already carries `width/height/durationMs` (`SvipeDiscover.java:34-58`), so the branch needs no MTProto round-trip.
- `svipe/SvipeDiscover.java` — add `relatedVideos(...)` and a **payload-capable** `sendEvent(account, channelId, messageId, type, JSONObject payload, recId, cb)` (today's public one at `:282` takes no payload, so watch time cannot be posted from outside `ReelsActivity`).
- `ReelsActivity.java:1713` — lower `LONG_FORM_MIN_DURATION_MS` from `5*60*1000` to `180_000` to match the server's `longform_min_duration_ms` (`svipe-backend/app/config.py:122`). Today every 3–5 minute horizontal video escapes all three guards: it loops, it gets a full cacheType-0 pull after 3 s, and it is persisted into the 600 MB offline queue. One-line fix, real bug.
- `res/values/strings.xml` + `values-uz` + `values-ru` — every new string, all three, or `Tools/check_svipe_strings.py` fails `preBuild` (`TMessagesProj/build.gradle:309`).

**`SvipeApi.CLIENT_LEVEL` stays 2.** Level 2 is defined (`SvipeApi.java:31-36`) as guarding *implicit* long-form behaviour; a user-initiated Download tap and a seeded `/v1/videos` query are both outside that contract and additive server-side.

### Playback engine wiring (exact)

```
new VideoPlayer(true, false)            // VideoPlayer.java:200 — pauseOther=true
// DO NOT call setIsReels(): its LoadControl caps read-ahead at 6s/10s (VideoPlayer.java:248-264)
// and would make scrubbing a 40-minute video stutter. Default profile (:265) is correct.
player.setLooping(SvipeVideoLadder.savedLoop(mo))     // default FALSE for long-form
stage.aspect.setAspectRatio(SvipeVideoLadder.videoAspect(doc), 0)   // before first frame, no jump
raise FileStreamLoadOperation.setPriorityForDocument(d, PRIORITY_HIGH) for every rung
player.setTextureView(stage.textureView)              // exactly once
player.setDelegate(...)                               // MUST precede prepare — delegate is
                                                      // dereferenced with no null check at
                                                      // VideoPlayer.java:1770/1781/1787/1792/1797/1812
player.preparePlayer(playbackQualities, cachedQualityOf(q))  // or preparePlayer(uri,"other") when
                                                             // alt_documents is empty (buildQualities
                                                             // returns null, ReelsActivity.java:1620)
if (resumeMs > 0) player.seekTo(resumeMs)
player.setPlayWhenReady(true); player.play();
```
`playbackQualitiesFor` mints a live file reference — call it **once per opened video only** (`FileLoader.getFileReference` inserts into a never-pruned process-lifetime map, documented at `ReelsActivity.java:1597-1607`).

Audio focus / music coexistence needs no new code: `pauseOther=true` registers on `NotificationCenter.playerDidStartPlaying` (`VideoPlayer.java:210-223`), `MediaController.java:1985-1992` consumes it. **No real Android `AudioFocus` is requested anywhere in this fork** (`VideoPlayer.handleAudioFocus`, `:1649`, has zero callers) — we mirror reels, not fix that.

---

## 2. Fullscreen + rotation — the owner's bug and the fix

### The bug, exactly (both halves, in series)

**Half 1 — an `OrientationEventListener` hands the orientation back to the system.** Built inside `PhotoViewer.preparePlayer` (`PhotoViewer.java:10588-10617`, verified verbatim):

```java
if (fullscreenedByButton == 1) {
    if (orientation >= 270 - 30 && orientation <= 270 + 30) { wasRotated = true; }
    else if (wasRotated && orientation > 0 && (orientation >= 330 || orientation <= 30)) {
        parentActivity.setRequestedOrientation(prevOrientation);   // <-- HERE
        fullscreenedByButton = 0; wasRotated = false;
    }
}
```
`prevOrientation` is captured once behind `if (prevOrientation == -10)` (`:6287-6289`) and **never reset**, and for `LaunchActivity` it is `SCREEN_ORIENTATION_UNSPECIFIED (-1)` because the manifest declares no `android:screenOrientation` (`AndroidManifest.xml:212-220`). So restoring it hands the activity back to the sensor, which immediately applies portrait — where the phone physically is.

**Half 2 — PhotoViewer has no fullscreen state to lose; it re-derives "am I fullscreen" from the window aspect on every measure pass.** `VideoPlayerControlFrameLayout.onMeasure` shows/hides the exit button on `if (parentWidth > parentHeight)` (`:3547-3563`); `checkFullscreenButton()` shows the enter button on `if (AndroidUtilities.displaySize.y > AndroidUtilities.displaySize.x && w > h)` (`:11038`). `PhotoViewer.onConfigurationChanged` is **empty** (`:19033`). So the portrait remeasure silently flips every predicate back to "not fullscreen" — nothing named `exitFullscreen` ever runs; the state simply stops computing as true.

**The same bug in a blunter form**, if anyone is tempted by `WebPlayerView`: `Components/EmbedBottomSheet.onConfigurationChanged:1033-1045` literally calls `videoView.exitFullscreen()` when the new config is not landscape, and `:963-979` is a byte-for-byte clone of the same listener.

**Third, independent defect the owner also asked for:** the enter button pins exactly *one* landscape, chosen from the display rotation at tap time — `displayRotation == Surface.ROTATION_270 ? REVERSE_LANDSCAPE : LANDSCAPE` (`PhotoViewer.java:6290-6296`, identical at `EmbedBottomSheet.java:467-473`). While pinned, turning the phone to the *other* landscape does nothing.

### The fix (five rules, all mandatory)

1. **`mode` is an explicit field on `SvipeVideoPlayerController` and the single source of truth.** `enterFullscreen()` / `exitFullscreen()` are the only writers. Nothing else may write it — ever.
2. **Orientation constant: `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.** On enter: `savedOrientation = activity.getRequestedOrientation(); activity.setRequestedOrientation(SENSOR_LANDSCAPE);`. On exit: `activity.setRequestedOrientation(savedOrientation); savedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;` — note the **reset**, which PhotoViewer lacks (`if (prevOrientation == -10)` at `:6287` means the first captured value is reused for the whole process lifetime, and if the first fullscreen tap happens while `StoryViewer.java:2035` or `BotWebViewSheet.java:2713` holds a portrait lock, that foreign lock becomes the permanent "restore" value). `SENSOR_LANDSCAPE` gives both landscapes and flips live — it appears exactly once in the whole app today (`GroupCallActivity.java:4540`, RTMP streams). Do **not** use `AndroidUtilities.lockOrientation/unlockOrientation`: they share one static slot (`AndroidUtilities.java:273`) with nine existing callers and the 2-arg form overwrites it unconditionally (`:1965-1975`).
3. **No `OrientationEventListener`. Anywhere. The listener *is* the bug.**
4. **Config-change handling reads `mode` and never writes it.** `LaunchActivity.onConfigurationChanged` (`:7127`) forwards to the stage *after* `checkDisplaySize`; the stage's handler does exactly one thing: recompute `inlineRect/fullRect/miniRect` from the new `AndroidUtilities.displaySize` and `requestLayout()`. Explicitly forbidden in review: any `parentWidth > parentHeight`, `displaySize.y > displaySize.x`, or `newConfig.orientation == ORIENTATION_LANDSCAPE` test that affects `mode`, control visibility, or which fullscreen button is shown. The enter/exit button is chosen by `mode == MODE_FULLSCREEN`, full stop.
5. **Fullscreen presentation must not depend on the orientation request succeeding.** `fullRect` = the whole window, black, 16:9 letterboxed and centred, and it must look right **in portrait too**. This is not defensive paranoia: the manifest's own comment (`AndroidManifest.xml:698-709`) records that at targetSdk 36 Android already ignores `setRequestedOrientation` on any display with smallest width ≥ 600 dp, that the `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` escape hatch is "a deadline, not a fix", and that it is non-functional at targetSdk 37. The orientation request is a **hint layered on top of an explicit boolean**. `GroupCallActivity` is the only surface in the codebase that already does this correctly — an explicit flag, presentation gated on `inFullscreenMode && isLandscapeMode == …` (`:4521, :6004`), landscape *may* enter (`:2506-2510`) and portrait *never* exits.

**Rotation cannot restart playback**, for two independent reasons: `LaunchActivity` declares `configChanges="…orientation|screenSize|smallestScreenSize|screenLayout"` (`AndroidManifest.xml:215`) so the activity is never recreated and on phones `checkLayout()` returns at its first line (`:1334-1336`); and per **D1** the player lives above the fragment stack, so even the tablet `rebuildFragments` path cannot touch it.

**Default I am taking (stated, per §8): rotating to landscape while inline does NOT auto-enter fullscreen** in v1. Enter is swipe-up or the button; exit is swipe-down, the button, or back. Asymmetric auto-enter is legal (GroupCallActivity does it) but "rotate to enter, button to leave" reads as a trap, and the owner asked for the gesture.

Back handling: `LaunchActivity.onBackPressed` consults the stage first (`mode == FULLSCREEN` → `exitFullscreen()`, consume), mirroring `:8341-8342`; the fragment's `onBackPressed(boolean)` (`BaseFragment.java:570`) leaves the page normally. Precedents: `EmbedBottomSheet.canDismiss():949-953`, `ArticleViewer.java:5934-5941`.

---

## 3. Reuse table — every item in SUPPORTED NOW

| Spec feature | Verdict | Exact API / source |
|---|---|---|
| Inline 16:9 surface, correct aspect + rotation | **reuse as-is** | `com.google.android.exoplayer2.ui.AspectRatioFrameLayout.setAspectRatio(ratio, rotation)` `:120`, `setResizeMode(RESIZE_MODE_FIT|ZOOM)` `:147`; aspect from doc attrs before first frame (`ReelsActivity.java:1744`), corrected in `onVideoSizeChanged` (swap w/h when `unappliedRotationDegrees` is 90/270, `:1817`) |
| Poster before first frame | **reuse as-is** | `BackupImageView` cover from `doc.thumbs` at 320 — `ReelsHolder.setCover` `:3213` / `hideCover` `:3225`; idempotent first-frame handler `handleFirstFrame()` `:1832` (both `onRenderedFirstFrame()` overloads fire — `VideoPlayer.java:1399` and `:1786`) |
| play/pause + big centre affordance | **reuse as-is** | `Components/PlayPauseDrawable.java:19` (`setPause(boolean, animated)` `:103`, `setParent(View)` `:139`); assembly copied from `SecretMediaViewer.java:957-968` |
| Seek bar, current+total time, draggable scrub | **reuse as-is (component) + build container** | `Components/VideoPlayerSeekBar.java:88` — headless: host `View.onDraw` → `seekbar.draw(canvas, this)` `:342`, forward `onTouch(action, x, y)` `:106`; `setSize/setProgress/setBufferedProgress/setColors/setHorizontalPadding/isDragging/getThumbX`. Scrub: `seekTo(ms, true)` (CLOSEST_SYNC) during drag, `seekTo(ms, false)` (EXACT) on release (`VideoPlayer.java:1582`). Time text: copy the allocation-free `format()` pair `PhotoViewer.java:10221/10233` |
| The control **bar container** | **extract from SecretMediaViewer, not PhotoViewer** | `SecretMediaViewer.java:2560-2650` is the same `VideoPlayerControlFrameLayout` minus editor/webview branches. PhotoViewer's (`:3486-3667`) drags in `videoTimelineView`, `photoViewerWebView` and editor state — **not worth extracting** |
| Auto-hide chrome, tap toggles | **extract (tiny)** | ~10 lines: `hideActionBarRunnable` `PhotoViewer.java:1034` + `scheduleActionBarHide(3000)` `:10340-10347`; visibility cross-fade `setVideoPlayerControlVisible` `:11258` |
| Double-tap left/right ∓10 s + ripple | **drawable reuse as-is, logic extract (~35 lines)** | `Components/VideoForwardDrawable.java:53` — `setOneShootAnimation(true)` `:86`, `setLeftSide(x < w/3)` `:95`, `addTime(10000)` `:309`, `setDelegate` `:107`; drawn from the stage's `dispatchDraw` via `setBounds(videoRect)` (`PhotoViewer.java:20578`). Zone + clamp logic: `canDoubleTapSeekVideo` `:21209` + `onDoubleTap` `:21224-21259` (thirds; requires total > 15 s; skips a seek landing below −9000 ms). Use `Components/GestureDetectorFixDoubleTap.java:12` so single-tap stays instant |
| Long-press → temporary 2× | **build new (~25 lines), do NOT adopt `VideoPlayerRewinder`** | `messenger/video/VideoPlayerRewinder.java:117` does give 2× via `setPlaybackSpeed(playSpeed * 2)` `:191`, but it couples in horizontal drag-to-change-speed `:195`, a backward `backSeek` loop that mutes+pauses `:42-85`, and `VideoFramesRewinder`. **Also it has a live defect:** `onRewindStart(boolean)` `:253` is never invoked by it (only `OldVideoPlayerRewinder.java:154` calls it), so PhotoViewer's override `:1202-1210` is dead code. We want: 300 ms `longPressRunnable` cancelled past `touchSlop` (`PhotoViewer.java:19290-19297`) → `setPlaybackSpeed(2f)` + `Components/SeekSpeedDrawable.java:54` badge (**reuse as-is**) → restore on ACTION_UP |
| Fullscreen enter/exit button | **build new** | Icons `R.drawable.msg_maxvideo` / `msg_minvideo`, strings `AccSwitchToFullscreen` / `AccExitFullscreen` already exist. The *handlers* must not be copied (see §2) |
| Playback speed menu | **reuse as-is** | `ui/SpeedButtonsLayout.java:28` (public; used by PhotoViewer `:6039`) + `ActionBar/ActionBarMenuSlider.SpeedSlider` with stops `{.5,1,1.5,2,2.5}` (`PhotoViewer.java:6025-6036`); apply `VideoPlayer.setPlaybackSpeed` `:1488`; persist per message as PhotoViewer does (`chooseSpeed` `:23499`, prefs `playback_speed`, key `speed<did>_<mid>`) |
| Quality menu | **reuse as-is — it is already public and currently unused** | `ui/ChooseQualityLayout.java:48` + `update(VideoPlayer)` `:82` (returns false when `getQualitiesCount() <= 1` → hide the whole block, PhotoViewer's rule at `:8745`), `Callback.onQualitySelected` `:116` → `player.setSelectedQuality(index)` `:730` + `VideoPlayer.saveQuality(q, mo)` `:455-485`. Verified: only its inner `QualityIcon` has callers (`PhotoViewer.java:6018`, `AudioPlayerAlert.java:1087`) — the layout is drop-in. It already localises the Auto row via `R.string.QualityAuto`; rung titles come from `Quality.toString()` |
| Loop-video option | **reuse as-is** | `VideoPlayer.setLooping` `:1669`, `saveLooping/getLooping` `:487/:494`; handler shape `PhotoViewer.java:5968-5975`. **Default off** for long-form; a user toggle is an explicit override of the guard, not a violation |
| Brightness (left half) / volume (right half) drags | **build new** | Nothing in the fork has a brightness or volume *gesture*. Volume: reuse the indicator + stepping of `Stories/StoriesVolumeControl.java:15` (`adjustVolume` `:70-99`, `AudioManager.STREAM_MUSIC`) but it is key-driven only (`onKeyDown` `:37`). Brightness: only precedent for writing it is `Stories/recorder/FlashViews.java:117/:128` (`WindowManager.LayoutParams.screenBrightness` + `updateViewLayout`); **must restore `BRIGHTNESS_OVERRIDE_NONE` on exit fullscreen and on `onPause`** |
| Autoplay toggle | **build new** | No such control exists. Persist in `SvipeConfig`-style prefs |
| Autoplay to next / Up-next + countdown | **build new (engine gap)** | `VideoPlayer` has **no `STATE_ENDED` plumbing at all** — derive `playbackState == Player.STATE_ENDED` in our own `onStateChanged`. Pre-roll the next video with the prepared-player pattern `ReelsActivity.prepareNextPlayer:2181` (**note:** the idle player needs a no-op delegate or `VideoPlayer` NPEs, `:2197-2212`) |
| Title / view count / relative date | **extract (public cell) — reuse the formatters verbatim** | `SvipeExploreGrid.metaLine:1597-1620` — `chat.title` · `LocaleController.getPluralString("Views", n)` + `AndroidUtilities.formatWholeNumber` · `LocaleController.formatRelativeDate(ConnectionsManager.getCurrentTime() - mo.messageOwner.date)` (**server time**, seconds). Watch page needs full multi-line title, so drop the cell's `setLines(2)` pin (`:1527`) for the header row |
| Channel row + subscribe | **extract, logic reusable as-is; restyle the button** | `ReelsActivity.toggleFollow:2772-2785` = `MessagesController.addUserToChat(channelId, self, 0, null, fragment, null)` / `deleteParticipantFromChat`, read via `ChatObject.isInChat(chat)`, plus a FOLLOW/UNFOLLOW event. The button (`:3374-3384`) is hardcoded `0xFF2F6DF6` on black and **hides itself once subscribed** (`:3241`) — wrong for a watch page; show "Subscribed" |
| Like | **extract** | `ReelsActivity.setLike:2573` → `SendMessagesHelper.sendReaction(...)`; state from the locally tracked flag, **not** from `mo.getChoosenReactions()` (the doc comment at `:2567-2572` records that reading it back broke unliking, emoji variation selectors); `showHeartBurst` `:2590` |
| Comment | **reuse as-is** | `SvipeReelsCommentsSheet(context, account, chat, messageId, mo)` `:106` + `setListener` `:132`; only open when `mo.getRepliesCount() > 0` (`ReelsActivity.java:2291`) |
| Share | **reuse as-is** | `ReelsActivity.share:2643-2685` — prefers `item.shareUrl` (the owned `svipe.uz/<code>` install loop the server attaches to every reference) and sends the actual document with a promo caption. Do **not** downgrade to a plain link |
| ⋮ overflow (copy link / channel / not-interested / block / report) | **reuse as-is** | `ItemOptions.makeOptions(fragment, anchor)`; the two existing sets are deliberately identical (`ReelsActivity:2687-2703`, `SvipeExploreGrid:1627-1656`) — keep the wording, add **Download** |
| Expandable full caption | **build new (trivial)** | `captionOf(mo)` `:1585` for the text; expand/collapse is new |
| **Download for offline** | **reuse as-is** | `mo.putInDownloadsStore = true;` **before** `FileLoader.getInstance(account).loadFile(doc, mo, FileLoader.PRIORITY_NORMAL_UP, 0)` — `FileLoader.java:846-848` forwards to `DownloadController.startDownloadFile` `:1442`, which is what puts it in Telegram's Downloads list and the `downloading_documents` table. Cancel: `cancelLoadFile(doc)` `:549`. Callsite pattern: `Cells/ChatMessageCell.java:17797`, `Components/SharedMediaLayout.java:7929-7935`. **Trap:** download `SvipeVideoLadder.targetRendition(qualitiesFor(mo))`, not `mo.getDocument()` (the top HLS rung), and key observers on that doc's `FileLoader.getAttachFileName` (ReelsActivity keeps `FeedItem.downloadDocId` for exactly this, `:1447/:3129`). "Save to gallery" as a secondary ⋮ action via `MediaController.saveFile(path, ctx, 1, null, null)` `:5531` |
| Download progress UI | **build new** | No reusable download widget exists (every one is a `RadialProgress2` inside a specific cell). Observe `NotificationCenter.fileLoadProgressChanged` — **ReelsActivity does not** (`:2941-2942` registers only `fileLoaded`/`fileLoadFailed`); args `(String location, Long done, Long total)`, throttled to 1/500 ms (`ImageLoader.java:2290`); sync read on rebind via `ImageLoader.getInstance().getFileProgressSizes(name)` `:2688` |
| Related list, paged/infinite | **extract cell, build list** | See §4 |
| Resume where you left off | **extract (worth it — nearly free)** | PhotoViewer's `savedVideoPositions` + the `media_saved_pos` prefs store for videos ≥ 5 min (`:11184-11190` write, `:10375-10399` read) is exactly the long-form case, but both stores are `private`. Extract a 30-line `SvipeVideoResume` helper |
| Stall watchdog / stuck-video recovery | **extract later (§6 step 13)** | `ReelsActivity:1951/:2058/:2158`. The load-bearing insight is the comment at `:2116-2127`: only `FileLoader.cancelLoadFile()` empties a wedged operation's request window — `changePriority`/`start()` are silently swallowed |

**Nothing needs to be extracted from PhotoViewer except three small things**: the double-tap zone logic (~35 lines), the auto-hide runnable (~10), and the time formatter (~15). Its controls container, quality menu and speed menu all have better sources (`SecretMediaViewer`, `ChooseQualityLayout`, `SpeedButtonsLayout`). Extracting PhotoViewer's `VideoPlayerControlFrameLayout` or `updateQualityItems()` (~210 lines, duplicated build/refresh branches) is **not worth it**.

`PipVideoOverlay` is a **design reference only** for the mini player (springs `:816-829`, pinch `:611`, controls auto-hide `:724-743`, the `consumingChild` dispatch trick `:709/:913`) — it is hard-wired to `PhotoViewer` (`setPhotoViewer` `:504`, `IPipSourceDelegate`).

---

## 4. Related videos

### Backend — yes, `/v1/videos` needs a seed, and it does not have one

Verified: `get_videos` accepts only `category, limit, offset, refresh` (`svipe-backend/app/api/videos.py:48-59`); `repo.longform_candidates` takes only `category_id/target/per_channel/epoch/min_channel_score` (`repositories.py:518-526`); `longform.DEFAULT_WEIGHTS` has no similarity term (`longform.py:31-37`). Seeding exists only for shorts (`/v1/feed?seed_channel_id&seed_message_id`, `api/feed.py:31-46`) and music.

**Do not seed `/v1/feed` instead**: it hard-filters `duration_ms <= feed_max_duration_ms = 300_000` (`config.py:103`), so a long-form seed returns shorts — `ReelsActivity.java:1708-1712` says exactly this.

**[CORRECTION to the watchpage report]** `refresh=true` does **not** bump the shorts `grid_epoch`. `/v1/videos` owns `state.longform_epoch` and the code carries a long comment explicitly forbidding it from reading `grid_epoch` (`videos.py:77-90`). It would still rotate the user's Video-tab window, so the related list must never send `refresh=1` — but the reason is narrower than reported.

**Interim-simple, two phases:**

*Phase A — ship day one, zero backend deploy.* Related = `SvipeDiscover.videos(account, null, offset, 20, false, cb)` with a client-side filter dropping the seed ref and anything already displayed. Honest and correct: it is the same globally-ranked long-form list, which is precisely what the owner authorised ("use that for now").

*Phase B — ~40-line backend PR in the same milestone.*
```
GET /v1/videos?limit=20&offset=0&seed_channel_id=<id>&seed_message_id=<mid>
```
1. `seed_channel_id: int | None`, `seed_message_id: int | None` as `Query` params on `get_videos`.
2. Cache key **must include the seed**: `lf:{uid}:{cat}:{epoch}:s{ch}_{mid}:v1` — otherwise a related request poisons the browse list's materialised cache (`videos.py:85`).
3. In `_build_list`'s `keep()`: drop the seed ref itself.
4. Ordering: same-channel episodes first (dedup-capped at 2 so a watch page does not become one show's archive), then `Video.topic_id == seed.topic_id`, then the existing `longform.rank()` order. No new weight vector needed for v1 — this is retrieval-side, matching what `longform_candidates` already does with per-channel pooling.
5. Additive and backwards-compatible → no `CLIENT_LEVEL` bump.

Deferred to the real related model (spec line 83): embedding/CLIP similarity, a `similarity` term in `DEFAULT_WEIGHTS`.

Also worth one line of the same PR (optional, flagged): `DiscoverResponse` has no `recommendation_id` (`schemas/discover.py:14-19`) unlike `FeedResponse` (`schemas/feed.py:43-45`), so every long-form event lands **unattributed** and the content-fingerprint dedup (`api/events.py:29-37`) no-ops. Not required for v1.

### Client request

`SvipeDiscover.relatedVideos(int account, long seedChannelId, int seedMessageId, int offset, int limit, Callback cb)` — same `feedGet` plumbing (`SvipeDiscover.java:176-208`, one silent 401 re-auth), `refresh` **hardcoded false**, paged from the watch page's list scroll listener exactly like `SvipeExploreGrid`'s (`:355-368`).

---

## 5. Telemetry

### Why completion cannot be the primary signal — and the concrete harm of reusing reels' classifier

`app/recsys/longform.py:5-8` already states it: *"a long video's completion rate measures its LENGTH, not its quality"* — `longform.DEFAULT_WEIGHTS` has no `completion_prior` at all. But the **client** still classifies with reels thresholds: `SvipeWatchEvent.classify` (`svipe/SvipeWatchEvent.java:13-19`) emits `VIDEO_END` at ≥ 90 % and `REPLAY` at ≥ 150 %, else `SWIPE_AWAY`. On a 40-minute upload nobody crosses 90 %, so **every** long-form watch becomes `SWIPE_AWAY`, and `reward.compute_reward`'s `SWIPE_AWAY` branch grades it by `watched_ms / video_duration_ms` (`reward.py:66-81`) → a genuinely excellent 8-minute view of a 40-minute documentary scores **0.2**, feeding the *shared* bandit and session vector (`engine.apply_event:356-366`). Reusing `classify` verbatim would poison long-form with a stream of near-negatives.

### Events the new player sends

| Event | When | Payload |
|---|---|---|
| `IMPRESSION` | once, on open, **for the watched video only** | `feed_position` |
| `PLAY_START` | play intent | `autoplay` |
| `FIRST_FRAME` | first frame | `time_to_first_frame_ms` |
| `HEARTBEAT` | every 30 s while playing, and on pause / background / mode change | `watched_ms`, `position_ms`, `video_duration_ms`, `buffering_ms`, `autoplay`, `network_type` |
| leave event (see below) | on close / autoplay-advance / player release | `watched_ms`, `video_duration_ms`, `dwell_ms`, `position_ms`, `buffering_ms`, `time_to_first_frame_ms`, `autoplay` |
| `LIKE/UNLIKE/SHARE/FOLLOW/UNFOLLOW/COMMENT/NOT_INTERESTED/BLOCK_CHANNEL/REPORT` | user action | as reels |
| `PLAY_FAILED` | watchdog gave up | as `ReelsActivity:2822` (`kind`, `conn`) |

`HEARTBEAT` is the load-bearing choice: it is **not** in `_EXPOSURE_EVENTS` (`api/events.py:19-26`) and `compute_reward` has **no branch for it** so it falls through to `_NEUTRAL` — zero bandit impact — while `repo.insert_event` persists the raw payload forever (`api/events.py:41-51`, and the module docstring's *"kept forever, re-derivable labels"*). That is exactly the vehicle for long-form watch time until a server-side value term exists.

**Leave classifier — `SvipeLongWatch.classify(watchedMs, durationMs, positionMs, endedNaturally, bufferingMs, ttffMs)`:**
```
endedNaturally || positionMs >= 0.98 * durationMs   -> "VIDEO_END"
watchedMs < 10_000                                  -> "SWIPE_AWAY"   // genuine abandon; dwell_ms +
                                                                      // buffering_ms + ttff let
                                                                      // reward.py:72-76 excuse a
                                                                      // network-caused bail
otherwise                                           -> "HEARTBEAT"    // NEUTRAL: never a false negative
```
Note `event_type` is a strict enum (`schemas/events.py:63` → `EventType`), so unknown strings 422 — the client cannot invent a `WATCH_END`. Hence `HEARTBEAT` as the neutral terminal.

The trade-off is explicit: a good-but-partial long watch yields **no positive reward** in v1. That costs nothing today (the long-form value model consumes zero client events — `longform.score_candidate:100-117` reads only `tg_views/tg_forwards/tg_reactions/posted_at/duration_ms/longvideo_score`) and it avoids the real damage of a false negative. `LIKE/SHARE/FOLLOW` remain the only positives that reach the bandit, as now.

**Backend follow-up, separate PR, not v1:** a long-form branch in `compute_reward` keyed off `video_duration_ms >= 180_000` grading on **absolute** watched time (saturating, e.g. `min(watched_ms / 600_000, 1.0)`), plus a watch-time aggregate term in `longform.DEFAULT_WEIGHTS`. Until then, long-form events only mark items seen (`api/events.py:52-57` → `videos.py:141`).

**Two hard rules:**
- The related list must **never** fire `IMPRESSION` for merely-rendered rows: exposure events call `store.add_seen_keys` and `_build_list`'s `keep()` drops seen refs, so impressions-on-scroll would strip the user's own Video tab.
- The leave event must be flushed **before** the player is released (it reads duration) — same discipline as `ReelsActivity.flushWatchEvent:1097-1124`.

Long-form watches will **not** appear in `/v1/reels/history` — `repo.recent_reel_watches` filters through `_servable()`'s 300 s cap (`repositories.py:963-971`). A `/v1/videos/history` is a later, separate item.

---

## 6. Ordered build steps (smallest first, each verifiable on device)

| # | Step | How the owner verifies it | Risk |
|---|---|---|---|
| 1 | Extract `SvipeVideoLadder` + `SvipeRefResolver`; switch `ReelsActivity` to them in the same commit; lower `LONG_FORM_MIN_DURATION_MS` to 180 000 | Reels still plays, including an HLS-laddered reel from a big channel; a 4-minute horizontal item no longer loops | ⚠️ **regression risk to the reels player** — pure move, no logic change; verify a 3-reel swipe + a quality switch |
| 2 | `SvipeWatchActivity` with a **static black 16:9 placeholder** + title/views/date/channel/caption rows + related list (Phase A source) + extracted `SvipeWideVideoCell`; route long-form taps from the Video tab | Tap a full-width card → watch page opens, metadata matches the card, related list scrolls and pages | low |
| 3 | `SvipeVideoStage` in `LaunchActivity.frameLayout` + inline playback, cover→first frame, tap play/pause | Video plays inline over the placeholder; starting it pauses the Music tab, and music pauses it | ⚠️ overlay geometry/insets vs status bar and the floating tab bar |
| 4 | `SvipeVideoControls`: seek bar + times + buffered track + auto-hide + centre affordance | Scrub a 20-minute video; chrome hides after 3 s, tap brings it back | low |
| 5 | **Fullscreen** — button + swipe-up + swipe-down + back; `SENSOR_LANDSCAPE`; config-change safe | **The owner's repro:** enter fullscreen → turn the phone to portrait → *stays fullscreen*; turn to the other landscape → flips; position and playback untouched throughout; press back → inline, orientation restored | ⚠️⚠️ **highest-value, highest-scrutiny step**; also test on a ≥600 dp emulator where the orientation request is ignored, and on `svipe_a16` |
| 6 | **Mini player** — drag down → mini, survives `presentFragment`/back/tab switches, drag up restores, X or side-swipe dismisses | Open a video, drag down, navigate to Chats → Music → Reels; audio keeps playing, the pill floats above the tab bar; drag up → the watch page comes back at the same position; background the app → playback pauses; kill and relaunch → no leak | ⚠️⚠️ lifecycle/leak surface; wire `LaunchActivity.onPause` (`:7025`) / `onDestroy` (`:6936`) |
| 7 | Speed (`SpeedButtonsLayout` + `SpeedSlider`), quality (`ChooseQualityLayout`), loop toggle | Menu shows quality rows only on a multi-rendition post; 2× plays 2×; both persist across reopen | low |
| 8 | Double-tap ∓10 s with ripple; long-press → 2× while held | Ripple on the correct third; middle third does nothing; release restores 1× | low |
| 9 | Telemetry: `SvipeVideoTelemetry` + `SvipeLongWatch` + payload-capable `SvipeDiscover.sendEvent` | `adb logcat` shows the event sequence; `/admin` event tab shows `HEARTBEAT` rows with sane `watched_ms`; **no** `SWIPE_AWAY` after a 10-minute watch | low (backend-visible: check on dev first) |
| 10 | Autoplay toggle + Up-next preview + countdown (prepared-next player) | Let a short long-form video end → countdown → advances; toggle off → stops at the end | ⚠️ `STATE_ENDED` is new plumbing; the idle player needs a no-op delegate |
| 11 | Download button + progress ring + cancel + "Save to gallery" | Download a video → appears in Telegram's Downloads tab, survives an app restart, plays offline; cancel mid-way frees it | low |
| 12 | Fullscreen brightness / volume vertical drags | Left half dims, right half changes volume, both restore on exit and on background | ⚠️ brightness must be restored to `BRIGHTNESS_OVERRIDE_NONE` on every exit path |
| 13 | Backend `seed_channel_id`/`seed_message_id` on `/v1/videos` (+ seeded cache key) and switch the client from Phase A to Phase B | Related list leads with same-channel episodes; the Video tab's own ordering is unchanged after opening several watch pages | ⚠️ cache-key mistake would poison the browse list — deploy to dev, verify both lists |
| 14 | Extract `SvipeStallWatchdog` and arm it on the long-form player | Throttle the network mid-play → auto-recovers instead of spinning; reels unaffected | ⚠️⚠️ touches the fork's hardest-won code (the owner's #1 Instagram-retention bug) — do it **last**, separately, with reels re-verified |

Steps 1–5 already deliver the owner's headline complaint. Steps 1–6 deliver every one of his seven numbered requirements.

---

## 7. Explicit non-goals (do not build)

- **Watch Later / "Keyinroq"** — deferred by name.
- **A purpose-built related-videos algorithm** — retrieval-side seeding only (§4). No similarity/embedding term in `longform.DEFAULT_WEIGHTS`.
- **Playlists. Chapters** (do not call `VideoPlayerSeekBar.updateTimestamps` `:270`). **Captions/subtitles. Ambient mode. Background audio playback** (backgrounding pauses). **System picture-in-picture** — the in-app mini player is the equivalent; do not touch `pipActivityController` / `supportsPictureInPicture` (`AndroidManifest.xml:216`).
- **Comments composer beyond reels'** — `SvipeReelsCommentsSheet` as-is; no new composer.
- **Thumbnail scrub previews** — `Components/VideoSeekPreviewImage.java:338` is drop-in and tempting; leave it out.
- **`/v1/videos/history`** for long-form watches.
- **A `recommendation_id` on `DiscoverResponse`** (noted, not v1).
- **A long-form reward branch in `reward.py`** (noted, separate PR).
- **`AudioFocus`** — mirror reels' `playerDidStartPlaying` mechanism; do not start using `VideoPlayer.handleAudioFocus`.
- **Anything in `PhotoViewer` / `EmbedBottomSheet`** — read-only references. Their bug stays theirs; fixing it is not in scope.

---

## 8. Ambiguities — defaults taken

1. **`SENSOR_LANDSCAPE`, not `USER_LANDSCAPE`** — spec line 68 says sensorLandscape. If fullscreen flipping while the phone is rotation-locked annoys the owner, it is a one-word change.
2. **Rotating to landscape while inline does not auto-enter fullscreen** (v1). Enter = swipe-up or button.
3. **Long-form routing threshold = 180 000 ms or `isLandscape()`**, matching the server's `longform_min_duration_ms` (`config.py:122`), not the client's stale 5 min. `ReelsActivity`'s guard is lowered to match in step 1.
4. **Exiting fullscreen always restores the previous orientation first**, whatever the destination (including drag-down straight to mini).
5. **Mini player keeps `PRIORITY_HIGH` stream priority while visible.** `FileStreamLoadOperation`'s priority map is a process-wide static (`:275`) with few large-file slots, so a minimised long-form player competes with the Reels tab's prefetch window; if that shows up as reels stalling, demote the mini player to `PRIORITY_NORMAL`.
6. **Quality labels come from `Quality.toString()`** via `ChooseQualityLayout` (its Auto row is already localised). If the owner wants localised rung labels, copy the `R.string.Quality2160…Quality144` mapping from `PhotoViewer.java:8796-8812`.
7. **Speed stops `{0.5, 1, 1.5, 2, 2.5}`** — the existing `SpeedButtonsLayout` set plus the slider, rather than extending it to YouTube's 0.25 steps.
8. **Download tap = Telegram Downloads list** (`putInDownloadsStore`); "Save to gallery" is a secondary ⋮ action, not the primary tap.
9. **Related list = the watch page's own single `RecyclerListView` with `SvipeWideVideoCell` rows**, not an embedded `SvipeExploreGrid`. The page must carry metadata/channel/action/caption rows above the related items, and a nested `RecyclerListView` would fight for touches.
10. **The two existing long-form entry points keep coexisting**: `SvipeSearchActivity:50` and `SvipeReelsHistoryActivity:63` branch on long-form rather than being rewritten — the smaller diff, and shorts keep going to `ReelsActivity` untouched.
---

# APPENDIX — mini player: verified host, and the traps (added after a dedicated read of the PiP stack)

D1's choice of host is **confirmed**, with an exact precedent, and three of the plan's assumptions are
sharpened below. Everything here is verified against the tree.

## Host — confirmed, and why not the alternatives
`LaunchActivity.getMainContainerFrameLayout()` (`LaunchActivity.java:1194`), as a full-screen
**click-through** host added after `bottomSheetTabsOverlay` (`:535`). Children added after
`drawerLayoutContainer` (`:523`) draw above **every fragment**, above `BottomSheetTabs`, and above
`MainTabsActivity`'s floating tab bar. The exact precedent is `FloatingDebugController.java:41`
(`activity.getMainContainerFrameLayout().addView(debugView, MATCH_PARENT, MATCH_PARENT)`).

- **NOT `ActionBarLayout`** — `ActionBarLayout.addView` force-`bringToFront()`s `bottomSheetTabs`
  (`:3517-3525`) and `bringChildToFront(sheetContainer)` runs at `:1320`/`:2135`. A fifth child fights
  both.
- **NOT `MainTabsActivity`** — its tab bar is a child of the *fragment view*
  (`MainTabsActivity.java:352-356`), so `presentFragment` covers it and the mini player would vanish.
- **NOT by extending `PipVideoOverlay`** — it is an eagerly-created static singleton (`:85`) that
  allows exactly ONE pip (`showInternal` bails on `if (isVisible) return false`, `:576-579`) and holds
  hard refs to `photoViewer`/`parentSheet` (`:121-123`). Reusing it inherits hard mutual exclusion with
  PhotoViewer's pip and the YouTube-embed pip. Build a separate host; read it for technique only.

Note the drag-to-pip gesture does NOT exist to copy: `PhotoViewer.enableSwipeToPiP()` is a hard
`return false` (`:21293-21295`), so that branch (`:19497-19501`) is dead code. We build the gesture.

## TRAP 1 — the host MUST be click-through, or it swallows every tap
Model `FloatingDebugView.onTouchEvent` returning `isBigMenuShown` (`:353-355`): the full-screen host
consumes touches only where the mini player actually is. Otherwise the bottom tab bar stops working.

## TRAP 2 — the mini player's resting offset is DYNAMIC, not a constant
It must clear whichever bottom chrome is present, and that changes per screen and animates:
- root fragment visible → `navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS)`
  (`DialogsActivity.java:291`), and `MainTabsActivity.checkUi_tabsPosition()` (`:945-959`) animates the
  bar in/out via `animatorTabsVisible` (`:223`) — the offset must follow it;
- web-app tabs docked → `INavigationLayout.getBottomTabsHeight(true)` (`:452`, impl
  `ActionBarLayout.java:3508-3512`), a full-width `dp(68+8)` dock that will collide at the
  bottom-right;
- other screens → zero.
Read these live; do not bake a constant.

## TRAP 3 — two players silently kill each other
`VideoPlayer` constructed with `pauseOther = true` subscribes to `playerDidStartPlaying`
(`VideoPlayer.java:208-211`) and pauses itself when any other player starts (`:216-222`, posted from
`:1692`). So a minimised player + a newly opened inline player would fight. **The single-instance rule
in D2 is what prevents this** — opening a new video must REPLACE the one instance's content, never
create a second player. Do not reach for `allowMultipleInstances` (`:161`). The same notification is
how starting playback pauses the Music tab (`MediaController.java:1985`), which the spec wants kept.

## TRAP 4 — register teardown, or leak a dead Activity + a live ExoPlayer
`LaunchActivity.onDestroyStaticResources()` (`:6917-6952`) explicitly tears down `PipRoundVideoView`,
PhotoViewer's `PipInstance`, `EmbedBottomSheet`, `GroupCallActivity` and `FloatingDebugController` — and
it is guarded by `if (activeInstanceCount == 0)` (`:6866`). A static player host that does not register
here pins a dead Activity, a TextureView and a live ExoPlayer. Also hook
`onPictureInPictureModeChanged` (`:6832-6857`), which blanket-dismisses the other overlays.
`ApplicationLoader.mainInterfacePaused` is the "backgrounded" flag.

## TRAP 5 — cancel springs on detach; recompute position on config change
`FloatingDebugView.onDetachedFromWindow` cancels both springs (`:389-397`) — copy that.
`PipVideoOverlay` additionally wraps its layout update in `try/catch (IllegalArgumentException)` and
cancels the spring (`:69-82`) because the window can vanish mid-animation. And saved pip position is
keyed by display size and nulled on config change (`:955`, `PipConfig` at `:1223`) — the mini player's
resting position must be RECOMPUTED after rotation, never restored blindly, or it lands off-screen.

## Techniques worth copying (no `ViewDragHelper` exists in this repo — zero hits)
- `SpringAnimation.TRANSLATION_X/Y` on a child view: `FloatingDebugView.java:375-397` — the right shape
  for an in-app overlay (translate a view, don't move a window).
- Drag + fling + edge-snap + swipe-off-to-dismiss: `PipVideoOverlay.java:816-950` (against
  `WindowManager.LayoutParams`, so adapt).
- The **mini↔full morph**: `BottomSheetTabsOverlay` is the blueprint — `openSheet`/`dismissSheet`
  (`:456`, `:517`) interpolate a view between two rects via
  `SheetView.drawInto(canvas, finalRect, progress, clipRect, alpha, opening)` (`:94`, used `:923`,
  `:1090`) with `AndroidUtilities.lerpCentered` (`:1035-1041`) and
  `AndroidUtilities.applySpring(animator, 260, 30, 1)` (`:504`, helper at `AndroidUtilities.java:6561`).
- `GestureDetectorFixDoubleTap` (`Components/GestureDetectorFixDoubleTap.java`) — the fork's
  `GestureDetector` with a working double-tap hook; used for pip's ±10 s seek (`PipVideoOverlay.java:746`).
- `VelocityTracker` + `OverScroller` + slop-based vertical/horizontal disambiguation:
  `BottomSheetTabsOverlay.java:110-124, 165-271`.

## Deferred, confirmed
Background audio needs a foreground service (`MusicPlayerService`, gated by
`MediaController.canStartMusicPlayerService()` at `:4323-4325`, which covers music/voice/round video
only). Out of scope: backgrounding PAUSES. Video cannot sensibly reuse `MediaController` — its video
path is hardwired to round video messages (`:3462-3500`).
