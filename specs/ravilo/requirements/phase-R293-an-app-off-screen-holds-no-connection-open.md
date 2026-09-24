# Phase R293 — An app that is off screen holds no connection open, and the fix reaches the TV that needs it

## Status

`Planned` — written 2026-09-24 from a server-side investigation. **Dev-reviewed 2026-09-24 against
`main` `9d2636bb`** (see §Dev review at the bottom: every client claim holds; the lifecycle hook is the
Activity's, not a new `ProcessLifecycleOwner` dependency; the catch-up needs `onOpen` to stop emitting an
unconditional refresh; FR-R293-4 has a second socket to cover; FR-R293-7 blinds one server-side lookup
unless 256 reads the header too; acceptance 1 is reachable because `app_version` refreshes on every
request, not only at login). Not built. Spec first. Pairs with **phase 256** (the backend half: close
reasons, flap detection, bridge debounce).
Complements **R292**, which applies the same rule to the player.

> Owner, 2026-09-24: *"That household is getting the app from the Google Play store. So we need to fix
> it and provide a fix ourselves so it's all handled properly."*

## What happens

aleks's TV (`BRAVIA 4K GB ATV3`, device `0c7d468e…`, **Ravilo 1.35 from Google Play**, in another
household, reaching the server over the internet) opens its live-events WebSocket (`/api/tv/events`,
R33) and loses it **about once a minute, for as long as the app process lives**, while nobody is
watching.

Measured from jellystructure's own log (container up since 2026-09-22 16:34) and Jellyfin's activity
log, 2026-09-24:

- **1,326 connect/disconnect cycles** in 29 hours. Connection lifetime: **median 68 s**, 1,290 of
  1,326 between 55 and 115 s, peaks at **63 s** (233) and **74 s** (177), a second group at 97–104 s.
  The client reconnects **0–2 s** after each disconnect (its 1 s backoff).
- **Only this device does it.** Every other Ravilo device in the log (three of ours on the LAN, two
  remote phones including aleks's own) shows a handful of cycles with long lifetimes.
- **It starts when watching stops.** On 2026-09-22 the connection was steady for the whole 1 h 37 min
  of viewing (13:53–15:30); the flapping began the moment the last film ended (15:30) and ran for
  **31 hours** at 35–43 reconnects an hour, until the process went away (2026-09-23 21:50). Same shape
  after viewing on 2026-09-06 and 2026-09-13 (≈20/h for two hours, then silence).
- **The connections end cleanly.** Our server logged only 61 `Ping timeout` drops in total (all devices)
  against those 1,326 disconnects: the far end closes each socket, it is not our server giving up on
  a dead peer (the server pings every 30 s with a 15 s timeout, which would drop a dead peer at ≈45 s).

Why this one TV and not ours is not known. Our BRAVIAs run Android 12 (API 31) and, as observed today,
suspend a backgrounded Ravilo rather than letting it keep reconnecting; aleks's TV evidently keeps the
process running, and something on that TV or its network ends each socket about a minute in. **The
client records nothing about why a socket ended** (`runCatching` in `RaviloApp.kt`, no log, no
telemetry), so the trigger cannot be recovered after the fact (FR-R293-6 fixes that for next time).
The fix below does not depend on knowing it.

### What each flap costs

- **A Home rebuild for nobody.** Each reconnect runs `onOpen = { …; liveConfig.emit(0L) }`, which makes
  `refreshConfig()` re-pull `/api/tv/config` **and** makes `HomeScreen` (which collects the same flow)
  silently re-pull `/api/tv/home`: ~40 Home feed builds an hour, 31 hours running, for a TV in
  standby.
- **A Jellyfin session each time.** Each connect opens a Phase 110 session bridge (`postCapabilities`),
  each disconnect ends it: **2,285 `SessionStarted`/`SessionEnded` rows** in Jellyfin's activity log from
  this TV alone. Across all devices, session rows are 43,244 of the log's 70,853 (61%).
- **A wrong answer to "is that TV on?"** R265/R270's *All your TVs* shows this TV online and offline
  every minute.
- **Background traffic.** Also running the whole time: the R141 config poll (every 15 s) and, when Home
  places it, the Live TV *On now* poll.
- **A credential in proxy logs.** The Android client passes the device token as `?token=` on the events
  URL. The reverse proxy logs the full URL on any error, and did so for this device's token. The
  server already accepts `Authorization: Bearer` on this route; only browsers need the query
  parameter.

### Why the fix has not reached it

aleks's phone and TV both still run **1.35**. 1.35 (2026-09-21 13:41) was the last release shipped to
Play's **internal** track; from 1.36 (the same day, 17:24) releases go to the **closed testing** track
(R215). meidam's phone picked up 1.36; aleks's devices did not. **aleks is evidently an internal-track
tester only.** Separately, **v1.37's Play upload failed** (*"Play automatic protection requires minSdk ≥
24; bundle has 21"*, addressed by R289), and CI is currently blocked by GitHub billing. **No build
reaches this TV until all three are resolved** (FR-R293-8).

## Requirements

### The rule

- **FR-R293-1 — The events socket lives only while the app is on screen.** It is opened on the app's
  `ON_START` and closed with a normal close (`1000`, reason `"background"`) on `ON_STOP` **or** screen-off
  (`ACTION_SCREEN_OFF`: a TV entering standby may send no `ON_STOP`, R292 FR-R292-8). Its reconnect loop
  is cancelled with it and **does not run while the app is off screen**: no reconnects, no backoff
  timer, nothing. Tie it to the process/activity lifecycle (`ProcessLifecycleOwner` or the activity's
  lifecycle), not to composition, which stays alive in the background.
- **FR-R293-2 — So do the polls.** The R141 config poll and Home's Live TV *On now* poll stop on
  `ON_STOP`/screen-off and restart on `ON_START`. A backgrounded Ravilo makes **no** network requests
  of its own (R292 already stops the player's).
- **FR-R293-3 — Coming back catches up once.** On `ON_START` the socket reconnects immediately, and the
  first open does **one** catch-up: a config-rev check, and a silent Home refresh only if the rev moved
  or the app was away longer than the push history can cover (a server-side playstate/home change may
  have been missed while disconnected). Not a full Home rebuild on every open.

### In the foreground

- **FR-R293-4 — A socket that keeps dying backs off.** Today any socket held ≥ 2 s counts as healthy
  and resets the backoff to 1 s, so a socket that dies every minute is reopened every minute forever.
  A socket is *healthy* only after it has been open **5 minutes**; one that ends sooner doubles the
  backoff (1 s → 2 → … → **60 s** cap, ±20% jitter), reset only by a healthy one. A quick reconnect in
  the foreground (≤ 30 s gap) does the config-rev check only, never a Home rebuild (FR-R293-3's rule).
- **FR-R293-5 — Commands never act invisibly.** `play_item`, `navigate`, `playstate_command` and
  `player_command` received while the app is not on screen (the gap between `ON_STOP` and the socket's
  close, or a platform that delivers late) are **dropped and logged**, never applied. A backgrounded
  app cannot bring its activity to the front on Android 10+ anyway; applying a `play_item` would start
  a player nobody sees (and, before R292, one that plays audio behind the launcher).

### Next time, we know why

- **FR-R293-6 — Every socket end is recorded, and told to the server.** The client records, for each
  events socket: how long it was open; how it ended (close code + reason from the server, or the
  exception class + message, or *closed by us*: background/screen-off/sign-out); the app's lifecycle
  state and `PowerManager.isInteractive` at that moment; the active network's type and whether it was
  validated. The last 10 are kept in memory and sent as one compact header on the next connect
  (`X-Ravilo-Events-Prev: <open-s>;<how>;<lifecycle>;<interactive>;<net>`, bounded, no URLs, no
  tokens), which phase 256 logs with the device id. The next device that flaps tells us why in the
  server log, without anyone touching it.

### Hygiene found on the way

- **FR-R293-7 — The token goes in a header on Android.** The Android client authenticates the events
  WebSocket with `Authorization: Bearer <device token>`; the `?token=` form remains only for the
  browser client, which cannot set a handshake header. No token in any URL a proxy logs, from any
  Android build.

### Getting it to the TV

- **FR-R293-8 — The fix is delivered, not just built.** This phase is not `✓ Built` until a build
  containing it is **on Google Play on a track aleks's account receives**. Concretely:
  1. R289 (minSdk 24) is built so Play accepts the upload (v1.37's failed on it);
  2. CI runs again (the billing block lifted), so a GitHub Release uploads to Play;
  3. aleks's Google account is added to the **closed testing** track's testers (Play Console →
     *Testing → Closed testing → Testers*), or the release workflow also publishes to the internal
     track while any household is still only there. Which one is the owner's call (open question 1);
  4. the Play-side state is confirmed: the track carries the new version code.

  Every other household on the internal track only is in the same position; the tester list is
  checked for all of them, not just aleks.

## Non-goals

- A background service, or keeping any connection alive off screen. Remote control of a TV whose app
  is not on screen is not something an Android app can do honestly (it cannot bring itself to the
  front), and the server now reports such a TV as offline, which is true.
- Diagnosing the exact trigger on aleks's TV by hand. FR-R293-6 makes the next occurrence self-reporting;
  the fix does not depend on the answer.
- `ravilo-web` (a browser tab's lifecycle is its own; only FR-R293-4's backoff applies there) and the
  Tizen receiver.

## Open questions

1. **Closed track or internal as well?** Adding aleks to the closed-testing testers is one click and
   also counts toward Play's 12-testers-for-14-days rule (R215). Publishing to internal as well keeps
   today's internal-only households updated without asking them to opt in again. Lean: add them to
   closed testing, and have the workflow warn when a release leaves any known household device (by
   `app_version` in `ravilo_device`) more than one version behind for a week.
   **Decided 2026-09-24 (owner): closed testing only.** The tester account was added the same day; CI's
   billing block is lifted; R289 is built. FR-R293-8's steps 1–3 are done — **step 4 done 2026-09-25: v1.38
   (code 1038) uploaded to closed testing** (run `36065731242`). The next build containing this phase
   reaches that TV by the same road.
2. **How long may a return be "quick"?** FR-R293-3's catch-up threshold for a full Home refresh:
   lean 30 s, the longest gap the server's push stream can be assumed to have covered.
3. **The Android version of aleks's TV** (Play Console's device catalogue has it). If it predates
   Android 12's cached-app freezer, that is the "why only this TV" answer, and every pre-12 TV in the
   field is exposed the same way today.
   *Dev review:* the backend never records an API level (`ravilo_device` has none, no client sends one —
   R289's review, item 7); the Play Console is the only source. FR-R293-6's header could carry
   `SDK_INT` at no cost, which would answer this for the next device without the Console.

## Acceptance

Measured from the server; nobody needs to touch the remote TV.

1. After aleks's TV reports an `app_version` containing this phase (`ravilo_device.app_version`), its
   next evening of viewing followed by standby produces **one** disconnect at standby and **zero**
   reconnects until the TV is next switched on (jellystructure log, phase 256's per-device close lines).
2. Jellyfin's activity log shows no more than one `SessionStarted`/`SessionEnded` pair per viewing
   session for `BRAVIA 4K GB ATV3`.
3. On our own TV (with the owner's go-ahead) and the Pixel 9: HOME → the server logs the device's
   socket closed with reason `background` within 2 s, and no request from the device arrives until it
   is foregrounded again; foregrounding reconnects within 2 s and refreshes Home at most once.
4. Unit: the backoff sequence for a socket dying at 60 s is 1, 2, 4, 8, … 60 s (± jitter) and resets
   only after a 5-minute hold; commands received in a non-started state are dropped.
5. No Android build puts the device token in the events URL (a check script, like
   `check-jellyfin-query-token.sh`).

## Dev review (2026-09-24, against `main` `9d2636bb`)

Every client claim holds. The socket loop is `LaunchedEffect(activeUserId)` in `RaviloApp.kt:387-418`
— tied to composition, `runCatching` around `connectEvents` (`:399`), `onOpen = { …; liveConfig.emit(0L) }`
(`:401`), backoff reset by any socket held ≥ 2 s and capped at 15 s (`:416`). `liveConfig` drives both
`refreshConfig()` (`:386`) and, through `LocalLiveConfig`, `HomeScreen`'s `store.refresh(silent = true)`
(`HomeScreen.kt:117-118`) — so every open is a config pull *and* a Home rebuild, as measured. The R141
poll is `:424-431` (15 s); the Live TV poll is `HomeStore.liveTvPollJob` (`HomeStore.kt:22`, `:155-160`,
60 s), a store-level coroutine that outlives composition. The token is a query parameter
(`TvApiClient.kt:651-652`); the server takes `?token=` **or** `Authorization: Bearer` on the route; the
Android WebSocket client is Ktor CIO (`RaviloRootActuals.kt:48`), which sets handshake headers. Nine
items.

1. **FR-R293-1's hook is the Activity's lifecycle, not a new dependency.** Nothing in the catalog brings
   `lifecycle-process`, and it is not needed: each entry point is one Activity, so `LocalLifecycleOwner`
   at the root of `RaviloApp` *is* the process's foreground state — the pattern `PlayerLifecycleEffect`
   already uses. One new expect, `AppLifecycleEffect(onStart, onStop)`: the Android actual is a
   `LifecycleEventObserver` plus the `ACTION_SCREEN_OFF`/`SCREEN_ON` receiver R292 FR-R292-8 needs (one
   seam, two consumers); the wasm actual is a no-op. Then the socket effect is keyed on
   `(activeUserId, foreground)`: leaving the foreground cancels the effect, which cancels the loop *and*
   its `delay(backoff)` — "no reconnects, no backoff timer" falls out of structured concurrency, nothing
   to write. The R141 poll is the same effect shape. The Live TV poll is not: it is a job on a retained
   store, so `HomeStore` needs an explicit pause/resume (or a foreground gate inside its loop), or
   FR-R293-2 is half true.
2. **FR-R293-3's catch-up needs `onOpen` to stop emitting `0L`.** Every collector treats any emission as
   "refresh"; the rev is only used by the poll, which already has the right shape (`seenRev`, emit on
   change only, `:426-429`). On open: fetch `/api/tv/config/rev`; emit only if it moved, or if the gap
   exceeded the threshold. And separate the two consumers: R248's `liveHome` (`:474`) is the Home-specific
   channel; `HomeScreen` collecting `liveConfig` as a rebuild trigger (`HomeScreen.kt:118`) is exactly the
   "Home rebuild for nobody" and should refresh on `liveHome` and a *moved* config rev, not on every open.
3. **FR-R293-4 has a second socket the spec does not name.** `ScreenSender.runSocket`
   (`ScreenSender.kt:65-81`) carries the identical 2 s/15 s rule for `/api/remote/events`; a phone whose
   remote socket dies every minute has the same storm against the same server. Make the backoff one pure
   helper (acceptance 4's unit test) used by both, or say why the remote socket is exempt. Lean: share.
4. **FR-R293-5 is half built.** `livePlayItem.collect` drops when signed out or on Login/ProfilePicker
   (`RaviloApp.kt:604-606`); `liveNavigate` drops only when signed out (`:621-623`); neither knows the
   lifecycle. With item 1's foreground state in the same composable it is one more condition on each, and
   the log line the FR asks for.
5. **FR-R293-6 has its slot, and needs `connectEvents` to report how it ended.** `identify()`
   (`TvApiClient.kt:714-719`) is the one place every request *and* the WebSocket handshake (`:659`) get
   their headers — `X-Ravilo-Events-Prev` goes there, gated to the events call. But today the frame loop
   skips non-text frames and `runCatching` in the app swallows the throwable, so the close reason never
   exists anywhere: `connectEvents` must return an end record — `closeReason.await()` after the loop for
   a clean close, the exception class otherwise, "closed by us" when the effect cancelled it.
6. **FR-R293-7 blinds one server-side lookup unless 256 fixes it.** The outer exception handler at
   `Server.kt:236-241` names the device for its INFO line from the *query* token only; once Android sends
   Bearer, every such line reads `device=unknown`. Phase 256 FR-256-1 must use the same `token ?:
   Authorization` expression the route's top already does. `RemoteRoutes.kt:232` (the remote events
   socket) reads only the query token as well — the header form should land there too, or the phone's
   remote socket keeps the token in proxy logs. Acceptance 5's script is `check-jellyfin-query-token.sh`
   (phase 239) with one pattern, the wasm client exempt.
7. **FR-R293-8 and acceptance 1 are reachable.** `app_version` is not login-only: the auth plugin passes
   the version and platform headers on every request (`AuthPlugin.kt:182`, `Server.kt:283`) and
   `recordAppInfo` writes when they change (`RaviloDeviceService.kt:191-197`). A TV updated from Play
   reports its new build on its first request. (The events route validates without the headers,
   `Server.kt:667`; harmless, since REST follows.)
8. **One cost the bill missed.** Each connect also stamps the device's address (`recordAddress`, at the
   handler's top; "stamped on an events-socket open") — a DB write per flap, 1,326 of them. Harmless,
   and gone with the storm; worth a line so the next reader does not rediscover it.
9. **Open question 2.** With item 2's rev check on every open, the 30 s threshold only decides the Home
   refresh when the rev did *not* move — and R248's `home_changed` carries no rev, so a push missed while
   disconnected is invisible; the gap is the only signal. Keep the lean.

**Net effect.** One lifecycle seam shared with R292, the socket and poll effects keyed on it, a store-level
pause for the Live TV loop, `onOpen` demoted to a rev check, a shared backoff helper, a returned end
record, and the header. Nothing about the server's ping period, the protocol or the tester list changes
from this side.
