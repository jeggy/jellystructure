# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-24_

## Current focus

**TV app-bar + hero polish + focus/back UX (2026-06-24).** Five Ravilo phases from a TV-UI review —
**all built (R51–R55):**
- **[R51](requirements/phase-R51-tv-brand-mark-logo.md)** (FR-RB1) — ✅ done — `components/BrandMark.kt`
  vector-draws the jellyfish **mark** (skin-tinted via `accentGradient`, from `ravilo-mark.svg`); the app
  bar shows the mark + an **ink** wordmark.
- **[R52](requirements/phase-R52-tv-compact-appbar.md)** (FR-RN1) — ✅ done — `AppBar` compacted
  (60 dp / 15 sp / 12×6 / 18-gap); **Search** moved from a nav tab to a right-side drawn-magnifier
  `SearchIcon` (search · clock · avatar). Nav lists + `RaviloApp` index maps remapped (Top 10 → 4,
  `DISCOVER_NAV_INDEX = 4`); focus chain: last nav → search icon → avatar.
- **[R53](requirements/phase-R53-tv-hero-buttonless.md)** (FR-RH1) — ✅ done — `HeroCarousel` is one
  focusable surface (select → detail, Left/Right pages cyclically, subtle inset focus ring, no scale);
  buttons removed; page **dots** moved bottom-right (active = accent pill). Design mockup synced.
- **[R54](requirements/phase-R54-tv-focus-reveal-label.md)** (FR-RF1) — ✅ done — `dpadFocusable` moved
  from the poster `Box` to the outer tile `Column` in `Tile.kt`, so the bring-into-view target spans the
  label; draw-only scale/ring stay on the poster (R42 preserved).
- **[R55](requirements/phase-R55-tv-back-scrolls-to-top.md)** (FR-RBK1) — ✅ done — shared
  `Modifier.backToTopOnBack` (`focus/BackToTop.kt`) on Home/Discover/Channel/Browse/Search: Back
  **scrolls to top** + refocuses a top target when scrolled, and only pops/exits at the top (harder to
  close the app by accident); detail + Player screens excluded.

**Phases R01–R50 complete.** R48–R50 (Discover / Top 10: `/api/tv/discover` API + per-user
`DiscoverConfig`, the Compose Top 10 screen + dedicated detail with live acquisition status, and the
`/ravilo` config-editor Top 10 section) landed 2026-06-24 — see "Done — Discover / Top 10" below
(`:ravilo-ui` compiles android + wasmJs; on-device verification pending). Earlier:
[R47](requirements/phase-R47-detail-focusable-draw-only-scale.md)
(2026-06-23) finished the R42 viewport-jump fix on the **detail screens**: R42 made the focus scale
draw-only "only on Tile/ChannelCard", so navigating Movie/Series detail still jumped — `EpisodeCard`,
`SeasonPicker` pills and `RaviloButton` applied `.scale` (and the button lift) as an **ancestor** of
`.dpadFocusable`, so the focused bounds grew with the spring and the scroll chased them. Moved scale +
glow + lift into an inner `graphicsLayer` with the focusable on a fixed-size outer; `CastCircle` already
scaled a descendant (unchanged). `:ravilo-ui` (android + wasmJs) compiles; `:ravilo-android:assembleDebug`
builds. _Verify on-device:_ season picker / episode rail / action buttons no longer jump.

## Done — Discover / Top 10 (built 2026-06-24)

A new TV surface: a **Top 10** tab driven by third-party popularity charts (Netflix via Tudum first),
where titles not in the library can be **requested** and fetched through Radarr/Sonarr, with a **live,
multi-stage status indicator** (requested → queued → downloading% → importing → available). Backend
engine + chart ingestion are jellystructure phases (**56** acquisition pipeline, **57** chart
ingestion — see [`../STATUS.md`](../STATUS.md)); the Ravilo side is three phases, now **built + committed**:

- **[R48](requirements/phase-R48-discover-api.md)** — `/api/tv/discover` API: a `discover` block on the
  per-user `RaviloConfig` (enabled/source/region/ordered lists), composition of charts + acquisition
  status, server-decided `discoverAvailable` gating, and live `acquisition_changed` over the R33 events
  socket.
- **[R49](requirements/phase-R49-tv-discover-screen.md)** — the TV experience: gated Top 10 tab, ranked
  rows with rich status indicators, a **separate** DiscoverDetailScreen (no playback/seasons) with a
  status-driven Request/Watch-Now button (trailers out of scope for now).
- **[R50](requirements/phase-R50-config-editor-discover.md)** — the `/ravilo` config editor section to
  pick, per user, which charts appear (drag-reorder/toggle), gated on Radarr.

_On-device follow-up (2026-06-23), after deploying R44–R46 to stue TV:_
playback was a black screen + empty home feed because the **paired Jellyfin user token had gone stale
(401)** — `getSeriesEpisodes` returned empty, so `DetailService` fell back to `id = jfEp?.id ?:
ep.path`, sending the episode **file path** as the playback id (`/Items//mnt/series/…mkv` →
CURLE_URL_MALFORMAT), and the audio/sub metadata (R46/R41) was starved because it all comes from the
failing `getItemDetail`. Fixed with a **server-token fallback** (`JellyfinClient.isTokenValid` +
`tvToken()` across Playback/Detail/HomeFeed/Browse): use the paired user token when Jellyfin still
accepts it, else the long-lived server token (userId stays in each URL, so per-user data is correct).
Also a **R45 follow-up**: the first pass only snapped to top *after* focus, so the reveal-scroll was
still visible — added a shared edge `BringIntoViewSpec` (`focus/BringIntoView.kt`) on the Home
LazyColumn + both detail Columns so focusing an already-visible Play/Resume is a no-op. Release APK
deployed in-place to stue TV; **the backend must be restarted (`runBackend`) for the token/audio/Phase-50
fixes to take effect — the native `[be]` does not hot-reload.**

The 2026-06-23 on-device batch (R44–R46) is done; latest is
**[R45](requirements/phase-R45-entry-scroll-focus-restore.md)** — "top action focused ⇒ top of
page": the Movie/Series detail actions row and the Home hero snap the scroll to the top via
`onFocusChanged` on entry and whenever focus returns up to Play/Resume/the hero (so the full hero
re-frames instead of the bottom-aligned button's bring-into-view stranding it), and the content-row
`BringIntoViewSpec` reveals a start-clipped tile at the row's `trackPadH` left inset so returning to
item 0 restores its padding (fully-visible tiles still never move — R42/R43 intact).

**[R44](requirements/phase-R44-media-transport-keys.md)** (2026-06-23) — the remote's physical
Play/Pause/Stop/FF/Rew/Next/Prev keys were inert; now `dpadFocusable` maps + consumes `Key.Media*`
(+ `Spacebar`), PlayerScreen routes them to `togglePlay`/`skip`/`advanceNext`/restart/exit regardless
of focus or chrome state, a Media3 `MediaSession` binds to the Android ExoPlayer (OS + external
controllers; no double-toggle because Compose consumes the focused key), and web wires
`navigator.mediaSession`. **[R46](requirements/phase-R46-track-label-metadata.md)** (2026-06-23) — the
Audio/Subtitles picker showed bare codes; the `StreamTicket` now carries an `audio: List<AudioTrack>`
from Jellyfin `MediaStreams` (`DisplayTitle` like "Synstolkning"), `RaviloPlayer.load` consumes it,
the Android getter prefers the server label (by audio-stream order) then a humanized language
(`languageName()`), and the web actual surfaces the labels. Remaining on-device item:
**[R45](requirements/phase-R45-entry-scroll-focus-restore.md)** fix entry scroll/focus so a focused
top action re-frames the hero at the top instead of auto-scrolling to a mid-page Play/Resume button
(`playFR.requestFocus()` bring-into-view), and restore a content row's left inset after you scroll in
and back.

**Phases R01–R43 complete.** **R43** (2026-06-23) — focus-navigation smoothness: after R42 removed the
jump, moving focus still felt laggy because the focus shadow/border read animated values as modifier
parameters (recomposition every frame) and the spring was soft. The whole focus animation now runs in the
**draw phase** (scale + shadow in `graphicsLayer{}`, ring in `drawWithCache`) so a focus move recomposes
once, not per frame, with a snappier `StiffnessMedium` spring (`Tile.kt` + `ChannelCard.kt`).
`LazyLayoutCacheWindow` neighbour-prefetch is deferred (needs Compose ≥1.9; repo is on CMP 1.8.1). From
2026-06-22 on-device feedback: **R38** launcher-icon optical centering
(the top-heavy mark read low — composite with an upward offset, icon pack regenerated), **R39** channel
logo fills the button (cover + clipped corners on TV + admin + mockup), **R40** instant back navigation
(retain screen stores in an app-root registry + idempotent load — no reload skeleton), **R41** TV subtitle
tracks (picker now reads player-discovered tracks like audio + backend sources external subs from Jellyfin
`MediaStreams` — fixes the always-zero, episodes included), **R42** focus-animation viewport jump
(edge-based `BringIntoViewSpec` so visible tiles aren't re-centred). All compile; APK redeployed to stue TV. [R36 — Channel-button editor](requirements/phase-R36-channel-button-editor.md)
**is complete (2026-06-22).** The channel-button look is configured only in the editor popup: the inline
Logo/Text + color cluster is gone; each config row shows a live chip + a **✎ Edit** pencil. In the popup,
**Logo** means a real **image asset** — pick a prior upload or upload a PNG/SVG (stored server-side under
`<dataDir>/channel-logos`, served at `/api/tv/channel-logos/<file>`, referenced as `logoUrl`); **Text**
shows the channel name; the **brand fill** has the five presets + a **＋ custom** **solid/gradient** builder
(From/To + angle), so `brandColor` is a CSS color **or** `linear-gradient(…)`. Backend:
`RaviloConfigService.normalize` sanitises `brandColor` (R26 — solid or single gradient, else clamp); the TV
`ChannelCard.parseBrandFill` renders a gradient brandColor; new admin endpoints
`GET/POST /api/tv/admin/channel-logos` (upload/list) + a public serve route. Mirrors the design in
`design/app/ravilo-config.html` + `design/app/ravilo-builders.{js,css}`; revises R32 §C2. All targets build;
backend routes runtime-verified (admin 401 / public-serve 404).

**[R37 — Brand-mark centering + asset-pack regeneration](requirements/phase-R37-brand-mark-centering.md)
is complete (2026-06-22).** The master `ravilo-mark.svg` is reframed to `viewBox 12 20 76 76` (**paths
untouched** — same shape). The entire `design/ravilo/assets/**` pack **and** the shipping
`ravilo-android/src/main/res/**` icons were regenerated from it — adaptive foreground/monochrome,
legacy mipmaps + round at every density, splash, store 512, the leanback banner, and the feature/
TV-banner graphics (lockup + "CINEMATIC STREAMING FOR JELLYFIN" kicker in Space Grotesk). All four
in-app inline marks use the recentred frame; the config-page header (`RaviloConfig.kt`) now flex-aligns
the mark at an `em` size so it tracks the "Ravilo TV" cap-height instead of floating high. The marks were
rendered with headless Chromium (faithful gradient) and composited over the unchanged brand field;
framing-only, no redesign. Follow-up to R22.

**Phases R01–R35 complete.** [R35 — Home-screen TV sizing + hero framing](requirements/phase-R35-home-screen-tv-sizing.md)
brings the **Home hero carousel and content-row headers** down to the same TV-tuned scale R34 set for the
detail screens (hero title 46→34sp, meta→15, synopsis→14, row headers 22→18), shrinks the shared
**`RaviloButton`** chrome **globally** (height 52→44dp, padding 24/10→18/8, corner 12→10, label 16→15sp —
so home and detail buttons match), and re-frames the **hero backdrop crop** to `center 26%` via a shared
`RaviloDimens.heroBackdropAlignment = BiasAlignment(0f, -0.48f)` across all three heroes (Home/Movie/Series),
replacing the old `TopCenter` that pushed subjects under the AppBar. These are **fixed-tuned** constants:
config-driven values (`heroHeightPct`, `autoAdvanceSeconds`, `tileShape`, `uiDensity`/`LocalTileScale`) are
untouched. The `design/ravilo/` mockup was mirrored and R35 supersedes R24's home Hero numbers.

[R34 — TV detail layout + content size](requirements/phase-R34-detail-layout-content-size.md)
reworked Movie/Series detail to a **full-bleed viewport hero** (title/meta/synopsis/Play overlaid in the
lower third) so the page opens at the top instead of auto-scrolling and stranding the screen, with TV-tuned
detail sizing; and added a per-user **`uiDensity`** (Compact/Cozy/Comfortable) that scales the grid tiles
on Home/Channel/Browse, edited in the config Behaviour section and applied **live** via R33.

[R32 — Unified filter workbench + hero builder + Library
round-trip](requirements/phase-R32-unified-filter-workbench.md) shipped: a shared condition-stack
builder (`ui/Workbench.kt`) powers Content rows, Channels **and** the jellystructure Library page;
**audio-track and hero_item are universal facets** (the feed evaluator `ConditionEvaluator` + a
`heroItem`/`viewer` predicate on `/api/media`); `ChannelConfig`/`RowConfig` gained `match` + a
`conditions` stack (additive — legacy single typed filters still honoured when no stack is set); the
Library offers **⚙ Add filter** + **Save filter as… Channel / Content row** (viewer picker), and
Movie/Series detail gained **★ Feature in Ravilo…** (locked-title hero builder + viewer picker writing
`HeroConfig`). Hero height clamp is now 30–100% with a 7s auto-advance default (R32 §F).

**[R33 — Live config push](requirements/phase-R33-live-config-push.md) is complete (2026-06-22).** A per-user
WebSocket (`/api/tv/events`, device-token auth via query param for browsers) so a config write
(`RaviloConfigService.save`, covering both the admin editor and viewer settings) pushes a `config_changed`
signal to that user's connected TVs, which **silently re-pull** the authoritative feed/config — channel
adds, hero-height/tile-shape/auto-advance, row reorders and skin changes appear in ~1s with no reload, no
flicker, and no loss of scroll/focus. The WS reconnects with backoff and does a full resync on (re)connect;
whichever layout screen (Home/Channel/Settings) is on top reflects the change, and refreshes run on
background scopes so navigation is never blocked. Renders server-pushed state only (constitution). The
Android Ktor engine moved Android→CIO (the Android engine has no WS support). Degrade-to-poll (§E3) and
the payload-in-event optimization (§F) are deferred.

Note the workbench preview reports an honest **≈** count when a stack uses
none-of / not-contains / Match-ANY (those are evaluated on the TV by `ConditionEvaluator`, not by the
positive-only `/api/media` count).

## Foundational decisions locked (constitution)

- **Control plane = jellystructure only; data plane = Jellyfin directly.** The client "logs into" only
  jellystructure (pairing); video/images stream from Jellyfin via brokered, scoped tokens.
- **Compose Multiplatform, two targets:** Android TV + Web/WASM **canvas** (browser video).
  Maximise sharing — screens/theme/components/focus/state live once in `:ravilo-ui`. The
  jellystructure "no Compose for Web" rule is scoped to the **admin** frontend; the two WASM bundles
  (admin DOM, Ravilo canvas) coexist.
- **Android player = Media3/ExoPlayer + Jellyfin's prebuilt FFmpeg decoder (GPL).** The Android
  `actual RaviloPlayer` (in `:ravilo-ui` `androidMain`) is a direct Media3/ExoPlayer integration;
  Jellyfin's prebuilt **`media3-ffmpeg-decoder`** (DTS/TrueHD/AC3/…) is added via a `RenderersFactory`
  in the Android-only **`:ravilo-player`** module — the GPL containment boundary (only `:ravilo-android`
  links it). A **source fork** of `jellyfin-androidtv`'s `playback/*` was evaluated and **skipped as
  redundant** (R31). This makes the **Android client GPL**; stream resolution + progress reporting route
  through `/api/tv/**`. **Web** uses the **browser-native** stack (DOM `<video>` + `hls.js` + JASSUB),
  **not** a `jellyfin-web` fork (GPL TS/JS, no decoder gain). The **whole repo is licensed GPL-3.0**
  (root `LICENSE`). _(Decided 2026-06-19; source fork skipped R31.)_
- **DTOs defined once** in `:shared`; reused by backend + admin frontend + both Ravilo clients.
- **Config is server-owned, per Jellyfin user, synced** across all a user's devices.
- **The TV renders server-composed layout & server-pushed state**; **Ravilo never mutates the
  library** (only playback progress, played/unplayed, per-user settings). **Non-admin** Jellyfin users
  are allowed.

## Design reference

- Visual target: the prototype in `design/ravilo/` (`Ravilo TV.html`, `ravilo.css`) — Aurora/Midnight/
  Noir skins, jellyfish brand, hero/channel-rail/rows, movie+series detail with watched/resume, search.
- The per-user config surface is mocked in `design/app/ravilo-config.html` (drives R16).

## Newly planned (2026-06-24)

- **[R56](requirements/phase-R56-tv-hero-no-focus-border.md) — Hero carousel: remove
  focus border** (FR-RHB1). R53 added an inset ring (`border(3.dp, colors.focusRing,
  RoundedCornerShape(14.dp))`) as the hero's focus indicator. Remove it entirely —
  the hero remains focusable/selectable/navigable but has no visible focus treatment.
  Constitution gains an explicit exception for full-bleed hero surfaces. Small change
  to `HeroCarousel.kt` + `ravilo.css`.

## Open threads

- **Stream brokering details (R08):** confirm how the per-user Jellyfin token is obtained/refreshed
  server-side from the paired session. _(Direct-play-vs-HLS policy now decided: client sends
  `ClientCapabilities`, jellystructure resolves via Jellyfin `PlaybackInfo`.)_
- **Player engine (resolved, R31):** Android uses direct Media3/ExoPlayer + Jellyfin's prebuilt
  `media3-ffmpeg-decoder` (Media3 1.8.0) in the GPL-contained `:ravilo-player` module; the
  `jellyfin-androidtv` `playback/*` **source fork was skipped as redundant**. Remaining: an on-device
  codec test (DTS/TrueHD/AC3) on real TV hardware.
- **Licensing (resolved):** the whole repo is **GPL-3.0** (root `LICENSE`); the `:ravilo-player`
  `NOTICE` preserves the decoder's upstream attribution.
- **Admin↔code sync (SYNC-AUDIT-2026-06) — open decisions:** §3.1 bring the R32 guided hero builder
  to the admin config editor, or accept the current inline hero-input row? §3.2 add a "Pair a TV"
  section to `design/app/ravilo-config.html`, or keep it code-only by intent? §5.2/§5.3 are optional
  code-adoption polish (detail pagebar dropdown menus + `.tabs2` tab classes) — no spec needed.
- **Next sync audit should be TV/player-first:** the 2026-06 audit diffed admin screens only and did
  **not** pixel-diff the Compose TV screens or examine the player; schedule a TV+player-focused pass
  (R34/R35 reshaped TV sizing).
- **Pairing approval UX (R03/R15):** web-session approval vs phone-credentials form — pick the primary
  path.
- **Compose-MP web a11y (R17):** canvas accessibility is best-effort; validate against real ATs early.
