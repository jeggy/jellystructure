# Phase R308 — Every studio and network tile sits on the light plate

> Owner, 2026-09-26: *"Let's update the background colors in the Networks and Studios tabs within
> Discover page in Ravilo. So that it always uses the white color of everything. Currently it only uses
> it for most things, but we have some examples where it doesn't use a white color and when there is no
> logo image available it just displays the text. But also without a logo, let's make it so, it always
> uses this white background."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Client (`ravilo-ui`, every platform: TV, phone, web)
plus one backend judgement that extends Phase 232. **Numbering:** verified against `STATUS.md` the same
day — Ravilo taken through **R307**.

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

- **13 read correctly on the plate exactly as they are.** Most are *coloured*, not white: 232's rule
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
  reads cleanly. See open question 1.

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
beside `logoInkOf` and `clearlogoInkOf`, unit-tested. A logo is re-inked only when **both** hold:

- **Most of its ink would be lost on the plate:** a large share of the alpha-weighted visible pixels is
  both light and unsaturated, so it has neither the luminance contrast nor the hue to show against
  `#E8EAF0`. A saturated light colour (yellow, lime, cyan) is not lost, because it reads by hue (Channel
  5's yellow is 0.86 mean luminance and reads fine).
- **It is ink on transparency, not a shape of its own:** its opaque fraction is low. A logo with an
  opaque body is never re-inked, because it would become a silhouette (FR-232-5a's reason, restated).

Thresholds are chosen the way 232 chose its own: by running the rule over production's logos before it
ships, and recording the result here. **Expected on production today:** exactly the three studio logos
described above are re-inked; none of the other 13 light-ink logos, and none of the 205 dark-ink logos.

⚠ **Measure on a thumbnail that keeps thin strokes.** `FfmpegRunner.rawRgbaThumb` squashes the logo to
32 × 32 (aspect not kept) with `flags=area`, which averages each channel without weighting by alpha, so
the colour of the transparent pixels bleeds into the strokes. A pure-white wordmark measures a mean
luminance of **0.92** at full size, **0.89** at 128 px, **0.84** at 64 px, but only **0.74** at 32 px.
232's `light` line (0.6) survives that. A rule about *white* ink does not. Use a larger, aspect-keeping
thumbnail for this verdict (for example 128 px wide), or a premultiplied scale. Either way it is still
one ffmpeg run, and still no PNG decoder in the backend.

**FR-R308-5 — One additive wire field; `logo_ink` is left exactly as it is.** `FacetItem` gains
`logo_reink: true`, present only on a logo the server has judged to re-ink and absent otherwise (absent =
draw as is, which is also what an unjudged logo gets). `logo_ink` keeps its current values and meaning,
because installed apps read it: an app from before this phase keeps drawing what it draws today (light
ink → dark card). The new client no longer reads `logo_ink` on these tiles, but the field stays in the
payload and in the shared DTO: an installed app still deserializes it, and removing a field "nothing
reads" is how v1.31 broke an installed TV.

**FR-R308-6 — Genres are not part of this.** The Genres wall has no logos and keeps its dark wordmark
cards. It is a different wall (3-up on the phone, shorter cards) and the owner named Networks and
Studios.

## Non-goals

- The plate colour itself. The owner calls today's plate "the white colour", and it stays `#E8EAF0`
  (open question 2).
- Title clearlogos (232 FR-232-5, R259 FR-R259-6), the admin Metadata page (192's checkerboard), and
  choosing or uploading a different logo for a studio or network. There is no such control today.
- `design/ravilo/ravilo.css`'s `.taxo-card` rule, which still gives a wordmark tile the dark card. That
  is the design project's to follow. The shipped app is what this phase changes.

## Acceptance

1. On the TV, Discover → Networks: every tile's card is the light plate. Channel 5, BBC Three, CBeebies,
   Hulu, KiKa, TV 2 Fri and TV3 are drawn in their own colours on it. The one network without a logo
   shows its name in dark ink on the plate, with its count beneath.
2. Discover → Studios, scrolled top to bottom: no dark card anywhere; every no-logo tile has its name in
   dark ink; the three white logos are drawn in dark ink and readable; no logo has turned into a solid
   silhouette.
3. The same two walls on the phone and in the web app. Focus (TV) and press (phone) look as they do today.
4. `/api/tv/facets` on production: `logo_reink: true` on exactly the three studio logos above; every
   `logo_ink` value is unchanged from before the phase.
5. An app installed before this phase, against the new backend, draws both walls exactly as it does
   today.
6. Unit tests for the verdict, on synthetic rasters: white strokes on transparency → re-ink; a white
   opaque shape with a dark outline → not; saturated yellow strokes → not; black strokes → not; an
   opaque rectangle → not; nothing visible → not. Plus the three production cases, measured at the
   thumbnail size the rule actually uses.

## Open questions

1. **Channel 4.** Pale green strokes on transparency: visible on the plate as they are, but weak.
   Re-inking reads better, but takes the brand colour away. The rule as written leaves it alone (it has
   hue). **Lean: leave it**; re-ink only what would otherwise vanish.
2. **Pure white instead of `#E8EAF0`?** The owner says "white". Today's plate is a cool off-white,
   chosen in R257 so a logo tile did not glare on a dark TV page. **Lean: keep the plate**, since it is
   the colour the owner is pointing at as the one to use everywhere.
3. **Should Genres follow for uniformity?** Lean no (FR-R308-6). It is one line to change if the owner
   wants it.
