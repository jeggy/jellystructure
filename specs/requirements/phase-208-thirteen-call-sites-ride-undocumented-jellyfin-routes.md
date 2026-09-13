# Phase 208 — thirteen call sites ride Jellyfin routes that 10.11.11 no longer documents

> Found 2026-09-13 by an audit that was looking for something else. Phase 207's closing open question
> asked *"how many other Jellyfin calls have never worked?"* — the answer is **one, and 207 is it.** But
> the same audit found thirteen call sites that work today on routes Jellyfin **no longer publishes**,
> each with a documented replacement sitting beside it.
>
> Nothing is broken. That is the whole problem: there is no signal, and the failure mode is a Jellyfin
> upgrade taking out Continue Watching, the media detail page, mark-as-played, favourites and Live TV
> playback at the same time.

## Status
Planned, written 2026-09-13. Audit-authored, not dev-reviewed, not built. Backend-only, mechanical,
no behaviour change intended — every route below has a documented equivalent with the same semantics.

Sibling of Phase 207 by discovery, not by cause: 207 is a call that has never worked, this is thirteen
that work but are undated cheques. **Deliberately separate phases** — 207 is a one-parameter bug fix
with a measured 21 s payoff, this is a mechanical sweep across the hottest paths in the product and
wants its own verification pass.

## The finding

### What was audited, and how

Every HTTP-calling method in `JellyfinClient.kt` (55 of them) was enumerated, then split:

- **25 read-only methods were executed live** against this household's Jellyfin **10.11.11** with the
  real admin token. **24 returned `200`. The one failure was `getItemMediaStreams` → `400`, which is
  Phase 207.**
- **Mutating methods were deliberately not fired.** This is a live household with real users; probing
  `markPlayed`, `updateUserPassword`, `deleteUserImage`, `restartServer` or `triggerLibraryRefresh`
  would change real state. Those were cross-referenced against Jellyfin's own OpenAPI document
  (`/api-docs/openapi.json` — 392 paths, 463 operations) and nothing more.

### The thirteen

| our route | callers | live | documented in 10.11.11 |
|---|---|---|---|
| `GET /Users/{userId}/Items` | `getResumeItems`, `getRecentlyPlayed`, `getResumeItemsAll`, `getRecentlyPlayedAll`, `getRecentlyTouched`, `getUserDataBulk`, `getFavoriteItemIds` — **7** | **200** | `GET /Items` (+`userId`), `GET /UserItems/Resume` |
| `GET /Users/{userId}/Items/{itemId}` | `getItemDetail` | **200** | `GET /Items/{itemId}` (+`userId`) |
| `POST /Users/{userId}/PlayedItems/{itemId}` | `markPlayed` | not probed | `POST /UserPlayedItems/{itemId}` |
| `DELETE /Users/{userId}/PlayedItems/{itemId}` | `markUnplayed` | not probed | `DELETE /UserPlayedItems/{itemId}` |
| `POST /Users/{userId}/FavoriteItems/{itemId}` | `markFavorite` | not probed | `POST /UserFavoriteItems/{itemId}` |
| `DELETE /Users/{userId}/FavoriteItems/{itemId}` | `unmarkFavorite` | not probed | `DELETE /UserFavoriteItems/{itemId}` |
| `POST /LiveTv/LiveStreams/Open` | `openLiveStream` | not probed | `POST /LiveStreams/Open` |

Seven of the thirteen are the `/Users/{userId}/Items` list route — which is **every Jellyfin fetch
Continue Watching is built from** (R219's four sources), plus the playstate hydration every tile's ✓
depends on, plus favourites.

The last row is the one that shows this is drift rather than a considered choice: **`closeLiveStream`
already uses the documented `POST /LiveStreams/Close`**, while its own partner `openLiveStream` uses the
undocumented `/LiveTv/LiveStreams/Open`. One pair, two conventions, written at the same time.

### Why "undocumented but working" is a real risk and not pedantry

The replacements are not guesses — `UserPlayedItems`, `UserFavoriteItems`, `UserItems/Resume` and
`UserItems/{itemId}/UserData` all exist in 10.11.11's document *alongside* the legacy forms being
absent from it. A server that ships the new shape, documents only the new shape, and still answers the
old one is a server mid-migration. That is what a removal looks like one version before it happens.

**Not verified, and this phase should not claim it:** whether Jellyfin has actually announced these
removals, or on what release. Checking the upstream changelog is part of the work (FR-208-1), not an
assumption to build on.

The blast radius if a future upgrade drops them, all at once and with no prior warning:

- Continue Watching (7 callers) — the row disappears entirely
- the Ravilo media detail page (`getItemDetail`)
- watched ✓ / resume slivers on every tile (`getUserDataBulk`)
- mark-as-played write-through, in both directions
- favourites / My List
- Live TV playback (`openLiveStream`)

### The methodological finding, which is the durable part

The OpenAPI cross-reference on its own reported **15 absent routes**. Of those:

- **13** were real-but-working (this phase)
- **2** were artefacts of the matcher, not of the code — `installPlugin` (its `{name}` segment is
  appended a line later, so a naive read truncated the path) and `warmSubtitleExtraction` (whose last
  segment is the partial template `Stream.{routeFormat}`, which a segment comparator reads as a literal)
- **0** were the actual bug

`getItemMediaStreams` is **declared in the document and returns 400 in practice.** So schema presence
and real behaviour are *orthogonal signals*, and neither substitutes for the other:

| | declared | absent |
|---|---|---|
| **works live** | the normal case | the thirteen — *upgrade risk* |
| **fails live** | `getItemMediaStreams` — *a live bug* | Phase 163's `POST /MediaSegments` (405) |

This is the third instance of the same lesson, and each cost a phase: Phase 163's `POST /MediaSegments`
→ 405, Phase 187's discovery that **the OpenAPI document is actively wrong about the image body** (raw
binary → 500, base64-with-MIME → 204), and now 207. Reading the schema is not probing; probing one
route is not auditing the rest.

One more general Jellyfin behaviour worth recording, learned here: **a route that needs a user context
answers `400`, not `401` or `403`, when you don't name one.** Both `GET /Items/{itemId}` and
`GET /Users/Me` do this with a server token. A 400 reads as "malformed request" and sends you to inspect
your query string instead of your auth model, which is exactly how 207 survived.

## Requirements

**FR-208-1 — establish whether these routes are actually deprecated, before migrating anything.** Read
Jellyfin's own changelog/API notes for the `/Users/{userId}/…` → `/UserPlayedItems`,
`/UserFavoriteItems`, `/UserItems` migration and record the version that introduced the new shapes and
any announced removal. If the legacy routes are simply undocumented-but-supported with no removal
planned, this phase is downgraded to FR-208-5's guard alone and the migration is not worth the risk.
**This is the gate; the rest of the phase is conditional on it.**

**FR-208-2 — migrate the read routes first, and verify each against real data.** The seven
`/Users/{userId}/Items` callers and `getItemDetail` are read-only and live-probeable, so each migration
can be proven by comparing responses before and after on the same ids. `getRecentlyTouched`'s
whole-library `DatePlayed` sort is the one to watch: `GET /Items` with a `userId` parameter must produce
the *same* membership and ordering, or R219's Continue Watching rules change silently — which is a
correctness regression dressed as a refactor.

**FR-208-3 — migrate the four write routes with the same care, and test them somewhere that is not the
household.** `markPlayed`/`markUnplayed`/`markFavorite`/`unmarkFavorite` cannot be probed against live
users. The `jellyfin-demo` container already running on this host is the right target. R142's
write-through and R185's played/position desync history both depend on these, so a silent no-op here
would show up as Continue Watching resurrecting finished titles — the exact bug R185 fixed.

**FR-208-4 — fix the `openLiveStream`/`closeLiveStream` inconsistency.** Close already uses the
documented form; Open should match it. Smallest item here and the only one that is unambiguously just
tidying.

**FR-208-5 — a guard so the next one of these is found by CI, not by an outage.** A single test or
script that exercises each `JellyfinClient` route shape against a reachable Jellyfin and fails on a
non-2xx, runnable against the demo container. Every one of the three historical instances (163, 187,
207) would have been caught on the day it was written. This is the requirement that makes the phase
worth more than the thirteen edits — **and it should be built even if FR-208-1 downgrades everything
else.**

**FR-208-6 — correct `getItem`'s comment.** It claims `/Items/{id}` 400s *because of the server token*
and that `/Users/{userId}/Items/{id}` is what it "expects". Both halves are wrong: the token is fine
(a `userId` parameter is what was missing), and the route it recommends is one of the thirteen. The
comment is load-bearing — it is what Phase 179 read before writing the bug in 207. Phase 207 also
requires this; whichever lands first does it.

## Out of scope

- **Phase 207's `userId` fix.** Its own phase, already specified, and a different kind of change.
- **Changing what any of these calls do.** Pure route migration; every response must be equivalent.
  Where it cannot be (FR-208-2's ordering question) the phase stops and reports rather than adapting
  the caller to a new shape's behaviour.
- **Auditing the *admin*-side Jellyfin surface** (`JellyfinSessionBridge`'s WebSocket, the image proxy
  in `RaviloArtworkService`). Same class of risk, different files; this phase is `JellyfinClient.kt`.
- **Pinning or asserting a Jellyfin version.** A real option for removing this class of risk entirely,
  and a household-infrastructure decision rather than a code one.
- **Adding routes Jellyfin documents but we do not use.** `GET /UserItems/{itemId}/UserData` may be a
  better fit than `getUserDataBulk`'s `Ids=` trick, but changing the *shape* of the playstate fetch is
  Phase 205's territory and not a migration.

## Open questions

1. **Does `GET /Items` with a `userId` parameter return identical membership to
   `GET /Users/{userId}/Items` for every filter combination we use?** `Filters=IsResumable`,
   `Filters=IsPlayed`, `Filters=IsFavorite`, `SortBy=DatePlayed` and the bare `Ids=` lookup are five
   distinct shapes and the audit only confirmed the legacy form answers 200 — not that the replacement
   answers *the same thing*. This is the real work of FR-208-2.
2. **Is `GET /UserItems/Resume` a better fit for `getResumeItemsAll` than `GET /Items`?** It is
   purpose-built and may page differently. Note R219's standing rule that **a `Limit` is never a cap** —
   whatever replaces these must still page to `TotalRecordCount`, which is the constraint that caused
   four separate bugs before it was written down.
3. **Should Phase 205 absorb the seven Continue-Watching callers?** 205 already restructures where those
   calls are made from, so migrating the same seven twice is wasted motion — but bundling a route
   migration into a restructuring makes a regression in either impossible to attribute, which is the
   reason 204/205/206 were kept apart in the first place. Leaning: keep separate, land this first since
   it is mechanical and verifiable.
4. **Does the demo container's Jellyfin match production's version?** FR-208-3 and FR-208-5 both depend
   on it being a faithful target. If it drifts, the guard tests a server nobody runs.
