# Phase 66 — Tags metadata page: popup backdrop, description pre-fill, color picker, library link (FR-TM1)

**Status:** Planned

## Goal

The tag edit popup on the Metadata → Tags tab has three bugs and a missing feature:

1. **Transparent backdrop** — page content shows through the modal inner card.
2. **Description always empty** — the description field shows blank even for tags that have one.
3. **Color swatches invisible** — `.swatch`, `.swatches` etc. are defined only in the `metadata.html` design mockup `<style>` block, never promoted to `wf.css`. The Kotlin frontend sees unstyled zero-size elements.
4. **Missing: library link** — no way to jump from the tag popup to the Library filtered by that tag.

## Bug details

### 1. Transparent backdrop
`showTagModal()` uses `background:var(--surface)` for the dialog card (`Metadata.kt` line 247). `--surface` is not defined in `wf.css` — the correct token is `--card-bg` (or `--bg-2`). The dialog is effectively invisible; page content shows through.

### 2. Description not pre-filled
`wireTagsTab()` calls `showTagModal(content, scope, name)` passing only the tag name. `showTagModal` does not look up the tag's existing description or color from the already-loaded `data.jsTags` list — the description `<input>` is always rendered empty, and the color hidden input defaults to `#6b7280`.

**Fix**: Before calling `showTagModal`, look up the matching `JsTagWithCount` from the rendered cards (which carry `data-name`, and the tag list is in scope). Pass the found tag's `description` and `color` into `showTagModal` so it can pre-populate the fields.

### 3. Color swatches invisible
`wf.css` is missing these rules (present only in `metadata.html` inline `<style>`):
```css
.swatches { display:flex; gap:8px; flex-wrap:wrap; }
.swatch { width:28px; height:28px; border-radius:8px; cursor:pointer; border:2px solid transparent; transition:border-color .12s; }
.swatch.on { border-color:var(--ink); box-shadow:0 0 0 1px var(--ink); }
```
`tag-card` and `tag-dot-lg` are already used outside the popup (in the tag list) so they must also be in `wf.css`:
```css
.tag-card { display:flex; align-items:center; gap:12px; padding:13px 15px; border-radius:var(--radius-s); cursor:pointer; }
.tag-dot-lg { width:14px; height:14px; border-radius:50%; flex:none; }
```
Move/promote these from `metadata.html` inline styles to `wf.css`.

### 4. Library link
Add a **"View in library →"** button (or link) inside the popup footer that navigates to `/library` with this tag pre-selected via the workbench filter. The URL pattern should match what Library's filter workbench produces for a JS tag selection, e.g. `#/library?tag=<name>` or the equivalent workbench parameter. Close the modal before navigating.

## Files

| File | Change |
|------|--------|
| `design/app/wf.css` | Add `.swatches`, `.swatch`, `.swatch.on`, `.tag-card`, `.tag-dot-lg` (move from `metadata.html` inline style) |
| `design/app/metadata.html` | Remove the now-redundant inline `<style>` block for the above classes |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/Metadata.kt` | Fix `background:var(--surface)` → `var(--card-bg)`; pass existing tag color+description into `showTagModal`; add library navigation button |

## Non-goals

- No change to the color palette (8 preset swatches remain as-is).
- No free-text hex color input.
- No change to the save/delete tag API calls.
