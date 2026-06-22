# Phase R39 — Channel logo fills the button (cover, not contain)

**Status:** Planned · _an uploaded channel logo should fill the entire channel-button box edge-to-edge
(cover), clipped by the box's rounded corners — not be contained/centred with padding. Consistent across
the TV app, the admin preview popup, the admin row chip, and the design mockup._

> Follow-up to **[R36](phase-R36-channel-button-editor.md)**. Same channel-button model; only the logo
> image's **fit** changes.

## Problem
A channel logo currently renders **contained** (centred, with padding / letterboxing) so it floats inside
the button with the brand fill showing around it. Desired: the logo **fills the whole button** (cover),
and the button's rounded corners clip the image (corners shaved a little). This must look identical in all
four places that draw a channel button.

## Requirements
### A. TV app
`ravilo-ui/.../components/ChannelCard.kt` — the `logoUrl != null` branch uses
`RemoteImage(url, modifier = Modifier.fillMaxSize().padding(24.dp))`. **Remove `.padding(24.dp)`** so the
image fills the card; the existing `RoundedCornerShape(18.dp)` clip shaves the corners. `RemoteImage`
already uses `ContentScale.Crop` (`seams/ImageLoader.kt`), so cover is already correct.

### B. Admin row chip
`src/wasmJsMain/.../ui/RaviloConfig.kt` `channelChipHtml()` — the `<img>`
(`max-width:78%;max-height:62%;object-fit:contain`) → **`width:100%;height:100%;object-fit:cover`**. Keep
the container's `overflow:hidden` + `border-radius` so corners clip.

### C. Admin popup preview
`src/wasmJsMain/.../ui/Workbench.kt` `.wbc-chip img` CSS
(`max-width:80%;max-height:64%;object-fit:contain`) → **`width:100%;height:100%;object-fit:cover`**.

### D. Design mockup (keep in sync)
`design/app/ravilo-builders.css` `.studio-wm .cf-wm-img`
(`max-width:84%;max-height:68%;object-fit:contain`) → **`width:100%;height:100%;object-fit:cover`**;
ensure the `.studio-wm` chip has `overflow:hidden` + rounded corners.

## Invariants
- Logo fills the button (cover) and is clipped by the button's rounded corners — identically on TV +
  admin preview + admin row + mockup.
- A **transparent** logo still shows the brand fill behind it; an **opaque** logo covers it (intended).
- Text mode and the no-logo initials fallback are unchanged.

## Out of scope
- Changing the brand-fill model, logo upload/storage (R36), or the button's size/shape.

## Design reference
`design/app/ravilo-builders.{js,css}` (`channelWM` / `.cf-wm-img`); the chip helpers in
`RaviloConfig.kt` (`channelChipHtml`) + `Workbench.kt` (`wbChannelChip`); `ChannelCard.kt`.
