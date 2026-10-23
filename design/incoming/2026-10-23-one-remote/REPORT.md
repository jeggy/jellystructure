# One remote — round report

**Round** `2026-10-23-one-remote` · Ravilo · phone, web, Mac · drawn in this repository.
**Brief** from Bárður. **Returns** to him on the brief's task with the PNGs.

Revision 2 is the section immediately below; round 1's report follows it unchanged, because it is
still the record of what the film half is and why.

---

# Revision 2 — music on today's Playing page

The brief stood alone. It named the three changes, named the frames, named the shipped page and
where to read it, and left the volume placement to me with a stated constraint. It carried no phase
number and no task id. Nothing went back.

**The film half is untouched as a design.** Every film, episode, web-film and language frame is the
same drawing; the pixels moved in nineteen of them only because of §3's footer, which is a change
the brief asked for and which lands in every frame that has a footer.

## 1 · Music is now the shipped Playing page

`frames.html` links `design/ravilo/mobile/ravilo-music.css` for the music set, so the pictures ARE
the shipped page — `mu-ntop`, `mu-ncov`, `mu-nmeta`, `mu-nsub`, `mu-seek`, `mu-tp`, `mu-nbot` and
the shipped `bnav` with Listen · Browse · Playing · Queue · Profile. The structure is read from the
Kotlin (`MusicPlayingScreen`, portrait branch) and cross-checked against
`design/ravilo/mobile/ravilo-music-player.js`; where they disagree the code wins and each
disagreement is listed below. Every class in the markup without an `or-` prefix is a shipped class.

**`a-music-today` is today's page beside it**, so "what changed" is a comparison the owner can
make rather than a claim I make. It differs from `a-music` in exactly three places and nowhere
else — I went back and lit the cast glyph in both when the first render differed in a fourth.

The page gains four things and nothing more:

1. **The place pill** replaces the device chip, in the chip's own row. It keeps the chip's own
   sentence — *Playing on {place}* / *Paused on {place}*, which is shipped `cast.playing_on` — and
   gains only the → that opens *Move to…*. It is never absent: an unreachable place greys it,
   because the place still exists and the viewer still needs to read where.
2. **Stop**, one tap from the page, as a labelled pill in the shipped bottom row. The row is 46 dp
   and has about 180 px of slack at 412, so naming a destructive action costs no height. Absent on
   a read-only playback — there is nothing there to press.
3. **The someone-else line and round 1's banners**, under the pill. All eleven of round 1's fixes
   carried over: lost/couldn't-move/moving have a banner and no line and every other state a line
   and no banner; ⏯ keeps the last reported glyph through a move, a failed move and lost contact;
   a failed move greys nothing, because the place it is still playing on did not break.
4. **Volume stays behind ⋯**, and that sheet gains a row per room.

### Why volume stayed in ⋯ — measured, not asserted

The brief's constraint was "wherever it fits **without** pushing the transport below the fold".
Measured off `a-music` at 412×915: the page's two `.mu-nspacer` flex gaps hold **about 108 px**
of slack in total (≈53 above the cover, ≈55 below the transport). The volume block is **154 px**
for a master row and two rooms, and **222 px** with Kontor and its reason line. Neither fits, and
an inline row would eat the page's whole composition before it ever reached the fold.

So the sheet, as today, at the shipped 5 % steps — and the sheet is `a-music-no-report`, with
Kontor drawn and disabled and its reason under it. Its heading is the **place**, drawn as the
shipped speakers panel draws it (`.spk-mh`: the group glyph and `roomsLine()`), not the word
*Volume*: the sheet already has a master row called Volume, and an *Applies on {place}* line on
top of that said the place a third time.

On the **Mac and the web** volume is in the capsule's popover, which is where it already ships —
so the answer is the same one on every surface: volume is one tap away, never on the page.

### What moved that the brief did not list, and why

Three things, each named rather than done quietly. Any of them is one line to revert.

- **`a-st-moving` and `a-st-move-failed`, and the states strip with them.** Two of the seven state
  frames are a song. Leaving them on round 1's layout would have put two music players in one
  package, which is the exact defect this revision removes. The builder is now chosen by kind from
  one place in `frames.html`.
- **`web-music-1440` is the desktop page, not a wide phone.** `MusicPlayingScreen` branches on
  `isDesktopLayout` before it ever reaches the portrait page, so Ravilo Web at 1440 *is* the cover
  beside the lyrics. Same body as the Mac frame, in browser chrome, with the capsule.
- **`mac-volume-popover`'s capsule gained Stop and lost ±10/30 s.** Round 1 drew skip buttons on a
  3:41 song; the capsule's transport now follows the kind, as the phone's already did. Stop is in
  the capsule on the desktop because the left column of the Playing page is 380 px and the place
  pill alone fills it — a Stop beside the pill truncated *Stue + Gæsteværelse*, which the first Mac
  render showed.

## 2 · `a-cast-film-no-slider`

`a-cast-film` with the volume row removed and the transient hardware-key pill showing
*Stue TV · 60 %*. The two frames are otherwise the same composition, which is what makes them an
A/B rather than two drawings. The lean frame is untouched.

**One thing to know about that pill: I could not find it in the shipped code.** There is no
Ravilo-drawn volume pill for a hardware key anywhere in `ravilo-ui` — the Android Cast SDK routes
the keys and the platform draws its own slider. So the frame draws the shipped **toast**
(`MusicToastHost`: #202433 at 90 %, 20 dp radius, a 1 px white 8 % border, 16/10 padding, 14 sp
semibold Sora, bottom-centre) carrying that line. Whether the pill exists, or has to be built, is a
question for you and Sjúrður and it is the one thing the frame asserts that I cannot source.

## 3 · The three-up footer

**All three the same two-line height**, not one line. Measured at 412: three buttons, 16 px of side
padding and two 9 px gaps leave 120.7 px each, and each button's own padding leaves ~108 px of
label. At 12.5 px semibold Sora **all three languages wrap**, English included — the render of
`a-nextup` shows *Subtitles &* / *audio*. One line is not reachable without going under the 12.5 px
floor the footer already sits on. The label block now always reserves two lines, so a one-word
sibling gets the same box, the three icons share one baseline and nothing is truncated. It is also
the only one of the two options that survives a longer string later. `fo-a-nextup` is the proof
frame; `a-nextup`, `fo-a-cast-film`, `da-a-cast-film` and the other seventeen footer frames are
re-rendered.

**And the string may make the question go away.** The shipped key is `player.audio_subs` —
*Audio & Subs* / *Lyd & undertekster* / *Ljóð & undirtekstir* — and `CastRemoteScreen.kt` draws it
in a `RemotePill` that is single-line with an ellipsis, 46 dp tall. The round's own draft
*Subtitles & audio* is longer than anything that ships. I kept the draft, as you asked, but at
adoption the shipped string on the shipped pill is one line in all three languages and this whole
item disappears. Your call, not mine.

## What only the pictures showed

Nine, every one found in a render and none of them visible in the markup or to any fence.

1. **The transient pill rendered maroon.** `Color(0xE6202433)` is ARGB — #202433 at 90 %. Pasted
   into CSS as an 8-digit hex it is a dark **red** at 20 %. Compose writes ARGB and CSS writes
   RGBA, and the first `a-cast-film-no-slider` was a wine-coloured lozenge.
2. **The 1440 web music frame drew its title in near-black on near-black.** `.or-rem` carries its
   own `color` and `.or-np` does not, so the browser pane had no ink until it was given some.
3. **…and its version chips read "RemasterLive".** `.rv-v` lives in `ravilo-desktop.css`, which the
   web set did not link. It does now — the web at a desk width *is* the desktop layout, so that is
   the right file for it to have anyway.
4. **The album-hue radial washed the daylight page brown.** The mockup's `.mu-now .bgt` is a dark
   radial off the album's hue; the Kotlin's `playingGround()` is a vertical gradient from
   `accentDim` at 60 % into the background, flat in noir. accentDim is `#E3E0FB` in daylight. The
   code wins and `daylight-a-music` is now a real light frame instead of a stain.
5. **A favourited ♥ drew as an outline.** The mockup writes `fill="currentColor"` inline and
   `.mu-ib svg { fill: none }` beats a presentation attribute, so the shipped mockup cannot draw a
   filled heart at all. The Kotlin uses `HEART_FILLED`. Seen at 3× on `a-music`.
6. **The speaker glyph in the shipped device chip laid out at 300×150.** `.mu-ndev` sizes its mark
   through `.rc-ic`, which is a drawn element and not an SVG, so an SVG in that chip has no size
   rule at all. `a-music-today` rendered two black slabs where the speaker should be.
7. **The shipped music mini bar loses both the artist and the end of the place at 412.**
   `afterMini` makes the second line `{artist} · 🔊 {rooms}` with `.spk-on { flex: none }`, so with
   a two-room line the place takes the whole row, collapses the artist to nothing and still clips —
   the bar reads `· 🔊 Stue + Gæstevær…`, with a stray dot where the artist was. The frame draws
   round 1's rule instead: the second line is the place, and only the place.
8. **The shipped desktop Playing page does not clear the capsule at 800 px.** At a 380 px cover the
   head is about 656 px and an 800 px window leaves 644 above the capsule, so the transport goes
   under the bar and the page scrolls. `mac-music` is rendered at 1280×900 **on purpose** — I would
   rather show the whole page and tell you it scrolls at 800 than ship a picture with the transport
   sliced. It is the only frame in the round that is not its set's usual size.
9. **The bottom row is `space-between` in the mockup and `lyrics · cast · spacer · ⋯` in the
   Kotlin.** Four buttons spread evenly is not three grouped left and ⋯ right. The code wins, and
   it is also what leaves room for Stop.

## Facts about the shipped system this revision ran into

- **The Playing page's cover is 80 % of the width in Compose (`SwipeCover`) and 84 % in
  `ravilo-music.css`.** The frames draw 80 %.
- **The device chip and the credits are siblings in the Kotlin and nested in the mockup.** In the
  mockup the ♥ ends up level with the chip instead of the title. Drawn the code's way.
- **`ravilo-music.css` redefines the midnight and noir tokens** — noir's accent is amber there and
  blue in `ravilo-phone.css`. That is why music is a set of its own in `frames.html`: loading that
  file into `phone-a` would have silently changed `noir-a-cast-film`, which you have accepted.
  (Round 1's noir finding therefore stands, and is now better explained: the phone's noir is a
  darker ground *in video mode*; in music mode it is amber, from a different file.)
- **`design/ravilo/desktop-directions.css` is still not linked.** `.np` and `.lyr`'s numbers are
  transcribed into `or-np*` — 380 px cover, 48 px gap, 70/48 padding, h2 28 px, `.by` 15 px, a 5 px
  seek line, a 26 px transport gap, a 58 px ⏯, lyrics 30 px/700/1.25 with the shipped mask. That
  file sets `body { background: #1b1c20 }` and a bare `.ic`, `.cap` and `.note`, each of which
  reaches this round's canvas and the Mac frames you have accepted.
- **With two version chips the album name truncates on the shipped `.mu-nsub` at 412** —
  *Marra Quay · Harbour Li…*. Shipped behaviour (nowrap + ellipsis); the album is named in full in
  the *Playing from* line above it, so it costs little, but it is visible in every music frame.

## Open for you

1. **The hardware-key pill** — see §2. No such pill is anywhere in `ravilo-ui`; the frame draws
   the shipped toast carrying that line, and whether the pill exists is yours and Sjúrður's.
2. **Stop on the Mac is in the capsule, and `mac-film` still has one in the Playing page's
   footer.** At adoption that is one or the other. I did not touch the accepted film frame.
3. **The shipped `player.audio_subs` is shorter than the round's draft** and would fit one line —
   see §3. If you map the footer onto it, the two-line height becomes unnecessary.
4. **The three frames I redrew beyond the brief** are listed under §1. Each is one line to revert.
5. Round 1's open items 1 and 3 are settled (the state line stays; noir goes to its own row).
   Open item 4 is answered by `a-cast-film-no-slider`.

## Canvas and render notes for this revision

- Linked, never copied: `ravilo-phone.css` everywhere, `ravilo-music.css` for the music and states
  sets, `ravilo-desktop.css` for the mac and web sets, `flags.css` throughout. Rendered with
  `scripts/render-design.sh`, `--viewport 1500x1000 --all .or-frame`, with `--network none`.
- `.mu-now`'s 0.28 s entry animation is switched off in the frames: it is one-shot and harmless
  live, but a screenshot can catch it mid-fade and a 60 %-opaque frame is not a design decision.
- No infinite animation reaches a frame. The shipped `.mu-mini .mu-pulse-s` is an infinite
  animation in a linked file; no frame uses that class.
- No `content-visibility`, no `backdrop-filter`, no large blur, no network reference, no SVG paint
  server, no `toLocaleDateString`.
- Every new and changed PNG was opened one at a time; the credits row and the bottom row of
  `a-music` were read at 3× and 4×, and the states strip's first caption at 4× to rule out a clip
  that turned out to be a viewer artefact.

---

# Round 1 — the original report

The brief stood alone: the question was a choice, the surfaces were named, the constraints arrived
as excerpts rather than a pointer, the stand-ins were named exactly, the states were listed and each
of the four decisions came with its lean stated as a lean. It carried no phase number and no task id
as a thing the drawing must name. Nothing went back.

## What came home

- `One Remote - Directions.html` — the directions doc: what is fixed, the two directions, how they
  compare, every state of the recommended one, the four decisions with the lean drawn, the en/da/fo
  string table, and what is still open.
- `png/` — 39 PNGs, `one-remote-<variant->-<state>.png`.
- `frames.html`, `or-data.js`, `or-kit.js`, `one-remote.css` — what the frames render from, and what
  the Kotlin gets ported from.

Every number in every picture comes out of `or-data.js`. The film's length is written once and the
seek bar, both clocks and the mini bar's progress thread are computed from it, so "do these pictures
agree with each other?" is a question a reviewer can check rather than trust.

## The one structural decision worth naming

**A1 and A2 are the same builder call with one field different.** `remote()` in `or-kit.js` is
called once for the cast and once for the server place, and the `reach` field that records which is
which is never read by any builder — grep `or-kit.js` for it and there is no hit. The round's whole
argument is therefore a fact in the source rather than a claim in a caption, and
`a-identity-pair.png` is the picture that settles it.

## What only the pictures showed

Eleven defects, every one found by looking at a render and none of them visible in the markup or to
any fence. Listed because this is the lane's one human check and the list is the evidence that it
pays.

1. **Lost contact was stated twice** — once as the state line under the title and once in the
   banner. Same for *couldn't move* and *moving to*. Fixed: those three states have a banner and no
   state line; every other state has a state line and no banner.
2. **Olivar's playback said "Olivar is watching" twice** — the state line and the read-only card.
   The card now carries the other sentence, the one the viewer needs: why there is nothing to press.
3. **Moving to Kontor drew ▶ while the song was still playing.** The place had reported no pause, so
   that was the frontend inventing a state — the one thing invariant 4 forbids. ⏯ now keeps the last
   reported glyph through *moving*, *couldn't move* and *lost contact*.
4. **A failed move greyed the whole transport and the volume.** The move failed; the place it is
   still playing on did not break. Greying is now *lost* and *offline* only.
5. **Two spinners for one wait** — one on the art card and one standing in for ⏯. The transport one
   is the shipped rule; the art overlay is gone.
6. **Lost contact greyed the transport but left the volume live.** A slider it cannot reach is
   exactly as inert as a play button it cannot reach. The volume block greys with it.
7. **The Faroese three-room frame overflowed 915 px by about 40** and pushed the footer off the
   screen — the tallest control stack the remote has. The square art now shrinks instead.
8. **`Gæsteværelse` was truncated to `Gæsteværel…`** in the volume block at every width. The name
   column widened on the phone; on the Mac popover it had to go the *other* way — at the desktop's
   150 px the three tracks collapsed to about 30 px and every slider read as a toggle.
9. **The web frames rendered at the wrong width.** `.or-wframe` had no `position`, so the absolutely
   positioned pane inside laid out against the viewport and the 1440 two-column grid silently did
   not apply. At 1× it just looked like a narrow design.
10. **The Mac said "Up next" twice**, as a footer button and as the list under it; and the third
    queue row was sliced by the capsule, which reads as a clip rather than a scroll.
11. **The volume popover was drawn over the Playing page**, which already holds the same three
    sliders inline — the same control on screen twice. It is now opened over Films, which is what
    the capsule's volume button is actually for.

Plus one caught before it rendered: `.or-cap` was about to be both the Mac capsule bar and the
states-strip caption. The caption would have been absolutely positioned off the strip. Round 9 lost
two frames to exactly this; the caption is `.or-scap`.

## Dropped, and said so

Nothing in the brief's inputs needed dropping — no `STATUS.md`, nothing under `specs/`,
`scripts/` or `.github/`, no `CLAUDE.md`, no `*.gradle.kts`, no `i18n/*.json`. The round writes
under `design/incoming/2026-10-23-one-remote/` and nowhere else.

## States drawn, and states skipped

Drawn: playing · paused · starting on the place · moving · couldn't move · lost contact ·
place offline · stopped by someone · ended · someone else's (view-only) · stop-confirm on someone
else's · next-up mirrored · no artwork · the volume block with a room that cannot report · Danish ·
Faroese · daylight · noir.

**Skipped on purpose, named rather than omitted quietly:** *loading* — the remote is only ever
opened on something that already exists, and *starting on the place* is the real wait; *empty* — a
remote with nothing playing is a closed sheet, not a screen; *10-foot focus* — the TV is not in this
round; the Tizen surface and the receiver's own screens, for the same reason.

## Facts about the shipped system this round ran into

- **Noir on the phone is a darker background and nothing else.** `ravilo.css` inverts the accent to
  amber for noir; `mobile/ravilo-phone.css` does not — it only moves `--bg-rgb`.
  `noir-a-cast-film.png` is in the package as the evidence. Reported, not fixed: a design-mirror
  disagreement is information.
- **The remote light-themes, and the hero card and the film player cannot.** Because the art is a
  card on solid ground and no ink sits on a picture, `daylight-a-cast-film` is a real frame rather
  than a dark surface in a light app. That is the payoff of the Cast remote's original rule and it
  is worth the owner seeing.
- **The three-up footer wraps in every language.** The shipped Cast remote already does, so it is
  not a regression — but it is visible in `a-stop-confirm` and `a-nextup` and it is the moment to
  fix it if the footer is being rebuilt.

## Canvas and render notes

- Linked, never copied: `../../ravilo/mobile/ravilo-phone.css`, `../../ravilo/desktop/ravilo-desktop.css`
  and `../../flags.css` (plus `design/flags/4x3/*.svg` through it). The three faces are
  `@font-face`d from `design/app/fonts/` so the render needs no network — it runs with
  `--network none`.
- **The Mac capsule is drawn flat.** The shipped capsule in `design/ravilo/desktop-directions.css`
  is `backdrop-filter: blur(26px) saturate(1.6)`, which a canvas cannot render. The frames use a
  flat `#22242e` approximation; the live capsule keeps its glass.
- **The spinners are static three-quarter rings.** An infinite animation breaks the screenshot
  render, and a still frame of a spinner is what a screenshot of a real one would be anyway. Same
  for the three-bar "playing" mark, which is the shipped `.ses-eq` without its animation.
- No `content-visibility`, no large `filter: blur`, no network reference, no SVG paint server (so
  no minted-id trap in this round), and no `toLocaleDateString` (no dates in frame).
- Rendered with `scripts/render-design.sh` (DEB-2125), `--viewport 1500x1000 --all .or-frame`.
  `or-frame` carries no style and exists so a phone nested inside the pair frame is never mistaken
  for a frame of its own.
- Every PNG was opened and looked at, one at a time and magnified where something was ambiguous.
  The halo around the art cards was checked at the pixel level before being written off: it is the
  card's own `box-shadow` darkening the ground, not a stray box.

## Open for Bárður

1. Does the state line keep *Playing on {place}* when the pill above already names the place? The
   round keeps it — shipped string, shipped shape — but trimming it to *Playing* is one line of
   i18n and would remove the only repetition left in the lean.
2. The three-up footer wrap, above.
3. Noir on the phone, above.
4. **Decision 1 carries real weight and the drawing does not settle it.** The shipped reason for no
   slider on a cast — volume belongs to the TV, and a slider invites a fight with the TV's own
   remote — is still true. The lean is drawn because the round was asked to draw it, not because
   the argument against it got weaker. The alternative is one frame with the volume row removed and
   was not spent on, deliberately.
