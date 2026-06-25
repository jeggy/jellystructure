# Phase 74 — Metadata write-through to the library DB; NFO write on explicit Save (FR-WM2)

**Status:** ✓ Done · _design_

> Authored from the design project (`design/app/media.html`). **Supersedes
> [Phase 9](archive/phase-09-dirty-indicators-diff-popup.md)** (per-field dirty indicators +
> diff popup) and **revises the trigger model of
> [Phase 73](phase-73-write-through-metadata-sync.md)**. Pairs with
> **[Phase 71](archive/phase-71-detail-write-through-lightbox.md)** (artwork/track
> write-through).

## Problem

Editing metadata on Movie/Series detail used a **stage → apply** model: title, year, plot,
director and studio showed an **amber "dirty"** border, a **"Save changes"** button appeared,
and a word-level **diff popup** let you review the pending edit before committing. Tags had the
same outline-ring staging. This is the last staged surface on the detail page after Phase 71
made artwork and tracks write-through — and it is inconsistent with how the rest of the page now
behaves.

## Change

- **No staging, no "Save changes" button.** Editing any metadata field (title, year, plot,
  director, studio) **writes directly to the library database** on change, with a small
  "*<field>* saved to library" toast. Adding/removing a **tag** writes immediately too.
- The per-field **amber dirty** borders, the **diff popup** (`#diff-modal`, `.diff-trigger`),
  and the tag **dirty outline** are **removed**.
- The Metadata card header now reads *"saved to library as you edit · Save writes the NFO ↗"*.
- The top split button is reframed into two clearly-named operations — **Save → NFO** and
  **Sync Jellyfin** — plus a **Save & Sync** primary (see below).

## DB vs disk: two distinct steps

This phase makes explicit a separation the product wants — **storing** an edit and **writing it
to disk / publishing it to Jellyfin** are different actions:

1. **Edit → DB (automatic, no button).** Every field/tag edit in the UI — changing the title,
   adding/removing a tag, editing the plot, year, director, studio — is written to the
   Jellystructure **database immediately** on change (the `PATCH …/metadata` call), with a
   small "*<field>* saved to library" toast. There is **no "Save changes" button**; the DB is
   the live source of truth and nothing is ever "pending" in the UI.

2. **Save / Sync → the existing top split button.** The two disk/Jellyfin operations live in the
   one split button in the detail pagebar, with clear, separate meanings:
   - **Save → NFO** — write our stored setup (metadata, tags, artwork) to the `.nfo` files on
     disk. *Our DB → disk.*
   - **Sync Jellyfin** — ask Jellyfin to re-read the `.nfo` files (a per-item refresh; no
     rewrite). *Disk → Jellyfin.*
   - **Save & Sync** (the primary face) — do both in sequence: write the NFO, then ask Jellyfin
     to re-read it.

   If the operator never presses Save, Jellyfin still picks the NFO up on its **next scan** once
   it has been written — but the NFO write itself is the explicit **Save** action, and the
   Jellyfin refresh is the explicit **Sync** action.

### Relationship to Phase 73 (revision)

Phase 73 made the backend **auto-push to Jellyfin on every metadata PATCH** (launch
`pushToJellyfin` right after `store.updateOne`). Under this phase the PATCH **persists to the DB
only**; the NFO write moves to **Save → NFO** and the Jellyfin refresh moves to **Sync
Jellyfin**. This avoids an NFO rewrite + Jellyfin per-item refresh on **every keystroke-commit**
(each field's `change` event would otherwise fire a full push), and gives the operator two
deliberate, clearly-labelled actions instead of an implicit one.

- **Backend implication:** `PATCH /api/media/{id}/metadata` and
  `PATCH …/episodes/{ep}/metadata` must **persist to the DB only** (`store.updateOne`) and **not**
  call `pushToJellyfin`. Expose the two operations as explicit endpoints driven by the buttons:
  **Save → NFO** writes the `.nfo` (NfoWriter, already emits every field per the Phase 73
  table); **Sync Jellyfin** triggers the per-item refresh; **Save & Sync** does both.

## Scope / invariants

- Comparison/display only on the client; storage and write forms are unchanged.
- Field locks (Phase 22) and drift detection (Phase 33) banners still apply — a saved-to-DB
  field that Jellyfin has locked still surfaces the lock warning on the next write.
- Per-episode metadata on Series detail follows the same write-through-to-DB model.

## Mockup

`design/app/media.html` (metadata fields + tags write through to DB on edit; "Save changes"
button, dirty borders and diff popup removed; top Save split writes NFO),
`design/app/series.html` (per-episode parity).
