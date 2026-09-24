# Phase R293 — An app that is off screen holds no connection open, and the fix reaches the TV that needs it

## Status

`Planned` — written 2026-09-24 from a server-side investigation, not dev-reviewed, not built. Spec
first. Pairs with **phase 256** (the backend half: close reasons, flap detection, bridge debounce).
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
2. **How long may a return be "quick"?** FR-R293-3's catch-up threshold for a full Home refresh:
   lean 30 s, the longest gap the server's push stream can be assumed to have covered.
3. **The Android version of aleks's TV** (Play Console's device catalogue has it). If it predates
   Android 12's cached-app freezer, that is the "why only this TV" answer, and every pre-12 TV in the
   field is exposed the same way today.

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
