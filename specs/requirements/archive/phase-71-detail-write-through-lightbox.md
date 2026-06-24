# Phase 71 — Detail-page write-through editing + artwork lightbox (FR-DE1)

**Status:** ✓ Done

> Authored from the design project. Slot into the phase index where it fits; the FR code
> (FR-DE1) is the stable reference.

## Problem

Editing on Movie/Series detail used a **two-step stage → apply** model in two places:

- **Artwork** — picking a TMDB candidate *staged* it (a `sel` outline + a footer with
  Discard / Save to disk) before it was written.
- **Tracks & order** — every flag/order edit accumulated in a **"Staged changes"** card
  (an ops list, the exact `mkvpropedit`/`ffmpeg` command preview, a cost chip, and an
  **Apply to file** / Discard pair).

This added ceremony to changes that are individually cheap and reversible, and the two
surfaces behaved differently. Separately, the artwork gallery had **no way to view a
candidate larger** than its grid tile.

## Change A — write-through (no staging)

Both surfaces now **write to disk immediately**; the explicit Apply/Save-to-disk step is
gone.

- **Artwork:** clicking a candidate (or **Use this artwork** in the lightbox) writes it to
  disk at once as `poster.jpg` / `fanart.jpg` / `clearlogo.png` / …; the green **ON DISK**
  outline moves to it. Upload / paste-URL also write immediately. The footer is now a
  static helper line, not a stage/save bar.
- **Tracks & order:** the **Staged changes** card (ops list + command preview + cost chip +
  Apply/Discard) is **removed**, replaced by a small persistent note. Every edit — set
  default, forced toggle, language, reorder (arrows or drag) — writes on the spot.
- **Cost is surfaced honestly via the toast**, not a gate: flag/language/default/forced →
  **`mkvpropedit`** in place (~40 ms, no re-encode); a re-order → **`ffmpeg -c copy`**
  remux (no re-encode, but rewrites the file).
- **Sync model:** writes land on **disk**; **Save & sync to Jellyfin** pushes now,
  otherwise Jellyfin picks the change up on its next scan. No `<lockdata>` (consistent with
  Phase 22). The per-episode editor on Series detail follows the same write-through.

## Change B — artwork lightbox

Each gallery candidate gains a hover **⤢ zoom** affordance that opens a **large preview
overlay**:

- Big image centred on a dimmed backdrop, sized per asset kind (poster / backdrop / logo /
  banner), with an info panel: **language, resolution, aspect, TMDB vote**, and the target
  filename.
- Action: **Use this artwork** (writes immediately + closes) or **✓ Currently on disk**.
- **Prev/next arrows + ← / → keys** step through the *currently filtered* candidates; **Esc**
  or click-outside closes; an `n / N` counter shows position.
- Single-clicking a card now **writes** (it used to stage); the zoom button is a separate
  affordance, so nothing else changed.

## Scope / invariants

- No new backend contract beyond dropping the explicit **Apply** step — writes already went
  through the same NFO / `mkvpropedit` path; they now fire per-edit instead of on Apply.
- Reuses the existing artwork resolver, filters (Phase 47/48) and track editor (Phase 27).

## Mockup

`design/app/media.html` (artwork lightbox + write-through tracks/artwork),
`design/app/series.html` (per-episode write-through).
