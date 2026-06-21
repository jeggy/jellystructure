# Ravilo — Constitution

This document defines the non-negotiable architectural decisions and design constraints for
**Ravilo**, the Android TV streaming app for Jellyfin libraries that are managed and served through
**jellystructure**. All development — human or AI-assisted — must adhere to these rules. If a decision
conflicts with this document, the document wins unless it is formally updated here first.

Ravilo lives **in the jellystructure repository** and shares Kotlin code with it. This is the source
of truth for Ravilo's architecture; [`plan.md`](plan.md) describes *how* it is built today and
[`requirements/`](requirements/) holds the per-phase "what". Where Ravilo depends on a rule from the
**jellystructure** constitution (`../constitution.md`), that rule still applies to the backend; this
document only adds or scopes rules for the TV product.

---

## Vision

Ravilo is a **10-foot, remote-first** streaming front-end — Netflix/Disney+-class polish — for a
household's Jellyfin library, delivered as **both an Android TV app and a browser (WASM) app from one
shared Kotlin + Compose codebase**. It is deliberately **not** the Jellyfin TV app: the experience
(especially series with seasons/episodes) is designed to be calm and obvious, not a settings maze.

Crucially, **Ravilo is a thin, presentation-focused client.** It does not scan, scrape, or write
metadata. Everything it shows — the home layout, the channel buttons, the rows, search — is
**composed by jellystructure** and merely rendered by Ravilo. The same jellystructure that owns NFOs
and artwork on disk also owns *what the TV shows and how it is arranged*, per Jellyfin user.

One sentence: **jellystructure decides; Ravilo displays (on TV and in the browser); Jellyfin streams.**

---

## Control plane vs. data plane (the central split)

This split is the most important rule in this document.

- **Control plane — jellystructure only.** Authentication, the home layout, channels/collections,
  rows, browse, multi-language search, watched-state, per-user configuration, and playback reporting
  all go through the **jellystructure API** (`/api/tv/**`). Ravilo never calls a jellystructure-owned
  concern against any other host.
- **Data plane — Jellyfin directly.** The actual **video/audio byte stream** and **image assets**
  are fetched **directly from Jellyfin** by the device, using a short-lived access token and base URL
  that jellystructure brokers during pairing. jellystructure is **not** a video proxy.

Rationale: routing tens of Mbit/s of video through the Kotlin/Native backend would be wasteful and
fragile; Jellyfin already serves media efficiently on the LAN. jellystructure stays the single
brain (auth, catalog, config, progress) while Jellyfin stays the muscle (bytes). Ravilo holds a
Jellyfin token **only** for the data plane and treats jellystructure as the only API it "logs into".

> If a future deployment cannot expose Jellyfin to the TV directly, jellystructure MAY add an opt-in
> stream/image relay. Until then, the device streams from Jellyfin directly. This is the only
> sanctioned exception and must be explicitly enabled.

---

## Technology Mandates

These choices are fixed. Do not introduce alternatives without updating this document.

### The clients — Compose Multiplatform (Android TV + Web/WASM)
Ravilo ships from **one Compose Multiplatform UI codebase** to two targets; **maximise sharing** — the
goal is that screens, theme, components, navigation, state, and the focus model are written **once**
in common code and run on both.
- **Compose Multiplatform** (`org.jetbrains.compose` / Compose MP) is the UI toolkit, **not**
  Android-only `androidx.tv:tv-material3`. tv-material3 is not multiplatform; shared screens are built
  on **Compose MP `foundation` + a custom Ravilo design system** (we own the components anyway) plus
  Compose's multiplatform focus APIs — native focus traversal (`focusable` + `focusGroup` +
  `focusRestorer`), with `FocusRequester` / `onKeyEvent` reserved for entry points and content actions
  (see R30). The Android module
  MAY add TV-specific niceties on top, but **no shared screen may depend on an Android-only Compose
  artifact.**
- **Android TV target:** native Android app, `leanback` launcher category, TV banner, D-pad primary
  input. Playback uses the **player engine forked from `jellyfin-androidtv`** (Media3/ExoPlayer +
  Jellyfin's FFmpeg software decoders) — see "Player engine & licensing" below.
- **Web/WASM target:** **Compose Multiplatform for Web (wasmJs)**, which is **canvas-based** (Skia via
  skiko). This is an accepted, deliberate tradeoff for Ravilo — see "Two WASM apps coexist" below.
  Playback uses the browser's media stack (HTML5 `<video>` / HLS) behind the shared player interface.
- **Player:** a shared `expect`/`actual` **`RaviloPlayer`** interface. The `actual` on **Android** is
  built on the **playback engine forked from `jellyfin-androidtv`** (GPL — see next subsection); the
  `actual` on **Web** is browser video (HTML5 `<video>`/MSE). The shared player *chrome* (overlay,
  controls, focus) lives once in `:ravilo-ui`; only the engine is platform-specific.
- **Async/state:** kotlinx.coroutines + `StateFlow` reactive stores, shared in common. Screens render
  store state; no ad-hoc mutable view state for server-owned data.
- **Networking:** the shared **Ktor client** over the control plane; the platform's native media stack
  over the data plane. **Images:** an `expect`/`actual` image loader (Coil on Android, Compose MP
  resource/`<img>`-backed painter on Web), pointed at Jellyfin image URLs.

### Player engine & licensing — forked from `jellyfin-androidtv` (GPL)
> **Status: DEFERRED (not built).** R14 ships the Android player on **direct ExoPlayer/Media3**, which
> covers all common formats; the `jellyfin-androidtv` fork and the `:ravilo-player` GPL-containment
> module below are **not yet built** (see [`STATUS.md`](STATUS.md)). The section records the intended
> architecture and the path back if exotic-codec (DTS/TrueHD/AC3) passthrough is later needed. Until
> the fork lands, the repo is not forced to GPL **by a fork** (the license choice still stands on its
> own).

Ravilo's **Android** player is **not** a from-scratch Media3 integration. The official
`jellyfin-androidtv` player is the best-tested playback stack in the ecosystem, so Ravilo **forks its
`playback/*` modules** — `playback/core` (player + play-queue + media-session abstraction) and
`playback/media3` (Media3/ExoPlayer backend) **including the `org.jellyfin.media3:media3-ffmpeg-decoder`**
software decoders so DTS/TrueHD/AC3/E-AC3 and other audio that stock ExoPlayer can't handle still play
— plus their subtitle (ASS/SSA/PGS), trickplay, and audio-passthrough handling, wrapped with Ravilo's
own Compose chrome.

- **The fork is isolated in an Android-only `:ravilo-player` module** — the **GPL containment
  boundary**. Only `:ravilo-android` links it; `:ravilo-ui` and `:ravilo-web` never do.
- **License consequence:** `jellyfin-androidtv` is **GPL-2.0** and the FFmpeg decoder is **GPL-3.0**,
  so the Android client is GPL regardless. The project licenses the **whole repository under GPL-3.0**
  (root `LICENSE`) — the simplest posture, and everything here is Jellyfin-adjacent (itself GPL)
  anyway. (The web bundle, backend, and admin frontend are not *forced* to GPL by the fork — they
  don't link it — but the project chooses one license for all.) When vendoring, **preserve upstream
  copyright/license notices** for the `playback/*` code and confirm `jellyfin-androidtv`'s exact
  **GPL-2.0-only-vs-or-later** terms: GPL-3.0 may incorporate GPL-2.0-**or-later** code, but
  GPL-2.0-**only** would force the repo to GPL-2.0 instead (and then conflict with the GPL-3.0 decoder).
- **Control-plane override of the fork:** upstream `playback/jellyfin` talks to Jellyfin **directly**
  for stream resolution and progress reporting. Ravilo **replaces those seams** so they route through
  jellystructure `/api/tv/**` (a `StreamTicket` in, progress/stop out) — see R08/R14. The fork supplies
  the **engine** (decode/render only); jellystructure remains the only API Ravilo "logs into"
  (Invariant 1).
- **Web** keeps the **browser media stack** behind the same `RaviloPlayer` interface — a DOM
  `<video>` element bridged from Kotlin/WASM, with **`hls.js`** (HLS) and **JASSUB/libass** (ASS/SSA
  subtitles), using the **same server-side `ClientCapabilities`→`PlaybackInfo` resolution** as Android.
  We **do not fork `jellyfin-web`**: it is GPL TS/JS, and the browser decodes the bytes regardless, so
  a fork buys no decoder advantage at high bridging cost — its `htmlVideoPlayer` is a **reference**
  only. The Android `playback/*` fork is Android-only and never crosses to web.

### Two WASM apps coexist in the repo (read carefully)
The repository now produces **two distinct WebAssembly bundles**, and they must not be conflated:
1. **jellystructure admin frontend** — the operator console. Stays exactly as the jellystructure
   constitution mandates: **DOM-based via `kotlinx.browser` + Tailwind, no Compose**. SEO/
   accessibility/CSS-integration reasons stand for that admin tool.
2. **Ravilo web** — the viewer app. **Compose Multiplatform for Web, canvas-based.** The
   jellystructure "No Compose for Web" rule is **scoped to the admin frontend only** and explicitly
   does **not** govern Ravilo. Ravilo is an immersive, remote/pointer-driven media surface where a
   single shared Compose UI across TV + web is worth the canvas tradeoff (no SEO need; accessibility
   handled via the focus model + platform a11y bridges).

### Backend additions live in jellystructure (Kotlin/Native, Ktor CIO)
- All Ravilo server work is **new routes and stores inside the existing jellystructure backend**,
  under the `/api/tv/**` namespace, built with the same mandates as the rest of the backend (Ktor
  CIO, kotlinx-io, SQLDelight, ktoml). Ravilo does **not** get its own server process.

### Shared Kotlin (the reuse mandate)
- **`:shared`** — pure model + client logic with **no UI**: `@Serializable` **DTOs**, enums, the
  **language-resolution value types**, and the **Ktor-client API layer** for `/api/tv/**`. Targets
  `wasmJs`, `androidTarget`, and the backend host target. **DTOs are defined once, here**, and reused
  by backend, jellystructure admin frontend, and both Ravilo clients. It may not depend on any
  platform UI (no Compose, no kotlinx.browser, no Ktor server).
- **`:ravilo-ui`** — the **Compose Multiplatform shared UI**: theme/skins, the design-system
  components, every screen, navigation, the focus engine, and the `StateFlow` stores. Targets
  `androidTarget` + `wasmJs`. Depends on `:shared`. Contains the `expect` declarations for
  `RaviloPlayer` and the image loader; the `actual`s live in the platform app modules.
- When a concept exists in jellystructure (a `MediaItem`, a language code, a track), Ravilo reuses
  the shared type. It does not redefine parallel models.

---

## Architectural Invariants

### Authentication & device pairing
Ravilo follows the same "no separate account" spirit as jellystructure, adapted for a TV with no
keyboard:
1. The TV app contacts **only jellystructure**. A device begins unauthenticated.
2. Sign-in uses a **pairing-code flow**: the TV shows a short code; the user approves it from an
   already-signed-in jellystructure web session (or types Jellyfin credentials on a paired phone) —
   no password is typed on the TV.
3. jellystructure authenticates against Jellyfin (`POST /Users/AuthenticateByName`), and — unlike the
   admin web app — **Ravilo permits non-admin Jellyfin users** (it is an end-viewer app, not the
   operator console).
4. On success jellystructure stores a **device session** (opaque token, SQLite) bound to that
   Jellyfin user, and brokers to the device: a control-plane device token + the Jellyfin **base URL**
   and a **data-plane access token** for streaming/images.
5. Every `/api/tv/**` call and the playback-reporting channel validate the device token; missing or
   revoked → 401 and the TV returns to pairing.
6. The Jellyfin password is **never** stored or sent to the TV. Data-plane tokens are scoped and
   refreshable through jellystructure; the TV never performs Jellyfin sign-in itself.

### Per-user configuration is server-owned and synced
- A viewer's Ravilo layout (hero items, channels, row order/visibility, the merged-"Newly Added"
  toggle, default skin, playback prefs) is stored **server-side in jellystructure, keyed by Jellyfin
  user** — never only on the device.
- Therefore **every device a user signs into shows the same configured experience**, and a change
  (from the jellystructure web config screen or an allowed on-device setting) **syncs to all their
  devices**. The device keeps only a cache + truly device-local prefs (e.g. the last-used skin if the
  user is allowed to override locally).
- Config edits are made on the jellystructure **web** "Ravilo" screen (operator/user) and read by the
  TV; the small set of viewer-tweakable settings the TV writes go back through `/api/tv/**`.

### The TV renders server-composed layout
- Home, channel views, and rows are **composed by jellystructure** from the user's config + the
  library, and delivered as a single descriptive feed. The TV does not invent rows or decide what
  "Continue Watching" means — it renders what the feed says.
- **Continue Watching is the merge of Continue + Next Up** (resume in-progress items and the next
  unwatched episode of started series), computed server-side. "Newly Added Movies"/"Newly Added
  Series" may be **merged into one "Newly Added" row** per the user's config.
- **The TV renders server-pushed state; it holds no derived catalog state.** (Same principle as the
  jellystructure web client.) Watched/resume state shown on a screen comes from the feed/detail
  payload, not local accumulation.

### Watched-state & playback reporting
- Episode/movie **watched, in-progress (resume position), and up-next** state is authoritative on the
  server (sourced from Jellyfin's user-data via jellystructure) and delivered with detail/feed
  payloads.
- During and after playback the TV **reports progress** (position, started, stopped, finished)
  through `/api/tv/**`; jellystructure forwards it to Jellyfin's playback-state API. Marking
  played/unplayed and advancing "next up" are server operations the TV requests — the TV never writes
  Jellyfin user-data directly.

### Remote-first interaction (and its pointer/keyboard equivalents)
- **D-pad is the primary input** on TV; the browser client maps the same model to **arrow keys +
  Enter + Esc**, with **pointer/click** and optional gamepad as equally first-class. The shared focus
  engine is one implementation; platforms only feed it input events.
- Every interactive element is focusable; there is always exactly one visible focus target with a
  clear focused treatment (scale + ring/glow). Focus order is predictable (left↔right within a row,
  up↕down between rows).
- **Back** is always meaningful (collapse overlay → leave detail → return to previous screen → Home).
  The app must never strand focus or trap the user.
- Series UX is the priority: from a series, the **resume/next episode is the default action** and is
  reachable in one or two presses; browsing all episodes and switching seasons happens in the same
  view without leaving it.

### Ravilo never mutates the library
- Ravilo is **read + playback-report only**. It never writes NFOs, never changes track flags, never
  triggers scans, never edits metadata. Those remain exclusively jellystructure operator concerns.
  The only writes Ravilo causes are **playback progress**, **played/unplayed**, and **per-user
  Ravilo settings** — all via `/api/tv/**`.

---

## Visual System

Ravilo shares jellystructure's **brand DNA** but is its own TV skin:
- **Aurora** is the default skin (the jellystructure purple→blue gradient, dark-primary). **Midnight**
  (cool teal/blue) and **Noir** (near-black, gold) are alternates selectable per user.
- **Brand mark:** a stylised **jellyfish** (original, Jellyfin-spirited — not Jellyfin's logo) +
  the "Ravilo" wordmark. The mark tints with the active skin's accent.
- **Type:** Space Grotesk (display) · Sora (UI) — matching jellystructure. Sizing follows 10-foot
  rules (large type, generous spacing, never below TV-legible minimums).
- The HTML/CSS prototype in `design/ravilo/` (`Ravilo TV.html`, `ravilo.css`) is the **visual target**
  for the Compose Multiplatform implementation — tokens/spacing/focus treatments map to Compose theme
  values shared across the Android and Web targets; the prototype is not shipped.

---

## Key Invariants (do not break)

1. **Control plane = jellystructure only; data plane = Jellyfin directly.** The TV "logs into" only
   jellystructure; video/images stream from Jellyfin via brokered tokens.
2. **DTOs are defined once in `:shared`** and reused by backend, web frontend, and TV app.
3. **Config is server-owned, per Jellyfin user, and synced across devices.**
4. **The TV renders server-composed layout & server-pushed state** — no client-invented rows, no
   derived catalog state.
5. **Ravilo never mutates the library** — only playback progress, played/unplayed, and per-user
   settings.
6. **D-pad-first**: always one clear focus target; Back is always meaningful; series resume/next is
   the default action.
7. **Compose Multiplatform is mandated for Ravilo**, targeting **Android TV + Web/WASM (canvas)** from
   one shared UI. The jellystructure "no Compose for Web" rule is scoped to the **admin frontend**
   only; the two WASM bundles (DOM admin, canvas Ravilo) coexist and never merge.
8. **Shared-first UI** — screens/theme/components/focus live in `:ravilo-ui` common code; no shared
   screen depends on an Android-only Compose artifact; platform modules add only `actual`s and entry
   points.
9. **Non-admin Jellyfin users are allowed** in Ravilo (it is a viewer app), unlike the admin web
   console.
10. **Android player = direct ExoPlayer/Media3 (fork DEFERRED).** R14 ships the Android `actual` on
    ExoPlayer/Media3 directly; the planned `jellyfin-androidtv` `playback/*` fork isolated in
    **`:ravilo-player`** (the GPL-containment boundary) is **deferred and not built** — see
    [`STATUS.md`](STATUS.md). All stream/progress paths route through `/api/tv/**`. The fork stays the
    path back if DTS/TrueHD/AC3 passthrough is needed; the GPL-containment reasoning applies if/when it
    lands.
