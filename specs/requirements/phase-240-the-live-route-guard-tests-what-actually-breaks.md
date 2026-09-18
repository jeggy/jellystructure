# Phase 240 — The live route guard tests what actually breaks, and replaces the OpenAPI document

## Status

`Planned` — written 2026-09-18 after the 12.1 upgrade audit found that the one test built to catch
this class of breakage is itself broken on 12.x, not dev-reviewed, not built. Pair: **241** (the mock
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
