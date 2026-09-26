# Phase 266 — The artwork zoom button opens the preview, on every kind of artwork

> Owner, 2026-09-26: *"When in the Jellystructure media item artwork tab. There is this small expand icon on
> every artwork available, but it's impossible to click on it, it just selects the image for me right away,
> instead of giving me the popup preview of the image. This was working previously, but has stopped
> working some time ago."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Admin frontend only (`MediaDetail.kt`'s artwork tab and
its inline stylesheet). **Numbering:** verified against `STATUS.md` the same day — admin taken through
**265**.

## Reproduced on production, safely

A headless Chromium on the production admin, signed in with an existing admin session, **with every
non-GET request aborted in the browser** so no click could save anything. On four titles it opened each
artwork target, hovered the first and third candidate card, and clicked the centre of the zoom button
(`⤢`, `.art-zoom`):

| Target | What is under the zoom button's centre | Result |
|---|---|---|
| Poster, Backdrop, Season poster | `BUTTON.art-zoom` | the lightbox opens ✓ |
| **Clearlogo** | **`SPAN.art-pill`** (the card's info bar) | **no lightbox; `POST …/artwork/candidates/save` fired** (blocked by the test) |

So it is not every artwork: it is **every logo**, and there it is not merely unclickable. The click lands
on the card, and the card's click handler saves that candidate as the title's logo at once. A
write-through save with no undo, triggered by what the operator meant as "let me look at it first".

### Why

- A candidate card is `aspect-ratio: t.aspect` over a grid column of at least 120 px. The info bar
  (`.art-card-meta`: language, ★ rating and pixel size as pills) is absolutely positioned at the bottom of
  the card, and the zoom button is absolutely positioned at the top right, 5 px in.
- **Phase 192 (FR-192-5/6, built 2026-09-06)** gave the clearlogo target its real shape, `4 / 1`, where
  it had been `16 / 9`. A logo card is now **139 × 35 px**. Its info bar is **26 px** tall, so it covers
  the card from 6 px down, **the zoom button included**, and most of the logo too. That is the "stopped
  working some time ago": it worked until 2026-09-06 because a 16:9 card left room above the bar.
- The info bar comes later in the markup than the button, so it paints on top. The card's handler skips
  a click only when `ev.target` itself has the class `art-zoom`. A click on a pill is not that, so it
  saves.

Posters (2:3) and backdrops and stills (16:9) leave enough room above the bar, which is why they work.

## Requirements

**FR-266-1 — Controls are never under content.** The zoom button sits above everything else in the card:
image, ribbons and the info bar. It gets its own stacking order, not a place in the markup. A card's
controls must be reachable at every aspect ratio the gallery draws.

**FR-266-2 — A short card puts its info beside the picture, not over it.** When the card is too short for
the info bar to sit under the zoom button without covering the artwork (the logo target today), the
info pills are drawn **below the image** as the card's caption, and the image area keeps the full 4:1
logo, visible edge to edge on its checkerboard. Posters, backdrops, season posters and stills keep the
overlay they have. The rule is written against the card's height, not against the word `clearlogo`, so a
future short target cannot bring the problem back.

**FR-266-3 — Clicking a control never selects the card.** The card's click handler ignores any click that
started inside a control of the card (`closest(".art-zoom")`, and any future button in the card), not only
a click whose target *is* the button. The zoom button's own handler keeps `stopPropagation`. Two guards,
so neither a child element inside the button nor a stacking mistake can turn "zoom" into "save" again.

**FR-266-4 — The zoom button can be found without a mouse.** The button is `opacity: 0` until the card is
hovered, which on a touch screen (the admin on a tablet or phone) means never. On a device without hover
(`@media (hover: none)`) it is always shown. It keeps its `title` and gains an accessible name
(*"Preview"*), since `⤢` alone reads as nothing to a screen reader.

**FR-266-5 — An e2e test that fails today.** In the admin suite, against the e2e stack's mocked TMDB
(with logo candidates added to its images answer if it has none): open
a title's Artwork tab, choose Clearlogo, hover a candidate, click the centre of its zoom button, and
assert that the lightbox is open and that **no** request to `/artwork/candidates/save` was made. The same
for a poster, so the fix for one shape cannot break the other.

## Non-goals

- Saving on a single click itself (write-through, Phases 71/74). Selecting a card still saves it, as
  designed. This phase only makes sure a click meant for the preview is not taken as a selection.
- The lightbox's own contents and its *Use this artwork* button.
- The Ravilo apps.

## Acceptance

1. Artwork tab → Clearlogo, on a title with logo candidates: hover a card, click ⤢ → the lightbox opens
   on that logo; nothing is saved (the logo on disk is unchanged).
2. The logo cards show the whole logo, with the language, rating and size underneath it.
3. The same click on a poster, a backdrop, a season poster and an episode still opens the lightbox, as it
   does today.
4. On a touch device the ⤢ button is visible without hovering, and tapping it opens the lightbox.
5. FR-266-5's test passes and fails when the info bar is put back over the button.
