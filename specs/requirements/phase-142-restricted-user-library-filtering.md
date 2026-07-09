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

## Dev-review follow-up (2026-07-09, later — tags now enforced)

Found live: a real user (Olivar, `EnableAllFolders=true`, `AllowedTags=[børne-tv]`) passed the library
check trivially and saw the entire catalog in Ravilo — exactly the gap this addendum flagged. **Tag
enforcement is now built**, per the recommendation above: `DeviceData.allowedTags`/`blockedTags`
(lowercased at login, from the same Policy response), two new `ravilo_device` columns (migration
`19.sqm`), `MediaItem.passesTagPolicy` + a combined `MediaItem.visibleTo(device)` gating every read path
§C lists, via a new `MediaStore.liveItems(device)` overload. Covered by `VisibleToTest`. **Still open:**
`maxParentalRating` enforcement — Jellyfin's numeric score has no established mapping to this
codebase's TMDB-certification strings, so it stays display-only on the 143 access line for now.

## Dev-review addenda (2026-07-09) — reconciled with live code

Verified against the working tree. This section **supersedes** the spec body where they differ.

### Verified (accurate as written)
- `JellyfinPolicy` (`auth/Models.kt:33-38`) is exactly `IsAdministrator` + `MaxParentalRating`. Adding the
  two `@SerialName` fields is the whole model change (but see the "one change away" correction below).
- `Scanner.scanItem` (`Scanner.kt:59-85`) already computes the owning `LibraryMapping lib`; `lib.jellyfinId`
  is in scope and is the Jellyfin VirtualFolders `ItemId` (`AppConfig.kt:207`; string-equated with live
  library ids at `Server.kt:255-257`). Threading it into `scanMovie/scanSeries` is a clean 3-line change.
- `MediaItem` adding `libraryId: String? = null` is backward-compatible — blobs decode with
  `Json { ignoreUnknownKeys = true }` (`MediaStore.kt:31`) and default `null`.
- `HomeFeedService`/`BrowseService`/`DetailService` all receive `device` (with `jellyfinUserId`) on every
  read path listed — the predicate can be threaded through as designed.
- `EnableAllFolders`/`EnabledFolders` are real Jellyfin `UserPolicy` fields; live `/Users` confirms the
  restricted users (charlotte 5 / danjal 2 / Test Stream 3). `EnabledFolders` id space **does** match
  `LibraryMapping.jellyfinId`. The `if (enableAllFolders) <all> else enabledFolders` resolution is correct.

### Corrections to apply
- **"getUsers/authenticateByName already return the full Policy" is misleading.** Both parse into the
  **typed 2-field `JellyfinPolicy`**, and the client runs `ignoreUnknownKeys = true`
  (`OutboundHttp.kt:49`), so `EnableAllFolders`/`EnabledFolders` are **on the wire but silently dropped**
  today — not retained. Reword to: "the fields arrive but are discarded; adding the two `@SerialName`
  fields retains them." (The "one deserialization change away" half is correct.)
- **§B4 backfill matches the WRONG path direction.** The stored `MediaItem.path` is the **local** path;
  `scanItem`'s in-scan match uses the **Jellyfin** path (`lib.jellyfinPath.ifBlank{localPath}`). A backfill
  keyed on `item.path` must match `lib.localPath.ifBlank{jellyfinPath}` — the direction the *sync/rescan*
  methods use (`Scanner.kt:456-459` etc.), **not** "the same match `Scanner` uses" in `scanItem`. Use
  `MediaStore.load()`'s existing `backfillTimestamps()` (`MediaStore.kt:127-142`) as the ready-made hook +
  template (one `db.transaction`, full-blob re-encode per row — the same one-off cost it already pays).
- **§C5 misses a device-facing read path: facets.** `BrowseService.facets(kind)` (→ `GET /tv/facets`,
  `TvRoutes.kt:298-300`) has **no `device` param** and computes chip counts over the full catalog — so a
  restricted user's Ravilo facet counts would leak blocked-library content. Add `facets` (with a `device`
  param) to §C5's list. Conversely, the global `libraryVersion`-keyed `MediaStore` facet caches
  (`metaFacets/trackFacets/nfoCoveredCount/countBatch`) serve **admin** surfaces only (Library page /
  Ravilo-config editor) — leave them **unfiltered**; do not add per-user cache keys there.
- **`MediaItem` file path is `src/commonMain/kotlin/dev/jellystructure/model/Media.kt` (commonMain),** not
  linuxX64 — the model is **shared with the wasmJs admin frontend**, so `libraryId` also lands in admin
  DTOs (harmless, but note it). `plan.md` §"Data Models (commonMain) › `MediaItem`" (105-138) lists every
  field and must gain `libraryId`.
- **Non-goals wording:** there is **no existing per-user catalog filter** today. `isKids`/`MaxParentalRating`
  are used **only** to steer *arr/Seerr request language (`RequestLanguageService`, `SeerrDiscoverService`),
  never to gate the catalog. So this predicate is the **first** per-user catalog gate — reword "the existing
  behaviour is untouched" (there is none to leave untouched).

### Design gaps / must-resolve
- **GUID normalization is the single most likely runtime failure.** `EnabledFolders` and
  `/Library/VirtualFolders` `ItemId` can differ in dashing/case across Jellyfin builds; the comparison is raw
  string membership. §A1 must specify a one-time normalize (strip dashes, lowercase) on **both** sides and a
  startup log of the two id sets to catch a mismatch.
- **`libraryId == null` default must be explicit.** Make `visibleTo` **fail-closed**: a restricted user
  (`allowed != null`) does **not** see `libraryId == null` items; an all-folders user (`allowed == null`)
  sees everything. And the backfill **must run before** filtering activates, or freshly-scanned-but-not-yet-
  backfilled items get wrongly hidden (called out in the research report §5).
- **Cross-spec seam with 141.** §A2 says "the policy is already fetched at login (Phase 141)" — but 141 as
  written does **not** persist `enabledFolders`. Pin down the hand-off: either 141's `loginDevice` captures
  the allowed set onto the device row, or 142 fetches the policy itself (via the existing `getUsers`, which
  returns each user's `policy` inline — no extra call). Recommend the latter to keep 141 minimal.
- **Column is per-(device,user) row.** `ravilo_device` PK is `(device_id, jellyfin_user_id)`, so a new
  `allowed_libraries` column follows the `is_kids` precedent but is stored **per device-row** — the same
  restricted user on 3 TVs stores it 3×, and a TTL refresh must update **all** of that user's rows (or
  recompute on read). Carry it on `DeviceData` (rebuilt from the row on every request) so the predicate reads
  `device.allowedLibraries` with no extra query.
- **Cache-freshness:** `HomeFeedService.feedCache` is already per-`jellyfinUserId`, but its key is
  `(libVersion, cfgHash, 5-min TTL)` — a Jellyfin **policy** change isn't reflected until the TTL rolls.
  Fold the allowed-set (or its hash) into the key, or accept ≤5-min staleness on a policy change (state which).
- **Cleanest seam (fewer touch-points):** filter `liveItems()` **once** at each service entry
  (`HomeFeedService` :92 & :113, `BrowseService` :46/:93/:129) — that automatically covers heroes (auto +
  curated resolve-by-id), channels, all rows, newly-added, and the Continue mapping. `DetailService` can't
  share one list (it uses `resolveByJellyfinId` + `relatedByGenre`), so there: `visibleTo` after resolve ⇒
  404 (§C6), and filter the `relatedByGenre` output. `ConditionEvaluator` has no `library` facet — confirm
  library filtering stays a **predicate on the item list**, not a new facet (the spec's approach is right).
