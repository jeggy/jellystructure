# Phase R240 — What a highlighted title says before you open it

> The viewer half of **Phase 202**. A focused poster currently says nothing but its own name. Two
> non-overlay directions fix that: **L**, a permanent status line at the foot of the screen, and **J**,
> the focused row opening in place. The server decides which one applies and how long the remote must be
> still first; Ravilo renders it, adds no D-pad stop, and moves nothing the viewer was aiming at.

## Status

`Planned` — written 2026-09-12, not dev-reviewed. **Built in the mockups** (`design/ravilo/`) across
2026-09-06 → 2026-09-12; no Compose code exists yet. L ships **on**, J ships **off** behind Phase 202's
switch, and J's default is not a hedge about whether it works — it is the reflow question in open
question 1.

**Numbering:** verified against `main` on 2026-09-12 (Ravilo taken through R239, admin through 201).
Pairs with **202**. Next free: 203 / R241.

Design: `design/ravilo/Focus Detail - Round 2 Directions.html` (six non-overlay directions + comparison
table; an 8-page landscape print copy sits beside it) and `design/ravilo/Focus Detail - Directions.html`
(round 1 — all six retired). Reference implementation: `design/ravilo/ravilo-focus.js`,
`ravilo.css`, `ravilo-app.js` (`fieldsFor`), `ravilo-i18n.js`, `Ravilo TV.html`.

## Current state

The detail *page* is rich — synopsis, genres (R221), flags, a slow-to-start note (R222), About. The
*row* is silent: a viewer scanning 42 titles across six rows gets a poster, a highlight ring and a
title, and has to open a page to learn whether a film is 90 minutes or 190, whether it has Danish
subtitles, or whether they are already 40 minutes into it.

Round 1 drew six directions and the owner rejected the whole class: every one of them floated something
over the row. Round 2 moved the axis to *where the information lives* and drew six more — **G** a
permanent info band replacing the hero carousel, **K** the tile's caption expanding, **H** a full-bleed
cinema takeover, **I** a fixed detail pane beside a narrower row list, **J** the row opening, **L** an
88px foot line. The owner picked **L** as the default and **J** as the next-gen option. (The
recommendation on the file had been G; L is the only direction that cannot regress anything and J is the
only one that feels good under the thumb, so shipping the floor and gating the ceiling avoids betting on
the band.)

## Goal

Answer the question a highlighted title raises, in the row, without overlaying it, without slowing the
browse, and without adding a single thing to reach past.

## Functional requirements

### The two directions

**FR-R240-1 — Render, never compute.** The client receives one resolved fact object per title (Phase 202
FR-202-5) and renders it. It derives no fact, re-checks nothing, and caches nothing past the payload.
**Absent ⇒ nothing renders** — no empty slot, no placeholder, no reserved space beyond FR-R240-2's
band. Formatting numbers against the client's own string table is not computing (FR-R240-11); deciding
what is true is.

**FR-R240-2 — L · the status line.** An 88px strip at the foot of the screen, always describing the
focused title: title · format badge · year · seasons + episode count · runtime · age rating · IMDb ·
audio and subtitle language flags with overflow counts · where you left off, and the **full genre list**
right-aligned. **Facts only, no synopsis** — there is no room for one, and a clamped two-line synopsis is
K's compromise, not L's.

It is **not an overlay**: the screen's scroll surface carries matching bottom padding, so the last row
can always clear the strip. That padding exists for as long as L is *configured* — not merely while
something is focused — or the whole page would shift the first time a tile took focus.

**FR-R240-3 — J · the row opens.** The focused poster grows in place from 210 to 300 dp wide in its own
slot, and one inert panel is inserted **inside the same row**, immediately after it, carrying title,
meta, up to four genre chips (the first marked as primary, per R221), the synopsis or *"No description
yet"*, and a foot line of flags + resume. The rest of the row continues past the panel; nothing is
positioned absolutely and nothing overlays. **The row band itself gets taller** — which is the whole cost
of this direction (open question 1).

**FR-R240-4 — J supersedes L; the client renders exactly one.** The client reads Phase 202's resolved
`focusDetail` (`"none" | "line" | "rowOpen"`) and renders that and only that. It does **not** receive
both booleans and does not own the precedence rule. When the mode is `rowOpen`, L's reserved band goes
away with it.

**FR-R240-5 — Nothing here is reachable, and nothing moves what the viewer was aiming at.** Neither
direction adds a focus stop: the line and the panel are inert, not in the D-pad order, not clickable,
with no action and no dismissal — labels, like the age badge. Down from a tile goes exactly where it goes
today; Play never moves; Back is unchanged.

Specifically, and because the repo has now shipped R200, R201, R223, R232 and R236 all on the same
fault: **no `FocusRequester` may be attached to the panel or to the grown tile**, and the panel must be
excluded from focus search so R236's row-level bridge (`focusRestorer()` on the row) cannot land on it.
J inserts and removes a child of a `LazyRow` on every settled focus move, which is precisely the
topology R236 FR-R236-5 warns about — a bridge target that may not be composed.

**FR-R240-6 — The delay, and what is shown while it runs.** Neither direction appears until the D-pad has
been still for `focusDetailDelayMs` (202 FR-202-3; default 170, `0` = immediately). Sweeping along a row
stays a plain row. At `0` the reveal is **not deferred by a zero-length timer** — a settled viewer waits
no frame they did not ask for.

While the delay runs, **nothing is stated**. L hides rather than leaving the previous title's facts on
screen: the one thing this surface must never do is state something true of a title that is no longer
focused. The band stays reserved, so the page does not move while the text clears.

### J's motion

The owner's first two reactions to a working J were *"too jumpy when navigating"* and *"the content is
jumping in"*. Both were geometry, not duration, and the rules below are what make J shippable — a build
that implements FR-R240-3 without them reproduces exactly what was rejected.

**FR-R240-7 — The open animates, from an interpolable start.** The poster's width animates 210 → 300; the
panel opens by **widening from zero** with its content clipped and laid out at its final width, so the
text does not reflow mid-tween and the posters to its right are pushed aside rather than teleported. The
collapse is the same motion reversed — the panel narrows out and the poster returns to 210, so the row
slides home instead of snapping — and the panel leaves the tree only once it has closed.

⚠ The mockup's hard-won lesson, because it is a trap in any layout system: the tile must animate from an
**explicit** width, not from an intrinsic one. With `flex: none` (basis `auto`) the CSS transition was a
discrete `auto → 300px` jump that no duration could smooth, and the poster snapped into its big card
however long the tween was. The Compose equivalent is animating a concrete `Dp` the row lays out from,
not a wrapped intrinsic size.

**FR-R240-8 — The row band reserves its opened height and never lowers it while focus stays in the row.**
The row grows **once**, on the first title the viewer settles on, at the height it will have when fully
open — measured against the tile's final width, not sampled from a tween in progress. Any later growth
(wrapped chips, a long synopsis) may raise it; nothing lowers it until focus leaves the row, which is the
only thing that gives the space back. Lateral moves within an opened row therefore move **nothing below
them**.

**FR-R240-9 — One vertical target, computed after the growth.** The reveal runs once the row has opened,
never racing it (R232 FR-R232-3's hazard, restated by R236 open question 3), and resolves to a single
target: `max(rowTop − 150, rowFoot + 40 − screenHeight)`. It never scrolls **back up** — the first content
row is deliberately parked under the hero, and an open row must not undo that peek. One scroll, one
target: two sequential scrolls are the jump.

**FR-R240-10 — A collapse and the row's catch-up cancel.** When the closing panel is to the **left** of
the newly focused tile, the width it gives back is subtracted from the row's horizontal scroll target, so
the collapse and the catch-up net out and the tile the viewer moved to arrives without a lurch (~900px
in the mockup). The horizontal catch-up runs on the **same clock and easing** as the growth: a native
smooth-scroll with its own duration finished at a different moment and read as two separate movements.

**FR-R240-11 — Four strings, three languages.** `fd_audio`, `fd_subs`, `fd_nodesc`, `fd_min_left` in
en/da/fo at ship, like every string since R180. Counts and durations are assembled from 202's numbers
against this table (`fact_min`, `fact_per_ep`, `resume`, `next_episode` already exist) — never shipped as
prose from the server.

**FR-R240-12 — Noir keeps the ink and drops the colour.** Aurora and Midnight mark L's strip and J's
primary genre chip with the accent; Noir's own accent is amber, where a tint reads as a warning. Noir
marks both by **weight and ink instead** — the same collision R221's primary chip hit, solved the same
way, and the third time this rule has been needed (R222 FR-R222-7).

**FR-R240-13 — A config change applies to the tile that is focused right now.** When `focusDetail` or the
delay changes (202 FR-202-4), the client re-renders the currently focused tile through its **normal focus
path**, so J's reveal rule runs on a switch flip exactly as it does on a focus move. Turning L off
releases its reserved band. A setting that needs a relaunch to see is a setting nobody compares.

## Non-goals

- **No overlay, ever** — the whole point of round 2. Round 1's plate, hero mirror, tile unfold and
  ambience wash are retired, not switched off.
- **No artwork.** Dropping the directions that needed a backdrop took the image URL and its fetch out of
  the payload (202 FR-202-5). Restoring either is a new phase.
- **No trailer autoplay on focus** — a transcode session per focus move, straight into 183's and R231's
  territory. Recorded rejected in round 1.
- **Nothing outside Home content rows** — not the channel rail, hero, browse, search, Discover, Live TV.
- **No new focus stop, no action, no dismissal, no viewer setting** (R216).
- **Not on the phone.** J's premise is a D-pad and a dwell; the phone has neither. The phone detail page
  already carries this information.
- **No change** to R137's retained scroll, R139's Back-return restore, R55's back-to-top, R140's
  bring-into-view, or R236's focus bridge.

## Acceptance

Run with `focusDetail: "line"` first, then `"rowOpen"`, on the stue TV.

1. **L**: focus any Home tile — the strip states that title within the configured delay; the last row
   still scrolls clear of it; no row moved when the first tile took focus.
2. **L**, sweeping: hold Right across a row — the strip is blank while moving and states the title the
   viewer stops on. It never shows a title that is not focused.
3. **J**, settling: focus a tile and wait — the poster grows, the panel widens open, the posters to its
   right slide aside, and the text appears inside the grown band. One movement, not three.
4. **J**, sweeping: hold Right across a row — **no row opens**, nothing below the row moves, and the row
   opens exactly once when the viewer stops.
5. **J**, lateral moves in an opened row: step Right one tile at a time — the row band's height does not
   change, the collapse and the next open read as one slide, and the page below stays put.
6. **J**, on the first content row (the one parked under the hero): the opened row's foot is fully on
   screen, and the page does **not** scroll back up afterwards.
7. **J**, then Down to the next row: the previous row's extra height is released and the panel is gone —
   no stranded panel, at any navigation speed.
8. Both directions: Down, Left, Right, Back and Play behave exactly as they do with `focusDetail: "none"`.
9. Flip the switch in jellystructure while a tile is focused: the screen changes without a relaunch, and
   J's reveal runs on the flip.
10. Danish and Faroese: every label is translated; nothing clips at 88px.

## Open questions

1. **The reflow cost on the living-room BRAVIA is unmeasured — this is why J ships off.** J changes a
   `LazyRow` item's size and its siblings' positions on every settled focus move, which is exactly the
   workload **invariant 11** exists to protect. FR-R240-6's delay and FR-R240-8's held band remove the
   *repeated* work (a sweep now costs nothing and an opened row grows once), so what remains to measure
   is a single open, in isolation, on that device. Until someone runs a focus sweep on the stue TV with a
   frame trace, the default does not change.
2. **Does `focusRestorer()` survive a row whose children change size?** R236 open question 2 already
   flags that restoring focus to a *disposed* child is an unverified assumption; J adds and removes a
   sibling in the same row. Verify in isolation before building — if the two interact badly, the panel
   should be a sibling of the row's item rather than a child of the list.
3. **Reduced motion — resolved 2026-09-12, built the same day.** Falls back to L, per the instinct
   recorded above: `effectiveFocusDetailMode()` (`ravilo-ui/.../focus/FocusDetailReducedMotion.kt`)
   downgrades a resolved `"rowOpen"` to `"line"` whenever the platform reports reduced motion, and
   leaves `"line"`/`"none"` untouched. Platform seam: `systemPrefersReducedMotion()`
   (`seams/ReducedMotion.kt`) — Android reads `ValueAnimator.areAnimatorsEnabled()` (API 26+, the same
   signal the "Remove animations" accessibility toggle and Developer options' animator duration scale
   both drive), falling back to reading `Settings.Global.ANIMATOR_DURATION_SCALE` directly below API
   26; wasmJs reads the standard `(prefers-reduced-motion: reduce)` media query. Read once per Home
   composition (not live-observed — matches R216's own link-state sampling tradeoff: a mid-session
   toggle is rare enough that a `ContentObserver` isn't worth the seam). 4 new
   `FocusDetailReducedMotionTest` cases on the pure downgrade rule; `:ravilo-ui:compileDebugKotlinAndroid`/
   `compileKotlinWasmJs`/`testDebugUnitTest`/`allTests` all green. Not device-tested.
4. **Where the text comes from** — 202 open question 1, unresolved: the home feed or a per-title fetch on
   focus. J's dwell makes the second option *less* bad than it was (no request until the viewer settles),
   which is worth saying out loud when the dev team decides.
5. **Is the delay one number or two?** One setting governs both directions today. L's reveal is free and
   J's is expensive, so a household that wants an instant line and a patient row cannot have both — a
   second number nobody can reason about was judged worse than that limitation.
