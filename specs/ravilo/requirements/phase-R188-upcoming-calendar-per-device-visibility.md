# Phase R188 — Ravilo: the Upcoming calendar leaks content past library/kids restrictions (FR-RV-UP1)

> Found while hardening for public-internet, multiple-real-users exposure (same session as Phase 156's
> Seerr per-user request attribution). R160 built the Upcoming calendar (Sonarr/Radarr feed of what's
> coming to the library) as deliberately unpersonalized — `TvRoutes.kt:488-489`'s own comment says "the
> calendar is the same for every viewer (no per-user scoping)". That was fine when every device was the
> admin; it isn't once restricted (library-limited or kids) profiles are real users on the internet.

**Status:** Implemented.

## Problem
`GET /tv/upcoming` and `GET /tv/upcoming/item/{id}` (`TvRoutes.kt:490-503`) call `UpcomingService`
without ever passing the requesting device — every profile, regardless of `allowedLibraries`/`isKids`,
sees the exact same feed. Contrast with `DetailService`/`BrowseService`/`HomeFeedService`, which all gate
on `MediaItem.visibleTo(device)` (`MediaStore.kt:57-58`, library allow-list + Jellyfin
AllowedTags/BlockedTags).

Two distinct kinds of item flow through `UpcomingService.build()` (`UpcomingService.kt:93-211`), and they
need different treatment:
1. **Held items** (`matched != null` — the Sonarr/Radarr calendar entry cross-references an
   already-scanned `MediaItem` via tvdb/tmdb id) — these have a real `MediaItem` with a `libraryId` and
   `tags`, so `visibleTo(device)` applies directly, same as everywhere else.
2. **Not-yet-held items** (`matched == null` — pure *arr calendar data, nothing scanned into
   `MediaStore` yet) — there is no `MediaItem` to check. *arr's calendar carries no Jellyfin
   library/tag information, so there's no reliable way to know which library this will land in, or
   whether its content would pass a kids profile's tag policy, before it's actually scanned.

## Requirements

### FR-RV-UP1-1 — Held items: apply the standard per-device visibility check
`UpcomingService.build()` already resolves `matched: MediaItem?` per item; the cache now also keeps that
association (`itemIdStr -> MediaItem?`) alongside the existing `DetailKey` map, so a per-request filter
doesn't need to rebuild or re-fetch anything — filtering the already-cached feed is O(items), not a
*arr round-trip.

### FR-RV-UP1-2 — Not-yet-held items: hide from any restricted device, not just filter
No per-item library/tag data exists for these, so there's no correct per-item check to run — only a
policy decision. A restricted device (`device.allowedLibraries != null` — not full access — **or**
`device.isKids`) sees **only held items** it's allowed to see; the "coming soon, not scanned yet"
half of the feed is admin/unrestricted-profile-only. This is the conservative default: it can never leak
kids-inappropriate or library-restricted content, at the cost of restricted profiles not seeing pure
*arr-calendar speculation for titles they may never even get access to once scanned.

### FR-RV-UP1-3 — `getDetail` matches the list filter
A restricted device hitting `/tv/upcoming/item/{id}` for an item the list filter would have dropped gets
the same `404` it already gets for an unknown id (`TvRoutes.kt:501`) — not a data leak via direct id
guessing.

### FR-RV-UP1-4 — The shared cache stays shared
`UpcomingService`'s single process-wide `CacheEntry`/TTL (R160's "opening the tab never fans out a live
*arr round-trip" design) is unchanged — the per-device filter runs on the cached, unfiltered feed at
request time, not per-device cache entries.

## Out of scope
- Resolving *arr root-folder → Jellyfin library for not-yet-held items to allow a finer per-item check
  (FR-RV-UP1-2's coarser policy is the deliberate, safe default — a future phase could revisit this if
  restricted profiles missing the "coming soon" section in practice becomes a real complaint).
- Kids-mode content-rating heuristics for not-yet-held items (same reasoning — no reliable signal exists
  pre-scan).

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/UpcomingService.kt` — `build()`, `getUpcoming`,
  `getDetail`, `CacheEntry`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt:488-503` — call sites.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:57-58` — `MediaItem.visibleTo(device)`.
