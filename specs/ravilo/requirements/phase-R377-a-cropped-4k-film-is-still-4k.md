# Phase R377 — A cropped 4K film is still 4K

> Owner, 2026-10-05, looking at *Newly Added — Movies* on the phone: *"why does it just say hdr and not 4k?"* —
> *Spider-Man: Brand New Day* (2160p iT WEB-DL, DV HDR) showed **HDR** while M3GAN 2.0 and Clown in a Cornfield
> beside it showed **4K HDR**.

## Status

`✓ Built` 2026-10-06 (see Build notes) — written 2026-10-05 (dev-authored) from the owner's ask. **Not dev-reviewed. Not to be built yet** (owner:
*"We'll implement it soon"*). Server only (`src/linuxX64Main`); no wire, string, client or config change. Number
verified free on `origin/main` `5ced0df0` (Ravilo tops at R376).

## Why it happens

The resolution tier is decided by the video track's width and height with **lower bounds**
(`tv/Quality.kt:15`, duplicated at `tv/HomeFeedService.kt:315`):

```kotlin
(w >= 3840 || h >= 2160) -> "4K"
(w >= 1920 || h >= 1080) -> "1080p"
(w >= 1280 || h >= 720)  -> "720p"
else                     -> "SD"
```

A scope film (2.39:1) whose letterbox bars were cropped off and whose sides lost a few pixels to the encoder fails
both tests: *Spider-Man: Brand New Day* is **3832 × 1600** — 8 px short of 3840, and nowhere near 2160 tall. It
lands in `1080p HDR`, and R325's `qualityBadge()` turns that into a plain **HDR** badge. Jellyfin itself labels the
same stream *4K HEVC Dolby Vision*. The same off-by-a-few-pixels miss pushes cropped 1080p releases (1918 × 802) down
to 720p.

Measured on the library 2026-10-05 (best video track per title, current rule vs FR-R377-1):

| Change | Movies | Episodes |
|---|---|---|
| 1080p → 4K | 7 | 0 |
| 720p → 1080p | 2 | 51 |
| SD → 720p | 0 | 1 |

The seven films: The Invite (3836 × 2072), Obsession (3236 × 2152), Over Your Dead Body (3836 × 1604), Power Ballad
(3828 × 1588), Spider-Man: Brand New Day (3832 × 1600), undertone (3836 × 1808), Watcher (3832 × 1912). The episodes
are mostly 1918 × 802 web releases (Lucky, Pluribus); the 720p→1080p films are The Lighthouse (1292 × 1076, 1.19:1)
and One Missed Call (1434 × 802).

## Requirements

**FR-R377-1 — Tiers by upper bound, like Jellyfin.** A video track's tier is the smallest tier it fits in, using
Jellyfin's own buckets (`MediaStream.GetResolutionText`, Jellyfin `master` 2026-10), collapsed to our four:

| Tier | Rule (width `w`, height `h`) |
|---|---|
| 4K | `w > 2560 \|\| h > 1440` |
| 1080p | otherwise `w > 1280 \|\| h > 962` |
| 720p | otherwise `w > 1024 \|\| h > 576` |
| SD | otherwise, when either dimension is known |

So a title's tier agrees with what Jellyfin shows in its own stream title. 1440p (2560 × 1440) stays `1080p`, as
today and as in Jellyfin. 8K and above are `4K` (we have no 8K tier). A track with neither dimension has no tier,
as today. ` HDR` is appended exactly as today (`videoRange == "HDR"`).

**FR-R377-2 — One resolver.** `HomeFeedService.toFocusDetailFacts()` stops carrying its own copy of the ladder and
calls `MediaItem.qualityLabel()` (`Quality.kt`). After this phase, `Quality.kt` is the only place a resolution tier
is decided, so the tile badge (R325), the Focus Detail badge (202), the detail page's quality and Browse's Quality
facet (R187) can never disagree.

**FR-R377-3 — Everything that reads the tier follows.** No other change is needed: the tile badge, the hero and
Focus Detail meta, the detail page and the Quality facet all read the resolver. A film that moves from 1080p to 4K
also moves in the Quality facet.

## Acceptance

1. *Spider-Man: Brand New Day* (3832 × 1600, HDR) shows **4K HDR** on its tile in *Newly Added — Movies*, on the
   phone and the TV, and in the Focus Detail meta.
2. *Over Your Dead Body* (3836 × 1604, SDR) shows **4K**.
3. A real 1080p film (1920 × 1080 or 1920 × 800, SDR) still has no tile badge, and with HDR still shows **HDR**.
4. Browse ▸ Quality ▸ 4K includes the seven films listed above; ▸ 1080p includes *Lucky* and *Pluribus*.
5. `grep -rn '3840' src/linuxX64Main` finds no tier ladder outside `Quality.kt`.

## Tests

A pure `QualityTest` (linuxX64Test) for `qualityLabel()` / `qualityBadge()`: 3840 × 2160, 3832 × 1600, 3236 × 2152,
2560 × 1440 (→ 1080p), 1920 × 1080, 1918 × 802, 1440 × 1080 (4:3 HD → 1080p), 1292 × 1076, 1280 × 720,
1278 × 716 (→ 720p), 1024 × 576 (→ SD), 720 × 576 (→ SD), width-only and height-only tracks, no dimensions (→ null),
HDR suffix on each tier, and a series picking its largest episode track.

## Open questions (for the dev review)

1. **Dolby Vision.** R325 FR-R325-3 lists *Dolby Vision* as a badge, but the resolver only ever emits `HDR` (DV
   profiles with an HDR10 base report `videoRange == "HDR"`). Out of scope here; worth a separate call if the owner
   wants *4K DV*.
2. **Cached feeds.** The home feed and Focus Detail facts are built per refresh. Confirm nothing persists the old
   tier (no migration expected — it is computed from the stored track dimensions every time).

## Build notes (2026-10-06)

- `tv/Quality.kt` gains `resolutionTier(width, height)` with FR-R377-1's four upper-bound rules; `qualityLabel()` reads
  it. `HomeFeedService.toFocusDetailFacts()` now calls `qualityLabel()` (FR-R377-2): no tier ladder remains outside
  `Quality.kt` (acceptance 5).
- `QualityTest` (linuxX64Test) covers every size in *Tests*, plus 7680 × 4320 → 4K, one-dimension tracks, the HDR suffix
  on each tier, the badge rule and a series taking its largest episode.
- Open question 2 answered: nothing persists the tier — it is computed from the stored track dimensions on each build
  of the feed, the detail page and the facets.
- Acceptance 1–4 need a deploy and a look at the phone and TV.
