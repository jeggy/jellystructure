# Focus rings and loaded content fade in, long grid titles scroll once on a TV

Task: DEB-2204

> Owner, 2026-10-23 (DEB-2204), on Wholphin's TV app: *"I feel like the Wholphin TV app is a bit more respnsive and
> nice small animations."* On the focus-polish option: *"Yes. With some research as well. We want to be sure that its
> gonna feel perfectly nice on TV, but also not breaking mobile or web etc"*. He answered the three questions on card
> `caaf68f0` (2026-10-23 22:32Z). The detail buttons keep their labels and only the ring animates. Long titles scroll
> once, on a TV, in grids only. The web app shows focus only after a key press, like the desktop app.

## What Ravilo does today

The research note on DEB-2204 (`focus-polish`) has the full reading. These are the facts this spec rests on.

- **Tiles, channel cards, cast circles, episode cards and season pills already animate their focus** on
  `RaviloMotion.focusSpring`/`softSpring`, drawn in the draw phase (`theme/Motion.kt`: scale and shadow in a
  `graphicsLayer`, ring in `drawWithCache`).
- **About 60 other focus rings appear and disappear in a single frame.** They are a
  `.then(if (focused) Modifier.border(…))`, a ring in `drawBehind` behind an `if`, or a border width or colour picked
  by a plain `if`. The main groups are settings, sign-in, the server address, profile picker, account screens, filter
  and genre chips, facet bars, the app-bar search and avatar, `DetailSynopsis`, the trailer's Close, the speakers
  panel, the live-TV guide, and `RaviloButton`'s outer ring, primary fill and ghost border. The film player's
  transport buttons, next-up button, skip-intro pill and episode-rail cards also **scale and glow in one frame**
  (`.scale(if (focused) 1.06f else 1f)` and a plain `.shadow`). Every one of them is in `ravilo-ui` `commonMain`.
- **The mockup already says these should ease.** `design/ravilo/ravilo.css` gives `.tile` a `.2s` border transition
  and `.btn` a `.2s` box-shadow transition, both on `--ease: cubic-bezier(.22,.61,.36,1)`. The Kotlin never followed it.
- **Every loading screen swaps to its content in one frame.** No screen uses `Crossfade` or `AnimatedContent`.
- **Long tile titles end in an ellipsis** (`Tile.kt`, `maxLines = 1`, `TextOverflow.Ellipsis`). On Home, the focused
  tile's caption is hidden once R240's row opens, 170 ms after focus lands, and the open row's panel states the full
  title.
- **The system reduce-motion setting is read in one place only.** `systemPrefersReducedMotion()` exists for Android,
  web and macOS, and only R240's row-open reads it. Compose on Android scales tweens and springs by the system
  animator scale on its own. Compose for web and desktop reads no reduce-motion setting, and `basicMarquee` ignores
  the setting on every platform (`FixedMotionDurationScale`).
- **On the web app, a window 600 dp or wider draws every focus visual all the time.** That includes a viewer using
  only the mouse, and a click leaves a ring behind. FR-R298-1 sets `LocalFocusVisible` to `!handset` everywhere but
  the desktop app. The desktop app has followed `KeyboardMode` since R350 (FR-R350-17): visuals show after a
  navigation key and go when the pointer moves or presses.

**Which clients this reaches.** `ravilo-ui` is compiled for the Android app (TV and phone in one APK), the web app
(wasm) and the desktop app (jvm). The Tizen screen app (`ravilo-screen`) and the Chromecast receiver
(`cast-receiver`, `ravilo-cast`) are hand-built Kotlin/JS DOM with no browsing UI. This spec does not reach them, and
FR-8 says so as the constitution requires.

## Prior art

- **Wholphin** (GPL-2.0, ideas only). Every focus move gets an immediate, cheap reaction. Its PR #1138 found that every
  card on Home recomposed on every D-pad move, and named that as the main cost on low-end TVs. FR-3 exists because of it.
- **`androidx.tv` tv-material.** It animates only scale (300 ms in, 500 ms out, `CubicBezierEasing(0, 0, 0.2, 1)`).
  Its border snaps (`SurfaceBorder.kt`), and it reads the animated scale during composition. Ravilo's draw-phase rule
  is already the cheaper one.
- **Jellyfin Android TV.** It keeps every action label visible. It runs an endless marquee on focused card titles with
  no delay.
- **tvOS.** `enablesMarqueeWhenAncestorFocused` scrolls a label only while its ancestor has focus.
- **WCAG 2.2.** Under 2.2.2, moving content that starts by itself and lasts more than 5 s needs a way to stop it.
  2.3.3 counts a change in size as motion and a change in colour or opacity as not.
- **`:focus-visible`.** A pointer user knows where focus is, so it is not drawn for them. FR-7 gives the web app that
  behaviour.

## Requirements

**FR-1 — One focus ring, and it eases.** `ravilo-ui` gets one shared focus-ring modifier. Every focus ring, focus
border, and focused fill or border colour listed above moves onto it.
- **The stroke is permanent and fixed in width.** Focus animates its alpha and colour, never its width, and never by
  adding or removing a modifier. R338 learned that a ring modifier which comes and goes breaks focus.
- **Where the focused look is wider than the resting one**, as with `RaviloButton`'s ghost border going from 1 dp to
  2 dp, the focused stroke is drawn over the resting one and only its alpha animates.
- **Timing:** a 200 ms tween on the mockup's `--ease`, `CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)`, in both
  directions. It goes into `RaviloMotion` as one named duration and one named easing.
- **The animated value is read only in the draw phase.** The modifier follows `Motion.kt`'s existing rule.
- **The modifier reads focus through `rememberFocusVisual()`** (FR-R298-2), so a phone draws no ring at all. That
  includes the server-address ring in `ServerSetupScreen`, which holds focus in a plain `mutableStateOf` and today
  draws on a phone too.
- **Unchanged:** ring colours, widths, offsets and shapes. The components that already animate their focus keep their
  springs.

**FR-2 — Instant scale and glow move to the button spring.** The film player's transport buttons, next-up button,
skip-intro pill and episode-rail cards scale and glow on `RaviloMotion.softSpring`, drawn in a `graphicsLayer` the way
`RaviloButton` does it. Their target scales stay as they are. Their rings follow FR-1. `RaviloButton`'s scale, lift
and glow springs are unchanged, and its ring and fill swap follow FR-1.

**FR-3 — A focus move redraws only what changed focus.**
- Moving focus from one tile to the next recomposes the tile that lost focus and the tile that gained it. It
  recomposes no other tile in the row and nothing in any other row.
- While an FR-1 fade, an FR-2 spring or an FR-6 scroll is running, the component recomposes no more than once at the
  start and once at the end. Every frame between is draw-only.
- This holds for `Tile`, `RaviloButton` and one migrated chip.

**FR-4 — Reduced motion turns this spec's motion off on every platform.**
- **The seam:** a `LocalReducedMotion` composition local is provided once at the app root from
  `systemPrefersReducedMotion()`. It is read once per root composition, as R240 reads it.
- **When it is true:**
  - FR-1's ring and FR-2's scale and glow jump straight to their end values.
  - FR-5 shows content without a fade.
  - FR-6 never starts.
- **Android:** the system animator scale already shortens Compose animations there, and this seam is the same switch
  read directly.
- **Out of scope:** animations this spec doesn't add (screen transitions, the hero, row-open) are unchanged. Row-open
  keeps its own downgrade (R240).

**FR-5 — Loaded content fades in.**
- **When:** a screen's content region replaces its loading view, which may be a shimmer skeleton, a spinner, a
  loading line or nothing. This covers both content and an error state.
- **How:** the new region fades in from alpha 0 to 1 over 220 ms (`RaviloMotion.IMAGE_CROSSFADE_MS`) on FR-1's easing.
  The loading view is removed in the same frame, so it is a fade-in and not a crossfade, and two trees never exist at
  once.
- **Draw-only:** the alpha is applied in a `graphicsLayer`. Layout, focus, focus requesters and restorers behave
  exactly as they do today.
- **Screens:**
  - Home, film detail, series detail
  - browse, seeded browse, Discover's tabs, Discover detail, upcoming detail
  - channel, live-TV guide, search results
  - music album, artist, Listen and browse
  - audiobooks
- **No fade when a screen first composes already loaded**, from R212's snapshot or from `DetailStore`'s cache. A
  returning viewer sees exactly what they see today.
- **Unchanged:** Coil's per-image 220 ms crossfade, and its skip on a memory-cache hit (R87, R214).

**FR-6 — A long title in a TV grid scrolls once.** This applies to the `Tile` in the browse grid (`BrowseScreen`),
the seeded browse grid (`SeededBrowseScreen`) and the search results grid (`SearchScreen`), on a TV (`isTvPlatform`)
only. It applies only when the title does not fit the tile's width.
- **Start:** after focus has rested on the tile for 1000 ms, the full title replaces the ellipsis, clipped to the
  tile's width. It moves left until its last character is visible.
  - Speed is at least 40 dp/s, and fast enough that this part takes no more than 3.5 s.
  - It then holds for 1 s, returns to the start over 300 ms on FR-1's easing, and stops. The ellipsis returns.
  - A full run takes under 5 s.
- **Once per arrival.** It runs once each time focus arrives on the tile. Resting longer does not repeat it.
- **Leaving.** When focus leaves, at any point, the title returns to its ellipsis in the same frame, and a pending
  start is cancelled.
- **Draw-only.** The movement is a draw-phase translation (FR-3). It is not `basicMarquee`, which ignores reduced
  motion (FR-4).
- **Not here:** Home's rows, the rows on detail pages, every other `Tile`, the desktop app, the web app and the phone.
  Their titles end in an ellipsis as today.

**FR-7 — The web app draws focus only after a key press, as the desktop app does.** This is a dated amendment to
FR-R298-1, recorded in R298's file.
- **The wide web window:** on the web app, when the window is not a handset (R256's `isHandset`),
  `LocalFocusVisible` is `KeyboardMode.on`. `KeyboardMode` is wired to the root with `Modifier.keyboardMode` exactly
  as on the desktop app.
  - Tab, the arrow keys, Enter, NumPadEnter or Esc turn the focus visuals on.
  - A pointer press, or a pointer move of more than a pixel, turns them off.
- **Unchanged:** a phone-sized web window (`false`), every TV (`true`), the phone (`false`) and the desktop app.
- **The film player's transport row** keeps its own hover-driven highlight exactly as it is, on every platform. FR-1
  and FR-2 only change its timing.

**FR-8 — Not reached.** The Tizen screen app and the Chromecast receiver do not compile `ravilo-ui`. They have no focus
rings, no browsing UI and no grid, and nothing in them changes. FR-R264-7's "no animations that are not opacity"
stands for the screen app.

## Invariants

- **Nothing moves on a phone because of focus.** `LocalFocusVisible` is false there (R298). On a phone this spec adds
  only FR-5's fade-in.
- **No layout changes because of focus.** No focus stop is added or removed. Rings keep their offsets, so no row,
  button or chip changes size. FR-R295-4's fixed button width and R306's gutter bleed still hold.
- **Every button keeps its label** (owner, 2026-10-23). The hero still draws no ring (R374).
- R367's list still reaches one resting position per key. R223's hero-to-top guarantee still rests on an instant
  `scrollToItem(0)`.
- No new strings, no wire change and no new dependency.

## Out of scope

- **Animating the hero → top scroll.** R57 made it instant because an animated scroll collided with the first Down
  press. FR-R223-2 now relies on it being instant.
- **Icon-only detail buttons with the label shown on focus**, and an inverted fill on focus. The owner chose to keep
  the labels and animate only the ring.
- **A palette-tinted background or a slower focus backdrop** (option C on DEB-2204). It needs its own design round.
- **R240's row-open, held-key throttling, and the reduce-motion behaviour of existing animations.**
- **Regenerating the baseline profile.** `Tile` and `RaviloButton` change here, which is the trigger in FR-RV-R213-3.
  But the generator still needs a TV on API 33 or later (DEB-2204's plan, *Stopped after step 1*). Today's profile
  doesn't cover these composables, so this change doesn't make it worse.

## Verification

**Automated (`:ravilo-ui:desktopTest`):**
- **FR-1 and FR-2:** every migrated site draws its ring only when `LocalFocusVisible` is true. The ring's alpha passes
  through intermediate values over 200 ms after a focus change, under a test clock.
- **FR-3:** a row of tiles, a row of `RaviloButton`s and a chip row each count compositions per item across a focus
  move and across the frames of the fade.
- **FR-4:** with `LocalReducedMotion` true, rings reach their end value in the first frame, FR-5 shows content at
  alpha 1, and FR-6 never offsets.
- **FR-5:** the loading view and the content are never composed in the same frame. A screen composed already loaded
  starts at alpha 1.
- **FR-6:** no start before 1000 ms, a title that fits never moves, the run ends within 5 s at its start position, and
  a focus change mid-run resets it in the same frame.
- **FR-7:** a `KeyboardMode` test on the web wiring. Tab turns it on, a pointer move turns it off, and a phone-sized
  window stays false.

**Compile:** `:ravilo-ui` for Android, wasmJs and desktop.

**On a device.** Why a device is needed: whether a D-pad move *feels* immediate, and what it costs in frames on a
low-end TV, cannot be measured on the JVM. One QA leg covers it.
- **TV frames (`TV` type, a BRAVIA on API 31, never the Streamer).** Run the `.debug` app built before and after on the
  same fixed `input keyevent` journey: Down ×4 at 400 ms, Right ×10 at 250 ms, Left ×10, Up ×4, then 20 × Right at
  80 ms. Read it with `dumpsys gfxinfo`, three passes each. **The pass bar:** janky frames no more than 0.5 points
  above the before build.
- **TV by eye:** rings and button glows ease in and out, content fades in after a cold start, a long title in Browse
  scrolls once and stops, and nothing on Home scrolls.
- **Pixel:** no ring anywhere, and the detail page fades in.
- **Desktop and web:** desktop rings only after a key. On the web, at a wide width, no ring after a click and a ring
  after Tab or an arrow. At a phone width, no ring.
