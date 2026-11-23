# Samsung TV overview — round report

Drawn 2026-11-23. Surface: the **Ravilo Android phone only**, 412 × 915 dp portrait — Profile →
*Samsung TV* → the guide's *Your Samsung TVs* screen. Linking the shipped
`design/ravilo/mobile/ravilo-phone.css`. No TV, desktop, web, admin, cast or Tizen frame exists in
this round: the Profile row that opens this guide is null on every platform but an Android phone
(FR-1), so no other surface has this screen at all.

Kind of round: **product design**, as the brief states. It lands under `design/incoming/`.

- `frames.html` — every frame, parameterised
  `?dir=a|b|c&set=states|studies|strip&lang=en|da|fo&skin=daylight|aurora&fs=100|200&tall=1`
- `stv-data.js` — the one array every TV, version, count, state and word comes out of
- `stv-kit.js` — the builders; the badge, the version lines and the button are **derived** from the
  three fields the real app has, never stated in the data
- `samsung-tv-overview.css` — the round's own rules, on top of the shipped phone stylesheet
- `Samsung TV Overview - Directions.html` — the directions document, the Compose port's reference
- `png/` — 78 PNGs, `samsung-tv-overview-<direction>-<state>[-dark][-lang][-200][-tall].png`

Every PNG was opened one at a time and magnified before this was written.

The brief stood alone: the question as a choice, one surface, the constraints as excerpts from the
spec, the stand-in named exactly, the eight states, the three open decisions each stated as a lean,
the kind of round named, and no phase number and no task id in anything the round must draw.
Nothing went back.

---

## The picks

**1 · Cards, with the approved TV's card larger — direction A, the brief's lean.**

A is recommended, and the comparison that decides it is not aesthetic. Three things the pictures
show:

- **B's button cannot say which TV it acts on.** Pinned to the bottom, it is 680 dp from the row it
  belongs to (`b-s2-update`), and the only thing joining them is a 12.5 sp name line above the
  button that I had to add for B to be legible at all. A's button is inside the card.
- **B is the one direction that breaks on two approved TVs.** The cap is ten requests, so two
  approved sets is allowed, and one dock has one slot. C has the same problem one step later: its
  second approved TV drops to a row with no button. A grows by one card.
- **C costs five new headline strings and retires one of the spec's own.** *Comes with the next
  Ravilo release* and *Waiting for the next release* printed one under the other in C's first
  render, and I dropped the spec's sentence to fix it. On the update state C still says it twice —
  *Update waiting* over *Update to 1.71*, 100 dp apart (`c-s2-update`).

**C is the better-looking of the three** and it is the most at-a-glance — the state is a 22 sp
Space Grotesk headline, which is exactly what the owner asked to be able to read in one look. If he
prefers it, the two costs above are the price and they are both real. B is a settings list; it is
the cheapest and the densest and it is in the set because the brief asked for the alternative, not
because I would ship it.

**2 · Two labelled version lines — the brief's lean, and the pictures make the case.**

B's one-line `1.70 › 1.71` is 44 dp shorter and unlabelled, and `b-s5-unknown` is what that costs:
the not-known state renders as `— › 1.71`, which says nothing. A's `On the TV —` / `Latest 1.71`
says it. At the 200 % font setting the labelled pair still fits on two lines; the arrow line
survives too, so the saving is real, but it buys nothing a phone screen with this much empty space
needs.

**3 · The button inside the card — the brief's lean.** See pick 1.

## What only the pictures showed

Nine. Every one of them was fixed and re-rendered, and the reason is in a comment beside the rule.

1. **A *Not set up* TV sat above the viewer's own approved one** (`a-s7`). The order was the data's.
   The round now ranks Approved/Ready → Waiting → Not approved → Not set up, with the server-only
   rows below every found TV. The spec fixes only the second half of that.
2. **With no Wi-Fi, a card said *Not found on this Wi-Fi*** directly under a notice saying the phone
   is not on a Wi-Fi (`a-s8`). No scan ran, so no card may claim one. The footer there is the
   shipped *Try again*, not *Search again*.
3. **At 200 %, the Faroese pill wrapped to two lines inside itself and truncated the TV's name to
   *Living R…*** (`a-s4-two-dark-fo-200`). A pill that wraps reads as two pills — the round-11
   defect. The pill never wraps now, and the name column has a floor that scales with the font, so
   the pill drops to its own line instead.
4. **At 200 %, *Comes with the next Ravilo release* was clipped at the card's right edge** —
   the picture read *…next Ravilo* with the last word cut off (`a-s6-nextrelease-200-tall`). It was
   a `flex: none` child in a label/value flex row. It is a sentence now, and wraps.
5. **At 200 %, *Wi-Fi* broke at its hyphen** into *Wi-* / *Fi*, in all three languages. In Compose
   that is a non-breaking hyphen in the string.
6. **On the Daylight theme the cards dissolved into the page.** `--card` (#f1f2f6) sits 2 % from
   `--bg` (#fbfbfd); only the big card had a hairline and the Bedroom card had no edge at all.
   Every card carries the hairline now.
7. **The skeletons were invisible on Daylight** — a 55 % opacity `--card` over a near-identical
   background. They are an `rgba(--fg)` tint now and read on both themes.
8. **The struck-through Wi-Fi glyph was illegible at 16 dp** and appeared three times on one
   screen. Dropped from the card line; it stays in the status bar and in the 20 dp notice.
9. ***Not installed* was set in JetBrains Mono** and read as a value the TV had returned
   (`a-x2-notinstalled`). A word value takes Sora; the mono face is for ids and versions.

Plus two in the alternatives, fixed so the comparison is honest rather than rigged: **B said *Up to
date* twice**, in the row and again in a dock that had nothing else to put there — B's dock now
appears only when there is something to do; and **C gave the hero block to a TV that is not on the
network** while the one that is got a one-line row (`c-s7`) — C now leads with a TV that answered.

## A new token the phone does not have

The badge green is the shipped `--good` (`#2dd49a`) on every dark skin. On **Daylight** that colour
reaches about **1.9 : 1** on its own 16 % tint — unreadable. Daylight takes `#0f7a52` at 14 %, and
`Not approved` takes `#f2797f` dark / `#b3242c` light. The admin system already carries exactly this
light-theme override on `.badge`; Ravilo's `RaviloColors` does not, and a promotion needs it as a
real token rather than as this round's rule. The other three skins (Midnight, Noir, Graphite) were
not drawn and will each need a value.

## Reused from the shipped app, not authored here

The status bar; the primary button (54 dp, radius 27, `--grad`, Sora 16 sp 600 — `GuideButton`,
unchanged); the card surface (`--card` at radius 14 — `TvChoice`); the badge pill's exact geometry
(10 sp Sora 700, .8 sp tracking, radius 6, padding 6/2, ink on a 16 % tint — the Profile row's
*Experimental* badge); JetBrains Mono for model codes and versions. Four shipped strings are quoted
character for character.

**Authored here, and new:** the page head (the guide's other screens are centred one-action pages;
this one is a list, so the title is left-aligned and the back control is a real 44 dp target), the
two-line version block, the card ordering rule, the loading state, the footer count line, and the
light-theme green above.

## States drawn, and states skipped

Drawn: all eight the brief names, in light; the recommended direction also in dark, in `da` and
`fo`, and at the 200 % font setting both phone-height and full-height. Three studies
(`a-x1-serverfirst`, `a-x2-notinstalled`, `a-x3-allbadges`) and one strip per direction.

**Skipped, and reportable:** the Midnight, Noir and Graphite skins. A focus state — this surface is
touch-only. An error state of its own — every failure here belongs to FR-7's install, which is a
different screen with its own one-sentence-one-button rule. The iPhone bezel — the Profile row is
Android-only. An empty state — the screen only opens when the viewer has an approved or ready
request, so there is always at least one card; the nearest thing is state 7.

## Open, for Bárður

1. **Should the approved card render from the server while the scan runs?** The request rows are
   already in hand — they are what decided this screen opens at all. Drawn as
   `a-x1-serverfirst`: the card appears at once with a short bar where *On the TV* will be, and one
   small placeholder stands for whatever else the sweep finds. It is a much better first second than
   two grey rectangles, and FR-10 item 1 does not say it.
2. **FR-10 has no line for "Ravilo is not on this TV".** FR-11 reads a 404 as *not installed*, which
   is distinct from *not known*, and it is the common case for a TV nobody has installed on yet.
   Drawn as `a-x2-notinstalled` with the draft string *Not installed*. Both states give the same
   button, so this is copy, not behaviour.
3. **Two approved TVs.** Nothing in the brief or the spec rules it out — the cap is ten rows. A
   holds it; B and C each need a new rule. Worth a sentence in FR-10 either way.

## Canvas and fence notes

Dark ink on the light-gray backdrop throughout; no `content-visibility`, no `backdrop-filter`, no
large blur, no infinite animation. **The scan indicator is a static arc for that last reason — the
live app turns it.** No `color-mix()`: the tints are written out, because a round's CSS is the
Compose port's reference and `color-mix` is newer than the Tizen floor this family keeps working
on. Every SVG id is minted from a counter, though this round defines no paint server at all. No
network reference of any kind; the three faces are `@font-face`'d from the repository's own woff2.
No hostname, no IP literal, no `passkey`/`announce`/`api_key`/`token=` anywhere. The two TV names
and two model codes are the brief's invented stand-in and appear in `stv-data.js` only.

Nothing on the forbidden list turned up in this round's inputs — no `STATUS.md`, no
`specs/research-reports/`, no `scripts/`, no `CLAUDE.md`. Nothing was dropped.
