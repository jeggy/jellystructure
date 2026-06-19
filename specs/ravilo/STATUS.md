# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-19_

## Current focus

**R01–R20 all done.** App running end-to-end on real Android TV hardware; performance overhaul
applied and deployed to the stue TV (2026-06-19). First hardware-testing pass completed and all
gaps resolved:

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
  cast/episodes/related lists so focus is no longer silently lost.

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
  all load from Jellyfin.

**Screen transitions (polish):**
- `AnimatedContent` (200 ms fadeIn / 150 ms fadeOut) wraps the `when(dest)` dispatch in
  `RaviloApp` so screen pushes/pops crossfade rather than hard-cutting.

**ravilo-web build (R17 gap):**
- `ravilo-web/webpack.config.d/skiko.js` — `NormalModuleReplacementPlugin` redirects
  `'./skiko.mjs'` imports to the Skiko npm package dir (was: `Module not found` compile error);
  `devServer.static` entry ensures `skiko.wasm` is served at runtime.

### Session 2026-06-19 — performance & correctness overhaul (R20)

**Focus / D-pad freeze (R20 root cause):**
- **Focus-state latch bug fixed.** `dpadFocusable` now has an `onBlurred` callback; all focusable
  components (`Tile`, `ChannelCard`, `RaviloButton`, `EpisodeCard`, `SeasonPicker` pill,
  `BrowseGrid` chip) reset `focused = false` on blur. Previously `focused` was only ever set to
  `true`, causing every navigated-through tile to stay highlighted and recompose on each subsequent
  D-pad event — the root cause of the 3–5 press freeze.

**Store coroutine hygiene:**
- `HomeStore`, `MovieDetailStore`, `SeriesDetailStore`, `BrowseStore`, `ChannelStore` all track a
  `loadJob` and cancel it before starting a new one. Rapid back-navigation no longer produces
  parallel in-flight requests or a race that overwrites a newer result with a stale one.
- `PlayerStore.startHeartbeat` uses `while (isActive)` so the loop exits immediately on scope
  cancellation.

**Allocation hot-paths memoized:**
- All `Brush.verticalGradient`, `Color.copy(alpha=…)`, and `RoundedCornerShape(Xdp)` calls in
  composable bodies wrapped in `remember { }` with appropriate keys. Eliminates per-frame Skia
  shader rebuilds and GC pressure that were contributing to frame drops during navigation.

**Lazy list key stability:**
- `StaticContentRow` gained an optional `itemKey` parameter. All `LazyColumn`, `LazyRow`, and
  `LazyVerticalGrid` `items(…)` calls across all screens now supply a `key = { … }` lambda
  using stable model IDs, preventing unnecessary full-row recomposition when list contents change.

**Scroll fighting navigation fixed:**
- All `animateScrollToItem` calls driven by `LaunchedEffect` replaced with `scrollToItem`.
  The animated variant was visually fighting D-pad movement by scrolling the list back to a
  computed position while the user navigated away.

**Compose rule violations fixed:**
- `PlayerChrome`: removed `val fr = remember { FocusRequester() }` inside a conditional block;
  uses the hoisted `nextEpFR` parameter instead.
- `PlayerScreen`: polling loop key changed from `LaunchedEffect(isPlaying)` to
  `LaunchedEffect(Unit)` so play/pause no longer restarts the position-polling coroutine.

### Next steps (no spec yet)
- End-to-end playback verification with a real Jellyfin + jellystructure instance
- R14 player bring-up testing (ExoPlayer/Media3 on real hardware)

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
