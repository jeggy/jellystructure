# Phase R220 — video output lost on return from background: detect it, recover from it, never sit black

> Reported 2026-08-31: *"Sometimes when I'm playing something and I click the home app on the tv remote
> or I shutdown(suspend/whatever-its-called) the TV and quickly turn it on again and open Ravilo again.
> Then the video continues (which is correct). But while audio works perfectly, the video is just black
> instead of continuing showing the movie."*

**Status:** ✓ Built 2026-09-02 (FR-R220-4/5 closed; FR-R220-1/2/3/6 were already built 2026-08-31, same
session as the spec) — built ahead of FR-R220-1's on-device confirmation rather than gated behind it,
since neither session had device access to reproduce with; the full ladder (rungs 1-4) shipped as
designed rather than narrowed to a confirmed subset. Compiles clean across every affected target
(`:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
`:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`,
`:ravilo-phone:compileDebugKotlin`) and `:ravilo-ui:testDebugUnitTest` passes. **Not dev-reviewed, not
reproduced on-device, and not deployed anywhere** — no device access, and live TVs are now off-limits
entirely per standing instruction. FR-R220-1's own on-device capture (which of the three surface-callback
cases actually applies) is still the right next step; the shipped ladder — and now the shipped prevention
— is a reasoned bet across all three cases, not a confirmed-necessary one. See the build note after §4.

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

**Built as a session-total count, not the fuller per-recovery breakdown** — see the build note.

---

## Build note (2026-08-31)

Implemented in one pass, same session as the spec, with **no on-device reproduction or verification**
(no device access this session; deploy was explicitly out of scope regardless). Treat this as a
reasoned, compiling implementation of the spec's design — not a confirmed fix for the reported bug.

- **FR-R220-2 (detection)**: `RaviloPlayerAndroid.renderedVideoFrameCount()` (new) reads
  `exo.videoDecoderCounters.renderedOutputBufferCount` (calling `DecoderCounters.ensureUpdated()` first,
  per its own cross-thread-read contract), returning 0 rather than throwing if counters aren't available
  yet. `PlayerVideoSurface`'s Android actual runs a 500ms poll loop (`LaunchedEffect`) declaring a stall
  after 4 consecutive ticks (~2s) where the player is playing, not buffering, not seeking, and the frame
  count hasn't moved — the conservative threshold the spec asked for. **Kept entirely within the Android
  actual** (not threaded through the common `RaviloPlayer` expect/`PlayerScreen`'s own poll loop) —
  simpler, matches "scope is Android only," and avoids adding cross-platform API surface for a detector
  every other platform would just no-op.
- **FR-R220-3 (ladder)**: rungs 1-3 also live entirely in `PlayerVideoSurface`'s Android actual. Rung 1:
  `RaviloPlayerAndroid.clearVideoSurfaceView()`(new)+`setVideoSurfaceView()` on the same captured
  `SurfaceView` instance. Rung 2: `player.seekTo(player.positionMs)` (a no-op-position seek, using the
  already-common API). Rung 3: bumps a `key(surfaceGeneration)` wrapper around the `AndroidView`,
  forcing Compose to tear down and recreate a genuinely fresh `SurfaceView`. Each rung gets 1.5s to show
  a new frame count before escalating. Rung 4 required one small **common-code** change: the
  `PlayerVideoSurface` expect/actual signature gained an `onVideoOutputStuck: () -> Unit = {}` callback
  (default no-op, so `wasmJs`/other call sites are unaffected); `PlayerScreen` wires it to
  `armSession(currentItemId)` — the exact same re-arm `PlayerLifecycleEffect`'s own `onForeground` already
  uses, not a new mechanism.
- **FR-R220-4 (prevention) — closed 2026-09-02.** `AndroidView`'s `onRelease` callback still nulls the
  captured surface reference (`onRelease` now also calls the new `RaviloPlayer.forgetVideoSurfaceView`,
  below, so the Android actual's own tracked reference can't go stale either). Added: (1) a
  `SurfaceHolder.Callback` registered on the `SurfaceView` right where it's created, alongside Media3's
  own — `surfaceCreated` re-calls `setVideoSurfaceView` on the same instance, a redundant call the FR
  itself calls harmless (open question 2, still not on-device-verified — see below); (2) the deterministic
  `ON_STOP`/`ON_START` detach/attach pair, wired into `PlayerLifecycleEffect`'s androidMain actual as
  `player.detachVideoSurfaceForBackground()` / `player.reattachVideoSurfaceForForeground()` — two new
  `RaviloPlayer` methods that read a `currentSurfaceView` ref the actual now maintains itself (set inside
  `setVideoSurfaceView`, cleared by `forgetVideoSurfaceView`), so `PlayerLifecycleEffect` never needs to
  reach into `PlayerVideoSurface`'s own Compose state to do this; (3) belt-and-suspenders for the case
  FR-R220-1 was meant to confirm before deciding whether it's needed (§2.4, neither callback firing at
  all): the `SurfaceView` is now an anonymous subclass overriding `onWindowVisibilityChanged`, re-attaching
  on `VISIBLE` the same idempotent way. All three are harmless-if-redundant by design, matching how the
  rest of this phase already shipped ahead of FR-R220-1's confirmation.
- **FR-R220-5 (never sit silently black) — closed 2026-09-02.** `PlayerVideoSurface`'s `expect`/`actual`
  gained a fourth parameter, `onVideoOutputRecovering: (Boolean) -> Unit = {}` (no-op on wasmJs, same
  pattern as `onVideoOutputStuck`). The Android actual's ladder calls it `true` the instant the stall
  threshold is crossed (before rung 1) and `false` once a rung recovers or right before the rung-4
  hand-off. `PlayerScreen` threads it into a new `videoOutputRecovering` state var and folds it into
  `rawBufferMoment`'s `when` — ahead of `isSeeking`/`isBuffering`, so a rung 2 no-op seek flipping
  `isSeeking` mid-ladder can't downgrade the presentation to SEEK — meaning any rung still running past
  R218's own ~400ms debounce shows the existing STALL treatment with no new copy or visual language, per
  the FR's own wording. `LiveTvPlayerScreen`'s separate `PlayerVideoSurface` call site does not pass this
  callback (Live TV has no R218 buffer-moment machinery — see open question 4's answer below), so it still
  inherits FR-R220-2/3/4 for free but not this one; building STALL presentation for Live TV from scratch
  is out of this phase's scope (R218 never covered Live TV either).
- **FR-R220-6 (telemetry)**: `PlayerQoeSnapshot` gained `videoOutputRecoveries: Int = 0` (additive,
  every other platform stays at the default 0). `RaviloPlayerAndroid.recordVideoOutputRecovery()`
  increments a session-total counter, called once per full ladder run regardless of which rung actually
  worked. **This is the session-total count, not the fuller per-recovery breakdown** (trigger/rung/
  time-to-recover) the FR describes — a lighter, still-useful subset given the no-device-testing
  constraint; a fuller breakdown is a reasonable follow-up once real field data says the mechanism is
  worth investing further in.
- **FR-R220-1**: still not run (no device access either session, and TVs are now off-limits entirely per
  standing instruction — trigger 2, TV standby, may be TV-only per the FR's own note, so this may stay
  unconfirmed indefinitely absent a policy change). **FR-R220-5 was closed 2026-09-02** — see the build
  note above; this bullet is kept for the historical record of the 2026-08-31 gap.

Verification: `compileKotlinLinuxX64`-equivalent for Ravilo —
`:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-web:compileKotlinWasmJs`,
`:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin` all clean;
`:ravilo-ui:testDebugUnitTest` passes (pre-existing tests, none written for this phase — see the open
question this raises below). No device, no deploy.

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
4. **Should the detector run outside the player screen?** As built, it lives inside
   `PlayerVideoSurface`'s Android actual (not `PlayerScreen`'s common poll loop — a deviation from this
   spec's original text, made because the surface-level rungs need Android-only types anyway; see the
   build note). **Checked 2026-09-02: `LiveTvPlayerScreen.kt:179` calls the same shared
   `PlayerVideoSurface` composable** (`PlayerVideoSurface(player, Modifier.fillMaxSize())`, default
   params), so it already inherits the detector, the rung 1-3 ladder, and FR-R220-4's prevention
   (SurfaceHolder.Callback + onWindowVisibilityChanged) for free, with zero Live TV-specific code. It does
   **not** get rung 4 (no `onVideoOutputStuck` wired — there is no Live TV equivalent of `armSession` to
   call) or FR-R220-5's forced STALL (no `onVideoOutputRecovering` wired, and Live TV has no R218
   buffer-moment machinery at all to force into a state) or FR-R220-4's `ON_STOP`/`ON_START` detach/attach
   (`LiveTvPlayerScreen` has no `PlayerLifecycleEffect` call of its own). Extending any of those three to
   Live TV is new scope this phase doesn't ask for — R218 itself never covered Live TV either.
5. **Interaction with Phase 180's session teardown.** `onBackground` → `store.stopSession(...)` now
   genuinely releases an in-flight transcode (Phase 180, FR-180-2). For a **direct play** that is a
   no-op and this phase's analysis holds unchanged. For a **transcoded** stream, the encode backing the
   still-buffered URL is released on `ON_STOP` and `onForeground`'s `armSession` re-negotiates without
   re-preparing the player. Audio continuing for minutes argues against this being the reported cause,
   but it is adjacent, new, and in this exact code path — worth confirming explicitly rather than
   assuming, and it would be a *different* bug with a different fix (re-prepare on foreground when the
   delivery was a transcode).
6. **No test coverage was written for the ladder or the detector.** The existing
   `ravilo-ui` commonTest suite has no fixture for exercising an Android `actual` composable's internal
   `LaunchedEffect` logic, and building one (a fake `RaviloPlayer`/`SurfaceView` harness) felt like
   scope creep on top of an already-unverified implementation — better to get real on-device signal
   first (FR-R220-1) and write tests against what's actually confirmed to matter.
7. **Resolved 2026-09-02.** FR-R220-5's "show STALL past the debounce" is now wired: `PlayerVideoSurface`
   gained `onVideoOutputRecovering: (Boolean) -> Unit`, true for the ladder's duration, threaded into
   `PlayerScreen`'s `rawBufferMoment` ahead of `isSeeking`/`isBuffering` so it can't be pre-empted by a
   rung's own side effects (rung 2's no-op seek). Not on-device-verified — the same standing caveat as the
   rest of this phase.
