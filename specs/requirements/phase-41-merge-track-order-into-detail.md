# Phase 41 — Unified track & order editor on Media Detail (movie) (FR-TO1)

"Tracks & order" tab becomes the single, complete track editor._

> Series episodes get the **same** editor via a per-episode modal — specified separately in
> **[Phase 42](phase-42-series-episode-track-editor.md)**, which reuses everything defined here.

## Problem
There are **two places** to manage a movie's tracks today, and it's confusing:
1. The **Tracks & order** tab on media detail — inline language assignment, set-default, subtitle
   forced flags (Phases 21/27/39).
2. A **separate `#/track-order?id={}` page** (`TrackOrder.kt`) — the only place that does real
   **reordering**, shows the **before → after** staging, and renders the **exact `mkvpropedit` /
   `ffmpeg` command** with the remux-cost warning before Apply.

The tab links *out* to that page ("Full reorder editor ↗"), so a routine task bounces the operator
between two screens with overlapping-but-different controls.

## Goal
The movie **Tracks & order** tab becomes the **single, complete** track editor. The standalone
`/track-order` route and `TrackOrder.kt` page are **deleted**.

## Current state (as-is)
- `MediaDetail.kt` Tracks tab: Audio/Subtitles segmented toggle, per-track language picker,
  set-default, subtitle forced toggle, the "default ≠ resolved" cascade alert, the qBittorrent guard
  chip (Phase 37), and a link to `track-order.html`.
- `TrackOrder.kt` (`/track-order?id=`): current-vs-after diff, drag/star to stage, exact-command
  preview, cost chips, Apply.
- Backend track ops already exist under `/api/media/{id}/tracks/**` (set language, set default,
  reorder, forced) applied via `mkvpropedit` (flags, instant) or `ffmpeg -c copy` remux (reorder),
  with the seeding guard (Phases 26/37). **No backend change is required** — this is a UI merge.

## Requirements

### A. One editor on the Tracks & order tab
1. Remove the "Full reorder editor ↗" link and delete the `/track-order` route + `TrackOrder.kt`. Any
   in-app links to it (e.g. Dashboard "Set track defaults" quick action) point to `media#tab=tracks`.
2. A **model-driven editor** over the item's real tracks. The track list has a column header
   (grip · # · track · language & codec · default) and one row per track. Each row provides:
   - **Reorder** — a drag **grip** *and* **▲▼** buttons (▲ disabled on the first row, ▼ on the last);
     reordering updates the live **1-based position** immediately.
   - **Language** — click the language chip to open a **searchable language picker** (see A5);
     untagged tracks show a red "no language" badge + the same picker via an **assign** affordance.
   - **Default** — **★ set default** (exactly one per track kind). See the multiple-default rule (B3).
   - **Forced** — subtitles only (Phase 39); a toggle **separate** from default.
3. An **Audio / Subtitles** segmented control switches which kind is shown; the column-mid header and
   the explainer copy (D) swap with it. Both kinds share the same row affordances (audio has no
   forced toggle).
4. Drag-and-drop reorder shows a clear drop indicator (insert-before/after); the grip uses the
   grab cursor.
5. **Searchable language picker (reuses Phase 11).** Every language control on this tab uses the
   shared **searchable dropdown**, not bare code buttons:
   - A **search box** filters as you type, matching on **English name, endonym, and ISO code**
     (e.g. "far", "før", "fo" all find Faroese).
   - Each option shows the **full display string** — language name + code (e.g. "English (en)",
     "Faroese (fo)") — never the bare code alone. The collapsed chip may show the code, but the
     menu and the selected row label show the full name.
   - Keyboard-navigable (↑↓ + Enter), focus-trapped while open, dismiss on Esc/outside-click; styled
     consistently with the rest of the app (same dropdown component as the Settings/series language
     pickers from Phase 11).
   - Covers the full ISO-639 set, not a hardcoded short list; recent/likely languages may sort first.

### B. Warnings & alerts inventory (each must be present, with trigger → message → resolution)
1. **Default ≠ resolved (audio cascade)** — *Trigger:* the default audio track's language ≠ the
   item's resolved metadata language. *Message:* "The default audio track is `<lang>` (`<name>`) but
   metadata resolves to `<resolved>`. Viewers would read `<resolved-name>` descriptions while hearing
   `<lang-name>`." *Resolution:* a one-click **"Make `<resolved>` default"** action; the alert clears
   when they match. Audio tab only; hidden when aligned.
2. **Multiple default audio** (Phase 21) — *Trigger:* more than one track of a kind has the default
   flag. *Message:* "Multiple default audio tracks. A file should have exactly one." *Resolution:*
   every default row shows a **"★ keep this one"** action that makes that row the sole default and
   clears the others.
3. **Untagged track** — *Trigger:* a track has no language. *Message:* red "no language" badge + the
   row styled as an error. *Resolution:* the inline **assign** row sets a language (staged like any
   edit). Untagged audio also explains it is skipped by the language resolver until tagged.
4. **Remux cost** — *Trigger:* the staged set includes a **reorder** (or the container is non-MKV).
   *Message:* a "⚠ remux" indicator: "reordering streams needs an `ffmpeg -c copy` remux (no
   re-encode, but rewrites the file — minutes on large files)." *Resolution:* informational; contrast
   with the "instant" indicator shown when only flag/language edits are staged.
5. **Seeding guard active** (Phase 37) — *Trigger:* the qBittorrent guard is configured/active.
   *Message:* a "⛨ guard active" chip explaining edits to an actively-seeded file are blocked.
   *Resolution:* Apply on a seeded file returns **409** and is blocked (Phase 26/40 semantics).

### C. Staged changes + exact command
1. As the operator edits, a **Staged changes** panel appears listing each pending op in plain language
   with an icon: reorder (⇅), language (🏷), default (★), forced (⮕).
2. Show the **exact command** that will run on Apply, syntax-highlighted, runnable only on Apply:
   - flag/language edits → **`mkvpropedit "<file>" --edit <sel> --set …`** using **1-based,
     type-relative** selectors (`track:a1`, `track:s2`, …), in place, ~instant, no re-encode.
   - any **reorder** (or non-MKV) → **`ffmpeg -i "<file>" -map … -c copy "<file>.reordered.mkv"`**
     (no re-encode). When both kinds of change are staged, show both commands.
3. A **cost indicator** distinguishes the instant (`mkvpropedit`) path from the remux (`ffmpeg`) path
   (B4). **Apply** and **Discard** act on the whole staged set; nothing touches the file until Apply;
   the panel hides when there are no changes. Apply respects the seeding guard (B5).

### D. Per-kind explainer copy (always visible under the list)
1. **Audio:** a two-item grid — "**▲▼ Order**: the track's index; Jellystructure reads audio tracks
   top-down to decide the metadata language (first match wins); players fall back to the first track."
   and "**★ Default**: the track the player auto-selects on playback — independent of order, and
   changing it never affects the metadata language."
2. **Subtitles:** "**★ Default**: the subtitle shown automatically." and "**⮕ Forced**: a *separate*
   flag — only foreign-dialogue lines; a track can be forced without being default."
3. A footnote reiterating everything is **manual** and only writes on **Apply**.

## Invariants
- **One editing surface** for a movie's tracks: `media#tab=tracks`. No `/track-order` route.
- **Manual, explicit, preview-first** — order/flags change only on Apply; exact command + cost shown
  first; nothing automatic.
- **`mkvpropedit` for flags, `ffmpeg -c copy` for reorder** — never a re-encode; POSIX perms kept.
- Exactly one default per track kind after any default edit.
- Reuses existing `/api/media/{id}/tracks/**` endpoints — **no backend change**.

## Interacts with (not re-specified here)
- The **Jellyfin field-lock banner** (Phase 22) and **Jellyfin ⇄ NFO drift banner** (Phase 33) also
  render on Media Detail; they are independent of this tab.

## Out of scope
- Series episodes — see **[Phase 42](phase-42-series-episode-track-editor.md)**.
- New backend track operations (all already exist); bulk edits; any re-encode/format conversion.

## Design reference
`design/app/media.html` (Tracks & order tab) implements this; `design/app/track-order.html` is
**removed**.
