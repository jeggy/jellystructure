# Phase 15 — Fix Library Path Matching for Movies (FR-B1)

**Status:** Planned

## Problem
Movies are not matched to any library during scans. Logs show:
`[WARN] No matching library for '/media/movies/...'`.

## Root cause (identified)
`Scanner.kt` uses:
```kotlin
val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
prefix.isNotBlank() && jellyfinPath.startsWith(prefix)
```
When `jellyfinPath` is blank, the fallback is `localPath` (the Jellystructure-side mount path, e.g.
`/mnt/data/movies`). But Jellyfin reports paths from its own container perspective (e.g.
`/media/movies`). The `startsWith` check silently fails when these differ — the common case when
Jellyfin and Jellystructure have different volume mount paths.

## Requirements

### Backend diagnostics
1. When no library matches a Jellyfin item path, the existing `[WARN]` log is augmented with all configured match prefixes: `[WARN] No matching library for '/media/movies/...' — configured prefixes: [/media/tvshows, /mnt/data/movies]`.
2. New endpoint: `GET /api/config/path-check` — returns per-library diagnostics:
   ```json
   [
     {
       "name": "Movies",
       "jellyfinPath": "/media/movies",
       "localPath": "/mnt/data/movies",
       "matchPrefix": "/media/movies",
       "localExists": true
     }
   ]
   ```
   `matchPrefix` is `jellyfinPath` if non-blank, else `localPath`. `localExists` calls `SystemFileSystem.exists(Path(localPath))`.

### Settings UI — "Test connections" enhancement
3. After clicking **"Test connections"**, if the Jellyfin connection succeeds, automatically call `GET /api/config/path-check` and render the results inline below the Jellyfin/TMDB badges.
4. Each library is shown as a compact row: library name, `matchPrefix` value, and one of:
   - Green badge `"Local path found ✓"` — `localExists == true`.
   - Red badge `"Local path not found ✗"` — `localExists == false`. Hint: `"Check that localPath is mounted correctly in the Jellystructure container"`.
   - Amber badge `"jellyfinPath not set — using localPath as match prefix"` — when `jellyfinPath` is blank.
5. This path-check result section is also shown after clicking **"Save"** in Settings (call the endpoint after a successful config save).
6. In the library mapping card, each entry shows a read-only inline line: **"Matching prefix:"** `{matchPrefix}`. This updates live as the user edits the Jellyfin path field.

### Settings UI — clarity improvement
7. In the library mapping card, add a `<details>` help element (collapsed by default) with the text: "Jellyfin path is the path as Jellyfin sees the library root inside its container. Local path is the same location from Jellystructure's perspective. If both containers share an identical volume mount, these are the same value. If they differ, set both correctly — Jellyfin path is used to match scanned items, local path is used to access files."
