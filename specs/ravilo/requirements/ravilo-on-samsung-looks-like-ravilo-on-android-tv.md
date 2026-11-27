# Ravilo on a Samsung TV looks like Ravilo on Android TV

Task: DEB-5852

> Owner on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful in
> our edge cases, but remember that QoE and performence is most important. We are also now getting more and more users."*
> He picked this piece on the card *"Ravilo polish: what to build"* on 2026-11-26, and said yes to bringing the Samsung
> app's look to Android TV's.

## Scope

- **Client in:** Ravilo on Samsung (`ravilo-screen`, its DOM shell in `ravilo-screen/wgt/screen.css` and
  `ravilo-screen/src/jsMain/kotlin/dev/jellystructure/ravilo/screen/`).
- **Clients out:** Android TV, phone, Ravilo Web, desktop Mac and Linux, and the Chromecast receiver. Android TV is the
  reference and does not change.
- **Look only.** The fixed feature list of
  [`ravilo-on-a-samsung-tv-is-a-full-app.md`](ravilo-on-a-samsung-tv-is-a-full-app.md) (DEB-4654) does not grow:
  Discover, Live TV, browse filters, trailers and themes stay out, as the constitution's Samsung exception says.
  Robustness is DEB-5685's (FR-55 to FR-64 of that spec).
- **The player is out.** R264's player chrome already has Android TV's sizes (DEB-5025). Nothing here touches the play
  start path, the encode warm-up (DEB-5394) or AVPlay.
- **No wire change, no new viewer text.** Every string stays as it is.
- Amends DEB-4654's spec with one dated note (below, and in that file).

## Why

The Samsung app is one viewer's TV, with 100 plays in 30 days, as many as all of Android TV's. It looks like a different
app. The shell copied Android TV's **dp numbers as CSS px**. The page is 1920×1080 CSS px (DEB-4845), and Android TV's
1080p panel is 960×540 dp at 2 px per dp. So every length on Samsung is half of Android TV's. Evidence (DEB-5687
evidence doc, Aurora theme, Android TV at 2×), re-checked at `6e50f9e84`:

| Token | Android TV | Samsung today |
|---|---|---|
| Font | Sora + Space Grotesk (`ravilo-ui/…/theme/Typography.kt:25-38`) | `system-ui` (`screen.css:2`) |
| Poster tile | 310×464 px (155×232 dp, `components/Tile.kt:64-69`) | 176×265 px (`screen.css:285, 288`) |
| Posters across 1920 px | about 5 | about 9 (arithmetic, not measured) |
| Tile / button radius | 24 / 20 px | 11 / 11 px (`screen.css:288, 370`) |
| Tile title / row heading / detail title | 36 / 32 / 68 px | 18 / 26 / 56 px (`screen.css:306, 270, 322`) |
| Focus ring | 6 px inside, glow, 200 ms (`Tile.kt:183-184, 228-242`) | 4 px outside, no glow, instant (`screen.css:307`) |
| Tile progress | 8 px, accent violet | 4 px, blue (`screen.css:310-311`) |
| Primary button | solid, accent on focus (`components/RaviloButton.kt:60-66`) | gradient always |
| Text inks | `#F3F4FB` / `#AEB4CB` / `#6B7290` | white at 100 / 72 / 45 % (`screen.css:1`) |
| App bar | 120 px, solid | 92 px, gradient (`screen.css:170`) |

Already shared, and kept: background `#0A0C13`, accent `#7B6EF0`, ring colour `#8E82FF`, the 1.10 focus scale, the
`EASE` curve `cubic-bezier(.22,.61,.36,1)`, FR-39's 248 px resting line (already 2×).

**The TV is weak.** It is a 2019 set on Tizen 5.0 (Chromium 63). DEB-4654 FR-14 holds: animation is opacity and
transform only, cold start to Home under 5 s. The constitution's smoothness rules hold too: focus is draw-only, and no
layout moves while focus moves.

## Requirements

### FR-1 — Every shell length is Android TV's dp at 2 px per dp
- **The rule.** A length in the shell (tile, gap, padding, radius, type size, bar height) is the Android TV value in dp
  or sp times two, in CSS px at the 1920×1080 page. The Aurora values are the reference (`theme/Colors.kt:58`:
  `tileRadius` 12 dp, so 24 px).
- **Tiles:** poster 310×464, landscape 512×288, square 360×360 (`Tile.kt:64-69`). The rail gap and the body gutter follow
  Android TV's row spacing at 2×. About 5 posters show across, as on Android TV.
- **Type:** tile title 36 px, row heading 32 px, a title page's title 68 px, and the rest of the shell's text at 2× its
  Android TV size.
- **Radii:** tiles and cards 24 px, buttons 20 px, chips and pills at 2× theirs.
- **Grids** keep six columns (`LocalGridColumns`, `RaviloApp.kt:235`), so their tiles grow with the page.
- **Rails** keep the room a grown tile needs (DEB-5487 FR-41). The padding is recomputed for the new tile size: the
  grown tile is never clipped; the glow may be cut at the rail's edge, as on Android TV.
- **Images** request what Android TV requests for the same variant (R96): the poster URL as today, and a landscape tile
  `w=640`. No new image width.
- **Not changed:** the player chrome, the setup and sign-in screens' layout (they already match), FR-39's resting line.

### FR-2 — Text is set in Sora and Space Grotesk, packed in the widget
- **Which face where** is Android TV's (`Typography.kt`): Space Grotesk for titles, headings and numbers where Android TV
  uses it, Sora for the rest. The fallback stack stays `system-ui, sans-serif`.
- **The files** are `design/app/fonts/sora.woff2` (25 KB) and `space-grotesk.woff2` (22 KB), the variable fonts the
  admin and the design sheets already ship. `syncScreenReceiver` copies them into `wgt/fonts/`, as it copies the flags
  (DEB-4893). No second copy is committed. They load from the widget's own files, never from the network.
- **`@font-face` uses `font-display: fallback`:** text is invisible for at most 100 ms, then shows in the system font
  until the face arrives. A face that fails to load leaves the system font. Nothing waits on it.
- **Coverage.** Every letter of the English, Danish and Faroese tables draws in the face (æ, ø, å, ð, á, í, ó, ú, ý and
  their capitals). The slice checks the two files' character maps once; if a letter is missing, it stops and raises it on
  the task before it ships.
- **Budget.**
  - The two fonts together stay at or under **64 KB**. They sit outside FR-14's 1 MB bundle budget.
  - Cold start to Home on the Tizen 5.0 emulator (`ravilo-tv-5`) grows by **no more than 100 ms** against the build
    before, median of five cold starts. FR-14's 5 s on the owner's set still holds.
  - First paint of the setup and sign-in screens stays under R264 FR-R264-7's 3 s.

### FR-3 — The focus ring is Android TV's, and it moves by opacity only
- **The ring:** 6 px, drawn **inside** the tile's edge on its corner radius, in the ring colour, with the theme's glow
  (`focusGlow`, `#7B6EF0` at 55 %, about 48 px blur).
- **The motion:** the ring and glow fade in over 200 ms on `EASE` while the tile scales to 1.10, and fade out the same
  way. The spring's small overshoot is not copied.
- **How it is drawn.** The ring and glow live on a `::after` layer of the art holder, drawn once. Only that layer's
  `opacity` animates, and the tile's `transform` as today. No `box-shadow`, `border` or size is animated.
- **Where:** home and grid tiles, See all, channel buttons, episode cards, cast faces, the synopsis target, and the
  episode-range toggles. Nav items, settings choices and buttons keep their own focus look (FR-4), with the same opacity
  rule.
- **If the glow costs frames.** A held Right across a 30-tile row on the 5.0 emulator must drop no more frames than
  today's build. If it does, the glow goes and the ring stays. That is the engineer's call on the task, not an owner
  question.

### FR-4 — Colours and controls are Android TV's
- **Text inks** are `#F3F4FB`, `#AEB4CB` and `#6B7290`.
- **A primary button** is a solid surface at rest and the accent fill when focused, with its label in `onAccent`
  (`RaviloButton.kt:60-66`). The shell draws no gradient on a button or a chip.
- **Tile progress** is 8 px in the accent violet.
- **An episode's watched mark** is green (`badgeWatched`, `EpisodeCard.kt:197`).
- **Rating badges** use Android TV's tier colours (`components/CertBadge.kt:25-31`; tier 0 is green, tier 1 blue).
- **One second accent in the shell**, `#3FB6F5`. The player's own `#00a4dc` is out of scope (FR-1).

### FR-5 — The top bar is Android TV's
- 120 px high, solid in the background colour, with Android TV's brand mark, tab sizes and spacing at 2×.
- Android TV's slim state on scroll is not copied: it animates height.

### FR-6 — Loading shapes follow the new sizes
- DEB-4889's skeleton blocks take `.home-tile`'s size and corner and the new heading and caption sizes, so nothing jumps
  when content arrives.
- The skeleton keeps its one opacity pulse. Android TV's moving shimmer is still not copied (DEB-4889, FR-14).

## Design

No design round. The target is the existing Android TV look, and no surface is new. Each slice posts screenshots side by
side, Android TV and Samsung at 1920×1080, to Rannvá on its task for an eye check: Home, a grid, a title page (movie and
series), Search, Settings, sign-in and the profile picker. A difference she flags is fixed in the same slice, or named on
the task as Chromium 63's limit (DEB-4654's *draws without it*).

## What this does not do yet

- Themes (Midnight, Noir, Daylight), the tile-size setting (`LocalTileScale`) and a theme-tinted brand mark.
- The focus-detail panel, the slim top bar, the moving shimmer.
- The player's chrome colours and the Chromecast receiver's look.
- Any feature Android TV has and Samsung lacks.

## Acceptance

- At 1920×1080 a Home poster tile measures 310×464 px with 24 px corners, and five or six posters show across.
- `document.fonts` reports Sora and Space Grotesk loaded from `fonts/`, and a tile title's computed font is Sora.
- A focused tile shows a 6 px inside ring with glow. During the fade only `opacity` and `transform` change.
- Cold start to Home on `ravilo-tv-5` is within 100 ms of the previous build, median of five.
- `scripts/check-tizen-engine.sh` and `scripts/check-tizen-assets.sh --packed` pass.
- Rannvá has seen the side-by-side pictures on each slice's task.

## Build order

One engineer. Proof runs against the test stack, as DEB-4654 FR-16 says
(`cd tests && npx playwright test e2e/<spec>`), after
`JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-screen:syncScreenReceiver --console=plain`. None is junior-ready: the
proof is Playwright and the emulator, not a JVM or linuxX64 test.

1. **FR-1, tiles, rails and type** (~180 lines, mostly CSS). `screen.css` (`.home-tile`, `.tile-art`, `.tile-*`,
   `.home-heading`, `.detail-*`, `.browse-grid`, `.home-rail` padding); `ScreenHome.kt` for the landscape `w=640`.
   Proof: a new case in `ravilo-screen-home.spec.ts` asserts the poster box, the corner radius and the count across;
   `ravilo-screen-browse.spec.ts` asserts six columns; the existing FR-39 to FR-42 cases stay green.
2. **FR-2, fonts** (~60 lines). `ravilo-screen/build.gradle.kts` (`syncScreenReceiver`), `screen.css` `@font-face` and
   families, `scripts/check-tizen-assets.sh --packed` (the two fonts are packed). Proof: a Playwright case reads
   `document.fonts`; five emulator cold starts before and after, on the task.
3. **FR-3, focus ring** (~120 lines CSS). `screen.css` ring rules for every target in FR-3. Proof: a Playwright case on
   a focused tile reads the `::after` style and records the transition properties; the held-Right frame check on the
   emulator, on the task.
4. **FR-4 and FR-5, colours, controls and the bar** (~150 lines). `screen.css` inks, `.detail-btn`, `.tile-progress`,
   `.shell-bar`, `.shell-nav`; `ScreenNav.kt` if the mark's markup moves. Proof: Playwright reads the computed colours
   of a focused and an unfocused primary button and the bar's height.
5. **FR-6, skeleton** (~50 lines). `screen.css` `.skel-*`, `ScreenSkeleton.kt`. Proof: `ravilo-screen-loading.spec.ts`
   asserts a skeleton block's size equals the real tile's.
6. **QA.** One `tizen-emulator` ticket on `ravilo-tv-5` covering FR-2's budget and FR-3's frames, with the side-by-side
   set for Rannvá.
