# R49 — Ravilo TV: Top 10 tab, dedicated detail, request + live status indicators (FR-RD2)

**Status:** Planned
**Depends on:** R48 (`/api/tv/discover` + events), R09 (design system/focus), R33 (live push)

## Goal

Build the **Top 10 / Discover** experience in `:ravilo-ui`: a gated top-nav tab of ranked chart rows
with rich acquisition indicators, a **dedicated detail screen** (separate from the R13 library detail),
a **Request** action wired to R48 — all D-pad-native and live-updating. (Trailers are out of scope for
now.)

## Tab (gated)
- A `Top 10` item in the app bar, shown only when `GET /api/tv/discover` returns
  `discoverAvailable = true` (R48 gating: Radarr enabled + per-user `discover.enabled` + non-empty
  lists). Hidden otherwise; re-evaluated on user switch (R18) and on `config_changed` (R33).

## Discover screen
- Header: "Top 10" + "Trending now · &lt;region&gt;" + a **source selector** (active provider; future
  providers shown disabled/"soon" so multi-vendor is visible).
- One ranked **row per user-configured list**, in the user's order. Each row is a focusable `LazyRow`
  of **ranked tiles**: a large rank numeral beside the poster (Netflix style), title, and a sub-line:
  - country lists → `weeksOnChart` + trend badge (rank-only, **no views**);
  - global/all-time → views + trend badge.
- **Status indicator on the tile** (the core ask — more than a %):

  | Status | Tile indicator |
  |--------|----------------|
  | `available` | ✓ corner badge ("In Library") |
  | `requested` | pending dot/pill "Requested" (no %) |
  | `queued` | "In queue" (or "#N") pill (no %) |
  | `downloading` | spinner + "Fetching · NN%"; if `stalled` → "· stalled", if `metadata` → "· starting" |
  | `importing` | "Importing…" pill |
  | `not_requested` | no indicator (selecting it opens detail to request) |
  | `failed` | "Failed" pill (retry from detail) |

  Indicators update **live** from the R33 `acquisition_changed` event — a tile can go pending →
  queued → 12% → 47% → importing → ✓ without a reload.

- **Series tiles show the episode aggregate** (Phase 56 roll-up): a downloading series reads
  “Fetching · 3/10” (episodes done / total), not a single torrent %; `firstAvailable` lets the tile/detail
  offer “Watch Now · E1” while later episodes are still fetching.

## Dedicated detail screen (NOT the R13 library detail)
A separate composable/route (`DiscoverDetailScreen`) — the library detail's playback/seasons model
doesn't apply here. Layout mirrors the library detail's *look* but its content/actions differ:
- Full-bleed backdrop + scrim; kicker = source ("Netflix via Tudum") + rank chip ("#3 in &lt;region&gt;").
- Title, meta (rating/year/genre/kind), a status line reflecting the live `acquisition` status.
- Overview/synopsis.
- **Primary action is status-driven:**
  - `available` → **Watch Now** (resolve to library item, hand to R08 playback);
  - `downloading`/`queued`/`requested`/`importing` → a **disabled/progress** button showing the live
    stage ("Fetching · 47%", "In queue", "Requested", "Importing…");
  - `not_requested`/`failed` → **Request** (calls `POST /api/tv/discover/request`; optimistic UI is
    **not** used — the button reflects the server's pushed status), failed offers retry.
  - Secondary: **My List**.
- A **"Why it's trending"** panel: rank, weeks-on-chart (country) or views (global/all-time), trend.
  Country detail notes "ranking only — no view counts".

## Implementation notes
- Reuse R09 focus model, R42/R43/R47 draw-only focus scale for the ranked tiles and detail buttons (no
  viewport jump), R40 instant-back store retention.
- The Discover store subscribes to `acquisition_changed` (R33 socket) and patches the matching
  entry/detail in place — server-pushed state only (constitution).
- Strings via the R19 i18n layer (`nav_top10`, `request_fetch`, `fetching`, `in_library`,
  `not_in_library`, `weeks_on`, `why_trending`, …) for en/da/fo.

## Mockup
`design/ravilo/Ravilo TV.html` + `ravilo-app.js` (`renderDiscover`, `rankTile`, `renderDiscoverDetail`)
+ `ravilo.css` (`.rtile`/`.rnum`/`.rstat`/`.ddt-*`) is the visual target.
Note: the mockup currently models a reduced status set (`available`/`fetching`/`none`); this phase
renders the full R48/Phase-56 enum (`requested`/`queued`/`downloading`+flags/`importing`).
