# Ravilo player: the missing buffering/loading states

**Date:** 2026-08-28

> Live-tested the day's Phase 177/178 delivery-aware negotiation fix against `Longlegs` (93 Mbps HEVC,
> exceeds stue TV's 60 Mbps decode ceiling) on stue TV. The negotiation fix works — Jellyfin correctly
> transcodes via hardware NVENC instead of the old broken direct-play. But the viewer (owner) hit play,
> saw a black screen for 3-7 seconds with zero feedback, assumed it had failed, and walked away — while
> the stream actually started fine ~20 seconds later and played unattended. Confirmed against real
> logcat timestamps (§2). This report catalogs every buffering/loading moment the player has, which ones
> already have a UI treatment, which don't, and what already-drawn design material exists to build from.
> **Scope: research only, no fix implemented** — this feeds a design pass, then a spec, then a build.

**Verdict:** the negotiation/decode-ceiling fix is not the bug here. The bug is that **one of four
distinct buffering moments has a UI treatment, and it isn't the one that just got a lot more common.**
The mockup (`design/ravilo/Ravilo TV.html` + `ravilo-player.js/css`) already drew and worded all four
states two phases ago (R180-era); none of that ever made it into the Compose build.

---

## 1. Why this surfaced today specifically

Before Phase 177, an over-decode-ceiling file (like Longlegs) went straight into a broken **direct
play** — fast to start, silently wrong (stutters for the file's whole runtime). After Phase 177, the
same file correctly triggers an on-the-fly **hardware transcode** (NVENC on the server) instead. That's
the right outcome, but spinning up a fresh 4K transcode and producing its first HLS segment takes real
wall-clock time — measured live at **~20 seconds** for Longlegs, vs. the near-instant start of a direct
play or an already-cached transcode segment.

So Phase 177 didn't introduce a new bug. It moved a large slice of the highest-bitrate library (exactly
the titles the owner called "the highest quality files") from a fast-but-broken path onto a
correct-but-slower one, and that slower path walks straight into a UI gap that was always there but
rarely exercised long enough to notice.

## 2. What actually happened, minute by minute (stue TV, real logcat)

| Time | Event | Source |
|---|---|---|
| 17:49:48.533 | Ravilo app launched (`MainActivity` start) | `ActivityTaskManager` |
| 17:49:48.921 | UI displayed (+381ms after launch) | `ActivityTaskManager` |
| 17:50:08.836 | MediaSession created — play pressed on Longlegs | `MediaSessionImpl` |
| 17:50:08.868 | `PlaybackState.state=2` (**PAUSED**), position=0 | `AudioMediaPlayerWrapper` |
| 17:50:08.875 | `state=6` (**BUFFERING**), position=0 | `AudioMediaPlayerWrapper` |
| 17:50:09 – 17:50:29 | **20 seconds of `state=6`, position stuck at 0.** Screen is black. Nothing else happens. | (repeated polls, no state change) |
| 17:50:29 | `state=3` (**PLAYING**), position jumps straight to 460000ms (the resume point) | `AudioMediaPlayerWrapper` |

The owner's own account matches exactly: clicked play, the on-screen media-button overlay briefly read
**"Paused"** (the raw `state=2` at session init — technically accurate, actively misleading), then black
screen, gave up after 3-7 seconds (`state=6` was still running for another 13-17s at that point), and
left. Playback succeeded on its own a few seconds after that and ran unattended for the rest of the
evening — confirmed later by polling Jellyfin's live `/Sessions` API and finding `PositionTicks`
advancing steadily in real time with `IsPaused: false`.

## 3. The four buffering moments — what exists today

Everything lives in `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
(shared by Android TV, phone, and the wasmJs web build — one Composable, three targets).

| # | Moment | Trigger | Today's treatment |
|---|---|---|---|
| A | **Session negotiating** — calling our backend / Jellyfin's `PlaybackInfo` before a stream URL exists at all | `PlayerSessionState.Loading` (`PlayerStore.kt:28`) | ✅ **Has one.** Spinner (`BufferingSpinner`) + generic `"Loading…"` text, `PlayerScreen.kt:1188-1198`. Deliberately worded neutral — "a neutral loading string regardless of cause; naming the PGS burn-in restream here would leak the delivery method" (R180-era comment, still true). |
| B | **Post-handoff, pre-first-frame** — ExoPlayer has the URL and is fetching/decoding the first segment(s) of what may be a transcode still being encoded server-side | `Player.STATE_BUFFERING` before `onRenderedFirstFrame` | ❌ **Nothing.** This is the Longlegs gap. `PlayerScreen.kt:1254-1255` is a literal stub: `// (In a real integration the engine signals buffering; we skip this for now)`. Black screen, no spinner, no text, indefinitely. |
| C | **Mid-playback stall/rebuffer** — network or server hiccup after the video was already flowing | `Player.STATE_BUFFERING` after `onRenderedFirstFrame`, not a seek | ❌ **Nothing visible**, but the signal already exists and is already wired — just not to the screen. `RaviloPlayerAndroid.kt`'s `qoeListener.onPlaybackStateChanged` (`:112-129`) tracks exactly this transition today, but only to accumulate `qoeRebufferCount`/`qoeRebufferMs` for the Phase 177 QoE report (`POST /api/tv/playback/qoe`) — it's telemetry, never rendered. |
| D | **Seek/scrub** — user drags the seek bar or presses skip | `Player.DISCONTINUITY_REASON_SEEK` | ❌ **Nothing**, and actively suppressed: `qoeSuppressNextBuffering = true` on a seek discontinuity (`RaviloPlayerAndroid.kt:110`) means seeks don't even count as QoE rebuffers, let alone show anything. |
| E | **Playback failed** (all retries exhausted) | `PlayerSessionState.Error` | ✅ **Has one.** Full error overlay with Retry/Back (`PlayerScreen.kt:1201-1252`) — this one was itself a bug fix (a prior version left the screen "stuck" on the frozen outgoing frame with no way out; see the comment at `:1202-1206`). Relevant precedent: B currently has the *same* failure mode this one was fixed for — no escape route if it hangs.

**Only A and E exist. B, C, and D are the same underlying gap** — a `Player.STATE_BUFFERING` the UI never
looks at — hitting three different trigger points.

## 4. The design work already exists, unbuilt

`design/ravilo/ravilo-player.css` (search `pl-buffer`, `:147-154`) already has a drawn buffering state
that was never wired into the real Compose player:

```css
.pl-buffer { position: absolute; inset: 0; z-index: 9; display: none; flex-direction: column;
  align-items: center; justify-content: center; gap: 26px; }
.player.buffering .pl-buffer { display: flex; }
.pl-spin { width: 92px; height: 92px; border-radius: 50%; border: 5px solid rgba(255,255,255,.16);
  border-top-color: var(--accent); animation: plspin .9s linear infinite; }
.pl-buffer .blab { font-size: 21px; font-weight: 600; color: var(--ink-soft); letter-spacing: .3px; }
.pl-buffer .blab b { color: #fff; font-weight: 600; }
```

And `design/ravilo/ravilo-player.js` (`:324-330, 338, 733`) already scripted **three different copy
variants** for three of the four moments above, on a shared mechanism:

```js
function buffer(ms, label, then) {
  root.classList.add('buffering');
  els.blab.innerHTML = label || `Loading from <b>Jellyfin</b>…`;
  ...
}
// Session start (moment A/B combined in the mockup — it doesn't distinguish them):
buffer(900, c.resumeNote ? `Resuming from <b>Jellyfin</b> · ${c.resumeNote}` : `Starting stream from <b>Jellyfin</b>…`, ...)
// Seek (moment D):
buffer(550, `Seeking…`, ...)
```

Notes on this prior art:
- It's a **static mockup** — the `ms` durations (900ms, 550ms) are fake fixed timers for the demo, not
  real signals. The real player needs to react to `Player.STATE_BUFFERING`/`STATE_READY`, not a timeout.
- It names **"Jellyfin"** in the copy. That's brand/source naming, not a codec or delivery-method
  leak — worth confirming it's still wanted (Ravilo's stated principle is Jellyfin-for-streaming-only,
  see `ravilo-off-jellyfin-data` project note), but it's a materially different category from the
  R180 ban on codec names / delivery-method cues (§5).
- It never drew moment C (mid-playback rebuffer over already-playing video) as visually distinct from
  moment A/B (cold black-screen start) — worth deciding whether it should look different (e.g. spinner
  over the frozen last frame vs. spinner over black).
- No phone or web-specific treatment exists — the CSS/JS mockup is TV-only. The real player is one
  Composable across all three targets (`PlayerScreen.kt` is `commonMain`), so whatever ships needs to
  read sensibly at TV 10-foot scale and at phone/web arm's-length.

## 5. The hard constraint: never reveal the delivery method

`specs/ravilo/requirements/phase-R180-audio-subtitle-picker-overhaul.md` (FR-RV-ASP1-2, `:55`) is
explicit: **no codec names, no delivery-method cues, no latency cue** — a viewer never learns whether
they're getting direct play, remux, or transcode, and never sees a hint that one is slower than another.
That review doc also flagged (§6, `:233-238`) that the old PGS "burn-in" overlay used to violate this
by literally reading *"Burning in subtitle… (transcoding)"* — **already fixed**: `PlayerScreen.kt:565-567`
now routes that restream through the same generic `PlayerSessionState.Loading` path as everything else,
confirmed by reading the current source (no more special-cased overlay text exists).

**This is binding on whatever the design produces here.** Longlegs is slow to start *because* it's
transcoding — but the loading screen must never say that, or hint at it, or vary its wording by cause.
One neutral treatment has to cover "instant direct play," "audio-only transcode," and "full 4K NVENC
transcode" identically. The existing `"loading"` string (`Strings.kt:242-243` en, `:487-488` da, `:732-733`
fo — "Loading...", "Indlæder...", "Ledur inn...") is the only copy that's already cleared this bar; any
new/expanded copy needs the same review.

## 6. Signals actually available to build on

**Android (`RaviloPlayerAndroid.kt`):** `exo.playbackState` / the existing `qoeListener` already
distinguishes cold buffering (`!qoeFirstFrameRendered`) from mid-playback rebuffer
(`qoeFirstFrameRendered && !qoeSuppressNextBuffering`) from a seek (`qoeSuppressNextBuffering`) — the
exact three-way split moments B/C/D need, already computed, just not exposed past the QoE counters.
`onRenderedFirstFrame` marks the B→playing transition.

**Web (`RaviloPlayerWasm.kt`):** no equivalent listener exists yet. `HTMLVideoElement` exposes `waiting`
/ `playing` / `canplay` events that map reasonably well (`waiting` ≈ `STATE_BUFFERING`, `playing` ≈
first-frame-or-resumed) but this side needs new wiring from scratch, not just exposing an existing
internal signal.

**Timing reality to design against:** ~20s cold start for a heavy on-the-fly 4K NVENC transcode (measured
live); near-instant for direct play or an already-warm transcode. Whatever ships must look reasonable
across that entire range without the wording ever hinting at which one is happening (§5) — a fixed
"Loading…" that sits on screen for 20 seconds unmoving invites the exact "is this broken?" reaction the
owner had, spinner or not.

## 7. Open questions for the design pass

1. **Debounce.** Should the buffering UI delay-appear (e.g. only render if `STATE_BUFFERING` persists
   past ~300-500ms) so a near-instant direct play never flashes a spinner it doesn't need? The session
   negotiation state (A) has no such debounce today and doesn't seem to need one in practice.
2. **Long-wait reassurance.** A silent, static spinner for up to ~20+ seconds is most of what caused
   today's report even if a spinner exists — is a static treatment enough, or does something need to
   progress/change the longer it runs (without ever naming the cause, per §5)? The mockup's copy
   ("Starting stream from Jellyfin…") is static; real transcodes can run past the mockup's 900ms
   placeholder by more than 20x.
3. **Escape hatch.** `PlayerSessionState.Error` learned this lesson already (§3, row E) — a stuck state
   with no way out reads as broken and traps the user. Should moment B/C get a Back affordance after
   some threshold, same idea?
4. **Visual distinction between cold-start (B) and mid-playback (C).** Black screen for B (nothing to
   show yet) vs. spinner over the frozen last frame for C (something *was* showing)? Or one identical
   treatment for both, for consistency?
5. **Seek/scrub (D).** Worth its own lighter/faster treatment (matching the mockup's separate 550ms
   "Seeking…" case), or fold into the same generic buffering UI as B/C?
6. **Copy: reuse "Loading…" everywhere, or the mockup's differentiated strings** ("Starting stream from
   Jellyfin…", "Resuming from Jellyfin · {note}", "Seeking…")? Differentiated copy is more informative;
   a single string is simpler to maintain and to translate (only 3 locales today: en/da/fo) and avoids
   any risk of the wording drifting into delivery-method territory over time.
7. **Phone/web layout.** The only existing visual reference (`ravilo-player.css` `.pl-buffer`/`.pl-spin`)
   is TV-scale (92px spinner, 21px label). Needs an equivalent at phone/web scale — no prior art exists
   for those two targets specifically.

## 8. Source references

- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` — `:1187-1199`
  (existing Loading overlay), `:1201-1252` (existing Error overlay, the escape-hatch precedent),
  `:1254-1255` (the stub), `:565-567` (PGS restream already routes through generic Loading), `:695-712`
  (`player.load()` / `player.play()` call site — where the B gap begins), `:714-753` (the 500ms poll
  loop `isPlaying`/`bufferedMs`/etc. already live in, where an `isBuffering` read would join).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerStore.kt` — `:26-30`
  `PlayerSessionState` (Idle/Loading/Ready/Error).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayer.kt` — the
  `expect class` player seam; `isPlaying`/`isEnded`/etc. are plain polled `val`s, the pattern an
  `isBuffering` addition would follow.
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerAndroid.kt` —
  `:94-133` the `qoeListener`, already computing the B/C/D distinction internally for QoE only.
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt` — no
  buffering signal exists yet; would need new event wiring (`waiting`/`playing` on the `<video>`).
- `design/ravilo/ravilo-player.css:147-154`, `design/ravilo/ravilo-player.js:324-330,338,733` — the
  unbuilt mockup treatment (spinner + three copy variants).
- `specs/ravilo/requirements/phase-R180-audio-subtitle-picker-overhaul.md:55,233-238` — the
  no-delivery-method-cues constraint and its one prior violation (already fixed).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/i18n/Strings.kt:242-243,487-488,732-733`
  — the existing neutral `"loading"` string in all three shipped locales.
- Live evidence: stue TV logcat (`08-28 17:49:48`–`17:50:29`), Jellyfin `/Sessions` API polling,
  `adb screencap` — session captured live during this investigation, 2026-08-28.
- Related: **Phase 177** (`phase-177-delivery-aware-playback-negotiation.md`) — the negotiation fix that
  made moment B common; **Phase 178** — unrelated (server-side I/O deferral, no client overlap);
  **R180** — the delivery-method-invisibility principle this must respect.
