<!-- Owner's plan for making the reels feed feel instant, written up from the 2026-08-17 session.
     Every number in here was measured that day on dev + prod, not estimated. Where the owner's
     proposal needs a correction it is flagged **[CORRECTION]** and the reason is given. -->

# Svipe instant feed — plan

Goal, in the owner's words: **the user should never see a loading progress.**

Four proposals, in the order they were raised. Each section says what exists today (with evidence),
what changes, and what I disagree with and why.

---

## 0. Two decisions that shape everything

**D1 — Playback never resolves. Only interaction resolves.**

A reel under ~20 MB has a second, sessionless route to its bytes: the public embed on
`t.me/<channel>/<id>?embed=1` exposes a plain HTTPS mp4 on Telegram's CDN, and any player can stream
it with Range requests. This is already in production for the guest surface and the share page
(`app/content/public_media.py`, `CEILING_BYTES = 20 * 1024 * 1024`).

Measured 2026-08-17, same device, same feed:

| | via MTProto | via the embed |
|---|---|---|
| make one reel playable | **1,746 / 1,813 / 1,875 ms** | **140 / 203 / 384 ms** |
| flood risk | the recurring one — it has taken music and reels down for hours twice | none |

So a signed-in user should play through the same route a guest does. `contacts.resolveUsername` and
`channels.getMessages` are then spent only when somebody LIKES, comments, follows, saves, opens a
channel or shares — actions, not watching.

What this costs: the quality ladder. MTProto offers Telegram's rungs (`SvipeVideoLadder`), the embed
offers one mp4. For clips of a few seconds and a few megabytes that is an acceptable trade. Above
the ceiling there is no sessionless path at all, so films and episodes keep MTProto — a different
surface, unaffected.

**D2 — The feed is read, never computed, on the request path.**

`build_feed` measured 436 ms on a quiet dev box and 3,066 ms on a loaded one. Nothing a user waits
for should contain that. The feed becomes a Redis read; the computing happens before anybody asks.

---

## 1. Baseline — where the time actually goes

Measured 2026-08-17 on dev. "Sources" were timed one at a time for attribution; they run in
parallel, so they do not add up to the total.

**Signed-in user, quiet box (load 17)**

| step | ms |
|---|---|
| `user_state` (Redis) | 3.6 |
| `clusters` | 140.7 |
| `graph_cf` | 111.9 |
| `fresh` | 44.8 |
| `trending` | 26.3 |
| `seen keys` (Redis) | 18.8 |
| **`build_feed` total (parallel)** | **436** |
| `attach_share_urls` | 56 |
| `attach_thumbs` | 18 |

**Guest, quiet box**

| step | ms |
|---|---|
| `user_state` + `seen` (Redis) | 7.0 |
| pools (topic 1 / 2 / 9 / global) | 79 / 192 / 82 / 27 |
| **`build_feed` total (parallel)** | **1,580** |
| gate re-check + share codes | 70 |

**Loaded box (load 29), same code:** `clusters` 3,750 ms, `graph_cf` 5,064 ms, user `build_feed`
3,066 ms. The box multiplies individual sources by up to 30×.

**Cold start, app side, guest, from `pm clear` (three runs):**

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| process up | 1,210 | 1,045 | 944 |
| device token | 1,016 | 449 | 695 |
| feed | 580 | 359 | 223 |
| media URL | 351 | 336 | 140 |
| bytes (Telegram CDN) | 1,766 | 1,579 | 1,199 |
| **first frame** | **4,327** | **3,165** | **2,710** |

**Prod telemetry before this session:** `feed_latency` p50 **13,361 ms** over 33 real samples;
`ttff` p50 41 ms.

---

## 2. Proposal 1 — precomputed feed in Redis, with an A/B swap

**As asked.** With no data at all, serve recent popular videos, ready in Redis. Any signal from a
user starts building "B" in Redis; "A" keeps being served until B is ready, then B replaces A. A
like restarts the cycle. The user always reads from Redis.

**What exists.** The pattern is already in the codebase: `store.put_grid` / `get_grid` back the
discover grid. Nothing equivalent exists for `/v1/feed`.

**[CORRECTION] The trigger cannot be every event.** A session sends ~20 events. Rebuilding per event
means 20 × `build_feed` per user per session — 20 × 3,066 ms of database work on a loaded box, for
one person. Rebuild when either:

* the served page is more than half consumed, or
* a strong signal arrives (LIKE, NOT_INTERESTED, BLOCK_CHANNEL),

and at most once per ~10 s per user.

**[CORRECTION] The cold list is shared, not per-user.** "No data at all → recent popular" is ONE
global list for every new device, rebuilt every few minutes. `discover_candidates` already produces
exactly this shape (deterministic, most-viewed, offset-pageable). This is the cheapest item in the
whole plan and it makes a brand-new user's first feed a single Redis GET.

---

## 3. Proposal 2 — fetch the first feed the moment the app opens

**As asked.** Both guest and signed-in: pull the initial feed at app start. If there is a Telegram
account but no Svipe token yet, pull a GUEST feed — the user opened the app to watch, so it must be
ready. Keep two videos prepared in RAM even if they never open the Reels tab; entering it starts
playback with no wait. The moment our token arrives, request a fresh personalised feed; when it
lands, use it from video 3–4 onward and drop the tokenless items the user has not opened (if they
opened one, drop the other) — an already-seen or already-disliked video must not come back.

**What exists.** The guest half was built and measured on 2026-08-17: `SvipeGuest.warmUp()` is
started from `LaunchActivity` the moment it knows there is no account, and the screen takes the
warmed page instead of racing it. First frame 2,710–4,327 ms from a cold install.

For signed-in users `SvipeWarmUp` / `SvipeReelWarmer` already warm the feed at app start — but they
wait for the auth chain, which measured **1,386–4,580 ms** (bot lookup + `/v1/auth/telegram/webapp`).

**Why the guest-feed-while-authenticating idea is the biggest single win here.** It removes the auth
chain from the critical path entirely: the user watches while the token is still being minted.

**[ADDITION] Attribute the early events.** Events for those first videos must reach the real user
once the token lands, or the recommender learns nothing from the first two things they watched.
Buffer them client-side until the token exists, then send with it.

---

## 4. Proposal 3 — one download policy, a prefix, and one local store

**As asked.** Never download a full video. The first ~5 seconds is enough. There is no separate
`cold_start` concept: bytes fetched for the live feed ARE the cold-start cache. Feed arrives → RAM →
local storage immediately; the same for the Telegram bytes. Nothing is deleted before it has been
watched, and it is kept for a while afterwards so swiping back is instant; app restart, or
Telegram's own Storage Usage, is what clears it. When a fresh feed replaces the old one mid-session,
the items past the user's position are swapped out — but anything already prefetched stays on disk
as unseen, usable for a cold start or a bad connection.

**What exists.** More than the proposal assumes:

* `SvipePreloadPlan` already head-preloads an ahead window at graded priority and cancels preloads
  that fall out of it;
* reels BEHIND the current one are never re-downloaded — their bytes stay in Telegram's cache, so
  swiping back is already instant, and eviction is already Telegram's size-based job. That is
  exactly the behaviour asked for;
* **but** `ReelsActivity` still does a **full download on Wi-Fi** (`only when full downloads are
  allowed (Wi-Fi)`), which is what the proposal objected to.

**[DECIDED 2026-08-17 — the full download STAYS.]** The owner ruled against deleting it. It is not a
stray optimisation: it is what fills the persisted offline queue, a shipped feature whose whole
purpose is instant cold-start playback with no network at all. The proposal and that feature were
asking for opposite things — "never download a full video" against "full-download always" — and the
offline queue wins. This paragraph exists so the contradiction is not re-litigated: point 1 below is
**cancelled**, deliberately, and anyone who reads §4 and reaches for the delete key should stop here.

What the perf work took out instead was the *resolving*, not the downloading. Neither the ahead
window nor the offline cushion will now spend a `contacts.resolveUsername` on a reel that already
carries a `play_url` — that was the scarce budget going to the least urgent work.

**Changes.**

1. ~~Delete the full-download path.~~ **CANCELLED** — see the decision above.
2. Make the prefix duration-aware. The head-preload is a flat ~2 MB today: far too much for a
   6-second clip and too little for a 5-minute one. MTProto cannot be asked for "5 seconds" — it
   fetches byte ranges — but `supports_streaming` guarantees the `moov` atom is at the front, so
   `size × 5 / duration` (capped) is the right ask.
3. One store. Bytes fetched for the live feed are the cold-start cache; `cold_start` stops being a
   separate concept.

**[CORRECTION] Not all 50. A bounded window.** Under MTProto each reel needs its channel resolved
before a single byte moves, and that budget is the one this project has burned twice
(`SvipeChannelResolve` exists because of it). Fifty videos is fifty resolves.

**This correction mostly dissolves under D1.** Over the sessionless route there is no resolve at all
— just an HTTPS GET — so a wide prefetch window becomes cheap. The window still stays bounded by
bandwidth and battery rather than by flood risk: **5 deep, graded by priority**, widening on Wi-Fi.

---

## 5. Proposal 4 — the media URL travels WITH the feed

**As asked.** For anything under 20 MB, do not resolve — even for a signed-in user. Resolve only
where we agreed it is genuinely needed. Prefetch the next five videos, current highest priority,
fifth lowest, so a swipe finds everything already in order.

**[CORRECTION] Do not call `/v1/guest/media/{code}` per video.** That endpoint scrapes the public
embed behind a **process-wide lock with a 0.4 s minimum interval** (`_MIN_INTERVAL_S = 0.4`,
`_URL_TTL_S = 15 * 60`). It is correct for one shared video on a web page. If every reel of every
signed-in user goes through it, that single lock becomes the bottleneck for the entire app.

**Put the URL in the feed response instead.** Then the client makes NO per-video call: the feed
arrives carrying twenty playable HTTPS URLs and prefetching starts immediately. That is strictly
better than both current paths, and it is what actually delivers "no loading progress".

For that, the server must hold warm URLs for its top candidates — a background job of the same shape
as the poster worker, which already fills pictures for exactly the references that get served.

**Coverage.** On prod, 184,797 gate-allowed reels are under the ceiling with a known size; 852,298
have no recorded size (the web indexer reads `t.me/s/`, which does not state file sizes). The client
rule is therefore simple: **play the URL if the feed carried one; resolve only if it did not.**

**A consequence worth naming.** Once playback is sessionless for everyone, the "guest feed while
authenticating" of §3 stops being a stopgap and becomes the permanent architecture: guest and
signed-in play through the identical path, and the only difference left is who chose the list.

---

## 6. Order of work

1. ~~**Shared cold list in Redis** (§2).~~ **SHIPPED to dev, 2026-08-17.** `app/recsys/coldlist.py`.
2. ~~**Media URL in the feed payload + warm-URL worker** (§5).~~ **SHIPPED to dev, 2026-08-17.**
   `app/content/play_urls.py` + a `play_urls` lane in the sessionless indexer; client side in
   `ReelsActivity` and `SvipeReelWarmer`.
3. **Guest feed while authenticating** (§3). Biggest cold-start win for signed-in users.
4. **Duration-aware prefix; 5-deep graded window** (§4). PARTLY done: the ahead-window and the
   offline cushion no longer RESOLVE reels they could already play. Killing the Wi-Fi full download
   was **cancelled by the owner** — the offline queue depends on it (see §4).
5. **Per-user A/B precompute with debounce** (§2). Most work, and it only pays once 1–4 are done.

### What shipped, and what it measured

Everything here was measured on 2026-08-17 against dev, not estimated.

**The URL reaches the client and the client uses it.** A device with no history asks for `/v1/feed`
and gets the shared cold list — `source: discover` — with **18 of its 20 items already carrying
`play_url`**, because building that list also queues its 300 references for the worker. On the
emulator, one cold start and three swipes:

    svipe: play pos=0 source=public-url
    svipe: first frame pos=0 in 1360ms prepared=false
    svipe: prepared next player pos=1 from public url
    svipe: first frame pos=1 in 6ms prepared=true
    svipe: prepared next player pos=2 from public url
    svipe: first frame pos=2 in 5ms prepared=true
    svipe: prepared next player pos=3                  <- no URL for this one
    svipe: first frame pos=3 in 15ms prepared=true

**`contacts.resolveUsername`: zero calls in that session.** Twelve `channels.getMessages` remain,
filling action rails behind reels that are already playing. That distinction is the whole point —
`getMessages`/`getHistory` were open on the afternoon this was written while `resolveUsername` was
locked out for 4,122 seconds. Position 3 is the fallback working correctly: no URL, so it took the
old path.

**The worker drains continuously.** The `play_urls` lane in the sessionless indexer went from 96 to
214 filled references on dev while this was being written, going through the same request meter as
the rest of that process. A fill pass measured 18 of 19 filled; the misses are audio posts (no
`<video src>` in the embed) and posts over the ceiling, and each one is a tombstone rather than a
repeated scrape.

**[CORRECTION] Demand-driven filling is one page behind, and for one user it never catches up.**
A feed serves 20 references, records that none had a URL, and the worker fills them — but the seen
filter guarantees that user's next page holds *different* references, so they never see the benefit
of their own demand; somebody else does. This is why the cold list warms its own URLs on build. It
is the one list that is shared, deterministic and served to every new device, so warming it once is
what makes a first feed playable without a session.

**[DECIDED] The Wi-Fi full download stays.** §4 proposed deleting it; the owner ruled against on
2026-08-17, because that download is what fills the persisted offline queue. The decision and its
reasoning now live in §4 itself, where anyone reading the original proposal will meet it. What did
change is that neither the ahead window nor the cushion will RESOLVE a reel that already carries a
URL: that was spending the scarcest budget on the least urgent work.

---

## 7. What this plan does not promise

"Never sees loading" is achievable in normal use, not absolutely. Two floors remain and should be
stated rather than designed around:

* **the very first launch** — something must arrive before anything can play; measured down to
  2,710 ms, not to zero;
* **swiping faster than the prefetch** — if the user outruns the window, they wait.

Everything else is reachable.
