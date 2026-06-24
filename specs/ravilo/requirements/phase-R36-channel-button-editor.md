# Phase R36 — Channel-button editor: logo asset upload, custom brand color/gradient, discoverable edit

**Status:** ✓ Done

> Revises **[R32](phase-R32-unified-filter-workbench.md) §C2** (the channel-button styling inside the
> shared workbench) and the channel parts of **[R16](phase-R16-jellystructure-config-screen.md) §3** /
> **[R28](phase-R28-config-editor-fidelity.md)**. Extends the `Channel` model in
> [`../plan.md`](../plan.md) (`logoUrl?`, `style: LOGO|TEXT`, `brandColor`). All writes still go through
> the per-user `RaviloConfig` store (R04/R26) and sync live via **[R33](phase-R33-live-config-push.md)**.

## Problem
The channel button's look is half-configured in two places. Each channel **row** carried an inline
**Logo / Text** segmented toggle, _and_ the editor popup carried a second Logo/Text "Style" control —
redundant and inconsistent. Worse, **"Logo" never meant an actual logo**: it just rendered the first
few letters of the channel name in a colored box, so there was no way to use a real network/brand logo
image. The **brand color** was limited to five fixed presets. And nothing on a row signalled that
clicking it opens an editor, so the popup (where all the real configuration lives) was easy to miss.

## Goal
Make the channel button fully configured **in one place — the editor popup** — with a real **logo
image** path (pick a previously uploaded asset or upload a new PNG/SVG) for Logo mode, a **text label
input** for Text mode, and a **brand fill** that is either a preset, a custom solid color, or a custom
gradient. Make every editable config row visibly **click-to-edit**.

## Current state (as-is)
- R32 shipped the shared workbench (`design/app/ravilo-builders.js` + `ravilo-builders.css`) used by
  Content rows, Channels, and the Library. The channel popup already had a Logo/Text "Style" segment, a
  five-swatch brand-color picker, and a live channel-button preview.
- Each channel **row** in `design/app/ravilo-config.html` also had a redundant inline Logo/Text
  seg-pill. "Logo" rendered `name.slice(0,3).toUpperCase()` in a `brandColor` box — never an image.
- `Channel(id, name, logoUrl?, style: LOGO|TEXT, brandColor)` already models `logoUrl?` and
  `brandColor`, but the editor never populated `logoUrl` and `brandColor` was a fixed preset string.
- Rows are click-to-edit (R32 §C3) but with no visible affordance.

## Requirements

### A. Style lives only in the popup
1. **Remove the inline Logo / Text seg-pill** from every channel row. A row shows: grab handle ·
   channel button (chip) · name + filter summary · **edit affordance (§E)** · show/hide toggle ·
   remove. The Logo-vs-Text choice is **not** settable on the row anymore.
2. The popup's channel-button section is relabelled **Display: Logo / Text** (was "Style") and is the
   single place the choice is made. Switching Display swaps the controls in §B/§C live.

### B. Logo display = a real image asset
1. With **Display = Logo**, the popup shows a **logo asset picker**: a small grid of selectable
   **brand-logo assets** (the user's previously uploaded logos for this server) plus an **Upload**
   tile. Picking a tile selects that asset; **Upload** opens a file picker (**PNG / SVG**, transparent
   recommended) and the chosen file becomes the selected asset.
2. The selected logo is stored as the channel's **`logoUrl`** and rendered on the channel button **over
   the brand fill** (a transparent logo sits cleanly on the brand color). If a Logo-mode channel has no
   asset chosen yet, the button **falls back** to the name initials over the brand fill (unchanged
   fallback), so a channel is never blank.
3. The live channel-button **preview** and the saved **row chip** both render the chosen image.

### C. Text display = a label input
1. With **Display = Text**, the popup shows a single **text input** for the button label; its
   placeholder is the channel name, so leaving it empty uses the name. The label is rendered as text
   over the brand fill on the button.
2. The preview and row chip render the text live as it is typed.

### D. Brand fill = presets + custom solid/gradient
1. The five **preset swatches** remain. A trailing **＋ custom** swatch opens an inline **builder**:
   - a **Solid / Gradient** segment;
   - **Solid:** one color picker;
   - **Gradient:** **From** + **To** color pickers and an **Angle** slider (0–360°, live degree
     readout).
2. The builder updates the preview **live**; the custom swatch shows the chosen fill and is selected
   while a custom value is active (selecting a preset deselects custom and collapses the builder).
3. **`brandColor` becomes a CSS fill string** holding either a solid color (e.g. `#7b6ef0`) or a
   `linear-gradient(<angle>deg, <from>, <to>)`. The TV renders it as the channel-button background; the
   preset values stay valid (they are already gradients).

### E. Discoverable edit affordance
1. Every **editable** config row (Channels, Content rows, Hero items — not the protected system rows)
   gains a visible **pencil "edit" icon** that opens the prefilled builder; it sits just before the
   show/hide toggle and highlights on hover.
2. Clicking the row **body** still opens the editor (R32 §C3); the icon is the explicit signal. The
   Channels section copy gains a short **"Click a channel to edit it."** hint.

### F. Backend / model
1. **Logo assets:** a small jellystructure endpoint set to **upload** a channel-logo image and **list**
   prior uploads for the picker (server-owned, per server; referenced from config by URL). Uploads are
   validated (type **PNG/SVG**, max size) and served back as the `logoUrl` the TV loads. _(Asset
   storage location/format is an implementation detail; the contract is upload → URL, and a list for
   reuse.)_
2. **`brandColor` validation (R26):** accept **either** a solid CSS color **or** a single
   `linear-gradient(deg, c1, c2)`; reject anything else and clamp to a default preset. The angle is
   0–360°; colors are validated hex/rgb.
3. Channel **`style`/`logoUrl`/`brandColor`** are written through the per-user `RaviloConfig` store
   (R04/R26) and pushed live (R33) like every other config edit.
4. **TV rendering (`ravilo-ui` `ChannelCard`):** the channel-button background must render a
   **gradient** `brandColor`, not only a solid. `parseBrandColor` today handles **hex only**
   (`#rrggbb`/`#aarrggbb`) and returns null for anything else, so an authored `linear-gradient(…)` would
   silently fall back to the accent. Extend it to parse a single `linear-gradient(<deg>, <c1>, <c2>)`
   into a Compose `Brush.linearGradient`; keep the solid-hex path. The existing `logoUrl` →
   `RemoteImage` path already works (logo images render today) — only the editor never sets `logoUrl`.

### G. Editor architecture (admin `RaviloConfig.kt`)
1. The current admin editor styles the channel button **inline on the row** — a `data-ch-style`
   Logo/Text `<select>`, an `<input type="color">` (**solid only**; gradients are dropped via
   `brandColor?.takeIf { it.startsWith("#") }`), and **no logo upload at all** (`logoUrl` is never set).
   "Edit filter" only reopens the workbench for **conditions**. This phase replaces that inline cluster
   with the **popup** channel-button section (§A–§D) and the per-row **pencil edit** affordance (§E).

## Invariants
- The channel-button look (Display + logo asset/text + brand fill) is configured **only in the editor
  popup** — no per-row style control.
- **Logo mode means an image** (`logoUrl`); initials are only a no-asset fallback, never the intended
  state.
- `brandColor` is a CSS fill (solid **or** gradient); no parallel color model, nothing the TV can't
  render as a background.
- All channel writes go through the **server-owned per-user store** (R04/R26) and sync via R33; the TV
  renders server-pushed state only.
- The workbench remains the **single** editor for channels/rows (R32) — this phase only enriches the
  channel-button half of it and the row affordance; conditions/facets are unchanged.

## Out of scope
- New facet axes or filter logic (R32) — unchanged.
- Per-item logo/text on Hero items (Hero keeps its clearlogo-overlay toggle, R32 §D2).
- Theming the logo by skin tint (the brand fill is author-chosen; skin tinting of the jellyfish brand
  mark is separate, constitution §Brand).
- A full digital-asset manager — only the channel-logo upload + reuse list needed by this picker.

## Design reference
`design/app/ravilo-config.html` (Channels rows: no inline Logo/Text toggle; pencil edit icon + "Click
a channel to edit it." hint) and the shared builder `design/app/ravilo-builders.js` (+
`ravilo-builders.css`): the channel popup's **Display: Logo / Text**, the **logo asset picker +
Upload**, the **text-label input**, the **brand fill** presets + **＋ custom** solid/gradient builder,
and the live channel-button preview. Rows for Content rows and Hero items show the same edit icon.

## As built — divergences from the original plan

- **Channel editor is a page, not a popup (R53).** By the time this phase was fully implemented,
  the channel editor had been promoted to a dedicated in-app page (`#/ravilo?channel=<id>` via
  `history.pushState` + `popstate`, browser Back returns to the list). The logo/colour picker and
  hero items (R52) all live on that page. The spec's "popup" framing is superseded by R53.

- **Brand fill builder (§D) — simpler than planned.** The elaborate From/To/Angle gradient builder
  (Solid/Gradient segment, two colour pickers, angle slider) was not built. Instead the implementation
  ships: **10 preset gradient swatches** (HBO, Netflix, Disney+, Apple TV+, TV 2, DR, Teal, Amber,
  Forest, Dark) with active-ring selection; a **native `<input type="color">`** for a custom solid
  hex (synced with a text field); and a **raw text field** for any hex or `linear-gradient(…)` value.
  A live preview chip shows the channel name rendered in the current fill and updates as name/colour
  change. The `brandColor` CSS-fill contract (solid or `linear-gradient`) is unchanged.

- **Logo upload + library picker (§B / §F) — implemented as specified.** The editor shows a live
  preview (44 px tall image), a URL text field, an "⬆ Upload" button (file picker → base64 →
  `POST /api/tv/admin/channel-logos`), and a library grid of previously uploaded logos
  (`GET /api/tv/admin/channel-logos`) as 60×36 thumbnail tiles with active ring.

- **Discoverable edit affordance (§E).** The existing "Edit" button on each channel row serves this
  role; no separate pencil icon was added.

- **Workbench filter.** The inline editor for channel content filters (which was always broken) was
  replaced with a read-only summary + "⚙ Edit filter" button that opens the real `openWorkbench()`
  modal. The workbench itself is unchanged (R32).
