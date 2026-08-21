# Phase 172 — Music videos: full filter support (workbench, Ravilo config, Library picker, Browse)

> Requested live, same session as Phase 168/171: "we need to add it to our workbench filter support
> and to anything in our ravilo configuration setup within jellyfin, so it's fully supported to use
> and filter it by music media type and on the library page within jellystructure, the top right
> picker should add musicvideo as well to the support simple media type filter." Phase 168 added
> `MediaKind.MUSIC_VIDEO` and scanned/matched it like any other kind (168/171); this phase closes the
> remaining gap — every UI/backend site that offers a Movie/Series type choice only offered two,
> silently excluding or mislabeling a music video wherever that choice narrows or seeds a filter.

**Status:** Implemented 2026-08-21.

## Problem

`MediaKind` has three members (`MOVIE`, `TV_SHOW`, `MUSIC_VIDEO`), but the type-filtering layer above
it — built before Phase 168 — is binary everywhere: a `wbInclude`/`libKind`/`RowConfig.mediaKind`
string only ever encodes "movies", "series", or "all/none". A music video item is invisible to
every "filter by type" control, and any Ravilo content row or channel scoped to it can't be built.

## Fix — add a third value at every binary type-filter site

Convention: admin-side `include` strings stay lowercase-plural (`"movies"`/`"series"`, now plus
`"musicvideos"`); every other layer (`RowConfig.mediaKind`, `ConditionEvaluator`'s row-membership
check, `BrowseService.browseByQuery`'s seed filter, `HomeFeedService`'s row builders) stores/reads
the literal enum name `"MUSIC_VIDEO"` (already the convention `MediaKind.name` gives for free, unlike
the pre-existing `"SERIES"` vs `TV_SHOW` mismatch which predates this phase and isn't touched).

**Admin — Library page top-right type picker** (`Library.kt`): `#kindseg` gains a third segment
`Music videos` (`k-mv`) alongside All/Movies/TV, wired through `libKind`, `libInclude()` (→
`"musicvideos"`), and `applyWorkbenchToLibrary` (→ `kind=MUSIC_VIDEO`).

**Admin — shared filter workbench** (`Workbench.kt`, used by both `library.html` and
`ravilo-config.html`'s content-row/channel editors): the `wb-include` segmented control in the modal
header gains a `Music videos` option (`data-inc="musicvideos"`); `countMatching()`'s live-count query
maps it to `MediaKind.MUSIC_VIDEO`.

**Admin — Ravilo config editor** (`RaviloConfig.kt`): every `include ↔ mediaKind` bridge (global
content rows + per-channel rows, both the "add" and "edit" paths) and both row-summary label helpers
(`systemRowSource`, `defaultRowTitle`) gain the `"musicvideos"`/`"MUSIC_VIDEO"` third case — a
content row or channel can now be scoped to music videos only, same as Movies/Series today.

**Backend — row-membership + row-building** (`ConditionEvaluator.rowMatches`, `HomeFeedService`'s
`NEWLY_ADDED`/`CUSTOM` row filters, `BrowseService.browseByQuery`'s seed `mediaKind` filter): each
gains a `"MUSIC_VIDEO"` branch alongside the existing `"MOVIE"`/`"SERIES"` ones, so a row/channel
saved with that scope actually filters correctly both on Home and on its "→ See all" seeded-browse
page.

**Ravilo Browse page TYPE facet** (`SeededBrowseScreen.kt`): value collection was already generic
(`bump(c.kind.name)`, and the match filter already compares against `c.kind.name` — no change
needed there), but `facetValueLabel`'s `TYPE` branch was a hardcoded binary `if (value == "MOVIE")
... else "series"` — a `"MUSIC_VIDEO"` facet value was silently mislabeled "Series". Fixed to a real
three-way `when`, backed by a new i18n key `browse.type.musicvideo` (en "Music video", da "Musikvideo",
fo "Tónleikamyndband") added alongside the existing `browse.type.movie`/`browse.type.series` in
`Strings.kt` (all three locales).

## Explicitly out of scope

- **No new Ravilo top-nav tab.** `BrowseKind`/`RaviloNavTarget` (the Movies/Series/Discover top-nav)
  and the old plain `browse()`/`facets()` "movie"/"series" `kind` param (`BrowseService.kt:113-236`,
  which backs those tabs + My List, not the workbench/config/Browse-facet paths above) are untouched
  — the ask was filter support, not a new primary nav destination. Music videos remain reachable via
  Library, a configured content row/channel, and the generic Browse page's Type facet.
- **No change to `addNewlyAddedRows`' default `mediaKind == null` split** (still Movies + Series,
  `HomeFeedService.kt:404-409`) — a music-video-only Newly Added row is now buildable *explicitly*
  via the workbench, same as a Movies-only or Series-only one always was; the unfiltered system
  row's own default behavior isn't part of this ask.
- Tizen shares `ravilo-ui`'s `SeededBrowseScreen`/facet code directly (no Tizen-specific copy found)
  — no separate Tizen change needed.

## Verification

- `compileKotlinLinuxX64` and `:ravilo-web:compileKotlinWasmJs` (covers `ravilo-ui`/`shared`
  commonMain too) both clean.
- No behavior change for existing Movie/Series filtering — every touched `when`/`if` gained a branch,
  none had its existing branches altered.
