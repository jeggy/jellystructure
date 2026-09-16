# Phase 220 — Browsing a season must not spawn one ffmpeg per still on the interactive path

> Scrolling *It's Always Rainy in Pittsburgh* → Season 17 on the stue TV, 2026-09-16, produced eight
> consecutive lines in the production log, one per episode card:
>
> ```
> [INFO] ffmpeg: ffmpeg -y -i '…/S17E01…-thumb.jpg' -vf scale=640:-2 -frames:v 1 -q:v 3 '/config/artwork/tv/.rsz_…-e1.jpg'
> [INFO] ffmpeg: ffmpeg -y -i '…/S17E02…-thumb.jpg' …
> …
> ```
>
> Each one is a process spawn on the request path, gated by the same four-permit interactive
> `ProcessGate` reserve that exists to keep interactive work responsive under a scan.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F13). Not dev-reviewed, not
built. Backend only. **Severity is low** — a still is a small JPEG, not a remux — and this phase is
sized accordingly: measure, pre-size, stop 503ing images.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 218; 219 by a sibling
spec the same day.

## What the code does (traced against `main`, 2026-09-16)

- `TvRoutes.kt:1057-1065` `GET /tv/image/{itemId}/still/{epFilename}` → `RaviloArtworkService.serveStill`
  (`:89-100`) → `resizeServe` (`:207-236`): cache hit via `readFresh`, else
  **`OutboundHttp.withPermit { … FfmpegRunner.resizeImage(…) … }`** — an outbound-HTTP-pool permit held
  around a local process — and `resizeImage` (`FfmpegRunner.kt:290-297`) → `runCommand` →
  `ProcessGate.withPermit`, which on a request handler is the **interactive** class:
  `INTERACTIVE_ACQUIRE_TIMEOUT_MS = 1_500` (`ProcessGate.kt:46`) over a reserve of 4 (182). A saturated
  reserve throws `GateTimeoutException` → StatusPages answers **503** for the image.
- `fetch_artwork` (`PipelineStepOps.kt:47-49`) writes the still to disk next to the episode
  (`-thumb.jpg`) but never the served 640 px variant; the first viewer to scroll a season pays for
  every card, serially, four at a time.
- The cost per spawn is small (~tens of ms of CPU, a few hundred KB read). The failure mode is not
  disk saturation — it is (a) a fast scroll through a long season queueing more than four resizes
  against a 1.5 s acquire timeout, producing blank cards, and (b) two unrelated pools (HTTP and
  process) coupled by one call.

## Requirements

**FR-220-1 — The pipeline produces what the TV will ask for.** `fetch_artwork` writes the served
variants — still 640, poster 320, backdrop 1920, logo h300 — into the same size-keyed cache
`resizeServe`'s `readFresh` reads, keyed identically, at fetch time. After one pipeline pass over an
item the request path is a file read.

**FR-220-2 — An image is never worth a 503.** The on-demand resize stays as the fallback for an item
the pipeline has not touched, but a cache miss under a saturated gate serves the **original** file
(bounded by a size cap; a still or poster is already small) rather than failing the request. The
client already tolerates any size.

**FR-220-3 — One pool per resource.** `resizeServe` no longer takes an `OutboundHttp` permit around a
local process; `ProcessGate` is the only gate a local ffmpeg holds.

**FR-220-4 — Backfill once, in the background.** A one-time pass generates the variants for the
existing library under `GateClass.BACKGROUND`, through 213's job queues, resumable, and reported on
Activity like any other job.

**FR-220-5 — Measured, before and after.** A cold browse of a 24-episode season records request-path
spawn count (target: 0 after FR-1/4) and p95 still latency; `/api/health`'s `tv-image` block reports
resizes-on-request per hour so a regression is visible.

## Non-goals

- Image formats, quality settings, or the client's Coil cache.
- The R242 backdrop's own fetches (already the hero's URL; unchanged).

## Verification

1. Unit: `resizeServe` serves the original under an injected saturated gate; the cache key produced
   by `fetch_artwork` equals the one `serveStill` computes for the same episode.
2. Live: scroll a long season twice on the stue TV; the log shows spawns only on the first pass before
   FR-4 has run, none after.

## Open questions

- Whether the size cap in FR-220-2 should be per type (a 4K backdrop original is not "small").
  Recommendation: cap at 2 MB, above which the 503 stands.
