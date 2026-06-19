# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-19_

## Current focus

**R01–R19 all done.** App is running end-to-end on Android TV.

Recent completions (this session):
- **R15 additions** — Server URL setup screen (first-run, native Android TV keyboard via `TextField`),
  Android manifest `INTERNET` permission + `usesCleartextTraffic`, "Change server" escape hatch on
  pairing error/expired states (focusable Retry + Change server buttons).
- **R16 addition** — "Pair a TV" card on the jellystructure Ravilo config page (`POST
  /api/tv/pair/approve` via browser session; code input + Pair button + inline feedback).

Next steps (post-R19, no spec yet):
- End-to-end pairing verification with a real jellystructure instance
- R14 player bring-up testing (ExoPlayer/Media3 on real hardware)
- Performance & polish pass before any public release

## Foundational decisions locked (constitution)

- **Control plane = jellystructure only; data plane = Jellyfin directly.** The client "logs into" only
  jellystructure (pairing); video/images stream from Jellyfin via brokered, scoped tokens.
- **Compose Multiplatform, two targets:** Android TV + Web/WASM **canvas** (browser video).
  Maximise sharing — screens/theme/components/focus/state live once in `:ravilo-ui`. The
  jellystructure "no Compose for Web" rule is scoped to the **admin** frontend; the two WASM bundles
  (admin DOM, Ravilo canvas) coexist.
- **Android player engine forked from `jellyfin-androidtv` (GPL).** Rather than a from-scratch Media3
  integration, the Android `actual RaviloPlayer` forks Jellyfin's `playback/*` engine (Media3 +
  `media3-ffmpeg-decoder` for DTS/TrueHD/AC3/…), contained in a new Android-only **`:ravilo-player`**
  module. This makes the **Android client GPL**; the fork's direct-to-Jellyfin stream/progress seams
  are re-pointed through `/api/tv/**`. **Web** uses the **browser-native** stack (DOM `<video>` +
  `hls.js` + JASSUB), **not** a `jellyfin-web` fork (GPL TS/JS, no decoder gain). The **whole repo is
  licensed GPL-3.0** (root `LICENSE`). _(Decided 2026-06-19.)_
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
  "Pair a TV" card on the Ravilo config page calls `POST /api/tv/pair/approve` with the browser cookie.
  Phone-credentials fallback exists on the backend but has no dedicated UI.
- **Compose-MP web a11y (R17):** canvas accessibility is best-effort; validate against real ATs early.
