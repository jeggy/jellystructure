# Phase R166 — Upcoming: episode-level "Already available" + localized library titles (FR-RV-UP1)

> Two correctness fixes to the R160 Upcoming calendar, both stemming from the same shortcut: when a
> feed item matches something in our catalogue, the server currently borrows only the *fact of a match*
> (series exists) and none of the matched item's actual data. Result: **(a)** an upcoming episode is
> flagged **"Already available"** whenever we hold *the series*, even if we don't hold *that specific
> episode*; and **(b)** titles show the English Sonarr/Radarr string ("Chore Captain (DK)") instead of the
> localized title our library resolved and stored ("Mesterholdet").

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — extends **R160** (built). Backend-only change to `UpcomingService`; the client
renders whatever the feed says, so **no app rebuild is required** to fix either symptom.

## Problem

### A — "Already available" is series-level, not episode-level
The Upcoming calendar shows a green **"Already available"** pill on an upcoming *episode* whenever we
hold the *series* — regardless of whether we actually hold that season/episode. A viewer sees "S10E05 ·
Already available" for an episode that hasn't aired and isn't on disk. Movies are unaffected (a movie is
atomic — holding the movie *is* holding it).

### B — Titles are the English *arr string, not our localized title
The calendar shows Sonarr's/Radarr's own English title. Our library already stores the **localized**
title per the constitution's language-resolution algorithm (per-file audio-track order → first TMDB hit;
series use the majority of episodes' resolved languages), so a Danish-audio "Chore Captain (DK)" is stored
as `MediaItem.title = "Mesterholdet"`. The Upcoming feed throws that away and shows the *arr's English
title even for series we hold and have a localized title for.

## Root cause (verified in code)

`src/linuxX64Main/kotlin/dev/jellystructure/tv/UpcomingService.kt`, the Sonarr episode branch:

- **`:76`** `val matched = seriesByTvdb[series.tvdbId]` — matches only the **series** by TVDB id.
- **`:80`** `val itemId = matched?.let { it.jellyfinId ?: it.id }` — non-null iff the series is in the
  catalogue; this value is used for **both** navigation *and* (line 95) as the `held` flag.
- **`:84`** `title = series.title` — the Sonarr English title; `matched.title` is never consulted.
- **`:95`** `status = resolveStatus(date, today, itemId != null, queued != null)` — `held = itemId != null`.
- **`:136-141`** `resolveStatus(...)`: `queued → DOWNLOADING`, **`held → AVAILABLE`** (fires on series
  presence), `date < today → MISSING`, else `MONITORED`.

The specific episode numbers are already in hand at **`:87-88`** (`season = ep.seasonNumber`,
`episode = ep.episodeNumber`) but are never checked against the matched series' episodes. And the matched
`MediaItem` — whose localized `title`/`overview` and full `episodes` list are already decoded — is
discarded except for its id.

**The data to fix both is already loaded.** `MediaStore.allItems()`
(`src/linuxX64Main/.../media/MediaStore.kt:368-372`) decodes each whole `MediaItem` (episodes embedded in
the JSON blob) per row, and `UpcomingService.build()` already holds `matched` at
`UpcomingService.kt:76` (episodes) / `:109` (movies) **before** building the `UpcomingItem`. Episodes are
`MediaItem.episodes: List<Episode>` (`src/commonMain/.../model/Media.kt:137`), each with
`seasonNumber: Int?` (`Media.kt:76`) and `episodeNumber: Int?` (`Media.kt:77`); **presence in that list
IS "held"** (episodes are built only from files found on disk — `Scanner.kt` filesystem walk; no separate
"held" boolean, none needed). The localized title is `matched.title` (`Media.kt`), set at
`Scanner.kt:171` (movie: `details.title`) / `:401` (TV: `details.name`) from
`getMovieDetailsLocalized`/`getTvDetailsLocalized`.

## Requirements

### FR-R166-1 — Episode-level availability for the status pill
1. For an upcoming **episode**, "held" (→ `AVAILABLE` / "Already available") must mean **we hold that
   specific season+episode**, not merely the series:
   `matched != null && matched.episodes.any { it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber }`.
2. **Decouple `itemId` (routing) from `held` (status).** `itemId` stays series-level — a viewer who
   holds the series should still be routed to the **real series detail** page when they open the card
   (R160 §G: "any series already in the catalogue opens the real detail"), even for an episode we don't
   yet hold. Only the **status pill** becomes episode-specific. Concretely: keep `itemId` as it is; pass a
   *separate* episode-level `held` boolean into `resolveStatus`.
3. An upcoming episode of a series we hold, but whose specific episode we do **not** hold, therefore
   shows `MONITORED` (no pill) when future-dated, or `MISSING` when past-due — never "Already available".
4. **Movies are unchanged** — a movie match is already item-level-correct (atomic); `held = matched != null`
   stays right for the Radarr branch.

### FR-R166-2 — Localized library titles (and synopsis) for matched items
5. When a feed item matches a catalogue `MediaItem` (`matched != null`), the `UpcomingItem.title` must be
   the library's **localized** `matched.title` (e.g. "Mesterholdet"), not the *arr English string. Falls
   back to the *arr title (`series.title` / `mv.title`) only when there is **no** catalogue match (an
   item we don't hold yet — we have no localized title for it).
6. Apply the same "prefer the matched library value" rule to the **synopsis** (`matched.overview`) and,
   where present, the **genre** (`matched.genres.firstOrNull()`), so a held item's detail/card copy reads
   in the resolved language. The user's stated intent covers "title/description/etc.".
7. Non-language-dependent fields stay sourced from the *arr calendar: `date`, `time`, `season`, `episode`,
   `year`, `network`, `releaseType`, download `progress`, and the specific upcoming **episode title**
   (`ep.title` — often not yet localized anywhere, and we may not hold that episode to localize it).

## Invariants
- **Backend-only.** Both fixes live in `UpcomingService.build()`; the shared DTO and the client are
  unchanged, so no `ravilo-*` rebuild is needed — the feed simply carries corrected `status`/`title`.
- **Server-authoritative, no client derivation** (R160 invariant preserved) — the client never inspects
  episode lists or localizes titles; it renders the feed.
- **`itemId` remains the routing key** and stays series-level; navigation behaviour (R160 §G) is
  unchanged. Only the *pill* and the *displayed title/synopsis* change.
- **Fallback is the *arr value**, never blank — an unmatched item keeps its English title, exactly as
  today.

## Out of scope
- Localizing the **specific upcoming episode title** — we frequently don't hold that episode (nor does
  TMDB necessarily have a localized episode title yet); it stays the Sonarr string. A future phase could
  localize it when the episode later lands.
- The **poster/detail** gaps for not-held items — that is **R167**.
- Any change to how `MediaItem.title` itself is resolved during a scan (constitution language algorithm
  is unchanged).
- The **`scan_episode_cap`** interaction: when an operator sets a positive `behavior.scan_episode_cap`,
  `matched.episodes` stores only a sampled spread (`Scanner.kt:229-234`), so an episode-presence check can
  false-negative for capped series. Default is `0` (unlimited → all episodes stored), the documented
  default; capped setups accepting a possible "not available" under-report on the calendar are an accepted
  edge, not addressed here.

## Source references / backend anchors
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/UpcomingService.kt` — episode branch `:71-95`, movie
  branch `:101-120`, `resolveStatus` `:136-141`. This is the only file that changes.
- `src/commonMain/kotlin/dev/jellystructure/model/Media.kt` — `title`, `overview`, `genres`,
  `episodes: List<Episode>` (`:137`), `Episode.seasonNumber` (`:76`) / `episodeNumber` (`:77`),
  `originalTitle` (`:113`), `titlesByLang` (`:147`), `resolvedLanguage` (`:120`).
- `src/linuxX64Main/kotlin/dev/jellystructure/arr/ArrClient.kt` — `ArrCalendarEpisode` (`:307-319`:
  `seasonNumber`, `episodeNumber`, `title`, `airDate`), `ArrCalendarSeriesRef` (`:322-328`).
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt` — localized title set at `:171` (movie) /
  `:401` (TV); episode-cap sampling `:229-234`.
- `specs/constitution.md:86-97` — the language-resolution algorithm that makes `MediaItem.title` the
  localized title.
- Related: **R160** (the calendar this corrects), **R149** (`SonarrEnrichService` tvdb→catalogue mapping).
