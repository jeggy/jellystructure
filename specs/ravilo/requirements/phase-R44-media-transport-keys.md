# Phase R44 — Media transport keys (Play / Pause / Stop / FF / Rew / Next / Prev)

enum + `onMediaKey` handler that maps `Key.MediaPlayPause/Play/Pause/Stop/FastForward/Rewind/Next/
Previous` (+ `Spacebar`) and consumes them; PlayerScreen routes them to `togglePlay`/`skip`/
`advanceNext`/restart/exit regardless of which control is focused or whether the chrome is shown.
Android binds a Media3 `MediaSession` to the ExoPlayer (created on `load`, released on `release`) so
the OS delivers hardware transport keys and external controllers work — and because Compose consumes
the key when the player is focused, the session path doesn't double-toggle. Web registers
`navigator.mediaSession` play/pause/seek/stop handlers on the `<video>`. `:ravilo-ui`
(android + wasmJs) compiles and `:ravilo-android:assembleDebug` builds.

## Problem
Pressing the physical **Pause** / **Play (resume)** buttons (and the other media-transport keys) on
the TV remote does nothing in the Ravilo player. Only the on-screen transport works: the viewer must
D-pad to the on-screen Play/Pause button and press OK. The dedicated remote keys — which most TV
remotes and Bluetooth/Assistant controllers expose — are inert.

## Current state (as-is)
- **All key handling is explicit D-pad only.** `focus/FocusModifiers.kt` `dpadFocusable` (lines
  ~44–55) maps only `DirectionLeft/Right/Up/Down`, `Enter/NumPadEnter/DirectionCenter`,
  `Back/Escape`; every other key returns `false`. There is **no** handling of `Key.MediaPlayPause`,
  `Key.MediaPlay`, `Key.MediaPause`, `Key.MediaStop`, `Key.MediaFastForward`, `Key.MediaRewind`,
  `Key.MediaNext`, `Key.MediaPrevious`, or `Key.Spacebar`.
- **The player already has the actions.** `screens/PlayerScreen.kt` has `togglePlay()` (line ~184),
  `skip(±ms)`, `advanceNext()`, and a focused player `Box` that owns key input via `dpadFocusable`
  (`playerFR`, lines ~308–413). The transport keys just need to reach these.
- **Android: no `MediaSession`.** `seams/RaviloPlayerAndroid.kt` builds a bare `ExoPlayer`
  (lines ~24–28) with no `androidx.media3.session.MediaSession`. `MainActivity.kt` overrides no
  `dispatchKeyEvent`/`onKeyDown`; `AndroidManifest.xml` declares no media-button intent filter.
  Without a media session, the OS may route hardware transport keys to whatever app holds an active
  session (e.g. a background music app) instead of the foreground player.
- **Web: no media-session integration.** `seams/RaviloPlayerWasm.kt` wraps a `<video>` with
  `controls = false` and wires no `navigator.mediaSession` action handlers, so browser/OS media keys
  don't reach it either.

## Requirements

### A. Handle transport keys in the shared player (portable layer)
1. Route media-transport `Key.*` events to the existing player actions, regardless of which on-screen
   control is focused and **even when the chrome is hidden** (wake the chrome on a transport press):
   - `MediaPlayPause` → `togglePlay()`; `MediaPlay` → resume (play if paused); `MediaPause` → pause.
   - `MediaFastForward` → `skip(+SKIP_FWD_MS)`; `MediaRewind` → `skip(-SKIP_BACK_MS)`.
   - `MediaNext` → `advanceNext()` when a next episode exists (else no-op);
     `MediaPrevious` → restart current item (seek 0), or previous episode if cheap to support.
   - `MediaStop` → exit playback (`onBack()`), reporting final position (same as Back).
   - Optionally `Spacebar` → `togglePlay()` for keyboard/web parity.
2. Add the handling without breaking native D-pad traversal: extend `dpadFocusable` with an
   `onMediaKey`/explicit media-key branch (return `true` only for handled media keys; keep returning
   `false` for unrelated keys so focus search is unaffected), or handle them in PlayerScreen's key
   block. Media keys are global to the player surface — they must **not** depend on `focus` state.

### B. Android — attach a Media3 `MediaSession`
3. In the Android `actual` (or `:ravilo-android`), create an `androidx.media3.session.MediaSession`
   bound to the `ExoPlayer` for the lifetime of a playback, released in `release()`. This is the
   canonical Android-TV path for the OS to deliver hardware transport keys to the foreground player
   (and enables Assistant/Bluetooth/Now-Playing transport for free). ExoPlayer maps the standard
   session commands to play/pause/seek; the shared chrome stays the source of truth for position
   polling.
4. The MediaSession's metadata (title, S·E) should reflect the now-playing item so external surfaces
   read correctly; this is a nice-to-have, not a blocker for the key handling.

### C. Web — `navigator.mediaSession` action handlers
5. In the WASM `actual`, register `navigator.mediaSession.setActionHandler` for `play`, `pause`,
   `seekforward`, `seekbackward`, `nexttrack`, `previoustrack`, `stop`, mapped to the same player
   actions, so browser/OS media keys drive the `<video>`.

## Invariants
- Renders server-pushed state only; transport keys mutate **playback**, never the library
  (constitution). Progress/played still report through `/api/tv/**`.
- One shared player **chrome**; the engine + media-session wiring is the only platform-specific part
  (`actual`). Don't fork the chrome per platform.
- Keep native Compose focus traversal (R30/R42/R43) intact — media-key handling is additive and must
  not consume directional/OK/Back keys.

## Out of scope
- A full system Now-Playing/notification surface and lock-screen art beyond what the MediaSession
  gives for free.
- Long-press / repeat-rate tuning for seek keys (single-press semantics first).

## Design reference
`ravilo-ui/.../screens/PlayerScreen.kt` (`togglePlay`/`skip`/`advanceNext`, key block),
`ravilo-ui/.../focus/FocusModifiers.kt` (`dpadFocusable`),
`ravilo-ui/.../seams/RaviloPlayerAndroid.kt` + `…/RaviloPlayerWasm.kt`,
`ravilo-android/.../MainActivity.kt` + `AndroidManifest.xml`. Media3 session:
`androidx.media3.session.MediaSession`.
