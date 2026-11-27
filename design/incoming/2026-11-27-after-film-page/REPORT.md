# The page that opens when a film ends — round report

Round slug `after-film-page`, 2026-11-27. Product design (every instance ships it), so it lands
under `design/incoming/`. Drawn from Bárður's brief; the pictures and this report go back to him.

`After Film Page - Directions.html` is the directions doc — what is fixed, the two directions per
question, how they compare, the states, and the strings. **This file is the other half: what the
pictures showed that the markup did not, and what is still open.** They do not repeat each other.

---

## The two answers the brief asked for

**1. Logo or text — the logo when the film has one, text when it has not.** The brief's lean, and
it is already how two shipped surfaces behave: `.dhero-logo` on the title page falls back to
`.dhero-title`, and the phone player's `.mp-ident` carries a `logo` and a `name` variant of one
slot. Adopting the lean adds no third pattern.

**2. A visible back on a pointer client — yes, but the one that is already there.** The brief's
lean was a ghost *Back* top-left. Drawing it showed the problem: `header()` in
`design/ravilo/desktop/ravilo-desktop.js` already puts a `.ghb center` bar with a back chevron at
the top of `.main` whenever the navigation stack is not empty, so the lean adds a **second** back
control to a page that has one. The recommended variant uses the shipped header, which also names
the film in the bar. Both are drawn (`b-desk-1280-header` / `b-web-900-header` against
`b-desk-1280` / `b-web-900`), so this is a picture rather than an argument.

On the phone the answer is unambiguously yes — no Back key, no Esc — and **the TV draws none**, as
the brief leans.

---

## What only the pictures showed

Six, and the lane has no second pair of eyes, so they are listed with what each would have cost.

1. **The two options were indistinguishable.** The first logo stand-in was the title in caps with
   a small rule under it, and beside the text option the two PNGs were all but the same picture —
   a question put to the owner that his own pictures could not answer. The stand-in is now a
   proper lockup: wide-tracked lettering, a mark between two rules, its own proportions.

2. **…and then it was too small.** At 72 px the lockup's *word* came out a third shorter than the
   text option's, because a logo's height includes its mark and the line under it. Drawn that way,
   option B would have lost at three metres on a difference nobody was asking about. It is 96 px
   now: the word is ~42 px against the text option's ~40 px cap height.

3. **The logo was centred in its own box.** Magnified, the wordmark started 70 px right of the
   kicker above it and the heading below it — three things that should share one edge, on three
   different ones. A tightly cropped logo file placed with the shipped `object-position: left
   bottom` lands on the gutter, so the drawing had to. Only the 6× crop showed it.

4. **The portrait grid pushed the page off the top of the screen.** Two columns at 412 px makes
   each poster 181 wide and therefore 272 tall; three rows of those are 956 px in a 915 px screen,
   and the render had no kicker, no logo and no heading in frame at all. Three columns puts the
   poster back at 116 — within two of the shipped 118 — and six titles become two rows.

5. **The landscape back button sat on the words.** At top-left it landed squarely on the kicker
   and the logo: PROCESS.md's round-8 defect, in a frame the markup gives no hint about, because
   the body is bottom-anchored and at 412 px tall the bottom reaches the top. It is top-right in
   landscape now; that corner is empty in every landscape frame.

6. **`End of film 2024` reads as a date.** The first render carried the film's year in the kicker
   through the shipped `.hero-kicker .n`, as the title page does. The title page earns that number
   because it is deciding whether to watch; after the credits it is noise, and next to *End of
   film* it reads as when the ending happened. Dropped.

Two more things seen and deliberately **not** changed:

- **Every tile prints its title twice** — once across the art and once in the label beneath. That
  is the shipped no-artwork tile (`.tile .art .tt` plus `.tile .label`), and in production these
  tiles carry posters. Faithfulness to the shipped component beats a prettier picture; naming it
  here so nobody reads it as a design.
- **A logo's fine detail dies at phone size.** In the landscape frames the stand-in's sub-line is
  a 5 px smudge. That is a real property of logo art at 52 px, not a rendering fault, and it is a
  genuine cost of direction B on the phone.

---

## Where this round departed from the brief, and why

**The brief asks for "the light theme for 1 and 3" — state 1 is the TV, and the TV has no light
theme.** `design/ravilo/platform/ravilo-shell.js` holds `TV_SKINS = ['aurora','midnight','noir']`
and prints *"The TV is dark-only: it shows the viewer's dark pick (Aurora here)"* for any light
pick; `i18n/en.json` carries `theme.dark_only` = *"This TV is always dark"*. **Noir** is rendered
in its place — it inverts the accent to amber and is the skin that actually tests the kicker and
the focus ring — and **Midnight** beside it. The phone's light theme (Daylight) is drawn as asked.

**In Daylight the page itself stays dark on every surface, and that is correct.** A surface sitting
on artwork keeps the dark tokens — `html[data-appearance="light"] .hcard, .mp` in
`ravilo-phone.css`, `.lt .hero .hb` on desktop — and this page is entirely artwork. What changes in
Daylight is the chrome around it: the rail, and the window header in the recommended variant. Said
here because a reviewer comparing `b-ph-port-grid` with `b-ph-port-grid-daylight` will otherwise
read two identical pictures as a failed render.

**Three frames the brief did not name were drawn**, because each is a decision somebody has to make
and a picture is cheaper than a paragraph: the app bar variant (`b-tv-appbar`), the zero-picks case
(`b-tv-empty`), and the scrolling-row alternatives on phone portrait and Ravilo Web
(`b-ph-port-track`, `b-web-900-scroll`).

---

## Open — these need Bárður, not me

1. **What happens when nothing in the list is unwatched?** "Up to 6" includes 0, the brief names no
   such state, and the spec excerpt does not say. `b-tv-empty.png` shows what the page is if it
   opens with nothing: a heading over nothing, and **no focus target at all**, which the
   constitution's *"must never strand focus"* forbids outright. The frame prints no sentence on
   purpose — writing one would be deciding it. *A lean, not a decision:* the page does not open,
   and the player closes to the film's page as it does today.

2. **Does the app bar belong on this page?** `b-tv-appbar.png` is the picture. *Recommended: no* —
   the page came out of fullscreen playback and offers one decision, and the bar puts four more
   targets in frame with *Library* drawn as current, which at three metres reads as a second focus.

3. **Does a dark logo need the light plate?** R257/R308 found 88 of 135 production logos are dark
   ink on transparency and gave the Studios tiles a light plate for it (`.taxo-card:has(.taxo-logo)`,
   `.taxo-logo.reink`). This page puts a logo on a film's own backdrop, which can be anything. Out
   of scope for a layout round; worth a line in the spec.

4. **Is the *More Like This* list already in hand when this page opens?** The round assumes yes —
   it arrives with the film's own page — and therefore draws no loading state. If the
   implementation fetches it at this moment, there is a loading state nobody has drawn.

---

## The stand-in corpus

**None of the seven titles is in `design/ravilo/ravilo-data.js`.** All seven are the brief's own
stand-ins, named exactly, and the brief is the authority on stand-in data (PROCESS.md step 1). They
are written out verbatim in `afp-data.js` and nothing else is invented. Their backdrop colours and
initials still come from the corpus's own `RAVILO.grad()` and `RAVILO.initials()`, so they sit in
the same colour world as every other Ravilo mockup. The cost is that these pictures cannot be
diffed title-for-title against an earlier round; worth knowing if the corpus is ever extended.

## Nothing was dropped from the inputs

No `STATUS.md`, nothing under `specs/research-reports/`, nothing under `scripts/`, no `CLAUDE.md`
arrived in this round's inputs, so nothing had to be dropped. Nothing in the brief read like it was
directing an agent.

## Fences, and what they cover

The round was staged and all four run on the staged tree:
`check-deanonymization.sh`, `check-phases.sh`, `check-css-scoping.sh`, `check-mobile-css.sh`.

Read by hand as well, because no fence can see these: every string literal in every file; every
`http`/`https` literal (**there are none** — the three faces resolve to `design/app/fonts/` and
`ravilo.css`'s Google Fonts `@import` is answered from the same directory by
`scripts/render-design.sh`, DEB-2922); and every one of the 76 PNGs, one at a time, with the first
tile and the identity block magnified.

**Nothing here is production CSS.** `design/ravilo/` is reference only, which is why every hex, px,
weight, radius and easing is written out in `after-film.css` for the Compose port. The round carries
no phase number and no task id in any drawing, and no file mints a number.

**Canvas rules held:** dark ink on the light-gray backdrop for every word printed on the canvas
itself; no `content-visibility`, no `backdrop-filter`, no large blur, no infinite animation. One
note on that last point — the shipped phone back control `.dback` **is** `backdrop-filter: blur(6px)`
and a blur breaks the screenshot render, so `.afp-phback` draws the same circle with a flat fill.
The glass belongs in the Compose port; it is only absent from the picture.

## Two shipped rules this round narrowed, named so they are not mistaken for production changes

- `.afp-tv .hero-bg` — the shipped hero backdrop made full-bleed instead of 620 px tall, because
  on this page the backdrop *is* the page and not a hero band.
- `.afp-ph .track` — the shipped phone track's bottom padding raised from 2 px to 6 px, because a
  label flush with the screen's floor reads as clipped when the row is the page's last element.

Both are scoped to this round's frame class. No shipped token and no shipped component is redefined.
