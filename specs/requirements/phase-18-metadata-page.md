# Phase 18 — Studios, Networks, Genres & Tags (FR-M1)

**Status:** Planned

## Problem
There is no way to browse media by studio, network, genre, or tag. The tags section on Media Detail
is partially wired (chips render) but cannot be reliably added or removed. There is no structured tag
system.

## Requirements

### New route and nav entry
1. New top-level route `/metadata` → new file `wasmJsMain/.../ui/Metadata.kt`. Add a sidebar nav entry **"Metadata"** with a tag/label icon, positioned between "Language" and "Settings" in the NAV list in `Shell.kt`.
2. The page has a top tab bar with four tabs: **Studios** · **Networks** · **Genres** · **Tags**. Active tab driven by a `?tab=` query parameter (`/metadata?tab=networks`). Default tab: Studios.

### Backend aggregation
3. New endpoint group under `/api/metadata`:
   - `GET /api/metadata/studios?sort=name|count` → `[{ name: String, count: Int }]` — aggregated from `MediaItem.studio` across all items, sorted as requested. Items with `studio == null` excluded.
   - `GET /api/metadata/networks?sort=name|count` → same shape from `MediaItem.network`. TV shows only.
   - `GET /api/metadata/genres?sort=name|count` → `[{ name, count }]` — from `MediaItem.genres` (each genre counted once per item containing it).
   - `GET /api/metadata/tags?sort=name|count` → `{ jsTags: [{ name, color, description, count }], otherTags: [{ name, count }] }` — `jsTags` from the `js_tags` table with structured metadata; `otherTags` are all tags from `MediaItem.tags` not in `js_tags`.
   - All aggregation computed by iterating the media store at query time (no separate aggregation table).
4. New library filter params: `GET /api/media?studio=Warner`, `?network=HBO`, `?genre=Action`. `MediaStore.list()` extended to support these.

### Studios tab
5. Grid of cards (2–4 per row depending on viewport), each showing: studio name (prominent), item count badge. Sort control: "A–Z" / "Most items". Clicking a card navigates to `/library?studio={name}` (encoded).

### Networks tab
6. Identical layout to Studios, filtered to networks. Clicking a card navigates to `/library?network={name}`.

### Genres tab
7. Compact chip-style layout (not full cards). Each chip shows: genre name + count badge. Sort: A–Z / Most items. Clicking a chip navigates to `/library?genre={name}`.

### Tags tab
8. Explanatory paragraph between tab bar and content (always visible):
   > "Jellystructure tags are structured labels you define here with a color and description. They survive metadata re-syncs — when pulling fresh data from TMDB, Jellystructure tags on an item are always preserved. All other tags (below) come from TMDB or were added manually and may be overwritten on resync."
9. **Section 1 — Jellystructure Tags**
   - Header: "Jellystructure Tags" with a "+ New tag" button.
   - Each tag displayed as a card: colored dot (tag color), name, description (truncated to 1 line), usage count.
   - "+ New tag" opens a modal: name input, color picker (8 preset palette swatches), description textarea. Save → `POST /api/tags`.
   - Clicking a tag card opens an edit modal with same fields + "Delete tag" (ghost red button, `confirm()` → `DELETE /api/tags/{name}`). Save edits → `PATCH /api/tags/{name}`.
10. **Section 2 — All other tags**
    - Header: "And the rest"
    - Compact chip list. Each chip: tag name + count. Read-only. No editing.

### Tags backend
11. New SQLite table `js_tags`:
    ```sql
    CREATE TABLE js_tags (
      name TEXT PRIMARY KEY,
      color TEXT NOT NULL DEFAULT '#6b7280',
      description TEXT NOT NULL DEFAULT ''
    );
    ```
12. New routes:
    - `GET /api/tags` → list all Jellystructure tags (without counts; counts come from `/api/metadata/tags`)
    - `POST /api/tags` → `{ name, color, description }` — create; 409 if name already exists
    - `PATCH /api/tags/{name}` → `{ color?, description? }` — update
    - `DELETE /api/tags/{name}` → delete the structured definition (does NOT remove the tag string from any `MediaItem.tags` lists)

### Media Detail — tags fix
13. The tags section in `MediaDetail.kt` is currently only partially functional. Fix:
    - On Media Detail page load, fetch `GET /api/tags` once (alongside the existing `ConfigApi.get()` call). Cache in a local variable.
    - When the tag input is focused, show a dropdown of matching Jellystructure tag names (substring filter). Non-matching free text is still accepted.
    - Jellystructure tags in the chip list render with a small colored dot: `<span class="tag-dot" style="background:{color}"></span>` prepended inside the chip.
    - Non-Jellystructure tags render as plain chips.
    - Tag add/remove functionality already works — no logic changes, only the above UI enhancements.

### Sync invariant — tags
14. When `POST /api/media/{id}/sync` (Phase 13) or `POST /api/media/{id}/repull` is called:
    - Load the current Jellystructure tag name set from `GET /api/tags`.
    - After fetching updated metadata, merge tags: `newTags = jellystructureTagsOnItem + tmdbSourcedTags` where `jellystructureTagsOnItem = item.tags.filter { it in jsTagNames }`.
    - Result: Jellystructure-defined tags survive; non-JS tags are replaced by whatever TMDB returns (or cleared if TMDB returns none).
    - Note: TMDB v3 movie/TV details do not currently return a tags field — `MediaItem.tags` is populated from manual edits only. This merge logic is future-proof and applies correctly when tags are absent.
