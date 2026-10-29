# Phase 172 — Music videos: full filter support (workbench, Ravilo config, Library picker, Browse)

> Requested live, same session as Phase 168/171: "we need to add it to our workbench filter support
> and to anything in our ravilo configuration setup within jellyfin, so it's fully supported to use
> and filter it by music media type and on the library page within jellystructure, the top right
> picker should add musicvideo as well to the support simple media type filter." Phase 168 added
> `MediaKind.MUSIC_VIDEO` and scanned/matched it like any other kind (168/171); this phase closes the
> remaining gap — every UI/backend site that offers a Movie/Series type choice only offered two,
> silently excluding or mislabeling a music video wherever that choice narrows or seeds a filter.

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

## Amendment 2026-10-29 — a collection keeps its own Include (DEB-2847)

Owner-found on prod, 2026-10-29. Above, this phase says that "a content row **or channel** can now be
scoped to music videos only, same as Movies/Series today", and that "a row/channel saved with that scope
actually filters correctly". For a row that is true: `RowConfig.mediaKind` stores it and
`ConditionEvaluator.rowMatches` applies it. For a channel (a *collection* in the admin) it never was.
`ChannelConfig` has no kind field, and that was so before this phase as well. The collection's
*Filter* dialog draws the All / Movies / Series / Music videos switch, but its `onApply` throws the
choice away (`RaviloConfig.kt`, `#ch-ed-filter-edit` and `#ch-workbench`, `{ query, _ -> … }`). A
collection set to Series reopens as All and serves films too. On prod one collection served 410
titles where series-only was meant. R36's `onSaveChannel` passed `include` through but never stored
it either. This amendment makes the sentence above true. It is new wire, so it is written here before
the Kotlin.

**FR-172-1 — The field.** `ChannelConfig` gains `@SerialName("media_kind") val mediaKind: String? = null`.
Its values and meaning are `RowConfig.mediaKind`'s: `"MOVIE"`, `"SERIES"`, `"MUSIC_VIDEO"`, or `null` for
all kinds. Any other string reads as `null`. The field is additive, and no Ravilo client reads
`ChannelConfig`, because clients get server-built rows only. An installed app ignores it, and a config
saved before this amendment reads as `null`, which is today's behaviour.

**FR-172-2 — Membership.** A collection with a `mediaKind` admits only items of that kind (`SERIES` ↔
`MediaKind.TV_SHOW`). The check sits in **one** place that every collection-membership site calls, so
the sites cannot drift apart (the R228 trap): `HomeFeedService`'s `MediaItem.matchesChannel` (the rail's
non-empty verdict, the collection page's content, its Continue Watching, its Newly Added, its custom
rows) and `BrowseService`'s per-card `channels` list. It applies before the query, and it applies on the
legacy single-typed-filter path too. A kind excludes items; it never adds any. A collection with a kind
and an empty query admits every item of that kind.

**FR-172-3 — Rows inside a collection.** A row's own `mediaKind` and its collection's are ANDed. A
Movies row inside a Series collection is empty and is hidden like any empty row. The row's *See all*
seed carries `seedMediaKind = row.mediaKind ?: channel.mediaKind`, so the browse page holds the same set
the row was built from. The two-way Movies/Series split of a collection's Newly Added row (`mediaKind ==
null`) is unchanged. In a Series collection the Movies half is empty and drops out.

**FR-172-4 — Admin.** Both collection workbench paths store the switch. *New collection — condition
workbench* saves `mediaKind` with the query. *Filter — (collection)* opens on the stored kind
(`initialInclude`, mapped as the row editors already map it) and writes it back on *Apply filter*. The
collection's filter summary names the kind when it is not All, for example *Series · Studio is …*.
The live count in the collection's dialog already counts by the switch. A row editor inside a
collection counts with the collection's kind ANDed, in the same way it already shows the collection's
query as a locked first block.

**FR-172-5 — Not in scope.** No migration. A collection saved before this amendment stays All until
it is saved again with a kind. The owner re-applies Series on the affected collection, and nothing
guesses it. The switch is not hidden on the collection dialog, and the row-level *Include* does not
change.

**Proof.** A `linuxX64Test`: a `ChannelConfig` with `media_kind: "SERIES"` round-trips through the
config JSON, and JSON without the key reads as `null`. A `SERIES` collection's content and its rail
verdict exclude a matching film, and a `null` collection still includes it (FR-172-2). A Movies row
inside a Series collection is empty, and its seed kind follows FR-172-3. On top of that, one admin
save and reopen of a collection's Filter showing Series (FR-172-4) goes on the QA queue.
