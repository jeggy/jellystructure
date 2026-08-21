# Phase 173 — Artwork tab: show the actual current asset, not just TMDB candidates

> Reported live, same session as Phase 171/172: a screenshot of `Ranvá_Christmas_Show_Download (2020)`'s
> Artwork tab — Poster's rail dot correctly read "2/3 · on disk" (green), but the main panel showed
> "TMDB has no poster candidates for this title" and nothing else. "It still doesn't look like there
> are artwork available within jellystructure."

**Status:** Implemented 2026-08-21.

## Root cause

Confirmed the file is real and correctly placed: `/mnt/media/jellyfin/music/Ranvá/
Ranvá_Christmas_Show_Download-poster.jpg` exists on disk under the exact `<basename>-poster.jpg`
convention Phase 171's `assetFilePath()` fix reads — the rail's "on disk" status
(`MediaApi.getArtworkStatus`) is correctly live and correct. The bug is upstream of that signal: the
Artwork tab's main panel (`renderArtGallery` in `MediaDetail.kt`) **only ever renders TMDB candidate
images** (`GET .../artwork/candidates`). Episode stills already had a "current on-disk preview" block
(R131, shown whenever `t.onDisk`, independent of candidates) — but the equivalent was never built for
item-level assets (poster/backdrop/clearlogo). A movie or series usually has *some* TMDB candidates, so
this gap was rarely visible; a `MUSIC_VIDEO` routinely has **zero** (no TMDB match by default, per
Phase 168, and even a matched one's TMDB candidates are a different concern from the manually-placed
local file) — so its Artwork tab main panel is reliably empty even when the asset is completely fine,
with nothing on screen to prove it.

## Fix

`renderArtGallery()`'s existing `currentPreview` block (previously `t.kind == "episode"` only) gains a
second branch: when `t.kind == "asset" && t.onDisk`, render a 64px thumbnail labeled "Currently in use"
above the (possibly-empty) candidate grid. Sourced from Ravilo's own public `GET /api/tv/image/{id}/
{type}` (`RaviloArtworkService`/`RaviloImageUrl`, `AuthPlugin` `OPEN_API_PATHS` — no new backend route
needed) — it already resolves through the exact same `ArtworkDownloader.assetPath()` the rail's own
"on disk" status comes from, so the preview and the status can't disagree with each other the way two
independently-built paths could. Type mapping: `clearlogo` → the route's `logo`. `artStillBust` (R131's
existing cache-buster, previously still-only) is now bumped on every item-asset save success too
(gallery card click, paste-URL, upload, drag-drop, lightbox "Use this artwork" — 5 call sites), so a
just-saved asset's preview doesn't show a stale cached thumbnail.

## Explicitly out of scope

- Season posters have the same underlying gap (candidate-only gallery, no current-preview) but weren't
  part of what was reported — not touched here.
- No backend change — `RaviloArtworkService`/`ArtworkDownloader` are untouched; this is purely wiring
  an existing, already-correct serving route into a UI panel that never used it.

## Verification

- `compileKotlinWasmJs` clean (covers the admin frontend).
- Confirmed on disk directly: `Ranvá_Christmas_Show_Download-poster.jpg` exists at the exact path
  `assetFilePath()` computes; no bare `poster.jpg` exists in that shared artist folder (ruling out the
  pre-171 movie-shaped convention as an alternate explanation for the rail's "on disk" status).
- Live-verified in the browser the same day (backend restarted, user confirmed): the "Currently in use"
  preview rendered correctly. Two follow-up rounds of feedback below.

## 2026-08-21 amendment #1 — move the preview into the candidate grid; generalize beyond music videos

**User feedback, live screenshot:** "Doesn't look so good yet. Would be nice to show it in the list as
well, maybe just a badge showing that it's not from tmdb." Separately: the same on-disk-but-
never-TMDB-touched gap "also [happens] in rare cases for normal movies/series, because the image was
included originally alongside the actual media file" — i.e. this was never a music-video-only bug, it's
any item whose poster arrived on disk without ever going through a TMDB fetch or a manual Artwork-tab
save.

- **Visual integration**: the small side-by-side preview strip is gone. The current on-disk asset is now
  a real tile inside the candidate grid itself (`localTile`, prepended to `cards`) — same `.art-card`
  box, same aspect ratio, badged **"NOT FROM TMDB"** (`.art-ribbon.local`, accent-purple, distinct from
  the existing green "ON DISK" ribbon a *matched* TMDB candidate gets) instead of a green on/off dot with
  no visual context. `shown.isEmpty() && localTile.isEmpty()` is now the actual empty-state condition (a
  title with a local asset but zero TMDB candidates no longer shows "No candidates for this filter" next
  to a tile that contradicts it).
- **A second, separate, wider bug surfaced by the same investigation**: `item.posterPath` — the field
  `Library.kt`'s grid cards (`posterCardHtml`) and `MediaDetail.kt`'s Overview-tab hero poster both use to
  decide whether to render an `<img>` at all — is **only ever set** by a TMDB match or a manual
  Artwork-tab save (`MediaRoutes.kt:607`, the Phase 133 `/tv/image/{id}/poster` sentinel convention). It
  is **never** back-filled when the scanner or a pipeline step merely finds a pre-existing on-disk poster
  with no TMDB source — exactly the case the user just described for ordinary movies/series, and the
  Ranvá case too. So even after this fix made the Artwork tab correctly show the asset, the **Library
  grid card and the item's own Overview-tab poster still rendered a blank placeholder** for the exact
  same title, because both gate on a field that was never the real "does art exist" signal — only a
  record of *how* it got there.
  - Fixed the same way Ravilo's own `toMediaCard()` already does it (`BrowseService.kt` —
    `posterUrl = RaviloImageUrl.poster(id)`, always set, never gated on a stored field): when
    `posterPath == null`, both `posterCardHtml` and the Overview poster now still render an `<img>`
    pointing at the real serving route (`/api/tv/image/{id}/poster`) instead of jumping straight to the
    "no poster" placeholder — a genuine miss 404s and swaps to the placeholder via an error handler
    (Library: one capture-phase listener delegated on `#poster-grid`, since `error` events don't bubble;
    Overview: a single listener, one poster per page). No backend/data-layer change — this is purely
    "stop trusting a provenance field as an existence field" on the two remaining UI call sites that did.
- Verified: `compileKotlinWasmJs` clean. Not yet re-verified live (this amendment landed after the
  live-verified state above; needs another restart).

## 2026-08-21 amendment #2 — soften the badge

**User feedback**, comparing a live side-by-side screenshot (jellystructure's Artwork tab vs. Jellyfin's
own player showing the same image cleanly): asked for design ideas, then scoped the response down to
just one — soften the badge. `"NOT FROM TMDB"` read like a warning/error for what is usually the
correct, expected state (especially for a music video, which never TMDB-searches by default) — a strong
accent-purple ribbon and border gave it unwarranted visual weight.
- Ribbon text: `"NOT FROM TMDB"` → `"Local"`.
- `.art-ribbon.local`: solid `var(--acc)` background → the same muted translucent `#000a` treatment
  `.art-pill` already uses elsewhere in this same grid, `color:var(--muted)` instead of white, no longer
  bold-white-on-accent.
- `.art-card.local`: border color `var(--acc)` (matching the "ON DISK" ribbon's visual weight) →
  `var(--line)`, the same neutral border every other UI element in this codebase uses for "nothing
  special here."
- The other two ideas offered (fix the `cover`→`contain` crop; give the Overview-tab poster a bigger
  hero treatment) were explicitly NOT chosen this round — left for a future pass if wanted.
- `compileKotlinWasmJs` clean. Not yet re-verified live.
