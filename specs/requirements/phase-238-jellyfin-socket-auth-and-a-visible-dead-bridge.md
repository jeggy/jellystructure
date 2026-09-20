# Phase 238 — The Jellyfin socket authenticates with a header, and a bridge that dies says so

> Owner direction 2026-09-18: the household's Jellyfin was upgraded to **12.1.0**. We target the
> latest Jellyfin and nothing older — no compatibility shim, no version branch, no fallback to a
> spelling an older server wanted.

## Status

`✓ Built` 2026-09-20 — written 2026-09-18 from a live audit of the upgraded household server (12.1.0),
dev-reviewed 2026-09-19 against `main` `dcb97f2c`, built 2026-09-20 with every review item taken.
Pair: **239** (the remaining query-string tokens), **240** (the guard test that should have caught
this), **241** (the mock that could not). Research report:
`specs/research-reports/jellyfin-12-1-upgrade-audit-2026-09-18.md`.

### Build (2026-09-20)

**The measured table was re-measured first, against the live server, with a real token** — because the
whole phase turns on it and one wrong row would have moved the fix to the wrong mechanism
(`jellyfin.example.net`, 12.1.0, 2026-09-20, `curl --http1.1`; note HTTP/2 answers 404 to an upgrade
attempt and tells you nothing):

| handshake | result |
|---|---|
| no credential | 403 |
| `?api_key=<valid>` | **403** |
| `?apikey=<valid>` | 101 |
| `Authorization: MediaBrowser … Token="<valid>"` | **101** |
| `X-Emby-Token: <valid>` | 403 |

- **FR-238-1** — the bridge sends `Authorization` and keeps `deviceId` as a plain query parameter.
  Review item 2 taken: `jellyfinAuth` widened `private` → `internal` rather than hand-rolling the
  header, so FR-238-1's own justification ("one place a credential becomes wire format") is actually
  true. Review item 3: the header's `DeviceId` is byte-identical to the query parameter **by
  construction** (both from the same `identity`), so there is nothing to check and no check was added.
  Open question 1 needed no probe — the review settled it from the Ktor artefact, and the live 101
  above confirms it end to end.
- **FR-238-2** — the log-once-**ever** `HashSet` is gone. First failure logs; a device that keeps
  failing logs again at most every 15 minutes with its consecutive-failure count and how long since
  its last success; a connect resets both. "This has been broken for three hours" is now something the
  log can say.
- **FR-238-3** — split per review item 4. `/api/health` (unauthenticated — the container HEALTHCHECK
  hits it) carries **counts only**: `session_bridges: {connected, failing}`. The per-device block
  lives on `/api/health/full`, which is authenticated. `last_error` is a **classified** reason
  (`BridgeFailure`), never `e.message`: curl and Ktor failure text routinely carries the request URL,
  and until FR-238-1 that URL had the household's Jellyfin token in it — so the obvious
  implementation would have published a live credential to a health endpoint. `BridgeFailureTest`
  pins that with the exact pre-fix URL as its input.
- **FR-238-6, new, from review item 6** — `never_attempted`. The blank-URL / blank-token guard throws
  nothing, so it logged nothing at all, not even the first line, and would have read as
  `connected: false, last_error: null` — indistinguishable from a handshake being refused. It is its
  own state with its own reason now.
- **Review item 5** — all cross-thread bridge state (`active`, the new per-device state) sits behind
  one `SpinLock`. Not a `Mutex`: the health handler's read must not suspend on a lock the retry loop
  holds.
- **Review items 7 and 8** are recorded in the code rather than changed: the permit is held for the
  whole retry loop (a give-up rule must *release* it), and `tvToken`'s server-token fallback chooses a
  **credential**, not a wire format, so FR-238-4 does not touch it. Note the fallback has never been
  exercised against real 12.1, because until FR-238-1 a dead device token and a good server token
  failed identically.
- **Tests.** `BridgeFailureTest` (6, green) for the classifier and the leak.
  `tests/e2e/jellyfin-session-bridge.spec.ts` opens a real TV events socket and asserts the bridge
  reaches **connected** on `/api/health/full`, plus that no device id appears on `/api/health`. That
  spec is what makes phase 241's headline acceptance possible at all — see its review item 1.

### Verified on production (v1.31, 2026-09-20)

- **Acceptance 1** — `Jellyfin session bridge connected: device=16149cda… user=7450a8c6…` in the
  backend log within seconds of the recreate, and `/api/health` reporting
  `session_bridges: {connected: 1, failing: 0}`.
- **Acceptance 2** — Jellyfin's own `Token is required. URL GET /socket` went from **one every 60
  seconds** to **zero**, from the moment the new bridge connected. (Mind the clocks: Jellyfin logs in
  UTC, the host is UTC+2 — the last rejection at `04:25:06Z` is one minute *before* the connect at
  `04:26:14Z`, not an hour after it.)
- **Acceptance 3 — fully verified on the stue TV (v1.32, 2026-09-20).** Its session row flipped from
  the stale `SupportsMediaControl: false` to **`true`** the moment its bridge connected
  (`device=84a570800cc828ec…`), which is `postCapabilities` landing — the thing that has been silently
  false since the upgrade.

  Then, end to end, with nobody at the TV:

  | step | result |
  |---|---|
  | `POST /Sessions/{id}/Playing` (dashboard *Play*) | Big Buck Bunny started on the TV |
  | `POST /Sessions/{id}/Command` (*DisplayMessage*) | the card rendered over the playing video |
  | `POST /Sessions/{id}/Playing/Unpause` | playing, 51 s → 61 s (advancing) |
  | **`POST /Sessions/{id}/Playing/Pause`** | **`IsPaused: true` at 62 s** |
  | 10 s later | still `IsPaused: true`, still 62 s |
  | `POST /Sessions/{id}/Playing/Stop` | playback ended |

  Every one of those travels Jellyfin → `/socket` (the connection that was answering 403) →
  `handleIncoming` → `TvEventBus` → the TV's own `/api/tv/events` socket. This is the phase 110
  guarantee, restored.

  **One false alarm worth recording.** A pause issued *before* playback had established did nothing —
  the client only collects `LocalPlaystateCommands` while `PlayerScreen` is composed, which is
  FR-R155-2's deliberate "ignore when no player is open". It briefly looked like the Playstate path
  was broken while `GeneralCommand` worked; it was not.

Acceptance 4 and 5 hold in CI against the tightened mock. Open question 2 stays open with its lean,
now observable either way.

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

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced against `JellyfinSessionBridge.kt`, `JellyfinClient.kt`, `AuthPlugin.kt`, `Server.kt`, `Main.kt`,
`Dockerfile` and the Ktor artefact on the build path. **The diagnosis is correct and FR-238-1 is safe to
build — open question 1 is answered below, not deferred.** Three things change: where FR-238-3's block
lives, what `last_error` may contain, and the concurrency the new state needs.

1. **Open question 1 is answered: yes, and no probe is needed.** The engine is
   `HttpClient(Curl) { install(WebSockets) }` (`:53`), and the artefact this build links against is
   `ktor-client-curl-linuxX64Main-3.6.0.klib`. Its `CurlAdapters.kt` declares
   `DISALLOWED_WEBSOCKET_HEADERS = setOf("Upgrade", "Connection", "Sec-WebSocket-Version",
   "Sec-WebSocket-Key")`, and `headersToCurl(request: HttpRequestData)` appends every header to the curl
   slist, skipping a header only when it is in that set *and* `request.isUpgradeRequest()`.
   `Authorization` is not in the set, and `toCurlRequest` builds the slist the same way for an upgrade as
   for any other request. **Ktor 3.6.0's Curl engine sends a custom `Authorization` header on the
   WebSocket upgrade.** FR-238-1 stands as written; the `apikey` fallback is not needed and FR-238-4's
   "one handshake shape" costs nothing. (Method worth reusing: a `.klib` is a zip, and
   `default/ir/strings.knt` carries declaration names and string literals — enough to settle questions
   like this without a probe build.)
2. **The helper FR-238-1 names cannot be called from here.** `jellyfinAuth` (`JellyfinClient.kt:1259`)
   is `private` and is an extension on `HttpRequestBuilder` declared inside that file; the bridge owns a
   separate `HttpClient` and has no access. `jellyfinIdentityHeader` (`:1243`) is `internal` and does
   reach. Either the bridge writes
   `header("Authorization", """${jellyfinIdentityHeader(identity)}, Token="$effectiveToken"""")` itself,
   or `jellyfinAuth` is widened to `internal` and used. **Widen it** — FR-238-1's own justification
   ("exactly one place in the codebase where a Jellyfin credential becomes wire format") is only true in
   the second form.
3. **The `DeviceId` already agrees; nothing to enforce.** `JellyfinDeviceIdentity.forDevice` (`:1228`)
   composes `"ravilo-${device.deviceId}-${device.jellyfinUserId}"`, and the existing URL already sends
   that same composed value as its `deviceId` query parameter (`:92`). Passing the same `identity` object
   into the header makes `DeviceId="…"` byte-identical to the query parameter by construction, so
   FR-238-1's last sentence is satisfied by changing nothing. Say so, so nobody adds a check.
4. **`/api/health` is unauthenticated, so FR-238-3 as written publishes device ids and an exception
   string to anyone who can reach the server.** `AuthPlugin.kt:125` exempts exactly `/api/health` — for
   orchestration probes that cannot carry a session, which is `Dockerfile:130` — while `/api/health/full`
   is *not* exempt. Two consequences. (a) A per-device list of ids plus failure state becomes world-
   readable; on this household's server that means the internet (phase 244's premise). (b) `last_error`
   taken from an exception message is worse than it looks: curl and Ktor failure text routinely carries
   the request URL, and today that URL is the one with `api_key=<token>` in it. **Split the requirement:**
   `/api/health` gains counts only — `bridges_connected`, `bridges_failing` — and the per-device block
   lives in `/api/health/full`, which is authenticated and is where audit-shaped detail belongs. And
   `last_error` is a **classified reason** ("403 at handshake", "connect timeout", "no Jellyfin URL
   configured"), never a raw `e.message`.
   **Sibling finding, recorded rather than fixed here:** the precedent FR-238-3 cites has the same
   problem already. Phase 219's `refreshers` block (`Server.kt:427`) keys `playstate_age_ms` and
   `continue_age_ms` by **Jellyfin user id**, on that same open endpoint. That belongs to 244's sweep, not
   to this phase, but it should not be repeated here on the strength of being a precedent.
5. **The bridge's shared state is unsynchronised today, and FR-238-2/-3 add a third thread to it.**
   `active: HashMap` (`:56`) and `bridgeDropLogged: HashSet` (`:60`) are mutated by `connect`/`disconnect`
   from the `/api/tv/events` route (`Server.kt:636`, `:657`) and read by the retry loop from `rootScope`,
   which is `CoroutineScope(SupervisorJob() + CoroutineExceptionHandler)` with **no dispatcher**
   (`Main.kt:126`) — `Dispatchers.Default`, genuinely multi-threaded on Kotlin/Native under coroutines
   1.11.0. That is already a latent race; FR-238-2's counters and suppression timestamps and FR-238-3's
   health read make it a certainty, and the health read is on yet another thread. The package already
   knows better: `TvEventBus` guards its `sessions` map with a `Mutex` (`TvEventBus.kt:29`). **Hold the
   new per-device bridge state in one `SpinLock`-guarded map** (`ops/SpinLock.kt`, exactly as
   `TmdbClient:86` and `MediaStore:160` do for cheap reads from a request thread) and move
   `active`/`bridgeDropLogged` behind it while there. A `Mutex` will not serve: the health handler's read
   must not suspend on a lock the retry loop holds.
6. **One failure mode FR-238-2 cannot see, because it throws nothing.** `runLoop`'s guard (`:82-85`):
   a blank `jellyfinUrl` or a blank `device.jellyfinUserToken` does `delay(RECONNECT_MAX_MS); continue`
   with **no exception and therefore no log at all — not even the first one**. FR-238-2 only rewrites the
   `catch` branch, so this device stays invisible, and in FR-238-3's block it would read as
   `connected: false` with a null `last_error`, indistinguishable from a device whose handshake is being
   refused. Model it as a third state — `never_attempted`, with the reason — distinct from a real
   handshake failure.
7. **Open question 2: the semaphore makes the waste worse than the question assumes.**
   `bridgeSemaphore.withPermit { while (isActive) { … } }` (`:76`) holds the permit for the **whole retry
   loop**, not per attempt, and `MAX_BRIDGE_CONNECTIONS = 16` (`:34`). A permanently failing bridge
   therefore occupies one of sixteen slots forever while achieving nothing, and a 17th TV blocks at
   `withPermit` and never attempts a handshake at all. On 12.1 today *every* bridge is in that state. The
   lean (keep retrying) is still right for a household of this size, and FR-238-3 is the right answer to
   the visibility half — but record the interaction, and if a give-up rule is ever added it must
   **release the permit**, not merely stop logging.
8. **FR-238-4 must not be read as deleting the token fallback.** `jellyfinClient.tvToken(base, device,
   cfg.apiKeys.jellyfinToken)` (`:90`) is phase 141's negative-cache plus server-token fallback: it
   chooses *which credential* to present, not *which wire format*. FR-238-4 forbids the second and says
   nothing about the first. Spell that out, because "no fallback" next to a line that is literally a
   fallback is exactly the sort of thing a builder resolves the wrong way. Note also that the fallback has
   never been exercised against the real 12.1 failure — a dead device token and a good server token both
   fail the handshake identically today, so the first genuine test of that path comes after FR-238-1.

**Acceptance.** Sound. One refinement: acceptance 2's "17 in 15 minutes" is a per-device count for one
TV, so capture the pre-fix baseline per device id — the household has several, and the aggregate will not
match. Acceptance 5's `/api/health` reference follows item 4 to `/api/health/full`.
