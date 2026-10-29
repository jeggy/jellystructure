# Collection artwork — three logos and three tile gradients

Two directions, three collections, drawn on the real tile. The directions doc is
`Collection Artwork - Directions.html`; the frames render from `frames.html` + `artwork-frames.js`
over `artwork-data.js`, which `tools/build-artwork.py` generates along with every SVG and every
number quoted here.

**The series hub's name is on hold.** Sagaer and Tidsmaskinen are complete, with upload files for
both directions. The series hub carries its mark and its gradient only — no wordmark, no watermark,
no `upload/` file — and its identifier is `serie-hub` everywhere. Where a frame shows its tile, the
wordmark's place is a dashed slot drawn by the canvas; it is in no SVG and in no file.

## The recommendation

**Direction A, the house badge.** Two reasons, one of them measured:

1. It continues the shape every logo already on the rail has — a white mark, then a white wordmark.
2. **It holds the family's cap height across a six-letter name and a twelve-letter one.** Sagaer
   sets the family at 39.25 units in the 320 × 100 box. Tidsmaskinen reaches 32 on two lines — 82 %
   of it. In direction B the same pair is 51.36 and 36, which is 70 %, because B spends the whole
   box on the short name and then has nowhere to go on the long one.

That second reason matters directly for the series hub: *Ét afsnit til* is thirteen characters with
two spaces and an accented capital, so it lands on the same side of this test as Tidsmaskinen, not
beside Sagaer.

## The table

| Collection | `brand_color` | `padding_logo` | White on stop 1 | White on stop 2 | Wordmark |
|---|---|---|---|---|---|
| Series hub (`serie-hub`) | `linear-gradient(135deg, #2a9168 0%, #0d3b2a 100%)` | `{top:14, right:20, bottom:14, left:20}` | 3.93 : 1 | 12.53 : 1 | **none — name on hold** |
| Sagaer | `linear-gradient(135deg, #a33b74 0%, #3a0f33 100%)` | `{top:12, right:18, bottom:12, left:18}` | 6.11 : 1 | 16.20 : 1 | Space Grotesk Bold (wght 700) |
| Tidsmaskinen | `linear-gradient(135deg, #9a7a2a 0%, #3a2a0c 100%)` | `{top:12, right:18, bottom:12, left:18}` | 4.05 : 1 | 13.85 : 1 | Space Grotesk Bold (wght 700) |

Every stop is a hex literal, every gradient is 135deg and darker toward the bottom-right, and every
string parses under the client's own grammar. The numbers are WCAG contrast of pure white against
each stop, computed in `tools/build-artwork.py` and written to `metrics.json`.

**Font.** `ravilo-ui/src/commonMain/composeResources/font/space_grotesk.ttf`, the variable font the
client itself ships, instanced to **wght = 700** with `fontTools.varLib.instancer` and shaped with
HarfBuzz, so the letter spacing is the font's own kerning rather than an approximation. Cap height
700 units per 1000 upm. Every letter is an outlined `<path>`; there is no `<text` in any file.

## The files

| File | What it is |
|---|---|
| `upload/sagaer.svg` | A, Sagaer — upload-ready |
| `upload/tidsmaskinen.svg` | A, Tidsmaskinen, two lines — upload-ready |
| `upload/b/sagaer.svg` | B, Sagaer — upload-ready |
| `upload/b/tidsmaskinen.svg` | B, Tidsmaskinen, two lines — upload-ready |
| `marks/serie-hub-a.svg`, `marks/serie-hub-b.svg` | the series hub's mark, **deliberately not under `upload/`** — they are not complete logos |
| `marks/stand-in.svg` | the neutral placeholder the five existing tiles are drawn with |
| `variants/tidsmaskinen-{a,b}-{oneline,twoline}.svg` | the line test, so the other choice can be uploaded without another round |
| `variants/tidsmaskinen-b-tittleclock.svg` | drawn and dropped, kept so the try is visible |

`grep -ci '<text\|<style\|<filter\|<image\|class=' upload/*.svg upload/b/*.svg` → **0, 0, 0, 0**.
The same grep extended with `href`, `<script` and `foreignObject` over `upload/`, `marks/` and
`variants/` → **0 everywhere**. Each file is `viewBox="0 0 320 100" width="320" height="100"` with
`role="img"` and an `aria-label` carrying the exact name, matching the light-blue tile's logo. The
320 × 100 box is right: fitted inside the padded tile it is width-limited, which is what leaves the
wordmark its cap height. Ids are prefixed per collection; there are in fact no ids left in any
file, because nothing in them needs a paint server.

**Skia.** Every file was rendered through Skia's own `SVGDOM` (`skia-python` 144.1,
`tools/skia-render.py`) as well as through Chromium, and the two are shown side by side per file in
the `compare-*` frames. All ten parse and all ten draw identically. **AndroidSVG is a third renderer
and is not covered here** — the files use only paths, which is what the existing logos use, but
nobody has put them through Android.

## What only the pictures showed

Five things, all fixed before this report:

1. **Direction B's pending slot sat on top of the series hub's glyph**, because the slot was placed
   for A's lockup in both directions. B's glyph now sits where B puts it, left of the word, and the
   slot starts after it.
2. **A tittle-clock is a smudge at the size it gets.** Drawn, looked at, dropped — see the directions
   doc. The replacement is a solid rewind badge, chosen because Sagaer's link glyph proved that
   silhouette survives 13 px and a thin open ring does not.
3. **The TV focus frames were scrolling like phone frames**, by five tiles regardless of width. They
   now scroll by the least that brings the focused tile into view.
4. **A focused tile's 22 dp glow was being clipped by the rail's own overflow.** The rail's vertical
   padding went to 40 px so the glow is inside the picture.
5. **Sagaer's chain links were too thin** beside the lettering at tile size, and the mark was too
   small against its own wordmark. Both were drawn up.

## Two findings for the brief, neither of them a defect

- **Eight 224 dp tiles with 16 dp gaps are 1904 dp wide, so the Home rail overflows a 1920 stage
  before the gutters are counted.** With these three added, the rail scrolls on a TV, not only on a
  phone — the eighth tile is never on screen with the first. Every TV frame is drawn honestly at
  1920 and clipped; the `-all-eight` frames drop the clip so the family can be judged whole.
- **The light-blue tile already on the rail carries white at 1.64 : 1** against its own first stop
  (`#7FD6F2`), and the mid stop is 2.32 : 1. The 3 : 1 rule this brief sets for the new logos is
  therefore stricter than what is shipped. Information, not a defect, and not mine to change.

## Nothing identifying

No library title, no filename, no poster, no still, no real host, no IP. The five existing
collections appear as one neutral placeholder; their names and their logos are nowhere in the round,
in any file or any picture, because they are third-party trademarks and they say what the household
owns. Nothing from `specs/`, `scripts/`, `STATUS.md` or `CLAUDE.md` arrived in the inputs, so
nothing had to be dropped. No phase number and no task id appears in any drawing or file name.

## States not drawn

Loading, empty, error and offline are not drawn: this round draws artwork, and the tile has no
state of its own beyond focus. No new string is introduced — the three names are the owner's own
Danish and the series hub's is unresolved — so there is no Danish or Faroese draft to list.

## Still open

1. **A or B**, the owner's. The lean is A.
2. **The three hues.** Sagaer's berry is the one nearest a focus violet; a wine such as `#9c3268`
   would widen that gap at the cost of moving toward the red tile.
3. **The series hub's name**, and with it its wordmark, its upload file and its watermark — which is
   the name's first eight characters, uppercased, so the name decides it.
