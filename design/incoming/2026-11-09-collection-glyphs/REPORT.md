# Collection popover glyphs — round report

Drawn 2026-11-09. Brief from Bárður: two glyphs for the Ravilo browse page's *Collection* popover,
one for a Ravilo collection and one for a Jellyfin collection, against the spec
`specs/requirements/jellyfin-collections-managed-from-jellystructure.md` (commit `17a43bce`), FR-8
and FR-9.

Package: `frames.html`, `cg-data.js`, `cg-kit.js`, `collection-glyphs.css`,
`Collection Glyphs - Directions.html`, ten PNGs under `png/`.

---

## What the round produced

**Three directions, built as a factorial rather than three unrelated ideas.** A is the
recommendation; **B changes only the hand-picked glyph** and **C changes only the Ravilo glyph**, so
"A, but the other one" is a pick and not a second round.

| | Ravilo collection | Hand-picked collection |
|---|---|---|
| **A — Stack / Tag** *(recommended)* | three plates of equal height, widening downward | a filled label pointing right, with its hole |
| **B — Stack / Pin** | the same stack | a head on a stem |
| **C — Wall / Tag** | six poster plates | the same tag |

**A is recommended because the pair differs in how the ink is distributed** — three strata lying flat
against one solid mass — rather than by a feature. That is the only kind of difference that survives
the brief's binding constraint, and `cg-three-metre.png` is where that is shown rather than asserted.

**Drawn and dropped, each for a collision the app already ships:** a **star** (`StarGlyph` is the IMDb
rating star — a star beside a name in a list ordered by count reads as a score), a **bookmark ribbon**
(`DeskIcon.BOOKMARK` / `MusicIcon.BOOKMARK` are saved-for-later, and its notch against the tag's point
is a two-unit difference), **a tick in a card** (the row already carries a tick, 18 dp to the left),
and **a folder** (`DeskIcon.FOLDER` is a directory on disc).

## What FR-9 asked the round to fix

| | | |
|---|---|---|
| **Size** | **18 dp** square, 24-unit grid | 36 px on the TV, 18 px on the phone. Larger than the 14 dp check and than the name's cap height, because it is the one thing in the row that must read at distance. The AUDIO flag in this same row is already 20 × 14 dp. |
| **Construction** | **filled, no stroke** | The desk set's 1.8-unit stroke is 2.7 px of a 36 px glyph and is gone by the 55 % column. The set already fills where it must (`DeskIcon.PLAY/PAUSE/NEXT`, `MusicIcon.MORE`). |
| **Position** | after the 18 dp check slot, own fixed-width 18 dp slot, **8 dp** before the name | Fixed width so names stay on one left edge down a mixed list. The AUDIO flag's existing pattern. |
| **Ink, resting** | `colors.text` | The same ink as the name beside it. |
| **Ink, focused** | `colors.text` — **unchanged** | The row's plate is the whole focus cue, as it already is for the name and the count. |

**Row height, focus ring and the popover's size rules are untouched**, as the brief required. The
18 dp glyph is shorter than the 20 dp text line, so the 36 dp row does not grow, and the glyph is
never focusable.

## The one change the round needs outside the glyph

`facetPopoverWidth` already gives `AUDIO` a **28 dp allowance** for its flag. **`CHANNEL` needs the
same 28** for its glyph — the glyph and its gap cost 26 dp of the name box. This is the existing
mechanism applied to one more facet key, not a change to the size rules: the clamp stays 220–420 dp.
Without it a long collection name that fits today would overflow.

## The 3 m test, with its arithmetic

A 55-inch 16:9 panel is 1218 mm wide; at 1920 px that is 0.634 mm per pixel, and at 3 m one pixel
subtends 0.211 mrad. This PNG read on a 27-inch 2560-wide monitor at 600 mm is 0.388 mrad per pixel.
`0.211 / 0.388 = 0.55`, so **the 1920 frame at 55 % of its pixels is the 3 m set**; 38 % is a 40-inch
panel at the same distance. Both columns are in `cg-three-metre.png`, stated so the approximation can
be argued with rather than trusted.

At 38 %: **A still separates. B's pin goes faint** (96 grid units of ink against the stack's 182 — the
column reads unevenly). **C's wall becomes a block.**

## What only the pictures showed

Four things, all found in the look-at-every-PNG pass and all fixed before this package was committed:

1. **Render 1's stack read as a lidded cup, not a pile.** It was two thin bars over one tall card,
   and the eye merged the card into a body with a lid on it. Fixed by making all three plates the
   *same height* with gaps two thirds of a plate deep. This is not visible in the markup at all.
2. **The pair was optically mismatched** — the stack tall, the tag squat — so the column read ragged.
   The tag is now taller and sharper, and the two are matched at 195 and 182 grid units of ink.
3. **The phone frame had the Collection chip scrolled off screen**, with the popover placed under
   nothing. The facet bar is now drawn carried to the chip, as a scrolled `LazyRow` leaves it.
4. **The longest-string frame's caption was false.** The 40-character Faroese name I had drawn does
   *not* ellipsise — at the 420 dp clamp the name box is about 43 characters. The frame now draws both
   sides of that line, at 58 characters and at 40.

A fifth was looked for and is clean: at 6× there are no stray pixels, no overlap, no clipped point and
no fused plates in either glyph, in any theme.

## One thing seen in the pictures that is not this round's to fix

**A focused row in a facet popover is painted `colors.surface` on a `colors.surfaceVariant` plate —
and on Aurora that is `#161A28` on `#1B2031`, which is *darker*, by a contrast ratio of about 1.1 : 1.**
Measured off the pixels of `cg-tv-a-sagaer-focused.png`, not estimated. At 3 m it is very nearly no
focus cue at all, and it is the reason frames 1 and 2 look almost identical — which was worth
reporting in itself.

That is shipped behaviour across every facet popover, it long predates this spec, and it is outside
this brief. **Raising it with you rather than touching it.** It is also the thing that would most
change whether these glyphs feel like they work on the sofa, so it may be worth its own task before
DEB-4032 ships.

## Strings

Two new, both screen-reader only. The viewer never reads the word *Jellyfin*.

| Key | English | Dansk | Føroyskt |
|---|---|---|---|
| `browse.collection.ravilo` | Ravilo collection | Ravilo-samling | Ravilo-savn |
| `browse.collection.picked` | Hand-picked collection | Håndplukket samling | Handplukkað savn |

The row announces label, then name, then count — *"Ravilo collection, Sagaer, 38"*.

**And one that is already right.** `browse.facet.channel` ships as **Channel / Kanal / Savn**. The
spec's rename is *Collection / Samling / Savn*, so **Faroese already says the word and does not
change** — only English and Danish do. `i18n/fo.json` carries a note on a neighbouring key warning
that *rás* (a live-TV channel), *sjónvarpsrás* (a broadcaster), *Studio* and *Savn* are four concepts
that must not be merged, which is why the two labels above qualify *savn* rather than reusing it bare.
Per SKILL-house, a design/shipped disagreement is information, not a defect — reporting it, not
"fixing" it.

## Two disagreements with the brief, drawn as shipped

1. **The chip summary.** The brief writes it as *Collection: Sagaer, Film club*. The shipped code is
   `Text(if (badge != null) "$label ($badge)" else label)` — **parentheses, not a colon**. The brief
   also says the summary is unchanged, so the parenthesised form is what the frames draw. If the colon
   was meant as a change, it is a change and needs saying.
2. **The brief does not say which of the two kinds of round this is** (product design, or the
   operator's own setup). `PROCESS.md` asks for that before drawing. I did not send it back for it,
   because the artefact is a glyph pair that every jellystructure instance would ship — unambiguously
   product — and because the brief had already handled the one risky dimension by naming the stand-in
   data exactly and citing DEB-2839 for the public collection names. **Flagging it so the next brief
   carries the line**, not as a defect in this one.

## Three things the round could not answer and did not decide

1. **The chip's closed summary carries no glyph.** FR-9 is about the popover's rows, and the summary
   is one string of mixed names — *Sagaer, Film club* is one Ravilo collection and one hand-picked
   one, with nothing to tell them apart. Drawn as text only. Whether that matters is yours.
2. **A name collision between the two kinds.** Nothing stops an operator naming a Jellyfin collection
   *Sagaer* while a `ChannelConfig` of that name exists. The popover would then show two rows with the
   same name and different glyphs — which is arguably the glyph doing its job, and arguably confusing.
   Not drawn; no stand-in data for it in the brief.
3. **Whether the glyph should also appear wherever else a collection name is shown** — the browse
   page's own header, the empty state (`browse.empty_channel`). FR-9 names the popover only, so the
   round drew the popover only.

## States

**Drawn** — TV focused on a Ravilo row; TV focused on a hand-picked row; phone 412; the chip bar
closed in three languages; the pair alone at 1× and TV size in all five themes; 10-foot focus; the
longest string, both sides of the 420 dp clamp; the 3 m test at two distances; the three directions in
situ; the three focus-ink treatments; the row's anatomy in dp.

**Not drawn, and why** — *loading, empty, error, offline*: the popover has none of its own. It is
built from facet values already in hand, the chip is not added to the bar at all when no card carries
a channel (`if (all.any { it.channels.isNotEmpty() })`), and it holds no socket and no player.
*Desktop and the cast receiver*: `FacetPopover` has no `isDesktopLayout` branch, so a computer draws
this row at the same dp; the receiver has no browse page. *Tizen*: the same paths and the same flat
fills, with nothing in either glyph an eleven-year-old engine cannot render.

## Housekeeping

- Nothing forbidden arrived in the round's inputs — no `STATUS.md`, nothing under `specs/`,
  `scripts/` or `.github/`, no `CLAUDE.md`, no `i18n/*.json`. Nothing to drop.
- The round carries **no phase number and no task id** in any drawn file.
- Every number in every picture comes from one array (`cg-data.js`), including the five palettes,
  which are copied value for value out of `ravilo-ui/.../theme/Colors.kt` — Ravilo's production truth,
  since no Gradle task consumes `design/ravilo/` and `ravilo.css` carries only three of the five
  themes.
- `frames.html` **links** `../../ravilo/ravilo.css`; nothing is copied. No network reference of any
  kind, no `@import url(http…)`, no hosted font, no `http`/`https` literal anywhere in the round. The
  render ran with `--network none` and came home clean.
- No `backdrop-filter`, no `content-visibility`, no large blur, no infinite animation. The popover's
  depth is a flat `box-shadow`, which is what Compose draws anyway.
- No `<defs>`, no gradient, no paint server in any SVG — the id trap cannot fire — and ids are minted
  from a counter regardless.
- Every class is `cg-` prefixed, so linking the production stylesheet cannot collide (round 9's bill).
- Every title in the poster grid is reused from `design/ravilo/ravilo-data.js`'s invented corpus;
  nothing new was invented and nothing identifies the household's library.
- The two Jellyfin-collection stand-ins and the two long names are inventions in the brief's and the
  corpus's register.
- Four fences run green on the staged round.
