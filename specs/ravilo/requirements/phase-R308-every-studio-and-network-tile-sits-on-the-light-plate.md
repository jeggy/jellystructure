# Phase R308 — Every studio and network tile sits on the light plate

> Owner, 2026-09-26: *"Let's update the background colors in the Networks and Studios tabs within
> Discover page in Ravilo. So that it always uses the white color of everything. Currently it only uses
> it for most things, but we have some examples where it doesn't use a white color and when there is no
> logo image available it just displays the text. But also without a logo, let's make it so, it always
> uses this white background."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client (`ravilo-ui`, every platform: TV, phone, web) plus one backend judgement that
extends Phase 232. **Every open question decided the same day**: the owner handed the calls over (*"You
just decide for me. We want all best solutions for everything"*). See *Decisions* at the end.
**Numbering:** verified against `STATUS.md` the same day — Ravilo taken through **R307**.

Supersedes, for the Studios and Networks walls only: **R243 FR-R243-2**'s "a wordmark tile sets the name
on the dark card", **R257 FR-R257-3**'s "a wordmark tile keeps the dark card", and **R259 FR-R259-5**'s
"a logo the server judged light keeps the dark card".

## What is wrong

A tile's card is one of two colours today, and which one depends on the tile
(`TaxonomyScreen.kt`, the `.background(...)` in `TaxonomyTile`):

| Tile | Card | Rule |
|---|---|---|
| a logo, judged dark ink or not judged | light plate `#E8EAF0` | R257 FR-R257-3 |
| a logo, judged **light** ink (Phase 232) | dark card | R259 FR-R259-5 |
| **no logo** | dark card, the name set in light ink | R243 FR-R243-2 |

Measured on production's `/api/tv/facets` for the owner's viewer, 2026-09-26:

| Wall | Tiles | Light plate | Dark card: light-ink logo | Dark card: no logo |
|---|---|---|---|---|
| Networks | 66 | 57 | 8 | 1 |
| Studios | 573 | 146 | 8 | **419** |

On Networks the eight dark cards break an otherwise light wall. On Studios the wall alternates between
the two all the way down, because three studios in four have no logo file (TMDB has none for them).

## What moving everything onto the plate costs

The 16 logos judged light were rendered on the plate as they are, and again re-inked in dark ink
(scratch renders, 2026-09-26):

- **12 read correctly on the plate exactly as they are.** Most are *coloured*, not white: 232's rule
  says `light` for any alpha-weighted mean luminance above 0.6, so saturated yellow, lime, green and
  bright blue all count (Channel 5, BBC Three, CBeebies, Hulu, KiKa, TV 2 Fri). The rest are white or
  pale shapes that bring their own outline or body (TV3's sphere, a ghost, a sticker-shaped emblem, a 3D
  block wordmark on a grey ground).
- **3 disappear on the plate:** three studio logos drawn as white strokes on transparency — two white
  wordmarks, and one white wordmark beside a coloured emblem (the emblem and a small tagline stay
  visible; the name does not). Re-inked in dark ink, all three read cleanly.
- **Re-inking is not safe in general.** Tinting keeps each pixel's alpha and replaces its colour, so a
  logo with an opaque body turns into a solid silhouette: the ghost became a dark ghost shape with its
  lettering gone, the sticker emblem became a blob, the 3D wordmark a block, TV3 a black disc. This is
  why FR-232-5a already never tints a mostly opaque title logo.
- **Channel 4** (pale green strokes on transparency) reads on the plate as it is, but weakly; re-inked it
  reads cleanly. Decided: re-ink it (decision 1).

So the plate needs a verdict narrower than 232's `light`: *this logo is light, unsaturated ink on
transparency, and only re-inking it can make it visible*. The client cannot make that judgement (it
would need the pixels, and it must render what it is given), so the server makes it once, the way 232
does.

## Requirements

**FR-R308-1 — One ground on Studios and Networks.** Every tile on the Studios and Networks walls draws
its card in the light plate colour R257 introduced (`#E8EAF0`, `TAXO_LOGO_PLATE`), whether or not it has
a logo and whatever ink its logo is. The same on every skin (Aurora, Midnight, Noir) and every platform
(TV, phone, web): there is one composable. Size, corner radius, padding, focus growth and the focus ring
are unchanged. The ring is already drawn against the plate on 203 of today's tiles, so it needs no new
treatment.

**FR-R308-2 — A tile without a logo sets its name in dark ink on the plate.** Same wordmark type, sizes,
line limits and ellipsis as today, and the caption rule is unchanged: the count only, never the name
again, never a "no logo" badge (FR-R243-2 still holds for everything except the card colour). The ink is
**one fixed dark colour**, not the skin's `colors.text` (light on every skin, so it would vanish on the
plate). Use the same dark ink as FR-R308-3's re-ink, so a wordmark tile and a re-inked logo tile read as
one family.

**FR-R308-3 — A logo is drawn as it is, unless the server says re-ink it.** A logo tile draws the logo
untouched on the plate. When the facet item carries `logo_reink: true` (FR-R308-5), the client draws the
logo with a source-in tint in the FR-R308-2 dark ink: every visible pixel becomes the ink at its own
alpha. This is the same mechanism R259 FR-R259-6 uses for title clearlogos (`TitleLogo.kt`'s
`ColorFilter.tint`). The client never decides this itself.

**FR-R308-4 — The server judges which logos to re-ink (backend, extends Phase 232).** For every file
under `artwork/studios` and `artwork/networks`, one extra verdict, computed with 232's machinery: off the
request path, one ffmpeg run per logo **ever**, persisted beside the logo, re-judged when the logo is
replaced (FR-232-4). Missing verdicts are filled by the same boot-time background pass
(`GateClass.BACKGROUND`), never inside `/api/tv/facets`. The rule is a pure function in `commonMain`
beside `logoInkOf` and `clearlogoInkOf`, unit-tested.

The rule is about **legibility on this plate**, not about the ink's colour:

- A visible pixel (alpha > 16) is **lost on the plate** when its WCAG contrast against `#E8EAF0` is below
  **1.6 : 1** *and* its saturation (HSV, `(max − min) / max`) is below **0.5**. It has neither the
  lightness difference nor the hue to show. Saturated light colours are not lost: Channel 5's yellow,
  BBC Three's lime and Hulu's green read by hue.
- `lost` = the alpha-weighted share of lost pixels. `opaque` = the share of all pixels with
  alpha > 240.
- **Re-ink when** `lost ≥ 0.95` and `opaque ≤ 0.90`: essentially all of the ink vanishes, and the logo is
  not a solid block. A block would become a dark rectangle; 232 already treats it as bringing its own
  ground.
- **or when** `lost ≥ 0.60` and `opaque ≤ 0.30`: most of the ink vanishes, and it is thin ink on
  transparency. This clause is what separates a white wordmark beside a coloured emblem, which is
  re-inked, from a white shape with its own outline or body (a ghost, a sticker emblem, a 3D block
  wordmark, a sphere), which would turn into a silhouette and is left alone.

**Run over all 221 production logos on 2026-09-26 (scratch script), this re-inks exactly four:**
Channel 4 (`lost` 1.00, `opaque` 0.30) and the three white studio logos (1.00/0.48, 0.99/0.13,
0.66/0.21). No dark-ink logo is caught, and neither is any of the other 12 light-ink ones. The nearest
misses are the white shapes that must not be tinted, at `lost` 0.61–0.71 with `opaque` 0.48–0.54.
FR-R308-7's test pins those numbers.

⚠ **Measure on the thumbnail the rule was measured on.** Those numbers come from an aspect-keeping,
alpha-correct resize to 128 px on the long side. `FfmpegRunner.rawRgbaThumb` squashes the logo to
32 × 32 (aspect not kept) with `flags=area`, which averages each channel without weighting by alpha, so
the colour of the transparent pixels bleeds into the strokes. A pure-white wordmark measures a mean
luminance of **0.92** at full size, **0.89** at 128 px, **0.84** at 64 px, but only **0.74** at 32 px.
232's `light` line (0.6) survives that. This rule does not. Give this verdict its own ffmpeg render:
`scale=128:-1` (or `-1:128` for a tall logo) on premultiplied alpha, e.g. `premultiply=inplace=1` before
the scale and `unpremultiply=inplace=1` after. It is still one ffmpeg run, and still no PNG decoder in
the backend.

**FR-R308-5 — One additive wire field; `logo_ink` is left exactly as it is.** `FacetItem` gains
`logo_reink: true`, present only on a logo the server has judged to re-ink and absent otherwise (absent =
draw as is, which is also what an unjudged logo gets). `logo_ink` keeps its current values and meaning,
because installed apps read it: an app from before this phase keeps drawing what it draws today (light
ink → dark card). The new client no longer reads `logo_ink` on these tiles, but the field stays in the
payload and in the shared DTO: an installed app still deserializes it, and removing a field "nothing
reads" is how v1.31 broke an installed TV.

**FR-R308-6 — Genres are not part of this.** The Genres wall has no logos and keeps its dark wordmark
cards. It is a different wall (3-up on the phone, shorter cards) and the owner named Networks and
Studios (decision 3).

**FR-R308-7 — Tests.** The verdict on synthetic rasters: white strokes on transparency → re-ink; a
white opaque shape with a dark outline → not; saturated yellow strokes → not; pale unsaturated green
strokes → re-ink; black strokes → not; an opaque white rectangle → not; nothing visible → not. Plus the
four production re-inks and the three nearest misses above, as fixtures, at the rule's own thumbnail
size.

## Non-goals

- The plate colour itself. The owner calls today's plate "the white colour", and it stays `#E8EAF0`
  (decision 2).
- Title clearlogos (232 FR-232-5, R259 FR-R259-6), the admin Metadata page (192's checkerboard), and
  choosing or uploading a different logo for a studio or network. There is no such control today.
- `design/ravilo/ravilo.css`'s `.taxo-card` rule, which still gives a wordmark tile the dark card. That
  is the design project's to follow. The shipped app is what this phase changes.

## Acceptance

1. On the TV, Discover → Networks: every tile's card is the light plate. Channel 5, BBC Three, CBeebies,
   Hulu, KiKa, TV 2 Fri and TV3 are drawn in their own colours on it, and Channel 4 in the dark ink. The
   one network without a logo shows its name in dark ink on the plate, with its count beneath.
2. Discover → Studios, scrolled top to bottom: no dark card anywhere; every no-logo tile has its name in
   dark ink; the three white logos are drawn in dark ink and readable; no logo has turned into a solid
   silhouette.
3. The same two walls on the phone and in the web app. Focus (TV) and press (phone) look as they do today.
4. `/api/tv/facets` on production: `logo_reink: true` on exactly four logos (Channel 4 and the three
   studio logos above); every `logo_ink` value is unchanged from before the phase.
5. An app installed before this phase, against the new backend, draws both walls exactly as it does
   today.
6. FR-R308-7's tests pass.

## Decisions (2026-09-26, delegated by the owner)

1. **Channel 4 is re-inked.** Pale green strokes on transparency are visible on the plate, but only
   just: every visible pixel is below 1.6 : 1 against the plate, with not enough hue to make up for it,
   on a screen read from across a room. The rule re-inks anything the plate cannot show, and the logo's
   shape carries the brand. Colour is kept everywhere it reads (Channel 5, BBC Three, CBeebies, Hulu,
   KiKa, TV 2 Fri, TV3).
2. **The plate stays `#E8EAF0`.** It is the colour the owner pointed at as "the white colour" to use
   everywhere, and an off-white glares less than pure white on a dark TV page. With every tile on a
   plate, the whole wall is plates, which makes that matter more, not less.
3. **Genres keep their dark cards.** The plate exists because captured logos are drawn for a light page.
   Genres have no logos, so a light plate would only put dark text on light for no reason. It also keeps
   the two kinds of wall visibly distinct: brands on plates, categories as words.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The rule is confirmed on the server's own tool. Five items.

1. **Re-run with ffmpeg, the way the server will run it: the same four.** `format=rgba,premultiply=
   inplace=1,scale=w=128:h=128:force_original_aspect_ratio=decrease:flags=area,unpremultiply=inplace=1`
   over all 221 production logos re-inks Channel 4 (`lost` 0.99, `opaque` 0.30) and the three white studio
   logos (1.00/0.48, 0.99/0.13, 0.67/0.20). The nearest misses are the silhouettes (0.61–0.72 lost, 0.48–
   0.54 opaque) and the solid boxes (opaque 0.76–1.00). Production's ffmpeg (5.1.9 in the container) has
   both filters.
2. **The raw frame needs fixed dimensions.** `rawRgbaThumb` (`FfmpegRunner.kt:297`) relies on a fixed
   `32×32`. With the aspect kept, the output size varies, so compute it in Kotlin from the source size and
   pass it explicitly (`scale=W:H`, `W = 128`, `H = max(1, round(h × 128 / w))`, the other way round for a
   tall logo). The byte count is then known. Do not pad to a square: padding changes the `opaque`
   fraction the thresholds were measured on.
3. **Where it runs.** `LogoDownloader`'s background ink pass (`LogoDownloader.kt:57-81`) gains the
   second verdict in the same loop: one more ffmpeg run per logo, ever, and a `<slug>.reink` sidecar
   (`1`/`0`), invalidated with the `.ink` one when a logo is replaced (FR-232-4). The pure function
   `logoReinkOf(rgba, w, h)` sits beside `logoInkOf` in `commonMain/.../media/LogoInk.kt`.
4. **The wire and the tile.** `FacetItem` (`Models.kt:1548`) gains
   `@SerialName("logo_reink") val logoReink: Boolean? = null`, set only when true, passed through
   `FacetsAcc.toFacets` (`BrowseService.kt:293-299`) next to `logoInk`. In `TaxonomyTile`
   (`TaxonomyScreen.kt`), for studios and networks:
   - the `.background(...)` rule becomes the plate unconditionally;
   - the wordmark's `color = colors.text` becomes one fixed ink constant (for example `0xFF1B1E2B`)
     beside `TAXO_LOGO_PLATE`;
   - the logo is drawn with `ColorFilter.tint(ink)` when `logoReink == true`. `RemoteImage`
     (`seams/ImageLoader.kt:26`) takes no `colorFilter` today, so it gains an optional
     `colorFilter: ColorFilter? = null` passed to its `AsyncImage`, the same filter `TitleLogo.kt:87`
     applies to an `Image`. Every existing caller is unchanged.

   Genres keep `colors.surfaceVariant`. The name and count captions under the card sit on the page, not
   the plate, and keep their colours.
5. **Tests.** `logoReinkOf` gets the synthetic cases, plus the four production re-inks and three misses,
   as small RGBA fixtures rendered with item 1's filter chain and committed. The fixtures are generic
   (strokes, shapes, colours); no production logo file enters the repository.

**Net effect.** One pure function, one extra ffmpeg run per logo (once), one sidecar, one DTO field, and
the tile's background, ink and tint. No migration.
