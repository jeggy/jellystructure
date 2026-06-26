# Phase R64 — Rename "Channels & Collections" → "Channels" (FR-RV-C2)

> Authored from the design project. Copy-only follow-up to the channel work in
> **[R36](phase-R36-channel-button-editor.md)** / **[R53](phase-R53-channel-editor-page-padding.md)** /
> **[R59](phase-R59-per-channel-content-rows.md)**.

## Problem
The config editor section that manages the Disney+-style logo row is titled **"Channels &
Collections"**, and assorted copy refers to "channels and collections" as if they were two things.
They are not: a channel **is** a saved filter that renders as a button and opens a page — there is
no separate "collection" entity. The double-barrelled name is longer, harder to localize (en/da/fo),
and implies a taxonomy that doesn't exist.

## Goal
Use **"Channels"** (singular concept) everywhere — section heading, nav item, helper copy, button
labels, and the TV-side wording — dropping "& Collections" / "collections".

## Requirements
1. **Admin config editor** (`design/app/ravilo-config.html`, `RaviloConfig.kt`): the section heading
   `Channels & collections` → **`Channels`**; the left-nav item already reads "Channels" (keep). Update
   the helper paragraph and any "Add channel / collection" affordance copy to "channel".
2. **TV** (`ravilo-app.js`, `ChannelScreen`/`AppBar` wording): any user-facing "collection" string →
   "channel". The Movies/Series/My List tabs are unaffected (they are browse destinations, not channels).
3. **Localization:** update the `channels`/`collections` keys in `ravilo-i18n.js` (and the Compose
   string resources) for **en / da / fo** so the new single term is translated, not just the English
   string.
4. **No behaviour, model, or layout change** — this is a naming pass only. `ChannelConfig`, the
   workbench, scoped rows (R59) and per-channel heroes (R52) are untouched.

## Invariants
- One term, one concept: a **channel** is a saved filter rendered as a button that opens a page.
- All three languages stay in sync; no English fallback left in da/fo.

## Out of scope
- Any change to channel behaviour, the editor, or the filter model.
- Renaming "Content rows", "Hero carousel", or the browse tabs.

## Mockup
`design/app/ravilo-config.html` (Channels section heading + copy), `design/ravilo/ravilo-app.js` +
`ravilo-i18n.js` (TV wording + translations).
