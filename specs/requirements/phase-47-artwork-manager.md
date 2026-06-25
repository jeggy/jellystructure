# Phase 47 — Artwork manager on Movie & Series detail (FR-AM1)

> Builds on **[Phase 31](archive/phase-31-studio-network-artwork.md)** (fetch/cache/serve artwork) and
> **[Phase 32](archive/phase-32-tmdb-match-picker.md)** (in-app TMDB picker) and the Phase 3 artwork
> downloader. This phase specifies the **per-item artwork editing UX** that sits on the detail-page
> **Artwork tab** — it does not change how artwork is cached or written, only how an operator browses,
> filters, picks, uploads and saves it.

## Problem
The Artwork tab on Media detail is a stub — three static slots (`poster.jpg`, `fanart.jpg`,
`clearlogo.png`) with no way to browse TMDB candidates, compare against what's on disk, handle the
common case where TMDB returns dozens of options, or deal with art that only exists in other
languages. Series have no season-poster / episode-still management at all. Operators fall back to
editing files by hand.

## Goal
Each detail page gets a full **Artwork manager**: pick an asset from a rail, browse its TMDB
candidates in an inline gallery with language/quality filters, drop in your own image, and save to
disk — with language handling that **defaults to the title's resolved language and falls back so the
picker is never empty when TMDB has art**.

## Current state (as-is)
- `media.html` Artwork tab: three non-interactive `imgslot` placeholders. `series.html`: none.
- Backend already fetches/caches TMDB artwork and writes `poster.jpg` / `fanart.jpg` /
  `clearlogo.png` / season + episode art (Phase 3, 6, 31). **TMDB image API already returns an
  `iso_639_1` language per image, including `null` (no-language / textless).** No backend change is
  required for the picker beyond exposing the candidate list + language tag already held.

## Requirements

### A. Asset rail + inline gallery (Movie detail)
1. The Artwork tab is split: a left **asset rail** lists every artwork type — **Poster** (2:3),
   **Backdrop** (16:9), **Clearlogo** (transparent), **Banner** (5.4:1) — each with a status dot
   (on-disk / low-res / missing), the on-disk language + resolution, and a thumbnail.
2. A **"Fetch all missing"** action fetches the best candidate for every missing asset in one go,
   using the same language rules (§C).
3. Selecting a rail item loads its **inline gallery** on the right; the on-disk asset is labelled with
   an on-disk badge (language + resolution) and the TMDB candidate count.

### B. TMDB candidate gallery
1. Candidates render as a hover-zoom grid at the asset's aspect ratio. Each card shows a **language
   pill** (or **"no-lang"** for textless), a **vote-score** pill, and the **resolution**; the
   candidate currently on disk is outlined in **green** with an **"ON DISK"** ribbon.
2. A filter/sort bar offers: **language chips** (§C), a **Prefer** control (§D), **Hi-res only**, and
   a **sort** segment (**Vote ★** / **Resolution**).
3. Clicking a candidate **stages** it (violet outline); a footer shows the staged choice and
   **Save to disk** / **Discard**. Nothing is written until **Save to disk**.

### C. Language handling — resolved-first, never-empty fallback
1. Candidates default to **this title's resolved metadata language** (e.g. `en`), plus **no-language**
   (textless) which is preferred for clean posters/logos.
2. If the resolved language has no candidates, fall back down a ladder until something exists:
   **resolved language → no-language (textless) → any language**. The picker is **never empty when
   TMDB has any art**.
3. **TMDB no-language (`iso_639_1 = null`, surfaced as `xx`) is its own bucket, distinct from
   "All".** The "All" chip means every language *including* no-language; the "No language" chip means
   *only* textless art. Language chips only list languages that actually have candidates, with a count.
4. A short, always-visible **explainer line** states exactly which filters are active, why (matched to
   resolved language / fell back because none existed), and **how many candidates are hidden**, with a
   one-click **"Show all N →"**. When a fallback is in effect the line is amber (e.g. *"No English
   clearlogo on TMDB. Falling back to No-language (3). 9 other-language candidates hidden."*).

### D. Prefer control
1. A two-chip **Prefer** control — **Textless** and **With text** — with **neither selected by
   default**. Selecting one sorts that kind first; clicking the active chip deselects it. The two are
   mutually exclusive.

### E. Add your own image
1. Each asset's gallery has a **dropzone**: **drag-and-drop** an image onto it to replace the asset,
   plus **Upload** (file browse) and **Paste URL** fallbacks. A dropped/uploaded image stages like a
   TMDB pick and writes on **Save to disk**.

### F. Save / write behaviour
1. Saving writes the asset beside the media file using the constitution's filenames — `poster.jpg`,
   `fanart.jpg`, `clearlogo.png`, `banner.jpg` — and updates the rail status + on-disk badge.
2. Writes are **explicit** (Save to disk), consistent with the app's manual, preview-first ethos.
   Field-lock / Jellyfin-side-lock detection (Phase 22) applies to artwork writes as elsewhere.

### G. Series detail — seasons & episode stills
1. **Series-level** poster / fanart / clearlogo use the same rail + gallery as movies.
2. A **Season posters** board shows one poster per season (incl. Specials) with status + per-season
   **Fetch / Replace**, and a **Fetch missing** batch action.
3. An **Episode stills** list shows each episode's still with status (ok / low-res / missing), a batch
   **"Fetch N missing stills"**, and per-row **Fetch / Replace**. Stills are fetched in **the
   episode's own resolved language first, then no-language** (episodes resolve independently — Phases
   6/12), and written beside each file as `{episode}-thumb.jpg`.
4. A compact left-rail note states the artwork-language policy and the series' Uniform/Mixed badge
   (reusing Phase 12 series-language framing).

## Invariants
- Artwork filenames and write locations follow the **constitution** (`poster.jpg` / `fanart.jpg` /
  `clearlogo.png` / `banner.jpg`; `{episode}-thumb.jpg`); no new on-disk layout.
- Language defaults to the **resolved language**; **no-language (`xx`) is a distinct bucket from
  "All"**; the picker never shows nothing when candidates exist.
- Writes are **explicit and preview-first**; respects Jellyfin field-lock detection (Phase 22) and the
  seeding guard where relevant.
- Reuses the Phase 31/32 fetch/cache/serve + match infrastructure — **no new artwork backend
  pipeline**.

## Out of scope
- Changing how artwork is cached, served, or named on disk (Phase 31).
- Bulk artwork operations across the whole library (possible later phase) beyond the per-item
  "Fetch all missing" / per-season / per-episode batches defined here.
- Choosing the TMDB *match* for an item (Phase 32) — this phase assumes the match is correct and
  manages its artwork.

## Design reference
`design/app/media.html` (Artwork tab — asset rail + inline gallery, language fallback incl. the
clearlogo no-English case) and `design/app/series.html` (season posters + episode stills). The
side-by-side exploration of the three layout directions is preserved in
`design/app/Artwork Manager.html`.
