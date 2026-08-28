# Phase R218 — The player must show it is working, without ever saying why it is slow

> Companion to **Phase 180** (server-side session teardown). Investigation
> `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md` catalogued four distinct
> moments where Ravilo waits and only one of them has a drawn treatment: the wait before a session ticket
> exists. A cold start behind a 4K transcode, a mid-playback rebuffer and a seek all currently paint
> **nothing** — the last frame simply freezes, or the screen simply stays black, with no evidence that the
> app is alive. R216's QoE capture landed the same week and gives us the counters; this phase gives the
> viewer the picture.

**Status:** Implemented (2026-08-28), Android/Compose only. Built the same day it was spec'd, compiles
clean on the Android and wasmJs targets; not yet dev-reviewed or on-device verified — none of the timing
constants have been tuned against a real TV (see Open questions #1/#2/#4, all still open).

## Build notes (2026-08-28)

- **The three signals FR-R218-1 needs** (`hasRenderedFirstFrame`, `isBuffering`, `isSeeking`) were added
  to the common `RaviloPlayer` interface and wired into the Android actual's existing single
  `qoeListener` (`onRenderedFirstFrame`/`onPlaybackStateChanged`/`onPositionDiscontinuity`), per this
  phase's own "consume that, do not add a second listener" instruction — no new `AnalyticsListener`.
  They are deliberately **separate fields** from R216's `qoeFirstFrameRendered`/`qoeSuppressNextBuffering`
  rather than reusing them: those persist across a binge's episode-to-episode player-instance reuse by
  design (cumulative QoE counters), which would silently suppress moment B on every episode after the
  first. These three reset in `load()` instead.
- **Where PlBufferMoment actually lives — a deliberate, documented deviation from the spec's literal
  text.** FR-R218-1 says "PlayerStore exposes a single derived buffering state." It's implemented in
  `PlayerScreen.kt` (Compose-side) instead: `PlayerStore` is architecturally decoupled from any concrete
  `RaviloPlayer` instance on purpose (see R216's own `qoeSnapshotProvider` injection pattern), and
  deriving a player-state-based enum inside the store would break that. The requirement's actual
  substance — one derived state, not three independently-racing overlays — is delivered by
  `PlBufferMoment` + a single debouncing `LaunchedEffect`, just one architectural layer up from where the
  spec's prose puts it.
- **wasmJs (FR-R218-6):** `hasRenderedFirstFrame`/`isBuffering`/`isSeeking` are hardcoded
  `true`/`false`/`false` — the documented fallback ("where it cannot [wire waiting/playing], falls back
  to moment A's behaviour"), not a bug. No `<video>` event wiring exists for these yet.
- **Moment D's "scrub tile"** doesn't exist as a UI element yet — `StreamTicket.trickplayUrl` is always
  null (its own future phase, per this phase's Out-of-scope section). Implemented as the nearest real
  equivalent instead: a small spinner beside the position timestamp in the seek row.
- **Moment C's chrome-forcing (Open question #2)** resolved as: force `PlayerChrome`'s `AnimatedVisibility`
  visible via `chromeVisible || stallActive` without touching `chromeVisible`/`chromeRevision`
  themselves, so R208's 30s auto-hide timer is never armed or reset by a stall and the chrome retracts
  the instant `displayedBufferMoment` leaves `STALL`. Not verified against a chrome the viewer had
  already raised manually before the stall began — should behave correctly (visibility is
  OR'd, never subtracted) but wasn't specifically exercised.
- **Not yet done / known gaps:** phone-specific scaled sizing (FR-R218-6's "40px spinner / 26px title /
  15px label") — the phone target (`:ravilo-phone`, which shares this same `PlayerScreen.kt`) currently
  renders moment B at the same fixed sizes as TV; functionally correct, not yet visually tuned per-form-
  factor. Open questions #1 (400ms debounce), #3 (frozen-frame-survives-rebuffer per decoder), and #4
  (seek debounce feel) are all still genuinely open — none of this was tested on real hardware before
  landing in this pass.
- **A related bug found and fixed in the same pass** (see phase 180's own build notes for the server
  half): `PlayerScreen.kt`'s `onDispose` called `store.stopSession(...)` directly instead of
  `store.close()`, leaving `PlayerStore.scope` — and the coroutine behind `startSession()`'s retry
  loop — running for up to ~15s after Back was pressed. Fixed by switching to `close()` (whose own doc
  comment, unchanged since before this phase existed, already said this was the intended path).

**Design reference:** `design/ravilo/Player Loading and Buffering - Directions.html` — the built,
reviewed exploration. Direction **B (Grounded)** chosen for the cold start; the stall treatment is the
frame labelled *"C (moment) · mid-playback stall — chosen"*; the seek treatment is *"D (moment) ·
seek / scrub"*. Direction **A (Quiet)** is recorded as the fallback and **C (Title card)** as rejected.
Every frame in that file is the visual target, at 1:1 TV scale plus the two phone frames.

## Why this phase exists

The owner's report was not "playback is slow" — it was that a wait with no feedback reads as a broken
app. On a 20-second transcode spin-up the TV shows a black screen with nothing on it, and the reasonable
conclusion is that the press didn't register. Three separate stutter investigations later, the underlying
delivery work is being fixed by 177/178/R216 — but even a perfectly negotiated stream sometimes waits,
and when it does, the app must look like it is working.

The constraint that shapes everything here is **R180 FR-RV-ASP1-2**: nothing the viewer sees may vary by
delivery method. That rules out the obvious "helpful" designs — no "Preparing your stream", no
"Transcoding", no percentage, no estimated time, no spinner that looks different for direct play. The
solution has to reassure using motion and context that are true regardless of how the bytes arrive.

### The four moments

| # | Moment | Today | This phase |
|---|--------|-------|-----------|
| A | Session negotiation (before a ticket exists) | `.pl-buffer` spinner + `"Loading…"` | unchanged |
| B | Cold start (ticket in hand, no first frame) | **nothing** — black | Direction B overlay |
| C | Mid-playback rebuffer | **nothing** — frozen frame | chrome-up treatment |
| D | Seek / scrub target resolving | **nothing** — chrome can blank | spinner in the scrub tile |

## Requirements

### FR-R218-1 — One buffering signal, three presentations

`PlayerStore` exposes a single derived buffering state, not three booleans. It distinguishes the three
moments by what else is true at that instant — whether a first frame has been rendered, and whether a
seek is in flight — and the UI picks a presentation from that. There must not be three independent
timers or three independent overlays that can race each other on screen.

- **Moment B (cold start)** — full-screen on black: the three-dot brand pulse, the title context
  (series name · episode title · `Season N · Episode M`, or the film's title alone), an indeterminate
  gradient sweep, and the string `"Loading…"`. The title context is catalog data the viewer *already saw
  on the detail page they pressed play from* — it carries no delivery information and is what makes the
  screen read as "the right thing is starting".
- **Moment C (stall)** — the frozen frame stays, dimmed ~38%, and **the transport chrome comes up on its
  own**: title, position, remaining, the progress bar with its buffered-ahead band, and the transport
  row with **a spinner standing in the play button's place**. The viewer keeps their position; the only
  thing moving is the buffered band. No centre overlay, no text.
- **Moment D (seek)** — no overlay and no words at all. The scrub tile carries a small spinner while its
  frame resolves, so the transport never blanks. A seek is an answer to the viewer's own input, not an
  interruption of it.

### FR-R218-2 — Debounce, so a fast start never flashes

No buffering presentation may appear before **~400 ms** of continuous waiting, for B, C and D alike. A
direct play that starts immediately must show nothing at all — a spinner that flashes for 200 ms is
itself a defect, and is the most likely way this phase makes things worse rather than better.

Moment A keeps its current undebounced behaviour; it is a different code path with a different origin and
is out of scope.

### FR-R218-3 — Deepen at 60 s, explain nothing

A wait that passes **60 seconds** is pathological (the measured worst real start was ~20 s). At that
point:

- Moment C dims further and raises the centre spinner, because the chrome alone stops being enough of a
  signal.
- The wording does **not** change. There is no second-stage message, no "still working", no cause.

There is **no escape hatch, no prompt and no cancel affordance** — see FR-R218-5.

### FR-R218-4 — One string, already translated

Every presentation that shows text shows the **existing** `"Loading…"` / `"Indlæder…"` / `"Ledur inn…"`
string from `ravilo-i18n.js`. No new locale keys, no new copy review, no drift between the three
moments, and no wording that could imply a cause. Moments C and D show no text at all.

### FR-R218-5 — Back always works, is never advertised, and ends the session

Back is available at every moment of every wait, exactly as it is today, and it is **never prompted**.
Drawing attention to the exit implies that leaving is costly or that the app expects to be abandoned;
both are the wrong message, and an earlier draft of this design was rejected on exactly that basis.

Leaving **tears the session down**: the client stops the player, releases the ticket and reports the stop
before navigating away. It must not leave a stream preparing for a viewer who has gone to watch something
else. The server half of this — actually releasing an in-flight transcode — is **Phase 180**; this phase
owns the client's obligation to signal it promptly and unconditionally, including when no first frame was
ever rendered.

### FR-R218-6 — Phone and web

Same three parts, scaled: 40 px spinner, 26 px title, 15 px label; moment C's chrome shrinks to the phone
transport with the same spinner-in-the-play-button rule. Leaving is the platform back gesture or button
and ends the session identically. The wasm player needs its own `waiting`/`playing` wiring to produce
FR-R218-1's state; where it cannot (no equivalent event), it falls back to moment A's behaviour rather
than inventing one.

## Invariants

- **Nothing varies by delivery method** (R180 FR-RV-ASP1-2). The same overlay, string and timings cover
  an instant direct play, an audio-only transcode and a 20-second 4K transcode. This is the binding
  constraint of the phase.
- **No numbers, ever.** No percentage, no bitrate, no ETA, no buffer seconds — consistent with Ravilo's
  standing rule that the viewer never sees delivery mechanics (R216 invariants).
- **A fast path shows nothing.** The debounce is not a nicety; an unnecessary flash is a regression.
- **Ravilo screens paint as one atomic frame** (constitution / no-flicker rule). The overlay
  cross-fades in and out over ~250 ms; never a hard cut from spinner to picture.
- **Motion, not words, is the reassurance.** Every long wait keeps something moving so it cannot be
  mistaken for a frozen frame — without a single character that hints at why.

## Out of scope

- **Making the wait shorter.** That is 177/178/R216's work; this phase assumes the wait exists.
- **Any cancel / retry / "try lower quality" affordance.** Ravilo exposes no quality controls (R216
  invariant), and retry belongs to the existing `PlayerSessionState.Error` overlay, which is unchanged.
- **Moment A's negotiation spinner.** Different path, already drawn, no reported problem.
- **Surfacing R216's QoE counters to the viewer.** They are admin-side diagnostics.
- **Trickplay / preview-image generation for the scrub tile.** FR-R218-3's spinner covers the tile while
  whatever image pipeline exists resolves; producing better preview frames is its own phase.
- **Tizen.** Follows once the Compose targets ship, as with R189's precedent.

## Source references

- `ravilo-ui/src/commonMain/…/screens/PlayerStore.kt` — `PlayerSessionState`, the existing `Loading` /
  `Error` states and the single capability construction site; FR-R218-1's derived state belongs here.
- `ravilo-ui/src/commonMain/…/screens/PlayerScreen.kt` — the transport chrome, its auto-hide timer
  (R208) and the existing `.pl-buffer` treatment; moments B/C/D all render here.
- `ravilo-ui/src/androidMain/…/seams/RaviloPlayerAndroid.kt:42-53` — `ExoPlayer.Builder`; R216's
  `AnalyticsListener` already observes exactly the state transitions FR-R218-1 needs (`isPlaying`,
  `onPlaybackStateChanged`, `STATE_BUFFERING`) — consume that, do not add a second listener.
- `ravilo/ravilo-player.css` `.pl-buffer` / `ravilo/ravilo-player.js` `renderPicker` — the mockup-side
  vocabulary the design file extends.
- `ravilo-ui/src/commonMain/…/i18n` (`ravilo-i18n.js` mirror) — the `"Loading…"` key reused by FR-R218-4.
- Related: **Phase 180** (server session teardown — FR-R218-5's other half), **R216** (the state
  transitions and QoE counters), **R180 FR-RV-ASP1-2** (the no-delivery-cues invariant), **R207**
  (generation-guard + Retry on the detail-screen Loading state — the precedent for a guarded loading
  state), **R206** (Loading text not centred — the last time this vocabulary was touched), **R211/R212**
  (startup latency; the debounce must not be worked around by delaying playback).
- `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md` — the four-moment
  catalogue and the seven open questions this phase answers.
- `design/ravilo/Player Loading and Buffering - Directions.html` — the chosen frames, at scale.

## Open questions

1. **Is 400 ms the right debounce?** Chosen as the shortest delay that reliably suppresses a direct-play
   flash without making a genuine wait feel unacknowledged. Untested on a real TV; the number should be
   tuned against R216's rebuffer telemetry once data accumulates, not guessed at again.
2. **Does raising the chrome on a stall fight R208's 30 s auto-hide?** The chrome coming up by itself is
   a system action, not a user one. It should not arm or reset R208's timer, and it should retract when
   playback resumes — but the exact interaction with a chrome the viewer had *already* raised manually
   needs deciding at build time.
3. **Does the frozen frame survive a rebuffer on all decoders?** Moment C assumes the last frame stays on
   screen. If any hardware path clears the surface instead, that case needs black + the moment-B overlay
   as a fallback, decided per-target.
4. **Should a seek that resolves instantly still show the tile spinner?** FR-R218-2's debounce says no,
   but the scrub tile is a smaller, quieter surface than a full overlay and a shorter debounce may feel
   better there. Worth a look on-device.
