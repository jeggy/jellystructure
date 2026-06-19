# Ravilo — Requirements

The **"what"** for Ravilo — one file per development phase. This table is the **single source of
truth** for which Ravilo phases exist and whether each is done or planned. Ravilo phases are prefixed
**`R`** to keep them distinct from jellystructure's numeric phases.

- Read [`../constitution.md`](../constitution.md) (Ravilo rules) and [`../plan.md`](../plan.md)
  (module layout + API) before starting any phase.
- Ravilo lives in the jellystructure repo; backend phases add `/api/tv/**` routes to the existing
  server, UI phases land in `:ravilo-ui` (shared Compose) with thin `:ravilo-android` / `:ravilo-web`
  modules + an Android-only `:ravilo-player` (the forked `jellyfin-androidtv` engine; GPL — see R14).

## Phase index

| Phase | Status | Focus | Spec |
|-------|--------|-------|------|
| R01 | ✓ Done | `:shared` KMP module — DTOs + Ktor `TvApiClient` (once, for backend + both clients) | [R01](archive/phase-R01-shared-module.md) |
| R02 | ✓ Done | Compose Multiplatform scaffolding — `:ravilo-ui` + `:ravilo-android` + `:ravilo-web`, one "hello focus" screen on both targets | [R02](archive/phase-R02-compose-mp-scaffolding.md) |
| R03 | □ Planned | Backend — `/api/tv/**` namespace + TV **device pairing** auth (code flow, non-admin users, device sessions) | [R03](phase-R03-device-pairing-auth.md) |
| R04 | □ Planned | Backend — per-Jellyfin-user **RaviloConfig store** (server-owned, synced across devices) + `GET /api/tv/config` | [R04](phase-R04-per-user-config-store.md) |
| R05 | □ Planned | Backend — **home feed** composition (hero, channels, rows incl. merged Continue+NextUp & Newly-Added) | [R05](phase-R05-home-feed-api.md) |
| R06 | □ Planned | Backend — **browse + multi-language search** + channel-scoped feeds (reuse Phase 29/30) | [R06](phase-R06-browse-search-api.md) |
| R07 | □ Planned | Backend — **detail** API (movie/series, seasons/episodes, watched-state/resume, cast, related) | [R07](phase-R07-detail-watched-state-api.md) |
| R08 | □ Planned | Backend — **playback brokering + progress reporting** (StreamTicket, heartbeats, mark played/next-up) | [R08](phase-R08-playback-brokering-reporting.md) |
| R09 | □ Planned | `:ravilo-ui` — **design system + focus engine** (Aurora/Midnight/Noir, components, shared D-pad/pointer model) + app bar | [R09](phase-R09-design-system-focus-engine.md) |
| R10 | □ Planned | `:ravilo-ui` — **Home** (hero carousel, channel rail, content rows) wired to `GET /api/tv/home` | [R10](phase-R10-home-screen.md) |
| R11 | □ Planned | `:ravilo-ui` — **Channel view + browse grids** (Movies/Series/My List, filters) | [R11](phase-R11-channel-and-browse-grids.md) |
| R12 | □ Planned | `:ravilo-ui` — **Search** (on-screen keyboard + live results) | [R12](phase-R12-search-screen.md) |
| R13 | □ Planned | `:ravilo-ui` — **Movie & Series detail** (season picker, episode rail, watched/up-next/resume) | [R13](phase-R13-detail-screens.md) |
| R14 | □ Planned | Player — `RaviloPlayer` `actual`s: **forked `jellyfin-androidtv` engine** (Android, in `:ravilo-player`, GPL) / browser video (web); resume, progress, binge next-up | [R14](phase-R14-player.md) |
| R15 | □ Planned | On-device **Settings** + first-run **pairing UX** (skin picker, playback prefs; writes via `PUT /api/tv/settings`) | [R15](phase-R15-ondevice-settings-pairing.md) |
| R16 | □ Planned | jellystructure web — **Ravilo config screen** backed by the R04 store (hero/channels/rows/merge/skin, per user) | [R16](phase-R16-jellystructure-config-screen.md) |
| R17 | □ Planned | **Web target hardening** + packaging — `:ravilo-web` canvas a11y/input, both bundles served, E2E across TV + web | [R17](phase-R17-web-target-packaging.md) |

## Suggested sequencing
- **Foundation:** R01 → R02 → R03 → R04 (module + scaffold + auth + config store).
- **Backend data:** R05 → R06 → R07 → R08 (can parallelize once R03/R04 land).
- **Client UI:** R09 first (everything depends on the design system + focus engine), then R10–R13 in
  any order, R14 (player) alongside R13, R15 after R03/R04.
- **Web + admin:** R16 can land any time after R04; R17 last (hardening once screens exist).

## Adding a new phase
1. Create `phase-RNN-short-name.md` here using an existing spec as a template.
2. Add a row to the table above.
3. On completion: move the file to `archive/`, flip status to `✓ Done`, update `../STATUS.md`.
