# R50 — Ravilo config editor: per-user Top 10 list selection (FR-RD3)

**Status:** Planned
**Depends on:** R48 (`DiscoverConfig` on `RaviloConfig`), R16/R28 (config screen + editor fidelity),
Phase 57 (`GET /api/discover/lists`)

## Goal

Add a **Top 10** section to the Jellystructure → Ravilo config screen so an admin configures, **per
Jellyfin user**, whether the Discover tab shows and which charts appear (and in what order). This is the
editor for the R48 `DiscoverConfig`; it follows the existing R28/R36 editor patterns (drag-reorder,
show/hide toggles, per-row affordances) exactly.

## Section: Top 10

Placed in the config screen nav after **Content rows**, before **Behaviour**.

- **Master enable toggle** ("show this tab") → writes `discover.enabled` for the edited user. When off,
  the body collapses and the TV hides the tab (R48 gating).
- **"Allow this user to request downloads" toggle** → writes `discover.canRequest` (Phase 56
  permission). Off = the user browses charts and sees statuses but the Request button is disabled
  (admins can always request regardless). Hidden/forced-on for admin users.
- **Gating note + link:** the tab also requires Radarr connected — link to
  `Settings → Download tools` (Phase 54/55). If Radarr is not enabled globally, show an inline warning
  that the tab won't appear regardless of this toggle.
- **Source** selector → `discover.source` (Netflix · via Tudum active; Disney+/Max shown "soon",
  disabled, matching the Phase 57 provider registry).
- **Country** selector → `discover.region` (drives which country charts exist).
- **Lists shown to this user** — a draggable, toggleable list (the `.cfg-row` pattern from R28) of the
  available charts from `GET /api/discover/lists?region=<region>` (Phase 57). Reordering writes the
  order of `discover.lists`; toggling membership adds/removes an id. Each row shows the chart's scope +
  metric as its sub-line, and country rows are annotated "rank only (no view counts)".
- A note restating the data caveat: **country charts are ranking-only**; global & all-time carry real
  viewership.

## Behaviour
- `populateForm()` hydrates the section from the edited user's `RaviloConfig.discover`.
- `readForm()` serializes `enabled`, `source`, `region`, and the **ordered, enabled** `lists`.
- Saving goes through the normal `RaviloConfigService.save` path → R33 pushes `config_changed` → the
  user's TVs re-pull `GET /api/tv/discover` and the tab/lists update live (~1s), same as every other
  config edit.
- The available-lists set is filtered by `region`; switching country re-loads the candidate rows.

## Non-goals / invariants
- **Per-user, server-owned** — selection lives in the R04 store keyed by Jellyfin user; no client-side
  list logic (the TV renders what R48 returns).
- **Reuse the editor chrome** — `.cfg-row`, drag-reorder, toggles, `RaviloConfigService.save`, R33 live
  push. No new persistence or sync path.
- **Lists are provider-driven** — the candidate rows come from Phase 57's `availableLists`, so adding a
  vendor/list later needs no editor change.

## Mockup
`design/app/ravilo-config.html` — the `#sect-top10` section: master `#top10-enable` toggle revealing
`#top10-body`, source + country selects, the `#top10list` draggable `.cfg-row` list (5 charts), the
gating link to `settings.html?tab=downloads`, and the rank-only caveat note.
