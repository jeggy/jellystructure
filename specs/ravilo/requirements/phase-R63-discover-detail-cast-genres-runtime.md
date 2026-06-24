# R63 — Discover Detail: cast, genres & runtime

**Status:** Done
**Depends on:** R48 (Discover API), R49 (TV Discover screen)

## Goal

Enrich the Discover detail screen with the three metadata pieces users expect before requesting a title:
**cast** (who's in it), **genres** (what kind of film/series), and **runtime** (how long). All three are
fetched from TMDB using the title's existing `tmdbId` and attached to the `DiscoverDetail` payload.

## Functional requirements

| Field | Source | Display |
|---|---|---|
| Genres | TMDB `genres[]` | Comma-separated pill row below year/type line |
| Runtime | TMDB `runtime` (movie) or `episode_run_time[0]` (series) | Formatted `Xh Ym` or `Ym`; shown in same line as year/type |
| Cast | TMDB `/credits` `cast[]` top 5 by order | Horizontal scrollable row of name + character, below WhyTrending |

- All three fields are **nullable/empty** — detail screen renders gracefully when absent (TMDB API key
  missing, tmdbId not resolved yet, or TMDB returns no data).
- Cast is capped at **5 people** (top 5 by `order` field) to keep the layout compact on a 1080 p TV.
- Cast avatars use `profile_path` from TMDB (`w185` image size); if absent, show a placeholder circle
  with initials.
- Runtime for a series shows the **average episode runtime** (first element of `episode_run_time`), with
  a note "per episode".

## Data model — shared DTO (`Discover.kt`)

```kotlin
@Serializable
data class DiscoverDetail(
    val entry: ChartEntry,
    val acquisition: AcquisitionRecord,
    val sourceLabel: String,
    val attribution: String,
    // R63 enrichment — nullable; absent when tmdbId is null or TMDB call fails
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,           // minutes; episode runtime for series
    val cast: List<Person> = emptyList(), // top 5 by order
    val isSeries: Boolean = false,      // true → show "per episode" runtime note
)
```

`Person` is the existing shared model (`id`, `name`, `role`, `imageUrl`).

## Backend — TMDB enrichment

### `TmdbClient.kt` additions

Add `runtime` to `TmdbMovieDetails` and `episode_run_time` to `TmdbTvDetails` (both already returned
by the TMDB details endpoint — just not captured):

```kotlin
data class TmdbMovieDetails(
    ...,
    val runtime: Int? = null,           // ← add
)

data class TmdbTvDetails(
    ...,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(), // ← add
)
```

Add credit data classes and fetch functions:

```kotlin
@Serializable
data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String = "",
    val order: Int = 0,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
)

// In TmdbClient:
suspend fun getMovieCredits(tmdbId: Int): List<TmdbCastMember>
suspend fun getTvCredits(tmdbId: Int): List<TmdbCastMember>
```

Both call `/movie/{id}/credits` or `/tv/{id}/credits` with `api_key`; return top 5 by `order`; catch
and log any failure (returning emptyList on error — the detail must not fail).

### `TvRoutes.kt` — `/tv/discover/item` route

Add `tmdbClient: TmdbClient? = null` parameter to `tvRoutes()`. In the `/discover/item/{listId}/{rank}`
handler, after resolving the `ChartEntry`, fetch TMDB enrichment when `entry.tmdbId != null`:

```
val enrichment = entry.tmdbId?.let { id ->
    when (entry.kind) {
        MediaKind.MOVIE -> {
            val d = tmdbClient?.getMovieDetails(id)
            val c = tmdbClient?.getMovieCredits(id) ?: emptyList()
            Triple(d?.genres?.map { it.name }.orEmpty(), d?.runtime, c)
        }
        MediaKind.SERIES -> {
            val d = tmdbClient?.getTvDetails(id)
            val c = tmdbClient?.getTvCredits(id) ?: emptyList()
            Triple(d?.genres?.map { it.name }.orEmpty(), d?.episodeRunTime?.firstOrNull(), c)
        }
    }
}
```

Build `Person` list from cast (up to 5), using TMDB image base `https://image.tmdb.org/t/p/w185`.

### `Server.kt` wiring

Pass the existing `tmdbClient` instance to `tvRoutes(... tmdbClient = tmdbClient)`.

## TV UI — `DiscoverDetailScreen.kt`

### Metadata line update

Current: `"2023  ·  Movie"` (year + kind)  
After: `"2023  ·  Movie  ·  1h 52m"` — runtime appended when present. For series: `"1h  ·  per ep"`.

Genres displayed as a horizontal `LazyRow` of small read-only chips immediately below the metadata line.

### Cast section

New `CastSection` composable rendered below `WhyTrending` (with a `Spacer(16.dp)` between them):

```
── Cast ─────────────────────
[Avatar] [Avatar] [Avatar] [Avatar] [Avatar]
 Name     Name     Name     Name     Name
 Role     Role     Role     Role     Role
```

- Each cast item: `Column(width=72dp)` containing `RemoteImage(48dp circle)` + name (12sp, 1 line,
  ellipsis) + character (11sp, textSecondary, 1 line, ellipsis)
- `LazyRow` with 12dp spacing, `focusRestorer()`
- Each item is `focusable()` (D-pad navigable; no selection action needed)
- Section header "Cast" in 13sp SemiBold textSecondary

## Out of scope

- Director / crew — cast only for now
- Trailer playback
- Ratings (TMDB vote_average) — separate feature
- Cast click → person filmography page

## Phase index update

`specs/ravilo/requirements/README.md` — mark R63 ✓ Done.
