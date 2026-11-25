# An admin save refreshes its title, not the library
Task: DEB-5540

> Owner, 2026-11-25 (DEB-5531): picked *"scan cancel/restart churn"* from the log review.

## Scope

Backend only: the admin's save and repull routes, and the `JellyfinClient` call they make. No DTO, schema or client
change. The admin's buttons and their order are unchanged. This extends
`phase-145-event-driven-full-pipeline.md` FR-145-7 (*"A `RunTarget.SingleItem` run posts **no** `/Library/Refresh`"*)
to the routes that predate it. A `RunTarget.Library` run keeps its one closing `/Library/Refresh`.

## What happens today (prod, DEB-5531)

On this Jellyfin a `POST /Library/Refresh` cancels a running library scan and starts a new one.

- **Series refresh.** `POST /api/media/{id}/jellyfin-refresh` (`TrackRoutes.kt`) sends a full item refresh and then,
  for every series, a `/Library/Refresh`. The code comment says *"Per-item FullRefresh alone does not consistently pick
  up tvshow.nfo changes"*.
- **`pushToJellyfin`** (`MediaRoutes.kt`), behind repull and season sync, does the same.
- **Save → Jellyfin on a series.** This runs `/nfo`, which rewrites `tvshow.nfo` and every episode NFO and refreshes,
  then `/artwork`, which refreshes, then `/jellyfin-refresh`, which refreshes and scans. That is three refreshes and one
  library scan per save.
- **Measured:**
  - On 2026-11-25 at 01:06–01:13Z, six saves each cancelled the running scan within 1 s.
  - On 2026-11-24 Jellyfin ran 43 scans against the 24 that were scheduled, 10 of them cancelled.
  - One language edit at 15:38Z caused three full rewrites of a series' 49 episode NFOs and three library scans.
  - The scans also slow Continue Watching: 32 % of its timeouts fell inside one, though scans run only 5.6 % of the time.

## Requirements

- **FR-1 — a title's refresh is the title's.** For a title that has a `jellyfinId`, `/api/media/{id}/jellyfin-refresh`,
  `pushToJellyfin` and every other admin route that refreshes that title send **exactly one**
  `POST /Items/{id}/Refresh`. For a series this is the series item with `Recursive=true` and the full metadata and
  image refresh modes, which is what FR-145-7 sends. These routes send **no** `/Library/Refresh`.
- **FR-2 — one save, one refresh.** The admin's Save → Jellyfin chain on a title ends in one Jellyfin refresh for
  that title, not one per step. The steps that write to disk (`/nfo`, `/artwork`) stop refreshing on their own when
  they run as part of the chain. Called on its own, each still refreshes once. The chain says so with
  `?refresh=false` on `POST /api/media/{id}/nfo` and `POST /api/media/{id}/artwork` (absent means refresh, so an
  admin page loaded before the deploy still works).
- **FR-3 — a title Jellyfin does not know yet.** A title with no `jellyfinId` still needs a library refresh to be
  found. That refresh goes through one coalesced trigger in `JellyfinClient`. The trigger fires 60 s after the last
  request for it. It does not fire while a Jellyfin library scan is running: after the scan ends it fires once, and only
  if a request is still owed. Every caller that today posts `/Library/Refresh` outside a `RunTarget.Library` run goes
  through it (`MediaRoutes`, `TrackRoutes`, `Server.kt`).
- **FR-4 — the reason in the comment is checked, not assumed.** The build proves on the dev Jellyfin that a recursive
  item refresh picks up a changed `tvshow.nfo`. It changes a series' plot in its NFO, sends FR-1's refresh, and reads
  the plot back through `/Items/{id}`. If it does not pick it up, the build stops and reports that on the row instead of
  keeping the library scan.

## Acceptance

1. **Unit test** (`linuxX64Test`, fake Jellyfin):
   - Save → Jellyfin on a series records one item refresh with `Recursive=true` and zero `Library/Refresh` calls;
   - on a film, one item refresh;
   - on a title with no `jellyfinId`, one coalesced `Library/Refresh` 60 s after three requests 10 s apart, and none
     while a fake scan runs.
2. **FR-4's check on dev** is written on the row: the plot before, the plot after, and the call that was sent.
3. **Prod, read-only, 24 h after the deploy.** Jellyfin's log shows about one library scan per hour (the pipeline's
   own), with no `Attempting to cancel Scheduled Task "Scan Media Library"` caused by an admin save.
