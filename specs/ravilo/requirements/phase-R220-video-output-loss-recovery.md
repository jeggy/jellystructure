# Phase R220 — video output lost on return from background: detect it, recover from it, never sit black

> Reported 2026-08-31: *"Sometimes when I'm playing something and I click the home app on the tv remote
> or I shutdown(suspend/whatever-its-called) the TV and quickly turn it on again and open Ravilo again.
> Then the video continues (which is correct). But while audio works perfectly, the video is just black
> instead of continuing showing the movie."*

**Status:** Planned. Design-authored 2026-08-31 from a source read of the live tree; **not yet
dev-reviewed**, and **not yet reproduced on-device** — FR-R220-1 exists to establish which rung of the
recovery ladder is actually needed before the rest is built.

Scope: the **Android** target (`ravilo-ui/src/androidMain`, shared by `:ravilo-android` and
`:ravilo-phone`). `ravilo-web` uses a DOM `<video>` element and `ravilo-tizen` its own player; neither
has a `SurfaceView` and neither is in scope.

---

## 1. What the report tells us before reading any code

The symptom is precise and unusually informative:

- **Audio is perfect.** Not stuttering, not restarted — continuous. The `ExoPlayer` instance, its audio
  renderer, its buffer and its network source are all healthy.
- **Playback continues.** Position advances; the player is in `STATE_READY` and `isPlaying` is true.
- **Only the picture is gone.**

Audio continuing across the background/foreground round-trip **proves the activity was not recreated** —
a recreation would tear down and rebuild the `ExoPlayer` and the audio would restart, not continue. So
the same `ExoPlayer`, the same Compose composition and the **same `SurfaceView` instance** are still
alive on return. That single deduction eliminates most of the candidate explanations and points at one
place: the path between the video renderer and the display surface.

The manifest is consistent with this: `ravilo-android/src/main/AndroidManifest.xml:26` declares
`android:configChanges="keyboard|keyboardHidden|navigation|screenSize|uiMode|orientation|screenLayout|smallestScreenSize"`,
so the TV's display/HDMI re-negotiation on resume does not recreate the activity.

---

## 2. What's there now

### 2.1 The video surface is bound exactly once, and never re-bound

`PlayerVideoSurface.kt` (androidMain), the whole binding:

```kotlin
AndroidView(
    factory = { ctx ->
        val surface = SurfaceView(ctx)
        player.setVideoSurfaceView(surface)
        surface
    },
    modifier = if (dar > 0f) Modifier.aspectRatio(dar) else Modifier.fillMaxSize(),
)
```

There is **no `update` block**, no re-bind on any signal, and no key that would make the factory re-run.
`RaviloPlayerAndroid` exposes exactly one video-surface API — `fun setVideoSurfaceView(sv: SurfaceView)
{ exo.setVideoSurfaceView(sv) }` (`RaviloPlayerAndroid.kt:247`). There is **no `clearVideoSurface`, no
detach, and no re-attach.**

The app is therefore relying entirely on Media3's internal `SurfaceHolder.Callback` to notice
`surfaceDestroyed`/`surfaceCreated` and re-wire the renderer for it. That normally works. When it does
not — for whatever reason on this particular hardware, at this particular moment — **the app has no
second line of defence and no way to know.**

### 2.2 Nothing detects "playing but not rendering"

R218 added player wait states, and the moments it models are (`PlayerScreen.kt:876-883`):

```kotlin
!hasRenderedFirstFrame -> PlBufferMoment.COLD
isSeeking              -> PlBufferMoment.SEEK
isBuffering            -> PlBufferMoment.STALL
```

In this failure **all three read healthy**: the player is `STATE_READY` so `_isBuffering` is false, no
seek occurred so `_isSeeking` is false, and `_hasRenderedFirstFrame` is `true`. R218 therefore
deliberately shows nothing — correctly, by its own rules. There is no `PlBufferMoment` for "the video
pipeline has stopped producing frames", because until now nobody knew that state existed.

`_hasRenderedFirstFrame` cannot be reused as-is either: it is set in `onRenderedFirstFrame`
(`RaviloPlayerAndroid.kt:119-122`) and **reset only inside `load()`** (`:205`), i.e. once per item. Once
episode 1 has rendered a frame, the flag stays `true` for the rest of that item's playback no matter
what happens to the surface afterwards.

The result is the worst possible presentation: a **silent black screen with working audio, no overlay,
no error, no retry and no way for the viewer to tell whether the app is broken or the file is.**

### 2.3 The lifecycle handler manages the session and the MediaSession, and ignores video entirely

`PlayerLifecycleEffect.kt` (androidMain) handles four events:

| Event | Action |
|---|---|
| `ON_PAUSE` | latch `resumeOnForeground = wasPlaying()`, `player.pause()` |
| `ON_STOP` | `onBackground()` → `store.stopSession(...)`, `player.setSessionActive(false)` |
| `ON_START` | `onForeground()` → `armSession(currentItemId)`, `player.setSessionActive(true)` |
| `ON_RESUME` | `if (resumeOnForeground) player.play()` |

Every one of those concerns the **backend playback session** (R192/R180) or the **OS MediaSession**
(R192). Not one of them touches the video surface. The transition that is empirically most likely to
break video output is the only transition with no video handling in it.

### 2.4 TV standby may not produce lifecycle events at all

The report names two distinct triggers — pressing Home, and TV standby/resume — and they are not the
same case. Home reliably delivers `ON_PAUSE`/`ON_STOP`. Display standby on an Android TV may deliver
**nothing**: on some devices the app is never stopped, the display layer is torn down and rebuilt
underneath it, and the only signal available is the `SurfaceHolder` callback pair (or nothing at all, if
the `Surface` object is reused but its buffer producer was invalidated).

A fix built only on `Lifecycle.Event` therefore cannot cover the case the user described second. This is
the main reason FR-R220-2's detector is specified in terms of **observed rendering**, not in terms of
lifecycle transitions: a detector that watches the outcome works for every trigger, including ones we
have not thought of.

---

## 3. Non-goals

- **Not** changing R218's existing wait states. Moments A-D stay exactly as specified; this phase adds a
  new, disjoint condition that none of them currently covers.
- **Not** pausing on background instead of continuing. The current behaviour (pause on `ON_PAUSE`,
  resume on `ON_RESUME`) is what the user describes as correct.
- **Not** switching `SurfaceView` back to `TextureView`. R173 established that `SurfaceView` is required
  for HDR/PQ metadata to reach the compositor, verified against Jellyfin's own Android TV client. That
  decision stands.
- **Not** a general playback-error redesign. A genuine stream failure is `PlayerError`'s job; this is
  specifically the case where the stream is fine and the output path is not.

---

## 4. Requirements

**FR-R220-1 — Reproduce and identify before building the ladder.** Establish on the real device which
of the following is true, because they need different fixes and the recovery ladder in FR-R220-3 should
not ship rungs it does not need:

1. `surfaceDestroyed`/`surfaceCreated` fire and Media3 re-attaches, but the codec never produces frames
   to the new surface.
2. The callbacks fire and Media3 does not re-attach.
3. Neither callback fires — the `Surface` is silently invalidated (the display-standby case, §2.4).

The cheapest instrumentation that answers this is a temporary `SurfaceHolder.Callback` registered
alongside Media3's own, logging every `surfaceCreated`/`surfaceChanged`/`surfaceDestroyed` with
timestamps, next to the lifecycle events and the rendered-frame counter from FR-R220-2. Capture over
both triggers (Home, and TV standby/resume) and record the answer in this spec.

Per the standing testing rules this is a **Pixel 9** exercise where the symptom reproduces there, and a
TV exercise only with explicit permission; note that trigger 2 (TV standby) may be TV-only, in which
case ask before testing rather than assuming.

**FR-R220-2 — Detect "playing but not rendering".** Add a video-output health signal, evaluated on the
existing 500 ms `POLL_MS` loop in `PlayerScreen`, that is true when **all** of:

- the player reports `isPlaying`, and
- `positionMs` is advancing (so this is not a legitimate pause or an end-of-stream), and
- the player is neither buffering nor seeking (so R218's STALL/SEEK own those cases), and
- **no video frame has been rendered** for a threshold interval.

The frame signal must be a real count, not a boolean. `ExoPlayer.getVideoDecoderCounters()` exposes
`renderedOutputBufferCount`, which increments per rendered frame and can be polled — **confirm this is
available and updating on the Media3 version in use** before depending on it; if it is not,
`AnalyticsListener.onVideoFrameProcessingOffset`'s `frameCount` is the fallback, and re-arming
`_hasRenderedFirstFrame` on every surface change is the crude last resort.

Threshold: long enough that a normal resume, a track switch or a codec re-init cannot trip it — start
around 2 s of playing-with-no-frames and tune from FR-R220-1's capture. **A false positive here costs a
needless recovery on a screen that was about to be fine, so bias conservative.**

**FR-R220-3 — Recover automatically, in escalating rungs.** On detection, run an escalating ladder,
re-evaluating FR-R220-2 after each rung and stopping at the first that restores frames:

| Rung | Action | Cost if it works |
|---|---|---|
| 1 | Detach and re-attach the video surface (`clearVideoSurface()` then `setVideoSurfaceView(sv)`) | invisible |
| 2 | Force a video-renderer flush — `seekTo(currentPosition)` | a sub-second audio blip |
| 3 | Recreate the `SurfaceView` (bump a key so `AndroidView`'s factory re-runs) and re-attach | brief black |
| 4 | Re-prepare the current item at the current position | a visible reload — what the viewer does by hand today |

This requires new API on `RaviloPlayer`: at minimum a `clearVideoSurface()` and a way to re-run the
attach. Keep it a single narrow addition to the seam (`expect`/`actual`), with the web and any other
actual implementing it as a no-op, rather than leaking Android types into `commonMain`.

Rung 4 must respect R184's position rules — re-preparing must use the **live** position, never a stale
one, and must not be mistaken by the session layer for a new play (no double `startSession`, no resume
position clobbered).

**FR-R220-4 — Prevent it deterministically where we can.** Independently of the ladder, stop relying
solely on Media3's internal holder callback:

- register our own `SurfaceHolder.Callback` on the `SurfaceView` and re-attach the surface to the player
  on `surfaceCreated`, idempotently (a redundant re-attach when Media3 already handled it must be
  harmless — verify this, it is the one risk in this requirement);
- on `ON_STOP`, detach the video surface explicitly; on `ON_START`, re-attach it explicitly — the
  deterministic version of what §2.3 currently leaves to chance;
- tear the callback down with the `AndroidView` (`onRelease`) so it cannot outlive the view.

If FR-R220-1 finds case 3 (no callbacks at all), also observe `View.onWindowVisibilityChanged` as a
second trigger for the same re-attach.

**FR-R220-5 — Never sit silently black.** Recovery is invisible when it is fast and honest when it is
not, per the R218 no-flicker discipline:

- rungs that complete within R218's existing **~400 ms debounce** show nothing at all;
- past that, show R218's **STALL** presentation — the existing frozen-frame-plus-transport treatment,
  using the **already-translated** string, adding no new copy and no new visual language;
- if the ladder reaches rung 4 and still has no frames, fall through to the normal player-error path so
  the viewer gets a real message and a real action instead of a black rectangle.

**FR-R220-6 — Record which rung fired.** Every recovery reports the trigger (lifecycle vs. surface
callback vs. neither), the rung that succeeded, and the time to first frame afterwards, alongside the
existing R216 QoE counters. If the field data shows rung 4 is what always works, rungs 1-3 are dead
weight and a follow-up phase should delete them; if rung 1 always works, FR-R220-4 should have prevented
it and the prevention is not working. **The counters are how we find out which, rather than guessing
twice.**

---

## 5. Open questions for dev review

1. **Does `getVideoDecoderCounters().renderedOutputBufferCount` update reliably on the Media3 version in
   use, polled from the main thread?** `DecoderCounters` is documented as needing `ensureUpdated()`
   before reading across threads. For a 2-second heuristic an occasionally-stale read is acceptable; a
   permanently-stale one is not, and would silently make the whole detector inert.
2. **Is a redundant `setVideoSurfaceView` on an already-attached surface safe?** FR-R220-4's
   idempotence assumption rests on it. If it forces a codec re-init, rung 1 stops being free and the
   ladder's ordering should change.
3. **`android:configChanges` does not list `colorMode`.** An HDR/wide-gamut capability change on resume
   would therefore recreate the activity. The report's "audio works perfectly" says that is *not*
   happening in this case — but it is worth confirming whether it happens in some *other* TV-standby
   case, since that would be a second, different bug wearing similar clothes.
4. **Should the detector run outside the player screen?** It is written here as `PlayerScreen` poll-loop
   logic, which keeps it scoped and cheap. If Live TV's player (`LiveTvPlayerScreen`) shares the same
   surface path, it has the same defect and should share the same fix rather than get a copy.
5. **Interaction with Phase 180's session teardown.** `onBackground` → `store.stopSession(...)` now
   genuinely releases an in-flight transcode (Phase 180, FR-180-2). For a **direct play** that is a
   no-op and this phase's analysis holds unchanged. For a **transcoded** stream, the encode backing the
   still-buffered URL is released on `ON_STOP` and `onForeground`'s `armSession` re-negotiates without
   re-preparing the player. Audio continuing for minutes argues against this being the reported cause,
   but it is adjacent, new, and in this exact code path — worth confirming explicitly rather than
   assuming, and it would be a *different* bug with a different fix (re-prepare on foreground when the
   delivery was a transcode).
