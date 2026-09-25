# Phase 248 — Casting to a screen is tested in CI, without a TV

## Status

**`⏸ Paused` 2026-09-25 — owner: *"Lets stop all tizen development for a while and lets mark them as paused."*** No Tizen work (building, testing on the
emulator or a TV, or the Samsung store submission) happens until the owner resumes it. Where it stood: The CI test keeps running against its stand-in AVPlay; nothing else is being built on it.
The status below is the state it was paused in.

`✓ Built` — written 2026-09-19 with the owner (**owner: spec first, build only after an explicit
go-ahead**), go-ahead given the same day, built and verified locally against a real backend the same
day, not dev-reviewed. Builds on 247's test-stack `ravilo-screen` image. **Building this test found
three real, confirmed bugs that meant casting to a real Tizen screen has never actually worked past
pairing — see §Build notes.** All three fixed as part of this phase, with the owner's explicit
go-ahead once each was found and confirmed.

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
1. The bundle loads from `ravilo-screen`'s second, allow-listed hostname `ravilo-screen-cast` (a
   network alias on the same container 247 built — see docker-compose.test.yml; casting has nothing
   to do with 247's sideloaded-origin question, and this keeps that question's own test undisturbed).
   The test completes R269's setup screen with the `app` address (also the first CI coverage of setup
   *succeeding*, not just failing).
2. The receiver shows a pairing code in `#idle-code`; the test reads it **from the DOM**, and a fake
   phone (`/api/tv/login` + `POST /api/remote/pair`, as in `screens.spec.ts`) claims it, then confirms
   `online: true` via `/api/remote/devices` before proceeding (pairing and the events socket actually
   connecting are two independent things — see FR-248-7).
3. The phone triggers its own scan (`/api/scan?full=true` via the same admin cookie session as
   `/api/auth/login` — no browser UI needed) and looks up a real jellyfinId via `/api/tv/search`, since
   `requireVisible()` checks jellystructure's own `MediaStore`, not the mock; `POST /api/remote/play`
   names that real item.
4. Assert, via the fake's own recorded call log (not transient DOM state — see FR-248-3): `open` (URL
   contains the item id) → `prepareAsync` → `play`, then the phone's `/api/remote/devices/{id}` reports
   `now_playing.playing` truthy.
5. `pause`, `unpause`, and an **absolute** `seek` (position_ms — what the real sender actually sends,
   see FR-248-7) each reach the fake AVPlay as the right call with the right argument.
6. `stop` returns the receiver to idle (`#idle` regains `.on`) with `close` recorded on the fake.

**FR-248-7 — Three real bugs, found building this, fixed as part of it (owner go-ahead given for
each).** Recorded here because the original FR-248-2 above assumed the wrong things would need
locking in as regressions; the actual build found the wiring itself was broken:
1. **The events socket never connected.** `Screen.kt`'s `HttpClient(Js)` never called
   `install(WebSockets)`, so `TvApiClient.connectEvents()` threw immediately and `eventLoop()`'s
   `runCatching` swallowed it — completely silently, forever, on every reconnect attempt. Confirmed
   live: before the fix, pairing succeeded over plain HTTP but the device never showed `online: true`
   and the browser never opened a WebSocket at all. Fixed to match every other WS-using client in the
   codebase (`ravilo-ui`'s wasmJs/Android clients, the backend's own `Curl` client). `ravilo-cast`'s own
   `HttpClient(Js)` has the identical gap but never calls `connectEvents()`, so it's latent, not
   currently broken — left alone (no dependency on `ktor-client-websockets` exists there today; adding
   one for unexercised code is its own change, not this phase's).
2. **stop/pause/unpause silently no-op.** `RemoteRoutes.kt` sends this quartet capitalized
   (`"Pause"`/`"Unpause"`/`"Stop"`, phase 111's original wire shape) but `Screen.kt`'s
   `onPlaystateCommand` matched lowercase literals only. Every other consumer already handles this —
   `PlayerScreen.kt`'s identical `when` calls `.lowercase()` first — this file just didn't. Fixed the
   same way.
3. **Absolute seek (the real scrub bar) silently no-ops; the case that WAS handled is unreachable.**
   `ravilo-ui`'s `ScreenSender.seekTo()` (R265) sends only `remoteCommand(id, "seek", positionMs = …)`;
   `RemoteRoutes.kt` maps `"seek"` to a `player_command` event with `position_ms` in `args`. But
   `Screen.kt`'s `onPlayerCommand` had a `"seek_relative"` case (reading `delta_ms`) and no `"seek"`
   case at all — and no sender anywhere sends `"seek_relative"` (`RemoteRoutes.kt`'s own dispatcher
   doesn't recognize that command name in the first place, so it was unreachable through the real API
   on **both** ends, not just unwired on the client). Fixed by adding a `"seek"` case reading
   `position_ms`, alongside the existing (still-dead, left alone) `"seek_relative"` one.

Together these meant: a real phone paired to a real Tizen `ravilo-screen` could list it as paired, but
every play/pause/stop/seek command sent to it went nowhere at all — the visible half (pairing) worked,
the half that actually drives playback didn't. None of this was caught by `screens.spec.ts`, which
plays the screen's role by hand and so never executes any of `Screen.kt`'s own command-handling code.

**FR-248-8 — A second, independently-confirmed instance of the exact gap phase 245 predicted and
deferred.** `/api/remote/devices/{id}`'s `now_playing.playing` field, read while paused, comes back
**absent from the JSON entirely**, not `false` — the server's global `ContentNegotiation`
(`Server.kt:193`) serializes with `encodeDefaults=false`, dropping any field at its Kotlin default.
Phase 245 fixed this for the WS re-broadcast and explicitly flagged the REST path as "a bigger-radius
change... left for its own phase if it ever causes a second symptom." This is that second symptom,
confirmed live. **Not fixed here** — the fix belongs to whatever phase takes on the global
`ContentNegotiation` change phase 245 already scoped out; `ravilo-screen-cast.spec.ts` asserts on
`toBeFalsy()`, not `toBe(false)`, to test the real (if imperfect) contract rather than encode a wrong
assumption.

**FR-248-3 — Deterministic by construction.** No fixed sleeps (auto-retrying `expect` and
`waitForType`-with-`fromIndex`, 245's lesson), a hard `test.setTimeout`, no assertion on anything
rendered by a media pipeline. If it fails, something in the protocol or the receiver broke.

**FR-248-4 — The mock Jellyfin answers what a play needs, and only that.** `startPlayback`
(`PlaybackService.kt:418`) calls, in order: `getItemDetail` (`GET /Items/{id}`), `startPlaybackSession`
(`POST /Sessions/Playing`), `getPlaybackInfo` (`POST /Items/{id}/PlaybackInfo`), then
`/Sessions/Playing/Progress`/`/Stopped`. Added to `tests/mock-jellyfin/server.js`: the four routes
above, `PlaybackInfo` answering one direct-playable `MediaSource`, the other three fire-and-forget
204s — matching the file's own existing style (no per-route credential check added here; 241, still
`Planned`, is the phase that tightens the whole file at once, and this stays consistent with every
sibling route until then rather than tightening five routes in isolation). No media bytes are ever
served — nothing fetches the constructed stream URL (FR-248-1's fake never calls it).

**FR-248-5 — CORS: a second hostname on the SAME container, not a second image.** `ravilo-screen`'s
docker-compose service gets a network alias, `ravilo-screen-cast`, added to `app`'s
`CORS_ALLOWED_ORIGINS`; the bare `ravilo-screen` hostname 247's two specs use stays off it. One
container, one build, two DNS names — 247's proof that a sideloaded-widget origin is refused stays
completely untouched, and this phase doesn't need (or want) to re-litigate that question.

**FR-248-6 — Spec'd, not built: the Chromecast half.** Google's CAF runs only on a Cast device and the
sender is the Android SDK; neither exists in CI. A stubbed-`cast.framework` test of `/cast/` is possible
later and is deliberately not part of this phase — most of what it would assert is the stub.

## Non-goals

- Proving Samsung's AVPlay, HTML-over-AVPlay (R264 OQ1), remote keys on hardware, or the real widget's
  `Origin` (247 OQ1). Those stay emulator/TV checks — needed when `AvPlayBackend`/`TizenPlatform.kt`
  change or before a Seller Office upload, not on every commit.
- Real video playback in CI, or vendoring hls.js into `ravilo-screen`. Product-code changes turned out
  not to be avoidable (see FR-248-7/-8) — three real bugs blocked the test from proving anything true,
  and were fixed with the owner's go-ahead rather than worked around; the fourth (FR-248-8) was left
  alone because its fix is explicitly out of scope, per phase 245's own notes, not because it's small.
- The phone's UI (R265's sheet is Compose; the phone is played by HTTP as in `screens.spec.ts`).
- Fixing `ravilo-cast`'s identical missing-`install(WebSockets)` gap (FR-248-7 item 1) — latent, not
  currently exercised, and adding an unused dependency for it is its own change.
- Fixing the global `ContentNegotiation` `encodeDefaults` gap (FR-248-8) — phase 245 already scoped
  this out as its own, bigger-radius phase; not reopened here.

## Acceptance

1. `ravilo-screen-cast.spec.ts` passes in CI. Verified locally: 5 consecutive clean runs with no
   retry used, plus roughly a dozen earlier debugging runs across the three bug fixes above.
2. Reverting the `install(WebSockets)` fix (FR-248-7 item 1) turns the test red — tried by hand,
   confirmed: the device never reports `online: true`. The `.lowercase()` and `"seek"`-case fixes
   were confirmed positively (the test failed before each, in the exact way FR-248-7 describes, and
   passed after) rather than by reverting a second and third time.
3. 247's two specs still pass and still prove refusal — verified locally against the same real backend
   in the same session (`ravilo-screen-cors.spec.ts` fully; `ravilo-screen.spec.ts` structurally, via a
   local rig that couldn't distinguish the two hostnames — see open question 4).
4. The e2e job's wall-clock grows by well under a minute (no new image, no new build step).

## Open questions

1. ~~`requireVisible()` needs a real library item~~ — **closed, on the second try.** This was written
   as the intended design before the code existed, and the intent didn't make it into the first
   committed version of the spec file — local verification passed anyway because the local rig always
   ran a manual scan by hand first, a step that never made it into the test. A real CI run on a truly
   fresh backend caught it exactly as described here: `/api/tv/search` found nothing. Fixed: the spec
   now really does trigger `POST /api/scan` (no `full=true` — nothing here needs a forced re-pull, and
   skipping it avoids redundant TMDB work if another file already scanned) and polls `/api/scan/status`
   for up to 90s, under the same admin cookie session `POST /api/auth/login` establishes (no browser UI
   needed — Playwright's `request` context carries the cookie automatically), then looks up the real
   jellyfinId via `/api/tv/search`, independent of any other spec file's run order. Re-verified against
   a fully fresh, isolated local backend with no manual pre-scan step of any kind.
2. ~~Does a code-paired screen have a Jellyfin token?~~ — **closed, yes.**
   `ScreenPairingService.claim()` copies the claiming phone's own `jellyfinUserToken` onto the
   receiver's device row (`ScreenPairingService.kt`) — confirmed live, `startPlayback`'s
   `tvTokenForClient` call succeeds.
3. ~~Does the fake need `tizen.*` too?~~ — **closed, no.** `registerTvKeys()` wraps its calls in
   `runCatching`, so a missing `tizen.tvinputdevice` global fails silently; only `webapis.avplay`
   needed faking.
4. **Not verified end-to-end locally: that `ravilo-screen` and `ravilo-screen-cast` genuinely behave as
   two distinct origins under the real docker-compose stack.** Local verification used two literal
   ports on one machine (there was no second hostname to alias outside Docker), which cannot
   distinguish "unlisted origin" from "listed origin" the way the real `testnet` network's aliasing
   does. The compose config validates (`docker compose config`) and the mechanism (network aliases) is
   standard, but the first real CI run of `ravilo-screen-cors.spec.ts` / `ravilo-screen.spec.ts` /
   `ravilo-screen-cast.spec.ts` together is the actual proof this works as designed.
