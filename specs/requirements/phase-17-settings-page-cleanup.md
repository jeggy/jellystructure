# Phase 17 — Settings Page Cleanup (FR-C1)

**Status:** Planned

## Problem
1. The sidebar nav links in Settings use `href="#sect-xxx"` anchor links. The app Router intercepts all `hashchange` events and tries to render pages, causing broken navigation.
2. The section structure is flat and missing logical groupings for new settings (scan workers, thread pool from Phase 15).
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
   - "Test connections" button (now also runs path-check per Phase 14)

   **Library mapping** (unchanged except for Phase 14 additions: match prefix line per library, `<details>` help text)

   **Scanning** (new section)
   - Watch library folders toggle (moved from Behaviour)
   - Scan workers numeric input (Phase 15)
   - Scan thread pool size input with restart-required indicator (Phase 15)

   **Metadata** (replaces old "Language" and "Behaviour" sections)
   - Fallback language picker (Phase 11, moved from Language)
   - Overwrite existing NFO fields toggle
   - Fetch artwork automatically toggle
   - Auto-tell Jellyfin to refresh toggle

   **Advanced**
   - "Danger zone" card with a **"Clear all scanned data"** button: `confirm()` dialog → `DELETE /api/media/all` (new endpoint that wipes the SQLite media store and scan state). Styled in red/destructive styling.

4. Remove the standalone "Language" nav section (its content moved to Metadata above).
5. The per-library `fallbackLanguage` inputs use the language picker component (Phase 11).
6. `readForm()` in `Settings.kt` is updated to include `scanWorkers` and `scanThreads` from the new Scanning section.
