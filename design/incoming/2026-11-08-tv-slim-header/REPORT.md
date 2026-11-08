# Slim TV header, 90 % screen scale, two browse ideas — round report

Drawn 2026-11-08 for Bárður. Surface: the Ravilo **TV** layout only, 960 × 540 dp on the 1920 × 1080
stage. Twenty-four PNGs in `png/`, the directions in `Slim Header - Directions.html`, the frames in
`frames.html` + `slim-header.css` + `slim-header.js`, every number in `slim-header-data.js`.

## The recommendation, in one table

| Part | Standard (today) | **Slim — recommended** |
|---|---|---|
| Bar height | 60 dp | **48 dp** |
| Brand mark | 26 dp | **20 dp** |
| Wordmark | 22 sp, −1 sp tracking | **17 sp, −0.8 sp** |
| Brand gap | 8 dp | **7 dp** |
| Nav pill type | 15 sp | **14 sp** |
| Nav pill padding | 12 × 6 dp | **11 × 5 dp** |
| Nav pill corner | 11 dp | **9 dp** |
| Gap between all top-level items | 18 dp | **14 dp** |
| Search / avatar circle | 40 dp | **32 dp** |
| Search glyph | 22 dp | **18 dp** |
| Focus ring on the circles | 2 dp, flush to the fill | **2 dp, held 2 dp clear** |
| Avatar initials | 14 sp | **12 sp** |
| Clock | 15 sp | **13 sp** |
| Side gutter (`screenPadH`) | 48 dp | **48 dp — unchanged** |

Twelve dp back to the content. The side gutter does not move: it is overscan safety (R205, R250),
not height.

## The three open decisions

- **Height — 48, not 44.** Not because of the type. Search and the avatar each carry a focus ring
  drawn *outside* the circle, so what must fit between the bar's edges is the whole focusable disc.
  At 48 the disc is 40 dp with 4 dp of page above and below it; at 44 it is 38 dp with 3 dp, and at
  85 % screen scale that 3 dp is 2.55 dp of real screen. A ring that close to a solid bar's edge
  stops reading as a ring. Both are drawn (`slim44-home-scrolled`) so this can be judged rather than
  taken on my word.
- **The clock stays**, at 13 sp. On a TV it is the only clock in the room — R267 removed it from the
  handset precisely because the platform draws one there and a TV has nothing of the kind.
- **The wordmark stays**, at 17 sp. Mark-only saves about 76 dp of a 960 dp bar that is not short of
  width, and spends the one place in the product where it says its own name.

## A and B

- **A — "Surprise me": worth it, as A1 (the labelled pill).** Drawn both ways. The pill sits in the
  right cluster, which is where the bar keeps its *actions*, so it does not read as a fifth tab —
  the resting frame (`A1-bar-at-rest`) is the one that answers that, and it does. A2 (glyph only) is
  cheaper in width and says nothing: a sparkle does not tell a viewer "open one title I have not
  seen". **The glyph is deliberately not two crossing arrows** — `detail.shuffle` / *Blanda* already
  owns that on a series page, and "play these in a random order" is a different promise.
  **"Another one" is drawn at the shipped button size, not smaller**, against the brief's wording: a
  ghost button under `heightIn(min = 44.dp)` breaks the actions row and puts a D-pad target below the
  10-foot rule. Ghost beside a filled Play already says "secondary".
- **B — the quieter row heading: worth it, with one thing for the owner.** 16 sp heading, count at
  13 sp in `--ink-dim`. **13 sp is not a new size** — `ContentRow` already has a `trailingInfo` slot
  at exactly 13 sp `--ink-dim`; B moves that text from the row's far end to beside the title.
  **But B drops See All from the row**, which is a row's only way into the full list. That is not
  mine to decide. If See All must stay, the count goes beside the title and See All keeps its place,
  and the heading is then busier than today's.

## Discover: I would not tighten the title block

Drawn as the brief leaned, and having drawn it, leaving it is right. `DiscoverScreen` already starts
at `appBarHeight + 24.dp`, so Slim lifts the whole block 12 dp with no further change. Taking more
out of that 24 dp would close the page's one piece of breathing room at the moment the bar above it
got quieter, and 22 sp over a 13 sp subtitle is the page's only hierarchy.

## One change that is a proposal, not a resize

**The focus ring on the search and avatar circles is close to invisible today, and Slim makes it
worse.** `--ring` `#8e82ff` drawn hard against `--accent` `#7b6ef0` is a 4 % difference: at 1× it
reads as a slightly darker hairline, and at three metres it does not read at all. I only found it by
magnifying `slim48-focus-search` to 4×, which is the whole reason that pass exists.

Slim therefore holds the ring **2 dp clear of the fill**, which is the treatment `RaviloButton`
already uses (`focusRing(…, outset = 5.dp)` — and the detail page's focused *Another one* proves it
reads). This is a **pre-existing defect in the Standard bar, not one Slim introduces**; Standard is
drawn exactly as it ships so the two can be compared side by side. Whether to fix Standard too is
Bárður's call, not something I folded in silently.

## What I had to decide, and tell you about

- **The mockup had already drifted, so I drew from the Kotlin.** `design/ravilo/ravilo.css`
  `.appbar` is 92 px — 46 dp — against the shipped 60 dp, and its nav pills, wordmark and circles are
  all off too. Drawing "today's bar" from the mockup would have made this round's own baseline wrong
  by 14 dp and every Slim number wrong with it. Every measurement in this round comes from
  `AppBar.kt`, `Dimens.kt`, `ContentRow.kt`, `Tile.kt`, `RaviloButton.kt`, `HeroCarousel.kt` and
  `DiscoverScreen.kt`. **`design/ravilo/ravilo.css` is now a 46 dp drawing of a 60 dp bar, and that is
  a defect for someone to raise against the mockup tree** — I did not touch it, this round lands
  under `design/incoming/` only.
- **The frames are drawn at Content size COMPACT (× 0.82)**, because that is what prod's global row
  is set to. It is what the owner will recognise, and it is the multiplier FR-5 composes with.
- **Three of the brief's seven stand-in titles are episode names in the corpus.** Heimferð, Brotsjór
  and Nýggj Spor are episodes of Nordvest, Havets Hjarta and Arvur in `ravilo-data.js`. They are all
  invented strings already in the corpus and I used the list exactly as the brief named it, but a
  reviewer diffing this round against the corpus will see posters named after episodes. Worth
  straightening in the next brief rather than inventing a substitute here.
- **Brotsjór, the landing title, is likewise an episode name** (Havets Hjarta S03E06). Drawn as a
  film detail page, as the brief asked.
- **Nothing in the round's inputs needed dropping.** No `STATUS.md`, nothing from
  `specs/research-reports/`, nothing from `scripts/`, no `CLAUDE.md`.

## What only the pictures showed

Six defects in the first renders, every one of them invisible to the four fences:

1. **Every TV frame was 1920 × 1081, not 1920 × 1080** — and before that, 1924 wide with a 4 px strip
   of grey backdrop down the right edge. A block `<section>` takes the sheet's full content width, and
   a text canvas earlier in the document lays out to a fractional height, which pushes every frame
   after it onto a fractional *y* that Playwright rounds outwards. Fixed with `width: fit-content`,
   an integer margin, and both annotation canvases moved to the end of the sheet.
2. **The "solid" scrolled bar was invisible.** I had used `rgba(var(--bg-rgb), .95)`; `AppBar.kt`
   uses `colors.surface` at 0.95 — the *surface*, not the page colour. Half of the state the round
   exists to show was a flat nothing.
3. **The focus ring on the circles** — above.
4. **At 90 % every row stopped short of the right edge**, leaving a ~300 px dead gutter, because I
   had hardcoded six posters. A row is a `LazyRow`: it draws past the edge and is clipped there. This
   one mattered more than it looks — the dead gutter *hid the entire point of the screen-scale
   setting*. The rows now run off the edge at every scale, and the 100 %-vs-90 % picture shows six
   posters across against seven.
5. **The hero backdrop read as a flat dark panel.** The corpus gradient sits at 12–42 % lightness and,
   under the production `--hero-tint`, left nothing for the transparent bar to be transparent *over*.
   A key light on the right gives it a subject.
6. **The Idea B comparison said See All was "at the far right"** while the picture, a 464 dp crop,
   showed it mid-frame. Caption corrected; a sentence that disagrees with its own picture is how a
   spec gets written wrong.

## Canvas and render notes

- `frames.html` **links** `../../ravilo/ravilo.css` rather than copying it, so every token, skin and
  font in the pictures is the production one. All of the round's own classes are `sh-` prefixed, so
  there is no repeat of round 9's collisions.
- No `content-visibility`, no `backdrop-filter`, no large blur, no infinite animations. Two
  production fills are drawn flat because `color-mix` is not worth risking on the pinned renderer:
  `accentDim` (the resting primary button) is the accent under a flat darkening plate, and the ghost
  button's border is `rgba(255,255,255,.26)` against the shipped `textSecondary` at 0.4.
- Every SVG `id` is minted from a counter — twenty-four frames each carry their own brand mark, and
  a shared id would have left every mark after the first painting nothing.
- Render: `scripts/render-design.sh --html design/incoming/2026-11-08-tv-slim-header/frames.html
  --viewport 1980x1200 --all .sh-frame --prefix tv-slim-header-`

## New strings

| Key | English | Danish | Faroese |
|---|---|---|---|
| `nav.surprise` | Surprise me | Overrask mig | Yvirraska meg |
| `detail.another` | Another one | En mere | Eitt annað |
| `detail.kicker_surprise` | SURPRISE | OVERRASKELSE | YVIRRASKAN |

The Faroese is a draft wanting a native read; *Vel eitt fyri mig* ("pick one for me") may be more
natural than *Yvirraska meg*. Every other string in every frame is the shipped `i18n/*.json` value.

## Still open, for Bárður

1. **44 or 48** — the owner's pick, both drawn.
2. **Does B drop See All, or keep it?** B as drawn drops it. This changes what a row offers, so it
   is an owner question, not a drawing one.
3. **Do we fix Standard's focus ring too**, or only Slim's? Slim needs it; Standard has the same
   defect and is out of this round's scope.
4. **`design/ravilo/ravilo.css`'s `.appbar` is 46 dp against the shipped 60 dp** — a defect in the
   mockup tree, for whoever owns raising it.
5. **A native Faroese read** on the three new strings.
