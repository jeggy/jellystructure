# Where the album ⋯ goes on the phone when an album has editions

Round slug `album-more-placement`, 2026-10-23. Ravilo music, phone, plus two desktop frames.
Drawn for Bárður's brief, which carries the owner's answer on DEB-2254 card `979f4e34`:
*"Let's go through Rannvá, so she can visually give us some options to pick from"*.

**The package.** `Album More - Directions.html` is the document to read; 38 PNGs are in `png/`;
`frames.html` + `amp-data.js` + `amp-kit.js` + `amp.css` are what the Kotlin would be ported from.
The music-playlists round's stylesheet **and** kit are linked, not copied, as the brief asked — and
that round in turn links the shipped `design/ravilo/music-play-all/` pair, so these pictures are
diffable against both earlier rounds.

**Five options, not three**, drawn with the four states the brief named: the editions album at
rest, the same album relabelled *Play album + extras*, the ⋯ open, and a plain album beside each.
Every option is drawn in English; the longest-label state of each is drawn in Danish and Faroese
too, because that is the state the brief's test actually bites on.

**The recommendation is E**, marked as a recommendation in the document and nowhere else. It is the
only option in which every label is whole in all three languages, and it is the only one that keeps
R373 entire while doing so.

---

## What the pictures showed that the markup did not

**1. R373's row already does not fit the phone, before this round adds anything.** This is the
finding that decides the brief's question, and it was not visible until the frames rendered.
`amp-b-rest.png` has the ⋯ taken off the action row completely — and *Shuffle* is still **Sh…**.
`amp-b-relabel.png` is **Play albu…** and **Sh…**. The `.re-sh` half is `flex:.72` of 316 dp, which
leaves 44 dp of label area for a word that needs about 62.

So *"every label stays whole"* is **not a test the ⋯'s placement can pass on its own**. Options B
and C remove the fifth tap target and change nothing about the labels. Only two of the five reach a
whole relabelled primary: A, by taking the relabel away altogether, and E, by giving the primary a
row of its own.

**2. Option D's two states are the same picture.** `amp-d-rest.png` and `amp-d-relabel.png` are
byte-for-byte the same action row: both picks truncate to **Play alb…**. Telling the listener which
pick is active is the only reason R373 relabels, so D silently deletes the behaviour it claims to
keep. Opening the two files side by side is the whole argument.

**3. Option A fails in Faroese and is on the edge in Danish.** `amp-a-rest-fo.png` — *Spæl fløguna*
wraps to two lines inside a 48 px button. `amp-a-rest-da.png` — *Afspil album* fits on one line and
runs flush into the corner radius at both ends. English is comfortable, which is exactly why this
had to be drawn in three languages rather than reasoned about in one.

**4. Option C's menu opens onto its own controls.** `amp-c-menu.png`: the ⋯ sits high, beside the
title, so its menu drops straight over *Shuffle* and half of *Play album*. Nothing in the markup
says that; the ⋯ is simply 150 px above the buttons and the menu is 8 px under the ⋯.

**5. Option D is inconsistent across albums.** `amp-d-plain.png` keeps a worded *Shuffle*;
`amp-d-rest.png` has a glyph. The same control is two different things on two album pages, which is
the one thing the brief says must not happen.

**6. E's second row left 76 px of itself empty in the first render.** `.sp.sh` is `flex:.72`
because it *shares* a row with `Play ▾` at `flex:1`. Alone on the second row it is the only
flexible item, so .72 stops growing at 72% of the free space and the ⋯ floats in from the right
edge. Fixed and stated in `amp.css`; it is the kind of thing no rule catches and one picture does.

**7. The ▾ half is 44 px, not 40.** Shipped: `ravilo-music.css:414`. The music-playlists round drew
40, which quietly gave every Play label 4 px it does not have on a real phone. Restated at the
production number here, with the correction named in `amp.css`. **This is a defect in the earlier
round's drawing, not in the code** — worth a line wherever that round's frames are referenced.

**8. The playlists round's editions frames carry no `+ {k} extras`.** FR-R373-2 point 1 puts it in
the header, and without it an editions album and a plain one are the same picture. Every editions
frame here has it.

**One thing I read wrong at 4× and then checked.** I first took the play triangle in the Danish
frame for a glyph being squeezed by its label and wrote a fix for it. Magnifying the English frame
beside it showed the same shape: `IC.play` spans 11 units of a 24 box, so it is simply a narrow
triangle. The fix is out and `amp.css` says so. What *is* real in that picture is the shipped
button's **zero horizontal padding** — `png/amp-zoom-glyph-da-4x.png` at 4×. It is left exactly as
production draws it in every frame, because adding padding would move every measurement in this
round by 24 px and the options would be compared against a row that does not exist.

---

## The options, one line each

| | keeps R373 | primary whole en/da/fo | relabel visible | plain album | new strings |
|---|---|---|---|---|---|
| **A** merged | **no** — loses the remembered pick | yes / flush / **wraps** | no relabel exists | identical, best of the five | 2 |
| **B** top bar | yes | **no / no / no** | no, truncated | loses its ⋯ too | none |
| **C** title block | yes | **no / no / no** | no, truncated | ⋯ reads as detached | none |
| **D** compact | yes on paper | **no / no / no** | **no, identical pictures** | **inconsistent** | none |
| **E** two rows | **yes, entire** | **yes / yes / yes** | **yes** | one row, as today | **none** |

**E costs** 58 px of vertical space on an album that has editions — one fewer track above the mini
player — and nothing else. No behaviour changes, no string changes, and the desktop is untouched:
`amp-dk-e-rest.png` and `amp-dk-e-relabel.png` show R373's split pair, FR-6's ⋯ and the relabelled
primary on one 1120 px row with room to spare. A, B and C would each move something on the desktop
as well.

**A is the honest second**, and it is the owner's real choice if vertical space on the phone matters
more than the remembered pick: it is the most consistent of the five and the calmest row. Its one
line of loss is in the document. It would need its primary shortened from *Play album* to *Play* to
survive Faroese — at which point an editions album and a plain one are literally the same row, which
may be a feature rather than a concession. **That is a direction, not a drawing choice, so I have
not picked it.**

---

## Open, and back to you

1. **{k}, the extras count.** Drawn as *+ 3 extras*. The brief named no number and the playlists
   round drew none. It is the only number this round invents; `amp-data.js` says so where it is
   defined. The album stays 10 official songs · 39 min, which is the brief's.
2. **The nine album track titles** beside *Northern Lights* are still the music mockup's corpus,
   carried over from the playlists round, which asked for the real stand-ins and has not had them.
   Nothing here depends on them — they are below the fold in every frame — but the ask is still open.
3. **R373's phone row truncating today** is shipped behaviour. Whichever option is picked, *Shuffle*
   is **Sh…** on a 360 dp phone right now and has been since R373 landed. Whether that is its own
   task is yours; it is not something this round introduced and not something I can file.
4. **Two strings option A alone would need** — `ed.play_extras_row` / `ed.shuffle_extras_row`, with
   en/da/fo drafts in `amp-data.js` and in the document, marked DRAFT. They are needed only if A is
   picked.
5. **In E, should the second row's *Shuffle ▾* fill the row** (as drawn, ⋯ flush right) or keep
   R373's `.72` share with the ⋯ beside it and dead space at the end? Drawn the first way because
   the second looked like a bug; say if you want the alternative drawn.
6. **The ⋯ on an album with no editions.** A, D and E keep it on the action row; B and C move it off
   that row on *every* album, including plain ones, which is inherent to them. Confirm that reading
   of B and C before putting them to the owner.

---

## Lane

- Drawn under `design/incoming/2026-10-23-album-more-placement/`, nothing else touched.
- Rendered with `scripts/render-design.sh` (DEB-2125), six invocations plus one 4× zoom.
- **Every one of the 38 PNGs opened one at a time**, and two at 4×.
- No phase number and no task id anywhere in the drawing.
- No network reference: the faces resolve out of the linked shipped stylesheet's own `fonts/`.
- Canvas rules observed — no `content-visibility`, no `backdrop-filter`, no large `filter: blur`,
  no infinite animations. The production `.mu-dback` carries `backdrop-filter: blur(6px)`; the
  linked round already draws it as a flat `rgba` fill and the ⋯ this round puts opposite it in
  option B matches that flat fill rather than the live blur.
- Nothing arrived in this round's inputs that must never land under `design/`.
- The four fences run on the staged directory.
