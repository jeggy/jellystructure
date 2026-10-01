# Phase R341 — The app icon is sized per platform, and the favicon is the bare jellyfish

> Owner, 2026-10-01, after exploring a flat icon: *"Q1: No, let's keep the gradient. Q2: We are keeping R0."* On size:
> *"There is too much empty space between the jellyfish and the spacing around it."* Then: *"On android it seems
> perfect. But on Macosx it seems too little"* and *"I would like to make the macosx even bigger, while not making the
> android bigger."* On favicons: *"Ravilo should be bare, while Jellystructure is not bare"*. In-app: *"Today — theme
> gradient."*

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/App Icons - Flat Directions.html`. **Dev-reviewed 2026-10-01** against `main` `44e26871` (see *Dev review* at the end). The owner decided the review's
open items the same day (see *Owner decisions* at the end of the Dev review); the requirements below include them.
**Numbering:** checked against `main` (tree `f88c706e`) the same day. Ravilo is taken through **R340** (R336 is reserved
by R335).

**Changes:**
- R313 FR-R313-1 and FR-R313-2: the tab icon loses its tile.
- R263 FR-R263-4: only the 180 px `apple-touch-icon` is re-rendered.
- R328 FR-R328-2: the Mac `.icns` is re-rendered.
- R333 FR-R333-4: the Flatpak's 512 px icon is re-rendered.

The admin's side is Phase 291.

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Remove the gradient? | **No.** The R62 "lit mark" stays: the gradient `#AA5CC3 → #00A4DC`, the glow, and the `#000B25` navy tile. No colour changes anywhere. The one exception is the Mac and Linux icon, which is drawn on an older purple radial ground today. It moves to the same flat `#000B25` (owner, 2026-10-01; dev review item 2). |
| D2 | One jellyfish size everywhere? | **No, the size depends on the platform** (FR-R341-1). The drawing stays the same; only its size on the tile differs. |
| D3 | A bare favicon? | **Yes, for Ravilo**: the jellyfish with no tile, in its gradient, redrawn for small sizes (FR-R341-3). The admin keeps its tile (291). |
| D4 | The logo inside the apps | **Unchanged**: the bare mark in the theme's `--accent → --accent-2` gradient. |

## Requirements

**FR-R341-1 — The jellyfish's size on the tile, per platform.** The size is measured as the bell's width (master
x 22–78) divided by the side of the visible tile, and the jellyfish is always **centred**:

| Where | Size | On a 100-unit tile |
|---|---|---|
| **macOS** (the `.icns` / Icon Composer file), **iPhone** (`apple-touch-icon-180.png`), **Linux** (the Flatpak icon) | **about 66 %** | `translate(-9 -20.21) scale(1.18)` of the 76-unit master (`viewBox="12 20 76 76"`) |
| **Everything else**: Android launcher (mipmaps, round, adaptive foreground, monochrome), the web manifest's 192/512/maskable/monochrome, the Play 512 icon, the feature graphic, the TV banner, the Samsung icon | **today's size, unchanged** (about 46 %) | unchanged |

- **Why 66 % on these three:** macOS, iOS and Linux don't crop the tile any further, and the apps beside Ravilo in a
  Dock fill about 60–70 % of their tiles.
- **Why Android stays:** Android crops every launcher icon to a circle or squircle, and the mark must stay inside its
  safe circle. Today's size already looks right there (owner).
- **"The tile" means the visible rounded square**, whatever canvas the toolchain uses. On macOS that is the area inside
  the system's squircle.

**FR-R341-2 — Re-render only what changes.** The outputs to re-render:
- `ravilo-desktop/icons/ravilo.icns`
- `ravilo-desktop/icons/ravilo.png`, the 512 px icon the Flatpak installs (Mac and Linux share it)
- `ravilo-desktop/src/desktopMain/resources/ravilo-icon.png`, the window and About icon, byte-identical to
  `icons/ravilo.png` (dev review item 4)
- `apple-touch-icon-180.png`

Each is rendered from `design/ravilo/assets/brand/ravilo-mark.svg` at FR-R341-1's placement, with R0's glow
(dev review item 8).

- **The Mac and Linux icon's ground is flat `#000B25`** (owner, 2026-10-01). Today it is a purple radial field. It
  keeps Apple's template: the 824 px tile inside the 1024 px canvas, its transparent margin and its baked shadow.
- **The Linux icon keeps the tile** (owner, 2026-10-01). The owner checks Flathub's icon guidelines by hand when they
  submit.

Nothing on Android, the Play listing or the TVs is touched. The master SVG itself is unchanged: placement is a property
of each output, not of the drawing.

**FR-R341-3 — The tab icon is the bare jellyfish, drawn for small sizes.**
- **`favicon.svg`** (R313) becomes a transparent SVG with **no tile**: a small-size drawing of the same jellyfish in
  the same gradient.
- **The drawing:** a fuller bell and three heavier tentacles. At 16 px the master's four 4.5-unit tentacles merge into
  one smudge (see the loupes in the mockup's §3).
- **`favicon.ico`** (16 + 32 px) is rendered from the same SVG.
- **The raster fallback link points at `favicon.ico`** (16 + 32, the bare jellyfish):
  `<link rel="icon" href="favicon.ico" sizes="32x32">` replaces the `icon-192.png` line in `index.html`, as
  the admin's `index.html` does. `icon-192.png` itself stays, for the manifest. Decided by the dev review (item 11),
  because a tiled fallback would put Ravilo's tab back on a tile beside the admin's.

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="14 24 72 72">
  <defs><linearGradient id="rg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#AA5CC3"/><stop offset="1" stop-color="#00A4DC"/></linearGradient></defs>
  <path d="M18 56 C18 21 82 21 82 56 C70 49 60 49 50 53 C40 49 30 49 18 56 Z" fill="url(#rg)"/>
  <g stroke="url(#rg)" stroke-width="9" stroke-linecap="round" fill="none">
    <path d="M32 56 Q27 71 33 86"/><path d="M50 58 L50 88"/><path d="M68 56 Q73 71 67 86"/>
  </g>
</svg>
```

`favicon.svg` is this small drawing at every tab size. The larger icons are separate files that keep their tiles
(dev review item 9).

**FR-R341-4 — One fixed gradient, and a known contrast risk.** The favicon uses the app icon's own gradient,
`#AA5CC3 → #00A4DC`, in light and in dark. It has no light/dark pair and no darker blue stop (owner, 2026-10-01:
*"Let's go with 'One fixed gradient' for now. and I'll come back if it doesn't really work."*).
- **The known risk:** the dev review's estimate (item 10) puts the blue end below 3 : 1 on light tabs (about 2.9 : 1
  on white, 2.2–2.5 on light strips) and the purple end below 3 : 1 on dark *selected* tabs (about 2.4–2.9 : 1).
  3 : 1 is the usual minimum for a non-text graphic.
- **Not a gate:** the build ships the fixed gradient. The owner judges it in real tabs. If it does not work, the
  remedy already worked out in item 10 is a favicon-only light/dark pair. It never changes the app icon.

**FR-R341-5 — The logo inside the apps is unchanged.** The bare mark beside the wordmark keeps the theme's
`--accent → --accent-2` gradient in all five themes (R338). This phase changes nothing in `ravilo-ui`.

## Non-goals

- Any colour change, or a flat or monochrome redesign. The owner explored this and declined it.
- The admin's icon (Phase 291).
- The Mac's music-mode icon (Phase R342). It builds on FR-R341-1's Mac size.

## Acceptance

1. **The Mac Dock:** the jellyfish fills about two-thirds of the tile, centred, with the gradient and the glow.
   Android's launcher icon is pixel-identical to before.
2. **The iPhone home screen** (Add to Home Screen): the jellyfish is the larger size. An installed Android web app is
   unchanged.
3. **A desktop browser tab**, light and dark: the jellyfish has no navy square, and its bell and three tentacles are
   distinct at 16 px. Its contrast is FR-R341-4's known risk, judged by the owner, not a pass/fail check.
4. The web app's `/favicon.ico` answers 200 and shows the same bare drawing.
5. Every theme's in-app logo looks as before.

## Open questions (for the dev review)

All five are answered: see the Dev review (items 10, 3, 7, 5 and 12) and its *Owner decisions*.

1. **Contrast:** FR-R341-4's contrast on Safari's and Firefox's light tab strips has only been estimated, not measured. Measure both. If either falls below 3 : 1, darken the favicon's blue stop.
2. **The macOS canvas:** where is the Mac's visible tile on the `.icns` canvas? The Mac icon template insets the rounded square inside the canvas. Is FR-R341-1's 66 % measured against that square in `ravilo-desktop/icons/`'s build script, or against the full canvas?
3. **apple-touch:** does iOS's own corner mask on `apple-touch-icon-180.png` crop the larger jellyfish? 264's admin version sits on an opaque ground with the tile inset for this reason. Does Ravilo's file need the same?
4. **The Flatpak icon:** GNOME draws app icons with no tile of their own. Should the Flatpak's 512 px PNG keep the navy tile at 66 %, or go bare? The lean is to keep the tile, since it's an app icon, not a favicon. Check this against Flathub's icon guidelines.
5. **The renderer:** R313 and 264 rasterised once in headless Chromium and committed the results. Should these re-renders use the same script, so the Mac, iPhone and Linux outputs have one source?

## Dev review (2026-10-01, against `main` `44e26871`)

Read against every icon file in the repository (measured, not eyeballed), `ravilo-web`'s `index.html` and service
worker, `ravilo-desktop`'s build and resources, the Flatpak manifest, the e2e suite, R263/R313/R328/R333/R342, and
the mockup's own drawing code (`rav()`, `MAC = 1.18`, `b4`). The direction holds and the placement maths is right.
But the Mac icon is not what the spec assumes it is, one output is missing from the list, and the contrast numbers
are worse than stated. Fourteen items. Items 2, 5, 10 and 11 were decided on 2026-10-01 (see *Owner decisions* at
the end). One is a look on the Mac before rendering (item 6).

1. **What exists today. Every raster is committed; nothing is generated at build time; nothing pins the bytes.**
   Bell width is measured as the widest row of the bell, divided by the visible tile:
   - **Web** (`ravilo-web/src/wasmJsMain/resources/`): `apple-touch-icon-180.png`, a full-bleed opaque `#000B25`
     square, bell 46 %. `icon-192.png` / `icon-512.png` 46 %, `icon-512-maskable.png` 57 %, `icon-96-monochrome.png`.
     `favicon.svg` (R313: the master on a `#000B25` tile, `rx 22`, mark at 72 %) and `favicon.ico` (16 + 32).
   - **Android** (`ravilo-android/src/main/res/mipmap-*`, mirrored in `design/ravilo/assets/android/`): 44–46 %; the
     adaptive background is flat `#000B25`. Play icon `design/ravilo/assets/store/ic_launcher-512.png` 44 %.
   - **Mac and Linux** (`ravilo-desktop/icons/`): `ravilo.icns` and `ravilo.png` (512). The `.icns` holds
     `ic07`–`ic14` plus `icp4`/`icp5` (16 to 1024 px), exactly the chunk set Pillow writes, so it was packed with
     Pillow. The Flatpak installs the same `ravilo.png` (`flatpak/net.jebster.Ravilo.yml:148`). Mac and Linux share
     one file.
   - **The window and About icon**: `ravilo-desktop/src/desktopMain/resources/ravilo-icon.png`, byte-identical to
     `icons/ravilo.png`, read by `Main.kt:127` and `AboutWindow.kt:43/56`. `ravilo-mark.png` (the bare mark in
     About) is the in-app logo and does not change.
   - **Not in the spec's lists, and unchanged:** `cast-receiver/icon-512.png` (the Cast console listing, 55 %) and
     `ravilo-screen/wgt/icon.png` (Tizen, 75 %; Tizen work is paused).
   - No render script exists. 264 and R313 rendered once in headless Chromium and committed. The e2e case
     (`tests/e2e/ravilo-web-headers.spec.ts:96-104`) checks the SVG `<link>` line, status and content type only. No
     `scripts/check-*` reads an icon.
2. **The Mac icon today is not the R0 the spec describes. Decided by the owner (see the end of this item).** `icons/ravilo.png` uses Apple's
   template: the tile is 412 of 512 px (824 of 1024), with a baked drop shadow and transparent margins. Its ground is
   a **radial field**, `#35326A` in the centre to `#1B1D37` at the edge, not the flat `#000B25` every other platform
   and the mockup's Dock row use. And its bell is **52 %** of the tile, not 46 %. So D1's "no colour changes
   anywhere" and the mockup's flat-navy Mac icon cannot both hold. **Lean:** render the Mac (and Linux) icon on flat
   `#000B25`, as the mockup the owner judged draws it, as R342's Icon Composer background layer says ("navy"), and
   as every other platform already is. Keep Apple's template margin and shadow.
   **Decided (owner, 2026-10-01): the lean.** FR-R341-2 and D1 now say so.
3. **Open question 2 (the Mac canvas): measure against the 824 px square.** At 1024 px the visible tile is the
   824 px rounded square at (100, 100), radius 22.5 % (185 px), so the 100-unit tile maps at `translate(100 100)
   scale(8.24)`. FR-R341-1's mark then has a bell of 544 px. Measured against the full canvas instead, it would be 82 %
   of the visible tile. Also: FR-R341-1's `translate(-9 -20.21) scale(1.18)` applies to the master's raw path
   coordinates (bell x 22–78 lands on 16.96–83.04, centred). Do not also undo the master's `viewBox` offset the way
   R313's `favicon.svg` did (`translate(-12 -20)`).
4. **FR-R341-2 misses one file.** Re-render `ravilo-desktop/src/desktopMain/resources/ravilo-icon.png` together with
   `icons/ravilo.png`, byte-identical as today. Otherwise the window icon (the alt-tab and taskbar icon on Linux, where
   the window is undecorated, `Main.kt:127-129`) and About show the old size beside a new Dock icon. The `.icns`'s 16
   and 32 px frames are downscales of the same render; the small drawing is for favicons only.
5. **Open question 4 (the Flatpak): keep the tile, and keep one PNG for Mac and Linux.** GNOME adds no shape, so a
   bare mark would read as a missing icon (the mockup's own Dock row says so for the Mac). The manifest already
   installs the Mac file; GNOME shows it with Apple's margin and shadow, as it does today. The Flatpak builds from the
   release tag, so the new icon reaches Linux with the next release and the manifest needs no edit. Flathub's own
   icon guidelines are the owner's to check when they submit, since Flathub is done by hand. An SVG icon stays R333's
   "later nicety". **Decided (owner, 2026-10-01): keep the tile;** the owner checks Flathub's guidelines by hand.
6. **Before rendering, look at the Dock on the Mac.** macOS 26 and later can put a classic `.icns` icon whose shape
   does not match the system's onto a grey rounded square, which shrinks it. If the Dock today shows a grey rim round
   Ravilo, part of "too little" is that, and only an Icon Composer `Assets.car` fixes it: R342 FR-R342-2's toolchain,
   which needs Xcode on a Mac. In that case R341's Mac half becomes R342's `ravilo-films.icon`, and the `.icns`
   stays as the icon for macOS 14–25 (`minimumSystemVersion = "14.0"`, `ravilo-desktop/build.gradle.kts:99`).
   Compose's packaging only takes `iconFile` (`:101`); an `Assets.car` plus `CFBundleIconName` needs its own
   packaging step. That is R342's work, not this phase's. One screenshot answers it.
7. **Open question 3 (apple-touch): no inset.** The file is a full-bleed opaque navy square, and iOS cuts the corners
   itself. At 66 % the mark's outer points (the bell's tips near y 41, the tentacles' ends near x 30 / y 83 on the
   100-unit tile) all lie outside iOS's corner areas, glow included. Keep it full-bleed. The same file is what Safari
   uses for a web app added to a Mac's Dock (R328's older-Mac road), and 66 % is right there too. An iPhone keeps the
   icon it took when the page was added, so acceptance 2 needs a fresh *Add to Home Screen*.
8. **"With the glow kept" adds a glow to two of the three outputs.** The shipped web icons have none: the pixels
   next to the bell are flat `#000B25`. Android's adaptive foreground has a faint one. The Mac file shows none on its
   field. Render all three with the mockup's R0 glow (`feDropShadow`, deviation 2.4 master units, `#8a6ff0` at
   0.6), scaled with the mark, so the iPhone, Mac and Linux icons match Android's lit mark.
9. **The small drawing: the spec's SVG is right, and better than the mockup's.** Its `viewBox="14 24 72 72"` holds
   the whole drawing. The mockup's `b4` crops at y 20.3–91.7 and cuts about 0.8 units off the middle tentacle's
   round end (it reaches 92.5). At 16 px the spec's drawing gives a 14 px bell, 2 px tentacles and 2 px gaps between
   them, so the three stay distinct. One sentence needs to go: "Anything larger uses the master" has no file to
   apply to. One `favicon.svg` serves every tab size, and the larger icons are separate files that keep their tiles.
   So `favicon.svg` is the small drawing, and `favicon.ico` (16 + 32) is rendered from it. No size media queries.
10. **Open question 1 (contrast): the blue end already fails on light, and the purple end fails on dark tabs.
    Decided (owner, 2026-10-01): one fixed gradient for now; the lean below is not taken.** Computed with WCAG's formula against typical tab colours. These are approximate, since each browser
    version and OS theme differs:
    - `#00A4DC` is 2.86 : 1 on a white active tab and 2.2–2.5 on light strips. The spec's "about 3 : 1" is high, so
      FR-R341-4's own remedy is triggered now: `#007BAA` reaches 3.2 or more on every light surface checked.
    - `#AA5CC3` is 2.4–2.9 on dark *selected* tabs (Chrome's `#35363A`, Firefox's `#42414D`, Safari's). FR-R341-4
      has no remedy for this.
    **Lean:** inside `favicon.svg`, a `@media (prefers-color-scheme: dark)` block lifts the purple stop to `#C27BD9`
    (3.4 or more on every dark surface) and keeps `#00A4DC`. Light keeps `#AA5CC3` with the darker `#007BAA`. The
    `.ico` takes the light pair. Both are favicon-only, never the app icon. Chrome and Firefox read that media query
    in an SVG tab icon; it follows the OS's scheme, not the browser's theme. The final numbers come from real
    screenshots of each browser's strip (Safari and Chrome on the Mac, Firefox in the Fedora desktop container), not
    from headless renders, which have no strip. The owner chose to ship the fixed gradient and come back if it does
    not work, so this lean is kept only as the ready remedy. FR-R341-4 records the risk.
11. **The `icon-192.png` fallback shows a tiled Ravilo. Decided by this review: the lean below.** A browser that takes no SVG tab icon
    (older Safari among them) uses `index.html:13`'s `icon-192.png`, which has the navy tile. Beside the admin's
    tile, that is exactly what D3 wants to avoid. **Lean:** change that line to
    `<link rel="icon" href="favicon.ico" sizes="32x32">`, as the admin's `index.html:8` does. `icon-192.png` itself
    stays, for the manifest. The e2e case is unaffected. FR-R341-3 now says so.
12. **Open question 5 (the renderer): yes, one committed script.** Lean: `scripts/render-brand-icons.sh`. It renders
    each output in headless Chromium through `tests/node_modules`' Playwright (Chromium is already in the Playwright
    cache), then packs the `.ico` and `.icns` with Pillow (the same chunk set as today). It holds the placement table
    in one place: per output, the canvas, tile rectangle, radius, ground, mark scale and glow. ImageMagick can't do
    the SVG step: it renders the gradient flat. The outputs are committed and the build still rasterises nothing
    (264/R313's rule). 291's two admin rasters and R342's PNG exports reuse it; only R342's Icon Composer step is
    Mac-only. It is a headless page render, not Skiko or a desktop window.
13. **No code change beyond `index.html`, and caching takes care of itself.** `sw.js`'s cache name is a hash of
    every file's bytes (`ravilo-web/build.gradle.kts:84-118`), so new icons replace the cached ones on the next
    update. Nothing in `ravilo-ui` changes (FR-R341-5 holds), and there is no API or wire change. The Mac's Dock
    caches icons; an updated `.app` can show the old icon until the Dock refreshes. That is a test note, not code.
14. **Build order.**
    1. The script (item 12).
    2. The favicon: `favicon.svg` (the spec's drawing, the fixed gradient), `favicon.ico`, `index.html:13` (item 11).
    3. `apple-touch-icon-180.png`.
    4. After item 6's look: the 1024 Mac render, then `ravilo.icns`, `icons/ravilo.png` and `ravilo-icon.png`
       (item 4), on flat `#000B25` with Apple's margin and shadow (item 2).
    5. Run `ravilo-web-headers.spec.ts`.

    The Flatpak and the `.dmg` pick the icon up with the next release. R342 builds on step 4.

### Owner decisions (2026-10-01)

1. **The Mac and Linux icon's ground is flat navy `#000B25`** (item 2). Apple's margin and shadow stay. Written into
   D1 and FR-R341-2.
2. **The favicon is one fixed gradient for now** (item 10). It has no light/dark pair and no darker blue. The owner:
   *"Let's go with 'One fixed gradient' for now. and I'll come back if it doesn't really work."* The measured
   contrast concern is recorded as a known risk in FR-R341-4. Item 10's pair is the remedy if it is needed.
3. **The raster fallback points at `favicon.ico`** (item 11). The link is 16/32, the bare jellyfish, and
   `icon-192.png` stays for the manifest. This one was decided by the dev review and the owner was not asked. It
   follows D3. Written into FR-R341-3.
4. **The Linux icon keeps the tile** (item 5). The owner checks Flathub's icon guidelines by hand when they submit.
