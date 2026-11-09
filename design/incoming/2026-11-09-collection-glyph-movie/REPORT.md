# The Collection popover's movie glyph — round report

Drawn 2026-11-09, a follow-up brief from Bárður after the owner's pick on DEB-4032 (card
`a32fe8e0`, recorded in FR-9 at `5d9771b8`): *"For 'Sagaer' it should be C but for 'Film club' it
should be some movie icon instead."*

Package: `frames.html`, `cgm-data.js`, `cgm-kit.js`, `collection-glyph-movie.css`,
`Collection Movie Glyph - Directions.html`, seven PNGs under `png/`.

The round is **self-contained**: it restates the previous round's metrics and palettes rather than
importing them, because `design/incoming/<round>/` is pruned at adoption and a cross-round import
would break on that commit.

---

## First, a correction to what I reported last round

The first round listed as a cost of direction C that **"six tiles fuse into one block at TV distance
— the frame at 0.55x shows it"**. **That was wrong.** `cgm-wall-gaps.png` is the picture: the wall's
0.8-unit gaps hold through 55 % and through 38 %. The owner picked C in spite of a cost that does not
exist.

What the wall *does* stop being at 3 m is **six posters**. It becomes a **field of small repeated
marks** — legible, but as a texture rather than as a grid of tiles. That is a different fact from
the one I reported, and it is the fact this round had to design against. **The wall itself needs no
change.**

## What the round produced

**Four movie glyphs, each drawn in the row beside the wall, with the distance column for all four.**

| | Ink | |
|---|---|---|
| **Clapperboard** *(recommended)* | 236 | a solid body under a slanted clapper bar, with a widening wedge between them |
| **Poster** *(runner-up)* | 222 | one filled plate with a play notch cut out |
| **Film strip** | 193 | stood on end, perforations vertical |
| **Reel** | 192 | a disc with a hub and four spoke windows |

**The clapperboard is recommended, and the reason is not legibility.** All four read at 18 dp — the
distance column shows that plainly, which is itself worth saying, because last round the distance
column *was* decisive and this round it is not. What decides it is that **the wall is a field of
small repeated marks and three of the four candidates are another one**: the strip is six dots, the
reel is a ring of them, the poster is a plain plate. The clapperboard's dominant feature is a
**diagonal**, and — checked against every glyph in `DeskIcons.kt`, `MusicGlyphs.kt` and `Glyphs.kt`
— **nothing else in the system has a diagonal at all.** Against a dot field that is the most distinct
thing available, and at 236 against the wall's 242 the column does not read heavier on one kind of
row than the other.

**Its cost:** it is the most detailed of the four. The two stripes are 1.8 units wide — they hold at
55 % and go by 38 %, leaving a plain slanted slab. It has to work without them, and the distance
column is where that is judged.

## The runner-up is a real second, not a formality

**The poster is the cleanest *meaning* of the four** — one film against a wall of them, which is
exactly the difference between a hand-made list and a big category, and its notch survives 55 %
intact. If the owner reads his own pair as *singular against plural*, the poster is the right answer
and the clapperboard is not. That reading is a fair one to have of his words, so **both are worth
putting to him.**

Against it: a filled plate with a play triangle in it is the shape `MusicIcon.FILM` already uses for
a video, and at 13.6 units it is the narrowest mark here against the wall's 18.4, so the hand-picked
rows read smaller down the column.

## Why the other two are not recommended

- **Film strip** — at 55 % it is a narrow field of small dots and the wall is a wide field of small
  dots. The same vocabulary twice is the one thing this pair must not be. `DeskIcon.FILM` is also
  already a film strip, meaning the Movies page in the desktop rail.
- **Reel** — `DeskIcon.DISC` is a disc with a hub, a music album, and at 3 m a reel and a disc are
  the same circle. That is the collision that dropped the star and the bookmark last round, so
  dropping it here is the same rule applied twice. Its spoke windows are a ring of small dots,
  echoing the wall again, and 192 against 242 leaves the column uneven, which is what cost
  direction B.

## What only the pictures showed

1. **The clapperboard's first render read as three floating chevrons, not a striped bar.** A 5.0-unit
   bar with 2.4-wide stripes puts a gap nearly half the bar's own thickness between the segments, and
   a gap that size separates rather than cuts. It is now 5.6 thick with 1.8-wide stripes — ratio 0.32
   against 0.48 — and reads as one band. Nothing in the markup says which of those two it is.
2. **The body was too squat** at 20 × 9.4 of a 24-unit box, reading as a lozenge rather than a board.
   It is now 17.6 × 9.2, with the bar overhanging it by 0.8 each side: a hinge stub left, a clapper
   tip right.
3. **The wall-fusion claim above** — found by drawing the thing I had asserted and looking at it.
4. **A stale ink figure in a caption** (261 after the geometry moved to 236). Fixed at the source: the
   caption now reads the number out of the data array instead of carrying its own copy, so it cannot
   go stale again.

Checked and clean: at 6× there are no stray pixels, no fused bar segments, no clipped clapper tip and
no overlap between bar and body, in any of the five themes.

## Nothing else changed

Row height, focus ring, the popover's size rules, the 28 dp `CHANNEL` width allowance, the glyph's
18 dp box, its position after the check slot, and `colors.text` resting and focused — all exactly as
the first round fixed them, and all still correct with the wall and the clapperboard in place. The
two screen-reader strings stand as drawn; the viewer never reads the word *Jellyfin*.

**Frames redone as asked:** 1, 2, 3 and 5 of the original brief with the new pair, plus the distance
column. Frame 4, the closed chip bar, carries no glyph and is unchanged from the first round — not
redrawn.

**States not drawn, and why** — unchanged from the first round and restated so this package stands
alone: loading / empty / error / offline, because the popover has none of its own, the chip is not
added to the bar at all when no card carries a channel, and it holds no socket and no player; desktop
and the receiver, because `FacetPopover` has no `isDesktopLayout` branch and the receiver has no
browse page; Tizen, because both glyphs are flat filled paths.

## Housekeeping

- Nothing forbidden arrived in the round's inputs. Nothing to drop.
- No phase number and no task id in any drawn file.
- Every number in every picture comes from one array (`cgm-data.js`), the five palettes copied value
  for value out of `ravilo-ui/.../theme/Colors.kt`.
- `frames.html` **links** `../../ravilo/ravilo.css`; nothing is copied. No network reference of any
  kind. The render ran with `--network none` and came home clean.
- No `backdrop-filter`, no `content-visibility`, no large blur, no infinite animation.
- No `<defs>`, no gradient, no paint server in any SVG — every shape and every hole is path data in
  one `<path>` with `fill-rule="evenodd"` — and ids are minted from a counter regardless.
- Every class is `cgm-` prefixed, so linking the production stylesheet cannot collide.
- Every poster title is reused from `design/ravilo/ravilo-data.js`'s invented corpus; the stand-in
  rows are the brief's, exactly.
- Four fences run green on the staged round.

## For the Compose port, when it comes

Both glyphs are filled paths on the 24-unit grid, in the `DeskIcon`/`MusicGlyph` idiom. The wall is
six rounded rects. The clapperboard is one rounded rect plus three four-point polygons; **no
`PathFillType.EvenOdd` is needed for the recommended pair** — that is why the bar is three
parallelograms rather than a bar with two holes. The poster and the reel would need `EvenOdd`; the
exact coordinates for all four are in `cgm-data.js`.
