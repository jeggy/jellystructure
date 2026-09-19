# Phase 241 — The mock Jellyfin refuses what the real one refuses

## Status

`Planned` — written 2026-09-18, **dev-reviewed 2026-09-19 against `main` `dcb97f2c`** (see §Dev review
at the foot: acceptance 2 needs 238's FR-238-3 health signal, so the build order is **238 → 241**), not
built. Pair: **240** (the live guard), **238** (the regression neither of them caught).

## What is wrong

`tests/mock-jellyfin/server.js` already states this phase's principle, in a comment it wrote for
phase 224:

> Refuse what the real server refuses. […] A lenient mock is how that shipped.

It applies that rule to exactly one route. `POST /Users/AuthenticateByName` validates the
`Authorization` header's four components and the credentials (`server.js:74-95`). Every other route
in the file — `/Library/VirtualFolders`, `/Items`, `/Library/Refresh`, `/Items/{id}/Refresh`,
`/Users/Me`, `/LiveTv/Info`, `/LiveTv/Channels` — answers without looking at a credential at all.

The WebSocket is worse than lenient. The upgrade handler (`server.js:136-148`) computes the
`Sec-WebSocket-Accept` hash and returns 101 to **any** handshake, with no token check of any kind:

```js
server.on("upgrade", (req, socket) => {
  const key = req.headers["sec-websocket-key"];
  …
  socket.write("HTTP/1.1 101 Switching Protocols\r\n" + …);
});
```

So the e2e suite could not have caught phase **238**'s regression, in which the real server started
answering 403 to the exact handshake the product sends. The mock would have returned 101 to it
forever. Its own comment still describes `JellyfinLibraryListener`, which phase 181 deleted outright
after finding it had never delivered an event.

There is a second, smaller drift: the auth check falls back to `x-emby-authorization`
(`server.js:80`). On 12.1 that header is rejected with a 401, measured 2026-09-18. The mock accepts a
credential form the real server no longer does.

A mock is allowed to be small. It is not allowed to be permissive in the direction that hides a real
failure, because then a green suite is evidence of nothing.

## Requirements

**FR-241-1 — The upgrade handler validates a credential.** `/socket` returns 101 only when the
request carries a valid token in the form phase 238 ships, and 403 otherwise, matching the real
server's own response. The mock must distinguish "no credential", "wrong credential" and "valid
credential" — a handler that accepts anything non-empty reintroduces the same blindness in a smaller
form.

**FR-241-2 — Data routes require a token.** Every mocked route other than `AuthenticateByName` and
`/System/Info/Public` returns 401 without a valid `Authorization: MediaBrowser … Token="…"` header.
This is one shared guard at the top of the handler, not a check per route.

**FR-241-3 — Only the header form the current server accepts.** Drop the `x-emby-authorization`
fallback. The mock accepts `Authorization: MediaBrowser …` and nothing else, because that is the only
form 12.1 authenticates.

**FR-241-4 — Anonymous routes stay anonymous, and say why.** The media and image routes are
genuinely anonymous on 12.1 — verified with no credential at all, returning 206 `video/mp4` and 200
respectively. If the mock grows any of them, they must be exempt from FR-241-2 with the measurement
recorded inline, so a future reader does not "fix" the exemption and diverge from the real server in
the other direction.

**FR-241-5 — The stale comment goes.** Replace the `JellyfinLibraryListener` note with what actually
opens that socket today: `JellyfinSessionBridge`, phase 110, one per connected TV.

**FR-241-6 — The mock declares its target.** A constant at the top naming the Jellyfin major version
the mock's behaviour was verified against, and the date. When the real server moves, this is the
marker that says the mock has not.

## Non-goals

- Mocking Jellyfin's full authorization model. Elevation, per-user policy and device-scoped tokens
  stay out; the suite does not exercise them.
- Making the mock reject the *shapes* phase 240's live guard covers. That guard runs against a real
  server for exactly this reason: a mock can only ever encode what someone already knew.
- Adding new mocked routes. This phase tightens what exists.

## Acceptance

1. The e2e suite passes with the product sending its real credential.
2. Reverting phase 238's fix makes an e2e test fail, rather than pass. This is the whole point of the
   phase and is the acceptance test that matters.
3. A request to any mocked data route with no `Authorization` header returns 401.
4. A `/socket` handshake with no token, or a wrong one, returns 403.
5. `x-emby-authorization` no longer authenticates anything.

## Open questions

1. Does any existing e2e test rely on the mock's current permissiveness — a fixture that never sets a
   token because it never had to? Those will surface as failures on the first run and each is either
   a fixture fix or a genuine product gap; they should be triaged rather than worked around by
   loosening FR-241-2.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Every claim checks out against `tests/mock-jellyfin/server.js` (148 lines): one real refusal on
`AuthenticateByName` (`:74-95`), seven data routes with no credential check at all, an upgrade handler
that 101s anything (`:136-148`), the `x-emby-authorization` fallback (`:80`), and the stale
`JellyfinLibraryListener` comment (`:133-135`). Nothing in the product sends `x-emby-authorization`, so
FR-241-3 is a safe deletion. **The phase's own headline acceptance does not yet work, for a reason worth
fixing in the spec rather than discovering during the build.**

1. **Acceptance 2 needs 238's health signal, not just 238's fix.** The good news first: the bridge *is*
   exercised by the suite. `screens.spec.ts:94` and `:104` open real `/api/tv/events` sockets for a
   screen and a phone, and `Server.kt:636` starts a `JellyfinSessionBridge` for each, so a tightened
   upgrade handler really would refuse the handshake the product makes. But **a failing bridge is
   silent** — that is phase 238's entire diagnosis — so tightening the mock produces a broken bridge and
   a **green suite**, which is this phase's own failure mode reproduced one level up. Acceptance 2
   ("reverting 238's fix makes an e2e test fail") is therefore unsatisfiable until something asserts on
   bridge state, and the thing that can is **238's FR-238-3**. Add the missing half explicitly: an e2e
   test connects a screen device and then asserts the bridge reads connected — `/api/health/full`'s
   `session_bridges` block under 238's review, or the counts in `/api/health` — and reverting 238's fix
   flips it. **Build order: 238 including FR-238-3, then 241.** Say so in the Status line, because
   without it this phase can be built in full and still prove nothing.
2. **Open question 1 answers itself from the fixture, and the answer is no.** `config-test/config.toml:3`
   pre-sets `jellyfin_token = "mock-access-token"` — the exact string `AuthenticateByName` returns
   (`server.js:89`) — so every backend call to the mock already carries a valid credential and FR-241-2's
   shared guard will not break a single fixture. The only calls that could regress are ones made *before*
   a token exists, and both existing candidates are safe: `LiveTvService.sync()`'s startup pair
   short-circuits on a blank token before issuing a request (`LiveTvService.kt:343`), and
   `testConnection`'s route is not mocked at all (item 3). Record this rather than leaving it to the
   first run.
3. **FR-241-2 exempts a route the mock does not have — and it belongs in FR-241-4, not FR-241-2.**
   `/System/Info/Public` is not in `server.js`; it falls through to the 404 default. The product does call
   it (`JellyfinClient.kt:316`, `testConnection`, the Settings → Connections *Test connection* button), so
   adding it is worth two lines and makes that button exercisable in e2e for the first time. But when it
   is added it is **anonymous by measurement, not exempt by convenience**: the product's own comment at
   `JellyfinClient.kt:324` records that it "would 200 even for an invalid token", which is precisely
   FR-241-4's category. Keeping the distinction matters — exempt-because-measured survives a reader who
   asks why, exempt-because-bootstrap does not.
4. **FR-241-6 would certify an unverified behaviour on the day it lands.** The mock's one real refusal was
   probed against **10.11.11** and says so inline (`server.js:75-78`: the 400 when the sign-in header
   lacks `Client`/`Device`/`DeviceId`/`Version`, found on production 2026-09-17). Phase 243 declares a
   **12.0** floor. Stamping a constant that reads 12.x without re-measuring that 400 on 12.x is the
   version marker asserting something nobody checked — the same shape as the `listener_connected = true`
   that concealed 181's dead listener. Re-probe it as part of this phase; it is one request, and if 12.1
   answers differently the mock's only genuine assertion is wrong today.
5. **FR-241-1 is implementable exactly as written, and the details are small.** Node's `upgrade` handler
   receives `req.headers`, so the `Authorization` check is available there; the valid token is the same
   `mock-access-token` constant; 238 keeps `deviceId` as a query parameter, which the mock can ignore;
   and the real server's answer to a bad or absent credential on `/socket` is **403** (238's measured
   table), which FR-241-1 already matches. The three-way distinction FR-241-1 insists on — absent, wrong,
   valid — is the right shape and costs nothing here.
6. **FR-241-4 is a no-op today, correctly.** The mock carries no media or image routes, so the carve-out
   has nothing to apply to yet. Worth keeping as written: it is the note that stops someone later adding
   `/Videos/{id}/stream` behind the FR-241-2 guard and quietly making the mock *stricter* than the real
   server, which fails in the opposite direction and is just as capable of hiding a regression.

**One consequence to expect in CI.** Once FR-241-1 lands and before 238 does, both device sockets in
`screens.spec.ts` will start bridge loops that fail on every run, each holding one of
`MAX_BRIDGE_CONNECTIONS`'s sixteen permits and retrying to the 60 s cap for the life of the process
(`JellyfinSessionBridge.kt:34`, `:76`, `:128`). Harmless at this scale, but it is log noise that arrives
with this phase and leaves with 238 — another reason to land 238 first.
