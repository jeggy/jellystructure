# Phase 57 — Chart / Discover feed ingestion (vendor-abstracted; Netflix via Tudum first) (FR-CH1)

**Status:** Planned
**Depends on:** Phase 2 (TMDB matching), Phase 14 (SQLite stores)

## Goal

Ingest third-party "Top 10"-style popularity charts so Ravilo (R48/R49) can show a **Discover / Top
10** surface. First provider is **Netflix, scraped via Tudum**; the design must keep room for **other
vendors later**, so everything goes through a provider abstraction and a normalized model — no
Netflix/Tudum specifics leak past the ingestion layer.

This phase is **ingestion + normalization + library/TMDB matching only**. The TV-facing API, per-user
list selection, and the request action live in R48; the request *pipeline* is Phase 56.

## Provider abstraction

```kotlin
interface ChartProvider {
  val id: String            // "netflix"
  val displayName: String   // "Netflix"
  val attribution: String   // "Tudum"
  fun availableLists(region: String): List<ChartListSpec>
  suspend fun fetch(spec: ChartListSpec): List<RawChartEntry>
}
```
- `ChartProvider` is the only place a vendor's quirks live. Adding Disney+/Max later = a new provider
  + registration; nothing downstream changes.
- A `ChartRegistry` holds enabled providers; `netflix` (Tudum) is the only one wired now.

## The lists (Netflix via Tudum)

`ChartListSpec { id, providerId, title, scope, category, metric, region? }`:

| List id | scope | category | metric | Notes |
|---------|-------|----------|--------|-------|
| `mov-<region>` | `country` | `film` | `rank` | Top 10 Movies in &lt;country&gt; — **rank only, no views** |
| `tv-<region>` | `country` | `series` | `rank` | Top 10 TV Shows in &lt;country&gt; — same DK filter, category=TV (free second row) |
| `mov-global` | `global` | `film` | `views` | Global Top 10 Movies — **real hours viewed + view counts** |
| `noneng` | `global` | `film` | `views` | Top 10 Non-English Films (global English/non-English split) |
| `alltime` | `alltime` | `film` | `views91` | Most Popular of all time — ranked by first-91-day views |

**Hard caveats (encode in the model, surface in UI):**
- **Country feeds are ranking-only.** No hours/views for a country — so a country `ChartEntry` carries
  `rank` + `weeksOnChart` but `views = null`. Never fabricate a view count for a country row.
- **Views/hours exist only at `global` + `alltime`.** Those entries carry `views`/`hoursViewed`.
- A `tv-<region>` list is essentially free given the country filter (same feed, `category = TV`).

## Trend / history (5 years of weekly snapshots)

Tudum exposes weekly history. Persist snapshots so we can compute, per entry, trend badges that a
single snapshot can't give:
- `weeksOnChart`, `isNew` (new this week), `trend` (`up`/`down`/`same`), `peakRank`,
  `weeksAtNo1`, plus year-end "best of" potential. Stored as `chart_history(list_id, item_key,
  week, rank, views?)`.

## Normalized model

```kotlin
data class ChartEntry(
  val listId: String, val rank: Int,
  val title: String, val year: Int?, val kind: MediaKind,
  val tmdbId: Int?,          // resolved at ingest via TMDB search (Phase 2 matcher)
  val itemId: String?,       // set when the title is already in the Jellyfin library → status=available
  val weeksOnChart: Int, val trend: Trend, val isNew: Boolean,
  val views: String?,        // null for country scope
  val backdropPath: String?, val overview: String?, val trailerKey: String?,
)
```
- **Library match:** at ingest, each entry is matched (by tmdbId, then title+year) against the media
  store; a hit sets `itemId` → the entry is `available`. Misses are request candidates (status comes
  from Phase 56 at read time, not stored here).
- **TMDB enrich:** backdrop, overview, and a trailer key (`/movie/{id}/videos`) are pulled so R49's
  detail page + trailer work without a second round-trip.

## Ingestion job

- `ChartIngestService.refresh(region)` — scheduled (default every 24 h; charts update ~weekly but
  daily is cheap) + on-demand. For each enabled provider × `availableLists(region)`: `fetch` →
  normalize → TMDB-resolve → library-match → upsert `chart_entry` + append `chart_history`.
- Region(s) come from config; matching/enrich reuse existing TMDB client + media store.
- Failures are per-list and non-fatal (a vendor layout change degrades one row, not the feature);
  logged to the Phase 17 activity log.

### Config (additive)
```toml
[discover]
enabled = true
providers = ["netflix"]   # registry ids; add "disney"/"max" later
regions = ["DK"]          # country charts to ingest
refresh_hours = 24
```

## Internal API
- `GET /api/discover/lists?region=DK` → available `ChartListSpec`s (for the Ravilo config editor, R50).
- `GET /api/discover/list/{id}` → resolved `ChartEntry[]` (consumed by R48; not a public TV route).

## Non-goals / invariants
- **No vendor specifics past the provider.** Downstream code only sees `ChartProvider` + `ChartEntry`.
- **Respect the data shape** — country = rank-only; views only global/all-time. The model forbids a
  country `views`.
- **No acquisition here** — matching tells us available vs candidate; requesting is Phase 56, exposed
  via R48.

## Mockup
`design/ravilo/ravilo-data.js` `discover` block (sources + 5 lists incl. the rank-only country rows and
view-bearing global/all-time rows) is the shape this normalizes to.
