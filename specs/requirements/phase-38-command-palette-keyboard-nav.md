# Phase 38 — Command Palette + Attention-Queue Keyboard Nav (FR-KB1)

**Status:** Planned

## Problem
Navigation is mouse-only: to reach a page or jump to a specific title you click through the sidebar or
the Library filters. The old triage page had arrow-key navigation; Phase 27 replaced it with the
floating dock but dropped the keyboard flow. Power users want a **⌘K command palette** and **keyboard
stepping** through the attention queue.

## Current state (as-is)
- `Shell.kt` builds the sidebar `NAV` and injects the scan + triage docks. The triage dock has
  Prev/Next/Open buttons (`#td-prev` / `#td-next` / `#td-open`) but no key bindings.
- No global keydown handling; no palette.

## Requirements

### Command palette
1. **⌘K / Ctrl+K** (from anywhere) opens a centered palette overlay with a search input and a result
   list. **Esc** / backdrop closes; **↑/↓** move selection; **Enter** activates.
2. Result set, filtered live by label + kind:
   - **Pages** — every sidebar nav destination.
   - **Actions** — Start library scan, Re-pull artwork (all), and similar high-level actions.
   - **Needs attention** — the current attention queue items (from `GET /api/triage`), each opening
     its `/media/{id}` detail.
   - (Optional later: live Library title search via `GET /api/media?search=`.)
3. A discoverable **"⌕ Search… ⌘K"** pill in the sidebar opens the same palette.

### Attention-queue keyboard nav
4. When no field is focused and the palette is closed, **`n` / `p`** step the triage dock to the
   next / previous attention item and **`o`** opens the current one (`/media/{id}`). Bindings are
   no-ops when the dock is hidden (nothing needs attention).
5. Keys never fire while typing in an `input`/`textarea`/contenteditable.

## Invariants
- **DOM-only / no router library** (constitution §5) — palette is plain `kotlinx.browser` DOM; it
  navigates via the existing hash router.
- **Frontend renders server state only** — palette items (attention, search) come from the API.

## Out of scope
- Fuzzy ranking / scoring; command history; customizable keymaps.
- A full keyboard-driven editing mode on media detail.
