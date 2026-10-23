# REPORT — round `music-playlists` (2026-10-23)

Making and changing a playlist in Ravilo music, on the phone and the desktop (Mac, Linux, Ravilo Web).

No phase number and no task id is drawn anywhere. None was asked for and none is used; the
`FR-n` references in this round's source files are comments naming the requirement each shape
answers, and no `FR-`, `DEB-` or `phase n` string reaches a rendered pixel — checked by extracting
all 349 distinct drawn strings from the built DOM (below).

---

## Package

1. **Directions doc (self-contained):** `Music Playlists - Directions - self-contained.html` — one
   file, 259 KiB, with both stylesheets, all three scripts and the three faces inlined as data
   URIs. No `<link>`, no `<script src=`, no `http`. The linked twin is
   `Music Playlists - Directions.html`; `build-selfcontained.mjs` turns one into the other and
   refuses to write a file that still points at a sibling.
2. **PNGs:** `png/`, **81 files**, named `music-playlists-<surface>-<state>[-<skin>|-<lang>].png`.
   One device pixel per CSS pixel. Largest 151 KiB; there is no size limit in this lane.
3. **The source the frames render from:** `frames.html` + `music-playlists.css` + `mpl-data.js`
   (the one array) + `mpl-kit.js` (the builders) + `render.mjs` (the renderer prototype). Every
   hex, px, weight, radius and easing value is in the CSS, with the production class each one
   restates named beside it.

### The one thing worth knowing about the sources

**This round links the last music round's stylesheet instead of copying it.**
`frames.html` loads `../../ravilo/music-play-all/music-play-all.css` and then
`music-playlists.css`. So every frame, skin, token, face, cover gradient, bottom bar, sidebar and
player bar in these pictures is byte-identical to the ones adopted on 2026-10-22, the three woff2
resolve out of that round's own `fonts/` with no copy and no network reference, and **the two
rounds of music pictures are diffable**. The glyphs come from that round's kit through
`window.MPK.ic`, so a glyph is the same path rather than a redrawing that nearly matches.

`music-playlists.css` adds only what did not exist. Its header names the production class behind
every restated shape (`.mp-sheet`, `.mu-pl`, `.mu-shfield`, `.mu-qrow`, `.mu-handle`, `.mu-pam`,
`.mp-fail`, `.qp`/`.qr`, …).

### One new token, and nothing else

`--pl-bad`, the destructive colour, with a value per skin:

| skin | value |
|---|---|
| aurora | `#d64541` |
| midnight | `#e2544e` |
| noir | `#e2603f` |

Music ships no error token. Its only destructive surface is the queue row's swipe panel, which
hard-codes `#d64541` in `ravilo-music.css` (`.mu-qrow .rm`). This round needs the same red in four
places — that swipe panel, *Delete playlist* in the ⋯, *Delete* in the confirm, and the name
field's error border — so it is named once instead of typed four times. Noir gets its own value
because `#d64541` reads muddy against amber on near-black. **A promotion adds the token to
`ravilo-music.css` and repoints `.mu-qrow .rm` at it**, which means a line in
`check-mobile-css.sh` in the same commit.

---

## What the round produced

**One visual language, seven surfaces, and not one new component type.** Every surface is a
shipped shape with one control added to it.

### Directions, and which one is recommended

| # | Direction | Recommended | Why |
|---|---|---|---|
| 1 | Where *New playlist* sits in the picker | **N1 — the first row, with a + tile** | It is also FR-4's stated order, so it costs no amendment. N2 (a button pinned under the list) is genuinely easier to reach with a thumb, but it contradicts FR-4 and spends a fixed strip of the sheet. |
| 2 | The desktop picker | **P1 — a popover anchored to the ⋯** | The window stays readable, the beak says *which* ⋯ was pressed, and clicking away dismisses it. The centred dialog dims the window, loses the anchor, and has to carry a *Cancel*. Its one advantage — room for a long list — is not collected, because the long list scrolls fine inside the popover. |
| 3 | Removing in desktop edit mode | **E1 — a × at the row's end on hover** | There is no swipe with a mouse, and the × sits in the column the row's ⋯ already occupies: no new column, no new vocabulary. E2 is two clicks for the commonest action in a mode that exists for it. |

Both alternatives are drawn, not described, so the comparison is like for like.

### Settled inside the drawing

1. **The ⋯ control.** 48 px square beside *Play* and *Shuffle* on the phone (so its glyph keeps the
   44 px tap area music uses everywhere), 38 px on the desktop. Its menu is right-aligned under it.
2. ***Done* in edit mode takes the secondary fill, not the primary.** The action of edit mode is
   dragging; *Done* is the way out. Drawn as a full-width gradient it was the loudest thing on a
   screen whose entire content is the list.
3. **The queue's two actions take their own row under *Up next*.** Forced by the pictures — see
   below.
4. **A playlist's artwork is its four-tile collage** on the desktop page, as it is in the Playlists
   chip and the picker. An empty one gets a dashed tile. The phone page gets **no artwork at all**,
   because that is what it ships with.

---

## What only the pictures showed

Eleven defects were found by looking at the renders, nine of them in this round's own drawing and
two in what the brief asks for. All are fixed or handed back.

**In the drawing — fixed:**

1. **Every "skin" PNG was Aurora.** `frames.html` set `data-skin` on `<html>` but the builders
   defaulted each frame's own `data-skin` to `aurora`, and the frame wins. Twelve midnight/noir
   pictures were silently the default skin. Caught by sampling pixels, not by eye: the scrim over a
   warm page looks cool enough to pass. Fixed, and verified by measurement —
   `#0d0c0a`/`#e2603f` (noir), `#07151f`/`#e2544e` (midnight), `#0e111b`/`#d64541` (aurora).
2. **The playlist page had been quietly redesigned.** The first draft gave it a full-width cover
   and the album page's numbered rows. The shipped page (`playlistHTML` in `ravilo-music.js`) is
   `.mu-ahead`: centred, **no artwork at all**, title and count, then `.mu-song` rows with a cover
   and an artist. The brief says the page stays as it is apart from what this round adds, and the
   drawing had not. Restored.
3. **A red *Remove* panel appeared in the drag frame.** The floating row is built with `k = -1` and
   a bare `k === o.swipe` matched a `-1` sentinel, so the lifted row took the swipe branch.
4. **The drag read as a missing song, not a move.** The lifted row was translated upward over its
   neighbour, hiding it. It now leaves a dashed slot at its origin and floats lower down, on both
   surfaces.
5. **The name sheet said "Playlist name" twice**, once as the label and once as the placeholder.
   The sheet opens focused, so the empty state is an empty focused field.
6. ***Cancel* read as stronger than *Create*.** With *Create* shown-and-disabled, a bordered Cancel
   beside it sent the eye to the way out. Cancel is now a text row.
7. **The error state contradicted the frame beside it.** It drew an empty field with *Create* live,
   while the empty state kept *Create* disabled — so the error could never have been raised. The
   field now holds spaces, which is the case that actually reaches the server and comes back `400`.
8. **The picker's empty state said the same thing twice** — a card reading "make one" under a
   button reading *New playlist*. One quiet line now says what the list would hold, and nothing
   restates the control.
9. **Four glyph slips**: *Play next* and *Add to queue* shared one glyph; *Remove from playlist*
   used a bare minus; the empty playlist's dashed tile was blank (a glyph name that exists in the
   borrowed set but not in this round's); the desktop dialog's rows sat 10 px right of its own
   title. All four fixed, and every glyph name in both sets is now checked programmatically.

**In the popover beak, looked at and accepted:** at 5× the popover's top border and the beak's own
border meet under the diamond. That is two correct hairlines meeting, it is invisible at 1×, and
chasing it would mean clipping a border. Noted rather than changed.

---

## Two things the pictures ask the brief to change

### Q1 — an album with editions has no room for the ⋯ *(the important one)*

`ph-album-editions` draws FR-6 literally on an album that has editions. That page **already**
carries two split controls (`.mu-dacts.re-acts` — *Play album ▾* and *Shuffle ▾*). Adding the ⋯
makes five tap targets across 324 dp: ***Play album* truncates to "Play al…" and *Shuffle*
collapses to "S…"**. This is a break on a shipped screen, caused by this round.

`ph-album-editions-merged` draws the proposal: **both ▾ give up their split and the single ⋯
carries every secondary action** — *Play extras*, *Shuffle extras*, *Add to playlist…*. Three
controls, every label whole, and one place to look for "anything else" instead of two dropdowns
and a ⋯. It is a better page than today's even before this round's row is added.

**This changes an adopted editions behaviour, so it is not mine to decide.** It needs an answer
before the client slices start, because FR-6 as written does not fit.

### Q5 — where the queue's two actions sit

FR-7 says *Save as playlist* goes "in the *Up next* heading, beside *Clear queue*". Drawn on the
heading line itself, at 360 dp **the English wrapped UP NEXT onto two lines and the Faroese
"Goym sum spælilista" pushed *Clear queue* clean off the right edge**; in the 300 px desktop queue
panel all three wrapped. They now sit on their own line inside the heading block, still beside each
other. I read that as inside FR-7 rather than against it, but it is worth a sentence in the spec.

Two further notes on that surface: today *Clear queue* is a centred button **below** the list
(`.mu-qclear`), not in the heading — FR-7 as written **moves** an existing control, which is a
change a viewer would notice. And in Faroese the two pills fill the 300 px desktop panel edge to
edge with no slack: a narrower panel, or a longer `music.pl_save_queue`, breaks it.

---

## Four smaller questions

| # | Question | What I drew |
|---|---|---|
| **Q2** | An **empty** playlist cannot be played, but FR-8 says nothing about *Play* and *Shuffle*. Shown-and-disabled, or absent? | **Shown and disabled** — the owner's own rule, and the one round 9 got backwards. |
| **Q3** | A shared playlist loses its ⋯ with nothing to say why. Is a *Shared with you* badge wanted? | **Both**, side by side: `ph-pl-shared` / `dk-pl-shared` are *exactly as today*, which is what the brief says; `-badge` is the proposal. |
| **Q4** | The picker needs one line when the viewer owns no playlist. FR-16 has no key for it. | A new key `music.pl_none`, drafted in three languages below. |
| **Q6** | The brief named *Northern Lights* on the album *Harbour Songs* (10 songs, 2024) but **not the other nine tracks**, and the playlist page needs a body. | Corpus titles from `design/app/music-data.js` as a stand-in, flagged in `mpl-data.js` rather than passed off as settled. A playlist and a queue legitimately span albums, so Road Trip's and the queue's contents introduce no contradiction; the album's nine do, and want naming. |

---

## Words

Every word drawn comes from FR-16's table or from the shipped `i18n/*.json`, which wins wherever a
string already exists. I read `music.play`, `music.shuffle`, `music.up_next`, `music.now_playing`,
`music.clear_queue`, `music.play_next`, `music.add_queue`, `music.go_album`, `music.go_artist`,
`music.add_playlist` and `music.new_playlist` out of the shipped files rather than drafting them —
two of my drafts happened to match the shipped Faroese exactly, which is a reason to read rather
than to guess.

**One string is new and is not in FR-16:**

| Key | en | da | fo |
|---|---|---|---|
| `music.pl_none` | Playlists you make show up here. | Playlister, du laver, vises her. | Spælilistar, tú stovnar, síggjast her. |

**The brief asked only for the English.** The round draws Faroese and Danish on the ⋯ menu, the
confirm, the queue, the song sheet and the toasts anyway, because a menu, a confirm and a pair of
pills are exactly where those two overrun and the house system makes every round draw the longest
string. It paid: the Faroese found the queue break above. The strings used are FR-16's own drafts
and the shipped files' values — **none was invented in the drawing**.

One honest gap: the queue page's `7 songs · 28 min left` stays English in the `-fo`/`-da` frames.
It is canvas furniture from the music mockup, not a key this round touches.

---

## States drawn, and states skipped

Drawn: **empty** (a playlist with no songs; a picker with no playlists of your own), **error** (the
name rejection, and all six toasts including *Couldn't change the playlist*), **the longest
string** (Faroese and Danish on six surfaces), **the round's own states** (every one the brief
names), and **all three music skins**.

Skipped, and why:

- **Loading.** No surface here has one of its own: the picker and the playlist page read lists the
  Playlists chip has already loaded, and FR-14 says they re-read on next show.
- **Offline / lost connection.** FR-13 collapses every failure — `401`, `403`, `400`, `404`, `502`
  — into one toast, *Couldn't change the playlist*, which **is** drawn. There is no second offline
  treatment to design.
- **10-foot focus.** No TV surface exists in this round by the brief's own constraint.
- **Phone landscape.** The brief does not ask for it and no surface here changes shape with the
  sensor; the sheets are bottom-anchored and the menus are anchored to their control.

---

## Jellyfin is invisible

No frame names Jellyfin, a server, another app or an address, in any state, in any language. The
failure line is exactly *Couldn't change the playlist*. Verified by extracting the `innerText` of
every frame across both sets and all three languages — **349 distinct strings, none matching**
`Jellyfin`, `server`, an IP literal, `FR-`, `DEB-` or `phase n`.

## Fences

Run on the **staged** directory, all four green:

```
check-deanonymization.sh  OK — no scrubbed real values found.
check-phases.sh           OK — 440 spec files; every name parses, no number is used twice …
check-mobile-css.sh       OK — every design-sync-fragile CSS rule tracked here is present.
check-css-scoping.sh      OK — every page-scoped stylesheet keeps its selectors under its own wrapper class.
```

Plus the checks no fence performs: every `http`/`https` literal (none), the substrings `passkey`,
`announce`, `api_key`, `token=` and any IP literal (none), `git check-ignore` on the round's path
before planning where files go (not ignored), and a human reading of every drawn string.

Nothing named in *"never landed"* appears: no `STATUS.md`, no `CLAUDE.md`, nothing under `specs/`,
`scripts/`, `.github/`, no `*.gradle.kts`, no `i18n/*.json`. **Nothing was dropped from the round's
inputs** — the brief carried none of them.

## Canvas rules

No `content-visibility`, no `backdrop-filter`, no large `filter: blur`, no infinite animations.
Two notes on that:

- **The production `.mu-dback` does carry `backdrop-filter: blur(6px)`.** The back button in these
  frames uses a flat `rgba(6,8,14,.72)` instead. The live phone keeps its blur.
- The caret in the name field and the keys on the keyboard are **static**. A blinking caret is an
  infinite animation and breaks the render.

Every SVG `id` is minted from a counter, for the reason `PROCESS.md` gives.

## Reproducing the pictures

```sh
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  node design/incoming/2026-10-23-music-playlists/render.mjs
```

`scripts/render-design.sh` (DEB-2125) was still not merged when this round ran, so the command
`PROCESS.md` carries is the one that drove it, unchanged. `render.mjs` and
`build-selfcontained.mjs` are prototypes kept beside the round they built; the permanent script is
the engineer's.
