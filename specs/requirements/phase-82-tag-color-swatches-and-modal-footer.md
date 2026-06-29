# Phase 82 — Tag color swatches render + tag-modal footer fits (FR-TG3)

## Problem
On `/#/metadata?tab=tags`:
1. Creating/editing a Jellystructure tag offers **name + description** but the **color picker appears
   absent** — no swatches show.
2. The edit-tag popup is also missing the colors, and its footer has four buttons — **Delete · View in
   library → · Cancel · Save** — that don't fit, so **"Delete" floats outside the popup's left edge**.

## Findings (premise correction: the color picker already exists in code — it just doesn't render)
The colour feature is **fully wired end-to-end already**; nothing is needed on the model or API:
- Backend: `JsTagStore.JsTag(name, color = "#6b7280", description)` (`media/JsTagStore.kt:13-17`);
  `POST /api/tags` + `PATCH /api/tags/{name}` accept `color` (`server/routes/MetadataRoutes.kt:51-54,
  192-206`); `GET /api/tags/with-counts` returns it.
- Frontend API: `MetadataApi.createTag/updateTag` already send `color`
  (`api/MetadataApi.kt:30,37,49,52,75,83`).
- The modal (`Metadata.kt:241 showTagModal`, same modal for create + edit) already renders a **Color**
  field with swatches (`:262-266`, generated `:248-250`, selection `:283-302`, `class="swatch ... on"`).

**The real bug is missing CSS.** `.swatch` / `.swatches` / `.swatch.on` (and `.tag-card`,
`.tag-dot-lg` used at `Metadata.kt:197-198`) are defined **only** as an inline `<style>` in the mockup
`design/app/metadata.html:18-26`, and were **never ported into the shipped `design/app/wf.css`**. The
WASM app ships `wf.css`/`app.css` verbatim, so every `.swatch` is a zero-size, borderless `<span>` →
the picker is invisible. (Confirmed: `grep swatch design/app/wf.css` → nothing.)

**Palette mismatch.** `Metadata.kt:239 PRESET_COLORS` is a Tailwind-ish set
(`#6b7280,#ef4444,#f97316,#eab308,#22c55e,#3b82f6,#8b5cf6,#ec4899`) and defaults to `#6b7280`
(`:244`). The **design** uses the Aurora palette
(`#7b6ef0,#2dd49a,#f5b542,#3fb6f5,#ff6f61,#b15cd0,#6b7280,#e0639a`), default `#7b6ef0`
(`design/app/metadata.html:118-123`). Follow the design.

**Footer overflow.** `Metadata.kt:272-277` is a single non-wrapping flex row
(`display:flex;gap:8px;justify-content:flex-end`) holding Delete + the `View in library →` link
(`:251-254`, `margin-right:auto`) + Cancel + Save, inside a `width:340px` modal (`:256`, ~292px content).
The four controls overflow; with `justify-content:flex-end` and no `flex-wrap`, the overflow spills past
the **left** edge, pushing **Delete** out of the box, and the `margin-right:auto` split collapses.

## Goal
The tag create + edit modal shows the design's swatch palette (selectable, with a selected ring), and the
edit-modal footer fits cleanly inside the popup with no floating buttons.

## Requirements
1. **Port the swatch CSS into `design/app/wf.css`** (so the shipped app styles it):
   `.swatches { display:flex; gap:8px; flex-wrap:wrap; }`,
   `.swatch { width:28px; height:28px; border-radius:8px; cursor:pointer; border:2px solid transparent; }`,
   `.swatch.on { border-color: var(--ink); }` — plus `.tag-card` and `.tag-dot-lg`
   (from `design/app/metadata.html:18-26`) so the tag cards/dots also style correctly.
2. **Align the preset palette to the design.** Set `Metadata.kt:239 PRESET_COLORS` to the Aurora eight
   and default to `#7b6ef0` (`:244`).
3. **Fix the modal footer** (`Metadata.kt:272-277`): make it wrap and split into two groups instead of a
   fixed-end overflow — e.g. `display:flex; flex-wrap:wrap; gap:8px; justify-content:space-between` with a
   left group `[Delete] [View in library →]` and a right group `[Cancel] [Save]`; drop the
   `margin-right:auto` on the lib link (`:251-254`). Optionally widen the modal (~380–420px) or make
   "View in library" an icon button so the left group fits on one line.

## Scope
- `design/app/wf.css` (port swatch + tag-card/tag-dot-lg CSS) — and the `metadata.html` mockup stays the
  source of truth.
- `src/wasmJsMain/.../ui/Metadata.kt` (PRESET_COLORS + default, footer markup).

## Non-goals
- No backend/model/API change — `color` already exists everywhere.
- Not a free colour input — preset swatches per the design (a custom-hex field is a possible later add).

## Acceptance
- Create a tag: the eight Aurora swatches show; clicking one rings it; the saved tag uses that colour.
- Open an existing tag: swatches show pre-selected to the tag's colour; the footer's Delete · View in
  library · Cancel · Save all sit inside the popup (wrapping if needed), with Delete grouped at the left.
