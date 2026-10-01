# Phase R346 — Specials sort first on the series page

> Found 2026-10-01 in R343's dev review, by reading the code. Owner, the same day: spec it now, fix it later.

## Status

`Planned` — written 2026-10-01 (dev-authored), from R343's dev review (item 2) against `main` `44e26871`. Not built,
not checked against live data. Number given by the coordinator (Ravilo R345 is spoken for). **Amends** R150-2 (which
season a series opens on) and R84's watched counts. **Used by** R343 (*the episodes that count*). One FR (FR-R346-5)
fixes the episode-code spelling on the viewer's screens.

## What happens

On a series with specials, the page treats Season 0 as the first season:

- **It opens on Specials** when any special is unwatched, even if the viewer is halfway through Season 2.
- **Play can start a special.** With nothing in progress and no Continue watching entry (a series nobody has started,
  or one whose next-up Jellyfin can't name), the button reads *Play · E1* and plays the first unwatched special.
- **The watched counts include specials.** *{w} of {n} episodes watched* counts them, so a viewer who has seen every
  episode but not the specials never sees the series as finished.

## Why it happens

1. `DetailService.kt:135` builds the season list as `uniqueEpisodes.map { it.seasonNumber ?: 0 }.distinct().sorted()`.
   Season 0 sorts first, and an episode with **no** season number is filed under 0 as well.
2. Three client paths walk that list in order:
   - the opening season: the first season with an unwatched episode (`SeriesDetailScreen.kt:295–303`);
   - the Play fallback: `allEps.firstOrNull { not played }` (`:330`), where `allEps` starts with the specials;
   - `watchedCount` over every episode (`:313`), shown on the hero (`:470`).
3. A catalog row with no Jellyfin item gets a path as its id (`DetailService.kt:165`, `"${ep.path}#…"`). It can never
   be played or ticked, but it still counts as an unwatched episode.
4. The server already has the right rule for its own next-episode: `MediaStore.nextEpisodeAfter`
   (`MediaStore.kt:679–690`) skips season 0, needs a Jellyfin id, and orders by season, episode, part. The client
   never got the same rule.

## Requirements

**FR-R346-1 — The episodes that count.** One helper in `ravilo-ui`: an episode counts when its season index is ≥ 1 and
its id is a Jellyfin id (not a path). The watched counts, the opening season and the Play fallback use it, and so does
R343 (finished, the 9+ count, Start over, the shuffle set).

**FR-R346-2 — The server lists Specials last.** `getSeriesDetail` orders the seasons 1, 2, … and puts Season 0 after
the last one. Installed apps take the first unwatched season and episode in list order, so this alone fixes the
opening season and the Play fallback for them, with no app update. No field changes: `Season.index` still says 0.

**FR-R346-3 — The opening season and the Play fallback skip specials.** The page opens on the first season (index ≥ 1)
with an unwatched counted episode, else on Season 1 (R343), and on Specials only when the series has nothing else. The
Play fallback is the first unwatched counted episode. A special the viewer has **started** still resumes: an
in-progress special and R306's `continue_episode_id` keep their place ahead of the fallback.

**FR-R346-4 — The counts are counted episodes.** *{w} of {n} episodes watched* counts counted episodes only. The
Specials pill keeps its own ✓ / partial badge (R151), counted over its own episodes.

**FR-R346-5 — Episode codes are S01E05 on every viewer screen.** The house spelling is S01E05, and a range is
S01E01–E03. The viewer's screens print other spellings today:
- the series page and the player: `S1 · E5` and `S1E5` (`SeriesDetailScreen.kt:168–178`);
- the Continue watching badge on Home: `S1:E5` (`HomeScreen.kt:609`);
- Upcoming and its detail: `S1·E5` (`UpcomingScreen.kt:425`, `UpcomingDetailScreen.kt:191`);
- the next-up label the server builds for Continue watching: `S1E5` (`EpisodeSpan.kt:17`);
- the play push's kicker for a screen: `S1 · E5` (`MediaStore.kt:665`, `:689`).

Put one formatter in `shared` (season, episode, optional range end → `S01E05` / `S01E01–E03`) and use it at every
site above. The admin's own codes (already padded) are not in scope. The strings are display text, not wire values,
so an older app on a newer server just shows the new spelling.

## Out of scope

Where Specials are listed in the admin · a special's own placement between episodes (Jellyfin's *airs before* data) ·
R343's own changes.

## Acceptance

1. A series with three seasons and an unwatched Specials folder, watched to S02E04: the page opens on Season 2, and
   Play reads *Resume* or *Play · S02E05*, never a special.
2. A series nobody has started that has specials: the page opens on Season 1, and Play reads *Play · S01E01*.
3. A series with every episode watched and the specials not: *{n} of {n} episodes watched*, and R343 treats it as
   finished.
4. The Specials pill is the last pill, after the last season.
5. A special stopped half-way still offers *Resume* on the page.
6. Every code on the series page, the player, Home's Continue watching and Upcoming reads S01E05 / S01E01–E03.
