# Phase R290 — pressing Play or Resume is one moment, not three

> Owner, 2026-09-24, on the re-entry finding: *"Let's write a spec for this, so it's much nicer and
> proper. We should provide the very best experience for our users."*

## Status

`✓ Built` 2026-09-25 from the dev review below (all seven items). **Frame-captured on the stue TV 2026-09-25
07:29** (release build `1.38-14-g34d63619-dirty`, dex guard 230/250), a direct-play episode resumed from the
detail page: at 1.3 s the start screen alone (pulse · `S1 · E1` · title · sweep · *Loading…*, no chrome, no seek
bar); at 2.5 s the film with the chrome at **0:49**, the resume point — never 0:00; Back out clean. The
transcode-with-remembered-audio case (FR-R290-4's restream) was not reproduced in this capture and stays on the
owner's device list. `Planned` when written 2026-09-24 from the soveværelse-TV sweep (Play Store v1.36 and a debug
build of `1.37-6-g2c2aca48`, both reproduced). **Dev-reviewed 2026-09-24 against `main` `9d2636bb`.**
Pairs with **R291** (instant audio switching), which removes the most common cause of the third moment
below.

### Build (2026-09-25)

- `PlayerStart.kt` (new): `StartPhase { BLACK, START, PLAYING }` from `startPhase(latched, startScreenDue)`
  and the latch `startLatchOpens(sessionReady, renderedFirstFrame, resolverSettled, restreamPending,
  renderedForMs)` — pure, `PlayerStartPhaseTest` (4). The latch is per **item** (item 2): the current
  stream's first frame **and** the resolver settled without asking for a restream; a silent stream (no
  track list for the resolver) opens it after a 1.5 s grace rather than never.
- `chromeVisible` starts false and `wake()` left the load effect (item 1): the chrome is raised, and its
  hide timer armed, when the latch opens in the poll loop. `positionMs = ticket.startPositionMs` beside
  `load()` (item 3). `play()` is **held** until the latch on a first start (item 4) — a mid-play restream
  plays at once, as before; the automatic audio restream marks `startRestreamPending` so the discarded
  stream never opens the latch. The video shutter also stays until the latch (no frame of a discarded
  stream). `playerBack` leaves at once while not PLAYING (item 5 holds).
- One `PlayerStartScreen` composable (R218's Direction B verbatim, R237's *still trying* line the one
  variation) from the press to the latch — negotiation, cold start, restream, retry (item 7) — and for
  R218's own COLD moment after it. The old Loading overlay only shows after the start. Extracting it took
  `PlayerScreen`'s widest method from 241 to 230 registers. Same code on the phone (item 6).

## What the viewer sees today

*Dreadful Me 4* on the soveværelse TV (86 Mbps → the backend transcodes it), resumed from the detail
page's **Resume · 85 min left**. Screens captured every ~0.8 s from the press:

| t | On screen |
|---|---|
| 0.0–0.7 s | The **full player chrome** over black — Back, the stream pill, the title, a seek bar at **0:00**, and a **pause** button (i.e. it claims to be playing). |
| 0.8–1.5 s | The same chrome, now reading **9:14** — the real resume position. |
| 1.5–12 s | R218's cold-start screen (pulse, title, sweep, *Loading…*). |
| 12 s → | The film, at 9:14, with the remembered audio. |

On a direct-play title the first two rows are shorter but still visible when the start takes over
400 ms. Three separate things are wrong.

**1. The chrome shows before anything can be shown.** `chromeVisible` defaults to `true`
(`PlayerScreen.kt:427`). R218's cold-start overlay is suppressed *by* the chrome only once it is
displayed, and it is displayed only after the 400 ms debounce (`BUFFER_MOMENT_DEBOUNCE_MS`). For those
400 ms — and for the whole of moment A, the session negotiation, which R218 leaves to its own small
overlay — the transport is drawn over black.

**2. The first number it shows is wrong.** Before the resume seek lands, `positionMs` is 0, so a resume
announces **0:00** and then jumps. A viewer who pressed *Resume · 85 min left* is told, for a moment,
that they are starting over.

**3. One press can become two starts.** When the remembered audio is not the stream's default and the
title transcodes, the first stream starts, the resolver runs on its first track list, finds the wrong
audio, and asks for a restream (R284 FR-R284-3). The first stream's startup is thrown away and a second
cold start begins — which is why the loader appears only *after* two chrome flashes. R291 removes this
cause; this phase makes sure no other cause can show it either.

### Measured again 2026-09-24, after R295
R295 put a black shutter over the *picture* until a stream's first frame (and, on a phone, until the
window has rotated). That removed the green frame and the portrait start, **not** the chrome. Frame
captures of that day's builds, bedroom and stue TVs and the Pixel 9:
- a restream's first frame is the full transport chrome over black, then R218's loader;
- the phone's first landscape frame is the chrome *and* the loader at once, seek bar at 0:00;
- a TV resume shows the chrome at 0:00 before the loader.
So FR-R290-1 and FR-R290-3 still describe exactly what is on screen.

## Requirements

### FR-R290-1 — Nothing but the start screen until the first frame
From the press until the **first frame of the stream the viewer will actually watch** is rendered, the
player shows the start screen and nothing else: no transport, no top bar, no stream pill, no seek bar.
`chromeVisible` starts `false`; it becomes `true` only when the first frame renders (and then follows
the normal auto-hide). This covers R218's moment A (negotiation) and moment B (cold start) as one
continuous presentation.

### FR-R290-2 — The start screen does not flash in, and does not flash out
R218's 400 ms debounce exists so a fast direct play shows nothing at all. Keep that: for the first
400 ms the screen is **plain black** — not the chrome, not the loader. After 400 ms, the start screen
(R218's Direction B, unchanged) fades in. Once it is up, it stays up across any restream that happens
before the first frame; a second negotiation does not restart the debounce or blink the screen.

### FR-R290-3 — The first position shown is the one you will see
Whenever a position is displayed during or right after a start, it is the **target** position
(the resume point, or 0 for *Play from start*), never the player's pre-seek 0. The seek bar's first
rendered value on a resume is the resume point.

### FR-R290-4 — A start may restream at most once, invisibly
If anything forces a restream before the first frame (R284's automatic audio, a burn-in chosen by
R181's resolver, a failed first attempt under R237), the viewer sees one uninterrupted start screen and
the film begins once. No chrome, no frame of the discarded stream, no audio from it.

### FR-R290-5 — Leaving during a start is always immediate
Back during any part of this moment leaves at once, as it does today (R218/180 teardown), including
while an internal restream is in flight — the in-flight session is torn down, not left encoding.

### FR-R290-6 — Handset and TV alike
The phone player (R244) has the same moments and follows the same rules; its own chrome defaults are
changed the same way.

## Invariants
- No new strings. R218's copy and Direction B stay exactly as drawn.
- Nothing about delivery (direct play vs transcode) becomes visible — R180 FR-RV-ASP1-2.
- `PlayerScreen`'s dex register budget (`scripts/check-player-dex.sh`): new state lives in
  `PlayerBookkeeping`, not in new `remember` locals in the composable.

## Out of scope
- Making the start itself faster (hardware encoding, R291's first-negotiation audio, phase 185's
  history). This phase is about what the wait *looks like*.

## Open questions
1. ~~Does ExoPlayer report the resume seek's first frame reliably enough?~~ **Answered 2026-09-24:** yes,
   in practice. R295's shutter is keyed on the same `hasRenderedFirstFrame` (reset at `load()`), and in
   every capture that day, direct play and HLS, first start and restream, TV and phone, it lifted on the
   first real frame of the resumed position. No frame of the pre-seek position appeared.
2. Moment A today has its own small centred overlay. Merge it into the start screen (lean: yes — the
   viewer cannot tell negotiation from buffering, and should not have to).
   **Closed — dev review item 7:** merge; R237's *retrying* line is the one variation that stays.

## Verification
- Unit: the derived "what is on screen" state for the start sequence (pure helper).
- Device, soveværelse TV and Pixel 9, release build: resume a transcoded title with a non-default
  remembered audio, and a direct-play title; frame-capture the first 15 s; no frame shows chrome or
  0:00 before the film. Navigate Back during the start and after it.

## Dev review (2026-09-24, against `main` `9d2636bb`)

The three wrongs are all in the code, one line number off. `chromeVisible` defaults `true` at
`PlayerScreen.kt:430` (the spec says `:427`). The chrome gate is `(chromeVisible || stallActive) &&
!coldActive && !(handset && locked)` (`:1797`); `rawBufferMoment` is `NONE` whenever `sessionState !is
Ready` (`:1134`, "moment A owns this wait"), so nothing can suppress the chrome during negotiation, and
after Ready `displayedBufferMoment` lags the raw moment by the 400 ms debounce (`:1148-1154`) — that is
the chrome's window over black. The 2026-08-29 comment at `:1786-1793` already names the default as the
cause and marks moment A "unrelated, out of scope". `positionMs` is written only by the 500 ms poll
(`:978`, `POLL_MS` at `:156`), never from the ticket — hence 0:00. Seven items.

1. **FR-R290-1 is two edits, not one.** The default at `:430`, and `wake()` at `:953` — called right after
   *every* `load()`, a restream's included — which sets `chromeVisible = true` and re-arms the hide timer.
   With the default alone the chrome still comes up on each load. Both move to the moment the latch in
   item 2 opens: the chrome is raised, and its timer armed, on the first frame the viewer will keep, not
   on a stream loading.
2. **FR-R290-2's blink is structural, and needs a latch per item, not per ticket.** `restreamWithSub` sets
   `Loading` (`PlayerStore.kt:228`) → `rawBufferMoment` becomes `NONE` → `displayedBufferMoment = NONE`
   **immediately** (clearing is deliberately undebounced, `:1149-1151`) → the start screen drops, the
   small Loading overlay and the chrome return, and on the new Ready the 400 ms runs again before COLD
   comes back. R237's retry does the same through `Loading(retrying = true)` (`PlayerStore.kt:208`). The
   fix is one derived start phase per **item**: `BLACK` (under 400 ms from the press), `START` (until the
   latch), `PLAYING`. The latch is two facts the screen already holds: `hasRenderedFirstFrame` for the
   *current* stream (reset on every `load()`, `:932`) **and** the resolver has settled for this item with
   no restream requested (`bk.resolvedForItemId == currentItemId`, `:736`) — "the stream the viewer will
   actually watch" is precisely the one the resolver did not send back. One pure helper returning one
   enum, unit-tested in `commonTest/…/screens` the way `recoverySeekTargetMs` is.
3. **FR-R290-3 is one assignment.** `ticket.startPositionMs` is in hand at Ready (`:933`); write it into
   `positionMs` beside the `load()`, and the first chrome frame cannot show the poll's 0 whether or not a
   tick has run. R292's dev review (item 2) puts a `start_position_ms` on the start request; once that
   lands the client knows the target before Ready and seeds `positionMs` from its own record on the way
   in. A restream already carries the position (`restreamWithSub(…, player.positionMs, …)`,
   `:727/:759/:771`).
4. **FR-R290-4's "no audio from the discarded stream" has no mechanism today — prepare, resolve, then
   play.** `player.play()` follows every `load()` unconditionally (`:950`); on a transcode the audio
   decodes ahead of the first video frame, and R295's shutter hides only the picture. Rather than a
   `setMuted` on the seam, hold `play()` until the latch opens: ExoPlayer renders the first frame on
   READY whether or not `playWhenReady` is set, and the tracks arrive at prepare, so the resolver's
   verdict and the first frame are both in hand before a sample is heard — a discarded stream is
   prepared, never *played*. (Its Jellyfin transcode still ran; R291 removes that.) Verify on a device
   that the first frame does render before `play()` on a resume seek; if it does not, mute until the
   latch is the fallback — a `setMuted(Boolean)` seam member, `exo.volume` on Android, `video.muted` on
   the web. Also: "a burn-in chosen by R181's resolver" is not a real cause — the resolver never
   auto-starts a burn-in (`:764`). R284's audio restream and R237's retry are the two.
5. **FR-R290-5 already holds, through Phase 180.** Back during any wait is `onBack()` at `:1371`
   (`sessionState !is Ready || COLD`); `close()` stops the session first and cancels the scope after
   (`PlayerStore.kt:341-350`); both restream paths register with `playbackTracker.started`
   (`PlaybackService.kt:940`, `:1003`), so a stop that lands before the restream does is caught by
   `pendingStops` (`:162`, FR-180-3). Keep the acceptance line; nothing to build.
6. **FR-R290-6 is the same default.** There is one `chromeVisible` for both chromes; the phone differs
   only in `!(handset && locked)` and R244's timer. "Its own chrome defaults" is item 1's edit, once. The
   phone's "chrome and loader at once" is the same 400 ms window under `rememberWindowShapeSettled`'s
   shutter (`:2034`).
7. **Open question 2 closes: merge, and keep one line.** The Loading overlay (`:1678-1700`) and the COLD
   screen (`:1625`) become the one start screen of item 2; R237's *retrying* line (`:1697`, FR-R237-5) is
   the only variation and stays. The invariant's "new state lives in `PlayerBookkeeping`" (`:264`) is
   right; add that the derived phase is one `val` from a pure helper — every extra derived local in the
   body also spends registers.

**Net effect.** Two chrome edits, one assignment, one enum + helper, `play()` deferred to the latch, one
overlay merged. No new strings, no seam change unless item 4's device check says otherwise.
