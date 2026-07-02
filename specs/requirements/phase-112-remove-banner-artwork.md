# Phase 112 — Remove the "Banner" artwork type (FR-AM6)

## Goal
Drop **Banner** from jellystructure's artwork system entirely. The project's single third-party metadata
source is **TMDB**, and TMDB has no banner image category — so the Banner slot can never be filled by
the pipeline, only by manual upload/URL. Rather than carry a permanently-empty, manual-only asset (and
its "missing" messaging), remove it: Poster · Backdrop · Clear logo remain the managed artwork set.

## Background
- Phase 100 already established the facts: TMDB provides no banners; candidates were always empty; the
  slot was made **upload/URL-only** with honest empty-state copy, deferring a fanart.tv/TVDB provider to
  "a future phase". This phase decides the other way: **no second artwork source is coming** (single-
  source constitution), so the slot goes away.
- **Triage is untouched by design:** no banner triage exists today (`TriageCount` fields are
  `untagged/mismatch/multiDefault/missingArtwork/missingFromSource`; "missing artwork" is
  **poster-on-disk only**, Phase 92). Removing Banner must keep it that way — no new "missing banner"
  signals anywhere, and the artwork rail stops showing a Banner "missing" chip.

## Removal inventory (verified, complete)
Backend:
- `ArtworkDownloader.kt:31` — `ArtworkStatus.bannerExists` field; `:67` — the `banner.jpg` existence
  stat in `check()`; `:231` — `assetPath` "banner" → `banner.jpg` branch; `:242` — chosen-path comment.
- `MediaRoutes.kt:483` — upload/save `"banner" -> "banner.jpg"` filename branch; `:487` — the
  "type must be poster, backdrop, clearlogo, or banner" validation message; `:515-538` — the
  candidates endpoint's banner branches (`asset=banner` on-disk check `:528`, the "TMDB has no banner
  type" null-candidates branch `:538`).

Frontend (`MediaDetail.kt`):
- `:2113` — `ArtTarget("banner", "Banner", "5.4 / 1")` in the artwork-target rail.
- `:2120` — `"banner" -> status.bannerExists` read.
- `:2285` — the "No banner providers configured — upload an image or paste a URL." empty-state copy.
- `:2046` — the `asset` union doc string.

Design mockups (mirror the removal so the next sync doesn't resurrect it):
- `design/app/media.html` + `design/app/series.html` — the Artwork tab's Banner target row/copy.

Not affected (verified absent): NFO writer (no banner tag), triage (none), Jellyfin sync (no banner
image push), the artwork download pipeline (never fetched banners), Ravilo (never consumed banners).

## Requirements
1. Remove every inventory site above: the FE rail shows **Poster · Backdrop · Clear logo** only; the
   backend rejects `banner` as an artwork `type`/`asset` with the updated validation message; the
   `ArtworkStatus` DTO drops `bannerExists`.
2. **On-disk `banner.jpg` files are left untouched.** Jellyfin reads sidecar banners independently of
   jellystructure; deleting user data is out of scope. Jellystructure simply stops reading, writing,
   reporting, or displaying them.
3. No triage/attention surface may gain or keep any banner signal (there is none today — acceptance
   guards the invariant).
4. Design mockups drop the Banner target so the design ⇄ repo sync round-trips clean.

## Non-goals
- No fanart.tv/TVDB (or any second artwork source) integration — explicitly rejected, not deferred.
- No cleanup/deletion of existing `banner.jpg` files.
- No change to Poster/Backdrop/Clear-logo behaviour (including Phase 100's clearlogo provenance).

## Acceptance
- The Artwork tab shows exactly three targets; no Banner row, chip, or empty-state copy anywhere.
- `POST …/artwork` and `GET …/artwork/candidates` with `banner` return 400 with the updated message.
- A library containing on-disk `banner.jpg` files scans and renders with zero banner mentions and zero
  new triage/attention items.
- `grep -ri banner` over backend + FE artwork code returns no artwork-banner logic (UI "banner" =
  unrelated notification banners remain).
