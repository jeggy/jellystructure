# R48 — Discover / Top 10 API (`/api/tv/discover`) + per-user list config + live status (FR-RD1)

**Depends on:** Phase 57 (chart ingestion), Phase 56 (acquisition pipeline), R04 (per-user config
store), R33 (live config/event push)

## Goal

Expose the Top 10 / Discover surface to Ravilo TVs: compose Phase 57's normalized charts with the
**per-user list selection** (stored in the R04 config) and Phase 56's **live acquisition status**, over
the `/api/tv/**` namespace. Gated by Radarr/Sonarr being enabled **and** a per-user opt-in.

This is the TV control-plane glue. Chart data = Phase 57; the request engine = Phase 56; this phase
wires them to a user and a device.

## Per-user config — extend `RaviloConfig` (R04 / R26)

Add a `discover` block to the server-owned per-user `RaviloConfig` (so it syncs across the user's TVs
and is editable in the config screen, R50):

```kotlin
data class DiscoverConfig(
  val enabled: Boolean = false,         // user opt-in (tab visibility)
  val canRequest: Boolean = false,      // may this (non-admin) user spend disk/bandwidth to request? admins always may
  val source: String = "netflix",       // ChartProvider id (Phase 57 registry)
  val region: String = "DK",
  val lists: List<String> = emptyList(), // ordered ChartListSpec ids the user sees
)
// RaviloConfig gains: val discover: DiscoverConfig = DiscoverConfig()
```

**Tab gating (server-decided, not client-guessed):** the Discover tab is offered only when
`discover.enabled` **AND** `discover.lists.isNotEmpty()` **AND** *the \*arr that serves the user's
selected lists is enabled* — i.e. Radarr for movie lists, Sonarr for TV lists (Phase 54/56). A
Sonarr-only household whose user selected only `tv-DK` is **not** gated off; a movie-only list needs
Radarr. The home/config payload carries a `discoverAvailable: Boolean` (computed from this rule) so the
TV shows/hides the tab without duplicating the logic.

## Endpoints (`/api/tv/**`, device-session auth from R03)

- `GET /api/tv/discover` — for the signed-in user: returns `discoverAvailable` + the user's ordered
  lists, each resolved (`ChartEntry[]` from Phase 57) with a per-entry `acquisition` record (Phase 56
  status) merged in. Country lists return rank-only entries (no `views`); global/all-time include
  views.
- `GET /api/tv/discover/item/{listId}/{rank}` (or by `tmdb:<id>`) — the detail payload for one entry:
  backdrop, overview, rank/weeks/trend/views, cast (reuse R07 where the title resolves to
  a library/TMDB id), and the live `acquisition` status. This is a **separate payload** from the
  library detail (R07) — Discover detail has no playback/seasons; it has request only. (Trailers are out
  of scope for now.)
- `POST /api/tv/discover/request` `{listId, rank}` or `{mediaKind, tmdbId}` → calls
  `AcquisitionService.request(..., requestedBy = user)` (Phase 56) and returns the new status record.
  Idempotent (re-request of an in-flight title returns the existing record; re-posting a `failed` one
  retries). **Authorized** per Phase 56's permission rule — admins always, non-admins only if
  `discover.canRequest`; the payload carries `canRequest` so the TV can disable the button up front.
  (No TV cancel route — cancel is admin-only, from the admin app.)
- Status updates stream over the existing **R33 `/api/tv/events`** WS as the **payload-bearing**
  `acquisition_changed` event defined in Phase 56 (the updated record inline — *not* a rev-signal that
  forces a full re-pull, and progress throttled to ≥5%/≥3 s). The TV patches the matching tile/detail
  indicator in place as it moves requested → queued → downloading% → importing → available
  (server-pushed state; the client never derives progress).

## Status mapping for the client

The API passes Phase 56's `AcquisitionStatus` through unchanged; R49 owns the visual mapping. The
gist the TV must honor:
- `available` → Watch Now (resolves to the library item; playback via R08).
- `requested` / `queued` / `importing` → non-% pills ("Requested", "In queue · #N", "Importing…").
- `downloading` → % with `stalled`/`metadata` flags.
- `not_requested` / `failed` → request affordance (failed shows retry).

## Non-goals / invariants
- **Control plane only.** No media bytes here; backdrops/trailers are image/stream refs the client
  fetches per the usual data-plane rules.
- **Server decides availability/gating**; the client never re-derives the eligibility rule.
- **Reuse, don't fork** — chart data (Phase 57), request/status (Phase 56), config store (R04), event
  bus (R33). This phase adds composition + 3 routes, not new subsystems.

## Mockup
`design/ravilo/ravilo-data.js` (`profiles[].discover = {enabled, lists}`, `discover.config`,
`discover.sources`, `discover.lists`) is the shape of the config + payload; the TV reads exactly this.
