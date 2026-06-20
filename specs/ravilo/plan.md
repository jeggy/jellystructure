# Ravilo — Plan

This document describes **how Ravilo is built**: the module layout in the jellystructure repo, the
shared code, the `/api/tv/**` surface, the screen set, and the playback model. The
[`constitution.md`](constitution.md) describes what must always be true; this file describes the
current intended implementation. Phase-by-phase "what" lives in [`requirements/`](requirements/).

> Ravilo lives **inside** the jellystructure repository and reuses its backend, build, and shared
> Kotlin. Nothing here removes or changes jellystructure's own behaviour; Ravilo only **adds**
> modules and a new server API namespace.

---

## 1 · Repository / module layout

```
jellystructure/                      # existing repo
├─ src/                              # existing backend (Kotlin/Native) + WASM admin frontend
│   ├─ linuxX64Main/…                #   Ktor CIO server  → adds /api/tv/** (Ravilo backend)
│   └─ wasmJsMain/…                  #   DOM admin frontend (unchanged; + Ravilo config screen, R16)
│
├─ shared/                          # NEW :shared — pure model + API client (KMP, no UI)
│   └─ src/commonMain/…             #   DTOs, enums, language value types, Ktor-client /api/tv layer
│        targets: wasmJs, androidTarget, <backend host>
│
├─ ravilo-ui/                       # NEW :ravilo-ui — Compose Multiplatform shared UI
│   └─ src/commonMain/…             #   theme/skins, components, screens, nav, focus engine, stores
│        + expect RaviloPlayer, expect ImageLoader
│        targets: androidTarget, wasmJs
│
├─ ravilo-player/                   # DEFERRED — not built (see STATUS.md). Was: Android-only fork of
│                                   #   jellyfin-androidtv playback/* (GPL containment boundary). R14
│                                   #   ships direct ExoPlayer/Media3 instead; revisit only if exotic
│                                   #   codec (DTS/TrueHD/AC3) passthrough is needed.
│
├─ ravilo-android/                  # NEW :ravilo-android — Android TV app
│   └─ src/androidMain/…            #   Activity/leanback entry, Coil actual, D-pad glue; direct
│                                   #   ExoPlayer/Media3 (no :ravilo-player — deferred)
│
├─ ravilo-web/                      # NEW :ravilo-web — Compose MP for Web (wasmJs, canvas)
│   └─ src/wasmJsMain/…             #   canvas entry, browser-video actual, key/pointer glue (no fork)
│
├─ specs/                          # jellystructure specs
│   └─ ravilo/                      # THESE specs
└─ design/
    ├─ app/                         # jellystructure web mockups
    └─ ravilo/                      # Ravilo HTML/CSS visual target (prototype)
```

- **`:shared`** is depended on by the backend, the admin frontend, `:ravilo-ui`. DTOs once, everywhere.
- **`:ravilo-ui`** is depended on by `:ravilo-android` and `:ravilo-web`; it holds essentially all UI.
- **`:ravilo-player`** (Android-only) was planned as a **forked `jellyfin-androidtv` `playback/*`
  engine** acting as a **GPL containment boundary**. It is **DEFERRED and not built** (see
  [`STATUS.md`](STATUS.md)): R14 ships the Android `actual RaviloPlayer` on **direct ExoPlayer/Media3**
  inside `:ravilo-android`, which covers all common formats. The fork remains the path back if
  DTS/TrueHD/AC3 passthrough is needed — revisit the GPL boundary then.
- Platform modules are thin: an entry point + the `actual` implementations of `RaviloPlayer` and the
  image loader + input wiring (Android's `RaviloPlayer` `actual` uses ExoPlayer/Media3 directly; the
  deferred `:ravilo-player` fork would slot in here).
  **Target: ≥90% of Ravilo's UI lines live in `:ravilo-ui` common.**

### Build
- Compose Multiplatform Gradle plugin on `:ravilo-ui`, `:ravilo-android`, `:ravilo-web`.
- `:ravilo-android` → APK/AAB (Android TV). `:ravilo-web` → a wasm bundle served as a **separate**
  static app from the jellystructure admin bundle (distinct entry HTML; both can be hosted by the
  backend or any static host).
- The backend gains `/api/tv/**` routes; no new process.

---

## 2 · `:shared` — DTOs & API client

Serializable DTOs (illustrative; defined once, consumed by backend + both clients):

```
TvSession(deviceId, userId, displayName, isAdmin)
PairingChallenge(code, expiresAt, pollToken)
ClientCapabilities(containers:[…], videoCodecs:[…], audioCodecs:[…], maxAudioChannels, hlsOnly?)
                                                  # what :ravilo-player can decode, incl. forked FFmpeg decoders
StreamTicket(jellyfinBaseUrl, accessToken, itemId, container, directPlay, hlsUrl?, startPositionMs,
             subtitles:[SubTrack], trickplay?, expiresAt)
                                                  # resolved server-side via Jellyfin PlaybackInfo + ClientCapabilities

HomeFeed(heroes: [Hero], channels: [Channel], rows: [Row])
Hero(item: MediaCard, taglineKicker, backdropUrl, logoUrl?, badge?)
Channel(id, name, logoUrl?, style: LOGO|TEXT, brandColor)          # studio/network/genre/tag-backed
Row(id, title, kind: CONTINUE|NEWLY_ADDED|GENRE|CUSTOM, config?, items: [MediaCard])
MediaCard(id, kind: MOVIE|SERIES, title, year, genre, rating, posterUrl, backdropUrl?,
          progressPct?, nextUpLabel?, badge?)                       # progress drives Continue Watching

MovieDetail(card, synopsis, runtime, cast: [Person], related: [MediaCard],
            playback: PlaybackState)
SeriesDetail(card, synopsis, seasons: [Season], cast, related,
             progress: SeriesProgress)                              # watched count, resume pointer
Season(index, name, episodes: [Episode])
Episode(id, n, title, runtime, overview, stillUrl, playback: PlaybackState)
PlaybackState(watched: Bool, positionMs, durationMs, pct)           # authoritative, from Jellyfin

SearchResults(query, items: [MediaCard])
RaviloConfig(heroes:[…], channels:[…], rows:[…], mergeNewlyAdded:Bool,
             defaultSkin, allowSkinOverride, showContinueProgress, tileShape)  # per Jellyfin user
```

`:shared` also exposes a coroutine **`TvApiClient`** (Ktor client) with one suspend function per
`/api/tv` endpoint, returning the DTOs above. Auth header = the device token from pairing.

---

## 3 · `/api/tv/**` — backend surface (control plane)

All under the device-token-guarded `/api/tv` namespace. (Jellystructure's existing `/api/**` admin
surface is untouched.)

| Method · path | Purpose | Notes |
|---|---|---|
| `POST /api/tv/pair/start` | Begin device pairing | returns `PairingChallenge` (code + poll token) |
| `POST /api/tv/pair/poll` | TV polls for approval | → `TvSession` + device token once approved |
| `POST /api/tv/pair/approve` | Web/phone approves a code | called from a signed-in jellystructure session |
| `GET  /api/tv/home` | The composed home feed | server-composes hero/channels/rows from user config |
| `GET  /api/tv/channel/{id}` | A channel's scoped feed | same row set, filtered to studio/network/genre/tag |
| `GET  /api/tv/browse` | Movies / Series / My List grids | `kind`, paging, reuses Phase 30 facets/filters |
| `GET  /api/tv/search` | Multi-language search | reuses Phase 29 `titlesByLang` (every title ever) |
| `GET  /api/tv/movie/{id}` | `MovieDetail` | incl. `PlaybackState`, cast, related |
| `GET  /api/tv/series/{id}` | `SeriesDetail` | seasons + episodes + per-item playback + resume pointer |
| `GET  /api/tv/continue` | Continue + Next Up (merged) | resume items + next unwatched episodes |
| `POST /api/tv/playback/start` | Open a playback session | → `StreamTicket` (Jellyfin base + token + URL) |
| `POST /api/tv/playback/progress` | Report position (heartbeat) | forwarded to Jellyfin playback API |
| `POST /api/tv/playback/stop` | End session | final position; advances next-up server-side |
| `POST /api/tv/mark` | Mark played / unplayed | `{itemId, watched}` → Jellyfin user-data |
| `GET  /api/tv/config` | This user's `RaviloConfig` | the layout the TV renders |
| `PUT  /api/tv/settings` | Viewer-tweakable settings | skin/playback prefs subset; syncs to all devices |

- **Composition is server-side.** `GET /api/tv/home` reads the user's `RaviloConfig` + library and
  returns a ready-to-render `HomeFeed`. The client does not assemble rows.
- **Image & stream URLs in DTOs point at Jellyfin** (data plane) using the brokered token; the client
  never builds Jellyfin URLs from scratch — it uses what the feed/ticket provides.
- Reuses existing jellystructure capabilities: meta-facets/filters (Phase 30), multi-language search
  (Phase 29), per-episode metadata & watched data sourced from Jellyfin.

---

## 4 · Per-user config store

- New SQLite table (SQLDelight) **`ravilo_config`** keyed by Jellyfin `user_id`, holding the
  serialized `RaviloConfig` (+ updated-at). One row per user; created with sensible defaults on first
  read.
- Read/written by: the jellystructure web **Ravilo config screen** (R16) and the viewer-settings
  subset via `PUT /api/tv/settings`. Read by `GET /api/tv/home` and `GET /api/tv/config`.
- **Device sessions** table: `ravilo_device(device_id, user_id, token, created, last_seen)`. Pairing
  challenges are short-lived (in-memory or a tiny table with TTL).
- Because config is keyed by user, **all of a user's devices read the same layout**; a save from any
  surface is visible on next `GET /api/tv/home`.

---

## 5 · `:ravilo-ui` — shared Compose UI

- **Theme/skins:** `RaviloTheme` exposing tokens (colors, type scale, spacing, radii, focus
  treatment) for Aurora / Midnight / Noir; switched by a `StateFlow`.
- **Focus engine:** a shared, testable focus/navigation model over Compose's multiplatform
  `Modifier.focusable` + `FocusRequester` + `onKeyEvent`; platforms feed it D-pad (Android) or
  arrow/pointer (Web) events. One row/column model used by every screen.
- **Components:** `Tile` (poster/landscape), `HeroCarousel`, `ChannelCard`, `ContentRow`,
  `EpisodeCard`, `SeasonPicker`, `CastCircle`, `OnScreenKeyboard`, `AppBar`, focusable `Button`.
- **Screens** (all common): Pairing, Home, Channel, Browse grid (Movies/Series/My List), Search,
  MovieDetail, SeriesDetail, Player chrome, Settings.
- **Stores:** `HomeStore`, `DetailStore`, `SearchStore`, `PlaybackStore`, `SessionStore` — coroutine
  `StateFlow`s fed by `TvApiClient`; screens render store state only.
- **`expect` seams:** `RaviloPlayer` (create/seek/play/pause/position callbacks) and `ImageLoader`.

## 6 · Platform modules

- **`:ravilo-android`** — `LeanbackActivity` hosting the Compose root; `actual RaviloPlayer` uses
  **direct ExoPlayer/Media3** (fed the `StreamTicket`, progress routed to `/api/tv/**`); `actual
  ImageLoader` = Coil; D-pad key events → focus engine; TV banner + `leanback` manifest;
  Now-Playing/Channels integration optional later.
- **`:ravilo-player`** — **DEFERRED, not built** (see [`STATUS.md`](STATUS.md)). Was planned as an
  Android-only GPL module vendoring `jellyfin-androidtv` `playback/core` + `playback/media3` (+
  `media3-ffmpeg-decoder`) with control-plane seams re-pointed at `/api/tv/**`, as the GPL containment
  boundary. Superseded for now by direct ExoPlayer/Media3 in `:ravilo-android`.
- **`:ravilo-web`** — Compose MP for Web (wasmJs, canvas) entry; `actual RaviloPlayer` = a
  browser-native DOM `<video>` element bridged to Compose (positioned with the skiko canvas), with
  **`hls.js`** for HLS and **JASSUB/libass** for ASS/SSA subtitles — **not** a `jellyfin-web` fork
  (that is GPL TS/JS and the browser decodes regardless; its `htmlVideoPlayer` is reference only);
  `actual ImageLoader` = browser-backed painter; keyboard + pointer (+ gamepad) → focus engine. Served
  as its own page, separate from the admin bundle.

---

## 7 · Playback model (control vs data plane)

1. User selects Play/Resume → `POST /api/tv/playback/start {itemId, capabilities}`, where
   `capabilities` is what `:ravilo-player` can decode (containers/codecs/channels, incl. the forked
   FFmpeg decoders).
2. jellystructure calls Jellyfin's **`PlaybackInfo`** with a device profile built from those
   capabilities and returns a **`StreamTicket`**: Jellyfin base URL + a scoped access token + the
   resolved stream URL (direct-play container or an HLS URL) + start position + subtitle/trickplay info.
3. The platform player streams **directly from Jellyfin** using the ticket. jellystructure is not in
   the byte path. (Web uses browser video; Android uses direct ExoPlayer/Media3 — the `:ravilo-player`
   fork is deferred.)
4. The client sends `playback/progress` heartbeats (and `playback/stop` at the end); jellystructure
   relays them to Jellyfin's playback-state API, so watched/resume/next-up stay correct everywhere.
5. On series, finishing an episode lets the server advance the **next-up** pointer; the detail screen
   reflects it on next load.

---

## 8 · What Ravilo deliberately does **not** do

- No metadata scraping, NFO writing, track-flag editing, or scans — those stay jellystructure operator
  features. Ravilo is read + playback-report + per-user-settings only.
- No direct Jellyfin **API** calls for catalog/auth (only the brokered data-plane fetches for
  bytes/images).
- No client-invented rows or derived catalog state.
- No second backend process; no separate Ravilo account system.
