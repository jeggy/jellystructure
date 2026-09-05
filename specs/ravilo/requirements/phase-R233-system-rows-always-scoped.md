# Phase R233 — A system row shows what is available where it is shown

> Live report (2026-09-04): standing inside the **Thriller / Gyser** collection, the Continue Watching
> row led with *Two and a Half Men*, *Klovn*, *Sjit Happens*, *Danish Dynamite* and *Ali G* — none of
> them thrillers, none of them in the collection. The row directly below it, *Newly Added — Movies*,
> was correctly filtered to the collection (*Primate*, *Until Dawn*, *Oldboy*, *Mutiny*). The two
> system rows on one page disagreed about what page they were on.

**Status:** ✓ Built 2026-09-05 — design-authored 2026-09-04 with the owner, built the next day. Compiles
clean (`compileKotlinLinuxX64` + `compileKotlinWasmJs`), full suite green (185/185), no schema
migration (FR-R233-4 — `ignoreUnknownKeys` already handles the field's removal on read). Not
dev-reviewed. **Not live-verified against the running dev backend** — this repo's own working
agreement is never to restart the live backend without asking, and the "Verification" table below
needs the new code actually loaded (a curl against the currently-running process would only prove the
*old*, pre-fix behaviour). Ask the user before restarting to run those checks.

### What's built

- `HomeFeedService.kt` — FR-R233-1/2 (custom-mode branch always filters Continue via `matchesChannel`,
  `scope` conditionals deleted; inherit-mode `RowKind.CONTINUE` gains the same filter, applied only
  when `channelFilter != null` so Home is an unconditional no-op) and FR-R233-3
  (`continueWatchingAll`'s `scopedToChannel`/mode check collapsed to "filters whenever the channel
  resolves"). FR-R233-5 untouched by construction — every change is a `.filter{}` layered on top of
  `canonicalContinueList()`'s existing return value; nothing built the canonical list from a
  channel-filtered source or touched `continueListCache`'s key.
- `shared/.../Models.kt` — FR-R233-4: `SystemContinue.scope`/`SystemNewly.scope` deleted; the
  `ChannelRowsConfig`/`ChannelSystemRows` KDoc corrected to describe scope-always-on and the R145
  mis-citation removed.
- `RaviloConfig.kt` (admin editor) — FR-R233-7: `scopeSeg()`, the `contScope`/`newlyScope` locals, the
  `.cf-sysscope` click handler, and both segmented controls deleted; the two row descriptions are now
  unconditional ("In-progress titles from this collection" / "Newest titles in this collection").
  **`design/app/ravilo-builders.js`/`.css` needed no change** — the 2026-09-05 design sync had already
  removed the same segmented control and its 2-line CSS rule before this phase was built (confirmed:
  no `scope:'all'`/`scope:'channel'` literal remains in the new-channel template either); see the
  design-sync restore commit from the same session, which explicitly left this removal alone as
  legitimate rather than reverting it.

## Problem

The behaviour is correct against today's spec, which is the problem: **R219 §5** and **R202**'s
invariant both say an inherit-mode channel's Continue Watching row is Home's own row, library-wide.
That was a deliberate decision, reached in R202 after the opposite bug (inherit channels showed *no*
Continue row at all, an R05 leftover). It reads wrong in practice, for a reason R202 didn't weigh: a
collection page is a claim about what is in the collection, and a row on that page that quietly
ignores the claim is worse than no row.

Two independent mechanisms produce this today, and both are being removed:

1. **`SystemContinue.scope` / `SystemNewly.scope`** (`"all"` vs `"channel"`, R143) — a per-channel
   opt-out that makes a system row library-wide inside a collection page. Consulted only in custom
   row mode.
2. **The inherit-mode Continue path** (`HomeFeedService.kt:401-409`, R202 FR-RV-R2-1) — builds from
   `libraryAll` and never filters, in any configuration.

Mechanism 2 is what the live report hit; Thriller / Gyser is an inherit-mode channel (`rows == null`).
Mechanism 1 is latent — **no channel in the live config sets `scope` at all** (verified 2026-09-04
against `ravilo_config`: Olivar, DanskTV and Disney+ are the only custom-mode channels and every one
of them is on defaults), so retiring the field changes nothing for them today. It is removed because
it is a setting that should never have existed, not because it is currently misused.

An asymmetry worth naming: **Newly Added is already channel-scoped in inherit mode** — the inherit
path passes the channel-filtered `all` list (`:410-431`), which is why the screenshot's second row was
right while the first was wrong. R202 noticed this and left it: *"channel-scoped Newly Added reads as
reasonable for a filter-driven row the way Continue Watching (a viewer-progress row, not a catalog
filter) doesn't. Left unchanged; revisit only if reported."* This phase is that report, resolved the
other way: the distinction between a progress row and a catalog row is real, but it does not survive
contact with a page that has a name on it.

## The rule

> **Continue Watching and Newly Added always show what is available in the place they are rendered.**
> Home → the whole visible library. A collection page → that collection. This is not configurable, in
> either row-list mode.

Home is unaffected in every case: Home's "available" set *is* the library, so the rule reduces to
today's behaviour there.

## What this phase does NOT change

Stated up front because a first reading of the live report conflated these with the fix:

- **The Same as Home / Custom row-list switch (R59) stays exactly as it is.** `ChannelRowsConfig.mode`
  keeps its meaning: whether a channel draws its *filter* rows from Home's global list or from its own
  ordered set. That is a genuinely useful choice and 7 of the 10 live channels rely on it. This phase
  changes only how the two **system** rows are scoped, in both modes.
- **`SystemContinue.show` / `SystemNewly.show` stay.** Hiding a system row on a channel page is a real
  editorial decision and is in live use (Olivar hides both). Only `scope` is retired.
- **`SystemNewly.merge` stays.** Merged vs split Movies/Series is presentation, not scope.
- **Membership and ordering of the canonical list.** R219's model — what is in the list, which card
  wins a conflict, `lastActivityAt` ordering, the 7-day touched window — is untouched. This phase adds
  a filter stage over it and removes a branch; it does not re-open the model.
- **Anything on the Ravilo clients.** See FR-R233-6.

## Requirements

### FR-R233-1 — Continue Watching on a channel page is filtered to that channel, in both row-list modes

`HomeFeedService.buildRows()` filters the canonical Continue Watching list with the existing
`MediaItem.matchesChannel(channelCfg, heroIds)` predicate (`:791`) whenever `channelFilter != null` —
with no reference to `mode` and no reference to `scope`.

- **Custom-mode branch (`:382`)** — drop the `if (sys.cont.scope == "channel") … else canonical`
  conditional; always filter.
- **Inherit-mode branch (`:401-409`)** — currently `canonicalContinueList(device, libraryAll, …)`
  with no filter at all (R202 FR-RV-R2-1). It keeps building from `libraryAll` (see FR-R233-5) and
  gains the same `matchesChannel` filter, applied when `channelFilter != null`. On Home
  `channelFilter` is null, so Home is a no-op.

Filtering precedes capping, unchanged from R219 FR-R219-5: the row is the 20 most recent titles *of
that collection*, not whatever survived a library-wide cut and happened to be in it.

### FR-R233-2 — Newly Added on a channel page is filtered to that channel, in both row-list modes

The custom-mode branch (`:387`) drops `if (sys.newly.scope == "channel") all else libraryAll` and
always passes `all` (the already-channel-filtered list). The inherit path already does this and needs
no change. This closes R202's deferred *"revisit only if reported"* item.

### FR-R233-3 — Continue Watching's "See all" follows, unconditionally

`continueWatchingAll(device, channelId)` (`:556`) filters whenever the channel resolves — the
`scopedToChannel` computation at `:564` and its `channelRows` local are deleted. The four-way decision
R219 FR-R219-6 specified collapses to:

- `channelId == null` → unfiltered (Home's See-all).
- `channelId` resolves to a channel → filter with `matchesChannel`, uncapped.
- `channelId` does not resolve → unfiltered (defensive, unchanged).

The row and its See-all page must never be able to disagree about membership. Under R219 that
required keeping two branch ladders character-for-character identical in two files; under this phase
there is one behaviour and nothing to keep in sync.

### FR-R233-4 — Retire `scope` from the config model

Delete `SystemContinue.scope` (`Models.kt:711`) and `SystemNewly.scope` (`:717`). `show` and `merge`
survive.

- **No migration.** `RaviloConfigService.kt:24` is `Json { ignoreUnknownKeys = true }`, so a stored
  blob carrying `"scope": "all"` still parses; the field disappears on the next config save. No live
  config carries it anyway (see Problem).
- `ChannelRowsConfig`'s KDoc (`:691`) currently reads *"in `inherit` mode the channel keeps inheriting
  Home's system rows (scoped to the channel per R59)"* — inaccurate today, accurate again after this
  phase. Keep it, and correct the `(R145)` citation on `ChannelSystemRows` (`:702`), which points at
  the responsive mobile layout phase; the per-channel system rows are **R143**.

### FR-R233-5 — The canonical list stays library-wide and cached per user

**This is the guard requirement.** R219 FR-R219-1 keys `continueListCache` on
`(Jellyfin user, device visibility scope)` — deliberately **not** on channel, because channel
membership is a filter over one shared list. Every change above is a *view* filter applied after
`canonicalContinueList()` returns.

Specifically forbidden, however tempting the simplification looks:

- building the canonical list from the channel-filtered `all` instead of `libraryAll`;
- adding `channelId` to the `continueListCache` key;
- re-deriving membership or order per channel in any other way.

Doing any of these forks the cache per channel, multiplies the Jellyfin round trips a single feed
build costs, and re-opens the class of bug R219 exists to close (see its **THE RULE THAT KEEPS GETTING
BROKEN** section — violated four separate times across R186/R217/R219).

### FR-R233-6 — No client change

The Ravilo apps (Android/Compose, Tizen, web) need no change and no redeploy. `RaviloApp.kt`'s
`ChannelView.onSeeAll` already forwards `dest.channel.id` for `RowKind.CONTINUE`, and non-Continue
rows already carry a channel-aware `seedQuery` via `withChannelSeed()`. The client continues to send
the channel id it is standing in and to compute no scope decision of its own; the server alone decides
what that id means — which after this phase is simply "filter", always.

Newly Added rows are built without a `seedQuery`, so they have no "→ See all" tile and there is
nothing to scope on that path.

### FR-R233-7 — Remove the scope control from the channel editor

`src/wasmJsMain/.../ui/RaviloConfig.kt`:

- delete the `scopeSeg()` helper (`:1077-1081`) and both call sites (`:1220`, `:1226`);
- delete the `contScope` / `newlyScope` vals (`:1075-1076`);
- delete the `.cf-sysscope span[data-scope]` click handler (`:1276-1285`);
- the two row descriptions become unconditional — **"In-progress titles from this collection"**
  (`:1219`) and **"Newest titles in this collection"** (`:1225`, keeping its `· combined` /
  `· Movies + Series` suffix).

The `show` toggles (`data-systog`, `:1262-1272`) and the merge toggle (`:1273`) are untouched, as is
the inherit-mode note at `:1240` — *"Shows the global Home rows scoped to this collection — the default
behaviour"* — which this phase makes true of the system rows as well as the filter rows.

Mirror the same removal in the design source of truth, `design/app/ravilo-builders.js`: the two
segmented controls (`:534`, `:540`), their handler (`:736`), and the `scope` keys in the new-channel
template (`:364` — which also carries a pre-existing drift, defaulting to `scope:'all'` where the
Kotlin model defaults to `"channel"`; both disappear here). Per `CLAUDE.md`, run
`scripts/check-mobile-css.sh` and `scripts/check-css-scoping.sh` after touching `design/`.

## Invariants

- **A system row never contradicts the page it is on.** If a title is not in the collection, it is not
  in that collection page's Continue Watching or Newly Added — regardless of row-list mode, config, or
  how the page was reached.
- **Scope is a property of the surface, not of configuration.** There is no supported way to put a
  library-wide system row on a collection page. If that is ever wanted again, it is a new phase with a
  new justification, not a resurrected field.
- **One canonical list** (R219 FR-R219-1), library-wide, cached per `(user, visibility scope)`. Views
  filter; they never re-derive. See FR-R233-5.
- **The row is atomic** (R102): a channel whose filtered Continue list is empty renders **no row at
  all**, not an empty one — the existing `if (cont.cards.isNotEmpty())` guard, unchanged.
- **Frontend renders server-pushed state only** (constitution). No client change (FR-R233-6).

## Edge cases, stated explicitly

| Case | Behaviour |
|---|---|
| Home's Continue Watching / Newly Added | Unchanged — library-wide, because that is what is available on Home. |
| Inherit-mode channel, viewer has in-progress titles in it | Continue row shows only those. **This is the fix.** |
| Inherit-mode channel, viewer has nothing in progress in it | No Continue row at all (R102 atomicity). New, and accepted. |
| Custom-mode channel | Same behaviour as inherit for both system rows; only the *filter* rows differ. |
| Custom-mode channel with `show: false` | Row omitted, as today. Olivar's live config. |
| Stored config still carrying `"scope": "all"` | Ignored on read (`ignoreUnknownKeys`), dropped on next save. Does not occur in the live config. |
| See-all opened from any channel | Filtered to that channel, uncapped. |
| See-all opened from Home | Unfiltered, uncapped. Unchanged. |
| Channel id that no longer resolves | Unfiltered — defensive, unchanged from R219. |
| Inherit-mode channel wanting to hide a system row | Not possible; `show` is read only in the custom-mode branch, in both backend and editor. Unchanged by this phase — see Open questions. |
| A title in the collection but not visible to this device | Excluded, by the visibility scope the canonical list is already keyed on (R219). |

## Out of scope

- **Making `show` available in inherit mode.** Today `ChannelSystemRows.show` is read only inside the
  custom-mode branch and its toggles render only inside the custom-mode editor body — consistent
  between backend and UI, and unchanged here. See Open questions.
- **A channel in custom mode with zero `items`** shows no filter rows. Already true, still true, and
  a misconfiguration rather than a behaviour to design around.
- **The Same as Home / Custom switch itself** (R59) — see *What this phase does NOT change*.
- **R219's list model** — membership, conflict resolution, ordering, caps, the SWR cache. Untouched.
- **Manual "remove from Continue Watching"** — still deferred (R219 Out of scope).
- **Live TV**, which has its own row and lifecycle (R177).

## Verification

No `HomeFeedServiceTest` exists and building one needs substantial Jellyfin DI mocking (R219 made the
same call). Verify the way R219 was verified — pull a real `device_token` from `ravilo_device` and
call the backend directly, no TV or app involved:

| Check | Expectation |
|---|---|
| `GET /api/tv/channel/ch-958085` (Thriller / Gyser, inherit) | Continue row contains only titles in the collection; `seedTotalCount` equals the filtered count, not 82 |
| `GET /api/tv/continue/all?channel=ch-958085` | Same membership, uncapped; count matches the row's `seedTotalCount` |
| `GET /api/tv/continue/all` (no channel) | Unchanged full canonical list |
| `GET /api/tv/home` | Byte-identical rows to before the change |
| `GET /api/tv/channel/ch-540633` (DanskTV, custom, defaults) | Unchanged — it was already channel-scoped |
| `GET /api/tv/channel/ch-704037` (Olivar, `show: false` on both) | Still no system rows |

Plus `compileKotlinLinuxX64` and the wasmJs frontend compile for FR-R233-7.

## Source references

- `src/linuxX64Main/.../tv/HomeFeedService.kt` — `buildRows()` custom-mode system-row block
  (`:376-390`, FR-R233-1/2), inherit-mode `RowKind.CONTINUE` case (`:401-409`, FR-R233-1),
  `continueWatchingAll()` (`:556-570`, FR-R233-3), `MediaItem.matchesChannel()` (`:791`),
  `canonicalContinueList()` (`:576`, FR-R233-5).
- `shared/.../tv/Models.kt` — `ChannelRowsConfig` (`:687`), `ChannelSystemRows` (`:702`),
  `SystemContinue` (`:709`), `SystemNewly` (`:715`) — FR-R233-4.
- `src/linuxX64Main/.../tv/RaviloConfigService.kt:24` — `ignoreUnknownKeys`, why FR-R233-4 needs no
  migration.
- `src/wasmJsMain/.../ui/RaviloConfig.kt` — `:1075-1081`, `:1219-1226`, `:1276-1285` (FR-R233-7).
- `design/app/ravilo-builders.js` — `:364`, `:534`, `:540`, `:736` (FR-R233-7).
- Related: **R219** (the canonical list; §5, FR-R219-5 and FR-R219-6 amended by this phase),
  **R202** (partially reversed — see its 2026-09-04 addendum), **R143** (introduced `scope`, retired
  here), **R59** (the row-list mode switch, untouched), **R228** (empty channels — the same "empty
  means empty" instinct), **R102** (atomic row).

## Open questions

1. **Should `show` become available in inherit mode?** An inherit-mode channel currently has no way to
   hide Continue Watching or Newly Added on its own page — hiding them on Home hides them everywhere.
   Not reported as a problem, and deliberately not bundled here; this phase is about scope, not
   visibility. Worth revisiting if a channel ever wants the filter rows without the system rows.
2. **Does the empty-row case need copy?** A collection where nothing is in progress now silently has
   no Continue row. Consistent with R102 and R228, but if a viewer reads the absence as a bug rather
   than a state, a one-line empty treatment becomes a design question.
