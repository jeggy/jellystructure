# Phase R299 — A cast that could not start says so

## Status

`Planned` — written 2026-09-24, not dev-reviewed. Spec first. The follow-up R297's Non-goals named.
Amends **R245** FR-R245-9's *Receiver unreachable* state (its wording stays, its trigger narrows).

## What happens today

When the receiver fails to load a stream (2026-09-24, bedroom TV: every cast, before R297), it stops
the session, returns to its idle screen and sends the phone `type: "ended"`. The phone then shows:

> **Lost contact with Soveværelse TV**
> It may still be playing. Ravilo cannot reach it to check. · *Retry*

Every clause is wrong. Contact is fine: the receiver just spoke. It is not playing: the receiver is
sitting on its idle screen. And *Retry* sends a `status` request the receiver answers with nothing
new, so the button does nothing visible. The viewer is told to suspect the network and their TV when
the true statement is *"this title cannot be played on that device"*.

Why the phone says it: `CastRemoteScreen` derives `unreachable` from `link != CONNECTED`, but after
the receiver's `ended` the SDK's media session is gone (`MediaStatus` null), `loaded` goes false, and
the remote's own layout for "nothing loaded" is the unreachable one — the closest state that existed.
R245's *Receiver unreachable* was written for the SDK link dropping (FR-R245-5's reconnect), not for
a receiver that is reachable and idle.

## Requirements

- **FR-R299-1 — The receiver tells the phone a load failed, as its own message.** On a load error
  (CAF `ERROR` during load, `MEDIA_FINISHED` with `endedReason == ERROR`, or the interceptor giving
  up), the receiver sends `type: "failed"` with `item_id`, `title`, `kicker` and `art_url` of the item
  that failed, then shows its idle screen. `ended` keeps meaning a real end. Older phones ignore an
  unknown type and behave exactly as today (`CastReceiverMessage.type` is a free string).
- **FR-R299-2 — The phone shows a *Couldn't play* state, not *Lost contact*.** `CastRemoteStatus`
  gains `failed: Boolean`. While the link is `CONNECTED` and the last message was `failed`, the remote
  shows the item's art (dimmed), its title, the line *"{device} couldn't play this"* and the sub-line
  *"Try another title, or play it on this phone."*, one primary action *Play on this phone* (opens
  the local player for `item_id`) and *Stop casting*. Transport is greyed as in the unreachable state.
  The mini bar hides, as it does for `ended`.
- **FR-R299-3 — *Lost contact* is only for a lost link.** `unreachable` stays `link != CONNECTED`;
  the failed state is checked before it. A receiver that is connected and idle is never "lost".
- **FR-R299-4 — Three languages.** `cast.failed` / `cast.failed_sub` / `cast.play_here` in en/da/fo,
  Faroese through the lexicon (R288).

## Non-goals

- Saying *why* (codec, bitrate). The receiver knows a Shaka code, not a reason a viewer can act on;
  R297 FR-R297-3 logs the code for us.
- Retrying on the receiver. A load that failed once fails again.
- The Tizen receiver (`ravilo-screen`), which has its own status model (236).

## Acceptance (Pixel 9 + bedroom TV)

1. With the pre-R297 receiver bundle served (every load fails): cast an episode → the TV shows its
   idle screen, the phone shows *"Soveværelse TV couldn't play this"* with the episode's art and
   title, *Play on this phone* opens the local player at 0:00, the mini bar is absent.
2. With the R297 bundle: a normal cast is unchanged (plays, *Playing on…*).
3. Pull the TV's power (or its network) mid-cast → *Lost contact* as before.
