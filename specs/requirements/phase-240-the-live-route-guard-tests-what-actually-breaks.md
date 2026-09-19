# Phase 240 — The live route guard tests what actually breaks, and replaces the OpenAPI document

## Status

`Planned` — written 2026-09-18 after the 12.1 upgrade audit found that the one test built to catch
this class of breakage is itself broken on 12.x, **dev-reviewed 2026-09-19 against `main` `dcb97f2c`**
(see §Dev review at the foot: FR-240-3's existence probe should use a method the route does not
implement, so it can never execute one), not built. Pair: **241** (the mock
side of the same gap).

## What is wrong

Three things, all discovered by the 2026-09-18 audit.

**1. The guard test cannot authenticate.** `JellyfinLiveRouteGuardTest.kt:50` sends its credential as
`X-Emby-Token`. Measured against 12.1:

| Credential form | `/Items` |
|---|---|
| `Authorization: MediaBrowser … Token="…"` | 200 |
| `X-Emby-Token` | 401 |
| `X-MediaBrowser-Token` | 401 |
| `X-Emby-Authorization` | 401 |
| `Authorization: Emby …` | 401 |

So every assertion in that test now fails for a reason that has nothing to do with the route shapes
it is asserting on. Phase 208 wrote it precisely so that "a route shape silently 400/404ing" would be
caught on the day it happened. Pointed at a 12.x server it reports a wall of failures; pointed at
nothing, as in CI, it returns early and reports nothing. Neither outcome tells anyone anything.

**2. It does not cover the thing that actually broke.** The guard asserts on the seven-caller
`/Items` family, `/Shows/NextUp` and three item-specific shapes. The one real regression the upgrade
produced was the `/socket` handshake (phase **238**), which the guard does not touch, because it only
tests `get(...)`.

**3. The document this project verifies against is gone.** The working rule — probe the live OpenAPI
before writing code against a route, the lesson of phase 163's `POST /MediaSegments` → 405 and
FR-187-1's actively-wrong image body — depends on `GET /api-docs/openapi.json`. On 12.1 that returns
**500 Error processing request**. Every alternative path tried returns 404. The verification method
this codebase's conventions are built on has no document to read.

What *did* work during the audit is a different technique: walk every `@SerialName` in `Models.kt`
against a live payload and report any field the server no longer sends. That found nothing on 12.1
across all 36 serializable models, which is a real and useful result — and it is the technique that
would have caught phase 207's missing `userId` and phase 187's wrong image body. It does not need
Jellyfin to publish anything.

## Requirements

**FR-240-1 — The guard authenticates the way the product does.** Replace `X-Emby-Token` with the same
`Authorization: MediaBrowser …` header `jellyfinAuth` builds. The test must not invent its own
credential format, because a test that authenticates differently from the product can pass while the
product cannot log in.

**FR-240-2 — The guard covers the socket.** Add an assertion that the `/socket` handshake returns 101
using the exact credential form phase 238 ships. This is the one route in the codebase where auth
failure is not silently swallowed by `bodyOrNull`, and it is the one that broke.

**FR-240-3 — The guard covers the mutating routes by existence.** For each route the product POSTs or
DELETEs — `Sessions/Playing`, `/Progress`, `/Stopped`, `Sessions/Capabilities/Full`,
`Items/{id}/PlaybackInfo`, `Library/Refresh`, `Library/Media/Updated`, `Items/{id}/Refresh`,
`Users/Password`, `LiveStreams/Open`, `DELETE Videos/ActiveEncodings` — assert that an
**intentionally invalid** token yields 401 rather than 404 or 405. A 401 proves the route exists and
authenticates without executing it. This is how the audit checked them safely and it generalises.

`POST /System/Restart` is explicitly **excluded** from this list. See FR-240-6.

**FR-240-4 — A model-field guard replaces the OpenAPI probe.** A checked-in script,
`scripts/check-jellyfin-models.sh` (or a test in the same opt-in family), that:

- parses every `@Serializable` class in `auth/Models.kt` for its `@SerialName` fields and whether
  each has a default or is nullable,
- fetches one live payload per model from a real server,
- reports every field the server does not send, separating those that would throw on parse from those
  that `ignoreUnknownKeys = true` would silently turn into a default.

The silent-default case is the dangerous one and must be reported as loudly as the throwing case.
`OutboundHttp.kt:190` sets `ignoreUnknownKeys = true` globally, so a renamed field becomes `false`,
`null` or `emptyList()` with no error anywhere — which is exactly how a metadata-ownership check
could go blind without a single log line.

**FR-240-5 — It runs against a disposable server, and it runs in CI.** The existing opt-in env vars
(`JELLYFIN_LIVE_TEST_URL/_TOKEN/_USER_ID`) stay, and the household server is never the target. Phase
231 made CI gate every publish; this guard joins it, pointed at a throwaway Jellyfin container of the
current major version. A guard that only runs when someone remembers to run it did not run for this
upgrade.

**FR-240-6 — Nothing in the test suite may restart or shut down a server.** `POST /System/Restart`,
`/System/Shutdown` and any other lifecycle route are on a written deny-list in the guard, with the
reason recorded inline. During the 2026-09-18 audit, `POST /System/Restart` sent with a deliberately
invalid token returned **204 and restarted the household's Jellyfin**, causing roughly one minute of
downtime and four failed progress writes for a viewer who was mid-playback. Every other privileged
route correctly returned 401. The existence-probe technique in FR-240-3 is safe precisely because
those routes authenticate first; this one did not, and the deny-list is what stops a future run from
rediscovering that the same way.

## Non-goals

- Testing Jellyfin's behaviour for its own sake. The guard asserts only on shapes the product
  actually calls.
- Reinstating an OpenAPI-based workflow. If `/api-docs/openapi.json` starts working again it is a
  convenience, not the mechanism this project relies on.
- Response *semantics*. The guard checks shape and status, not whether a value is correct.

## Acceptance

1. Pointed at a 12.x server with valid credentials, the guard passes in full.
2. Reverting phase 238's fix makes the guard fail on the socket assertion specifically.
3. Renaming any `@SerialName` in `Models.kt` to a value the server does not send makes the FR-240-4
   check report it, including for a field that has a default.
4. The guard runs in CI against a disposable container and its failure blocks a publish, per phase
   231.
5. The deny-list in FR-240-6 is present, and no test in the repository issues a lifecycle call.

## Open questions

1. Where does the disposable Jellyfin for CI come from — a fresh container with an empty library, or a
   seeded one? An empty library makes the item-specific assertions return early, which is the
   existing test's behaviour and weakens it. Seeding a container with one file is more work but is
   the difference between the guard covering three shapes and covering eleven.
2. Should the model-field guard also flag fields the server sends that no model reads? That finds
   capability we are ignoring rather than breakage, which is a different and lower-priority signal.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

All three diagnoses check out: `JellyfinLiveRouteGuardTest.kt:50` does send `X-Emby-Token`, the guard
only calls `get(...)` so it cannot reach `/socket`, and every route named in FR-240-3 exists in the
product. **One requirement should change technique, because as written it can repeat the outage it was
written in response to.**

1. **FR-240-3's probe executes any route that does not enforce, and the deny-list only covers the ones
   we happened to find.** The technique is "send the real method with an intentionally invalid token and
   assert 401", justified as proving existence "without executing it". That holds only for routes that
   authenticate *before* acting. FR-240-6 records the counterexample in this spec's own text: `POST
   /System/Restart` with a deliberately invalid token returned **204 and restarted the household's
   Jellyfin**. A deny-list defends against the routes someone thought to list; the next route that
   authenticates late is discovered the same way this one was.
   **Use a method the route does not implement instead.** Jellyfin is ASP.NET Core: its routing answers
   **405** when the path matches but the method does not, and **404** when the path does not exist. So
   `GET /Sessions/Playing` distinguishes existence from absence, needs no credential at all, and cannot
   mutate anything under any authentication behaviour. Two consequences worth having: FR-240-6's
   deny-list becomes defence in depth rather than the only thing standing between a future run and
   another outage, and the guard can then cover `POST /System/Restart` itself — a route the product
   genuinely calls (`JellyfinClient.kt:497`) and which the deny-list currently leaves permanently
   untested. Caveat to write in: for a path that implements both methods the GET will simply answer, so
   the assertion is **"not 404"** rather than "405", and the chosen method must be a read.
2. **FR-240-1 needs the widening 238 and 239 also need.** `jellyfinAuth` is `private`
   (`JellyfinClient.kt:1259`). `linuxX64Test` is a friend compilation of `linuxX64Main`, so `internal`
   is visible from the test — widen it once and this requirement is satisfied by calling it. If it stays
   private, FR-240-1's own rule ("the test must not invent its own credential format") cannot be met,
   because the test would have to re-implement the header string, which is precisely the drift the rule
   forbids. Three phases now want this one-word change; whichever lands first should make it.
3. **FR-240-2 needs a client the test does not have.** The guard's `get(...)` uses
   `OutboundHttp.client` (`OutboundHttp.kt:188`), which installs ContentNegotiation, HttpTimeout and
   HttpRequestRetry but **not** `WebSockets`. The socket assertion therefore brings its own
   `HttpClient(Curl) { install(WebSockets) }`, mirroring `JellyfinSessionBridge.kt:53`. Worth saying,
   because "add an assertion that the handshake returns 101" reads like one line and is not.
   *Checked and cleared:* the shared client's retry is connection-layer only and only for GET/HEAD/OPTIONS
   (`OutboundHttp.kt:205-211`), so it cannot silently retry a status the guard is asserting on — FR-240-3
   does not need a separate client.
4. **FR-240-4's requirement is right; its stated mechanism is not.** `ignoreUnknownKeys = true`
   (`OutboundHttp.kt:190`) governs keys the **server sends that the model does not declare**. A field the
   server *stops sending* is defaulted because the property **has a default or is nullable** — that
   happens with or without the flag. The two only combine on a **rename**: the new key is ignored as
   unknown (which without the flag would throw) while the old key is absent, so the default applies. So
   the guard's two buckets are discriminated by exactly what FR-240-4 already parses — default-or-nullable
   versus required — and the sentence should say `ignoreUnknownKeys` is what hides a *rename*, not an
   absence. Also a count correction: `auth/Models.kt` carries **38** `@Serializable` declarations, not 36.
5. **FR-240-5 is closer than it reads, and its remaining cost answers open question 1.** `ci.yml:65`
   already runs `./gradlew linuxX64Test --no-daemon`, so the guard is *already* in CI and already returns
   early — what is missing is only a server and three env vars. But a fresh `jellyfin/jellyfin` container
   issues no token until its **startup wizard** is completed (`/Startup/Configuration`, `/Startup/User`,
   `/Startup/Complete`), so "a fresh container with an empty library" is not the cheap option it sounds
   like: the wizard has to be scripted either way, and once it is, adding one small media file and
   triggering a scan is a few more lines. **OQ1 therefore answers itself — seed it.** Eight of the eleven
   shapes in the existing guard are item-specific and `return@runBlocking` without an item
   (`JellyfinLiveRouteGuardTest.kt:71`), so an unseeded container buys a guard that tests roughly a third
   of what it claims, which is the weakness this phase exists to remove. Put the provisioning in
   `scripts/` beside the other check scripts so it is runnable by hand as well as by CI.
6. **Open question 2 — no, and for a sharper reason than priority.** Flagging fields the server sends
   that no model reads would fire on nearly every response: Jellyfin returns large objects and the
   product deliberately models a thin slice of each. The output would be thousands of lines on the first
   run and the check would be muted within a week, taking the useful half of the guard's credibility with
   it. If that capability sweep is ever wanted it is a one-off report, not something that gates a publish.

**Acceptance.** Sound, with two notes. Acceptance 2 requires 238 to have landed, so the build order is
**238 → 240** and this acceptance encodes that dependency — say so. Acceptance 5 ("no test in the
repository issues a lifecycle call") becomes much easier to hold under item 1, since the probe stops
issuing the real method at all.
