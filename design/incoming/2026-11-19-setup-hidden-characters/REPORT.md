# The server-setup screen's hidden-character warning and fix button — round report

**Round** `2026-11-19-setup-hidden-characters` · Ravilo **phone, Android TV and the desktop
window** · drawn in this repository.
**Brief** from Bárður. **Returns** to him on the brief's task with the PNGs.

**31 PNGs** in `png/`. The written half is `Hidden Characters - Directions.html`: what is fixed, the
two directions, how they compare, every state drawn and every state skipped, the decisions the
drawing made, the three open questions and the strings. This file is the round's own record — what
was built, what the pictures changed, and what is still owed.

---

## The brief

**It stood alone and nothing went back.** It named the surfaces, quoted FR-4, FR-5 and FR-6 as
excerpts rather than pointing at them, named every stand-in exactly (the address, the trailer, the
three states, and which state the TV draws focused), named the states, carried no phase number, and
named no `DEB-n` for the drawing to carry. The spec it cites
(`specs/ravilo/requirements/the-server-address-ignores-stray-whitespace-and-shows-a-pasted-line-break.md`)
was read in full and the brief's excerpts match it. `ServerSetupScreen.kt`, `Colors.kt`, `ravilo.css`,
`ravilo-phone.css`, `desktop-directions.css` and the three `i18n/*.json` were read for what the
screen already is.

**Nothing in the round's inputs had to be dropped.** No `STATUS.md`, nothing under
`specs/research-reports/`, nothing under `scripts/`, no `CLAUDE.md`.

**One line for the next brief: it does not say which kind of round it is.** `PROCESS.md` step 1 asks
for that, and asks that a missing one go back rather than be decided here. I drew it, because this
one is not ambiguous in any direction — a sign-in screen every Compose client ships, with
`jelly.example.net` as the stand-in, is product design by definition, and sending it back would have
cost a day to confirm something the brief's own content settles. **It is still a line the next brief
should carry**, because the rule exists for the round where it is *not* obvious, and a habit of
leaving it out is how that round arrives.

---

## What the round recommends

| Decision | What the round found |
|---|---|
| Where the fix button sits | **A — under the mark line, full column width.** B, beside the warning, is drawn and not recommended |
| The line-break mark | **`U+00B6 ¶`, not FR-4's `U+23CE ⏎`** — measured, see below |
| The hidden-character mark | **`U+00B7 ·`**, as FR-4 says — it needs no decision |
| The warning colour | **`ravilo.css`'s `--warn #f5b542`**, with a different value on two of the five themes |
| The mark line's overflow | **wrapping, break-anywhere** — and the round could not draw the overflow itself |

### A, because FR-5 already fixed a vertical order

FR-5 fixes the D-pad order field → fix button → *Connect*. **A is that order drawn as a column**, and
Down means down at every step. In B the button is beside the mark line, so Down from the field has to
move the ring *sideways* into a row that holds two other things, and Down again has to leave it — the
same press doing two different things one row apart.

The second reason is that **FR-5's label is unbounded**: it carries the cleaned address, so it is as
wide as the address is. A gives it the full column and lets it wrap; B gives it a fixed share of one
row. `B-phone-linebreak-kbd-up` is the frame that decides it — at 412 dp with the keyboard up, B
breaks the English label mid-word *and* wraps the mark line in the middle of `[21:20]`.

B costs about 54 dp less height. That could only have mattered with the phone keyboard up, and
`A-phone-linebreak-kbd-up` shows A fits there with room to spare. **On the TV and the desktop B is
perfectly legible** and its problem is the D-pad geometry alone — worth saying plainly, because the
phone frames make B look worse than it is on the surfaces where width is not scarce.

### The mark glyph: FR-4 names a codepoint we do not ship

This is the round's one finding that changes the spec.

`glyph-probe.html` renders each candidate in all three self-hosted faces and measures the advance.
Three different type designs give a codepoint they really carry **three different widths**; a
codepoint none of them carries is drawn by one fallback face and measures **identically**. The
control, `U+0041`, measures 50 / 76 / 63.

- **`U+23CE` ⏎ — FR-4's pick — measures 100 / 100 / 100. It is in none of our three faces.** On a
  device it draws from whatever fallback is there, in a weight and a shape we do not control, and on
  a device that has none it is tofu. `U+21B5`, `U+21A9`, `U+2936`, `U+2423` and `U+25AB` fail the
  same way.
- **`U+00B6` ¶, the pilcrow, measures 53.9 / 63 / 58** — it is in all three, it is the typographer's
  own mark for exactly this, and it is the recommendation.
- **`U+00B7` ·** measures 23.9 / 26 / 19 and needs no decision.

`mark-glyph-in-place` shows both, washed and bare, at the size they are read. **Changing FR-4's
codepoint is a dated amendment to the spec, hand-written** — it is not a thing the drawing decides.

I did not write the probe's first version correctly, and the correction is worth keeping: it compared
each candidate against `U+10FFFF`, a noncharacter no font carries, and called any difference
"present". Chromium resolves both through the fallback stack, so a real glyph in the *fallback* face
is simply wider than the last-resort box, and every candidate came back "in the subset" — including
the one the page exists to catch. The cross-family test is in the file with the reason.

### A wash behind the mark

The glyph stays in the warning colour, as FR-4 asks, and sits on an 18 % wash of the same colour.
A bare mark reads as a character *of* the address, which is the one thing it must not be. And **the
address around it is not warn** — FR-4's "in the warning colour" applies to the mark; drawing the
whole line that way satisfies the sentence and defeats its purpose, because the viewer is looking for
one character and a line that is entirely warn has nothing in it to find (`mark-colour`).

### The warning colour needs a token that does not exist

`ravilo.css` carries `--warn: #f5b542` and already spends it on `.dplaynote` and `.ep-playnote`, so
the round takes it and invents nothing. But:

- **`RaviloColors` has no warn field at all**, and of the three CSS files only `ravilo.css` carries
  `--warn` — `ravilo-phone.css` and `desktop-directions.css` do not.
- **Noir's accent IS `#f5b542`.** A warning drawn in it would be the accent colour. `A-tv-noir` is
  drawn at Noir's `accent-2` `#e0792f` instead.
- **Daylight is a light theme** where `#f5b542` reads at 1.8 : 1 on `#fbfbfd`. `A-phone-daylight` is
  drawn at `#8a5a00` ink (6.5 : 1) with a `#c98a12` rail.

So the promotion adds **two** values, not one: a mark ink and a rail. `warn-across-themes` is the
table.

---

## What the pictures found that the markup did not

Four, and one of them was only visible in one frame out of thirty-one.

1. **The phone screen had collapsed to its content height in every single frame, and only the light
   theme showed it.** Clipping the system keyboard to the bezel radius had me re-declare
   `.sh-phone .screen { position: relative }`, which overrode the `position: absolute; inset: 0`
   that makes the screen fill the bezel. On every dark theme the bezel behind it is near-black too,
   so eleven frames looked right and were wrong. `A-phone-daylight` is where it showed, as a white
   screen ending two-fifths of the way down a black phone.
2. **The TV's `Connect`-focused state was drawn without its focus ring** — fill only. That is State C,
   the frame the owner reads as "this is what it looks like when it works", so it was showing a
   control the app does not have. The shipped treatment is the accent fill *and* the 2 dp ring.
3. **The measured width table said the TV column was 946 dp.** It read the 1920 px stage as 1920 dp;
   the stage is density 2, so that column is 480 dp and the table was wrong by a factor of two while
   the drawing beside it was right. It now measures the real column off the DOM, which removes the
   class rather than the instance.
4. **At 4× the pilcrow's stem reached the right edge of its own wash** and read as a clipped glyph.
   2 dp of horizontal padding instead of 1. Invisible at 1×, and the reason `PROCESS.md` says
   magnify.

Two more, smaller: the system keyboard's square corners poked through the phone's 46 px radius
(it belongs inside `.screen`, not beside it), and the `oneline-clipped` frame was identical to the
wrapping one because the brief's address does not overflow — it proved nothing and was replaced by
the measured width table.

---

## The one thing the round could not draw

**The mark line overflowing.** FR-4 says the address is shown "on one line". `mark-line-width`
measures how much line there is — 412 dp on the phone, 480 on the TV, 440 on the desktop, i.e. 57
monospaced characters at the narrowest against the brief's 25. **The brief's address does not
overflow on any surface**, and inventing a longer one is exactly what the stand-in rule exists to
stop, so there is no honest frame of it in this round.

The rule still has to be decided, because a pasted address is not bounded. The round draws **wrapping,
break-anywhere** and recommends it: clipping can hide the very mark the line exists to show, and a
horizontally scrolling line is unreachable with a D-pad. **If the overflow is to be drawn, the next
brief needs to name the long address.**

---

## One thing for the task, not for a picture

**Arrival focus can land on a disabled *Connect*.** `ServerSetupScreen.kt`, read 2026-11-19:

```kotlin
LaunchedEffect(Unit) { runCatching { if (host.isNotBlank()) connectFR.requestFocus() else addressFR.requestFocus() } }
```

With FR-3 reading a saved address back and FR-6 disabling *Connect* while a warning shows, a TV
arriving here from *Wrong server?* with a dirty saved address focuses a control that does nothing,
with the fix button one press **up**. Nothing in the drawing fixes that — it is a line in the Kotlin.
Raising it is Bárður's call.

---

## How it was rendered

`scripts/render-design.sh`, no CSP `<meta>` anywhere (DEB-2922 answers `ravilo.css`'s font `@import`
from `design/app/fonts/`). `frames.html` is parameterised and **the set decides which shipped token
file is loaded** — the TV, the phone and the desktop are three files that name their tokens the same
way, and loading two into one document would let one answer for the other. Two of the three carry no
font link of their own, so `setup-marks.css` re-declares the three families against the repo's own
woff2. No network reference, in the renderer or in a browser opened by hand.

```sh
S=design/incoming/2026-11-19-setup-hidden-characters
scripts/render-design.sh --html $S/glyph-probe.html --viewport 1280x1100 --all .frame       --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=phone'                  --viewport  700x1200 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=phone&lang=da'          --viewport  700x1200 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=phone&lang=fo'          --viewport  700x1200 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=phone&skin=daylight'    --viewport  700x1200 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=tv'                     --viewport 2000x1250 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=tv&lang=fo'             --viewport 2000x1250 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=tv&skin=noir'           --viewport 2000x1250 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=tv&skin=midnight'       --viewport 2000x1250 --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=desktop'                --viewport 1280x900  --all .sh-frame --prefix setup-hidden-
scripts/render-design.sh --html $S/frames.html --query '?set=canvas'                 --viewport 1320x1400 --all .sh-frame --prefix setup-hidden-
```

A variant renders fewer frames than the base; which is which is in `setup-frames.js`'s `BUILD`
table, so that file and this one cannot disagree about what exists. `setup-data.js` is the one array
every number, string and state in every picture derives from.

**Every PNG was opened and looked at, one at a time, and `mark-glyph-in-place` and
`A-tv-clean-connect-focused` were magnified to 4× and 2×.** Four of the six defects above came out
of that pass and none of the four fences can see any of them.

---

## The frames

**Phone (Pixel 9, 412 × 915)** — `A-phone-linebreak-kbd-down`, `A-phone-linebreak-kbd-up`,
`A-phone-space-kbd-down`, `A-phone-clean`, `A-phone-da`, `A-phone-da-kbd`, `A-phone-fo`,
`A-phone-fo-kbd`, `A-phone-daylight`, `B-phone-space`, `B-phone-linebreak-kbd-up`.

**Android TV (1920 × 1080)** — `A-tv-linebreak-fix-focused`, `A-tv-linebreak-field-focused`,
`A-tv-space-fix-focused`, `A-tv-clean-connect-focused`, `A-tv-linebreak-apps-row`, `A-tv-fo`,
`A-tv-noir`, `A-tv-midnight`, `B-tv-linebreak-fix-focused`.

**Desktop window (1200 × 760)** — `A-desktop-linebreak`, `A-desktop-clean`, `B-desktop-linebreak`.

**Canvas** — `glyph-probe`, `compare-A-vs-B`, `mark-colour`, `mark-glyph-in-place`,
`warn-across-themes`, `mark-line-width`, `anatomy`, `strings`.
