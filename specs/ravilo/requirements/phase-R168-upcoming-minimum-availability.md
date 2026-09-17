# Phase R168 — Upcoming: honor Radarr "Minimum Availability" for the "Missing" section (FR-RV-UP3)

> The **"Missing from your library"** section is over-reporting: it flags movies as *missing* using the
> wrong date. A film that was **in cinemas** months ago but has no physical/digital release yet — and
> whose Radarr **Minimum Availability** is **"Released"**, so Radarr itself will **not** grab it — still
> shows up as "Missing". The section should respect Radarr's own availability judgment (typically
> "Released"), so it only lists movies Radarr actually considers grabbable-but-not-arrived.

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — extends **R160** (built). Backend-only change to `UpcomingService` + one field
added to `ArrCalendarMovie`; **no app rebuild** (the feed's `missing` list simply gets shorter/correct).

## Problem
"Missing from your library" is meant to surface **stuck/failed grabs** — things Radarr should have
downloaded by now but didn't. Instead it lists any monitored movie whose *any* release date (including a
**theatrical** date) is in the past, regardless of whether Radarr would ever grab it yet. A movie set to
minimum-availability **"Released"** that has only been in cinemas is **not** something Radarr will fetch —
listing it as "Missing" is noise, and it's exactly what the user sees too much of.

## Root cause (verified in code)
`src/linuxX64Main/kotlin/dev/jellystructure/tv/UpcomingService.kt`:

- **Missing filter** (`:130-133`): `all.filter { it.date < today && it.status == MISSING }`.
- **`resolveStatus`** (`:136-141`): `MISSING` when not queued, not held, and `date < today`.
- **`pickMovieRelease`** (`:160-169`): builds `candidates` from **`digitalRelease`, `physicalRelease`,
  and `inCinemas` weighted equally**, filters to the calendar window, and — when none is future — picks
  the **most-recent past** date (`maxByOrNull`). That past date is what feeds the `date < today` MISSING
  check. So a movie whose only past date is a **cinema** date lands in "Missing".
- **Radarr's own availability signal is ignored.** `ArrCalendarMovie` (`arr/ArrClient.kt:332-346`) does
  **not** parse `minimumAvailability` (Radarr returns it; `ignoreUnknownKeys = true` at
  `OutboundHttp.kt:49` silently drops it), does **not** parse the movie `status`, and while it *declares*
  `isAvailable` (`:345`) that field is **dead** — a full-tree search finds it read nowhere. Radarr
  already computes "is this grabbable given its minimum availability?" and hands it to us; we throw it
  away and re-derive a worse answer from raw dates.

## Correct semantics (Radarr)
`minimumAvailability` is **per-movie** (returned in each movie object — not a global setting, so **no
config plumbing is needed**), one of `tba | announced | inCinemas | released | preDB`. For the common
default **`released`**, Radarr considers a movie grabbable once the **physical or digital** release has
passed (whichever is earlier, plus an optional global delay) — a **cinema-only** past date does **not**
satisfy it. The cleanest correct signal is Radarr's own computed **`isAvailable`** boolean, which already
bakes in each movie's `minimumAvailability`.

**Sonarr has no minimum-availability concept.** An episode is governed only by its **air date**; the
sole realistic refinement is a small **grace window** (an episode airs at time T but isn't posted/
grabbable for a few hours to ~1 day), so it shouldn't be called "missing" the instant the air date
passes.

## Requirements

### FR-R168-1 — Parse Radarr's availability signals
1. Add `minimumAvailability: String` to `ArrCalendarMovie` (default `""`) and **use** the already-declared
   `isAvailable: Boolean`. No new endpoint or query param — both come back by default on
   `/api/v3/calendar`; only the data class needs the field. Optionally parse the movie `status`
   (`announced`/`inCinemas`/`released`/…) if useful for display, but `isAvailable` is sufficient for the
   decision.

### FR-R168-2 — "Missing" respects minimum availability
2. A movie appears in **"Missing from your library"** only when **Radarr itself considers it available**
   (`isAvailable == true`) **and** we don't hold it **and** it's not queued/downloading **and** it's
   within the ~6-month lookback. Honoring `isAvailable` automatically respects whatever each movie's
   `minimumAvailability` is (a "Released" movie only in cinemas has `isAvailable == false` → correctly
   excluded).
3. **If deriving from dates instead of / in addition to `isAvailable`** (defensive, since `isAvailable`
   is a point-in-time snapshot from the last Radarr refresh): for `minimumAvailability == released`, treat
   "past its availability" as `min(physicalRelease, digitalRelease)` (whichever present) `< today` and
   **ignore `inCinemas`** for the missing decision; for `inCinemas`, `inCinemas < today`; for `announced`,
   available once any date is known; for `tba`/`preDB`, never eligible for the missing list. Prefer
   `isAvailable` as the primary gate; use the date derivation as a fallback when `isAvailable` is absent.

### FR-R168-3 — `pickMovieRelease` stops equating cinema with physical/digital for the missing check
4. The past-date pick that drives the MISSING decision must not let a **cinema-only** past date qualify a
   "Released"-minimum movie. Either scope `pickMovieRelease`'s past-date candidates by the availability
   rule in FR-R168-2, or gate the final MISSING classification on `isAvailable`/min-availability before
   the `date < today` test. (The **upcoming/forward** display can still show the cinema date as an
   informational release date — this requirement is specifically about what counts as *overdue/missing*.)

### FR-R168-4 — Sonarr episode grace window (refinement)
5. An upcoming **episode** should not be flagged **MISSING** the instant its air date passes — apply a
   small grace window (air date + N days, e.g. 1–2) before classifying it missing, since Sonarr exposes
   no per-episode availability flag and a just-aired episode legitimately isn't grabbable yet. Keep it a
   fixed, documented default (no new config axis unless a clear need emerges).

## Invariants
- **Backend-only.** `UpcomingService.build()` + one `ArrCalendarMovie` field; DTO/enum
  (`UpcomingStatus`) and client unchanged — the corrected `missing` list rides the existing feed.
- **Per-movie, no config.** `minimumAvailability`/`isAvailable` come from the Radarr movie object; we add
  **no** global availability setting to our config.
- **Radarr is the authority** on "grabbable-yet?" — we honor its `isAvailable`/`minimumAvailability`
  rather than re-deriving a stricter/looser answer from raw dates.
- **The ~6-month lookback bound and "not held, not queued" conditions are unchanged** — this narrows
  *which past-dated items* qualify, it doesn't change the window or the held/queued gates.
- **Server-authoritative** (R160) — the client still just renders `feed.missing`.

## Out of scope
- **Sonarr** minimum-availability (concept doesn't exist there) — only the FR-R168-4 grace window applies.
- A user-facing **"why isn't this here"** explanation on the card (the "Was due <date>" badge stays as-is
  for genuinely-missing items).
- Any **acquisition** action (request/retry) — R160 fence holds; this only fixes *classification*.
- Surfacing `minimumAvailability` as displayed text in the UI (no source/setting names leak — R160).

## Source references / backend anchors
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/UpcomingService.kt` — missing filter `:130-133`,
  `resolveStatus` `:136-141`, `pickMovieRelease` `:160-169`, window constants `:17-19`
  (`LOOKBACK_DAYS = 183`, `LOOKAHEAD_DAYS = 60`).
- `src/linuxX64Main/kotlin/dev/jellystructure/arr/ArrClient.kt` — `ArrCalendarMovie` `:332-346`
  (`isAvailable` declared `:345` but unused; `minimumAvailability`/`status` not parsed),
  `getRadarrCalendar` `:255-260`, `getSonarrCalendar` `:243-249`, `getQueue` `:206-229`.
- `src/linuxX64Main/kotlin/dev/jellystructure/OutboundHttp.kt:49` — `ignoreUnknownKeys = true` (why the
  fields arrive but are dropped).
- `shared/.../tv/Upcoming.kt` — `UpcomingStatus { MONITORED, DOWNLOADING, AVAILABLE, MISSING }`,
  `UpcomingFeed.missing` (documented as "released in the past, monitored, not imported" — the definition
  this phase tightens to "past its **minimum availability**").
- Config: `src/linuxX64Main/.../config/AppConfig.kt` — `ArrConfig` (connection only; confirms no
  availability setting to add).
- Related: **R160** (the "Missing" section this corrects), **Phase 54 / R48–R50** (Radarr integration,
  read-only fence).
