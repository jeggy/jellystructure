# Phase 38 — Command Palette ⌘K + Keyboard Nav (FR-KB1)

**Status:** ✓ Done

## Features

### Command palette (Ctrl+K / ⌘K)
Overlay that opens on Ctrl+K (or Cmd+K). Contains a fuzzy-filter input and a list of commands:
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
