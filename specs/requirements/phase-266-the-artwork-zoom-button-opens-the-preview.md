# Phase 266 — The artwork zoom button opens the preview, on every kind of artwork

> Owner, 2026-09-26: *"When in the Jellystructure media item artwork tab. There is this small expand icon on
> every artwork available, but it's impossible to click on it, it just selects the image for me right away,
> instead of giving me the popup preview of the image. This was working previously, but has stopped
> working some time ago."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Admin frontend only (`MediaDetail.kt`'s artwork tab and its inline stylesheet).
**Numbering:** verified against `STATUS.md` the same day — admin taken through **265**.

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

## Dev review (2026-09-26, against `main` `0e5e434f`)

The reproduction matches the code line for line. Five items.

1. **Where it lives.** Candidate cards are built in `renderArtGallery` (`MediaDetail.kt:3638-3651`: the
   `⤢` button at `:3644`, *before* `.art-card-meta` at `:3645`, so the bar paints over it). The card's
   click handler is at `:3719-3744`, with the one-level check at `:3724`
   (`ev.target.classList.contains("art-zoom")`), and the button's own handler at `:3747-3755`. The
   styles are inline in `injectArtworkStyles()` (`:3939`): `.art-zoom` `:3984`, its hover reveal `:3983`,
   `.art-card-meta` `:3987`. No design stylesheet is involved, so `check-css-scoping.sh` is unaffected.
2. **When it started.** `ArtTarget("clearlogo", …, "4 / 1")` (`:3422`) replaced `"16 / 9"` in `449b1e86`,
   Phase 192's build, committed under the 194/R237 spec message. That is also why the history is hard
   to find.
3. **FR-266-2 has one clean shape.** Put the image in its own box with the target's aspect ratio, and the
   pills in a normal-flow row **below** that box, whenever the card is short. "Short" is decided when the
   gallery renders: the aspect ratio is known, so it is `height = column width ÷ ratio` against a
   threshold of about 90 px. Nothing is measured after layout. Posters (2:3) and 16:9 targets keep the
   overlay. The zoom button gets `z-index: 2` inside the image box (FR-266-1).
4. **FR-266-3's guard:** `(ev.target as? Element)?.closest(".art-zoom, button") != null → return`. It
   covers the icon, any future child element, and any future control in the card.
5. **The e2e needs the mock to serve images.** `tests/mock-tmdb/server.js` answers `/search/*`,
   `/movie/{id}` and `/tv/{id}` only. Add `/movie/{id}/images` (two posters, two logos, `file_path`
   only). The thumbnails point at the real image CDN and will not load in CI, but the cards still lay
   out by `aspect-ratio`, which is all the test needs. Assert with `page.on('request')` that no
   `/artwork/candidates/save` was sent.

**Net effect.** One render function's markup, one handler's guard, a few inline CSS rules, one mock
route, one e2e file. No backend change.
