# Phase 91 — Scheduled scan pipeline (composable building blocks) + release-age freshness policy (FR-SP1)

> Authored from the design project. **Supersedes** the single-toggle scheduled rescan in
> [`design-gap-fix.md`](design-gap-fix.md) §1B and extends the scanner (Phases 7/16/36) and the
> write subsystems (TMDB 32 · artwork 31/47 · NFO 3/74 · Jellyfin refresh 50 · Radarr/Sonarr 54 ·
> drift 33 · notifications 36).

## Problem
The scheduled rescan is a single **on/off toggle + frequency/cron** that kicks off one monolithic
"full library scan". An operator can't say *what* the scheduled run should actually do — scan only,
or scan + re-pull TMDB + write NFOs + tell Jellyfin — and there is **no way to periodically
re-sync the unchanging catalogue with TMDB**. A film released this year keeps getting TMDB updates
(ratings, cast, artwork) for months; a 1990s title almost never changes. Today the only options are
"scan everything every night" (wasteful, hammers TMDB) or "never re-check" (metadata goes stale).

## Goal
Turn the scheduled scan into a **pipeline of building blocks**: an ordered, operator-composed chain
that runs on the schedule. It **always starts with `Scan media files`** (the trigger), which gains a
**release-age freshness policy** so unchanged titles are re-checked on an age-based cadence; the
operator then chains whatever action steps they want after it.

## A. The trigger — `Scan media files` + freshness policy
The first block is fixed (can't be removed or reordered). Beyond discovering **new & changed** files
(the existing scanner: Jellyfin `VirtualFolders` + ffprobe), it adds an opt-in **re-check policy**
for **unchanged** files, tiered by release age:

| Release age (e.g. run in 2026) | Default cadence | TOML |
|---|---|---|
| Released **this year** (`2026`) | every week | `refresh_this_year = "weekly"` |
| Released **1–5 years ago** (`2021–2025`) | every month | `refresh_1_5y = "monthly"` |
| Released **over 5 years ago** (`before 2021`) | every 6 months | `refresh_older = "6months"` |

Each cadence is operator-settable: `weekly · monthly · 6months · yearly · never`. A master
`recheck_unchanged` toggle enables/disables the whole policy.

**Internal state (the key new piece):** the media store keeps a **`last_checked` timestamp per
item** (the year is already stored — Phase 53). On each scheduled run the trigger selects:
`new ∪ changed ∪ {items whose (now − last_checked) ≥ cadence-for-their-release-age}`. Only **due**
items enter the working set; `last_checked` is stamped when an item is processed. New/changed files
are always included regardless of the policy. This is a write-path addition to `MediaStore`
(SQLite, Phase 14) — same pattern as existing per-item state; no new service.

## B. The working-set model
The trigger produces a **working set** of items. Every subsequent block operates on **that set**
(not the whole library), so the pipeline reads as "scan → then do these things to what the scan
selected". This keeps a scheduled run cheap and is why the freshness policy matters: it controls how
big the set is.

## C. Building blocks (and where each is already implemented)
All blocks map to subsystems that already exist — the pipeline **orchestrates** them, it does not
introduce new capabilities:

| Block | Does | Backed by |
|---|---|---|
| **Scan media files** (trigger) | discover new/changed + due-for-recheck | scanner (7/16/36) + new `last_checked` |
| **Pull TMDB metadata** (`scope: missing/all`) | re-match & fetch metadata/original-language | TMDB match/re-pull (24/32/53) |
| **Download artwork** (`scope: missing/all`) | poster/fanart/logo/stills | `ArtworkDownloader` (31/47) |
| **Write NFO files** (`overwrite: bool`) | write `.nfo` to disk | NFO writer (3/74) |
| **Sync Jellyfin** | `POST /Items/{id}/Refresh` for the set | Jellyfin refresh (50) |
| **Rescan in Radarr / Sonarr** | nudge the *arr managing each title | ArrClient (54) — gated on *arr enabled |
| **Detect drift** | flag Jellyfin ⇄ NFO differences | drift detection (33) |
| **Wait** (`minutes`) | pause before the next step (let Jellyfin settle) | trivial scheduler delay |
| **Send notification** (`on: summary/changes/errors`) | webhook ping | notifications (36) |

The pipeline is **dynamic**: add a block via a `+` between any two steps (palette popover),
drag-reorder, per-step skip toggle, per-step options, and a `Run now` that executes immediately.
The schedule (Daily / Weekly / Every 6h → derived cron) drives when it runs.

## Implementability review (the honest read)
- **All blocks are implementable** — each is a thin wrapper over an existing subsystem; the only
  genuinely new state is the per-item `last_checked` timestamp + the age→cadence selection query.
- **Ordering is free, which allows illogical pipelines** (e.g. `Sync Jellyfin` before `Write NFO`,
  or `Detect drift` before anything is written). Recommendation: **soft validation** — keep reorder
  free but show a non-blocking inline hint when a step's usual prerequisite is missing (TMDB→NFO→
  Sync is the canonical order). Do **not** hard-block; operators may have reasons. The seeded default
  (Scan → Pull TMDB → Sync Jellyfin → Notify) is already a sane order.
- **`Wait` is the lowest-value block** — keep it (useful before `Detect drift` so a Jellyfin refresh
  settles first) but it's optional.
- **No conditional branching.** A "Condition / gate" block was prototyped and **deliberately removed**
  — the pipeline is a **linear** sequence only. Branch logic adds UX + execution complexity this
  feature does not need.
- **Concurrency:** steps run **sequentially** over the working set (the scanner's own worker pool
  parallelises within a step, Phase 16). No parallel branches.

## Config shape
`config.toml` keeps the cron (`scan_schedule`) and adds an ordered `[[scan.pipeline]]` array; the
trigger carries the freshness keys:
```toml
scan_schedule = "0 3 * * *"

[[scan.pipeline]]
step = "scan_files"
recheck_unchanged = true
refresh_this_year = "weekly"
refresh_1_5y      = "monthly"
refresh_older     = "6months"

[[scan.pipeline]]
step  = "pull_tmdb"
scope = "missing"

[[scan.pipeline]]
step = "sync_jellyfin"

[[scan.pipeline]]
step = "notify"
on   = "summary"
```
A skipped step persists as `enabled = false` rather than being dropped.

## UI (the mockup)
`design/app/settings.html` → **Libraries** tab → **Scanning**: a `Scheduled scan pipeline` card with
(a) a header (master enable · Daily/Weekly/Every-6h · time · derived cron · `Run now` · a colour-coded
recipe summary), (b) the trigger block with the freshness-policy table, (c) the chained action blocks
with the add-palette / drag-reorder / per-step options, and (d) the live `config.toml` mirroring the
pipeline as it's built. Each block is colour-coded by job; the run animation pulses through the
enabled steps.

## Invariants
- The pipeline **always begins** with `Scan media files`; it is the only required, non-removable step.
- Linear sequence only — no conditions, no branches, no parallelism.
- Persisted in `config.toml` (survives restart); the frontend renders the stored pipeline (FE=BE).
- Every block is an existing subsystem; the pipeline only orchestrates and schedules them.

## Out of scope
- Conditional / branching steps (explicitly dropped).
- Per-library pipelines (one global pipeline for now).
- Per-step item filtering beyond the trigger's freshness policy (the working set is defined once, by
  the scan).
- New scan capabilities — this phase composes what already exists.

## Source references
- `design/app/settings.html` (pipeline builder + freshness policy + live TOML).
- Scanner & schedule: `Main.kt` (`scanIntervalHours`), the multi-worker scanner (16), persistent scan
  state (7), operator controls (36).
- Step subsystems: TMDB (24/32/53), `ArtworkDownloader` (31/47), NFO writer (3/74), Jellyfin refresh
  (50), `ArrClient` (54), drift (33), webhook notifications (36).
- New: `MediaStore.last_checked` (per-item timestamp) + age→cadence selection (SQLite, 14).
