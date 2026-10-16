# An SVG logo with live text is refused at upload

Task: DEB-1511

> Owner, 2026-10-16 (DEB-1511): *"The 3rd item in the collections here is not displayed correctly on some
> platforms. Here's an example from the web browser. Please get it to work."* The screenshot shows Ravilo Web's
> Collections row. The third card draws the logo's star and its yellow pill, but none of the lettering.

## What happens today

A collection's logo is a file uploaded in the admin (Ravilo → Collections → ✎ Edit → ⬆ Upload). `ChannelLogoStore`
keeps it under `<config>/channel-logos/` and serves it publicly at `/api/tv/channel-logos/<file>`. Every Ravilo app
loads it through Coil, and an SVG goes through `coil3.svg.SvgDecoder`. That decoder is not the same on every
platform:

| Client | SVG renderer | Live `<text>` |
|---|---|---|
| Android TV, phone | AndroidSVG (`RaviloAppContext.kt`) | Drawn, but in a system font. The font the file names is not on the device. |
| Ravilo Web (Wasm) | Skia `SVGDOM` via Skiko (`wasmJsMain/RaviloRootActuals.kt`) | **Not drawn.** Skia's SVG module has no font manager and draws no text. |
| macOS, Linux | Skia `SVGDOM` via Skiko (`desktopMain/RaviloRootActuals.kt`) | **Not drawn.** Same decoder. This is from reading the code; no Mac or Linux client was checked. |

The broken logo is the only stored SVG with a `<text>` element. Its two words are `<text>` runs set in a web font
that the file does not embed. Each shape is a `<path>` or a `<rect>`, so those draw everywhere, and that is exactly
what the screenshot shows. The other stored SVGs are all paths, and they render on every platform.

The admin preview hides the problem. The admin is a browser page, and it shows the logo in an `<img>`. A browser
draws SVG text in a fallback font, so the person uploading sees working lettering.

## Immediate fix (no code)

The logo's lettering was converted to outlines (`<path>`s) in the font the file names, at the weight, size,
letter-spacing and anchoring the file specified. The result renders the same on every platform. The converted file
and a before/after render are attached to DEB-1511. To put it live, upload it in the admin and pick it for that
collection. That is a prod config change, so the owner makes it.

## Requirements

**FR-1. The upload refuses an SVG that carries live text.** `POST /api/tv/admin/channel-logos` answers
`400 Bad Request` for an `.svg` upload whose bytes contain a `<text` element start. The check is case-insensitive and
also catches a namespaced `<svg:text`. The `error` names the reason and the fix: the lettering is live text, Ravilo's
web and desktop apps cannot draw it, so outline the text (convert to paths) or upload a PNG. Nothing is stored. The
admin already shows the server's message under "Upload failed:", so the admin needs no new UI for this. PNG, JPG and
WebP uploads are not affected.

**FR-2. A stored SVG that carries live text is marked in the picker.** `GET /api/tv/admin/channel-logos` gives each
entry a new `liveText: Boolean` field, `false` by default. It is `true` for a stored `.svg` that FR-1 would have
refused. The admin's "Uploaded logos — click to use" library marks such a tile with a short caution:
*"text won't show on web or desktop"*. The tile stays pickable. This is additive on `ChannelLogo`, so no installed
client breaks (the TV never reads the admin list).

## Non-goals

- **No client change.** Giving Skia a font manager, or bundling fonts into the clients, would at best draw a
  substitute font. A logo's lettering is artwork, and outlines are the only form that looks the same everywhere.
- **No server-side outlining or rasterising.** Either one needs the right font on the server for an arbitrary
  upload, and an outline made in the wrong font is a wrong logo.
- **No sweep of other SVG features.** Skia's `SVGDOM` also ignores CSS in a `<style>` block. One stored logo uses a
  class rule, but that rule sets the default fill (black), so the logo is unaffected. If a logo ever breaks that way,
  it gets its own task.

## Verification

- FR-1: a backend test posts a small SVG with a `<text>` element and expects a 400 with the reason, and nothing
  stored. A path-only SVG and a PNG still store.
- FR-2: a backend test lists a store that holds one SVG with text and one without, and expects `liveText` set on
  the first only.
- Immediate fix: once the converted logo is live, Ravilo Web's Collections row shows the third card's lettering. A
  QA seat with Ravilo Web checks this. The Android TV card is checked too, now in the intended font.
