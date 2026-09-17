# Phase 232 — Which ink a logo is drawn in

> R257 put every captured studio/network logo on a light plate, because 88 of 135 are dark ink on
> transparency and were invisible on the dark card. That left the ~8 genuinely **light-ink** logos
> (Channel 4, Zwart Arbeid, CBeebies…) worse off than before. The client cannot know which is which
> without decoding the image; the server can, once, at rest.

## Status

`✓ Built` 2026-09-17 — spec'd and built the same day, not dev-reviewed. **Live on production as v1.22** (`Logo ink: judged 135 logo(s)` at boot; `/api/tv/facets` carries `logo_ink`: studios 87 dark / 6 light, networks 36 / 5 — the same 11 light logos as the dry run, no temp files left behind). The title-clearlogo extension below is built but NOT in v1.22. Backend +
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

The admin Metadata page (it has 192's checkerboard). ~~Title clearlogos~~ — **wrong, see the extension below.**

## Extended 2026-09-17 (evening) — title clearlogos too

Seen on the stue TV an hour after the first build: *Gone Missing*'s clearlogo is **black ink**, and on the
detail hero — darker on the left since R257's tint — it is close to invisible. The Home hero has the same
tint and the same problem. "They sit on artwork, not on a card" was the wrong reason to exclude them:
the text column's ground is *deliberately* dark, on every title.

- **FR-232-5 — the same judgement for a title's clearlogo.** `ClearlogoInk` judges
  `assetFilePath(item, "clearlogo.png")` with the same `rawRgbaThumb` + `logoInkOf`. The file lives beside
  the media, so **no sidecar is written into the library**: results are held in memory, keyed by
  path + size, filled by a background pass at boot and on a miss (the request returns `null` = unknown
  and the next one has the answer). Never computed on the request path.
- **FR-232-5a — "dark" is far stricter for a title logo, because the client RE-INKS it.** Caught before it
  shipped by running the rule over production's 146 clearlogos: with the studio rule's 0.6 line, **53** of
  them — *Rex and Monty*, *Nosy Nick*, *The Simpsons* — would have been flattened to a white
  silhouette. `clearlogoInkOf` says `dark` only for a logo that is near-black **and** unsaturated (mean
  luminance ≤ 0.2, saturation ≤ 0.3: *Gone Missing*, *$tatus*, *La Curva*, *My 300-kg Life*), never for a
  mostly opaque image (it would become a solid box), and `null` — leave the artwork alone — for
  everything in between. 11 tests.
- **FR-232-6 — additive wire field** `logo_ink` on `Hero`, `MovieDetail` and `SeriesDetail`, present only
  once known. Client half: R259 FR-R259-6.
