# Phase 136 — Jellystructure: Jellyseerr/Overseerr connection; retire third-party chart Discover

> Renumbered **134 → 136** after syncing with the repo (repo owns 132–135).

> Add a **Jellyseerr / Overseerr** connection under **Settings ▸ Download tools**, and **remove the entire
> third-party chart Discover/Top-10 subsystem** (Netflix·Tudum / JustWatch / movieofthenight — RapidAPI) plus
> the **Streaming Availability API key**. Seerr becomes the sole browse/request source for Ravilo's Request tab
> ([R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)); per-user rows are configured in
> [Phase 137](phase-137-ravilo-config-request-builder.md).

**Status:** Planned — **design built** (`design/app/settings.html`), backend/frontend integration unbuilt.

## Problem
Discover/Top-10 was fed by ingesting third-party charts from several vendors — Netflix (Tudum), JustWatch, and
**movieofthenight.com** via a **RapidAPI "Streaming Availability" key**. We are standardising on **Seerr** for
all discover + request. The vendor stack (providers, country charts, weekly refresh, the RapidAPI key gating)
is now dead weight and confusing next to the Seerr flow.

## Current state (as-is) — design built in `design/app/settings.html`
- **Added:** a **Jellyseerr / Overseerr** card in the **Download tools** tab (`#sect-seerr`): enable toggle,
  **Seerr URL**, masked **API key**, connection/status chip + **Test connection**; copy points users to
  **Ravilo config → Request** for per-user visibility. `config.toml` preview gains `[seerr] enabled/url/api_key`.
- **Removed:** the **"Discover / Top 10 sources"** card (`#sect-discover`: `PROVIDERS`/`GROUPS` provider chips,
  `#reg-grid` country charts, refresh cadence) **and** its driving IIFE; the **Streaming Availability API key**
  field (`#sa-key`) in **Connections**; the `sect-discover` entry in the tab map. No RapidAPI / JustWatch /
  movieofthenight anywhere.

## Requirements
### A. Seerr connection (Download tools)
1. A **Jellyseerr / Overseerr** card under **Settings ▸ Download tools**: enable toggle, **URL**, **API key**
   (masked, `##KEEP##`-style on save like the *arr keys), **Test connection** with a live status chip. The
   card is the **connection only** — it does not choose what shows on the TV.
2. `config.toml` `[seerr]` block: `enabled`, `url`, `api_key`. Requests/approvals are owned by Seerr; approved
   titles are fulfilled by the existing Radarr/Sonarr integration.

### B. Retire the chart subsystem
3. **Remove** the Discover/Top-10 chart-sources UI, its provider/region/refresh config, and the
   **Streaming Availability (RapidAPI) key**. Remove the corresponding config keys and ingestion. No
   third-party chart provider (Netflix·Tudum, JustWatch, movieofthenight) remains — **supersedes/retires
   Phases 57 and 107**.

## Invariants
- Seerr card is **connection-only**; per-user Request visibility & rows live in Ravilo config (Phase 137).
- API key masked at rest; never logged.
- No RapidAPI / streaming-availability dependency remains in config, UI, or backend.

## Out of scope
- Per-user **Request rows** builder → **[Phase 137](phase-137-ravilo-config-request-builder.md)**.
- The Ravilo **Request tab** UI → **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)**.
- Migrating existing Discover config — the feature is removed, not migrated.

## Source references
- Design: `design/app/settings.html` (`#sect-seerr` card; removal of `#sect-discover`, `#sa-key`, the provider
  IIFE, and the tab-map entry; `[seerr]` in the config preview).
- Related: **Phase 57 / 107** (chart ingestion, now retired), **Phase 54/56** (Radarr/Sonarr — fulfilment),
  **[Phase 137](phase-137-ravilo-config-request-builder.md)** (per-user rows),
  **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)** (TV Request tab).
