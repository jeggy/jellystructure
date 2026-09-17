# Phase 166 — Any cron you like for the scheduled pipeline, validated before it can be saved (FR-SCHED1)

> Reported 2026-08-14: *"Currently the 'Scheduled scan pipeline' has 3 options 'Daily', 'Weekly' or
> 'Every 6h'. […] let's update it, so we also can modify these cron rule to anything we want. So I can
> easily say every 1 or 3 hours etc. Now that it's possible to enter own cron rules, we should also make
> sure to validate anything entered here, so it will also in fact work when user clicks save."*

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — dev-authored, not yet built.

## 1. What's there now

**Frontend** (`Settings.kt`, `design/app/settings.html`): a three-way segmented control
(`#pipe-freq`: Daily · Weekly · Every 6h) plus a time input, and a **read-only** derived cron display
(`#pipe-cron`). `computePipeCron()` (`Settings.kt:2345-2349`) emits exactly one of
`0 H * * *`, `0 H * * 0`, `0 */6 * * *`.

**Backend** (`nextRunDelayMs`, `Main.kt:369-407`): a hand-rolled matcher for precisely those three
shapes. It reads fields 0 (minute), 1 (hour) and 4 (day-of-week), and supports `*/N` in the hour field,
a literal hour, a literal minute, and a day-of-week that is either `*` or a single digit. Anything else
returns `null`, and the scheduler idles with a warning.

Beyond the missing flexibility, the current implementation has three defects worth fixing while we are
in here:

1. **Fields 2 and 3 (day-of-month, month) are split out but never evaluated.** `0 3 1 * *` — "03:00 on
   the 1st of the month" — is accepted by the parser and then run **daily**. Silently wrong, not
   rejected.
2. **`*/N` in the hour field forces the minute to 0** (`tm.tm_min = 0`, `Main.kt:388`), ignoring
   whatever the minute field said. `30 */6 * * *` runs on the hour.
3. **A cron the Settings page can't recognise is silently rewritten.** The load-time parser
   (`Settings.kt:851-863`) falls through to `else -> pipelineFreq = "daily"`, and the next Save calls
   `computePipeCron()`, which emits `0 3 * * *`. A hand-edited `config.toml` schedule is destroyed by
   an unrelated visit to Settings. This is the reason a "Custom" mode must round-trip, not just exist.

There is also **no validation anywhere on the save path**. `ConfigStore.update` (`ConfigStore.kt:37-40`)
assigns and persists with no checks, so an invalid schedule saves happily, scheduling silently stops,
and the only signal is a `Logger.warn` in the backend log — repeated every 60 seconds forever, because
the scheduler re-warns on every poll tick (`Main.kt:284-285`).

## 2. Functional requirements

### FR-166-1 — A real cron parser, shared between frontend and backend

New `src/commonMain/kotlin/dev/jellystructure/cron/CronExpr.kt`, compiled into both the wasmJs admin
frontend and the linuxX64 backend (same placement as `MediaJobParams.kt`), exposing:

```kotlin
sealed interface CronParse {
    data class Ok(val expr: CronExpr, val description: String) : CronParse
    data class Invalid(val fieldIndex: Int, val message: String) : CronParse
}
fun parseCron(text: String): CronParse
```

Standard 5-field cron, evaluated against **local wall-clock time** (the clock the admin reads — same
basis as today):

| # | Field | Range |
|---|---|---|
| 0 | minute | 0–59 |
| 1 | hour | 0–23 |
| 2 | day of month | 1–31 |
| 3 | month | 1–12 |
| 4 | day of week | 0–6, `7` accepted as Sunday |

Per field: `*`, a literal, a range `A-B`, a step `*/S` or `A-B/S`, and comma-separated lists of any of
those. Optionally also accept the macros `@hourly` `@daily` `@weekly` `@monthly` `@yearly` by expanding
them to their canonical 5-field form.

Explicitly **rejected**, with a message naming what was found: Quartz/extended syntax (`L`, `W`, `#`,
`?`), a seconds field (6 fields), named months/days (`JAN`, `MON`), and `@reboot`. Rejecting these
loudly is the point — silently mis-evaluating them is what defect (1) above already does.

**Day-of-month + day-of-week semantics must follow standard cron's OR rule**: when *both* fields are
restricted (neither is `*`), the expression fires on a day matching **either**. This surprises people,
so `description` must say it out loud ("on the 1st of the month, and every Monday").

`description` is a plain-English rendering used by the UI, e.g. `0 */3 * * *` → "Every 3 hours, on the
hour"; `30 4 * * 1-5` → "At 04:30, Monday to Friday".

### FR-166-2 — Backend: evaluate the parsed expression, not three hard-coded shapes

`nextRunDelayMs(cron, nowEpochSec)` is reimplemented on top of `parseCron`, keeping its current
signature, its local-timezone basis, and its DST handling (`tm_isdst = -1` before every `mktime`, so a
target on a transition day resolves correctly).

Evaluation walks forward day by day from today: for each candidate day, `mktime` its midnight, check
day-of-month / month / day-of-week against the parsed sets, and if the day matches take the first
(hour, minute) pair from the ordered allowed sets that is strictly after `now`. Bounded at **1500 days**
of search; exhausting the bound returns `null` (an expression that never fires — e.g. `0 0 30 2 *`),
which FR-166-4 rejects at save time so it can never reach the scheduler in the first place.

Fixes, as a direct consequence: day-of-month and month are honoured; the minute field is honoured
alongside `*/N` hours; lists and ranges work everywhere.

### FR-166-3 — Scheduler log hygiene

The scheduler's "not understood" warning fires once per **distinct** schedule value, not once per
60-second poll. Remember the last-warned string and re-warn only when it changes.

### FR-166-4 — Validation on the save path (the "it will in fact work when the user clicks save" half)

`PUT /api/config` validates `scan_schedule` before handing it to `ConfigStore.update` and responds
`422` with `{ "field": "scan_schedule", "error": "<message>" }` when any of these hold:

1. It does not parse (`CronParse.Invalid`) — the message carries the offending field and reason.
2. It parses but **never fires** within the 1500-day horizon.
3. It fires **more often than every 15 minutes**. Checked by computing the next 10 fire times and
   requiring every consecutive gap ≥ 15 min. `* * * * *` would start a full library pipeline every
   minute; this is a safety floor, not a preference, and the error message must say so
   ("a pipeline run takes minutes to hours — schedules more frequent than every 15 minutes are not
   allowed").

A blank `scan_schedule` remains valid and means "scheduling off" (unchanged).

Validation lives in a small `AppConfigValidator` alongside the route, not inside `ConfigStore` —
`ConfigStore.update` is also the path used by internal callers (e.g. the boot-time webhook-secret
generation, `Main.kt:224`) that must never be able to fail on unrelated validation.

### FR-166-5 — Settings UI: presets, "Every N hours", and Custom

`#pipe-freq` becomes four options: **Daily · Weekly · Every N hours · Custom**.

- **Daily** / **Weekly** — unchanged, still driven by the `#pipe-at` time input, still emitting
  `0 H * * *` / `0 H * * 0` so existing configs round-trip byte-identically.
- **Every N hours** — generalises today's fixed "Every 6h" with a small numeric input (N, 1–23) and an
  optional past-the-hour minute, emitting `M */N * * *`. This is the direct answer to "every 1 or 3
  hours" and needs no cron literacy.
- **Custom** — `#pipe-cron` stops being a read-only derived display and becomes a real text input.

**Round-trip on load** (fixes defect 3): match the stored `scan_schedule` against the preset shapes;
on a match select that preset and populate its controls; on **no** match select **Custom** and put the
stored string in the input verbatim. A schedule this page didn't author is never silently rewritten
again.

**Live feedback while typing**, driven by the same shared `parseCron` the backend uses, so the frontend
can never disagree with the server about what is valid:

- Valid → a green check, the plain-English `description`, and a **next 5 runs** preview list.
- Invalid → a red state, the specific message, and **Save disabled** with the reason next to the
  button. Blocking Save is the requirement; the `422` in FR-166-4 is the backstop for anything that
  reaches the API another way (the TOML editor, a direct call).

The next-run badge (`#pipe-next`) keeps its current behaviour of showing "save to apply" while editing
and the backend's real computed next run after save (`GET /api/media/scan/status`, `MediaRoutes.kt:1831`,
which already recomputes from live config).

### FR-166-6 — TOML preview

`buildToml`'s `scan_schedule` line (`Settings.kt:1366-1368`) is unchanged, but the preview must render
the custom string as typed.

## 3. Non-goals

- Per-step schedules. One schedule drives the whole pipeline, as today.
- Timezone selection. Evaluation stays on the host's local timezone; the UI already shows local times.
- A seconds field, or any non-standard cron dialect.
- Scheduling anything other than the pipeline (the segments lane from Phase 164 is queue-driven, not
  cron-driven).

## 4. Open questions

1. Does the 15-minute floor (FR-166-4 case 3) risk blocking a legitimate use? A "scan_files-only"
   pipeline is cheap and an operator might reasonably want it every 5 minutes. Options: keep the floor
   at 15 min flat, or scale it (allow < 15 min only when the enabled step list is `scan_files` alone).
   Recommendation: flat 15 min for this phase; revisit if it bites.
2. Whether `@daily`-style macros are worth supporting at all, given the presets already cover them.
   Low cost, mild convenience; author's call.
3. `behavior.scan_interval_hours` — the legacy "interval since boot" fallback still live in the
   scheduler (`Main.kt:286-289`) and still in `AppConfig`. It has no UI. Worth deprecating in the same
   pass, or explicitly leaving alone? Recommendation: leave the code path, document it as legacy.

## 5. Verification

- Unit tests on `parseCron` for: each field form (`*`, literal, range, step, list, combinations), the
  rejected dialects, and the day-of-month/day-of-week OR rule.
- Unit tests on `nextRunDelayMs` covering the three legacy shapes (must produce identical results to
  today — a regression guard), plus `30 */6 * * *`, `0 3 1 * *`, `0 9-17/2 * * 1-5`, and a
  never-fires expression.
- A DST-transition test on the host timezone for a daily schedule crossing the spring-forward hour.
- Manual: enter `0 */3 * * *` in Custom, confirm the description, the next-5 preview, and that the
  saved config's real next run matches the preview.
- Manual: enter `* * * * *` and confirm Save is blocked with the 15-minute message; confirm the API
  also rejects it when posted directly.
- Manual: hand-edit `config.toml` to `0 9-17/2 * * 1-5`, open Settings, confirm it loads as **Custom**
  with the string intact, and that saving an unrelated field leaves it untouched.
