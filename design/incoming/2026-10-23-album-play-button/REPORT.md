# One *Play album ▾* button, and the ⋯ beside it

Round slug `album-play-button`, 2026-10-23. Ravilo music, phone, plus the desktop header.
Drawn for Bárður's brief, which carries the owner's own direction on DEB-2254 card `e3a844f5`:
*"I would like the Play Album and Shuffle to merge into a single button… with a down arrow on the
right side… and then we have this … button on the right of it."*

**The package.** `Album Play Button - Directions.html` is the document to read; 38 PNGs are in
`png/`; `frames.html` + `apb-data.js` + `apb-kit.js` + `apb.css` are what the Kotlin would be
ported from. The album-more-placement round's stylesheet **and** the music-playlists round's
stylesheet and kit are linked, not copied, as the brief asked — and those in turn link the shipped
`design/ravilo/music-play-all/` pair, so these pictures are diffable against all three earlier
rounds.

**Four variants.** The owner fixed the row, so the four differ only in the two things the brief
asks about: whether the main part **relabels** after a pick, and how the menu **groups** its rows.

**The recommendation is C**, marked as a recommendation in the document and nowhere else. **B is
the owner's own example**, and the one line separating them is in the document as a trade, not as
a verdict.

---

## What the pictures showed that the markup did not

**1. With the menu shut, the four variants are one picture — measured, not estimated.** This is
the finding that decides how the round is presented. The first render drew all sixteen shut-menu
frames and compared them pixel by pixel: **no two differ by more than 35 pixels out of 280 800, at
a maximum channel delta of 27 of 255, and every one of those pixels is in a 20 px band at the
frame's right edge where the page-tint gradient dithers.** Nothing in the layout differs.

So the shut-menu states are drawn **once**, with the variants in the file name
(`apb-abcd-rest`, `apb-bcd-relabel`, `apb-abcd-more`), and the four menus — the whole question —
are drawn per variant. Four copies of one picture would have buried the two frames there are to
compare. The same collapse holds on the plain album, for a stronger reason: a plain album has one
order, so it has nothing to remember, nothing to tick and nothing to subtitle, and every one of
the three things the variants differ in falls away.

**2. The width question is a yes, and it undoes the previous round's problem.** The
album-more-placement round found R373's row already truncating at 360 dp — *Shuffle* as **Sh…**,
the relabelled primary as **Play albu…**, in English. One split button removes it: the ⋯ takes 48
of the 324 dp, the gap 8, the ▾ half the shipped 44, and the label gets **224 dp** where R373's
pair gave it about 150. `apb-zoom-row-da-4x.png` is Danish — *Afspil album + ekstra*, the longest
label in the product — whole, centred and clear of both corner radii. **R373's relabel survives
the merge in all three languages**, which removes the reason the previous round had for dropping
it, and is why three of the four variants keep it.

**3. The fourth menu row was invisible in the first render.** Shipped `.re-menu` is `z-index: 5`,
under the mini player (71) and the bottom bar (72). Harmless today, because today's menu has **two**
rows and stops about 90 px short of the player. Every variant here has **four**, and the fourth —
*Shuffle + extras*, the row the owner specifically asked for — was painted underneath the mini
player and could not be seen at all. Drawn at `z-index: 75`. **This is a real change to shipped
CSS**, stated in `apb.css` at the line, and it is open below.

**4. The Danish subtitle wrapped, and the glyph I added is why.** Shipped `.re-menu` is
`min(290px, 100%)`, which is enough for R373's rows because they have no glyph — both of them are
*Play*. Once one menu holds the shuffles as well, a glyph is the only thing telling the two kinds
of row apart at a glance, and an 18 px glyph with its 10 px gap costs the row 28 px. In
`apb-b-menu-da` that was exactly the difference between a one-line subtitle and a wrapped one:
*"10 sange · den officielle rækkefølge"* went to two lines, which made the ticked row a head taller
than the three beside it and left the ✓ floating against nothing. Taking the menu to the full
324 dp of content gives it 34 px back and every subtitle in all three languages holds one line.
**A second change to a shipped number**, stated at the line, and open below.

**5. The split paints its gradient twice, and at 268 px that is visible.** Each half carries
`background: var(--grad)` of its own, so the main part runs purple → cyan and the 44 px ▾ beside it
**starts again at purple** — it reads as a lighter tab bolted to the end of the button rather than
half of it. It is in R373's row today, but today the main part is one of *two* split buttons and
about 150 px wide, so the restart is small; the owner's single button is 268 px and the gradient
travels much further first. `apb-zoom-seam-4x.png` at 4×.

**Every variant frame in this round is drawn with the seam, exactly as production draws it**, so
the four are compared against the row that exists — the same discipline the previous round used in
refusing to invent button padding. One extra frame, `apb-seam-fix.png` with
`apb-zoom-seam-fix-4x.png`, shows the fix beside it: the gradient moves to the wrapper, the halves
go transparent, the division becomes an inset hairline. **It is not a variant and not a choice for
the owner** — a production change for you to spec or drop.

**6. D's heading costs exactly the clearance the menu had.** `apb-zoom-d-menu-3x.png` at 3×: D's
menu bottom edge lands flush on the bottom bar — no overlap, and no gap either, so it reads as cut
off by the bar rather than floating over the page. The other three clear it by about 25 px. And on
a plain album the heading is *all* that is left of D, sitting over two rows, one of which is what
the button beside it already does (`apb-d-plain-menu.png`).

**7. The desktop ⋯'s menu was 120 px away from its own button.** The linked round anchors it
`right: 0` against `.btns` — right when the ⋯ is the last thing in a short row, wrong here,
because `.btns` is as wide as the album title above it. The menu landed at the header block's
right edge, reading as a menu from nothing. Fixed by hanging it from the button;
`apb-dk-c-more.png`. **Worth a line wherever the playlists round's desktop frames are referenced**,
because the same anchor is in that round.

**8. Two Faroese strings in my own data file were the mockup's drafts, not the shipped values.**
`design/ravilo/ravilo-i18n.js` has `ed.sub_album` as *"tann almenna raðfylgjan"* and `ed.more_play`
as *"Fleiri hættir at spæla"*; `i18n/fo.json` ships *"upprunaliga raðfylgjan"* and *"Fleiri mátar
at spæla"*. The shipped file wins, so the frames were re-rendered against it. Caught by reading
every key out of `i18n/*.json` one at a time rather than trusting the mockup's table — the mockup
is where the drafts live, and three of this round's nine strings disagree with it.

---

## The four, one line each

| | keeps R373's remembered pick | menu grouping | subtitles | new strings |
|---|---|---|---|---|
| **A** the label never changes | **no** — drops it, and the two subtitles with it | by order | **gone** | 1 |
| **B** relabels · by order | yes | by order — **the owner's own example** | kept | 1 |
| **C** relabels · by action | yes | by action — **the top group is R373's shipped menu, row for row** | kept | 1 |
| **D** relabels · the menu names itself | yes | by order, under `ed.more_play` | kept | 1 |

**Why C.** Its first group is today's menu *unchanged* — same two rows, same subtitles, same tick —
with the two shuffles joining under a separator, so nothing a listener already learned moves. It is
the calmest of the four to look at, because the two tall rows pair up and the two short ones pair
up instead of alternating. And it is the only grouping in which the ✓ cannot be misread: both
ticked-eligible rows are adjacent and both are *Play*, so the tick plainly marks **an order**.

**Why B is the honest alternative, and why I have not treated it lightly.** It is the owner's own
example, and in it each group is self-contained — *here is the official album and the two things
you can do with it* — so a shuffle row carries its order in the grouping as well as in its words.
In C that order is carried only by the words. **That is a direction, so it is the owner's.**

**A** is the only variant that changes shipped behaviour, and the change is not small: R373
remembers the order per album per device and says so on the button, and A stops doing both. If the
remembered pick is judged not to have earned its keep, A is the honest way to drop it — but that is
a behaviour decision, not a drawing one.

---

## The brief's note, answered in one line

**Should this pattern spread to artist and playlist pages later?** The ▾ should not; the ⋯ should.
An artist page and a playlist have **one order**, so their ▾ would hold exactly two rows — *Play*
and *Shuffle* — which is a dropdown that hides a button and gives nothing back. The merge earns its
keep only where there is more than one way to order the thing, which today is an album with
editions and nothing else. Keep *Play* + *Shuffle* as a pair there, and let the ⋯ spread.

---

## Open, and back to you

1. **Does picking a shuffle row move the remembered order?** If a listener picks *Shuffle +
   extras*, does the main part relabel to *Play album + extras*? Drawn as **no** — the ✓ and the
   relabel follow the two *Play* rows only — but FR-R373-2 point 3 says the pick is what *both*
   buttons follow, and a shuffle row now carries an order in its name. It is a behaviour question,
   not a drawing one.
2. **A plain album's button becomes *Play album*.** It says *Play* (`music.play`) today. Drawn as
   *Play album* on both kinds, because that is what makes them one design and the brief asked for
   that — but it changes the button on **every** plain album page in Ravilo.
3. **The open menu lands on the mini player.** Drawn over it at `z-index: 75`, because the
   alternative was the fourth row being invisible. The player's ends still show past the menu's
   edges. A scrim, as the shipped sheets use, is the other way; it is the same answer for all four
   variants, so it is not a variant.
4. **The gradient seam** (finding 5) — fix in production, or leave as shipped?
5. **One new string**, `ed.shuffle_extras`, needed by every variant: *Shuffle + extras* /
   *Bland + ekstra* / *Blanda + eyka*, marked DRAFT in `apb-data.js` and in the document. Nothing
   else is new — the other eight are read out of `i18n/*.json` at their shipped values.
6. **{k}, the extras count,** drawn as *+ 3 extras*, carried over from the album-more-placement
   round, which asked for the real one and has not had it. The extras row's subtitle count is drawn
   as **13**; the shipped subtitle fills `{n}` with official + extras + **B-sides**, and this
   stand-in album draws no B-sides, so 13 is 10 + 3 and would be larger on a real album. Said in
   `apb-data.js` where it is defined, so 13 does not read as a rule.
7. **The nine album track titles** beside *Northern Lights* are still the music mockup's corpus,
   carried from the playlists round, which asked for the real stand-ins and has not had them.
   Nothing here depends on them.

---

## Lane

- Drawn under `design/incoming/2026-10-23-album-play-button/`, nothing else touched.
- Rendered with `scripts/render-design.sh` (DEB-2125), six invocations plus five zooms.
- **Every one of the 38 PNGs opened one at a time**, four of them at 3× or 4×. Findings 3, 4, 5,
  6 and 7 were visible in no other way, and finding 1 is a measurement taken off the pixels.
- A canvas bug caught on the way: this round's captions name the variant *and* the state and run
  to three lines, and the second and third lines painted **inside** the frame, which the render
  then cropped into the PNG. `frames.html` now clears three lines. A caption is the canvas, not
  the design, and it had reached the pictures.
- No phase number and no task id anywhere in the drawing.
- No network reference: the faces resolve out of the linked shipped stylesheet's own `fonts/`.
- Canvas rules observed — no `content-visibility`, no `backdrop-filter`, no large `filter: blur`,
  no infinite animations.
- Nothing arrived in this round's inputs that must never land under `design/`.
- The four fences run on the staged directory.
