# Phone episode download — round report

Drawn 2026-11-10. Surface: the **Ravilo Android phone's series page only**, 412 × 915 dp portrait,
linking the shipped `design/ravilo/mobile/ravilo-phone.css`. No TV, desktop, web, admin or cast
frame exists in this round — no other surface has a download control at all (FR-1).

Kind of round: **product design**, as the brief states. It lands under `design/incoming/`.

- `frames.html` — every frame, parameterised `?set=page|rail|sheet|multi|season|study&lang=en|da|fo`
- `ed-data.js` — the one array every number, state and word comes out of
- `ed-kit.js` — the builders
- `episode-download.css` — the round's own rules, on top of the shipped phone stylesheet
- `Phone Episode Download - Directions.html` — the directions document, the Compose port's reference
- `png/` — 53 PNGs, `phone-episode-download-<direction>-<state>[-lang].png`

Every PNG was opened one at a time and magnified before this was written.

The brief stood alone: the question as a choice, one surface, the constraints as excerpts, the
stand-in named exactly, the states, the open decisions each as a lean, no phase number and no task
id in anything the round must draw. Nothing went back.

---

## The picks

**1 · Where the control sits: direction A, "under the card" — the brief's lean.**

The under-card line becomes 320 × 44 dp with the Watched toggle at the start and the control at the
end. The alternative I drew is **B, the control inside the card's text area**, which is the only
shape that costs no vertical height at all. B loses on three things the pictures show and no
sentence would have:

- `b-page-tall` — the control sits **inside** the card, so on a watched episode it inherits the
  card's shipped 62 % dimming and goes grey. The one control that says "this is on your phone" is
  washed out on exactly the cards that have it.
- `b-rail-da`, `b-rail-fo` — the text column drops from 292 dp to 228 dp and **every** synopsis
  clips a word earlier, on all eight cards, in both long languages.
- The tap target is a 44 dp box inside the box whose tap plays. It has to consume the tap, and a
  1 px divider is the only thing on screen saying so.

B's 44 dp saving is real and is its whole case. It is paid on every card in every language.

**2 · Icon-only — the brief's lean, and for a stronger reason than the brief gives.**

No state of this control carries a word, so it has **one width in all three languages**. The
fixed-width-per-language rule the film page needed (FR-8 amended, because Danish squeezed Play to
82 dp) is not needed here at all — there is nothing to measure. 58 × 44 dp, not the 52 I drew
first: at 52 the 3× study showed "100 %" touching both edges.

**3 · The waiting states: half the brief's lean.**

The control shows **the clock for every wait**, which is the shipped
`DownloadPhase.controlState()` mapping and not this round's invention. But the brief's other half —
"the reason is on the Downloads page" — does not survive its own picture. Compare `a-rail-waits`
with `a-rail-waits-icononly`: with the control alone, *Waiting for Wi-Fi*, *Waiting for a
connection* and *Waiting for the server* are **one identical glyph**, and a failed episode is
distinguishable only by a small amber triangle 180 dp below the thumbnail.

So the card says which wait it is, **in the air date line's place** — never a new line, never on
the picture, no extra height, and only while the download is not at rest. A resting card keeps its
air date and is exactly today's card. This costs nothing and is the difference between a viewer
learning why nothing is happening and a viewer being sent to another page to find out.

---

## ⚠ The Episodes header already overflows on the shipped phone, in English, at 412 dp

**This is the one thing to settle before the amendment, and it is not caused by this round.**

`EpisodesHeader()` puts *Episodes*, the watched count, the *Download season* chip and *Shuffle* on
one row. That is about 438 dp of content on a 412 dp screen. `a-header-today` is the shipped
arrangement rendered as-is:

| | 412 dp | 360 dp |
|---|---|---|
| English | **breaks** — *Episodes* clipped to "sodes" at the left edge, the count under the chip's icon | **breaks** |
| Danish | just fits, Shuffle flush with the screen edge | **breaks** |
| Faroese | **breaks** — the count runs under the chip | **breaks** |

The chip and Shuffle have shared that row since the season chip was added, so this is shipped today and a viewer sees it today.
The round cannot draw the brief's state 4 — "the rail beside the *Download season* chip" — without
resolving it, so it drew three arrangements:

- **one row** (`a-header-today*`) — shipped, the table above;
- **the count stacked under the word** (`a-header-stackcount-360*`) — fixes 412 dp, still collides
  at **360 dp in English**, where the two chips alone are 234 dp;
- **the chips on their own 44 dp row** (`a-page*`) — **recommended.** One rule, no width threshold,
  no truncation, holds in all three languages at both widths, and a third chip later still fits.
  Costs 44 dp once per series page, on a page that scrolls.

**A decision is needed, and it is Bárður's:** whether this is a line in this round's amendment or a
separate fix. The pictures are self-consistent under the third reading.

---

## What only the pictures showed

Seven, and two of them are traps `PROCESS.md` warns about by name.

1. **The round-11 z-index trap, twice, in two different costumes.** First a **class-name
   collision**: the hero's fade and the sheet's dimmer were both `.ed-scrim`, so the hero inherited
   the sheet's `z-index: 70` and painted over the series title and the meta line. Renaming it to
   `.ed-hscrim` with `z-index: 1` did not fix it — the hero is `position: relative` with
   `z-index: auto`, so a child's `1` does not stay inside the hero; it competes at the page's level
   against the body's `auto`, wins, and paints the scrim over the title again. `isolation: isolate`
   on the hero is the fix. Two renders, and in the markup both versions look correct.
2. **A green check in the control collided with the green check in the Watched toggle**, 240 dp
   away on the same line, meaning two different things. The film page can use a check because the
   word *Downloaded* is beside it; this control has no word. Replacing it with a green **outlined**
   arrow then collided with the *Download* control's white outlined arrow — "on the phone" and "not
   on the phone" became the same shape in two tints. The answer is the shipped `DownloadedMark`'s
   own: **filled, not outlined** — `#0a0c13` on a solid `#f3f4fb`, the mark at control size, so a
   viewer learns one symbol and reads it twice on the same card. The `--good` variant is drawn
   beside it at 3× so the loudness is a pick from pictures, not from this paragraph.
3. **"100 %" did not fit the control.** At 52 dp the 3× study showed the number touching both
   edges. 58 dp. The number is reachable: round 1's own `shortLabels` measures "100 %", so the
   shipped system already treats it as a state the control can be in.
4. **The under-card line followed each card's own body height.** A one-line synopsis lifted that
   card's control 18 dp above its neighbours', so a rail of controls sat at several heights. A
   106 dp min-height on the card body puts them all on one baseline. This affects the shipped
   Watched toggle today, for the same reason.
5. **The study's own number block contradicted its own pictures** after the control grew from 52 to
   58 dp and *Downloaded* changed treatment. A reference whose figures disagree with the picture
   above them is worse than no reference.
6. **"Delete" above a button labelled "Delete".** The confirm sheet had a heading that repeated its
   own action button and never named the thing. The heading is gone; the sentence is the question.
7. **The *not allowed* frame still drew the control.** FR-2 says a viewer who may not download sees
   none of it. Suppressing the chip is not suppressing the control, and only the picture said so.

Two more that are judgement rather than defect: the "Every episode is on this phone" chip
overlapped the header title until the chips got their own row, and would have lost its comparison
for a reason that was not about design; and the chip-row fix was found only because the Faroese
360 dp frame was rendered, which is the width and the language the brief asked for by name.

---

## Open questions for Bárður

1. **The Episodes header overflow above.** Blocking for the amendment, not for the pictures.
2. **A running control has no second target.** FR-8 says what a *Downloaded* control offers and
   says nothing about a running one, so a viewer who starts a download from the rail cannot pause
   or stop it from the page they started it on — they must go to the Downloads page. I drew the
   sheet (`a-running-menu`) because the gap is visible the moment the rail exists, but it is a new
   behaviour and therefore not mine to decide. It needs one new string, `download.cancel_dl`.
3. **`download.paused` does not exist and FR-7 already assumes it does.**
   `DownloadPhase.statusKey()` returns `null` for a pause, so nothing anywhere says the word — but
   FR-7's acceptance 15 requires that a paused copy "still reads *Paused* five minutes later". The
   rail needs exactly that key. Three languages are in the directions doc.
4. **A paused control keeps its number, per the shipped mapping — the brief grouped Paused with
   the waits.** On the film page a status line disambiguates a pause; the rail had nothing, and a
   paused control with the accent fill is indistinguishable from a moving one in a still frame. I
   drained the accent and added *Paused* to the status line. If the spec wants Paused to read as
   *Queued* instead, that is a one-line change to `controlState()` and the number goes.
5. **What the delete confirm names.** I drew *Nordvest · S01E03*, never the episode's own title:
   *Grindadráp* alone does not say what is being deleted, and the corpus's episode titles overlap
   the film pool (the 2026-11-09 round hit that collision). If `{title}` should be the episode
   title, the string is unchanged and only the caller differs.
6. **A season already fully on the phone.** I recommend the chip is **unchanged** — FR-5's
   `download.season_have` already lets its sheet say 8 of 8 are there, so no new rule and no new
   string. The chip-as-statement alternative is drawn (`a-season-all-spent`) and now fits, since
   the chips have their own row; it needs `download.season_all`.
7. **The multi-episode card takes one control, and I am confident about it**, but it is the brief's
   question 5 so it is stated plainly: two episodes in one file are one physical download — one
   size, one progress, one queue entry. Two controls would be drawing two downloads of one file.
   `MultiEpisodeCard` has no `downloaded` parameter today and would need one, like `EpisodeCard`.
8. **The Watched toggle is 28 dp tall today**, below the 44 dp floor the brief sets for the new
   control. Direction A gives it a 44 dp row to sit in without changing its own look, which is an
   improvement the round gets for free — but the toggle's own hit area is the build's call, not a
   drawing's, so it is named here rather than assumed.

---

## States drawn, and states skipped

**Drawn:** the rail with every phase side by side in three languages; all three waits and a pause;
the same rail with no status line, for the comparison; a card that is 28 % watched and 42 %
downloaded; the page at 412 and 360 dp in three languages, plus the whole page; the *Downloaded*
control's Play / Delete choice and the delete confirm naming what it frees, in three languages; a
running control's sheet; the multi-episode card in both directions and three languages; a season
entirely on the phone, two ways; a viewer who may not download; the shipped header and two
alternatives at both widths; the control and the under-card line at 3×.

**Skipped, and why:**

- **Loading and empty** — the rail reads the phone's own disk for its phases and the episodes
  arrive with the page. Neither state exists on this surface.
- **Skins** — aurora only. Nothing here introduces a colour that is not a shipped token or one the
  2026-11-09 round already landed, so the other four should follow; they are untested and that is a
  fact, not a claim.
- **10-foot focus** — no TV surface in this round.
- **The iPhone bezel and landscape** — the surface is Android phone portrait (FR-1).
- **The hero's action row, and the page below the rail** — Play / My List was settled by the
  2026-11-09 round and this round must not redraw it; About, cast and related are unaffected by
  anything here. The page frames carry the hero, the synopsis, the Episodes header and the rail,
  and stop.
- **The hero at its shipped 340 dp** — the frames use 230 dp so the rail is in the picture. On the
  device the rail starts below the first screen, and the `tall` frames show the page.

## Canvas and fence notes

- The shipped `.dback` is `backdrop-filter` glass; the canvas cannot carry that, so the frames draw
  the back button as a flat 55 % tint. The live app keeps its blur.
- No `content-visibility`, no `backdrop-filter`, no large blur, no infinite animation anywhere.
- No network reference of any kind: the three faces are the repo's own woff2 and the render ran
  with `--network none`.
- **No `<defs>`, no gradient element and no SVG `id` exists in this round**, so the hidden-subtree
  paint-server trap cannot apply. Every fill is `currentColor` or a CSS background.
- The month names and the date order are tabled per language in `ed-data.js`: Chromium has no
  Faroese locale and `toLocaleDateString` falls back to English in silence.
- Nothing by a forbidden name is in these inputs: no `STATUS.md`, nothing under `specs/`,
  `scripts/`, `.github/`, no `*.gradle.kts`, no `i18n/*.json`. **Nothing was dropped from the
  brief's inputs** — there was nothing in them that does not belong here.
- The round carries no phase number and no `DEB-n` in any drawn string, file name or canvas label.
- Every title, person, size and date is the brief's stand-in or the shipped corpus in
  `design/ravilo/ravilo-data.js`. Nordvest Season 1 is hand-written there, not generated, so the
  generated-title collision the 2026-11-09 round hit cannot arise. No hostname, no IP literal, no
  real library title.
