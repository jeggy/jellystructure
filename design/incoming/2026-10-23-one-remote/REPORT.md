# One remote — round report

**Round** `2026-10-23-one-remote` · Ravilo · phone, web, Mac · drawn in this repository.
**Brief** from Bárður. **Returns** to him on the brief's task with the PNGs.

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
