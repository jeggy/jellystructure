# Phase 18 — Settings Page Cleanup (FR-C1)


## Problem
1. The sidebar nav links in Settings use `href="#sect-xxx"` anchor links. The app Router intercepts all `hashchange` events and tries to render pages, causing broken navigation.
2. The section structure is flat and missing logical groupings for new settings (scan workers, thread pool from Phase 16).
3. The "Advanced" section is empty placeholder text.

## Current state (as-is)
- Nav links: `<a href="#sect-connections">`, etc. — trigger Router on click
- Sections: Connections, Library mapping, Language, Behaviour, Advanced
- Behaviour contains: overwrite NFO, fetch images, watch enabled, tell Jellyfin toggles
- Advanced: placeholder text only

## Requirements

### Navigation fix
1. Replace the `<a href="#sect-xxx">` nav items with `<button>`/`<span>` elements that call `document.getElementById("sect-xxx")?.scrollIntoView(js("({behavior:'smooth'})"))`. No hash changes → Router not triggered.
2. Use an `IntersectionObserver` to highlight the active nav item as the user scrolls: when a section header enters the viewport, add `active` styling to the corresponding nav button.

### Section reorganisation
3. Rename, reorder, and restructure the settings sections as follows:

   **Connections** (unchanged)
   - Jellyfin URL, machine token, TMDB key
   - "Test connections" button (now also runs path-check per Phase 15)

   **Library mapping** (unchanged except for Phase 15 additions: match prefix line per library, `<details>` help text)

   **Scanning** (new section)
   - Watch library folders toggle (moved from Behaviour)
   - Scan workers numeric input (Phase 16)
   - Scan thread pool size input with restart-required indicator (Phase 16)

   **Metadata** (replaces old "Language" and "Behaviour" sections)
   - Fallback language picker (Phase 11, moved from Language)
   - Overwrite existing NFO fields toggle
   - Fetch artwork automatically toggle
   - Auto-tell Jellyfin to refresh toggle

   **Advanced**
   - "Danger zone" card with a **"Clear all scanned data"** button: `confirm()` dialog → `DELETE /api/media/all` (new endpoint that wipes the media store and scan state — i.e. empties `MediaStore` / `media.json` and resets `ScanTracker` / `scan-state.json`; **not** SQLite — none exists, see [`_investigation-findings.md`](_investigation-findings.md)). Styled in red/destructive styling.

4. Remove the standalone "Language" nav section (its content moved to Metadata above).
5. The per-library `fallbackLanguage` inputs use the language picker component (Phase 11).
6. `readForm()` in `Settings.kt` is updated to include `scanWorkers` and `scanThreads` from the new Scanning section.

### Sticky page bar and left nav
7. The settings pagebar (title "Settings" · "Test connections" button · "Save" button) must be
   **sticky at the top of the viewport** (`position: sticky; top: 0; z-index: 60`) so the Save
   and Test buttons are always reachable without scrolling. In HTML: `id="set-pagebar"` on the
   pagebar div; CSS rule targets `#set-pagebar`. A `border-bottom` separates it from the content
   area while scrolling.
8. The left section-nav card must stick **below the pagebar** (`position: sticky; top: 88px`),
   where `88px` is the measured height of the sticky pagebar. This keeps the section navigation
   visible while scrolling through long sections.

   **Implementation note (gap):** `Settings.kt` currently renders `<div class="pagebar">` without
   `id="set-pagebar"`, so the sticky CSS selector does not apply. Add `id="set-pagebar"` to the
   pagebar div. The left nav inline style already sets `position:sticky` but uses `top:16px` —
   update to `top:88px` to align below the sticky pagebar.
