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
✓ Built 2026-09-13 (FR-208-1/2/3/5/6; FR-208-4 corrected, not applicable — see below). Audit-authored,
not dev-reviewed, not deployed. Backend-only, mechanical, no behaviour change intended — every route
migrated has a documented equivalent with the same semantics, verified live where that was safe to do.
`compileKotlinLinuxX64`/`compileTestKotlinLinuxX64` clean; full `linuxX64Test` suite green.

**Correction found while building this phase: the count is twelve, not thirteen, and FR-208-4 describes
a bug that no longer exists.** `openLiveStream` was already fixed to call the documented
`/LiveStreams/Open` — not `/LiveTv/LiveStreams/Open` — in commit `dcc2de32`
(`fix(livetv): correct LiveStreams Open/Close URL path (was 404ing)`), **2026-07-10, more than two
months before this phase's audit.** The audit that produced this spec misread the code (or was
comparing against a since-fixed memory of it) and reported a live bug that had been dead for ten weeks.
Verified directly against the current source: `openLiveStream` already builds
`/LiveStreams/Open`, matching `closeLiveStream`'s `/LiveStreams/Close` exactly — there never was an
inconsistency to fix, once the file is actually read rather than recalled. The real count is the seven
`/Users/{userId}/Items` callers + `getItemDetail` + the four write methods = **12**, not 13. Kept as a
visible correction here rather than silently editing the title/count away, since "an audit's own finding
turned out to be wrong on inspection" is exactly the kind of thing this phase's methodology section is
about.

**FR-208-1 resolved, with primary-source evidence — not just an absent OpenAPI entry.** Jellyfin's own
current source (`jellyfin/jellyfin` on GitHub, `Jellyfin.Api/Controllers/*.cs`) marks every one of the
twelve legacy routes `[Obsolete("Kept for backwards compatibility")]` plus `[ApiExplorerSettings(IgnoreApi
= true)]` — which is *why* they're absent from the OpenAPI document: deliberately hidden, not merely
undocumented by omission. Each legacy handler is a one-line delegate to the modern one
(`MarkPlayedItemLegacy(userId, itemId, datePlayed) => MarkPlayedItem(userId, itemId, datePlayed)`, and
the same pattern for `MarkUnplayedItemLegacy`/`MarkFavoriteItemLegacy`/`UnmarkFavoriteItemLegacy`/
`GetItemsByUserIdLegacy`) — same internal code path, not merely "similar." A community thread
(community.firecore.com) independently confirms the same migration and the "deprecations are normally
marked for an entire major release cycle before removal" convention. **This phase's migration is
therefore justified, not just its FR-208-5 guard** — the gate does not downgrade.

Sibling of Phase 207 by discovery, not by cause: 207 is a call that has never worked, this is twelve
that work but are undated cheques. **Deliberately separate phases** — 207 is a one-parameter bug fix
with a measured 21 s payoff, this is a mechanical sweep across the hottest paths in the product and
wants its own verification pass.

**Build notes:**

- **FR-208-2 (read routes) — migrated and live-verified byte-for-byte identical**, not just "returns
  200": `getResumeItems`/`getResumeItemsAll` (`Filters=IsResumable`), `getRecentlyPlayed`/
  `getRecentlyPlayedAll` (`Filters=IsPlayed`), `getRecentlyTouched` (no filter, `SortBy=DatePlayed`),
  `getFavoriteItemIds` (`Filters=IsFavorite`), `getUserDataBulk` (`Ids=` bulk), and `getItemDetail`
  (single-item) — all six distinct query shapes were run against the live household Jellyfin, old path
  vs new path, same token, same moment: **identical JSON, item-for-item, in order, `TotalRecordCount`
  included.** This resolves open question 1 outright — `GET /Items?userId=` does not just work, it
  returns the *exact same* membership and ordering as `GET /Users/{userId}/Items` for every filter
  combination this codebase uses. `getNextUp`'s `/Shows/NextUp` route was never in the `/Users/{userId}/`
  family and is unaffected.
- **FR-208-3 (write routes) — migrated, deliberately NOT live-tested.** `markPlayed`/`markUnplayed`/
  `markFavorite`/`unmarkFavorite` now call `/UserPlayedItems/{itemId}?userId=`/
  `/UserFavoriteItems/{itemId}?userId=`. Firing a live write against the household to "verify" a route
  the source already proves is the same code path would mutate real watch-history/favourites data for
  no additional confidence — the risk this phase's own FR-208-3 text warns against. The `jellyfin-demo`
  container this phase names as the right target **is running on this host** (`172.28.0.43:8096`,
  reachable), but no credentials for it were available this session — its admin account exists and is
  password-protected, and guessing at a password was not attempted. Whoever has those credentials should
  run the four write calls there before this phase is considered dev-reviewed.
- **FR-208-5 — built as `JellyfinLiveRouteGuardTest`.** Bypasses `JellyfinClient`'s own methods
  deliberately (they swallow a non-2xx into an empty default, the exact ambiguity this guard exists to
  catch) and asserts on raw HTTP status for every migrated shape plus the exact URL Phase 207 fixed.
  Opt-in via three env vars (`JELLYFIN_LIVE_TEST_URL`/`_TOKEN`/`_USER_ID`) — passes as a no-op with none
  set (verified: 0 assertions run, build stays green), and was run for real against the live household
  Jellyfin during this build (read-only shapes only, same reasoning as FR-208-3) — **all shapes 2xx.**
- **FR-208-6 — already done.** Phase 207's commit corrected `getItem`'s comment; this phase adds nothing
  further to it.

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

### The twelve

**Corrected during the build pass** (see Status): the seventh row below (`openLiveStream`) does not
exist — the code already calls the documented `/LiveStreams/Open`, fixed in commit `dcc2de32` on
2026-07-10. Struck through rather than deleted, so this table still shows what the original audit
claimed and where it was wrong.

| our route | callers | live | documented in 10.11.11 |
|---|---|---|---|
| `GET /Users/{userId}/Items` | `getResumeItems`, `getRecentlyPlayed`, `getResumeItemsAll`, `getRecentlyPlayedAll`, `getRecentlyTouched`, `getUserDataBulk`, `getFavoriteItemIds` — **7** | **200**, verified byte-identical to the replacement | `GET /Items` (+`userId`), `GET /UserItems/Resume` |
| `GET /Users/{userId}/Items/{itemId}` | `getItemDetail` | **200**, verified byte-identical | `GET /Items/{itemId}` (+`userId`) |
| `POST /Users/{userId}/PlayedItems/{itemId}` | `markPlayed` | not probed (see FR-208-3) | `POST /UserPlayedItems/{itemId}` |
| `DELETE /Users/{userId}/PlayedItems/{itemId}` | `markUnplayed` | not probed (see FR-208-3) | `DELETE /UserPlayedItems/{itemId}` |
| `POST /Users/{userId}/FavoriteItems/{itemId}` | `markFavorite` | not probed (see FR-208-3) | `POST /UserFavoriteItems/{itemId}` |
| `DELETE /Users/{userId}/FavoriteItems/{itemId}` | `unmarkFavorite` | not probed (see FR-208-3) | `DELETE /UserFavoriteItems/{itemId}` |
| ~~`POST /LiveTv/LiveStreams/Open`~~ | ~~`openLiveStream`~~ | **already fixed 2026-07-10** — not part of this phase | `POST /LiveStreams/Open` |

Seven of the twelve are the `/Users/{userId}/Items` list route — which is **every Jellyfin fetch
Continue Watching is built from** (R219's four sources), plus the playstate hydration every tile's ✓
depends on, plus favourites.

The struck-through row was originally read as the one that showed this was drift rather than a
considered choice (`closeLiveStream` already documented, `openLiveStream` supposedly not) — it turned
out to be an error in the audit instead: both have used the documented form since July.

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

**FR-208-4 — fix the `openLiveStream`/`closeLiveStream` inconsistency.** ~~Close already uses the
documented form; Open should match it. Smallest item here and the only one that is unambiguously just
tidying.~~ **Withdrawn — the inconsistency does not exist.** `openLiveStream` already calls the
documented `/LiveStreams/Open`, fixed in commit `dcc2de32` on 2026-07-10, ten weeks before this phase's
audit claimed otherwise. Verified directly against the current source during this build. Nothing to fix.

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

1. ~~**Does `GET /Items` with a `userId` parameter return identical membership to
   `GET /Users/{userId}/Items` for every filter combination we use?**~~ **Answered, live, during this
   build: yes, byte-for-byte.** All six distinct query shapes this codebase uses (`Filters=IsResumable`,
   `Filters=IsPlayed`, the no-filter `SortBy=DatePlayed` sort, `Filters=IsFavorite`, the bare `Ids=`
   lookup, and the single-item detail form) were run old-path-vs-new-path against the live household
   Jellyfin and returned **identical JSON**, not just matching status codes.
2. **Is `GET /UserItems/Resume` a better fit for `getResumeItemsAll` than `GET /Items`?** Still open —
   not tested this pass, since the `Filters=IsResumable` form on `GET /Items` was already confirmed
   correct and sufficient. Worth a separate look if `getResumeItemsAll`'s paging ever becomes a concern
   in its own right, but not blocking this migration. R219's standing rule stands unchanged: **a `Limit`
   is never a cap** — whatever is used must still page to `TotalRecordCount`.
3. **Should Phase 205 absorb the seven Continue-Watching callers?** Resolved by construction, not by
   decision: Phase 205 restructured *when* these calls happen (background-refreshed, never on the
   interactive path) without touching *which URL* they call, and this phase changed the URL without
   touching the calling convention. Landing 205 first, then 208 against the resulting code, meant neither
   pass needed to know about the other's edits — confirms the "keep separate" leaning was right, and
   shows why: a regression introduced by either phase stayed attributable to it alone.
4. **Does the demo container's Jellyfin match production's version?** **Checked — no, and this matters.**
   `jellyfin-demo` (`172.28.0.43:8096`) reports **12.0.0**; the household runs **10.11.11**. That's not a
   patch gap, it's the major-version jump the `[Obsolete]` source evidence above was read from (GitHub
   `master`, i.e. 12.0-era code) — the demo container is at least a plausible proxy for *that* evidence,
   but FR-208-3's write-route testing, if run there, would be validating against a server two major
   versions ahead of the one this fix actually ships to. Not disqualifying (the legacy-route shapes are
   old and stable enough that a 10.11→12.0 gap is unlikely to have changed their behavior), but the next
   person to test the write migration should know they are not testing production's actual version, and
   should treat a pass there as "plausible," not "proven."
