# Phase 38 — Command Palette ⌘K + Keyboard Nav (FR-KB1)

## Features

### Sidebar search pill
A `<button class="cmdk-pill">` injected directly below the sidebar logo acts as a persistent
discoverability affordance. Label: `⌕ Search…` with a `⌘K` kbd hint flush-right. Clicking it
opens the command palette. CSS class `.cmdk-pill` is in `app.css` (full-width, border, fill-2
background, hover highlights with `--hi` border). This button is the primary entry point for
users who haven't memorised the keyboard shortcut.

In `shellHtml()` the button is rendered as static HTML (`id="cmd-search-pill"`) directly after
the `.logo` div. `renderShell()` wires its click handler after `injectCommandPalette()` is set up.

### Command palette (Ctrl+K / ⌘K)
Overlay that opens on Ctrl+K (or Cmd+K), or by clicking the sidebar search pill. Contains a
fuzzy-filter input and a list of commands:
- Quick navigation: Library, Settings, Activity, Metadata, Triage queue, Dashboard
- "Start full scan"
- "Triage: next item" / "Triage: previous item" (also showing shortcut keys)

Pressing Enter executes the first match. Escape closes. Clicking outside closes.

### Triage attention-queue keyboard shortcuts
When focus is NOT in a text input or textarea:
- **n** — next triage item (advances dock index + navigates)
- **p** — previous triage item
- **o** — open current triage item (navigate without advancing)

## Implementation
All in `Shell.kt`:
- `PaletteCmd` data class + `buildPaletteCommands()` 
- `injectCommandPalette(body)` — creates `#cmd-palette-overlay` element
- `showPalette()` / `hidePalette()` / `renderPaletteList(query)`
- `wireGlobalKeyBindings()` — single `document.keydown` handler for Ctrl+K and n/p/o
- `navigateToTriageItem()` moved up to be shared with palette and dock handlers (duplicate removed)
- `injectCommandPalette()` + `wireGlobalKeyBindings()` called from `initShell()`
