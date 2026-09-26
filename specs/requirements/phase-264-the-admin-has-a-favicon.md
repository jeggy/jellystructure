# Phase 264 — The admin has a favicon

> Owner, 2026-09-26: *"The jellystructure website has no favicon."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Frontend resources and build only; no route, no
config, no backend logic. **Numbering:** verified against `STATUS.md` the same day — admin taken through
**263**.

## What is wrong, measured

"The website" is the admin: the page the backend serves at its own origin, including the login screen.

- `src/wasmJsMain/resources/index.html` has no `<link rel="icon">`, no `apple-touch-icon`, and no icon
  file beside it. The resources directory holds `index.html` and nothing else.
- So every browser falls back to requesting `/favicon.ico`. `serveFrontendFile` answers an asset-shaped
  path that does not exist with a **404** (FR-235-3: never the SPA's `index.html`), and the
  StatusPages handler turns that into `{"error":"not found"}` as `application/json`. Checked on
  production the same day: `GET /favicon.ico` → `404 application/json`. Every tab, bookmark and history
  entry of the admin shows the browser's blank page glyph.
- The mark already exists and is already chosen: the **Quartet Play** tile from
  `design/app/Jellystructure Logo.html`. That is the gradient `#b15cd0 → #7b6ef0 → #00a4dc` tile with the
  white quartet-and-play glyph, and the exploration has its own *Favicon · small sizes* artboard at
  32 px and 16 px. The admin already draws it inline, as the `brand-mark` SVG in the sidebar and the
  top bar (`Shell.kt`) and on the login screen (`Login.kt`).
- The CSP already allows it: `img-src 'self' data: blob: https:`. `contentTypeFor` already maps `svg`,
  `png` and `ico`.

## Requirements

**FR-264-1 — An icon, from the mark the admin already uses.** `index.html` declares:

- `<link rel="icon" href="favicon.svg" type="image/svg+xml">`: the Quartet Play tile as a standalone
  SVG, drawn the way the design's favicon artboard draws it (the `IconTile` proportions: corner radius
  ≈ 22 % of the side, glyph inset 18 %). It is the same drawing as `Shell.kt`'s `brand-mark`, not a new
  mark.
- `<link rel="icon" href="favicon.ico" sizes="32x32">`: a raster fallback for a browser that does not
  take an SVG icon. 16 and 32 px in one `.ico`, rendered from the SVG.
- `<link rel="apple-touch-icon" href="apple-touch-icon.png">`: 180 × 180, the tile on an **opaque**
  ground (iOS fills transparency with black), for a phone that saves the admin to its home screen.

The files are rendered from the one SVG and committed; the build does not rasterise anything.

**FR-264-2 — `/favicon.ico` exists at the root.** The `.ico` is served at `/favicon.ico` itself, so a
client that asks for it without reading the page (bookmark sync, a feed reader, an old browser) gets the
icon rather than a JSON 404.

**FR-264-3 — Both frontend builds ship the files.** The icons live beside `index.html` in
`src/wasmJsMain/resources/`. The production distribution copies processed resources as it does
`index.html`. `syncDesignAssets` (the development dist that `runDev` serves) copies only an explicit
`include("index.html")` from the processed resources today, so its include list must name the icon
files too, or the dev server keeps the 404. The Docker image's `COPY … productionExecutable/
/app/frontend/` then carries them with no change.

**FR-264-4 — Caching as for every other frontend file.** No special case: `serveStaticBytes` already
gives a non-hashed name `no-cache` plus an ETag, so a changed icon is picked up on the next load and an
unchanged one costs a 304.

**FR-264-5 — An e2e check.** One Playwright assertion in the existing admin suite: the page's
`link[rel=icon]` resolves `200 image/svg+xml`, and `GET /favicon.ico` answers `200 image/x-icon`.

## Non-goals

- **The public info site.** It lives outside this repository, already has a favicon (an inline data
  URI), and draws the older plain Quartet mark without the play triangle. See open question 1.
- **Ravilo's web app.** It has a manifest, `icon-192/512` and an `apple-touch-icon`, but no
  `rel="icon"`. See open question 2.
- A PWA manifest or theme colour for the admin. It is not installable and nothing asks for that.

## Acceptance

1. Open the admin (and its login screen) in a desktop browser: the tab shows the Quartet Play tile.
   The same icon shows in a bookmark and in history.
2. `GET /favicon.ico` → `200 image/x-icon`. The backend's log shows no 404 for it.
3. On an iPhone, *Add to Home Screen* on the admin gives the tile on an opaque ground, not a screenshot
   of the page.
4. A dev run (`runDev`) serves the same icons as the Docker image.

## Open questions

1. **Should the public info site move to the Quartet Play mark too?** Its nav logo and favicon still draw
   the plain Quartet from before the play triangle was added. **Lean: yes**, but it is a change outside
   this repository.
2. **Should Ravilo's web app get a tab icon while this is open?** A browser tab showing Ravilo has none
   today. It would take one line and the existing `icon-192.png`. **Lean: yes, as a one-line R-phase.**
   It is kept out of this one because it is a different product.
