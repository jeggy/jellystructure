# Ravilo as the TV's home screen — round report

**Round** `2026-11-17-tv-home-screen-apps` · Ravilo **TV only**, 960 × 540 dp on the 1920 × 1080
stage · drawn in this repository.
**Brief** from Bárður. **Returns** to him on the brief's task with the PNGs.

**43 PNGs** in `png/`. The written half is `TV Apps - Directions.html`: what is fixed, the named
directions, how they compare, every state drawn and every state skipped, what the drawing decided,
and the new strings in three languages. This file is the round's own record — what was built, what
the pictures changed, and what is still owed.

---

## The brief

**It stood alone and nothing went back.** It named the question as a choice, named the surface,
quoted the spec and `specs/ravilo/constitution.md` as excerpts rather than pointing at them, named
the kind of round (product design) and the round directory, named every stand-in exactly, listed the
states, and stated each of the three open decisions as a lean. It carried no phase number and no
`DEB-n` for a drawing to name. The spec it cites
(`specs/ravilo/requirements/ravilo-can-be-the-tvs-home-screen.md`) was read in full and the brief's
excerpts match it.

**Nothing in the round's inputs had to be dropped.** No `STATUS.md`, nothing under
`specs/research-reports/`, nothing under `scripts/`, no `CLAUDE.md`.

**Two things in the brief are stale or wrong, and both are cheap to fix in the next one:**

1. **The CSP note.** The brief says the TV-frame `@import` "needs a `file:`-only CSP in
   `frames.html` (known lane bug)". DEB-2922 fixed that: `render-design.sh` answers
   `design/ravilo/*.css`'s Google Fonts `@import` from `design/app/fonts/`, and says so in its own
   `--help`. This round carries no CSP `<meta>` and renders clean.
2. **The header pills.** The brief names them *Home, Movies, Series, **Music***. The shipped TV bar
   is *Home, Movies, Series, **Discover*** (`raviloNavItems()`), and there is no `nav.music` key in
   `i18n/en.json` at all. I drew the brief's set exactly, as the stand-in rule requires — but
   *Discover* is three characters wider than *Music*, so the brief's set **understates** the width
   the entry has to fit into, and width is one of this round's findings. `B-standard-shipped-tabs`
   and `B-fo-shipped-tabs` are drawn with the real tab set for that reason, and every row in the
   width canvas that matters uses it. **Not a defect to fix in the drawing — a line for the next
   brief.**

---

## What the round recommends

| Decision | Lean in the brief | What the round found |
|---|---|---|
| Where the entry sits | **A** — a round button beside Search | **A2**, a new third direction: A's position with B's label. A is a close second; **B is not recommended** |
| Tile layout | **5 across** | **6 across**, which shows all eighteen entries with no scrolling at all |
| First tile's label | **TV settings** | Still the lean in English — but in Faroese **neither label fits a six-across cell**, so this decision and the layout decision are not independent |

### A2 exists because the house already answered this question once

The brief's two directions are a glyph-only circle (A) and a tab (B). The slim-header round settled
the identical question for *Surprise me* the other way: a glyph-only circle "says nothing", and a
**labelled pill in the right cluster** does not read as a fifth tab. Drawing A and B without A2 would
have put a choice to the owner that the house has already made once, in the opposite direction, and
a reviewer who remembers that round would have asked.

A2 is `SurprisePill`'s shape, which the bar already has — no new component, no new treatment. It is
the widest of the three (110 dp more bar than today, against A's 58), and a Standard bar still has
150 dp spare after it.

**A is a genuinely close second, and the argument for it is not width.** Four squares in a 2 × 2
grid is a near-universal convention for "apps" in a way a sparkle is not — which is the reason
*Surprise me* needed its word and this might not. If the owner prefers the quieter bar, A is a
defensible pick and not a worse one.

**B is the one I would not ship.** `B-standard-at-rest` is the frame that decides it: *Apps* is
indistinguishable from *Movies*, *Series* and *Music*, so it reads as a fifth section of Ravilo's
library rather than as the way out of Ravilo. In `bar-geometry` the focused Apps pill and the focused
*Home* tab are visibly the same object. B does buy one thing the others cannot — a place in the
bar's "where am I" row, so Apps can mark itself there (`B-grid6-apps-first-view`); A and A2 rely on
the page's own title instead.

---

## The one finding that is not this round's to fix

**The TV bar already overflows before Apps is added.** `bar-width-arithmetic` measures each
combination in the browser after layout:

- Slim, shipped tabs, **every optional item at once** (Cast + the now-playing pill + *Surprise me*),
  **no Apps entry at all**: 991 dp of content in a 960 dp screen — **31 dp clipped**.
- The same bar + A: 77 dp clipped. + B: 99 dp clipped.

The row clips at the **right** edge, so what disappears first is the avatar, then the clock, then
Search (`A-slim-crowded`, `B-slim-crowded`). **Apps makes an existing defect worse; it does not
create it,** and the Apps entry should not be the thing that gets blamed for it. Raising it is
Bárður's call, not this round's.

Note that my first draft of that frame drew the music pill as "track · artist" at full length and
**overstated the crowded bar by about 90 dp**. `TvMusicPill` caps its title at 110 dp and shows no
artist; the numbers above are the corrected ones, measured from the pill's own Kotlin.

---

## What only the pictures showed

Nine defects in the renders, every one invisible to the four fences.

1. **The focused Search and Apps glyphs were near-black on the accent fill.** I had used `--bg`;
   `onAccent` (`Colors.kt`) is **white** in every skin but noir, whose accent is amber. `ravilo.css`
   has no token for it, so the round declares its own. Half the point of a focused circle was a dark
   smudge on purple.
2. **Every banner tile said its own name twice.** I had put a label under all eighteen cells, over
   FR-11, which says a banner *or* an icon and a label. Dropped the label from banner cells and moved
   the fallback's icon and label **inside** the banner's own cell — which is both what the spec
   describes and what keeps every cell exactly 16:9.
3. **Four plates were the same purple.** The plate colour came from the app name's hash and the hash
   clustered — DR TV, HBO Max, Kodi and Netflix were indistinguishable, against a brief that asks for
   "one distinct solid colour" each. Now the hue is the app's position in the A–Z list spread evenly
   round the wheel, with a half-turn for odd indices so grid neighbours are never wheel neighbours.
   **The first version of that line also collided** — Apple TV and Plex landed on the same orange —
   so `hueSpread()` is in the data file and prints the worst gap (22°).
4. **The two fallback cells read as holes in the grid.** `--card` on `--bg` is a 4 % step: the two
   apps Android has no banner for looked like two apps that had failed to load. They carry the
   shipped hairline now.
5. **The gear was a sun.** A small circle, eight spokes and a faint outer ring read at 1× as an
   asterisk inside a halo. Redrawn as the ring itself with eight teeth on it.
6. **The page head collided with the grid when Apps scrolled.** I translated only the grid and left
   the head where it was, so the second row of tiles slid straight over "Open another app, the TV's
   settings, or Google TV." — in the one frame that exists to show scrolling. The head now scrolls
   with the grid and the column is clipped at the bar, as `DiscoverScreen` does.
7. **In noir, a focused tile lost its ring.** `--ring` is amber there, and a banner's colour belongs
   to the app — a focused orange Apple TV plate had an amber ring on it. `Tile.kt`'s lift went back
   on, which separates the focused cell whatever colour the app shipped.
8. **The focused list row had two focus signals** — the inverted fill *and* a 3 dp ring, which a
   D-pad user reads as two targets. The fill alone now.
9. **The "today" can't-reach frame carried my own proposal.** It showed the SERVER chip, which
   today's screen does not have. A "today" picture with something today does not have is how a
   comparison lies.

And one caption that disagreed with its own picture, which is the same class of defect: the
first-tile canvas claimed *Android-stillingar* fits a six-across cell. **It does not** — both Faroese
labels ellipsise at 131 dp. Caption corrected, and the corrected fact is what makes the label and
layout decisions dependent on each other.

---

## What I had to decide, and tell you about

- **The brief's tab set versus the shipped one** — above.
- **The code's grouping.** The brief writes the code `K7Q-4XN`. `groupCode()` groups with a **space**,
  not a hyphen, so the frames show `K7Q 4XN`. The QR encodes `K7Q4XN`.
- **The server on the can't-reach screen is a new element.** The brief names `ravilo.example.net`
  there and nothing on that screen names a server today. Drawn as the **same chip** the code screen
  already uses for *This TV*, so it is the screen's own vocabulary rather than a new device, and it
  is listed as a new string (`tvsignin.server`).
- **The signed-out Apps screen gained a Back hint.** Apps opened from sign-in has no bar and no tabs,
  so the Back key is the only way out, and the constitution wants that said. It is the sign-in
  screen's own `tvsignin.back_to_profiles` sentence in the same type and colour — a new string, not a
  new device.
- **A single-column list was ruled out by arithmetic before being drawn**: at a 10-foot row height it
  shows 5 of 18 and scrolls three times, giving up the only thing a list is meant to win. Direction C
  is the fair three-column form.
- **The loading state is skipped on purpose**, and the directions doc says so: FR-11's list is a local
  `PackageManager` query, not a network call. A skeleton would draw a wait that does not happen.
- **I did not touch `design/ravilo/ravilo.css`.** Its `.appbar` is still 92 px — a 46 dp drawing of a
  60 dp bar — which the slim-header round raised and nobody has landed. Every measurement in this
  round comes from the Kotlin (`Dimens.kt`, `AppBar.kt`, `SurpriseMe.kt`, `TvSignInScreen.kt`,
  `TvNowPlaying.kt`, `Tile.kt`, `Colors.kt`), listed in `apps-data.js`'s provenance block. **That
  mockup defect is still open for whoever owns raising it.**

---

## Still open, for Bárður

1. **Where the entry sits** — A2 recommended, A a close second, B drawn and not recommended. Owner's
   pick; all three are drawn at rest and focused, in both bars.
2. **Tile layout** — six across recommended, five across the brief's lean, the list a real contender.
   Owner's pick.
3. **The first tile's label**, and it is **not independent of 2**: in Faroese neither label fits a
   six-across cell. Either five across, or a truncated Faroese label, or let the fixed tiles' label
   wrap to two lines (which saves *Android-stillingar* and not *Sjónvarpsstillingar*).
4. **Does Apps come before or after the password button on the can't-reach screen?** Both drawn. A
   password cannot work while the server is unreachable, and Apps is the only control there that
   reaches Wi-Fi.
5. **Does the can't-reach screen keep Retry as its arrival focus?** My lean is yes. A behaviour
   question, not a drawing one.
6. **The bar already overflows with every optional item present** — 31 dp before Apps. A defect to
   raise against the bar, not against this round.
7. **A native Faroese read** on *Appir* and *Sjónvarpsstillingar*, and on the three sentences.
8. **A physical scan of the sign-in QR is still owed**, exactly as it was on the TV sign-in round.
   `verify-qr.py` reads the matrix back and confirms the string; no camera exists on this seat.
9. **`design/ravilo/ravilo.css`'s `.appbar` is 46 dp against the shipped 60** — still open from the
   slim-header round.
