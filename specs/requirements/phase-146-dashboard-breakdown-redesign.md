# Phase 146 — Dashboard "Needs your attention" breakdown redesign (FR-DB2)

> The Dashboard already renders a triage-type breakdown (Phase 117, extended by Phase 144's
> `cover_as_video`), but its layout diverged from the design and reads as a flat, dense list. The
> design mockup (`design/app/index.html`) reworks the **"Needs your attention"** card into a
> scannable, clickable **issue-type grid** that is the single attention surface on the Dashboard —
> per-item step-through lives entirely in the floating Triage dock. This phase brings the shipped
> `Dashboard.kt` card up to that design. **Presentation/UX only** — no new triage types, detection,
> or counts (all of that is Phases 117/121/122/128/144); this is how the existing
> `TriageTypeCount` list is laid out and linked.

## Problem

The current `Dashboard.kt` "attention breakdown" works but:
- it renders as a single-column, text-dense stack that doesn't scan at a glance, and it still carries
  a **sample list of individual flagged items** alongside the type breakdown — duplicating what the
  floating Triage dock already does (step through each item to its detail page).
- issue rows are terse (bare type label + count) with no plain-language description of what the issue
  *is* or why it matters, so an operator has to already know the vocabulary.
- zero-count types are shown with the same weight as non-zero ones, adding noise.

The design resolves all three: the breakdown **is** the card, every type reads in plain language, and
the card defers per-item work to the dock.

## Current state (verified)

- `Dashboard.kt` builds the attention card from the `TriageTypeCount` list returned by
  `TriageRoutes.kt` (`untagged`, `cascade_mismatch`, `multi_default`, `language_mix`,
  `missing_from_source`, `missing_still`, `duplicate`, `zero_audio`, `cover_as_video`), each with a
  label + count, and links each to `library.html?filter=<type>` (the Library `filter=` handling and
  per-type sets already exist — Phases 117/144).
- The floating Triage dock (`Shell.kt`, injected app-shell) already owns per-item navigation:
  step prev/next through the attention queue, opening each item's detail page. The Dashboard's own
  per-item sample list is redundant with it.
- Design mockup: **`design/app/index.html`** (the `.attn-breakdown` grid) + the shared tokens in
  `app/wf.css` — no new CSS file needed.

## Design

### A. The breakdown grid is the card body
1. The **"Needs your attention"** card header is unchanged in intent: title, a total-count badge
   (`214 items`), a **Show attention dock** button (reopens the dock), and **Browse all →**
   (→ `library.html?filter=attention`).
2. Directly below the header intro, render the issue types as a **two-column responsive grid**
   (single column below ~640px). Each cell is a full-cell link (`library.html?filter=<type>`) with:
   - a **bold human label** (e.g. "Untagged audio", "Default ≠ resolved", "Cover art as video"),
   - a **one-line plain-language description** of the issue (e.g. "an audio track carries no language
     tag", "cover image muxed as a selectable video track"),
   - a **count badge**, colored by severity — `bad` for hard file-health problems (untagged,
     missing_still, cover_as_video, zero_audio, duplicate, missing_from_source), `warn` for
     advisory/soft ones (cascade_mismatch, language_mix, multi_default).
3. **Zeros are de-emphasised:** a type with count 0 renders at reduced opacity (~50%) with a neutral
   (uncolored) badge, and is still clickable (opening an empty filtered Library is a valid, honest
   result). Non-zero rows are full-weight.
4. Row order is fixed and severity-grouped (not alphabetical, not count-sorted — a stable position
   lets an operator build muscle memory): the design's order is untagged · missing_still ·
   cascade_mismatch · language_mix · multi_default · cover_as_video · zero_audio · duplicate ·
   missing_from_source.
5. A footer line links **Browse all flagged items in Library →** (`library.html?filter=attention`).

### B. Drop the duplicated per-item sample list
6. The Dashboard card no longer renders its own table of individual flagged items — that role belongs
   entirely to the floating Triage dock (step-through) and to Library (`?filter=…`). The card is a
   *breakdown + entry points*, nothing per-item.

### C. Copy
7. Each type carries a fixed English label + description string (the design's wording is the source).
   These live next to the type ids so the breakdown, the Library filter labels, and the Triage-dock
   sublines stay consistent (reuse the existing per-type strings where they already exist rather than
   inventing a parallel set).

### D. Related (already shipped in the design, fold in if convenient)
8. The Dashboard **Quick actions** card in the design offers six actions (View attention · Manage
   tracks · Re-pull artwork · Sync NFOs to Jellyfin · Jellyfin rescan · View activity) with a
   feedback line — matching the buttons the backend already exposes. If `Dashboard.kt`'s quick
   actions still show the older three, bring them to parity in the same pass; otherwise out of scope.

## Non-goals
- **No new triage types, detection, or count logic** — the `TriageTypeCount` list and every `filter=`
  set are unchanged (Phases 117/121/122/128/144 own those).
- **No change to the Library `filter=` behaviour** or the floating Triage dock — this is the
  Dashboard card only.
- No per-item list on the Dashboard (deliberately removed — §B).
- No real-time push; the card is a point-in-time read refreshed on load like today.

## Acceptance
- The Dashboard "Needs your attention" card shows the issue types as a two-column (one-column on
  narrow) grid of label + description + severity-colored count badge; each cell links to
  `library.html?filter=<type>`.
- Zero-count types render dimmed with a neutral badge and are still clickable.
- The card no longer renders a per-item sample table; the header's Show-attention-dock + Browse-all
  and the footer Browse-all-in-Library are the only per-item entry points.
- Row order is the fixed severity-grouped order in §A4; labels/descriptions match the design.
- Verified visually against `design/app/index.html` and via admin `compileKotlinWasmJs`.

## Status note
Design-authored, `Planned`, not yet dev-reviewed. On the next design→repo export this spec lands at
`specs/requirements/phase-146-dashboard-breakdown-redesign.md`; `scripts/check-phases.sh` will flag it
as a spec with no `STATUS.md` row, at which point the dev team adds its row (next admin number is 146;
next after this is 147).
