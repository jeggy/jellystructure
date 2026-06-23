# Phase 57 — Chart / Discover feed ingestion (vendor-abstracted; Netflix via Tudum first) (FR-CH1)

**Status:** Planned
**Depends on:** Phase 2 (TMDB matching), Phase 14 (SQLite stores)

## Goal

Ingest third-party "Top 10"-style popularity charts so Ravilo (R48/R49) can show a **Discover / Top
10** surface. First provider is **Netflix's official Tudum data feeds**; the design must keep room for
**other vendors later**, so everything goes through a provider abstraction and a normalized model — no
Netflix/Tudum specifics leak past the ingestion layer.

### Source of truth: 3 stable TSV feeds (not HTML scraping)
Netflix publishes its engagement charts as **downloadable TSV files** with a schema unchanged since
2021 — our **5 lists are slices of 3 files**, not 5 scrapes:
- `all-weeks-countries.tsv` — per-country weekly **rank** (no views). Source for `mov-<region>` +
  `tv-<region>` (filter `country_iso2` + `category` = Films/TV).
- `all-weeks-global.tsv` — global weekly **hours viewed + views**, split Films (English) / Films
  (Non-English) / TV. Source for `mov-global` + `noneng`.
- `most-popular.tsv` — all-time **first-91-day views**. Source for `alltime`.

So `fetch()` is a TSV download + parse + filter, not a DOM scrape — robust to site redesigns. Record the
real feed URLs in the provider. **Gate refresh on the `week` column changing** (the files update weekly;
a daily poll of an unchanged week is wasted work — compare the latest `week` to what we ingested).

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
| `mov-global` | `global` | `film` | `views` | Global Top 10 Movies — **English** films; real hours viewed + view counts |
| `noneng` | `global` | `film` | `views` | Top 10 **Non-English** Films (the global feed's English/non-English split) |
| `alltime` | `alltime` | `film` | `views91` | Most Popular of all time — ranked by first-91-day views |

(`tv-global` is **deliberately omitted** — the country TV row covers the user's locale; a global TV row
can be added later as another slice of `all-weeks-global.tsv` if wanted.)

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
  val tmdbId: Int?,          // resolved at ingest via TMDB title(+kind) search; null if unresolved
  val tmdbConfidence: Float, // match score; low scores flagged for the admin match-picker
  val itemId: String?,       // set when the title is already in the Jellyfin library → status=available
  val weeksOnChart: Int, val trend: Trend, val isNew: Boolean,
  val views: String?,        // null for country scope
  val backdropPath: String?, val overview: String?,
)
```
- **TMDB resolution is title-search, not id/year lookup.** The TSV carries only `show_title` (+ week +
  rank/views) — **no tmdbId and no year**, so year cannot be a *matching input* (it's only known *after*
  resolution). Resolve by **title + kind** search and take the best hit, storing a `tmdbConfidence`.
- **Mismatch recovery (reuse Phase 32).** Foreign/ambiguous titles will mis-resolve; a wrong hit
  otherwise shows the wrong poster/overview and **re-asserts on every refresh**. So: low-confidence
  matches are surfaced to the admin via the **Phase 32 TMDB match-picker**, and a **persisted override
  map** `chart_override(provider, title, kind → tmdbId)` (keyed on `kind` too, so a movie and a series
  sharing a title don't collide) is applied first on every ingest and **survives re-ingest** (an admin
  correction sticks). Unresolved entries still appear (rank + title) but aren't
  requestable until matched.
- **Library match:** after TMDB resolution, each entry is matched against the media store (by tmdbId);
  a hit sets `itemId` → the entry is `available`. Misses are request candidates (live status comes from
  Phase 56 at read time, not stored here).
- **TMDB enrich:** backdrop + overview are pulled so R49's detail page works without a second
  round-trip. (No trailer key — trailers are out of scope for now.)

## Ingestion job

- `ChartIngestService.refresh(region)` — scheduled (default daily, but **only re-parses when the feed's
  `week` advances** — weekly data) + on-demand. For each enabled provider: download the relevant TSV(s)
  once, slice into `availableLists(region)`, normalize → apply override map → TMDB-resolve (record
  confidence) → library-match → upsert `chart_entry` + append `chart_history`.
- Region(s) come from config; matching/enrich reuse existing TMDB client + media store.
- Failures are per-list and non-fatal (a parse error in one slice degrades that row, not the feature);
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
