# Phase R192 — Ravilo TV: release the OS MediaSession when the player backgrounds, not just on screen exit (bug fix, FR-RV-SESS1)

> A viewer reported that, minutes after watching on the "stue" living-room Android TV — with the TV
> powered off — their phone still showed a system media-control card ("📺 Stue TV" / "Ravilo", paused
> partway through, transport controls) in Android's Quick Settings. Investigated live via a screenshot
> the user shared: this is Android's built-in cross-device media-session mirroring (same Google
> account), not anything drawn by the Ravilo phone app or any custom Cast/`MediaRouter` code in this
> repo (there is none). Root cause is that the TV app's own `androidx.media3.session.MediaSession`
> is only released when the Compose player screen leaves composition — never when the screen merely
> backgrounds (TV sleep, remote/HDMI-CEC power-off, app switch), which is the far more common way a
> TV playback session actually ends.

**Status:** Implemented.

## Bug report
"After watching a series in Ravilo on my stue TV, I still have the media controls available in my
Android phone in the notifications bar (this is native Android, not within or by the Ravilo app).
Many minutes have passed. How come this is not removed from the phone? The TV has been shut off
even." (2026-08-10.) A follow-up screenshot showed Android's Quick Settings "media output" card,
tagged with a green "📺 Stue TV" chip, title "Ravilo" / subtitle "Stue TV", progress bar paused
partway through, and transport controls (skip/play/stop) — i.e. a **remote** session card, not a
locally-created one.

## Investigation
No Cast/`MediaRouter` integration exists anywhere in this repo (`grep -r "MediaRouter|CastPlayer|
androidx.media3.cast|com.google.android.gms.cast"` across the whole tree returns nothing), so the
phone's card is Android's own built-in cross-device media-session surfacing (Settings → Connected
devices → Cross-device services), which mirrors any `androidx.media3.session.MediaSession` that
reports itself `isActive` to a device signed into the same Google account. Ravilo doesn't opt into
this deliberately — Media3 requires a `MediaSession` bound to the player at all simply so the OS
routes hardware transport keys (remote Play/Pause/FF/Rew) to it (R44) — cross-device mirroring is a
side effect the app never intended to leave open.

The chain (`ravilo-ui` module):
1. `seams/RaviloPlayerAndroid.kt:58-61` builds a `MediaSession` bound to the live `ExoPlayer`,
   `.setId("ravilo-player")`, lazily on first `load()` (line 99). It is only ever torn down in
   `release()` (line 178-181) via `mediaSession.release()`.
2. `release()` is called from exactly one place: `PlayerScreen.kt:869-874`, inside
   `DisposableEffect(Unit) { onDispose { player.release() } }` — fires only when the Compose player
   screen leaves composition (the viewer navigates to a different in-app screen).
3. `seams/PlayerLifecycleEffect.kt` (androidMain actual) handles the Android `Lifecycle` separately.
   `ON_PAUSE` → `player.pause()` (line 33-36, ExoPlayer only, no session interaction). `ON_STOP` →
   `currentOnBackground()` (line 40-43) — whose own comment (`PlayerScreen.kt:831-834`) explicitly
   says the intent is to "END the playback session while we are away," but the actual call
   (`PlayerScreen.kt:843-846`) only reaches `store.stopSession(...)`, a backend/Jellyfin playstate API
   call (`PlayerStore.kt:130-145`) — it never touches `player` or `mediaSession` at all.

So when the TV sleeps, is turned off via remote/HDMI-CEC, or the app is simply backgrounded: the
Activity gets `ON_STOP` (often not `ON_DESTROY`, since Android TV keeps the process alive) → the
player screen **stays in composition** → `onDispose`/`player.release()` never runs. `exo.pause()`
updates the session's reported `PlaybackState` to paused at the last position (matching the
screenshot's paused progress bar) — but the `MediaSession` object itself is never deactivated, so
Android's cross-device layer keeps advertising it to other signed-in devices indefinitely.

## Requirements

### FR-RV-SESS1-1 — A platform seam to toggle MediaSession activity independent of release
`RaviloPlayer` (`seams/RaviloPlayer.kt`, `expect class`) gains `fun setSessionActive(active: Boolean)`.
Android actual (`RaviloPlayerAndroid.kt`): Media3's `androidx.media3.session.MediaSession` has **no
public `isActive` setter** (confirmed against its sources — only `release()`; the underlying legacy
compat session's `setActive` is internal to Media3, not exposed) — so "deactivate without a full
teardown" is implemented as **release-and-recreate-on-demand** instead of a flag toggle.
`mediaSessionLazy: Lazy<MediaSession>` becomes a plain nullable `mediaSessionRef: MediaSession?` (a
Kotlin `lazy` delegate can't be reset once initialized) with a private `ensureMediaSession()` helper.
`setSessionActive(false)` releases the session and clears the ref (a no-op if already null/never
created); `setSessionActive(true)` recreates it via `ensureMediaSession()`, bound to the **same**
still-alive `exo` instance — so this never rebuilds the player itself, only the lightweight session
wrapper around it. Wasm actual (`RaviloPlayerWasm.kt`): no-op — there is no OS-level
MediaSession/cross-device surfacing on web to manage.

### FR-RV-SESS1-2 — Deactivate on `ON_STOP`, reactivate on `ON_START`
`PlayerLifecycleEffect`'s Android actual (`seams/PlayerLifecycleEffect.kt`) calls
`player.setSessionActive(false)` in the `ON_STOP` branch (alongside the existing
`currentOnBackground()` call) and `player.setSessionActive(true)` in the `ON_START` branch, but only
when `backgrounded` was true (alongside the existing `currentOnForeground()` call) — i.e. exactly the
same two sites that already exist for the backend session start/stop, so a genuine background→
foreground round-trip (the viewer briefly glances at another app and comes straight back, without the
Compose screen ever leaving composition) still resumes normally with the session simply re-activated,
no player rebuild needed.

## Invariants
- **A backgrounded/off-screen TV never keeps advertising an active media session to other devices.**
  `ON_STOP` is the one lifecycle callback Android reliably fires before the screen goes idle/off, so
  it is the single point of truth for "playback here is no longer something another device should be
  offered control of."
- **In-app resume (returning to the same episode without leaving the player screen) is unaffected.**
  Only the lightweight `MediaSession` wrapper is torn down and recreated; the underlying `ExoPlayer`
  instance (and its position/buffer/track state) is never touched by this phase, so a background/
  foreground round-trip resumes exactly as before.
- **This phase does not change what gets sent to the backend.** `store.stopSession`/`armSession` and
  their resume-position semantics (R184) are untouched — this is purely an OS-session-visibility fix,
  layered alongside the existing backend calls at the same two call sites.

## Out of scope
- A truly abrupt power cut (unplugging the TV, not via remote/HDMI-CEC standby) with no software
  lifecycle callback firing at all cannot be handled at the app layer — no fix here can guarantee
  instant cleanup for that case; Android's own system will eventually reap a genuinely dead session
  independently. The reported case (normal TV-off/idle after watching) goes through `ON_STOP`
  normally and is what this phase covers.
- No Cast/`MediaRouter` integration is being added or removed — this phase only controls the
  `isActive` flag on the `MediaSession` Ravilo already creates for hardware-transport-key support
  (R44); it does not opt into or out of any Cast feature.
- `ravilo-ui` has no test source set for `androidMain` (`ravilo-ui/build.gradle.kts` declares no
  `androidUnitTest`/instrumented test dependencies) — this phase adds no automated regression test;
  manual on-device verification (background the TV app or power it off via remote, then check whether
  the phone's cross-device media card disappears) is left to the user, per project convention for
  on-device testing.

## Dev-review addendum (2026-08-10 — implementation notes)
1. Chose to wire `setSessionActive` directly into `PlayerLifecycleEffect` (androidMain) rather than
   through `PlayerScreen`'s `onBackground`/`onForeground` closures — it's a pure Android-platform
   `MediaSession` concern, and `PlayerLifecycleEffect` already calls `player.pause()`/`player.play()`
   directly at the same two sites for the identical reason. Keeps `PlayerScreen.kt`/`PlayerStore.kt`
   (common code, also used by web) completely untouched.
2. Verified via `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
   `:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`,
   `:ravilo-phone:compileDebugKotlin` — all pass. Not on-device verified this session (deploy is
   user-initiated, per standing preference).

## Source references
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayer.kt` — `expect class
  RaviloPlayer`, new `setSessionActive`.
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerAndroid.kt` —
  `mediaSession`/`mediaSessionLazy`, `release()`, new `setSessionActive` actual.
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt` — no-op
  `setSessionActive` actual.
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/PlayerLifecycleEffect.kt` —
  `ON_STOP`/`ON_START` branches, new `setSessionActive` calls.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt:830-874` —
  the existing `PlayerLifecycleEffect` wiring and player-release `DisposableEffect`, unchanged by
  this phase but where the underlying comments describe the original ("end the session") intent.
- Related: **phase-R184-autoplay-next-stale-position.md** (the same background/foreground session
  lifecycle, different bug — stale resume position, not OS session visibility).
