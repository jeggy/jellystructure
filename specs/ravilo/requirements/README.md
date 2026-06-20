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
| R01 | ✓ Done | `:shared` KMP module — DTOs + Ktor `TvApiClient` (once, for backend + both clients) | [R01](phase-R01-shared-module.md) |
| R02 | ✓ Done | Compose Multiplatform scaffolding — `:ravilo-ui` + `:ravilo-android` + `:ravilo-web`, one "hello focus" screen on both targets | [R02](phase-R02-compose-mp-scaffolding.md) |
| R03 | ✓ Done | Backend — `/api/tv/**` namespace + TV **device pairing** auth (code flow, non-admin users, device sessions) | [R03](phase-R03-device-pairing-auth.md) |
| R04 | ✓ Done | Backend — per-Jellyfin-user **RaviloConfig store** (server-owned, synced across devices) + `GET /api/tv/config` | [R04](phase-R04-per-user-config-store.md) |
| R05 | ✓ Done | Backend — **home feed** composition (hero, channels, rows incl. merged Continue+NextUp & Newly-Added) | [R05](phase-R05-home-feed-api.md) |
| R06 | ✓ Done | Backend — **browse + multi-language search** + channel-scoped feeds (reuse Phase 29/30) | [R06](phase-R06-browse-search-api.md) |
| R07 | ✓ Done | Backend — **detail** API (movie/series, seasons/episodes, watched-state/resume, cast, related) | [R07](phase-R07-detail-watched-state-api.md) |
| R08 | ✓ Done | Backend — **playback brokering + progress reporting** (StreamTicket, heartbeats, mark played/next-up) | [R08](phase-R08-playback-brokering-reporting.md) |
| R09 | ✓ Done | `:ravilo-ui` — **design system + focus engine** (Aurora/Midnight/Noir, components, shared D-pad/pointer model) + app bar | [R09](phase-R09-design-system-focus-engine.md) |
| R10 | ✓ Done | `:ravilo-ui` — **Home** (hero carousel, channel rail, content rows) wired to `GET /api/tv/home` | [R10](phase-R10-home-screen.md) |
| R11 | ✓ Done | `:ravilo-ui` — **Channel view + browse grids** (Movies/Series/My List, filters) | [R11](phase-R11-channel-and-browse-grids.md) |
| R12 | ✓ Done | `:ravilo-ui` — **Search** (on-screen keyboard + live results) | [R12](phase-R12-search-screen.md) |
| R13 | ✓ Done | `:ravilo-ui` — **Movie & Series detail** (season picker, episode rail, watched/up-next/resume) | [R13](phase-R13-detail-screens.md) |
| R14 | ✓ Done | Player — `RaviloPlayer` `actual`s: ExoPlayer/Media3 (Android) / browser `<video>` (web); shared chrome; resume, progress heartbeats, binge next-up | [R14](phase-R14-player.md) |
| R15 | ✓ Done | On-device **Settings** + first-run **pairing UX** (skin picker, playback prefs; writes via `PUT /api/tv/settings`) | [R15](phase-R15-ondevice-settings-pairing.md) |
| R16 | ✓ Done | jellystructure web — **Ravilo config screen** backed by the R04 store (hero/channels/rows/merge/skin, per user) | [R16](phase-R16-jellystructure-config-screen.md) |
| R17 | ✓ Done | **Web target hardening** + packaging — `:ravilo-web` canvas a11y/input, both bundles served, E2E across TV + web | [R17](phase-R17-web-target-packaging.md) |
| R18 | ✓ Done | **Multi-user profiles & fast switching** — several Jellyfin users per TV, cached tokens, "Who's watching?" + avatar switcher (FR-RV18) | [R18](phase-R18-multi-user-profiles.md) |
| R19 | ✓ Done | **Interface localization** (en / da / fo), per Jellyfin user, set in Jellystructure (FR-RV19) | [R19](phase-R19-localization.md) |
| R20 | ✓ Done | **Performance & correctness overhaul** — focus-latch fix, store job cancellation, allocation memoization, lazy-list keys, scroll behaviour, Compose rule violations (FR-RV20) | [R20](phase-R20-performance-overhaul.md) |
| R21 | ✓ Done | **Back navigation & focus polish** — root back-intercept so Back never exits mid-stack; spring animations, 1.06–1.10× scale, animated 0→3dp ring, glow shadow on all focusable components (FR-RV21) | [R21](phase-R21-back-navigation-focus-polish.md) |

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
