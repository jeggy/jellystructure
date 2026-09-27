# Phase R313 — Ravilo's web app has a tab icon

> Found while writing 264 (the admin's favicon), 2026-09-26, and decided the same day under the owner's
> *"You just decide for me. We want all best solutions for everything."*

## Status

`✓ Built` 2026-09-27 (see *Build notes* at the end). Written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). `ravilo-web` resources only; no Kotlin, no route, no backend change. **Numbering:**
verified against `STATUS.md` the same day — Ravilo taken through **R312**.

## What is wrong, measured

- `ravilo-web/src/wasmJsMain/resources/index.html` links a manifest (R263) and an `apple-touch-icon`,
  but has **no `<link rel="icon">`**. A desktop browser does not use manifest icons for its tab, so an
  open Ravilo tab, a bookmark and a history entry show the browser's blank page glyph.
- The web app's own container answers `GET /favicon.ico` with a **404** (checked 2026-09-26), because
  `web-static-server` serves no such file (FR-235-3's rule: an asset-shaped path that does not exist is
  a 404, never `index.html`).
- The icons already exist, rendered from the R62 lit-mark master under R263 FR-R263-4:
  `icon-192.png`, `icon-512.png`, the maskable and monochrome variants, and `apple-touch-icon-180.png`,
  all the gradient mark `#AA5CC3 → #00A4DC` on the `#000B25` navy ground. The vector master is
  `design/ravilo/assets/brand/ravilo-mark.svg`.

## Requirements

**FR-R313-1 — A tab icon from the brand master.** `index.html` declares:

- `<link rel="icon" href="favicon.svg" type="image/svg+xml">`: the lit mark from `ravilo-mark.svg` on a
  rounded `#000B25` tile. That is the same composition as `icon-192.png`, as a vector, so it stays sharp
  at every tab size and reads on a light and a dark tab strip alike.
- `<link rel="icon" href="icon-192.png" sizes="192x192">`: the existing PNG, for a browser that does not
  take an SVG icon.

The SVG is written once from the master and committed. Nothing is rasterised at build time.

**FR-R313-2 — `/favicon.ico` exists.** A 16 + 32 px `favicon.ico`, rendered from the same SVG, sits
beside `index.html`, so the web app's own origin answers `GET /favicon.ico` with the icon rather than a
404.

**FR-R313-3 — Every path the web app is served from carries them.** The files are in the web app's
resources, so they ship in the `ravilo-web` image and in the backend's `/tv/` copy of the same bundle.
The hrefs are relative, like the manifest's, so both origins resolve them. If the service worker
precaches the shell's icons (R263), the two new files join that list.

**FR-R313-4 — An e2e check.** In the existing Ravilo web suite: `link[rel=icon]` resolves
`200 image/svg+xml`, and the web app's `GET /favicon.ico` answers `200`.

## Non-goals

- The installed-app icons (manifest, maskable, monochrome, apple-touch). They are R263's and are right.
- The Chromecast receiver page. It has no tab.
- The admin's favicon, which is Phase 264.

## Acceptance

1. Open the Ravilo web app in a desktop browser: the tab shows the lit mark on navy. So do a bookmark and
   history.
2. The web app's `/favicon.ico` → `200`.
3. The installed web app (iPhone, Pixel 9) is unchanged.

## Dev review (2026-09-26, against `main` `0e5e434f`)

1. **Both origins serve it with no code.** The `ravilo-web` container's `web-static-server` serves any
   file in its bundle, and the backend's `get("/tv/{...}")` hands `/tv/*` to the same bundle
   (`Server.kt:756-759`). A relative `href="favicon.svg"` resolves under both. The browser's own
   `/favicon.ico` request goes to each origin's root: the `ravilo-web` container answers it from the
   bundle, and on the backend origin it gets 264's admin icon.
2. **The service worker picks the files up by itself.** `sw.js`'s precache manifest is generated at build
   time from every file in the dist except `sw.js` and `runtime-config.js`
   (`ravilo-web/build.gradle.kts:87-122`), so the new icons join it with no edit.
3. **The SVG** is `design/ravilo/assets/brand/ravilo-mark.svg`'s paths inside a rounded `#000B25` square
   with the same inset as `icon-192.png`. Rasterise the `.ico` once and commit it (as in 264).
4. **The e2e case** fits `tests/e2e/ravilo-web-headers.spec.ts`, which already fetches the web bundle's
   files and checks their headers.

**Net effect.** Three resource files and two `<link>` lines. Nothing else.

## Build notes (2026-09-27)

1. **`favicon.svg`**: `ravilo-mark.svg`'s paths and gradient on a `#000B25` square with `rx=22`.
   **Deviation from dev review item 3:** the mark fills about 72 % of the tile (`translate(14 14)
   scale(0.9474)` of the 76-unit master), not `icon-192.png`'s 50 %. At `icon-192`'s inset the jellyfish
   was 8 px tall in a 16 px tab and 16 px in a 32 px one (rendered and looked at). The installed icon's
   inset leaves room for the OS's own masking, which a tab does not do. The admin's favicon (264) uses
   the same tighter inset.
2. **`favicon.ico`**: 16 and 32 px PNG frames rendered from that SVG in headless Chromium, committed.
3. **`index.html`**: `favicon.svg` (SVG) and `icon-192.png` (192) as `rel=icon`, relative hrefs.
   `web-static-server` already maps `svg` and `ico`; the service worker's precache list is generated
   from the dist, so it picks both up.
4. **E2E**: `ravilo-web-headers.spec.ts` gains *the web app has a tab icon*, run against both serving
   paths (the `ravilo-web` container and the backend's `/tv/`).

**Verified 2026-09-27:** production's `ravilo-web` answers `favicon.svg` and `favicon.ico` 200 and declares both icons; the e2e case passes on both serving paths.
