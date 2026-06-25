# Phase 51 — Tag population & merge lifecycle (FR-TG1)


## Problem
Item tags were **never populated**. The scan pipeline read no tags from Jellyfin, so `MediaItem.tags`
was always empty unless a user added one by hand on Media Detail. As a result the Metadata → Tags
"And the rest" section, the Library tag filter, and `meta-facets` tags were all empty in practice.

Three breaks in the chain:
- `JellyfinClient` never requested the `Tags` field (`Fields=…` omitted it).
- `JellyfinItem` had no `tags` slot, so tags would have been dropped at deserialization anyway.
- `Scanner.scanMovie`/`scanSeries` built `MediaItem` without a `tags` argument → defaulted to `[]`.

Separately, the **Phase 19 §15 tag-merge invariant was never implemented**: `syncMovie`/
`syncSeriesEpisodes`/`rescanMetadata`/`rescanFromJellyfin` carried `item.tags` through verbatim (via
`copy()`), and a **full re-scan wiped all tags including JS tags** — `runScan` persists raw scanned
items (`tags=[]`) through `addOrUpdate` + a final `update()` that `deleteAll()`s and re-inserts, neither
of which preserved JS tags. That violated constitution invariant #6.

## Decisions
- **Tag source.** Non-JS tags are sourced externally; **TMDB keywords** (`/movie/{id}/keywords`,
  `/tv/{id}/keywords` — movie nests under `keywords`, TV under `results`) are the `tmdbSourcedTags`
  the Phase 19 §15 merge always anticipated ("future-proof"). Full scans source non-JS tags from
  Jellyfin's `Tags` field.
- **JS tags = names in the `js_tags` store** (`JsTagStore.nameSet()`); they always survive every path.
- **Scan = replace, sync = union** (the three rules below).

## Behavior (the tag lifecycle)
| Action | Resulting `item.tags` |
|--------|-----------------------|
| Full scan / auto-scan | Jellyfin `Tags` **+** existing JS tags (Jellyfin authoritative for non-JS) |
| Re-pull from TMDB (`/sync`, `/repull`) | TMDB keywords **+** existing JS tags (drops stale Jellyfin-only tags) |
| Re-pull from Jellyfin (`/repull-jellyfin`) | Jellyfin `Tags` **∪** everything existing (additive merge) |

- `…/seasons/{n}/sync` (`syncSeason`) re-syncs one season's episodes only, not series-level metadata —
  it leaves `tags` untouched (preserves verbatim) rather than stripping non-JS tags.
- The manual `PATCH /api/media/{id}/metadata` path (`updateOne`) is **not** JS-merged — a user removing
  a JS tag by hand must stick.

## Implementation
- `auth/Models.kt` — `JellyfinItem` += `@SerialName("Tags") val tags: List<String>`.
- `auth/JellyfinClient.kt` — `Tags` added to `Fields=` in `getItems`/`getItemsByParent`/`getItem`.
- `tmdb/TmdbClient.kt` — `getMovieKeywords`/`getTvKeywords` (+ `TmdbKeyword`/`*KeywordsResponse` DTOs).
- `media/Scanner.kt` — injected `JsTagStore`; scan sets `tags = jItem.tags`; TMDB re-pull paths fetch
  keywords + apply `mergeRepullTags` (TMDB keywords + JS-kept); `rescanFromJellyfin` unions
  `fresh.tags ∪ existing.tags`.
- `media/MediaStore.kt` — injected `JsTagStore`; `preserveJsTags()` applied in **both** `addOrUpdate`
  (auto-scan path) **and** `update` (the post-scan `deleteAll`+reinsert) so a re-scan keeps JS tags.
- `Main.kt` — `JsTagStore` constructed before `MediaStore`/`Scanner` and passed to both.

## Notes
- NFO writing already emits `<tag>` elements for movie/tvshow, so populated tags round-trip to Jellyfin.
- TMDB keywords are not language-localized (canonical English names) and can be numerous — all are kept.
- Upholds constitution invariant #6 and completes Phase 19 §15.
