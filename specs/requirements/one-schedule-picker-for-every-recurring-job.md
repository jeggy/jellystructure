# One schedule picker for every recurring job: cron, chosen weekdays, monthly, yearly and every N of any unit

Task: DEB-4387

> Owner, 2026-11-12 (DEB-4387), on a screenshot of an auto-fetcher's *Check: Weekly on · Wed · 04.00*: *"I want to
> have more flexibility on the schedule. Let's make sure we are using shared code in regards of schedule pickers
> around the whole jellystructure platform. I want cron, every week, every year, every 10 years, every month, every
> monday and tuesday etc to be possible"*

## Scope

Backend and admin. No Ravilo client changes, and no change to anything a Ravilo client reads.

It replaces every schedule picker in the admin with one shared control, backed by one shared schedule type and one
evaluator. It amends the pickers promised in `processing-runs-as-nine-features.md` (FR-3.2, FR-3.3 and its
2026-10-29 amendment), `ai-re-rank-is-switched-per-viewer-and-recommendations-run-on-a-schedule-you-pick.md` (FR-3.1),
`auto-fetch-turns-a-series-page-into-a-paced-scheduled-download.md` (FR-6.2) and
`phase-166-custom-validated-cron-schedule.md` (FR-166-4's horizon). Each carries a dated amendment pointing here.

## Investigation findings

What exists on `main` at `ce9cc552`:

| Setting | Stored as | Admin control | When it is due |
|---|---|---|---|
| *Find new & changed* | 5-field cron (`Processing.kt:38`) | *Every N hours* · *Daily at* · *Weekly on* · *Custom* (`SettingsProcessing.kt:376-446`) | cron, first fire after **now** (`Main.kt:1013`) |
| Auto-fetcher *Check* | its own words: `every6h`, `daily@HH:MM`, `weekly@D@HH:MM` (`Autofetch.sq:16`, `AutofetchStore.kt:277-316`) | *Every 6 h* · *Daily at* · *Weekly on*, one weekday (`AutofetchHtml.kt:214-223`) | converted to cron on the server, first fire after the last run, 6 h floor |
| Music, Audiobooks | `hourly` / `daily` / `weekly` (`Processing.kt:104`, `:121`) | a select (`SettingsProcessing.kt:575`) | last run + interval |
| Recommendations, Suggestions | `daily` / `weekly` (`AppConfig.kt:426-431`) | a select (`SettingsProcessing.kt:557`) | last build + interval. DEB-2827/DEB-2830 (backlog) would give each a cron picker of its own. |
| Live TV guide refresh | minutes, 15–1440 (`LiveTvStore.kt:41`) | 15 min · 1 h · 6 h · Daily (`LiveTv.kt:100-103`) | cache age |

There is one cron parser, in `commonMain` (`cron/CronExpr.kt`), and one evaluator, on the server
(`linuxX64Main/.../cron/CronEvaluator.kt`). That evaluator refuses a cron that does not fire within 1500 days. The
rest is duplicated: two *Daily at / Weekly on* pickers that share no code, three ways of putting a schedule into
words, four copies of the weekday names, and four different rules for "is it due".

Three of the owner's examples cannot be written as a cron at all: *every 10 years*, *every 2 weeks* and *the first
Monday of the month*. A 5-field cron has no year field and no memory of when it started. Every serious recurrence
editor (Google Calendar, Outlook, Apple Calendar, Jellyfin's own scheduled-task triggers) uses the iCalendar RRULE
model for this: a frequency, an interval and an anchor date, narrowed by weekdays, month days and months. This spec
adopts a subset of it alongside cron. It does not replace cron.

Times are wrong today in a way the household can see. The evaluator uses the server process's local zone. The
container sets no `TZ`, so it is UTC. The preview shows the browser's zone. So *Daily at 04:00* fires at 04:00 UTC
and previews as some other hour. FR-4 fixes this. Without that fix, *every Monday and Tuesday* near midnight lands
on the wrong day.

## Requirements

### FR-1 — One schedule type, one stored form

1. A schedule is a single string, in one of two forms:
   - a **cron**: the five fields or a macro, exactly as `CronExpr.kt` accepts them today. Every stored *Find*
     schedule stays valid unchanged.
   - a **recurrence**: `every:` followed by `;`-separated `KEY=value` pairs, a subset of RFC 5545 RRULE:
     - `FREQ` is `HOURLY`, `DAILY`, `WEEKLY`, `MONTHLY` or `YEARLY`;
     - `INTERVAL` is 1–100, default 1;
     - `BYDAY` is weekdays `MO`…`SU`, comma-separated. With `MONTHLY` it may carry an ordinal: `1MO` is the first
       Monday and `-1FR` the last Friday;
     - `BYMONTHDAY` is 1–31, or `-1` for the last day of the month;
     - `BYMONTH` is 1–12;
     - `AT` is `HH:MM`. With `HOURLY` it is `MM`, the minute past the hour;
     - `FROM` is `YYYY-MM-DD`. This is the anchor: the date the intervals count from.

     Example, *every 10 years on 1 March at 04:00, counting from 2026*:
     `every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01`.
2. **Cron is written whenever the choice can be said as a cron.** That covers *every N hours* (when N divides 24),
   daily, any set of weekdays, *monthly on day D* and *yearly on a date*. The recurrence form is written only for
   what cron cannot say: an interval above 1 on days, weeks, months or years, an ordinal weekday, and *last day of
   the month*. A backend rolled back one release therefore still reads every schedule that does not need the new
   form.
3. An empty string means **off**: the job runs only when started by hand, as a blank *Custom* does today.
4. A month that lacks the day (a `BYMONTHDAY=31` in April) is skipped, as RFC 5545 skips it. The picker offers
   *Last day* for the case where that is not what the household means (FR-3.4).
5. The type lives in `commonMain` next to `CronExpr.kt` and owns parsing, the canonical string and the plain-words
   sentence. The sentence replaces `describeCron`, `ProcessingSchedule.describeFind` and the preset regexes in
   `SettingsProcessing.kt`. The weekday and month names exist once.

### FR-2 — One evaluator

1. One function gives the next *n* fires after a moment, for both forms, in the schedule zone (FR-4). It replaces
   `nextFireEpochSec` and its day-by-day walk. It can live in `commonMain` on `kotlinx-datetime`, which the build
   already has. A recurrence is computed arithmetically from `FROM`, so *every 10 years* costs no more than *daily*.
2. **Validation** (supersedes FR-166-4's horizon). A schedule is valid when it parses and fires at least once within
   **100 years** of now. FR-166-4's floor stays: fires are never less than **15 minutes** apart. A setting may set a
   higher floor of its own (FR-5). An invalid schedule, or one under its floor, is refused with 422 and the
   sentence of what is wrong, as FR-166-4 refuses it today.
3. **When a job is due:** when its schedule's first fire after its last run has passed. A job that has never run
   is due. Fires missed in a row are run **once**. This is DEB-2823 FR-3.3's rule, now applied to every setting in
   FR-5. For *Find* it is a change: a backend that was down over a fire now runs that fire once when it comes back,
   instead of waiting for the next one.

### FR-3 — The picker

One control, built once (an HTML builder in `commonMain`, as `AutofetchHtml.kt` is), and used by every setting in
FR-5. The structure follows a calendar's *custom recurrence* editor:

1. **Repeat every [N] [hours ▾ | days | weeks | months | years]**, with N from 1 to 100. *Custom (cron)* is the last
   entry in the unit menu.
2. **Weeks** show seven weekday toggles, Monday first. Any number may be on, and at least one must be. Pressing
   *Every Monday and Tuesday* is two toggles.
3. **Hours** show *at minute [MM]*. Every other unit shows *at [HH:MM]*.
4. **Months** offer *on day [1–31 | Last day]* or *on the [first | second | third | fourth | last] [weekday]*.
5. **Years** offer *on [month] [day]*.
6. An N above 1 on days, weeks, months or years shows **starting [date]**. It defaults to the next date that
   matches, and sets `FROM`.
7. **Custom (cron)** is the cron text box with live validation, as now.
8. Under the control, always: the **sentence** and the **next five runs**, both from the server
   (`POST /api/config/validate-schedule`, FR-6). Save is held while the server says the schedule is invalid. The
   admin derives no times itself, so it renders server-pushed state only.
9. A stored schedule that the simple modes can show opens in them. Anything else opens in *Custom (cron)*, or in
   the mode its recurrence names. A stored schedule is never rewritten because the control could not show it.
10. A setting that can be off has an **Off** choice at the head of the unit menu (FR-1.3).

### FR-4 — The schedule zone

1. Schedules fire in **one zone**, `[general] timezone` (an IANA name). It defaults to the server's `TZ`, or UTC
   when that is unset. The Settings page shows it and lets it be changed.
2. The picker names the zone beside the time (*04:00 Atlantic/Faroe*). The sentence and the next five runs are
   given in that zone, not in the browser's.
3. A time that a DST change skips runs at the first minute after the gap. A time that a DST change repeats runs
   once.

### FR-5 — Which settings use it

| Setting | Stored key | Own floor | Carry-over, once, at load |
|---|---|---|---|
| *Find new & changed* | `[processing.find] schedule` (unchanged) | 15 min | none: a cron is already a schedule |
| Recommendations, Suggestions | `recommendations_schedule`, `suggestions_schedule` (DEB-2823 FR-3.2) | 15 min | DEB-2823 FR-3.4, unchanged |
| Music, Audiobooks | `[processing.music] schedule`, `[processing.audiobooks] schedule` (new keys) | 15 min | `hourly` becomes `0 * * * *`, `daily` becomes *daily at* the hour and minute of the last run, `weekly` the same on that weekday. With no run yet: 04:00, and Monday. The old `interval` is still written for one release (DEB-1664 FR-2.4's rule). |
| Auto-fetcher *Check* | `autofetch_fetcher.schedule` (same column) | **6 h**, and the minute spread by the fetcher's id (auto-fetch FR-2.3, unchanged) | `every6h`, `daily@`, `weekly@` keep being read for good, and are written in the new form at the fetcher's next save. No migration. |

Every row above starts using the FR-2.3 due rule.

**Pending owner decision (card on DEB-4387):** whether the Live TV guide refresh and the release-age re-check tiers
(*Metadata*, *Verify*, *Track lengths* and so on; `2years`, `5years`, `never` today) also take part. The
recommendation: the guide refresh takes the picker with a 15-minute floor. The tiers keep being ages, not calendar
times ("re-check when the last check is older than…"), but their select becomes the picker's *every [N] [unit]*
half, so *10 years* is possible there too.

### FR-6 — Routes

1. `POST /api/config/validate-schedule` takes `{ schedule, floorMinutes? }` and answers with the fields it answers
   today plus `sentence` and `zone`. Fields are only added. It accepts both forms.
2. Each setting's existing save route accepts both forms, and refuses with 422 what FR-2.2 refuses.
3. Every view that shows a next run (the Dashboard, the Processing rows, the fetcher cards, *Activity ▸ Schedule*)
   gets it from the one evaluator.

## Stand-in data

For pictures and tests: the processing features as they are named in the admin, and an auto-fetcher named
*Evening News*. No title or file from the library.

## Acceptance

- A Find schedule saved before this change (`0 */1 * * *`, `30 3 * * 0`) loads, shows in its preset, runs at the
  same times, and is byte-identical after an unrelated save.
- *Weeks · Mon + Tue · 04:00* saves as `0 4 * * 1,2`, and its next five runs are Mondays and Tuesdays at 04:00 in
  the schedule zone.
- *Every 2 weeks · Wed* from 2026-11-18 fires on 18 Nov and 2 Dec, not on 25 Nov.
- *Every 10 years · 1 March · 04:00* from 2026 validates, previews 2026, 2036, 2046 and so on, and its sentence says
  so.
- *Months · last Friday* fires on 27 Nov 2026 and 25 Dec 2026.
- *Months · day 31* skips November. *Last day* does not.
- An auto-fetcher stored as `weekly@3@04:00` opens as *Weeks · Wed · 04:00*. Saved unchanged, it is written as
  `0 4 * * 3` and fires at the same time. An auto-fetcher at *every 1 hour* is refused, because of the 6 h floor.
- A Music feature on `daily` carries over to *daily at* its last run's time. `interval` is still written for one
  release.
- With `timezone = "Atlantic/Faroe"` on a UTC host, *Daily at 04:00* fires at 04:00 local time on both sides of a
  DST change, and the preview shows 04:00.
- There is one schedule control in the admin source. `SettingsProcessing.kt`'s and `AutofetchHtml.kt`'s own pickers
  and the duplicated weekday arrays are gone.

## Non-goals

- Seconds, `L`/`W`/`#` cron syntax, and named days or months in cron. The recurrence form covers what these would
  add.
- Exclusion dates, *except holidays*, and an end date or count. Nothing in the house asks for them.
- The request lifecycle sweep and the fixed internal loops. They have no picker.
- Ravilo clients. They have no recurring schedule.

## Design

The control has a surface, so it gets a round (Rannvá, `design/PROCESS.md`). The control is drawn once and shown in
its two homes:

- the auto-fetcher side panel, about 280 px wide, as in the owner's screenshot;
- a Processing row.

The states to draw:

1. *Weeks* with Mon and Tue on;
2. *Every 2 weeks* with *starting*;
3. *Months · last Friday*;
4. *Every 10 years*;
5. *Custom (cron)*, valid and invalid;
6. *Off*;
7. *Hours* refused under the 6 h floor.

Each state shows the sentence and the next five runs. It is an admin surface on a desktop browser, with no TV or
remote constraints. The styling goes into `wf.css`.

## Build order

One engineer, in order, each piece blocked by the one before:

1. **Backend: the type and the evaluator** (FR-1, FR-2, FR-4, FR-6.1), with *Find* moved onto them. No UI change.
   The tests are the acceptance cases above, run as `linuxX64Test`.
2. **Backend: the other settings move onto it** (FR-5, FR-6.2–6.3). This includes the Music/Audiobooks carry-over
   and the auto-fetcher's old forms. It folds DEB-2827's backend piece in, rather than building it a second time.
3. **Design round** (Rannvá). It runs in parallel with pieces 1 and 2.
4. **Admin: the shared control** (FR-3) in every setting of FR-5, built on the round's pictures. The two old
   pickers are removed. It replaces DEB-2830's two pickers.

Each piece files its own QA row when built, with a written plan and no named seat.
