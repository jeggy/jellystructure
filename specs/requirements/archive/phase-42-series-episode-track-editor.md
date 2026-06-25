# Phase 42 — Per-episode track & order editor on Series detail (FR-TO2)

**Status:** ◑ Reopened (2026-06-25, design-diff audit) — the Series detail Seasons & Episodes tab diverges from `design/app/series-simpsons.html`: the season picker is an inline segmented control that wraps unusably for 40+ season shows (design uses a searchable prev/next dropdown), and it is missing the "Expand all issues" button + the per-episode language badge in the collapsed row. _(Originally: Done — series episodes get the same unified track editor as movies, in a per-episode modal.)_

> Builds directly on **[Phase 41](phase-41-merge-track-order-into-detail.md)**. The editor body,
> warnings inventory, staged-changes panel, command/cost rules, and per-kind explainer copy are all
> defined there and **reused verbatim** — this phase specifies the **series-side framing** (where it
> opens, the modal anatomy, per-episode data, and the episode example cases). Do Phase 41 first.

## Problem
On Series detail, each episode currently edits its tracks with **ad-hoc inline snippets** — an
untagged-assign row on one episode, a multiple-default radio group on another — with no reordering, no
command preview, and no consistency with the movie editor. There is no single, complete way to
maintain an episode's tracks the way Phase 41 gives movies.

## Goal
Every episode on Series detail opens the **Phase 41 unified track editor**, scoped to that episode's
file, in a **modal**. The ad-hoc per-episode track snippets are removed.

## Current state (as-is)
- `MediaDetail.kt` series mode / `series.html`: episodes expand to show title/overview/still editing;
  track handling is inline and inconsistent (E03-style untagged assign; E06-style multiple-default
  radios). Per-episode metadata + resolved-language independence already exist (Phases 6/12).
- After Phase 41, the unified editor (component + warnings + staged command + cost) exists for movies.
- Backend per-episode track ops already exist (Phase 6 + `/api/media/{id}/tracks/**` scoped to the
  episode file). **No backend change is required.**

## Requirements

### A. Entry point — every episode is editable
1. Each episode's expanded body shows an **"Edit tracks & order →"** affordance (a "Fix tracks &
   order →" variant when the episode has a track issue), alongside a compact read-only track summary
   (e.g. `fo · eac3 ★`, `da · aac`).
2. Activating it opens the editor **modal** scoped to that episode; the episode row's expand/collapse
   is not triggered by the same click (stop propagation).
3. Episodes with issues surface the issue on the row as today (badges: "1 untagged track",
   "multiple default audio") — the modal is where they are fixed.

### B. Modal anatomy
1. A centered modal over a dimmed/blurred backdrop. **Header:** title "Tracks & order", an **episode
   badge** (`S01E06`), an **Audio / Subtitles** segmented control, and a **✕** close.
2. Below the header, the episode's **file name** (`Nordvest - S01E06.mkv`) in monospace.
3. **Body:** the Phase 41 editor — track rows (drag grip + ▲▼ reorder, the **searchable language
   picker** with full display names per Phase 41 §A5, ★ default / "keep this one", subtitle forced
   toggle), the per-kind **explainer** (Phase 41 §D), and the **warnings inventory** (Phase 41 §B)
   rendered **inside the modal**.
4. **Staged changes** (Phase 41 §C) render **inside the modal**: pending-op list, exact
   `mkvpropedit` / `ffmpeg -c copy` command, cost indicator, and **Apply to file** / **Discard**.
   When there are no changes, a one-line manual/Apply hint shows instead.
5. **Dismissal:** ✕, backdrop click, and **Esc** all close the modal. Closing with unsaved staged
   changes discards them (they were never written).

### C. Per-episode, independent data
1. The editor is seeded from **that episode's own** audio/subtitle tracks; edits and the staged
   command target **that episode's file** only. No cross-episode/bulk apply.
2. The audio cascade alert (Phase 41 §B1) uses the **episode's own resolved language** — episodes
   resolve independently of the series (Phases 6/12), so a `da`-leading episode in an otherwise `fo`
   series is correct, not a warning.

### D. Example cases the design must demonstrate
1. **Clean episode** (e.g. S01E01) — tagged audio + subtitle, one default; editor opens with no
   warnings and an empty staged panel.
2. **Untagged track** (e.g. S01E03) — an audio track with no language; the assign row tags it, which
   stages an `mkvpropedit … --set language=…` op.
3. **Multiple default audio** (e.g. S01E06) — two audio tracks flagged default; each shows
   **"★ keep this one"**; choosing one collapses to a single default and stages the flag edits.

## Invariants
- Series episodes use the **same editor** as movies (Phase 41) — no parallel/ad-hoc track UI.
- Per-episode scope: edits and commands target one episode's file; **no bulk/season apply**.
- Episode **resolved-language independence** is preserved (Phases 6/12).
- Manual, preview-first, `mkvpropedit`/`ffmpeg -c copy`, seeding-guard-respecting — inherited from
  Phase 41.
- Reuses existing endpoints — **no backend change**.

## Out of scope
- Bulk/season-wide track edits across episodes (possible later phase).
- The movie editor itself (Phase 41).
- New backend operations; any re-encode.

## Design reference
`design/app/series.html` — every episode opens the modal editor; the three example cases above are
implemented (S01E01 clean, S01E03 untagged, S01E06 multiple-default).
