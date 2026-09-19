# Phase 248 — Casting to a screen is tested in CI, without a TV

## Status

`Planned` — written 2026-09-19 with the owner, not built (**owner: spec first, build only after an
explicit go-ahead**), not dev-reviewed. Builds on 247's test-stack `ravilo-screen` image.

## What is wrong

The owner's most important use case — *a phone plays something on the Samsung TV* (236 / R264 / R265) —
is guarded by exactly one test, and that test runs none of the receiver's code. `screens.spec.ts` plays
**both** roles by hand with `fetch()` and raw WebSockets: it proves the backend's routes, and it caught a
real wire bug (245), but `ravilo-screen.js` — pairing loop, events socket, `onPlayItem` → negotiate →
open → status, the command handlers, `AvPlayBackend` — has never executed in CI. 247 loads the bundle, but
only as far as the setup screen failing.

The owner will not have a Samsung TV available most of the time. Nearly everything this path depends on
is shared code that changes for unrelated reasons (`TvApiClient`, the `shared` DTOs, `/api/remote/**`,
the events socket, `ravilo-receiver-core`, which Chromecast also uses). Today a regression there is
found the next time someone happens to point a phone at the TV.

Two facts from reading the code that shape the design:

- **Outside a TV the receiver cannot play anything at all.** `detectMediaBackend()`
  (`MediaBackend.kt:170`) falls back to `HtmlVideoBackend`, which wants native HLS or `window.Hls` —
  and `ravilo-screen/wgt/index.html` loads no hls.js. In Chromium that path ends in
  `"no HLS support"` → `failLoad()`. (Harmless on Tizen, which takes `AvPlayBackend`; a real gap for the
  webOS follow-on the module's build comment promises. Not this phase's to fix — recorded.)
- **Real video decode in CI is the wrong thing to chase.** Playwright's Chromium has no H.264/AAC, and
  headless media has already hung this suite once (`ravilo-web.spec.ts`'s header). It would also test
  `HtmlVideoBackend`, the path no shipping device uses.

So the test fakes the one thing CI cannot have — Samsung's player — and runs everything else for real.

## Requirements

**FR-248-1 — A fake `webapis.avplay`, injected before the bundle loads.** A Playwright
`page.addInitScript` defines `window.webapis.avplay` with exactly the surface `AvPlayBackend` calls
(`open`, `setDisplayRect`, `setDisplayMethod`, `setListener`, `prepareAsync`, `play`, `pause`, `seekTo`,
`close`, `getState`, `getCurrentTime`, `getDuration`, `setSelectTrack`). It records every call in order,
answers `prepareAsync` with its success callback, advances `getCurrentTime()` on a clock while "playing",
and honours `seekTo`. `detectMediaBackend()` therefore selects **`AvPlayBackend` — the real Tizen code
path** — with no product change and no test-only branch in the bundle. `tizen.tvinputdevice` gets the
same treatment only if `registerTvKeys()` needs it to not throw.

**FR-248-2 — One spec drives the whole cast, through the real bundle.**
`tests/e2e/ravilo-screen-cast.spec.ts`, against the existing stack:
1. The bundle loads from the `ravilo-screen` origin; the test completes R269's setup screen with the
   `app` address (this also makes it the first CI coverage of setup *succeeding*).
2. The receiver shows a pairing code in `#idle-code`; the test reads it **from the DOM**, and a fake
   phone (`/api/tv/login` + `POST /api/remote/pair`, as in `screens.spec.ts`) claims it.
3. The phone lists `/api/remote/devices` and finds the screen; `POST /api/remote/play` names a real,
   scanned fixture item.
4. Assert, in order: `#loading` shows the title → the receiver's own `POST /api/tv/playback/start`
   returns 200 → the fake AVPlay recorded `open(<the ticket's URL>)` then `prepareAsync` then `play` →
   the phone's subscribed socket receives `device_status` with `loaded`, `playing` and the item id.
5. `pause`, `seek` (absolute) and `seek_relative` from the phone each reach the fake AVPlay as the right
   call with the right argument, and each produces a status push reflecting it (position after seek).
6. `stop` returns the receiver to idle with a fresh-or-same pairing code visible, `close` recorded, and
   a not-loaded status pushed.

**FR-248-3 — Deterministic by construction.** No fixed sleeps (auto-retrying `expect` and
`waitForType`-with-`fromIndex`, 245's lesson), a hard `test.setTimeout`, no assertion on anything
rendered by a media pipeline. If it fails, something in the protocol or the receiver broke.

**FR-248-4 — The mock Jellyfin answers what a play needs, and only that.** `startPlayback`
(`PlaybackService.kt:418`) calls, in order: `getItemDetail`, `startPlaybackSession`, `getPlaybackInfo`,
then progress/stop reports. Add the minimum routes for a fixture item with one media source that
direct-plays. Per 241's rule, each new route checks the credential rather than accepting anything; no
media bytes are served (nothing fetches the URL — FR-248-1).

**FR-248-5 — CORS for this spec is the ordinary allow-list.** The `ravilo-screen` origin is added to
`app`'s `CORS_ALLOWED_ORIGINS` so the cast can run. 247's two specs must keep proving refusal, so they
move to origins that stay unlisted (247's header spec already uses `null`/`evil.example.com`; its
browser spec needs a second unlisted origin or an equivalent — decide at build). This phase says
nothing about 247's open question: an allow-listed `http://` origin is not a sideloaded widget's.

**FR-248-6 — Spec'd, not built: the Chromecast half.** Google's CAF runs only on a Cast device and the
sender is the Android SDK; neither exists in CI. A stubbed-`cast.framework` test of `/cast/` is possible
later and is deliberately not part of this phase — most of what it would assert is the stub.

## Non-goals

- Proving Samsung's AVPlay, HTML-over-AVPlay (R264 OQ1), remote keys on hardware, or the real widget's
  `Origin` (247 OQ1). Those stay emulator/TV checks — needed when `AvPlayBackend`/`TizenPlatform.kt`
  change or before a Seller Office upload, not on every commit.
- Real video playback in CI, vendoring hls.js into `ravilo-screen`, or any change to product code. If
  the build finds it needs one, that is a finding to bring back, not to slip in.
- The phone's UI (R265's sheet is Compose; the phone is played by HTTP as in `screens.spec.ts`).

## Acceptance

1. `ravilo-screen-cast.spec.ts` passes in CI and 20 consecutive local runs pass with no retry used.
2. Each of these, tried by hand, turns it red: renaming a `PlayItemEnvelope`/`ScreenStatus` wire field on
   one side only; breaking `onPlayerCommand`'s `seek_relative`; removing `sendStatus()` from `onPlaying`;
   reverting 245's `encodeDefaults` fix.
3. 247's specs still pass and still prove refusal.
4. The e2e job's wall-clock grows by well under a minute (no new image).

## Open questions

1. **`requireVisible()` needs a real library item.** `screens.spec.ts` gets away with
   `e2e-fake-item-1` because `/api/remote/play` doesn't validate; the receiver's own
   `playback/start` does. The spec must not depend on `scan-fixture.spec.ts` having run first
   (alphabetical order is not a contract) — trigger and await its own scan, or share a setup step.
2. **Does a code-paired screen have a Jellyfin token?** `startPlayback` calls
   `tvTokenForClient(device)` and throws re-auth if null. A device paired by `/api/remote/pair` never
   typed a password — confirm what 236 stores for it, and that it works against the mock.
3. **Does the fake need `tizen.*` too**, and does `registerTvKeys()` fail soft without it? Read
   `TizenPlatform.kt` at build.
4. Which exact Jellyfin routes `getItemDetail`/`startPlaybackSession`/`getPlaybackInfo` hit on 12.x —
   take them from `JellyfinClient.kt`, not from memory.
