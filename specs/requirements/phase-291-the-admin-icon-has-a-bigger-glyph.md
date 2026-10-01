# Phase 291 — The admin's icon has a bigger glyph

> Owner, 2026-10-01: *"Let's go with R0 and A0, but for A0, let's make it a little bigger, so the icon within is
> bigger."* And: *"Jellystructure is not bare."*

## Status

`✓ Built` 2026-10-01, not deployed (see *Build notes*). Written 2026-10-01 (design-authored) from `design/App Icons - Flat Directions.html` (A0). **Dev-reviewed 2026-10-01** against `main` `44e26871` (see *Dev review* at the end). The owner decided its one open item the same day (see *Owner decisions* at the end
of the Dev review).
**Numbering:** first written as 290; the dev side took 290 the same day (*an album's year is the year it first came
out*), so this is **291**, checked free against `main` (tree `ef52889`) on 2026-10-01.

**Changes:** Phase 264's drawing (FR-264-1 and build note 1). Ravilo's side is R341.

## Requirements

**FR-291-1 — The quartet-and-play glyph fills 59 % of the tile, up from 51 %.** On the 100-unit tile the glyph's
transform goes from `translate(18 18) scale(.64)` to **`translate(13 13) scale(.74)`**. It stays centred: the inset
goes from 18 % to 13 %.

Everything else is unchanged:
- the corner radius
- the gradient `#b15cd0 → #7b6ef0 → #00a4dc`
- the white glyph, its half-tone squares, the outlined fourth square and the play triangle

**FR-291-2 — Everywhere the tile is drawn.** The mark appears in these places, and they must stay one drawing:
- `Shell.kt`'s `brand-mark`, in the sidebar and the top bar
- `Login.kt`
- `favicon.svg`, `favicon.ico` (16/32/48) and `apple-touch-icon.png` (180, a full-bleed opaque square, as shipped;
  dev review item 4), all from 264
- the info site's nav mark and favicon (264's item on the deployment directory). The dev side updates it once the
  release carrying 291 is live in production (owner, 2026-10-01; dev review item 6).

The design mockups take the same transform: `app/app-shell.js` `BRAND()` and `app/login.html`.

**FR-291-3 — The admin keeps its tile everywhere, favicon included.** Ravilo's tab icon goes bare (R341). The admin's
square tile beside Ravilo's round jellyfish is what tells the two tabs apart, so the admin never drops its tile.

## Non-goals

- Colours, the radius, the drop shadow under the sidebar mark: none of them change.
- Ravilo's icons (R341).

## Acceptance

1. **Sidebar, top bar, login and the browser tab** all show the bigger glyph, as one drawing.
2. At 16 px the four squares stay separate and the play triangle is visible.
3. A Ravilo tab and an admin tab side by side: a bare jellyfish and a square tile.

## Open questions (for the dev review)

All three are answered: see the Dev review (items 5, 4 and 6) and its *Owner decisions*.

1. **16 px:** at 16 px the glyph's 6-unit outline on the fourth square is under 1 px. Does the outlined square still read in `favicon.ico`'s 16 px frame at the new size, or does that frame need its own heavier stroke?
2. **The apple-touch icon:** at 13 % inset, does `apple-touch-icon.png`'s tile (inset on `#0b0d14` so iOS's rounding doesn't clip it) need its own inset re-checked?
3. **The info site:** the info site lives outside this repository (264). Who re-renders its copy, and when?

## Dev review (2026-10-01, against `main` `44e26871`)

Read against the admin frontend (`Shell.kt`, `Login.kt`, `src/wasmJsMain/resources/`), the root `build.gradle.kts`,
the e2e suite, Phase 264's spec and build notes, the design files and the info site's copy in the deployment
directory. The change is small and holds as written. Eight items. The one that needed the owner (item 6, a
go-ahead) was decided on 2026-10-01 (see *Owner decisions* at the end). The three open questions are answered by
the files themselves (items 4–6).

1. **The design side is already done; the code still draws the old glyph in three inline copies.**
   `design/app/app-shell.js:52` (`BRAND()`) and `design/app/login.html:24` already carry `translate(13 13) scale(.74)`.
   The shipped admin has the old `translate(18 18) scale(.64)` in three hand-copied SVG strings: the sidebar
   (`Shell.kt:584`, gradient id `jsg-side`), the top bar (`Shell.kt:612`, `jsg-tb`) and the login screen
   (`Login.kt:18`, `jsg-login`). `syncDesignAssets` copies the design's CSS, not `app-shell.js`
   (`build.gradle.kts:289-304`), so nothing reaches Kotlin by itself. FR-291-2 says the places "must stay one
   drawing": build one Kotlin helper (`brandMarkSvg(gradientId)` beside `Shell.kt`'s shell) that returns the tile
   and glyph, and call it from all three. Then the next change is one edit.
2. **"The corner radius" is three radii today. Keep each.** The in-app marks use `rx="30"` (`Shell.kt:584/612`,
   `Login.kt:18`, and the design's `login.html`), `favicon.svg` uses `rx="22"`, and the design's `BRAND()` uses
   `rx="23"`. Each is what 264 shipped or drew. This phase changes only the glyph's transform; the radius drift
   between `BRAND()` and `Shell.kt` is old and is not this phase's to fix. `app.css`'s drop shadow on `.brand-mark`
   (`app.css:114`) and `Login.kt`'s inline `filter` stay as they are.
3. **The geometry checks out; one phrase in FR-291-1 is loose.** The glyph's content spans 10–90 in its own units.
   At `translate(13 13) scale(.74)` it lands at 20.4–79.6 on the tile: 59.2 % wide, centred (13 + 50 × .74 = 50).
   Today it spans 24.4–75.6: 51.2 %. So 51 → 59 % is right. "The inset goes from 18 % to 13 %" is the transform's
   offset, not what the eye sees: the visible margin round the glyph goes from 24.4 % to 20.4 %.
4. **Open question 2 (apple-touch): the file has no `#0b0d14` ground, and needs none.** The shipped
   `src/wasmJsMain/resources/apple-touch-icon.png` is a full-bleed, opaque gradient square: its corner pixel is the
   gradient's start colour (`#b05cd0`, alpha 255), and `#0b0d14` appears nowhere in it. So 264's build note 2 and
   FR-291-2's first wording, "(180, on its `#0b0d14` ground)", described a file that does not exist (FR-291-2 is now
   corrected). The full-bleed square is the right
   form for iOS: iOS cuts its own rounded corners and has no transparency to fill with black. Re-render it the same
   way, full-bleed. No inset is needed at the new size: the glyph's point nearest a corner sits about 22 units in from
   each edge, and iOS's corner mask reaches about 7 units in along the diagonal.
5. **Open question 1 (16 px): no special heavier stroke.** Everything in the glyph grows by the same 16 %. The
   outlined square's 6-unit stroke goes from 0.61 px to 0.71 px at 16 px. The gap between squares goes from 1.02 px
   to 1.18 px. Nothing gets thinner, so the 16 px frame reads at least as well as the one 264 shipped and the owner
   accepted. Two facts limit what the `.ico` matters for: Chrome and Firefox draw the tab from `favicon.svg`
   (`index.html:7`) at every size, so the `.ico`'s 16 px frame only reaches a client that takes no SVG icon. And at
   16 px the glyph does not land on the pixel grid (the first square runs from 3.3 to 7.4 px). So: render, and look at
   the 16 px frame enlarged. If the gap between squares smears into one grey pixel, nudge only that frame's
   `translate` by up to half a pixel. Acceptance 2's "four squares stay separate" is the same bar as today, not a
   new one.
6. **Open question 3 (the info site): copy three files, edit three lines, rebuild the site. Decided (owner,
   2026-10-01): the dev side does it after the release.** The site's `favicon.svg`, `favicon.ico` and `apple-touch-icon.png` are byte-identical copies
   of the repo's files, and each of its three pages draws one inline nav mark at the old `translate(18 18) scale(.64)`.
   Its `Dockerfile` already names the three icon files in its `COPY`, so it needs no edit. The work: copy the three
   new files over, change the three inline transforms, rebuild the site's container. That directory is outside this
   repository and the rebuild is a deploy, so the owner does it or says go. **Lean:** the session that releases 291
   does it right after that release reaches production, so the admin and the site change on the same day.
7. **Which files change, and with which tool. Nothing pins their bytes.**
   - `src/wasmJsMain/resources/favicon.svg`: one attribute, by hand.
   - `favicon.ico` (16, 32 and 48 px PNG frames, as today) and `apple-touch-icon.png` (180 px): re-rendered from the
     new SVG and committed. There is no render script in the repository; 264 rendered once in headless Chromium
     through the Playwright in `tests/node_modules`. Do the same, or use R341's script if it lands first (R341 dev
     review item 12). ImageMagick is on the host now, but it renders a gradient SVG flat, so use it for nothing here.
     Pillow (on the host) packs the `.ico` from PNG frames.
   - File names do not change, so `syncDesignAssets`' include list (`build.gradle.kts:304`), the production bundle
     and the `Dockerfile` need no edit.
   - The e2e check (`tests/e2e/auth.spec.ts:9-20`) asserts status and content type only, and no `scripts/check-*`
     reads an icon. No test changes.
   - Browsers cache icons on their own schedule even with 264's `no-cache` + ETag (FR-264-4). An iPhone's home-screen
     icon is taken when the page is added, so acceptance needs a fresh *Add to Home Screen*.
8. **Build order.** One commit: the Kotlin helper (item 1), `favicon.svg`, the two re-rendered files, run the
   e2e `auth.spec.ts` against a dev run. Then, once the release carrying 291 is live in production, the info site
   (item 6). No backend, route, config or API change. The
   exploration file `design/app/Jellystructure Logo.html` keeps its old transforms; it is history, not a target.

### Owner decisions (2026-10-01)

1. **The info site: the dev side does it after the release** (item 6). Once the release carrying 291 is live in
   production, copy the three new icon files (`favicon.svg`, `favicon.ico`, `apple-touch-icon.png`) into the site,
   change the three pages' nav-mark transforms to `translate(13 13) scale(.74)`, and rebuild the site's container.
   The owner allowed this on 2026-10-01.

## Build notes (2026-10-01)

**Built 2026-10-01, not deployed.** Everything in this repository is done. The info site is the one step left, and
it waits for the release (*Owner decisions* 1).

1. **One drawing in Kotlin (dev review 1).** `src/wasmJsMain/kotlin/dev/jellystructure/ui/BrandMark.kt` has
   `brandMarkSvg(gradientId, attrs)`. It returns the tile and the glyph at `translate(13 13) scale(.74)`. The
   sidebar (`jsg-side`), the top bar (`jsg-tb`) and the login screen (`jsg-login`) all call it. Each caller keeps
   its own attributes: the `brand-mark` class, or the login's inline size and drop shadow. The radius stays `rx="30"`
   (dev review 2).
2. **`favicon.svg`:** the one attribute, by hand. Its radius stays 22.
3. **The rasters come from R341's script:** `scripts/render-brand-icons.sh admin` renders `favicon.svg` in headless
   Chromium and Pillow packs `favicon.ico` (16, 32 and 48 px PNG frames). `apple-touch-icon.png` is 180 px, opaque
   and full-bleed, with the tile's corners squared off, as shipped (dev review 4).
4. **Deviation, the 16 px frame (dev review 5):** the frame is drawn half a pixel up and to the left
   (`translate(9.875 9.875)`, that frame only). Centred, the gaps between the four squares sit across a pixel edge
   and smear into two half-tone columns. Moved, each gap is one clean pixel column. The squares are separate either
   way, and the shift can't be seen at 16 px. The 32 px, 48 px and SVG frames are centred.
5. **Verified:** `compileKotlinWasmJs` passes. I looked at every frame enlarged: at 16 px the four squares are separate
   and the play triangle shows. The e2e `auth.spec.ts` was not run, because no dev backend was started; it checks only
   status and content type, and no file name changed.
6. **Still owed:**
   - **In a browser:** the sidebar, the top bar, the login screen and the tab should all show the bigger glyph. On an
     iPhone, *Add to Home Screen* again to see the new touch icon.
   - **The info site, after the release** (dev review 6): copy the three icon files over, change the three pages'
     nav-mark transforms to `translate(13 13) scale(.74)`, and rebuild the site's container.
