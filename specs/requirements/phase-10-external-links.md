# Phase 10 — External Links on Media Detail (FR-X1)

## Problem
The media detail page has no direct links to the source systems. Users have to manually navigate to
Jellyfin or TMDB to cross-reference.

## Current state (as-is at time of spec)
- `item.jellyfinId` is available but only used for internal API calls
- `item.tmdbId` is displayed in the Identity card as plain text
- The Jellyfin base URL is available via `ConfigApi.get()?.apiKeys.jellyfinUrl`
- The detail page already calls `ConfigApi.get()` once on load (for `fallbackLang`)

## Requirements
1. In the Media Detail page `pagebar`, add two compact ghost icon-link buttons, placed to the left of "Save → disk":
   - **"Jellyfin ↗"** — visible only when `item.jellyfinId != null`. URL: `{jellyfinUrl}/web/index.html#!/details?id={jellyfinId}`. Opens in a new tab.
   - **"TMDB ↗"** — visible only when `item.tmdbId != null`. URL for movies: `https://www.themoviedb.org/movie/{tmdbId}`, for TV: `https://www.themoviedb.org/tv/{tmdbId}`. Opens in a new tab.
2. The Jellyfin URL is read from the already-fetched config (`ConfigApi.get()`). If `jellyfinUrl` is blank the Jellyfin button is omitted entirely.
3. Style: `btn sm ghost` with small text, consistent with the existing "Re-pull from TMDB" button.
4. No new API routes. All data already exists on `MediaItem` and config.
