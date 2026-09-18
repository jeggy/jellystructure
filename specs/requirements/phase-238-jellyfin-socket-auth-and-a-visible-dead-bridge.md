# Phase 238 — The Jellyfin socket authenticates with a header, and a bridge that dies says so

> Owner direction 2026-09-18: the household's Jellyfin was upgraded to **12.1.0**. We target the
> latest Jellyfin and nothing older — no compatibility shim, no version branch, no fallback to a
> spelling an older server wanted.

## Status

`Planned` — written 2026-09-18 from a live audit of the upgraded household server (12.1.0), not
dev-reviewed, not built. Pair: **239** (the remaining query-string tokens), **240** (the guard test
that should have caught this), **241** (the mock that could not). Research report:
`specs/research-reports/jellyfin-12-1-upgrade-audit-2026-09-18.md`.

## What is wrong

`JellyfinSessionBridge` authenticates its outbound WebSocket by putting the token in the query
string (`JellyfinSessionBridge.kt:92`):

```kotlin
"/socket?api_key=$effectiveToken&deviceId=${identity.deviceId}"
```

Jellyfin 12.1 does not accept that parameter name. Measured against the live server on 2026-09-18:

| Handshake | Result |
|---|---|
| `/socket?api_key=<token>` | 403 Forbidden |
| `/socket?apikey=<token>` | 101 Switching Protocols |
| `/socket` + `Authorization: MediaBrowser … Token="…"` | 101 Switching Protocols |
| `/socket` with no credential | 403 Forbidden |

So phase 110's bridge cannot open at all. Jellyfin logs `Token is required. URL GET /socket` once a
minute, forever. On the audited server that was 17 rejections in 15 minutes for one device
(`84a570800cc828ec84196c9114603fac`, *BRAVIA 4K VH21*).

**The consequence is not only the socket.** `postCapabilities` runs inside the connected block, so
while the handshake fails the device never registers `SupportsMediaControl`. Jellyfin's dashboard
cannot pause or seek that TV, the remote-control menu never lists it, Home Assistant never sees it as
a controllable player, and `TvEventBus` never delivers a `Message` or `Playstate` command to it.
Every one of those is a phase 110 guarantee that is currently false.

**It fails silently, which is why nobody noticed.** `bridgeDropLogged` is a `HashSet` guarding the
warn (`JellyfinSessionBridge.kt:60`, used at `:120-123`): the first drop per device logs, and every
later failure is swallowed. The loop then retries forever with `backoff` capped at
`RECONNECT_MAX_MS = 60_000` (`:37`, `:128`). A permanently broken bridge therefore produces exactly
one line in the backend log and then nothing, ever again, while the retry loop runs for the life of
the process. The audited server's single warning was at 20:16:51; the failure was still live at
20:32 with no further backend output.

That set exists for a good reason — a flapping TV must not spam the log. But "log once" and "log
once ever" are different things, and the code implements the second.

## Requirements

**FR-238-1 — The handshake carries an `Authorization` header, not a query parameter.** The bridge
builds its credential the same way every other authenticated Jellyfin call does, through
`jellyfinAuth`/`jellyfinIdentityHeader` (`JellyfinClient.kt:1243`, `:1259`), and passes it as a
request header on the `webSocket(...)` call. No token appears in a URL. The `deviceId` query
parameter stays: it is not a credential, and the header's own `DeviceId` must carry the same value
so Jellyfin binds the socket to the session the REST calls already use.

Using the header rather than `apikey` is deliberate. Both work on 12.1, but the header is the one
mechanism already used everywhere else, it keeps the token out of proxy and access logs, and it
leaves exactly one place in the codebase where a Jellyfin credential becomes wire format.

**FR-238-2 — A bridge that cannot connect stays visible.** Replace the log-once set with a rule that
cannot hide a permanent failure:

- The first drop per device logs at warn, as today.
- Subsequent consecutive failures for the same device log at warn again **at most once every 15
  minutes**, carrying the consecutive-failure count and the elapsed time since the last success.
- A successful connect resets both the suppression window and the counter, and logs at info, as
  today.

**FR-238-3 — `/api/health` reports bridge state.** A `session_bridges` block beside phase 219's
`refreshers`, listing per device id: `connected` (bool), `consecutive_failures`, `last_error`
(status or message, truncated), and `last_connected_ms_ago` (null if never). This is the signal that
makes the failure observable without reading container logs, the same reasoning phase 203's
`mkv_health_swept_at` and phase 219's refresher ages were added under. Cheap reads only, no probing
inside the handler.

**FR-238-4 — No fallback, no version branch.** The bridge attempts one handshake shape. It does not
retry with `api_key` on a 403, does not sniff the server version, and does not carry a compatibility
path for a Jellyfin older than 12. A 403 is a real failure and is reported as one.

## Non-goals

- Changing what the bridge does once connected. Keepalive, frame handling, the semaphore cap and
  `disconnect()` are untouched.
- The other query-string tokens elsewhere in the codebase. Those are phase **239**, because they are
  on routes that answer anonymously on 12.1 and are therefore not broken today.
- Any admin UI for bridge state. FR-238-3 is a health-endpoint field; whether Activity or Settings
  renders it is a later decision.
- Jellyfin's own `/socket` behaviour. We match what the current server accepts; we do not ask it to
  keep accepting what it dropped.

## Acceptance

1. With the household server on 12.1, a paired TV's bridge connects, and the backend logs
   `Jellyfin session bridge connected` for it.
2. Jellyfin stops logging `Token is required. URL GET /socket`. Measured over 15 minutes, the count
   is zero where it was 17.
3. That TV appears as a controllable session in Jellyfin's dashboard, and a pause issued there
   reaches the TV. This is the phase 110 guarantee and is the real acceptance test.
4. No token appears in any Jellyfin URL the bridge constructs. Grep for `api_key` in
   `JellyfinSessionBridge.kt` returns nothing.
5. With the token deliberately invalidated, the backend logs the drop, then logs again roughly 15
   minutes later with a failure count above one, and `/api/health` reports
   `connected: false` with a non-null `last_error` throughout.

## Open questions

1. Does Ktor's Curl engine `webSocket(...)` reliably send a custom `Authorization` header on the
   upgrade request on Kotlin/Native? If it does not, `apikey` is the fallback and FR-238-1's
   reasoning about log hygiene is the only thing lost. Confirm before building, the same way phase
   163's 405 and FR-187-1's image body were confirmed before building.
2. Should a bridge that has failed continuously for some long period stop retrying rather than loop
   forever at 60 s? Leaning no: the TV is still paired and the failure may be transient server-side.
   But an hours-long loop against a server that will never accept it is pure waste, and FR-238-3 now
   makes the decision observable either way.
