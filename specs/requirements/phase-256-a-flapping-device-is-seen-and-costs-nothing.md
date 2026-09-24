# Phase 256 — A device whose connection keeps dropping is seen, explained, and costs nothing

## Status

`Planned` — written 2026-09-24, not dev-reviewed, not built. Spec first. The backend half of **R293**
(read its "What happens" for the measurements; they are not repeated here).

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
