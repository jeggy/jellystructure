# Phase R384 — With no credits marker, the next episode waits for the end

> Owner, 2026-10-11: *"Just got a new tv show where episodes are 1-5 minutes. So the next up can't be shown to early,
> but it is."* — then: *"if there is no marker then the next episode countdown should only start when there are
> 1 second left. This also means it will count while it's finished."*

## Scope

Written 2026-10-11 (dev-authored, from the owner's decision above). **Owner approved the whole spec 2026-10-11**
(*"All sound good"*): the movie card follows the same 1 s rule, and open question 1's lean is taken (FR-R384-6).
Touches the player in the app (`ravilo-ui` `PlayerScreen`, so the TV, phone, desktop and web apps), the Cast receiver
(`ravilo-cast`) and the receiver-only TV app (`ravilo-screen`), plus the shared rule in `:shared`.
**Supersedes** R111's 20 s fallback (R182 §D's "existing `NEXTUP_AT_MS`-before-end heuristic") and the *else 20 s
before the end* half of **FR-R351-13**. The marker half of FR-R351-13 is unchanged.

## What was seen

- The new show is 31 short children's songs: 22 of them are about **1 minute** long, the rest 2–10 min
  (runtimes from the prod DB, 2026-10-11). It was added at 13:30 the same day and has **no segments yet**
  (`media_segment` has no rows for it, `has_segments = 0`): `detect_segments` has not reached it. A song has no real
  credits, so even after detection most of these episodes will have no trusted marker.
- With no trusted marker, the app's player shows the card when **20 s are left** (`NEXTUP_AT_MS = 20_000L`,
  `PlayerScreen.kt` ~line 1249). It then counts down `skip_secs` (4/6/8, default 6) and, with autoplay on,
  **advances when the countdown ends**. On a 60 s song the card covers the last third, and **the last ~14 s are never
  played**.
- The Cast receiver does the same through the shared `nextUpStartMs` (`NEXT_UP_FALLBACK_MS = 20_000L`). The
  receiver-only TV app uses its own `NEXT_UP_TAIL_MS = 20_000L` with a 10 s countdown.
- R111 picked 20 s for full-length episodes with unknown credits. The guess is wrong for short episodes, and for a
  long episode it also skips the end of an episode we know nothing about.

## The rule

- **A trusted marker** (the shared `trustedCreditsStartMs`: positive, inside the duration, in the back half): as today.
  The card goes up at the marker, never later than the countdown + 2 s before the end (FR-R351-13 on the receiver),
  and the countdown runs as today.
- **No trusted marker:** the card and its countdown start when **1 second is left**, or at the real end of the file,
  whichever comes first. The countdown always runs its **full length**. When the file finishes during the countdown,
  the countdown keeps going over the finished picture. Then the next episode starts (autoplay on), or the card waits
  for the viewer (autoplay off), as today.

The episode is played to its last frame. The next one comes `skip_secs` later, not `skip_secs` earlier.

## Requirements

### FR-R384-1 — One rule, in `:shared`

`nextUpStartMs(durationMs, creditsStartMs, countdownSecs)` returns `durationMs − 1 000` when there is no trusted marker
(the new `NEXT_UP_NO_MARKER_MS = 1_000L` replaces `NEXT_UP_FALLBACK_MS`). The countdown clamp
(`− countdownSecs·1000 − NEXT_UP_END_MARGIN_MS`) applies **only to a trusted marker**. A file shorter than 1 s returns
0. The no-marker point is this one shared value in every player and receiver; none keeps its own 20 s constant. (The
Cast receiver asks `nextUpStartMs`; the app's player and the screen app keep their own marker rules, which this phase
does not change, and use `NEXT_UP_NO_MARKER_MS` for the no-marker case.)

### FR-R384-2 — The app's player (`PlayerScreen`)

- The early trigger uses FR-R384-1: with no trusted marker it fires when `durationMs − positionMs ≤ 1 000` (still
  `> 0`). `NEXTUP_AT_MS` is deleted.
- The natural-end trigger stays as the safety net. If the 1 s point was never seen (500 ms poll, a stream that ends
  a little before its stated duration, a seek past it), the card and the full countdown start at the end.
- When the file ends during the countdown, nothing else changes. The last frame stays on screen, the card keeps
  counting, and the countdown is not restarted or cut short. (R351 already notes that this player waits on the last
  frame. This FR keeps it that way.)
- Skip Credits **Off** (R230): unchanged. The early trigger stays suppressed and the end is the only trigger.
- The last episode or a movie with no marker (the *Skip credits* card, no countdown) goes up at the same 1 s point.
  This is one trigger for the card, not two. A guessed 20 s *Skip credits* offer on a title with unknown credits is
  the same mistake.

### FR-R384-3 — The Cast receiver (`ravilo-cast`)

- The card goes up at `nextUpStartMs` (FR-R384-1). The log line (`nextup at …`) is unchanged.
- **On a real end (`END_OF_STREAM`) while the countdown runs, the countdown is not cancelled.** The receiver keeps the
  last frame (or the card over black if the platform clears the video) and loads the next episode when the countdown
  ends. Today `onFinished` cancels `nextUpJob` and loads the next episode at once.
- On a real end with a next episode, autoplay on and **no countdown started yet**, the countdown starts at the end
  instead of loading the next episode at once. The phone's remote mirrors it as today (`nextup` messages).
- The stop report for the finished item is sent at the end as today (position = duration). The countdown does not keep
  the finished session open. Back/Stop during this countdown behave as during any countdown.
- Skip Credits Off and autoplay off: unchanged (the receiver's current end handling).
- **A status during the countdown carries its seconds** (`nextupSecs`, `nextTitle`): the player's IDLE after the end
  makes the receiver send a status, and without them the senders would read the finished media as the end of the cast.
  Additive on the wire.
- **The senders** (`mergeCastStatus`, shared by the phone and the Mac): while the receiver's last word is a countdown
  (a `nextup`, or a status with `nextupSecs`), finished media is **not** *ended* — the card, the mini bar and the remote
  stay (loaded, the last play state) until the next episode's status or the receiver's *ended*. An installed app older
  than this shows *ended* under the countdown for those seconds; the next episode still loads.
- **A cancelled countdown stays cancelled** for that item (found while building): the receiver had no latch, so the
  next time update after the phone's *Watch credits* put the card straight back. Now the item is latched (cleared by a
  sender's new play, kept across a restream), and its end is an end (*ended*, idle). A cancel after the end is that end
  at once.
- Any new load (a sender's, the next episode, a restream) ends a running countdown, so one running on past a film's end
  can never load "the next episode" over what a sender started meanwhile.

### FR-R384-4 — The receiver-only TV app (`ravilo-screen`)

- `NEXT_UP_TAIL_MS` is deleted. With no usable marker the card is due at 1 s left (FR-R384-1, with its own 10 s
  countdown passed in).
- `onFinished` with a countdown running lets it finish (holding the last frame) instead of calling `playNext()` at
  once. With no countdown yet, it starts one, the same as FR-R384-3.
- Built in the same change (plain Kotlin/JS). Its device check waits until Tizen work resumes.

### FR-R384-6 — The preload waits for half way (owner 2026-10-11, was open question 1)

R381's preload of the next episode with no trusted marker starts at `max(duration − 60 s, duration / 2)` instead of
60 s before the end, so a short episode's own start is not competing with the next one. With a marker: unchanged.

### FR-R384-5 — Tests

- `NextUpTriggerTest`: no marker ⇒ `duration − 1 000` for any countdown (4, 6, 8, 20). An untrusted marker (first
  half, past the end, 0) ⇒ the same. A trusted marker ⇒ unchanged, incl. the clamp. A 60 s file ⇒ 59 000. A 500 ms
  file ⇒ 0.
- A `PlayerScreen`-level or state-machine test (wherever the existing auto-advance tests live): a 60 s item with no
  marker and autoplay on plays to its end and advances `skip_secs` after the end, not before. An item that ends
  before the 1 s point is seen gets the full countdown from the end.
- A receiver test for FR-R384-3's end handling (finish during a countdown ⇒ one load, after the countdown; finish
  before a countdown ⇒ countdown, then one load).

## Acceptance

1. On the phone (Pixel 9, debug build), a 1-minute episode with no marker plays to its last second. The card shows
   with 1 s left, keeps counting over the finished picture, and the next episode starts `skip_secs` after the end.
2. The same on a TV app (debug build) and when cast to a Chromecast.
3. A full-length episode **with** a trusted marker behaves exactly as before: card at the marker, countdown, advance.
4. A movie with no marker shows its *Skip credits* card in the last second, not 20 s early.

## Not changed

- Segment detection, and when the scan reaches a new show.
- The countdown length (`skip_secs`; the screen app's 10 s), autoplay, Skip Credits Off, the stinger case.
- What counts as *finished* (R347's `playbackFinished`: the marker, else 90 %).

## Open questions (dev)

1. ~~R381's no-marker preload point~~ — decided by the owner 2026-10-11: FR-R384-6.
2. Whether the Cast receiver's video element keeps its last frame after `MEDIA_FINISHED` on the Chromecast HD and
   the Google TV streamer, or goes black. Either is acceptable (the card is the point), but a black frame should be
   deliberate, not a flash.

## Build notes (2026-10-11)

- **`:shared`** `NextUpTrigger.kt`: `NEXT_UP_NO_MARKER_MS = 1_000L` replaces `NEXT_UP_FALLBACK_MS`; `nextUpStartMs`
  clamps to the countdown only for a trusted marker. `NextUpTriggerTest` rewritten for the new rule (6 tests).
- **`PlayerScreen.kt`**: `NEXTUP_AT_MS` deleted; the early trigger fires at `NEXT_UP_NO_MARKER_MS`; nothing else needed —
  the countdown (`LaunchedEffect(nextUpVisible)`) does not look at the player's state, the natural-end trigger already
  starts the card when the last second was missed, and the player keeps its last frame when ended. The preload point is
  FR-R384-6.
- **`ravilo-cast` `Receiver.kt`**: `onFinished` keeps (or starts) the countdown on a real end with autoplay and sends
  the item's stop once; `loadEpisode` does not send a second stop for it; `endNextUp()` is the one way a countdown
  ends (the load interceptor, a sender's stop, the dashboard's stop, *play now*, cancel); `nextUpCancelledFor` latch;
  `endedHere()`; status carries `nextupSecs`/`nextTitle` while the countdown runs.
- **`CastStatusMerge.kt`**: the `counting` rule; one new test in `CastStatusMergeTest` (12 tests).
- **`ravilo-screen` `Screen.kt`**: `NEXT_UP_TAIL_MS` deleted; `onFinished` keeps (or puts up) the card instead of
  `playNext()` at once, `playNext` stops a finished title at its length, a cancel after the end ends it (`endHere()`).
- Verified here: `:shared:desktopTest`, `:ravilo-ui:desktopTest` (all green), `:ravilo-cast:compileKotlinJs`,
  `:ravilo-screen:compileKotlinJs`, `:ravilo-web:compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid`,
  `:ravilo-ui:compileKotlinDesktop`. **Not run on any device**; the acceptance list is owed. The screen app's check waits
  for Tizen work to resume. The Cast receiver ships with the backend image (next deploy); the app with the next release.
