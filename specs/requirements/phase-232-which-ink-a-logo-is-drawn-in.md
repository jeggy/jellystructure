# Phase 232 — Which ink a logo is drawn in

> R257 put every captured studio/network logo on a light plate, because 88 of 135 are dark ink on
> transparency and were invisible on the dark card. That left the ~8 genuinely **light-ink** logos
> (Channel 4, Zwart Arbeid, CBeebies…) worse off than before. The client cannot know which is which
> without decoding the image; the server can, once, at rest.

## Status

`✓ Built` 2026-09-17 — spec'd and built the same day, not dev-reviewed, not deployed. Backend +
one additive wire field; client half is R259 FR-R259-5.

**Numbering:** verified against `STATUS.md` 2026-09-17 — admin taken through **231**.

## Requirements

- **FR-232-1 — one word per logo, computed once.** For every file under `artwork/studios` and
  `artwork/networks`, the alpha-weighted mean luminance of its visible pixels decides `light` (> 0.6)
  or `dark`. An image with no transparency to speak of (> 90 % opaque — a logo on its own white or
  coloured rectangle) is `dark`: it brings its own ground and sits correctly on the light plate.
  The rule is a pure function in `commonMain` (`logoInkOf(rgba, w, h)`), unit-tested.
- **FR-232-2 — ffmpeg does the decoding, off the request path.** `FfmpegRunner.rawRgbaThumb`
  renders a 32 × 32 RGBA raw frame (4 096 bytes) to a temp file; no PNG decoder is added. The result
  is persisted as a sidecar `<slug>.ink` beside the logo, so each logo costs one ffmpeg run *ever*.
  Missing sidecars are filled by a background pass at boot (`GateClass.BACKGROUND`) and after a logo
  fetch batch — **never** inside `/api/tv/facets`.
- **FR-232-3 — additive wire field.** `FacetItem` gains `logo_ink: "light" | "dark"`, present only
  with `logoUrl` and only once known. Absent means *unknown* and the client keeps R257's default
  (light plate). Old clients ignore it.
- **FR-232-4 — a replaced logo is re-judged.** The sidecar is ignored when older than its logo.

## Non-goals

Recolouring or inverting artwork. Title clearlogos (they sit on artwork, not on a card).
The admin Metadata page (it has 192's checkerboard).
