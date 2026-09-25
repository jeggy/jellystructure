# Phase 256 — A device whose connection keeps dropping is seen, explained, and costs nothing

## Status

`✓ Built` — **built 2026-09-25 from the dev review, all six items** (see §Build below), deployed to the
household backend the same day. `Planned` when written 2026-09-24. **Dev-reviewed 2026-09-24 against `main`
`9d2636bb`** (see §Dev review at the bottom: every server claim holds; "replaced" is already detectable in
`unregister`; the bridge's `connect` is already idempotent, so the grace is a deferred `disconnect`; FR-256-5
compares against the deployed backend's own version, not GitHub; the device row and its mockup both gain the
two lines). The backend half of **R293** (read its "What happens" for the measurements; they are not repeated
here).

### Build (2026-09-25)

- **FR-256-1 — one close line, classified** (`tv/EventsSocketHealth.kt`, `EventsCloseCause`): `TV events:
  device <id> closed user=<id> open=<s>s cause=<…>` with `client close <code> <reason>` from the client's
  close frame (`closeReason.await()`, bounded to 1 s), `ping timeout` (Ktor's own close reason or the
  throwable's text, never echoed), `eof` / `reset` from the throwable's class or text, `replaced` from
  `TvEventBus.unregister`'s identity check (item 1 — it now returns whether a newer socket held the slot),
  else `server error <Class>`. The old `connection dropped: <message>` WARN is gone (the message could carry
  a URL). The outer StatusPages handler reads `token ?: Authorization` and emits the same shape with
  `open=0s` (item 2).
- **FR-256-2 — the client's account** (`sanitizeEventsPrev`): R293's `X-Ravilo-Events-Prev` taken at 512
  bytes, ten entries, six fields each, every field reduced to `[A-Za-z0-9._:+-]` × 40; logged once as
  `TV events: device <id> previous sockets: …` on connect, never on the close line (item 6).
- **FR-256-3 — the flap counter** (`DeviceFlapCounter`, owned by `TvEventBus` under its mutex): connects and
  lifetimes per device over a rolling hour; above 12/h one WARN per device per hour (*reconnected N times in
  the last hour (Ravilo 1.35, tv); median connection 68 s*), `unstable_devices` on `/api/health/full`, and
  `reconnects_last_hour` on the admin overview's device — present only while unstable, so the row's
  *Unstable connection: N reconnects in the last hour* line renders or does not (server-decided). In memory.
- **FR-256-4 — the grace** (`tv/DeferredDisconnects.kt`, item 3): the handler's `finally` calls
  `sessionBridge.disconnectAfterGrace(deviceId)` (90 s, one cancellable job per device); `connect` cancels a
  pending one before its own idempotence guard, logging *bridge kept*. A **replaced** socket schedules
  nothing — the newer socket owns the bridge (a detail the review did not name: without it the old socket's
  timer would have closed the bridge under a connected device). `isConnected`, the stop watchdog and Now
  Playing are untouched, so Phase 110 FR B.2 holds by construction.
- **FR-256-5 — releases behind** (`VersionBehindTracker`, item 4): `raviloReleasesBehind(app, ServerVersion
  .current)` (259's arithmetic) ≥ 2, first seen per (device, version) in memory, said after seven days as
  `releases_behind` + `behind_since` on the overview; a dev build on either side says nothing — **which
  means a household running a `-g…` dev deploy (as this one does today) never sees the line**; only a
  released backend does. The admin row renders *Ravilo 1.35 — 3 releases behind since …* with the
  Play-tester hint; the mockup's bedroom TV carries both lines (item 5); `.usr-cap.unstable`/`.behind` in the
  served `wf.css`, fenced by `check-mobile-css.sh`.
- **Tests:** `EventsSocketHealthTest` (4: the four causes + reset/error, the sanitizer, the counter crossing
  and clearing, the seven-day rule) and `DeferredDisconnectsTest` (2: cancel inside the grace, fire once
  after it) — acceptance 1 and 2.

In one line: a Ravilo TV in another household reopened its `/api/tv/events` socket 1,326 times in 29
hours, about once a minute while nobody watched. The server noticed nothing, logged no reason for any
close, and turned every reconnect into a new Jellyfin session: 2,285 activity-log rows from that
device alone.

## What the server does today

- `webSocket("/api/tv/events")` (`Server.kt`) logs `TV events: device … connected` / `disconnected`
  and nothing about **why** a socket ended. A clean end (close frame, FIN) is silent; an exception is
  logged as `WS /api/tv/events device connection dropped: <message>` **without the device id**. Of
  1,326 disconnects from the flapping device, none carries a reason; the server's only 61 `Ping
  timeout` lines (all devices) are not attributable.
- Every connect calls `sessionBridge.connect(device)` and every disconnect
  `sessionBridge.disconnect(device.deviceId)` immediately. A device that reconnects every minute is a
  Jellyfin session that starts and ends every minute: `SessionStarted`/`SessionEnded` rows, and a
  `postCapabilities` call each time. Session rows are 43,244 of Jellyfin's 70,853 activity-log rows.
- Nothing counts reconnects. A device that flapped for 31 hours looked, from the admin UI, like a
  device that was online.
- Nothing tells the admin that a household's devices stopped receiving updates (aleks's devices are
  still on 1.35 two releases later; see R293 "Why the fix has not reached it").

## Requirements

- **FR-256-1 — Every close says why, with the device.** When an events socket ends, one line:
  device id, user, how long it was open, and the cause, classified: `client close <code> <reason>`,
  `eof`/`reset` (the peer vanished), `ping timeout`, `replaced` (the same device opened a newer socket),
  or `server error <class>`. The existing exception path gets the device id. No token, no URL.
- **FR-256-2 — The client's own account is logged too.** A client sending R293's
  `X-Ravilo-Events-Prev` header (the previous sockets' lifetimes and how they ended, as the device saw
  it) has it logged once per connect beside FR-256-1's line: bounded (≤ 512 bytes), sanitized to the
  documented field set, never echoed anywhere else. Together, the two sides of every close.
- **FR-256-3 — A flapping device is visible.** Per device, connects in a rolling hour. Above **12/h**:
  one `WARN` per device per hour (*"device … reconnected 38 times in the last hour (Ravilo 1.35, tv);
  median connection 68 s"*), the same figures in `/api/health/full`, and a line on that device's row in
  **Settings → Users & devices**: *"Unstable connection: 38 reconnects in the last hour"*. Cleared when
  the rate falls back under the threshold. Counted in memory: a restart forgets it, which is fine for a
  signal about *now*.
- **FR-256-4 — A reconnect is not a new Jellyfin session.** On an events-socket close the device's
  session bridge is kept for a **90 s grace period**; a reconnect of the same device within it re-uses
  the bridge (no `disconnect`, no new `postCapabilities`). The grace expires ⇒ the bridge closes as
  today. **Unchanged:** Phase 110 FR B.2's immediate playback stop on disconnect (Now Playing clears
  at once; a device that reconnects and is still playing reports progress again and re-appears). A
  device that is flapping does not change what Jellyfin's dashboard shows every minute.
- **FR-256-5 — A device that stopped updating is visible.** **Settings → Users & devices** marks a
  device whose last reported `app_version` is **more than one release behind the latest GitHub Release
  for more than seven days**: *"Ravilo 1.35 — 2 releases behind since 23 Sep. If this device installs
  from Google Play, check that its account is a tester on the track releases go to."* (R293 FR-R293-8.)
  Read-only; no automatic action. Devices on a `-dirty`/debug build are exempt.

## Non-goals

- **Refusing or throttling reconnects server-side.** It would make today's clients worse, not better: a
  1.35 client treats a socket that closes within 2 s as a failure and doubles its backoff only to a
  **15 s** cap, i.e. refusing it turns one reconnect a minute into four. The fix for the storm is the
  client's (R293); the server's job is to see it and not amplify it.
- Changing the server's ping period (30 s) or timeout (15 s): nothing measured implicates them.
- Any change to the events protocol other than reading R293's optional header.

## Open questions

1. **Grace length.** 90 s covers every measured reconnect gap (0–27 s) with room; long enough that a
   TV genuinely switched off still leaves Jellyfin's dashboard within two minutes. Lean: 90 s.
   *Dev review:* the bridge posts a keepalive every 30 s (`JellyfinSessionBridge.kt:38`), so a 90 s grace
   is at most three posts for an absent device — bounded and cheap. Lean stands.
2. **Threshold.** 12/h (one every five minutes) is far above any of our own devices (≤ 19 cycles in two
   days) and far below the flapping one (35–43/h). Lean: 12/h.

## Acceptance

1. Unit: the close classifier maps a close frame, an EOF, a ping timeout and a replaced socket to the
   four causes; the flap counter crosses 12/h and clears.
2. Unit: a reconnect inside the grace re-uses the bridge (no `disconnect`/`connect` on a fake bridge);
   a reconnect after it opens a new one.
3. On production after deploy: every `disconnected` line for a Ravilo device carries a cause; while any
   1.35 device still flaps, Settings → Users & devices shows it as unstable and as releases behind, and
   Jellyfin's activity log gains at most one session pair per 90 s gap rather than one per reconnect.

## Dev review (2026-09-24, against `main` `9d2636bb`)

Every server claim holds. `tryRegister`/`unregister` log connect and disconnect with no cause
(`TvEventBus.kt:49`, `:58`); the handler's catch logs `WS /api/tv/events device connection dropped:
${e.message}` with `device` in scope and unused (`Server.kt`, the events handler); the outer exception
handler (`:236-241`) finds the device by the query token only; `sessionBridge.connect` runs on every
connect and `disconnect` in the handler's `finally`; ping 30 s / timeout 15 s (`:195-198`); nothing
counts anything. Six items.

1. **"Replaced" is already detectable, in one place.** `tryRegister` overwrites a same-device entry
   (`TvEventBus.kt:47`) and `unregister` removes it only when `map[deviceId] === session` (`:54`). The
   `else` branch of that identity check *is* the `replaced` cause — the older socket's cleanup finding a
   newer one in its slot. Classify there, not from timing.
2. **FR-256-1's other three causes come from Ktor and the exception.** After the frame loop,
   `closeReason.await()` on the server session yields the client's code and reason for a clean close and
   `null` for an abrupt end (`eof`/`reset`); a ping timeout and anything else arrive as the throwable
   the catch already holds — classify by its class, with the message kept out of the line unless it is
   one of the known shapes. The outer handler at `:236-241` covers a close thrown *outside* the handler
   (upgrade or close handshake) and must (a) read `token ?: Authorization` exactly as the route's top
   does — R293 FR-R293-7 makes the query form disappear from Android, and this line would read
   `device=unknown` for every TV afterwards — and (b) emit the same one-line shape as the `finally`
   path, so a reader has one format to grep.
3. **FR-256-4 is a deferred `disconnect`, because `connect` is already idempotent.** `connect` returns
   early when the device is `active` (`JellyfinSessionBridge.kt:95-100`); so the grace is: the handler's
   `finally` schedules `disconnect(deviceId)` 90 s out (one cancellable job per device, the map guarded by
   the same lock), and `connect` cancels a pending one before its own guard. No change to `runLoop`, to
   `postCapabilities`, or to `tvEventBus.isConnected` (`TvEventBus.kt:63`) — which is what the stop
   watchdog and Now Playing read, so FR-256-4's "unchanged" clause is true by construction: the grace
   touches the bridge only. Acceptance 2's fake bridge tests exactly the schedule-and-cancel.
4. **FR-256-5 compares against the deployed backend, not GitHub.** Nothing in the backend queries
   releases (no `releases/latest` anywhere), and it need not: `ServerVersion.current`
   (`ServerVersion.kt:11`, the env override or `BuildInfo.version`) is the release the backend was built
   from, and "a client older than its server" is the comparison that matters. Versions are plain
   `MAJOR.MINOR` (231): "more than one release behind" is a MINOR gap ≥ 2 on the same MAJOR. Two exemptions
   the spec should state: a client on a `-dirty`/`-g…` build (already listed) **and a server on one** —
   production runs a dev build as this is written, and `1.37-6-g…` parses as 1.37 only if the code says
   so. Seven days of "behind" needs a first-seen timestamp per device, in memory is enough.
5. **FR-256-3's admin line has its row, and its mockup.** The device row is `RaviloUsers.kt:185-187`
   (`usr-cap` lines: created/last seen, `appVersionLine`); `OverviewDevice` (`RaviloApi.kt:42-52`) gains
   `reconnects_last_hour` and the behind-since fields, additive. The mirror is
   `design/app/ravilo-users.html:75-76` — add both lines there in the same pass, or the next design export
   removes them from the served page (the 187/185 lesson: `.usr-cap` itself once lived only in the mockup).
   `/api/health/full` extends `healthSnapshot()` (`JellyfinSessionBridge.kt:118`), which is already the
   per-device shape.
6. **FR-256-2's header is bounded on arrival.** Ktor gives the handshake headers inside the route
   (`call.request.headers`); take the first 512 bytes, keep only the five documented fields per entry,
   drop the rest silently. Log it once, on the connect line, never on the close line — the close line is
   what the flap counter will be grepped from.

**Small correction.** "Nothing tells the admin that a household's devices stopped receiving updates" is
right, but `app_version` itself is fresh: the auth plugin records it on every request
(`AuthPlugin.kt:182` → `recordAppInfo`), so FR-256-5 reads a live value, not a login-time one.

**Net effect.** One classifier in `unregister` + the catch, a deferred `disconnect`, a rolling counter, two
DTO fields, two admin lines (and their mockup), and a version comparison against `ServerVersion.current`.
