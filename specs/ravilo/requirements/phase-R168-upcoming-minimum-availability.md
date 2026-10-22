# Phase R168 — Upcoming: honor Radarr "Minimum Availability" for the "Missing" section (FR-RV-UP3)

> The **"Missing from your library"** section is over-reporting: it flags movies as *missing* using the
> wrong date. A film that was **in cinemas** months ago but has no physical/digital release yet — and
> whose Radarr **Minimum Availability** is **"Released"**, so Radarr itself will **not** grab it — still
> shows up as "Missing". The section should respect Radarr's own availability judgment (typically
> "Released"), so it only lists movies Radarr actually considers grabbable-but-not-arrived.

**Status:** Planned — extends **R160** (built). Backend-only change to `UpcomingService` + one field
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
   *Reversed by the 2026-10-22 amendment below: the forward display no longer shows a date Radarr won't act on.*

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

## Amendment 2026-10-22 (owner, DEB-2098) — every film is dated by when Radarr will act on it

Task: DEB-2098

> Owner, 2026-10-22: *"In Radarr it's configured for release, but in Ravilo the cinema is shown, which means it
> won't be available on the date said in Ravilo."* Decided on the task's card the same day: a film with only a
> cinema date is shown at Radarr's estimate, labelled **Expected**; and Ravilo shows **no cinema date anywhere**
> for a film whose availability is a later date — one date per film, the one it can arrive.

### What was wrong

`pickMovieRelease` shows the soonest of digital/physical/cinema that is today or later, else the latest past one.
It never asks which date Radarr acts on. Measured on the live Radarr (6.4.4) on 2026-10-22, 412 of 423 films on
Minimum Availability *Released*:

- a *Released* film still in cinemas sits on Coming Soon at its **cinema** date, labelled *In Cinemas*, while
  Radarr waits for the digital/physical date (10 films);
- a film whose digital date has passed sits at its future **physical** date (12 films). Held ones show
  *Available* on a future day. An unheld, unqueued one that Radarr has been searching for since its digital date
  is shown as upcoming instead of in *Missing* — a breach of FR-R168-2 above;
- a *Released* film whose cinema date has passed and that has no digital/physical date yet appears **nowhere**:
  not upcoming, not missing (18 films).

### What Radarr already sends

Every movie on `/api/v3/calendar` carries **`releaseDate`**: the date Radarr treats as "available" under that
film's own `minimumAvailability`. Checked against all 423 films; it is exactly the rule FR-R168-2.3 spelled out:

| `minimumAvailability` | `releaseDate` |
|---|---|
| `tba`, `announced` | the earliest of cinema, digital, physical |
| `inCinemas` (cinema date known) | the cinema date |
| `released`, `preDB`, and `inCinemas` without a cinema date | the earlier of digital and physical; if neither exists, **cinema + 90 days** (Radarr's estimate); if no cinema date either, absent |

It does **not** include Radarr's global Availability Delay, and neither does Ravilo: the date on screen stays a
true fact ("Digital Release, 18 Dec"), not a computed one.

### Requirements

**FR-R168-5 — A film's date is the day Radarr will act on it.** `ArrCalendarMovie` gets `releaseDate: String?`.
`UpcomingItem.date` for a movie is `releaseDate`'s calendar date (`take(10)`). When Radarr does not send it,
derive it from the table above (the same rule, so an older Radarr gives the same answer). A film with no date at
all is left out, as today. The calendar window, the forward/missing split (`date >= today || DOWNLOADING` /
`date < today && MISSING`), the held/queued gates and `resolveMovieStatus` stay as they are — only the date fed
into them changes. So *Missing*'s "Was due {date}" also shows this date. `pickMovieRelease`'s "soonest of three"
is removed.

**FR-R168-6 — The label is the release that date is.** `releaseType` is the one of `digital` ▸ `physical` ▸
`cinema` (that order on a tie) whose calendar date equals FR-R168-5's date. `cinema` therefore only remains where
the cinema date *is* the date Radarr acts on (`inCinemas`, or `tba`/`announced` with cinema earliest). When the
date equals none of the three — Radarr's cinema + 90 days estimate — `releaseType` is **null**, so an installed
app shows the date with no label and its detail page drops the *Release type* row (both already handle null).

**FR-R168-7 — An estimate says so on the wire, additively.** `UpcomingItem` gets
`@SerialName("date_estimated") val dateEstimated: Boolean = false`, true exactly when FR-R168-6 left `releaseType`
null because the date is Radarr's estimate. An app that predates it ignores the field. The wire baseline
(`WireBaseline.kt`) is regenerated for the new field and nothing else.

**FR-R168-8 — The app labels an estimate "Expected".** Where an app draws the release-type label — the Coming
Soon card's corner and subtitle (`UpcomingScreen.releaseTypeLabel`) and the detail page's *Release type* row
(`UpcomingDetailScreen.ScheduleGrid`) — an item with `dateEstimated` reads **Expected**. New string
`up.release_expected`: English `Expected`, with a `note` saying it labels a film whose release date is Radarr's
guess and may move; Danish `Forventet` and Faroese `Væntað`, each with a `note` marking it a draft to review.
No layout, focus or size change on any client; the label sits in the slot the release type already uses.

**FR-R168-9 — No cinema date where Radarr waits for a later one.** No surface — list, detail, or any new field —
shows a film's cinema date when FR-R168-5's date is a different one. (Owner decision; nothing is added to carry it.)

### Effect (same live data, 2026-10-22)

Films on Coming Soon go from 26 to 10: 5 at a real digital date, 5 at Radarr's estimate (labelled *Expected* on
an updated app). Films still in cinemas whose real date is beyond the 60-day lookahead leave until they come
inside it. Held films whose digital date has passed leave — they have arrived. The unheld, unqueued, available
film moves to *Missing*. Films that had vanished come back at their estimate once it is inside the window.

### Slices

1. **Backend** — FR-R168-5, -6, -7, -9. Reaches installed apps on deploy, no app release.
2. **Client** — FR-R168-8. Blocked by 1.

### Acceptance

1. Unit (`UpcomingService`, fake calendar rows, no real titles), today = 2026-10-22, window 2026-04-22…2026-12-21:

   | `minimumAvailability` | cinema | digital | physical | `releaseDate` sent | held / queued / `isAvailable` | Expected item |
   |---|---|---|---|---|---|---|
   | released | 2026-12-03 | 2026-12-18 | — | 2026-12-18 | no / no / false | upcoming, `2026-12-18`, `digital` |
   | released | 2026-11-05 | — | — | 2027-02-03 | no / no / false | absent (date outside window) |
   | released | 2026-08-13 | — | — | 2026-11-11 | no / no / false | upcoming, `2026-11-11`, type null, `date_estimated` true |
   | released | 2026-08-20 | 2026-09-29 | 2026-11-09 | 2026-09-29 | no / no / true | **missing**, `2026-09-29`, `digital` |
   | released | 2026-07-24 | 2026-07-25 | 2026-11-02 | 2026-07-25 | yes / no / true | in neither list |
   | inCinemas | 2026-10-30 | 2026-12-10 | — | 2026-10-30 | no / no / false | upcoming, `2026-10-30`, `cinema` |
   | announced | 2026-11-20 | — | — | 2026-11-20 | no / no / false | upcoming, `2026-11-20`, `cinema` |
   | released | 2026-08-13 | — | — | *(absent)* | no / no / false | same as row 3 (derived) |

2. Wire: an item with `date_estimated` true decodes in the current `ravilo-ui`; an item without the field decodes
   with `dateEstimated == false`; the baseline diff is that one field.
3. Device (after slice 2 is released and the backend deployed): on Coming Soon an estimated film reads *Expected*
   on the card and in its detail page's *Release type* row, in English, Danish and Faroese; an app built before
   slice 2 shows the same film with its date and no label, and no crash.
4. No film on Coming Soon reads *In Cinemas* unless its Radarr Minimum Availability is *In Cinemas*, *Announced*
   or *TBA*.
