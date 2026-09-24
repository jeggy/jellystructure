# Phase R290 — pressing Play or Resume is one moment, not three

> Owner, 2026-09-24, on the re-entry finding: *"Let's write a spec for this, so it's much nicer and
> proper. We should provide the very best experience for our users."*

## Status

`Planned` — written 2026-09-24 from the soveværelse-TV sweep (Play Store v1.36 and a debug build of
`1.37-6-g2c2aca48`, both reproduced). Not dev-reviewed, not built. Pairs with **R291** (instant audio
switching), which removes the most common cause of the third moment below.

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

## Verification
- Unit: the derived "what is on screen" state for the start sequence (pure helper).
- Device, soveværelse TV and Pixel 9, release build: resume a transcoded title with a non-default
  remembered audio, and a direct-play title; frame-capture the first 15 s; no frame shows chrome or
  0:00 before the film. Navigate Back during the start and after it.
