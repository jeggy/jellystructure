# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-20_

## Current focus

**R01–R19 all done.** App running end-to-end on real Android TV hardware; first hardware-testing
pass completed and the following gaps/bugs found and fixed.

### Session 2026-06-20 — hardware-test fixes

**Navigation / focus (R09/R10/R12/R13 gaps):**
- **AppBar is now D-pad focusable.** Nav items (Home / Movies / Series / My List / Search) each have
  `dpadFocusable`; D-pad UP from the hero carousel enters the bar, LEFT/RIGHT steps between items,
  OK navigates to the corresponding screen. `onNavSelect` wired in `RaviloApp` to push
  `Dest.Browse(BrowseKind.MOVIES/SERIES/MY_LIST)` or `Dest.Search`.
- **HeroCarousel** gained an `onUp` callback so pressing UP from the hero reaches the AppBar.
- **SearchScreen / OnScreenKeyboard** now requests focus on its first key on mount (was: keyboard
  rendered but no key focused so D-pad had nowhere to go). Also fixed per-key `focused` state that
  was stuck `true` after first focus — now derived from grid-level `focusRow/focusCol`.
- **MovieDetailScreen / SeriesDetailScreen** guard DOWN from the Play row against empty
  cast/episodes/related lists so focus is not lost.
- **CastCircle** is now D-pad focusable (`focusRequester` + `onLeft/Right/Up/Down/Select`); cast rows
  in MovieDetail and SeriesDetail properly wire `castFR` to each item with UP returning to the Play
  button row.
- **Global Back intercept** in `RaviloApp` catches any unhandled Back key event from any focused
  element and calls `pop()`, so pressing Back always returns to the previous screen regardless of
  which element holds focus.

**Android TV keyboard / IME (R15 gap):**
- `ServerSetupScreen` `TextField` now calls `LocalSoftwareKeyboardController.show()` on
  `onFocusChanged` so the native IME fires with a properly-populated `EditorInfo` (was: `inputType=NULL`,
  keyboard appeared and immediately hid).
- `android:windowSoftInputMode="adjustPan"` added to `<activity>` so the layout does not reflow
  when the IME appears (layout reflow was causing an immediate `HIDE_UNSPECIFIED_WINDOW` hide).

**Image loading (deferred from R09 "Player/image real impls — later"):**
- Coil 3 (`coil-compose` + `coil-network-ktor3`) added as `commonMain` dependency.
- The `expect/actual` `RemoteImage` stubs replaced with a real `AsyncImage` (Coil 3 is KMP-native;
  no per-platform actual needed). Posters, backdrops, cast circles, channel logos, episode stills
  all load from Jellyfin directly.

**Screen transitions (polish):**
- `AnimatedContent` (200 ms fadeIn / 150 ms fadeOut) wraps the `when(dest)` dispatch in
  `RaviloApp` so screen pushes/pops crossfade rather than hard-cutting.

**ravilo-web build (R17 gap):**
- `ravilo-web/webpack.config.d/skiko.js` — `NormalModuleReplacementPlugin` redirects
  `'./skiko.mjs'` imports to the Skiko npm package dir (was: `Module not found` compile error);
  `devServer.static` entry ensures `skiko.wasm` is served at runtime.

### Next steps
- End-to-end playback verification with a real Jellyfin + jellystructure instance
- R14 player bring-up: vendor `jellyfin-androidtv` `playback/*` into `:ravilo-player`; rewire its
  stream-resolution + progress-report seams to `/api/tv/**`
- Player duration display: currently estimated at 0 ms; needs `durationMs` from `StreamTicket` or
  `PlaybackState` passed through `PlayerStore`

---

## Streaming model (key invariant)

**jellystructure provides; Jellyfin streams.** This split governs every data-path decision:

| What | Who serves it | How client gets it |
|------|--------------|-------------------|
| Home feed / browse / search / detail | jellystructure (`/api/tv/**`) | `TvApiClient` with device token |
| Poster / backdrop / still / logo images | **Jellyfin** image API | URL in DTO → Coil fetches directly |
| HLS / direct-play stream URL | **Jellyfin** (jellystructure resolves via `PlaybackInfo` and puts it in `StreamTicket`) | `StreamTicket.hlsUrl` → ExoPlayer / `<video>` |
| Progress heartbeat relay | jellystructure (`POST /api/tv/playback/progress`) → Jellyfin | client POSTs; jellystructure forwards |

jellystructure is **never** in the byte path for video or images. It acts as:
1. The **auth broker** — trades device pairing code for a scoped Jellyfin access token
2. The **control plane** — composes feeds, resolves playback tickets, stores per-user config
3. The **progress relay** — forwards heartbeats and stop events to Jellyfin's user-data API

The TV client's only "login" is to jellystructure. It never signs into Jellyfin itself, never builds
a Jellyfin URL from scratch, and never writes Jellyfin user-data directly.

---

## Foundational decisions locked (constitution)

- **Control plane = jellystructure only; data plane = Jellyfin directly.** The client "logs into" only
  jellystructure (pairing); video/images stream from Jellyfin via brokered tokens.
- **Compose Multiplatform, two targets:** Android TV + Web/WASM **canvas** (browser video).
  Maximise sharing — screens/theme/components/focus/state live once in `:ravilo-ui`. The
  jellystructure "no Compose for Web" rule is scoped to the **admin frontend** only; the two WASM
  bundles (admin DOM, Ravilo canvas) coexist.
- **Android player engine forked from `jellyfin-androidtv` (GPL).** Rather than a from-scratch Media3
  integration, the Android `actual RaviloPlayer` forks Jellyfin's `playback/*` engine (Media3 +
  `media3-ffmpeg-decoder` for DTS/TrueHD/AC3/…), contained in a new Android-only **`:ravilo-player`**
  module. This makes the **Android client GPL**; the fork's direct-to-Jellyfin stream/progress seams
  are re-pointed through `/api/tv/**`. **Web** uses the **browser-native** stack (DOM `<video>` +
  `hls.js` + JASSUB), **not** a `jellyfin-web` fork. The **whole repo is licensed GPL-3.0**. _(Decided 2026-06-19.)_
- **DTOs defined once** in `:shared`; reused by backend + admin frontend + both Ravilo clients.
- **Config is server-owned, per Jellyfin user, synced** across all a user's devices.
- **The TV renders server-composed layout & server-pushed state**; **Ravilo never mutates the
  library** (only playback progress, played/unplayed, per-user settings). **Non-admin** Jellyfin users
  are allowed.

## Design reference

- Visual target: the prototype in `design/ravilo/` (`Ravilo TV.html`, `ravilo.css`) — Aurora/Midnight/
  Noir skins, jellyfish brand, hero/channel-rail/rows, movie+series detail with watched/resume, search.
- The per-user config surface is mocked in `design/app/ravilo-config.html` (drives R16).

## Open threads

- **Stream brokering details (R08):** confirm how the per-user Jellyfin token is obtained/refreshed
  server-side from the paired session. _(Direct-play-vs-HLS policy now decided: client sends
  `ClientCapabilities`, jellystructure resolves via Jellyfin `PlaybackInfo`.)_
- **Player fork bring-up (R14):** vendor `jellyfin-androidtv` `playback/*` into `:ravilo-player`;
  rewire its stream-resolution + progress-report seams to `/api/tv/**`; confirm upstream's exact
  **GPL-2.0-only-vs-or-later** terms; decide how to **track upstream** changes (subtree/submodule/
  manual vendor + version pin).
- **Licensing (resolved):** the whole repo is **GPL-3.0** (root `LICENSE`). Remaining task at
  fork-vendoring time (R14): preserve `jellyfin-androidtv` copyright/license notices (e.g. a
  `:ravilo-player` NOTICE) and confirm its GPL-2.0-only-vs-or-later terms.
- **Pairing approval UX (R03/R15/R16): resolved.** Web-session approval is the primary path — the
  "Pair a TV" card on the Ravilo config page calls `POST /api/tv/pair/approve` with the browser
  cookie. Phone-credentials fallback exists on the backend but has no dedicated UI.
- **Compose-MP web a11y (R17):** canvas accessibility is best-effort; validate against real ATs early.
- **Ravilo config editor (`/ravilo`) — R26/R27/R28 implemented (compiles; runtime verification
  pending).** The admin frontend now binds directly to the shared `RaviloConfig` (the parallel
  `Admin*` DTOs are gone): `:shared` is on the `wasmJsMain` classpath, channels/rows carry generated
  `id`/`order`, and `PUT /tv/admin/config` validates ids and 400s on a bad body instead of 500.
  R27 added `HeroConfig.enabled/order` + `RaviloConfig.heroHeightPct/autoAdvanceSeconds` (clamped in
  `RaviloConfigService.normalize`, honoured in `HomeFeedService`). R28 rebuilt the editor (reorder,
  show toggles, facet-backed channel/row filters, system-row protection, correct Newly-Added merge,
  hero height + auto-advance, page chrome, schematic live preview, sidebar `tv` icon). **Still TODO:**
  the TV client (`:ravilo-ui` `HeroCarousel`) consuming `heroHeightPct`/`autoAdvanceSeconds`; a real
  Jellyfin item-search picker for heroes; end-to-end save/load verification against a live instance.
