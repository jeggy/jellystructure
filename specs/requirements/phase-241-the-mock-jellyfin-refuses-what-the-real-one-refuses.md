# Phase 241 — The mock Jellyfin refuses what the real one refuses

## Status

`Planned` — written 2026-09-18, not dev-reviewed, not built. Pair: **240** (the live guard), **238**
(the regression neither of them caught).

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
