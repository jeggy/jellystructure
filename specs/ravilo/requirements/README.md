# Ravilo — Requirements

The **"what"** for Ravilo — one file per development phase. This table is the **single source of
truth** for which Ravilo phases exist and whether each is done or planned. Ravilo phases are prefixed
**`R`** to keep them distinct from jellystructure's numeric phases.

- Read [`../constitution.md`](../constitution.md) (Ravilo rules) and [`../plan.md`](../plan.md)
  (module layout + API) before starting any phase.
- Ravilo lives in the jellystructure repo; backend phases add `/api/tv/**` routes to the existing
  server, UI phases land in `:ravilo-ui` (shared Compose) with thin `:ravilo-android` / `:ravilo-web`
  modules. R14 ships direct ExoPlayer/Media3; the Android-only `:ravilo-player` FFmpeg decoder module
  (DTS/TrueHD/AC3) landed in R31.

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
| R14 | ✓ Done | **Player** — `RaviloPlayer` expect/actual (direct ExoPlayer/Media3 on Android, browser `<video>` on WASM); full player chrome; episode rail; next-up countdown; Audio & Subs picker | [R14](phase-R14-player.md) |
| R15 | ✓ Done | On-device **Settings** + first-run **pairing UX** (server URL setup, skin picker, playback prefs) | [R15](phase-R15-ondevice-settings-pairing.md) |
| R16 | ✓ Done | jellystructure web — **Ravilo config screen** backed by the R04 store (hero/channels/rows/merge/skin, per user) | [R16](phase-R16-jellystructure-config-screen.md) |
| R17 | ✓ Done | **Web target hardening** + packaging — `:ravilo-web` canvas a11y/input, both bundles served, E2E across TV + web | [R17](phase-R17-web-target-packaging.md) |
| R18 | ✓ Done | **Multi-user profiles & fast switching** — several Jellyfin users per TV, cached tokens, "Who's watching?" + avatar switcher | [R18](phase-R18-multi-user-profiles.md) |
| R19 | ✓ Done | **Interface localization** (en / da / fo), per Jellyfin user, set in Jellystructure | [R19](phase-R19-localization.md) |
| R20 | ✓ Done | **Performance & correctness overhaul** — focus-latch freeze fix, store coroutine hygiene, allocation hot-paths, CastCircle D-pad focus | [R20](phase-R20-performance-overhaul.md) |
| R21 | ✓ Done | **Back navigation + focus polish** — D-pad Back pop, spring animation on all focusables, halo focus glow | [R21](phase-R21-back-navigation-polish.md) |
| R22 | ✓ Done | **Android TV APK packaging** — TV banner, adaptive icon, splash screen, R8 release build + signing | [R22](phase-R22-apk-packaging.md) |
| R23 | ✓ Done | **Design system foundations** — bundled Sora + Space Grotesk fonts, corrected skin palettes (Noir warm-gold), new tokens, `RaviloDimens` | [R23](phase-R23-design-system-foundations.md) |
| R24 | ✓ Done | **Component upsizing & visual fidelity** — AppBar 92 dp, Hero 600 dp/72 sp, Tile full-size + gradient fallback, channel card, episode card, row headers, buttons | [R24](phase-R24-component-visual-fidelity.md) |
| R25 | ✓ Done | **Polish: loading skeletons, focus glow fix, smooth scroll** — shimmer skeletons, `focusGlow` shadow color, minimal-scroll `LazyRow`, season progress bar, browse chip contrast | [R25](phase-R25-polish-loading-states.md) |
| R26 | ✓ Done | **Config DTO unification** — drop parallel `Admin*` DTOs; `wasmJsMain` uses `:shared` `RaviloConfig` directly; backend validates IDs and clamps values | [R26](phase-R26-config-dto-unification.md) |
| R27 | ✓ Done | **Layout model extensions** — `HeroConfig.enabled/order`, `heroHeightPct`, `autoAdvanceSeconds`; feed filters by enabled, sorts by order, passes layout params | [R27](phase-R27-layout-model-extensions.md) |
| R28 | ✓ Done | **Config editor fidelity** — `/ravilo` rebuilt: drag-reorder, show/hide toggles, typed filters, system-row protection, hero height slider, tile-shape picker, live schematic preview | [R28](phase-R28-config-editor-fidelity.md) |
| R29 | ✓ Done | **TV control-plane contract review fixes** — stop/settings request-type bugs, server `ignoreUnknownKeys`, shared `ViewerSettingsRequest`, `viewerSkinOverride`/`effectiveSkin`, `session_id` removal, FocusGrid empty-row guard, R26 clamp/UUID reconcile, `:ravilo-player` deferred doc sync | [R29](phase-R29-tv-contract-review-fixes.md) |
| R30 | ✓ Done | **Native focus traversal** — replace the manual `FocusEngine` (per-item `requestFocus`) with Compose focus search + `focusRestorer`; fixes held-key lag/stuck on lazy rows & grids; `FocusEngine.kt` deleted | [R30](phase-R30-native-focus-traversal.md) |
| R31 | ✓ Done | **`:ravilo-player` FFmpeg decoder** — jellyfin's `media3-ffmpeg-decoder` in an Android-only, GPL-contained module + renderers factory for DTS/TrueHD/AC3 (Media3 1.8.0); source-vendor fork skipped as redundant; on-device codec test pending | [R31](phase-R31-player-engine-fork.md) |
| R32 | ✓ Done | **Unified filter workbench + hero builder + Library round-trip** — one shared condition-stack builder for Content rows, Channels & the Library; audio-track + hero-item as universal facets; Save-filter-as-Channel/Row + "Feature in Ravilo" on Movie/Series detail | [R32](phase-R32-unified-filter-workbench.md) |
| R33 | ✓ Done | **Live config push** — per-user WebSocket (`/api/tv/events`); a config write (`RaviloConfigService.save`) pushes `config_changed` to that user's connected TVs, which silently re-pull the feed/config so channel/hero-height/tile-shape/row/skin edits appear in ~1s with no reload | [R33](phase-R33-live-config-push.md) |
| R34 | ✓ Done | **TV detail layout + content size** — full-bleed viewport hero on Movie/Series detail (opens at top, no entry-scroll that strands the screen) + TV-tuned detail sizing; per-user `uiDensity` (Compact/Cozy/Comfortable) scales the grid tiles, edited in the config screen, applied live via R33 | [R34](phase-R34-detail-layout-content-size.md) |

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
