# Phase 73 — Write-through metadata sync to Jellyfin (FR-WM1)

## Problem

When a user edits metadata fields on the detail page (title, overview, year, original title,
tags, genres, director, studio, network) and clicks **Save changes**, the changes are written
to the Jellystructure database — but the NFO file on disk is **never updated** and Jellyfin is
**never refreshed**. As a result, Jellyfin continues to show its own scraped data indefinitely,
regardless of what Jellystructure has on record:

- Tags not shown in Jellyfin
- Description (overview/plot) missing or stale
- Title shows Jellyfin's scraped name instead of the curated one
- Genres and studio missing

The same applies to per-episode metadata edits: `PATCH /episodes/{epFilename}/metadata` writes
to the DB but does not update the episode's `.nfo` file.

## Root cause

`PATCH /api/media/{id}/metadata` (MediaRoutes.kt) calls `store.updateOne(updated)` and
returns `updated` to the client, but **never calls `pushToJellyfin`**. Similarly,
`PATCH /api/media/{id}/episodes/{epFilename}/metadata` calls `store.updateOne(updatedItem)`
with no subsequent NFO write or Jellyfin refresh.

All other write paths that change persisted metadata (repull-jellyfin, sync, repull-TMDB) do
call `pushToJellyfin`, so they correctly update the NFO and trigger a Jellyfin refresh.

## Requirement (FR-WM1)

1. **Item metadata save** — immediately after `store.updateOne(updated)` in
   `PATCH /api/media/{id}/metadata`, launch `pushToJellyfin` on the app scope so the NFO is
   written and Jellyfin is refreshed in the background (non-blocking; the HTTP response is
   returned to the client first, as with track-edit write-through).

2. **Episode metadata save** — immediately after `store.updateOne(updatedItem)` in
   `PATCH /api/media/{id}/episodes/{epFilename}/metadata`, launch `pushToJellyfin` on the app
   scope so the episode's `.nfo` is written and Jellyfin is refreshed.

3. **NFO content** — `NfoWriter.buildMovieXml` and `buildTvShowXml` already emit all required
   fields (`<title>`, `<plot>`, `<year>`, `<genre>`, `<tag>`, `<director>`, `<studio>`). No
   NfoWriter change is needed — the bug is purely in the route handler.

4. **Jellyfin title display** — Jellyfin appends the production year to titles in its own UI
   (e.g. "Desk Crush (2026)"). This is Jellyfin's display format for the year field; the
   NFO `<title>` contains only the plain title. No change needed — once the NFO is read, the
   title field in Jellyfin will match Jellystructure's stored title.

## Fields synced to Jellyfin NFO

| Jellystructure field | NFO element | Jellyfin reads |
|---|---|---|
| `title` | `<title>` | ✓ |
| `originalTitle` | `<originaltitle>` | ✓ |
| `year` | `<year>` | ✓ |
| `overview` | `<plot>` | ✓ |
| `tmdbId` | `<tmdbid>` + `<uniqueid type="tmdb">` | ✓ |
| `genres` | `<genre>` (one per element) | ✓ |
| `tags` | `<tag>` (one per element) | ✓ |
| `director` | `<director>` | ✓ |
| `studio` | `<studio>` | ✓ |
| `network` | `<tvstudio>` (TV shows) | ✓ |

## Implementation

**`src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt`**

In `PATCH /{id}/metadata` (item-level), after `store.updateOne(updated)`:
```kotlin
appScope.launch { pushToJellyfin(updated, artwork, configStore, jellyfinClient, appScope, arrRescan) }
```

In `PATCH /api/media/{id}/episodes/{epFilename}/metadata`, after `store.updateOne(updatedItem)`:
```kotlin
appScope.launch { pushToJellyfin(updatedItem, artwork, configStore, jellyfinClient, appScope, arrRescan) }
```

No other files need to change.

## Acceptance criteria

- Saving any metadata field on a Movie or Series detail page immediately triggers an NFO write
  on disk and a Jellyfin per-item refresh (full = true).
- After the background sync completes, the Jellyfin item shows the correct title, overview,
  year, genres, tags, studio/network — matching what Jellystructure has stored.
- The HTTP response to the client is returned before the Jellyfin sync completes (non-blocking).
- Editing an episode title/overview on the Series detail also triggers an episode `.nfo` write
  and Jellyfin refresh.
