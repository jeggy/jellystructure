# Phase 194 — One network blip marks a good token dead for ten minutes and blocks playback on every device

> Live report, 2026-09-06: *"I tried to play some media on soveværelse TV this morning, but it didn't
> start fast enough, so I stopped."*

## Scope
All six FRs. Root-caused from production logs, the production database, and a live token probe against
Jellyfin. Companion client-side phase: **R237** (the ~15 s spinner that hid this from the viewer).
FR-194-6 is the phase that owns the shared `OutboundHttp` client's retry policy, and it is the only one
of the six that reaches beyond the token path — see the 2026-10-13 amendment.

**The same failure recurred on stue TV at 07:38:58Z the morning this was built**, which is what
triggered the implementation: `TV: paired user token rejected by Jellyfin (401) for user 7450a8c6…
— negative-cached 10min`, 14 minutes after that device had played normally, against a token that
probed `200` in ~30 ms five times in a row. Two devices in two days; not a rare race.

### What was built
- `JellyfinClient.isTokenValid` → **`checkToken`**, returning `TokenCheckResult(outcome, httpStatus,
  error)` over the three-state `TokenCheck` (FR-194-1). The caught exception is carried out instead of
  being swallowed by `getOrDefault(false)`.
- `isPairedTokenValid` → **`pairedTokenCheck`**, returning `TokenCheck`. `UNKNOWN` writes neither cache
  and — the part that mattered most — no longer clears an established positive entry (FR-194-2).
- `tvTokenForClient` returns the device's own token on `UNKNOWN`, `null` only on `REJECTED`
  (FR-194-3). `tvToken`'s 19 call sites are untouched in behaviour.
- The fabricated `"(401)"` is gone; three log lines that each name their real cause, with the `UNKNOWN`
  ones deliberately *not* suppressed by `tokenRejectionLogged` (FR-194-4).
- One ~500 ms re-probe before a `REJECTED` is believed and cached (FR-194-5).
- FR-194-6: the shared `OutboundHttp` Curl client had **no** retry of any kind (confirmed — no
  `HttpRequestRetry` was installed). Added one, scoped hard: `maxRetries = 1`, idempotent methods only
  (GET/HEAD/OPTIONS), and only when the call *threw* a connection-layer failure — never when a request
  reached the server and got an answer, so Phase 183's 429/AIMD pacing and the 503 + `Retry-After`
  contract are untouched. An unrecognised exception is simply not retried, i.e. today's behaviour.
- `TvRoutes`' FR-187-8 password-change probe now treats only `REJECTED` as "the token died"; a
  transient failure there would otherwise tell the viewer to sign in again on every device for nothing.

`compileKotlinLinuxX64` clean; 221 `linuxX64Test` tests pass (run from the linked test binary — the
Gradle test task itself fails in its own report writer, unrelated to this change).

## What actually happened

Soveværelse TV is `ravilo_device.device_id = 52ff71d00407f87b14846d9f1520a04d` ("BRAVIA 4K VH2",
user `jogvan` / `7450a8c6f40a4ac89a1487b23f47c332`). Local time, 2026-09-06:

| Time | Event |
|---|---|
| 07:31–07:51:55 | Server completely idle — the 07:30 scheduled pipeline finished at 07:30:08, no log lines at all for 20 minutes |
| 07:51:55 | `[WARN] TV: paired user token rejected by Jellyfin (401) for user 7450a8c6… — negative-cached 10min` |
| 07:52:00 | `TV events: device 52ff71d0… connected`; Jellyfin session bridge connected |
| 07:52:23 | Pratarna — play attempt |
| 07:52:27 | Pratarna — play attempt |
| 07:52:33 | Mintys Træhus — play attempt |

All three attempts wrote a `playback_qoe` row with `video_decoder = NULL`, `direct_play = 0`,
`link_kind = 'unknown'`, `bandwidth_estimate_bps = NULL`. Every one of the ~40 prior rows this device
has ever written carries a real decoder and `direct_play = 1`. These three never received a stream.

The 10-minute negative cache opened at 07:51:55 and ran to 08:01:55. All three attempts fall inside it.

**The token was never invalid.** Probed live against `https://jellyfin.example.net` on 2026-09-06,
using the exact `jellyfin_user_token` still stored for that device, against the exact endpoint
`isTokenValid` uses:

```
GET /Users/7450a8c6f40a4ac89a1487b23f47c332
try1: 200 0.100s   try2: 200 0.121s   try3: 200 0.079s   try4: 200 0.094s   try5: 200 0.069s
```

The whole container lifetime (9 h) contains exactly **two** of these warnings — 05:55 and 07:51 local,
both today, both for the same still-valid token. Because `tokenRejectionLogged` (`:911`) suppresses
repeats until a success clears it, two logged lines means the token succeeded in between. It is not
dying. It is being *intermittently misjudged*.

## Root cause

`JellyfinClient.isTokenValid` (`auth/JellyfinClient.kt:289-292`):

```kotlin
suspend fun isTokenValid(baseUrl: String, token: String, userId: String): Boolean = runCatching {
    if (token.isBlank()) return false
    httpGet(baseUrl.trimEnd('/') + "/Users/$userId") { jellyfinAuth(token) }.status.isSuccess()
}.getOrDefault(false)
```

Three distinct outcomes collapse into the single value `false`:

1. Jellyfin answered **401/403** — the token really is rejected. *This is the only case the caller is
   designed for.*
2. Jellyfin answered **5xx / 502 / 504** — the server is unhealthy. Says nothing about the token.
3. **The call threw** — connect timeout, TLS handshake failure, DNS blip, a stale pooled connection
   the far end had already closed. Says nothing about the token, and the exception is swallowed by
   `getOrDefault(false)` without ever being logged, so there is no evidence left behind.

Case 3 is overwhelmingly the likely one here: the server had been idle for 20 minutes, Jellyfin is
reached over external HTTPS (`jellyfin_url = "https://jellyfin.example.net"`), and the shared
`OutboundHttp` Curl client (`OutboundHttp.kt:177-183`, `connectTimeoutMillis = 10_000`) pools
connections across that idle window. The first outbound call after a long quiet period is precisely
where a stale pooled connection surfaces — and it surfaced 5 seconds before the TV finished connecting.

`isPairedTokenValid` (`tv/PlaybackService.kt:890-919`) then treats that `false` as authoritative:

```kotlin
} else {
    tokenValidUntil.remove(userToken)
    tokenInvalidUntil[userToken] = nowMs() + TOKEN_NEGATIVE_TTL_MS   // 10 minutes
}
…
Logger.warn("TV: paired user token rejected by Jellyfin (401) for user … — negative-cached 10min")
```

Two things compound the misdiagnosis:

- **The message asserts a status code no code path ever read.** `isTokenValid` returns a `Boolean`;
  the "(401)" is a guess baked into the log line. It sent this investigation looking for an expired
  sign-in that does not exist, and it would send the next one the same way.
- **The failure is cached; the success is too, but shorter.** `TOKEN_VALID_TTL_MS` is 5 minutes
  (`:41`), `TOKEN_NEGATIVE_TTL_MS` is 10 (`:49`). A wrong answer therefore outlives a right one by 2×,
  and nothing re-probes inside the window — the fast path at `:897` returns `false` without a round
  trip, so a Jellyfin that recovered one second later is not consulted again for ten minutes.

### Why browsing kept working and only playback broke

There are two consumers of `isPairedTokenValid`, and they degrade in opposite directions:

- `tvToken` (`:921-922`, 19 call sites) falls back to the long-lived **server** token. Home feed,
  artwork, detail pages, Continue Watching — all kept working normally.
- `tvTokenForClient` (`:935-936`) deliberately does **not** fall back, because it embeds the token in
  a stream URL handed to the client (the 2026-08-02 H2 security fix — a stale device token must never
  be silently upgraded to server-admin credentials). It returns `null`.

So `startPlayback` (`:347-348`) and `restream` (`:775-776`) throw
`JellyfinReauthRequiredException` → `HttpStatusCode.Conflict` (`server/Server.kt:208-210`).

The security fix is correct and this phase does not weaken it. But it means a transient network error
is converted into a hard, deterministic, ten-minute playback outage for **every device belonging to
that user** — the cache is keyed on the token, and one user's token is shared across all their paired
devices — while the rest of the app carries on looking perfectly healthy. That is the worst possible
shape for a diagnosis: nothing is visibly broken except the one thing you wanted to do.

## Problem, stated plainly

An unreachable dependency and a rejected credential are not the same fact, and only one of them is a
reason to stop trusting a credential. The code has one channel for both, caches the fused answer for
longer than it caches the truth, and logs it as a status code it never saw.

## Goal

A Jellyfin that is briefly unreachable produces, at most, one failed request — not a ten-minute
playback outage across a household. When a token genuinely is rejected, that stays exactly as visible
and as safe as it is today.

## Requirements

### FR-194-1 — Three outcomes, not two

`isTokenValid` stops returning `Boolean`. It returns a three-state result:

```kotlin
enum class TokenCheck { VALID, REJECTED, UNKNOWN }
```

- `REJECTED` — Jellyfin answered **401 or 403**. The only outcome that means the token is bad.
- `VALID` — Jellyfin answered 2xx.
- `UNKNOWN` — the call threw, or Jellyfin answered anything else (5xx, 429, an unexpected 4xx). The
  question was not answered.

The thrown exception's message must be logged at WARN on the `UNKNOWN` path. Swallowing it into
`getOrDefault(false)` is what left this incident with no evidence, and the fix is not complete without
it.

### FR-194-2 — Only a rejection is cached as a rejection

In `isPairedTokenValid`:

- `REJECTED` → negative-cache for `TOKEN_NEGATIVE_TTL_MS`, exactly as today.
- `VALID` → positive-cache for `TOKEN_VALID_TTL_MS`, exactly as today.
- `UNKNOWN` → **cache nothing.** Neither map is written and neither is cleared. The next call re-probes.

An `UNKNOWN` must never clear an existing positive cache entry either. Today the `else` branch does
`tokenValidUntil.remove(userToken)` (`:907`), so a single blip also discards a validity we had already
established and paid for.

### FR-194-3 — An unknown answer does not deny playback

`tvTokenForClient` returns the paired token on `UNKNOWN`.

The reasoning is the security fix's own: the danger it guards against is handing **server-admin**
credentials to a device whose own token went stale. On `UNKNOWN` we hand the device *its own* token
back — the same one it already holds. No privilege is escalated, and no token crosses a boundary it
had not already crossed. If the token turns out to be genuinely dead, the downstream Jellyfin call
fails on its own and the client sees a real error, which is the correct outcome and one request later
than today rather than ten minutes earlier.

`tvToken`'s server-token fallback on `UNKNOWN` is likewise unchanged in effect — an unreachable
Jellyfin fails either token equally, so the fallback is a no-op in this case rather than a risk.

### FR-194-4 — The log line says what happened

Replace the fabricated status code. Three distinct messages, each naming its real cause:

- `REJECTED` → `TV: Jellyfin rejected this device's paired token (HTTP {code}) for user {id} — negative-cached {n}min; re-pair the device`
- `UNKNOWN`, threw → `TV: could not verify paired token for user {id} — {exception message}; not cached, will retry`
- `UNKNOWN`, non-2xx → `TV: could not verify paired token for user {id} — Jellyfin returned HTTP {code}; not cached, will retry`

The `UNKNOWN` messages must not be suppressed by `tokenRejectionLogged`. That set exists to stop a
genuinely-dead token from flooding the log every 5 minutes; a repeated `UNKNOWN` is a live signal that
Jellyfin is flapping and is exactly what an operator needs to see. Rate-limit it separately if volume
becomes a problem — do not silence it.

### FR-194-5 — One retry before believing a rejection

A `REJECTED` verdict costs the household ten minutes of playback, so it should not rest on a single
round trip. Before negative-caching, re-probe once after a short delay (~500 ms). Only a second
consecutive `REJECTED` writes the negative cache. `UNKNOWN` on the retry leaves the state unwritten
per FR-194-2.

This is cheap — it runs only on the already-rare rejection path, never on the hot path, which is
served by the positive cache.

### FR-194-6 — A stale pooled connection is retried, not surfaced

Independent of the token path, and the most likely proximate trigger: the first outbound request after
a long idle period can fail on a pooled connection the far end has already closed. Confirm whether the
shared `OutboundHttp` Curl client (`OutboundHttp.kt:177`) retries an idempotent GET that fails at the
connection layer, and if it does not, add a single such retry.

This is scoped to connection-establishment/reset failures on idempotent requests only. It must not
retry a request that reached the server and got an answer, and it must not stack with FR-194-5 into
four round trips — FR-194-5 counts *verdicts*, not requests.

## Non-goals

- No weakening of the 2026-08-02 H2 security fix. `tvTokenForClient` still never falls back to the
  server token. FR-194-3 changes only what happens when validity is *unknown*, never when it is
  *rejected*.
- No change to `TOKEN_VALID_TTL_MS` or `TOKEN_NEGATIVE_TTL_MS`. Ten minutes is a reasonable interval
  for a genuinely dead token; the bug is what gets classified into it.
- No re-pairing flow, no token refresh, no change to how tokens are issued at pairing.
- No change to the 19 `tvToken` call sites.
- Nothing about the client-side spinner — that is **R237**.

## Acceptance

1. Block outbound access to Jellyfin (or point `jellyfin_url` at a black-holed address). Press Play on
   a TV: the request fails once, with a log line naming the real network error. Restore access and
   press Play again **immediately** — it plays. No ten-minute window.
2. Invalidate a device's paired token in Jellyfin for real. Press Play: HTTP 409 and the re-pair
   message, exactly as today; the log names HTTP 401; the negative cache is written after two probes.
3. Grep the production log after a week: zero occurrences of a "(401)" claim not backed by an actual
   401 response.
4. With Jellyfin briefly unreachable, browsing (home feed, artwork, detail) degrades exactly as it does
   today — this phase must not change the `tvToken` fallback's behaviour.
5. A transient failure while one device is idle does not affect a *second* device of the same user
   that presses Play during the same window.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt:283-292` — `isTokenValid`, the
  `runCatching{}.getOrDefault(false)` that fuses the three outcomes.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt:39-49` — the two caches and their
  TTLs (5 min positive, 10 min negative).
- `…/PlaybackService.kt:890-919` — `isPairedTokenValid`: the fast paths, the `else` branch that both
  negative-caches and clears the positive entry, and the fabricated "(401)" log line at `:911-916`.
- `…/PlaybackService.kt:921-922` — `tvToken` (server-token fallback, 19 call sites).
- `…/PlaybackService.kt:924-936` — `tvTokenForClient` and the 2026-08-02 H2 doc comment explaining why
  it must not fall back.
- `…/PlaybackService.kt:347-348`, `:775-776` — the two throw sites.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/Server.kt:208-210` — 409 mapping.
- `src/linuxX64Main/kotlin/dev/jellystructure/OutboundHttp.kt:177-183` — the shared Curl client and its
  timeouts.
- Production evidence: `docker logs jellystructure` 2026-09-06T03:55:18Z and T05:51:55Z;
  `playback_qoe` rows for device `52ff71d0…` at 07:52:23/27/33 local.

## Open questions

1. **Was it really a stale pooled connection?** The circumstantial case is strong (20 minutes idle,
   external HTTPS, first call after the quiet period) but the exception was swallowed, so it is not
   proven. FR-194-1's logging makes the *next* occurrence self-diagnosing; consider whether it is worth
   reproducing deliberately (idle the client, sever the connection at the far end, call) before
   committing to FR-194-6's retry.
2. **Should `isTokenNegativeCached` (`:938-942`) distinguish `UNKNOWN`?** It backs Phase 110's device
   health panel, which currently reads "re-pair this user". Under this phase `UNKNOWN` never reaches
   the cache, so the panel silently becomes more accurate — but a "Jellyfin unreachable" state might be
   worth surfacing there in its own right rather than as an absence.
3. **The caches are process-global `HashMap`s keyed on the token string**, shared across every device
   of a user. That is what widened a one-device blip into a household-wide outage. Correct as designed
   (the token *is* the unit of validity), but worth confirming that is still the intent now that
   Phase 141 gives each `(device, user)` its own identity.
4. The 07:51:55 check ran ~5 s *before* the TV's WebSocket connected, so something other than the play
   request triggered it. Worth identifying which route probes the token on device wake-up — if it is a
   cheap background call, it is deciding playback availability for the whole household as a side effect.

## Amendment (2026-10-13) — FR-194-6's retry was never the narrow one its own comment describes (DEB-914)

FR-194-6 says, and `OutboundHttp.kt`'s comment above the plugin repeats, that the retry is *"idempotent
methods only, and only when the call threw — a request that reached the server and got an answer is never
retried, so Phase 183's 429/AIMD pacing and the 503 + `Retry-After` contract are untouched."*

**That is not what is installed, and has not been since 2026-09-06.** Every outbound 5xx answer, on every
method, is re-sent once, 200 ms later.

### What the mechanism actually is

Read out of `ktor-client-core` 3.6.0 (`HttpRequestRetry.kt`, sources jar, verified 2026-10-13), not
inferred:

- `HttpRequestRetryConfig`'s own `init` block is `retryOnExceptionOrServerErrors(3); exponentialDelay()`
  (`:75-78`). `retryOnExceptionOrServerErrors` sets **both** predicates — `shouldRetry`, via
  `retryOnServerErrors` → `retryIf { _, response -> response.status.value in 500..599 }` (`:155-159`),
  and `shouldRetryOnException`.
- `OutboundHttp`'s block replaces `shouldRetryOnException` (through `retryOnExceptionIf`) and lowers
  `maxRetries` to 1. It never touches `shouldRetry`, so the default survives at the lowered count.
- `IDEMPOTENT_METHODS` is therefore applied to the exception path only. The response path — the one the
  comment claims does not exist — admits POST, PUT, PATCH and DELETE.

Measured, not read: a stand-in sidecar answering `503` to `/measure` receives **two POSTs per
`AvSyncService.check()`**, 200 ms apart. `AvSyncBusyGiveUpTest.a sidecar that is never free refuses the
measurement and stores nothing` asserts that 2 today, with this issue named in the comment, so the fix
shows up as a test failure rather than as a silent halving of the sidecar's traffic.

### What it costs

- **Non-idempotent writes are replayed.** `IDEMPOTENT_METHODS` exists precisely to stop a POST being
  re-sent; it is not consulted on the path that re-sends it. A POST to Jellyfin or to an *arr that does
  its work and *then* answers 500 gets a second one.
- **The 503 + `Retry-After` contract is inverted.** A 503 means *come back in n seconds*; we come back in
  200 ms and only then honour the wait at the caller's level. Phase 317's busy contract (FR-317-29) and
  Phase 183's pacing are the two places that contract was promised.
- Phase 183's 429/AIMD half of the claim **is** true — 429 is not 5xx, so `shouldRetry` never fires on it.

### FR-194-7 — The shared client retries a thrown connection failure and nothing else

The retry becomes what FR-194-6 was specified, reviewed and recorded as. No new capability.

`install(HttpRequestRetry)` clears **both** inherited predicates before stating its own, so a future ktor
upgrade that changes the `init` default cannot widen our policy again:

```kotlin
install(HttpRequestRetry) {
    noRetry()                       // ktor's own init installed retryOnExceptionOrServerErrors(3)
    maxRetries = 1                  // AFTER noRetry(), which sets maxRetries = 0
    retryOnExceptionIf { request, cause -> request.method in IDEMPOTENT_METHODS && cause.isConnectionLayerFailure() }
    delayMillis { 200 }
}
```

**The ordering is load-bearing.** `noRetry()` is `maxRetries = 0; shouldRetry = false; shouldRetryOnException
= false` (`:85-89`), so `maxRetries = 1` must come after it or the connection-layer retry FR-194-6 was built
for silently disappears too — trading one wrong behaviour for another. `retryIf { _, _ -> false }` beside
the existing lines is an acceptable alternative with the same effect and no ordering trap; the explicit
`noRetry()` is preferred because it names the intent and survives a reader who does not know the default.

The comment above the plugin keeps its explanation of *why* the connection-layer retry exists and gains one
sentence on why the clear is there. A comment that describes an invariant the code does not hold is the
actual defect here; both halves get fixed together.

**A 5xx is not retried by the shared client for anyone.** Where a caller genuinely wants one, it asks for it
at the call site, where the method, the idempotency and the `Retry-After` are all in view — Phase 183 owns
pacing, Phase 195 owns the ingest backoff. No compensating retry is to be added anywhere else as part of
this fix; if one is found to be needed, that is its own phase with its own evidence.

### Decision (Tróndur, 2026-10-13) — restore the invariant; do not keep the wider retry

DEB-914 raises, fairly, that today's doubled traffic is at least *consistent* and that something may have
come to depend on the second attempt. The invariant wins anyway, for two reasons: a replayed POST is a
correctness bug whose blast radius is every write the backend makes outbound, and nothing can be said to
depend deliberately on a retry that no spec, comment or review ever described. Anything that turns out to
depend on it accidentally is a caller that needs an explicit retry, and the test suite is where that should
surface, not production.

This is not viewer-visible and does not reach the constitution, so it is not an owner call. It does change
behaviour on every outbound call the backend makes, so it is **not** a drive-by inside another phase's
commit: it lands on its own, with its own tests, and FR-317-29's busy counting is checked in the same pass.

### Tests (added)

- The shared client's policy, asserted directly against a stand-in server, four cases: a **GET** answered
  `500` is sent **once**; a **POST** answered `503` is sent **once**; a **GET** that throws a
  connection-layer failure is sent **twice**; a **POST** that throws the same failure is sent **once**.
  The third is FR-194-6's reason for existing and must not regress with the rest.
- `AvSyncBusyGiveUpTest.a sidecar that is never free refuses the measurement and stores nothing` flips its
  assertion from `2` to `1` and drops the DEB-914 comment. The lane's own arithmetic is unchanged — one
  `AvSyncBusy` per `check()` either way — which is the point: the only thing that changes is how much
  traffic the sidecar sees.
- No test asserts the ktor default. If a later ktor version drops `retryOnExceptionOrServerErrors(3)` from
  `init`, `noRetry()` is still correct and these four cases still pass.

### Acceptance (added)

6. A stand-in server answering `500` to a GET and `503` to a POST receives exactly one request of each per
   call. A stand-in that refuses the connection receives two attempts for a GET and one for a POST.
7. `AvSyncBusyGiveUpTest` is green with `1`, and the 317 lane's give-up count is unchanged — thirty busy
   answers still take thirty waits of `retry_after_s`, not fifteen.
8. `linuxX64Test` green as a whole; no other test needed a number changed. A test elsewhere that *did*
   silently depend on the doubled traffic is a finding to report on DEB-914, not to paper over.
