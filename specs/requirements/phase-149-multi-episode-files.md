# Phase 149 — Multi-episode files: one file, several episodes (FR-MEF1)

> Some episodes ship **three-to-a-file**: `Johnny.Bravo.S01E01E02E03.NORDIC.PDTV.x264-ROCKETRACCOON.mkv`,
> so a 24-episode season lives in **8 files**. Today the scanner maps such a file to **episode 1 only** —
> E02 and E03 get no item and show up **missing** in the Library and in Ravilo. This phase makes
> jellystructure model one physical file as **N episodes** (each with its own metadata, still, watched-
> state), write the correct multi-episode NFO, and present it correctly in the admin — while never
> splitting or re-muxing the file. The Ravilo/phone viewing side is
> **[Phase R179](../ravilo/requirements/phase-R179-multi-episode-file-card.md)**.
> Design mockup: **`design/app/series-johnnybravo.html`** (combined file rows + per-episode artwork
> picker); exploration in **`design/Multi-Episode Files.html`** and **`design/Multi-Episode Files — Option B.html`**.

## Goal
A file whose name spans an episode range (`S01E01E02E03`, `S01E01-E03`, `1x01x02x03`, …) is ingested as
**every episode it contains**, each a first-class item with its own title/overview/still/air-date and
watched-state, all pointing at the same physical file (with chapter offsets when the container has them).
The admin shows one **combined row per file** (expandable to its episodes) and a **per-episode artwork
picker**. A multi-episode file is a **normal, valid state — never a triage issue**.

## Current state (verified)
- The scanner derives an episode from the filename's `SxxEyy`. A multi-episode filename resolves to a
  single episode (the first match), so the other episodes in the file are **never created** — they read as
  gaps in Seasons & episodes, in coverage counts, and in Ravilo's episode strip.
- Jellyfin itself understands multi-episode files (it reads the stacked numbering) and its NFO convention
  is **multiple `<episodedetails>` blocks in one `.nfo`** (or per-episode nfos), one per contained episode.
- `ffprobe` already runs at scan time; when a file has **chapter markers** at the episode boundaries they
  give per-episode start/end offsets. Many files have none (one continuous stream).
- Constitution holds: jellystructure is the metadata/organization layer; it never edits the media stream
  except through the existing track-edit path (mkvpropedit/ffmpeg), which operates on the **whole file**.

## Requirements

### A. Detection & parsing
1. Recognize multi-episode filenames and parse the **full contained range**, not just the first number:
   `SxxEyyEzz`, `SxxEyy-Ezz`, `SxxEyyEyzEzz…` (arbitrary count), `1x01x02`, `Eyy-Ezz`. Widen the existing
   `SxxExx` matcher (it already grew for `S2025E01` in Phase 53) rather than adding a parallel parser.
2. From one physical file, create **N episode records** — one per contained episode number — each carrying
   its own `(season, episode, title, overview, still, aired)` and all referencing the same `filePath`.
3. **Chapter detection (ffprobe):** if the container has chapters whose count matches the contained
   episodes, map each episode to a `chapterStart`/`chapterEnd` (ms) and set `hasChapters = true`. Otherwise
   `hasChapters = false` (continuous file) — the episodes still exist as metadata but share one playable
   span. Never *create* chapters; only read what exists.

### B. Data model
1. `Episode` (see [`plan.md`](../plan.md)) gains: `file` (shared physical path), `partIndex` (0-based order
   within the file), `partCount`, `chapterStart`/`chapterEnd` (nullable ms), `hasChapters`. A **file group**
   is the set of episodes sharing a `file`, ordered by episode number.
2. Episode **identity is stable across re-scans** — keyed by `(series, season, episode)`, **not** by
   filename position — so per-episode watched-state and artwork survive a re-scan or a rename (reuses the
   Phase 53 `itemId` discipline).
3. The additive fields must be tolerated by older consumers (`ignoreUnknownKeys` on the Ravilo DTO), as with
   the Phase 140 `query` field.

### C. Per-episode metadata & artwork
1. Each episode fetches its **own** TMDB metadata (title/overview/still) exactly like a normal single-file
   episode — independent of its file siblings. Per-episode stills are cached/served per the existing
   artwork path (Phase 47/81), one still per episode number.
2. The admin **Artwork** surface treats each episode independently: pick one still from TMDB per episode,
   the standard gallery (`.art-cand` `current`/`sel`, ON-DISK ribbon). Changing E02's still touches only E02.

### D. NFO
1. On **Save → NFO**, write the multi-episode NFO Jellyfin expects: **N stacked `<episodedetails>` blocks**
   in the file's `.nfo` (one per contained episode), each with its own `<season>`, `<episode>`, `<title>`,
   `<plot>`, `<thumb>`, `<aired>`. Round-trips with re-pull.
2. **Sync Jellyfin** re-reads them; Jellyfin then presents the file as its multiple episodes.

### E. Editing & the shared physical file
1. Track edits (reorder / default / forced via mkvpropedit/ffmpeg) operate on the **shared file once** and
   therefore affect **all N episodes**. The editor must state this plainly ("This file contains E01–E03 —
   the edit applies to all three"). Do not offer per-episode track edits for a shared file.
2. The qBittorrent seeding-guard (Phase 26/37) and cross-seed accounting key on the **file**, so a lock
   covers the whole group.
3. **No file splitting / re-muxing into separate files** — out of scope (see Non-goals).

### F. Admin series page (Seasons & episodes + Artwork)
1. **Seasons & episodes:** render **one row per file** (Option B) — `S01E01–E03` · combined runtime · a
   **"3 in 1 file"** badge · the source filename · a **mini-triptych** thumbnail composed of the 3 stills.
   The row is **expandable** to its episodes, each with its chapter offset and (for the shared file) an
   **Edit tracks** entry. A per-file chapter indicator ("chapters" / "no chapters — continuous").
2. **Artwork tab:** a **per-episode picker** — one column per episode (selected still + candidate strip +
   "More from TMDB"), with a **file switcher** to move between the season's files.
3. A multi-episode file is **not** an attention item — no dirty/triage badge for the fact of being multi-
   episode (per-episode issues like a missing still still surface normally).
4. Design target: **`design/app/series-johnnybravo.html`**.

### G. Library, coverage & recency
1. All N episodes count individually in episode totals, coverage/NFO-covered counts (Phase 89), and
   **Newly Added** (each episode carries its own `createdAt`, Phase 108). Each contained episode is
   independently searchable (multi-language search, Phase 29).

## Non-goals
- **No splitting or re-muxing** the physical file into separate per-episode files.
- **No chapter authoring** — only read existing chapter markers; a chapter-less file is fully supported as a
  continuous unit.
- **No per-episode track edits** on a shared file (the stream is one file).
- No new triage type for multi-episode files (they are a normal state).

## Acceptance
- A `S01E01E02E03` file ingests as **three episodes**, each with its own title/overview/still and stable
  watched-state; none read as missing anywhere (Library, coverage, Ravilo).
- The series page shows **one combined row per file**, expandable to its episodes with chapter offsets; the
  **Artwork** tab picks stills **per episode**; a file switcher moves between files.
- **Save → NFO** writes N `<episodedetails>` blocks; **Sync Jellyfin** makes Jellyfin present the episodes.
- A track edit on a shared file **warns it applies to all contained episodes**; the seeding-guard covers the
  whole file.
- Chapter status is shown; a chapter-less file works as a continuous unit with no error.

## Status note
Design-authored, `Planned`, not yet dev-reviewed. Exports to
`specs/requirements/phase-149-multi-episode-files.md`; `scripts/check-phases.sh` will flag it for a
`STATUS.md` row. Pairs with **[R179](../ravilo/requirements/phase-R179-multi-episode-file-card.md)**.
**Next admin number after this is 150.**
