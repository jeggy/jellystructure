# R127 — Workbench facet-value counts (+ channel-scoped narrowing)

> Builds on **[R32](phase-R32-unified-filter-workbench.md)** (the shared condition-stack Workbench used by
> Library + Channels + Content rows), **[R86](../../requirements/phase-86-ravilo-config-page-performance.md)**
> (`batch-count`, the decoded-library/facet caches), and **[R87](phase-R87-channel-row-coverage-gap.md)**
> (channel↔row composition). Pure metadata/UX — no change to the evaluator or the config model.

---

## Problem

The shared filter Workbench renders facet values as bare chips ("Action", "Netflix", "Pixar", a tag) with
**no count**, so an operator can't tell whether a value covers 200 titles or 2 — building a meaningful
channel or content row is guesswork. The counts already exist: `GET /api/media/meta-facets` and
`/api/media/track-facets` return `FacetItem(value, count, color?)` for every facet, already count-sorted —
but `Workbench.wbValuesFor()` did `.map { it.value }` and **threw the count away**. (Library's quick-filter
dropdown already shows these counts; the Workbench just never did.)

And when setting up a content row **inside a channel**, even if counts were shown they'd be library-global —
useless for judging a row within that channel's narrower set.

## Requirements

1. **Count on every Workbench facet value.** Each value chip shows its item count — **Studio · Network ·
   Genre · Tag** and the audio facets **language · codec · track title** — ordered by count (desc; the API
   already sorts). Tags keep their colour dot + Jellystructure/Other grouping.
2. **Channel-scoped narrowing.** When the Workbench is opened for a content row **inside a channel** (the
   channel's own filter is passed as `baseConds`/`baseMatch`, already shown as the "Scoped to channel filter"
   banner), the counts are **narrowed to that channel's scope**: each value's count = items in the channel
   that have it, **ordered by the narrowed count**, and values **not present in the channel disappear**.
   Computed **once** when the Workbench opens (channel scope is fixed for the row — not live faceted search).
   A channel with no filter, and the **library-wide** Workbench, show global counts.

## Data / transport

- **No model change.** New endpoint `POST /api/media/facets` `{ match, conditions }` → one combined response
  `{ studios, networks, genres, tags, audioLanguages, audioCodecs, trackTitles }` of `FacetItem(value, count,
  color?)`, all **narrowed** to the conditions. Backed by `MediaStore.facetsNarrowed(match, conditions)`:
  filter `allItems()` via `ConditionEvaluator.matches` (same path as `countBatch`) then count meta + track
  facets over the narrowed set in one pass (reuses the extracted `buildMetaFacetsFrom`/`buildTrackFacetsFrom`).
  Uncached — computed on demand when the Workbench opens in scope (cheap over the Phase-88 decoded cache).
- Global `GET meta-facets`/`track-facets` unchanged (library-wide Workbench). `batch-count` is *not* reused —
  it returns one total per stack, not per-value counts; one `/facets` pass beats N batch calls.

## Scope / invariants

- Channel-scoped, **not** live faceted search (user choice) — the in-progress row conditions do not re-narrow
  the value counts; only the channel's own filter does. The live **preview total** still unions
  `wbConds + wbBaseConds` as before.
- 0-count values are naturally absent from a narrowed set (computed from the narrowed items), so the channel
  Workbench only offers values that exist in the channel.

## Out of scope

- Live faceted search (recomputing counts as the row's own conditions change; needs per-facet exclude-self).
- Counts in the Ravilo TV app (admin/config surface only).

## Files

- Frontend: `ui/Workbench.kt` (`wbItemsFor` count accessor + `.wb-vcount` badge in tag + generic + track
  chips + narrowed fetch in `openWorkbench`), `api/MediaApi.kt` (`NarrowedFacets` + `narrowedFacets`).
- Backend: `media/MediaStore.kt` (`buildMetaFacetsFrom`/`buildTrackFacetsFrom`/`facetsNarrowed`),
  `server/routes/MediaRoutes.kt` (`POST /api/media/facets`).
