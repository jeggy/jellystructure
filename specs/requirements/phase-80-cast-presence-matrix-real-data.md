# Phase 80 — Cast presence matrix reflects real TMDB per-season data (FR-CC4)

**Status:** ✓ Done

> The Season-scope presence matrix shows **every actor present in every season and every
> episode**. It's not reading real data — `episodePresence` is never populated, and the UI treats
> "empty" as "present everywhere." Fix it to show actual per-season presence from TMDB.

## Problem

On the series-detail **Cast & crew** tab → **Season** scope, the presence matrix
(`renderSeasonMatrixHtml`, `MediaDetail.kt:2980`) is meaningless: in the "All seasons" view every
main-cast row shows a full episode count for every season, and in the per-season E1…En view every
cell is lit. So a guest who appeared in one S3 episode looks identical to a series lead.

### Root cause

`Person.episodePresence: Map<String, List<Int>>` (`Media.kt:24–43`) is **never assigned anywhere**
in the codebase — a grep finds the definition, three readers (matrix UI + `NfoWriter`), and **zero
writers**. The scanner populates `episodeCount` (TMDB `total_episode_count`) but leaves
`episodePresence` at its default `emptyMap()` (`Scanner.fetchCredits`, lines ~793–852).

The matrix then interprets empty as "present in all episodes":

```kotlin
// All-seasons view (MediaDetail.kt:3016–3022)
val count = if (presence.isNullOrEmpty() && p.episodePresence.isEmpty())
    item.episodes.count { it.seasonNumber == s }   // ← fabricates "in every episode of the season"
    else presence?.size ?: 0

// Per-season view (MediaDetail.kt:3051)
val present = p.episodePresence.isEmpty() || p.episodePresence[sn]?.contains(en) == true  // ← always true
```

### Why the data was missing

The two TMDB credit endpoints currently called don't carry per-season presence:
- `/tv/{id}/aggregate_credits` — series-wide `total_episode_count` per actor; **no** season or
  episode breakdown.
- `/tv/{id}/season/{n}/episode/{m}/credits` — episode **guest stars + crew** only; the response
  data class (`TmdbEpisodeCreditsResponse`) deserializes only `guest_stars`/`crew`, and TMDB's
  episode `cast` array is the series cast, not an episode-accurate appearance list.

**Crucially, TMDB *does* expose per-season credits — the code just doesn't call them:**
- `GET /tv/{id}/season/{n}/aggregate_credits` — the season's cast with `roles[].episode_count`
  **scoped to that season**. This is exactly the per-season granularity the matrix needs.
- `GET /tv/{id}/season/{n}/credits` — the season's flat cast list (presence, no counts).

## What TMDB can and cannot tell us (the design constraint)

- **Per-season presence + counts for recurring cast: AVAILABLE** via season `aggregate_credits`.
  An actor absent from a season simply isn't in that season's response → genuinely "not in S_n".
- **Per-episode presence for recurring cast: NOT AVAILABLE.** Episode credits only list
  episode-accurate **guest stars** (and crew); regular cast is not resolved per episode. Any
  per-episode grid for recurring cast would be fabricated.

So the matrix should be **accurate at season granularity for recurring cast**, and
**episode-accurate only for guest stars** (which we already fetch per episode).

## Goal

The Season scope shows real presence:
- Recurring cast: a per-season episode count (e.g. S1 = 10, S2 = 0, S3 = 8); seasons an actor
  isn't in render empty, not full.
- Guest stars: episode-accurate (unchanged — already correct from episode credits).
- No row claims presence the data doesn't support.

## Fix

### 1. TMDB client — add season aggregate credits

Add to `TmdbClient.kt`:
```kotlin
suspend fun getTvSeasonAggregateCredits(seriesId: Int, season: Int): TmdbAggregateCreditsResponse
// GET /tv/{id}/season/{n}/aggregate_credits  (reuses the existing TmdbAggregateCreditsResponse shape)
```
Each `TmdbAggregateCastMember.totalEpisodeCount` in that response = the actor's episode count **for
that season**.

### 2. Scanner — populate real per-season counts

In the series credit path (`Scanner.fetchCredits` for `!isMovie`), after the series-wide
`aggregate_credits`, fetch season aggregate credits for each season the item has and build a
**season → episode-count** map per cast member. Store it on `Person`.

Because TMDB gives season **counts**, not the specific episode numbers, repurpose the model to match
the data we actually have:
- Add `seasonEpisodeCounts: Map<String, Int>` to `Person` (season number → episode count in that
  season). This is the accurate, available data.
- Keep `episodePresence: Map<String, List<Int>>` only for **explicit operator overrides** (manual
  per-episode toggles), and **revise its default semantics**: empty now means *"use
  `seasonEpisodeCounts` / not present"* — never "present in all episodes."

(Pre-warm note: this adds one TMDB call per season at scan time. Bound it like other scan fetches
and respect the FD-budget invariant from Phase 78 — see [`phase-78`](phase-78-fd-exhaustion-crash.md).)

### 3. Matrix UI — render from real data

`renderSeasonMatrixHtml` (`MediaDetail.kt:2980`):
- **All-seasons view:** recurring-cast cell for season `s` = `p.seasonEpisodeCounts[s] ?: 0`; render
  `·` when 0. Remove the `episodePresence.isEmpty() → count all episodes` fallback entirely. Total
  column = sum of season counts (or `episodeCount`). Guest rows stay computed from
  `episodes…guestStars` (already accurate).
- **Per-season E1…En view:** keep the **guest-star** rows (episode-accurate). For recurring cast,
  do **not** fabricate per-episode dots — either:
  - **(Recommended)** show recurring cast at season granularity only (drop the recurring-cast
    per-episode dot grid; show a single "in this season: N eps" indicator), and reserve the
    per-episode toggle grid for manual operator overrides via `episodePresence`; or
  - keep the editable per-episode grid but **seed from season membership** (on for episodes in
    seasons the actor appears in, off elsewhere) and label it a manual refinement.
- Update the ⓘ help text to state the granularity honestly: *"TMDB resolves recurring cast per
  season and guest stars per episode; per-episode recurring-cast toggles are manual refinements."*

### 4. NFO inheritance stays consistent

`NfoWriter.buildEpisodeXml` filters inherited main cast by `episodePresence` (`NfoWriter.kt:137`).
Update the filter to the new semantics: an inherited cast member is written to episode `(s,e)` when
they appear in season `s` (`seasonEpisodeCounts[s] > 0`) unless an explicit `episodePresence[s]`
override excludes/includes the specific episode. This keeps `episodedetails.nfo` actor lists
plausible instead of "everyone in every episode."

## Scope

- `src/linuxX64Main/kotlin/dev/jellystructure/tmdb/TmdbClient.kt` — `getTvSeasonAggregateCredits`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` — per-season fetch + populate
  `seasonEpisodeCounts`.
- `src/commonMain/kotlin/dev/jellystructure/model/Media.kt` — add `Person.seasonEpisodeCounts`;
  revise `episodePresence` doc/semantics.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` — `renderSeasonMatrixHtml` (+ the
  episode-scope inherited-cast filter if it shares the default logic) + ⓘ help text.
- `src/linuxX64Main/kotlin/dev/jellystructure/nfo/NfoWriter.kt` — inheritance filter semantics.
- `design/app/wf.css` / `media.html` mockup — matrix styling already exists (`.matrix`, `.ndot`,
  `.dot`); update only if the legend/labels change.

## Migration

Existing stored items have empty `episodePresence` / no `seasonEpisodeCounts`. Until a
re-pull-from-TMDB / re-scan repopulates them, the matrix should degrade gracefully (recurring cast
shows `episodeCount` total but blank per-season, rather than the old all-on fabrication). A
**Re-pull from TMDB** on the detail page fills the new field.

## Verification

1. Pick a series where a character joins mid-run (e.g. a S3-only recurring actor). After re-pull,
   the All-seasons matrix shows them with counts only in their seasons; earlier seasons render `·`.
2. A guest who appears in one episode shows a single episode in the per-season E-grid and 1 in the
   season total — not the whole season.
3. Series leads show counts across all seasons matching TMDB.
4. Episode NFOs (`episodedetails.nfo`) list inherited cast consistent with season presence, plus
   that episode's guest stars.

## Non-goals

- Fabricating per-episode appearance data for recurring cast (TMDB doesn't provide it).
- Per-episode regular-cast accuracy beyond manual operator overrides.
