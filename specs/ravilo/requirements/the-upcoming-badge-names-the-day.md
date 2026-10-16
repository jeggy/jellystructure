# The upcoming-episode badge names the day it airs, not "Soon"

Task: DEB-1478

> Owner, 2026-10-16 (DEB-1478), with a screenshot of the *Newly Added — Series* row, three tiles reading
> `Soon • S01E07`, `Soon • S01E04` and `Soon • S22E08`: *"Let's change this text. So instead of "Soon" it will say the
> day instead. So an example could be "Thu • S01E07". Remember it should work across all languages."*

## What the badge does today

R149 put a corner badge on the poster of a continuing series that has a next episode scheduled in Sonarr. R249 and
the corner table in `Tile.kt` (`resolveCornerBadges`) decide which corner it takes. R279 moved its text into the
string table as `tile.badge_soon`, which reads `Soon • {label}` in English, `Snart • {label}` in Danish and
`Skjótt • {label}` in Faroese.

The server knows the day. It does not send it.

- `SonarrEnrichService` picks the next monitored episode that has no file yet, airing today or later, and stores
  `MediaItem.sonarrNextAiringDate = airDateUtc.take(10)`. That is the **UTC calendar date**. The time of day is
  thrown away.
- `HomeFeedService`, `BrowseService` and `DetailService` build `MediaCard.upcomingEpisode` (`upcoming_episode` on
  the wire) as the bare code `S01E07`. No date travels with it.
- The tile's text is `str("tile.badge_soon", label)`. Every client that draws a poster tile gets it from the same
  `ravilo-ui` `Tile.kt`: Android TV, phone, web, Mac and Linux.

Two consequences shape this spec:

1. **The day has to be added to the wire next to the code, not merged into it.** An installed app that predates this
   change reads `upcoming_episode` and wraps it in its own `Soon • …`. If the server sent `Thu • S01E07` in that
   field, an old app would draw `Soon • Thu • S01E07`. The constitution and `CLAUDE.md` require wire changes to be
   additive.
2. **The day has to be worked out on the viewer's device, from the actual airing time.** The stored UTC date is the
   wrong day for anyone whose clock is not on UTC near midnight. An episode at 01:00 UTC on Friday airs on Thursday
   evening in New York. The server serves users outside the household, so it cannot assume one time zone.

## Prior art in this codebase

- **R270 (FR-R270-4)**, the Screens sheet's *"last seen {when}"*: a weekday inside the last seven days, a date
  beyond that. This spec uses the same rule, looking forward instead of back.
- **R279**: the three-letter weekdays `wd.abbr.mon`…`wd.abbr.sun` and months `month.jan`…`month.dec` are already
  translated into Danish and Faroese for the Coming Soon calendar (`UpcomingScreen.weekdayAbbrev` /
  `monthAbbrev`). This spec reuses them, so the change works in every language the string table has. A language
  added later inherits it by translating keys it would translate anyway.

## Scope

The poster tile's upcoming badge only, on every client that draws `Tile`. **Not in scope:** the series detail
page's *next airing* line (`SeriesDetail.nextAiring`), the Coming Soon calendar, and the corner rules from R249.
The Coming Soon calendar works out "today" in UTC (`UpcomingScreen.todayUtc()`), not in the device's time zone.
That is noted here as an observation only. It is a separate fix if the owner wants one.

## Requirements

**FR-1 — The server keeps the airing instant.** `MediaItem` gets `sonarrNextAiringUtc: String?`. It holds Sonarr's
`airDateUtc` for the episode that `SonarrEnrichService` already chooses, as Sonarr sends it (ISO-8601, UTC). It is
written and cleared together with `sonarrNextAiringDate`, including the `ended` branch that clears it.
`sonarrNextAiringDate` stays exactly as it is, because `FreshnessFilter` and `DetailService` read it. `MediaItem` is
stored as JSON, so this needs no migration. Rows written before this change have it as `null` until the next Sonarr
enrichment pass.

**FR-2 — The card carries it, additively.** `MediaCard` gets `@SerialName("upcoming_air_utc") val upcomingAirUtc:
String? = null`. It is set wherever `upcomingEpisode` is set (Home, Browse, Detail's related cards), under the same
condition, from FR-1's field. `upcoming_episode` keeps its current value, the bare `S01E07`. An app that predates
this ignores the new field and keeps drawing `Soon • S01E07`.

**FR-3 — The badge names the day, in the viewer's time zone.** The tile converts `upcomingAirUtc` to a local date in
the device's own time zone and compares it with today's local date:

| Air date (local) | Badge |
|---|---|
| today, or 1–6 days ahead | `{weekday} • {code}`, e.g. `Thu • S01E07` (`wd.abbr.*`, drawn as the table writes it, not upper-cased) |
| 7 or more days ahead | `{day} {month} • {code}`, e.g. `12 Nov • S03E01` (`month.*`) |
| before today, missing, or unparseable | `Soon • {code}`, unchanged (`tile.badge_soon`) |

The last row is the safety net. A row the enrichment pass has not refreshed yet, or a server that predates FR-2,
still gets today's badge and never shows a day that has already passed.

**FR-4 — Every word comes from the string table.** Add two keys to `i18n/en.json`, each with a `note`, and draft
Danish and Faroese values marked for review the way R279's drafts are:

- `tile.badge_upcoming`: `{when} • {label}`. `{when}` is FR-3's weekday or date, and `{label}` is the episode code.
  A language can reorder it.
- `date.day_month`: `{day} {month}`. `{day}` is a plain number and `{month}` is `month.*`. A language can reorder it
  or add punctuation (Danish `12. nov`).

No weekday or month name is written in Kotlin. A language that has not translated `tile.badge_upcoming` falls back
to the English template around its own translated weekday, which still reads correctly.

**FR-5 — The badge fits where `Soon` fitted.** The badge keeps R149's style and R249's corner rules, and keeps
ellipsizing as it does today. A three-letter weekday is no wider than `Soon`/`Snart`/`Skjótt`. A date is a few
characters wider, and the existing ellipsis already covers that.

## What the code makes cheap or expensive

Cheap overall. FR-1 is one field and one line in `SonarrEnrichService`. FR-2 is one field and three call sites,
each next to the existing `upcomingEpisode =`. FR-3 is one small pure function, roughly `upcomingWhen(airUtc,
now, zone): When?`, that `Tile.kt` calls before `str(...)`. `kotlinx-datetime` is already in `ravilo-ui`
`commonMain`. Putting the logic in a pure function makes FR-3's table unit-testable with a fixed clock and zone,
the same way `TileCornerBadgesTest` tests the corner rules.

The new field is filled by the next Sonarr enrichment pass after the backend deploy. Until then every badge keeps
saying `Soon`, so the deploy needs no backfill and has nothing to order.

## Acceptance

1. A unit test of FR-3's function, with a fixed `now` and zone, covers: same day; +1; +6; +7 (date, not weekday);
   yesterday (`Soon`); `null` and garbage (`Soon`); and an instant of `…T01:00:00Z` on a Friday, which is Thursday
   in `America/New_York` and Friday in `Atlantic/Faroe`.
2. A backend test shows `upcoming_air_utc` present exactly when `upcoming_episode` is, and `upcoming_episode`
   unchanged.
3. On a device, after a deploy and one Sonarr enrichment pass: a continuing series airing this week shows
   `<weekday> • SxxExx` in English, and in Danish or Faroese with that language's weekday (`Tor`/`Hós` for
   Thursday). An app built before this change, pointed at the new backend, still shows `Soon • SxxExx`.
