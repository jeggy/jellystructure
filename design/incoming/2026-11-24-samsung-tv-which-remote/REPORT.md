# Which remote do you have? — design round report

Round `samsung-tv-which-remote`, drawn 2026-11-24. Surface: the **Ravilo Android phone only**,
412 × 915 dp portrait — Profile → *Samsung TV* → the guide, step 1 *Open the hidden menu*. Brief
from Bárður on this round's task; the behaviour it draws is FR-13 of
`specs/ravilo/requirements/samsung-tv-install-from-the-phone.md`, amendment 2026-11-24.

Product design, so it lands under `design/incoming/`. No TV, desktop, web, admin, cast or Tizen
frame exists in it: the Profile row that opens this guide is null on every platform but an Android
phone (FR-1), so no other surface has this screen at all.

## What came home

| File | What it is |
|---|---|
| `Samsung TV Which Remote - Directions.html` | The directions document: what is fixed, the three directions, how they compare, every state the recommended direction holds, the decided rows, the strings, what is still open |
| `frames.html` | Every frame, parameterised by `?dir=&set=&lang=&skin=&fs=&tall=` |
| `swr-data.js` | Every string, state, model code and colour. One array; nothing downstream states a number |
| `swr-kit.js` | The two remote drawings and the three screen builders |
| `samsung-which-remote.css` | The round's own rules, on top of the **linked** shipped `ravilo-phone.css` |
| `png/` | 51 PNGs, `samsung-tv-which-remote-<direction>-<state>[-dark][-da/fo][-200][-tall].png` |

The shipped stylesheet is **linked, not copied**, so every token, radius, weight and font in the
pictures is the production one. Nothing here is production CSS: `design/ravilo/` is reference, so
every hex, px, weight and radius is written out for the Compose port.

Rendered with `scripts/render-design.sh` (16 invocations, one per parameter combination), viewport
520 × 1000 for the phone frames and 1900 × 1050 for the strips.

## The three directions

**A — Two remotes (recommended).** The question is the screen's headline; the two drawn remotes are
two tall cards side by side. After the tap the *same* drawing, bigger, is the step's illustration
with the keys to press lit. The switch is the guide's own quiet link. Its one new component is the
two-remote choice; its one geometry change is step 1's illustration slot, 220 × 150 → 220 × 260 dp
on this step only. It is first because the whole decision is *long remote or short remote*, and A
spends the most of the screen on exactly that, twice — once to choose by, once to confirm by.

**B — Keys up close.** The question is the headline; the choices are two full-width rows with a
small drawing at the left and a line naming the remote. The instruction's illustration is not the
remote but the **keys**, cropped into the shipped 220 × 150 slot at four times A's size. It buys
the clearest possible "press these"; it costs the silhouette on the instruction screen — a viewer
who chose wrongly has less to notice it by — and two extra strings.

**C — Remote tabs.** The smallest change to the shipped screen: step 1 keeps its title on every
state, the question is a prompt line, the drawings stay at 220 × 150, and after the tap the choice
becomes a two-tab control under the step indicator — which is where the viewer sees what they chose
*and* how they switch, so no quiet link is needed. It buys continuity and one affordance instead of
two; it costs prominence (a tab bar on a screen whose one action is *Next*) and size: at 220 × 150
the Number/Color key is a speck, and its marking is then carried by the body text alone.

Each direction answers all three of the brief's open rows differently, so the pick is one choice and
not three.

## What only the pictures showed

Six things, none of which any fence can see. Listed because this is the lane's one human check.

1. **Both choice cards were drawn lit.** The question state had 1–5 in accent and the Number/Color
   key ringed before anything had been chosen — it read as a selection already made. The drawings
   now take a `lit` flag and the choice passes false.
2. **The two remotes were drawn to the same height,** which flattened the one cue the whole screen
   rests on: both read as "a remote" and only the keypad told them apart. They now share a *scale* —
   300 units against 212 — so the basic remote is visibly the longer object at every size.
3. **Direction B's detail crop painted outside its slot,** over the step indicator and across the
   title, because the face rect deliberately runs past the viewBox to *be* a crop and the SVG was
   set `overflow: visible`. This is round 8's pictures-overflowing-their-card.
4. **The press halo was two outlines around one target.** Magnified, the accent ring outside the
   lit Number/Color key read as a double border, and on the keypad the halos chained across 1-2-3,
   whose centres are 19 units apart with no room for a 10-unit ring. That is round 11's
   two-rings-read-as-two-targets and it is invisible at 1×. Halos removed: the accent *is* "press
   this", drawn once — a fill where the key can carry it, a ring where the key's own marking must
   stay readable.
5. **The remote's face vanished into the page.** Filled `--bg`, it was the same colour as the screen
   behind it in the instruction states: direction B showed two side rails and a floating keypad. The
   face now takes `--card` on the page and `--bg` inside a card.
6. **At 200 % font, *press 1 2 3 4 5* broke as "1 2 3" / "4 5",** which reads as two numbers to type
   rather than one sequence of five keys. Fixed in the **string**, not the layout: the spaces between
   the digits are U+00A0 in all three languages. It is invisible in the file, so it is named here —
   whoever writes `en/da/fo.json` must keep them.

Two more, found and fixed by looking rather than by a rule: the Number/Color key is drawn larger
than its true proportion, because at its real size its digits and dots were a smudge and a remote
whose one named key is unreadable is a photograph, not an instruction; and the stacked large-font
cards carry a 124 dp drawing rather than 104 dp, because at 104 the long-against-short cue had
almost gone for exactly the viewer who needs the picture most.

## States drawn, and states skipped

Drawn, per the brief: the question; number keys pre-2024; no number keys pre-2024; no number keys
2024+ (the longest body) — for all three directions, light, English. The recommended direction also
in **dark**, in **`da`** and **`fo`**, at the **200 % font setting**, and at **`fo` + 200 %** (the
brief's state 5: both choice labels fit, breaking at the existing hyphen in *tal-knappar*), plus
dark + `fo` + 200 %. Every 200 % state also has a `-tall` frame that renders the whole screen, so
"is anything clipped below the fold?" is answered by a picture rather than by assertion.

Two studies: `x1`, the fourth body FR-13 writes and the brief's list does not hold (number keys on a
2024+ TV); and `x2`, the side-by-side choice *forced* to stay side by side at 200 %, which is the
evidence behind A's stacking rule.

Skipped, with the reason — **loading, empty, error, offline** do not exist on this screen: it reads
no server state, holds no socket and has nothing to load. **10-foot focus** does not apply; this is a
phone. Directions B and C are drawn in English light only, as the brief says.

## Things the implementer should know

- **The screen does not name the TV.** No step of the shipped guide does, and inventing a TV line
  here was out of the round's scope. The two stand-in TVs differ only in which body text the screen
  carries; their names and models appear in the strip captions, which are canvas, not phone.
- **At 200 % the longest body runs past the fold** and the viewer scrolls to reach *Next*. That is
  the shipped page's own behaviour (the guide column scrolls); the `-tall` frames show nothing is
  clipped or overlapped.
- **Touch targets:** the back chevron, both choice targets, the tabs, the primary button and the
  quiet link are 44 dp or more on their smaller axis. The shipped quiet link is 13 sp with 8 dp
  padding, about 37 dp; this round draws it at 44. The back control is likewise the phone app's
  stroked chevron in a 44 dp target rather than a 26 sp `‹` — both reported as changes, small as
  they are, rather than slipped in.
- **No photograph, no Samsung logo, no model number** appears in either drawing. The four key
  colours are data (`SWR.KEYCOLORS`), because the viewer matches them against a real remote, and
  none of them is the brand accent.
- Every SVG id is minted from a counter. The round defines no paint server at all, so the
  hidden-subtree trap cannot bite, but the counter is there so adding one cannot reintroduce it.

## Still open — for Bárður

1. **What the title says in the question state.** The brief does not name it. A and B make the
   question the H1 and drop *Open the hidden menu* until a remote is chosen; C keeps the step title
   and puts the question under it. A real choice, and not one this round should make alone.
2. **Two markings on one key.** The Number/Color key is drawn with `123` *and* four coloured dots,
   because the instruction names both. If the owner's own set labels it one way only, one of the two
   can come off the drawing and out of the sentence.
3. **The question screen is about half empty** at the default font: it is top-aligned like every
   other guide screen and, per FR-13, has no primary button. Centring it would balance the screen and
   break step 1's alignment with steps 2–4. Left top-aligned, deliberately — worth a word from the
   owner when he sees it.
4. **Direction A at 200 % becomes direction B's row shape.** If A is picked, that stacked row is what
   its large-font state looks like, so the two directions are not fully independent. Worth saying out
   loud before the pick rather than after.

Nothing forbidden turned up in the round's inputs: no `STATUS.md`, nothing under
`specs/research-reports/`, nothing under `scripts/`, no `CLAUDE.md`. No network reference of any kind
is in anything landed, and no `http`/`https` literal appears in the round at all. The two model codes
are real Samsung product codes and neither is the household's; no library title, filename, host or
address appears anywhere.
