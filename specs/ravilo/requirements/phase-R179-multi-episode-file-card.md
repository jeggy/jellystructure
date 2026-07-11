# Phase R179 — Ravilo: multi-episode file as one combined card (FR-RV-MEF1)

> The viewing side of **[Phase 149](../../requirements/phase-149-multi-episode-files.md)**. When several
> episodes live in **one file** (`S01E01E02E03…`), Ravilo's series detail must show them without gaps —
> today only the file's first episode appeared, so E02/E03 looked **missing**. The chosen model is a single
> **combined card** whose still is a **skewed triptych** so all three episode images show at once. Applies
> on **TV** and **phone**. Design mockups: **`design/ravilo/Ravilo TV.html`** (`ravilo-app.js` ·
> `ravilo-data.js` · `ravilo.css`) and **`design/ravilo/Ravilo Mobile.html`**; direction picked in
> **`design/Multi-Episode Files — Option B.html`**.

## Goal
On a series whose season is stored as multi-episode files (e.g. Johnny Bravo S1 — 24 episodes in 8 files),
the episode strip shows **one card per file**: a combined "Episodes X–Y" card whose artwork is a
three-panel **triptych** (each episode's still, split by two lightly-skewed seams, numbered), listing the
contained episodes and their runtimes. Nothing reads as missing. Single-file episodes render as normal
episode cards, unchanged.

## Current state (verified)
- Ravilo's series detail (`renderDetail`) lays out episodes as individual `EpisodeCard`s from the server-
  pushed `SeriesDetail`. A multi-episode file surfaced only its first episode → visible gaps.
- Phase 149 makes the server model the file as **N episodes** that each carry a shared `file`, a
  `partIndex`, optional `chapterStart`, and `hasChapters`. The frontend groups by that server-provided
  file/group id — it **never parses filenames** (constitution: the frontend renders server-pushed state only).

## Requirements

### A. Grouping & the combined card (Option B)
1. In series detail, **group consecutive episodes that share a `file`** into one render unit. A group of
   >1 renders as a single **combined card**; a lone episode renders as today's `EpisodeCard`.
2. The combined still is a **triptych**: the group's episode stills placed left→right, divided by **two
   seams tilted ~8°**, each panel bearing its episode number. Files with >3 episodes show the first three
   panels; the label still spans the full range.
3. Card content: an **"Episodes X–Y"** range label (on the still and as the title), the contained episodes
   listed with their runtimes, and a **"1 file · N episodes · <total>m"** line. Combined runtime = sum of
   the episodes.

### B. Playback
1. Selecting the combined card **plays the file**. With chapters (`hasChapters`), playback enters at the
   resume/next episode's `chapterStart`, and the hero **Play/Resume** + "Up next · E{n}" resolve at
   **episode granularity** (which chapter to resume). Without chapters, the file plays as **one continuous
   unit** from the start.
2. Continue Watching / Next-Up reference the specific episode (and chapter offset where supported), not the
   whole file, so resume lands in the right segment.

### C. Watched-state
1. **Aggregate on the card:** all-episodes-watched shows the watched treatment (dimmed still + ✓); a
   partially-watched group shows an in-progress bar. The card's **mark-watched toggles every episode in the
   file together**.
2. **Season progress counts individual episodes** (e.g. "1 of 24 watched"), not files — the season bar and
   season-picker fractions stay per-episode. With chapters, finishing a chapter marks that episode watched
   (rides the R147/R176 watched-state propagation); without chapters, finishing the file marks the group.

### D. Surfaces
1. Apply on **TV** (`Ravilo TV.html` series detail) and **phone** (`Ravilo Mobile.html`). The phone detail
   gains an **Episodes** section (it had none) rendering the same combined triptych cards.
2. Focus/D-pad navigation treats the combined card as one card (play surface + watched toggle), so the
   season strip stays keyboard-navigable with the correct default focus on the resume item's group.

### E. Data
1. The combined card is built entirely from server-pushed `SeriesDetail` — episodes carry `file`,
   `partIndex`, `partCount`, `chapterStart`, `hasChapters`. No filename logic on the client.

### F. i18n
1. New en/da/fo strings: "Episodes {a}–{b}", "{n} episodes · one file", and the runtime/label bits
   (`Strings.kt` + `design/ravilo/ravilo-i18n.js`).

## Reuse
`EpisodeCard`/tile + focus-row model, the player chrome + resume pointer, watched-state propagation
(R147/R176), and the R33 live-config push so a server-side re-group reaches the TV without a restart.

## Non-goals
- **No client-side file splitting** — the client shows what the server groups.
- The triptych shows **up to three** stills (first three of a larger group + the full range label); no
  N-panel montage.
- **No per-chapter scrubber UI** beyond the normal player when chapters are present; no timeshift here.
- Behaviour for single-file episodes is unchanged.

## Acceptance
- A multi-episode series (Johnny Bravo S1 — 24 eps / 8 files) shows **8 combined triptych cards**; nothing
  reads as missing; each card lists its contained episodes.
- Selecting a card plays the file (entering at the resume chapter when present); **mark-watched toggles the
  file's episodes together**; the season reads **N of 24** watched with a per-episode bar.
- Works on **TV and phone**; the strip stays D-pad-navigable with default focus on the resume group.
- en/da/fo strings present; the client never inspects filenames.

## Status note
Design-authored, `Planned`, not yet dev-reviewed. Exports to
`specs/ravilo/requirements/phase-R179-multi-episode-file-card.md`; `scripts/check-phases.sh` will flag it
for a `STATUS.md` row. Pairs with **[Phase 149](../../requirements/phase-149-multi-episode-files.md)**.
**Next Ravilo number after this is R180.**
