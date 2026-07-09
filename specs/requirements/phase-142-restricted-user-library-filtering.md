# Phase 142 — Restricted-user catalog filtering by Jellyfin library access (FR-AUTH2)

> Ravilo currently shows **every** paired user the whole scanned catalog, ignoring Jellyfin's per-user
> library restrictions entirely. This makes jellystructure aware of each user's Jellyfin library access
> and **filters every device-facing read path** so a restricted user only ever sees (and can request/
> play) content their account is allowed. Restricted users stay **first-class** — this is filtering,
> never blocking. Builds on **Phase 141** (real per-user identity + the policy fetched at login).
> Related: R30 (facets), R83/R84 (playstate hydration), the MediaStore read paths (HomeFeed/Browse/
> Detail).

## Problem

Live Jellyfin has genuinely restricted users — `charlotte` (`EnableAllFolders=false`, 5 enabled
folders), `danjal` (2), `Test Stream` (3) — alongside unrestricted ones. But:

- `JellyfinPolicy` (`auth/Models.kt:34-38`) captures **only** `IsAdministrator` + `MaxParentalRating`.
  jellystructure never learns which libraries a user may access.
- `MediaStore` serves jellystructure's **own** scanned catalog (`getAll`/`liveItems`) with **no per-user
  ACL** — `HomeFeedService`, `BrowseService` (`/tv/browse`, `/tv/search`), and `DetailService` use
  `device.jellyfinUserId` only for per-user config/watch-state, not to gate the catalog.
- `MediaItem` has **no library association** at all, so there is currently nothing to filter on.

Net effect: a restricted user would see the entire library in Ravilo, and attempting to stream a
blocked title would 403 at Jellyfin — a broken experience. (Note: because Phase 141 gives each device a
real per-user token, without this phase the mismatch becomes *visible* — the catalog shows titles the
user's own token can't stream.)

## Current state (verified)

- `getUsers`/`authenticateByName` already return the **full** Jellyfin `Policy` object — the extra
  fields are one deserialization change away.
- `Scanner.scanItem` (`Scanner.kt:59-85`) already resolves each item's owning `LibraryMapping lib` by
  **path prefix** (`jellyfinPath`), and `LibraryMapping.jellyfinId` (`AppConfig.kt:207`) **is the
  Jellyfin library ItemId** — the exact id space Jellyfin's `EnabledFolders` uses. So item→library is
  already computed at scan time; it's just not persisted.
- `config.libraries` (auto-discovered from `/Library/VirtualFolders`) gives the full library set, so
  "all libraries" and per-path backfill are both derivable offline.

## Design

### A. Learn each user's library access
1. Extend `JellyfinPolicy` (`auth/Models.kt`) with `@SerialName("EnableAllFolders") val enableAllFolders:
   Boolean = true` and `@SerialName("EnabledFolders") val enabledFolders: List<String> = emptyList()`
   (Jellyfin library ItemIds). No `IsDisabled` field — Jellyfin blocks disabled accounts at auth;
   jellystructure never blocks anyone.
2. **Allowed-library set** for a user = `if (enableAllFolders) <all library ids> else enabledFolders`.
   The policy is already fetched at login (Phase 141); persist the resolved allowed-library ids on the
   `ravilo_device` row (new column) — or refresh lazily on a TTL like the playstate calls. Admins
   (`EnableAllFolders=true`) resolve to "all" and are unaffected.

### B. Tag items with their library
3. Add `libraryId: String? = null` to `MediaItem` (`model/Media.kt`), set from `lib.jellyfinId` inside
   `Scanner.scanItem` (pass it into `scanMovie`/`scanSeries`). Serialized like any other field; installed
   TV APKs ignore unknown fields, and older stored blobs decode with `libraryId = null`.
4. **One-time backfill** (migration / startup step): for every stored `MediaItem` with `libraryId == null`,
   re-derive it from `item.path` against `config.libraries` prefixes (the same match `Scanner` uses).
   No full re-scan required. Items whose path matches no library keep `null` (treated as visible only to
   all-folders users, or excluded — see §C default).

### C. Filter every device-facing read path
5. Add a central `MediaStore` predicate — `fun MediaItem.visibleTo(allowed: Set<String>?): Boolean` (an
   `allowed == null` / all-folders set ⇒ everything) — and thread the device's allowed set through the
   services that already receive `device`: `HomeFeedService` (home rows **and** channel/content-row
   evaluation — filter the *surfaced* items so channels/rows never leak a blocked title),
   `BrowseService` (`/tv/browse` + `/tv/search`), and `DetailService` (movie/series detail, More-Like-
   This/related, and `getPlaystate` id lists). Blocked items are dropped **before** the DTO is built, so
   Ravilo never renders them and counts/facets reflect only visible content.
6. Detail-by-id for a blocked item returns 404 (as if absent) rather than leaking metadata. Defense-in-
   depth: the user's own token would 403 the stream anyway.

Result: a restricted user gets a **fully working** Ravilo scoped to exactly their libraries — no empty
screens, no errors, just their permitted content; an admin sees everything, unchanged.

## Non-goals
- Blocking, disabling, or hiding restricted users — they are first-class; this is filtering only.
- Enforcing Jellyfin **parental-rating** item filtering here (the existing `isKids`/`MaxParentalRating`
  behaviour is untouched; a future phase can add rating-level filtering if wanted).
- A full re-scan to populate `libraryId` (the path-prefix backfill avoids it).
- Per-item (as opposed to per-library) ACLs, or Jellyfin "block unrated items"/tag-based blocks.
- Any client-side ACL — Ravilo renders exactly what the server serves (see R175).

## Acceptance
- `JellyfinPolicy` round-trips `EnableAllFolders`/`EnabledFolders`; a user's allowed-library set resolves
  correctly (all-folders ⇒ every library; else the listed ids).
- Every `MediaItem` has a correct `libraryId` after the backfill (unit-tested: path-prefix derivation
  matches `Scanner`'s own matching).
- Logged in as `charlotte` (restricted), Ravilo Home/Browse/Search/Detail show **only** her permitted
  libraries' content and nothing else; as an admin, the full catalog appears — verified by a unit test on
  the filter predicate (admin sees all; a 2-library user sees only those) plus a manual on-device check.
- A blocked item's detail id returns 404; no blocked title appears in any channel/row/facet count.
- Verified via `compileKotlinLinuxX64` + `linuxX64Test` + admin `compileKotlinWasmJs`.

## Design addendum (2026-07-09, design-side — not yet dev-reviewed)

The Phase 143 mockup's per-user **access line** displays the *full* Jellyfin policy — library access
**plus allowed/blocked tags and max parental rating** — because tag-based restriction is a normal way
households restrict content in Jellyfin. Display is covered by 143 (read the extra `Policy` fields);
**enforcement** of tags/rating on the device-facing read paths remains outside this phase's scope (see
Non-goals) and is recommended as a **follow-up phase**: reuse §C's `visibleTo` predicate seam, extend
the persisted per-device policy snapshot with `allowedTags`/`blockedTags`/`maxParentalRating`, and
filter with the same drop-before-DTO rule. Until then the admin UI shows tag/rating restrictions that
Ravilo does not yet enforce — the 143 tab is the only surface that makes that gap visible to an
operator, which is deliberate.
