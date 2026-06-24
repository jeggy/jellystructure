# Phase R53 — Channel editor as a dedicated page + channel-button padding (FR-RV-C1)

**Status:** ✓ Done

> Authored from the design project. Revises **[R36](phase-R36-channel-button-editor.md)**
> (the channel-button editor, which opened in a popup).

## Problem

The Channel / Collection editor was a **popup modal**. That was fine for a few fields, but
it became cramped once a channel could also carry its own **Page hero** (R52) — and opening
the hero-item picker (itself a modal) on top of another modal is awkward. Channels are now
the richest thing in the layout and deserve room.

## Change A — channel editor is its own page

Adding or editing a **Channel / Collection** opens a **dedicated in-app page**, not a popup:

- The app chrome (sidebar / top bar) stays; the config layout is hidden behind the editor,
  so it reads as navigating to a sub-page.
- A **"‹ Back to layout"** bar with **Cancel / Save changes** returns to the Channels list.
- Because the editor is a page (not a modal), the **hero-item picker opens as a normal
  popup on top** of it and returns to the page on save.
- **Content rows** and **Library filters** keep using the quick **popup** — only the
  channel editor is promoted to a page.

## Change B — channel-button padding

The channel button (Logo or Text, R36) gains per-display **padding**:

- **Top / Right / Bottom / Left**, **0–40 px**, stored **separately for Logo vs Text**
  (each display mode keeps its own padding).
- Applied **live** to the channel-button preview and written into the saved button.
- The control is **collapsed behind a small icon** by default and **auto-expands** when
  padding is already set, with a compact **summary badge** (e.g. `14/0/0/10`) on the toggle
  so a configured value is visible even when collapsed.

## Data model

- `ChannelConfig` gains a `padding` map keyed by display mode
  (`logo` / `text` → `{ top, right, bottom, left }`, px, clamped 0–40).
- No change to the R36 `logoUrl` / `brandColor` / display-mode fields.

## Scope / invariants

- Only the channel editor becomes a page; rows + Library filters stay popups.
- Padding is purely presentational (insets the button content); it does not affect the
  channel's filter or scoped rows.

## Mockup

`design/app/ravilo-config.html` (channel editor opens as a page),
`design/app/ravilo-builders.js` + `design/app/ravilo-builders.css` (screen host, padding
control, hero-item picker layering).
