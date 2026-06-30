# Phase 96 — Bulk track re-order across a series (FR-TO3)

> **Phase number:** this was first drafted as "Phase 95"; the repo had meanwhile shipped Phase 95
> (non-destructive scanner), so it was renumbered to **96** on the next sync. Status: **Planned**
> (design done, not built).

> Builds on **Phase 41** (the unified editor — command,
> cost, seeding-guard and warning rules are inherited verbatim) and **Phase 42**
> (per-episode modal editor). This phase **lifts Phase 42's "no bulk/season apply" invariant** — that
> was explicitly parked there as a "possible later phase". The per-episode editor is unchanged and
> remains the place to fix one episode by hand.

## Problem
A co-production lands with its audio tracks in an inconsistent order across episodes — e.g. some
`zh · en · da`, some `da · en · zh`, some `en · zh · da`. An operator wants a single canonical order
(say `da · en · zh`) applied to **every** episode. Today that means opening the Phase 42 modal on each
episode and re-dragging by hand — tedious and error-prone for a 12-episode season, with no overview of
which episodes are already right or which can't be sorted cleanly.

The hard part is **edge cases**: an episode missing one of the target languages, an episode with a
language *not* in the target (a stray `de`), or an untagged track. These must never be silently guessed
— the operator needs to see exactly what will happen, exclude the awkward ones, and get a list of what
was left for manual handling.

## Goal
A **dedicated full-screen wizard** (Set up → Review → Apply), reached from the **Seasons & episodes**
header on Series detail, that applies one target track order across a chosen scope (whole series or one
season), classifies every episode against that order, lets the operator resolve edge cases, previews
total remux cost, applies as a **progress-reporting background job**, and ends on a **manual-handling
list** of everything it deliberately did not touch.

Audio and subtitles are **separate passes** — the same wizard, one track kind at a time (a mode switch
on step 1); never bundled into one apply.

## Current state (as-is)
- Phase 42: every episode opens the unified editor in a modal, scoped to **one** episode's file. Its
  invariants forbid bulk apply.
- Movie tracks have `POST /api/media/{id}/tracks/reorder` (ffmpeg `-c copy` remux). **Episodes do not**
  — episode track ops are only `…/episodes/{epFilename}/tracks/{plan,default,language}`. So bulk
  reorder needs **new backend work** (unlike Phases 41/42, which were pure UI merges).
- Long operations already stream `JobEvent` over WebSocket (`started` / `progress` / `file_done` /
  `finished`); the frontend **renders server-pushed state only** (constitution §4).

## Requirements

### A. Entry point
1. The **Seasons & episodes** header gains an **"↕ Re-order audio across series"** action next to the
   existing "Re-probe episode files" / "Expand all issues" controls.
2. It opens the wizard as a **full-screen takeover** that **keeps the global app shell** (left nav +
   theme picker) — it is a focused admin surface, not a modal. The floating Triage dock is suppressed
   on this surface. A persistent "‹ Back to {series}" exit and a 3-step **stepper** (Set up · Review ·
   Apply) sit in the wizard's own top bar.

### B. Step 1 — Set up
1. **Track type** segmented control: **Audio / Subtitles**. Switching it re-seeds the whole flow from
   that kind's tracks; the two are independent passes.
2. **Scope** segmented control: **Whole series** or a single **Season N**, with the episode count on
   each. Default = whole series.
3. **Target order** — a **drag-to-arrange priority list**, seeded with the **languages detected across
   the scope** (distinct, in first-seen order) and an **"+ add language"** affordance backed by the
   Phase 11 searchable picker for languages not present. Each row: rank badge, drag grip, **▲▼**
   buttons (disabled at ends), and remove (✕, disabled when one remains). This list *is* the target —
   no separate "golden episode".
4. **Optional toggle — "Also set the default {kind} track to #1 (`<lang>`)"**. Order and the ★ default
   flag are separate concepts (Phase 41 §D); default off → each episode keeps its current default.
5. A one-line statement of the edge-case contract: *strays / untagged → flagged for manual review,
   never auto-placed; episodes missing a target language → skipped by default, opt-in per episode.*

### C. Classification (one bucket per episode, derived against the target)
Computed by the backend **plan** endpoint (D1) — never by optimistic local state. For each episode in
scope, with `present` = distinct tagged languages, `strays` = `present − target`,
`missing` = `target − present`:

| Bucket | Trigger | Disposition |
|---|---|---|
| **Will re-order** | ≥2 tracks · no untagged · no strays · order ≠ target | Applied. ffmpeg `-c copy` **remux**. |
| **Already correct** | present⊆target · already in target order | No-op for order. *If* set-default is on and the default ≠ #1, an **instant** `mkvpropedit` flag edit still applies; otherwise untouched. Not an error. |
| **Partial — skipped** | no strays/untagged but `missing` ≠ ∅ | **Skipped by default.** Per-episode **"Apply to the subset anyway"** opts it in; applies the target order over just the languages it has. |
| **Needs review** | has a **stray** language, or **any untagged** track | **Never touched.** Sent to the manual-handling list (E). |
| **Nothing to do** | ≤1 track of this kind | Inert. |

Re-classification is live in the prototype as the target order changes; in production the plan endpoint
is re-requested when step-1 inputs change before entering Review.

### D. Step 2 — Review (preview-first, like Phase 41 §C — nothing is written here)
1. Backed by `POST /api/media/{id}/tracks/bulk-reorder/plan` `{kind, scope, order, setDefault}` →
   per-episode `{status, current[], proposed[], reason, estSeconds, remux:bool}` + scope totals.
2. **Grouped count cards** across the top — one per bucket (Will re-order · Already correct · Partial ·
   Needs review · Nothing to do) — each **clickable to filter** the table; the partial card also shows
   how many are currently opted-in.
3. **Remux-cost banner**: total estimated time and the count of episodes needing a **full remux**
   (order change → `ffmpeg -c copy`), contrasted with the instant flag-only edits (Phase 41 §B4). Only
   shown when ≥1 episode will remux.
4. **Pivot / matrix table** (default view; a **List** view toggles to the Phase 42-style stacked cards):
   - **Rows** = episodes; **columns** = ordered positions **1st · 2nd · 3rd · more**.
   - Each cell shows the **language chip** at that position. **Only the first three** positions are
     shown inline; episodes with >3 tracks show a **"+N"** pill — clicking it (or the row) expands the
     full ordered sequence.
   - A **Show: Current / Proposed** toggle flips every row between the file's actual order and the
     order it will become. A **Sort: Episode / Status** toggle orders rows naturally (S/E) or
     action-first (will re-order → partial → needs review → already correct → nothing).
   - The ★ marks the default track on the **proposed** order when set-default is on; **stray**
     languages render in the error color so they stand out across the grid; untagged shows `⚠ und`.
   - Expanding a row reveals the reason line, the **current → proposed** sequence (Phase 41 staging
     idiom), and the per-episode action: **"Apply to the subset anyway"** (partial) or **"Open episode
     editor →"** (needs-review, linking to the Phase 42 modal at `series#{epId}`).
5. Sticky footer: a running *"N will re-order · M need manual handling"* summary and **"Apply to N
   episodes →"** (disabled when N = 0). N counts re-orders **plus** opted-in partials.

### E. Step 3 — Apply, progress, and the manual-handling list
1. **Apply** posts the confirmed set to `POST /api/media/{id}/tracks/bulk-reorder`
   `{kind, scope, order, setDefault, optIn:[epId…]}`, which runs as a **background job** and returns a
   `jobId`. The wizard subscribes to the existing `JobEvent` WebSocket.
2. **Progress is server-pushed** (constitution §4): from `progress` events the wizard renders a
   **percentage, the current episode, episodes done / total, and estimated time remaining**, plus a
   `mkvpropedit`/`ffmpeg -c copy` note. Per the threshold rule, when the planned total is estimated to
   exceed **~2 seconds** the full progress view is shown; trivially short jobs may complete without it.
3. The job is **resilient per file**: a single episode failing (seeding-guard **409**, guard
   **unreachable 503**, or an ffmpeg error) does **not** abort the run — it's recorded as a failed item
   and the job continues. `finished` carries `{succeeded, failed}`.
4. **Done summary**, led by the **"Needs manual handling" list**: every episode that was **review** or a
   **non-opted partial**, plus any **per-file failure** from E3 — each with its reason (stray language /
   untagged / missing language / blocked-seeding) and an **"Open →"** link to the Phase 42 editor.
   Nothing in this list was modified. A **"Sync Jellyfin ↻"** action and a back-to-series link round it
   out. A tip notes that fixing the flagged episodes (tag, add the missing language) and re-running
   folds them into the clean set.

### F. Backend additions (this phase is **not** "no backend change")
1. **Episode reorder** — `POST /api/media/{id}/episodes/{epFilename}/tracks/reorder`, mirroring the
   movie `tracks/reorder` (ffmpeg `-c copy`, seeding-guard first). Bulk apply is built on this.
2. **Bulk plan** — `POST /api/media/{id}/tracks/bulk-reorder/plan` (dry-run; classification + per-episode
   est. cost; writes nothing).
3. **Bulk apply job** — `POST /api/media/{id}/tracks/bulk-reorder` → `{jobId}`; iterates the confirmed
   episodes, runs the per-file reorder (+ optional default `mkvpropedit`), emits `JobEvent`
   (`started` → `progress`/`file_done` per episode → `finished{succeeded,failed}`), re-probes each
   touched file, and runs the **SeedingGuard** per file (fail-closed, per E3).

## Invariants
- **Lifts** Phase 42's "no bulk/season apply" rule; the per-episode editor is otherwise unchanged.
- **Preview-first, manual, explicit** (Phase 41) — the plan is dry-run; **nothing writes until Apply**.
- **Edge cases are flagged, never guessed** — strays and untagged go to manual review untouched;
  partials are skipped unless explicitly opted in.
- **`mkvpropedit` for the default flag, `ffmpeg -c copy` for order** — never a re-encode (constitution
  §"Operation"). Order-relative, 1-based, type-relative selectors as in Phase 41 §C.
- **One canonical target order per pass**; **audio and subtitles never apply together.**
- **Frontend renders server-pushed progress only** — no local accumulation (constitution §4).
- **SeedingGuard runs per file**; a blocked/failed file is reported, not fatal to the run.
- **Episode resolved-language independence** (Phases 6/12) is read-only here — bulk reorder changes
  physical track order, which the resolver reads, but does not override per-episode resolution.

## Out of scope
- Cross-**series** apply (one series at a time), and applying audio + subtitles in a single run.
- Track **add / delete** in bulk, language **re-tagging** in bulk, or any re-encode / container
  conversion.
- A saved/reusable "house order" preset across series (possible later phase).
- Changing the per-episode Phase 42 editor.

## Design reference
`design/app/Bulk Audio Reorder.html` — the full wizard inside the app shell: drag priority list +
set-default toggle (step 1); grouped count cards, remux-cost banner, and the **pivot table**
(first-3 + "+N", Current/Proposed, Episode/Status sort) with List fallback (step 2); WS-style
percentage progress and the manual-handling list (step 3). The sample series exercises every bucket —
a clean reorder, an already-correct no-op, a missing-language partial, a stray-`de` review, an untagged
review, and a single-track "nothing to do". Entry point added to `design/app/series.html`.
