# Phase 223 — Slide a marker, not only its edges: dragging in the segment editor

> Asked 2026-09-16, right after phase 222 landed:
>
> *"Do we have support for drag and drop to slide a segment back and forward? Both to move the start
> and end time at once, or slide only the start or end time of a segment already on the timeline. We
> want the experience to be perfect and easy to use."*
>
> Edges: yes, with a mouse. The whole marker: no — and the bar's cursor already says `grab`, so the
> tool promises a gesture it does not have. This phase is the drag model the editor should have had
> from the start: one engine for every pointer, a slide as well as a resize, a readout you can see
> while you move, snapping to the evidence that is drawn right under the track, a cancel, and one rule
> about neighbours that the client and the server share.

## Status

`Planned` — written 2026-09-16 from the question plus a read of `Segments.kt` as built by phase 222
(`main` `90dbc201`), `design/app/segments.css` and `segments.js`, `SegmentRoutes.kt`, and a read-only
copy of the production database. Not dev-reviewed, not built. Admin frontend (`/segments`), the served
`segments.css`, the mockup, and one shared rule the backend validates too. No Ravilo half — but Ravilo
is the consumer whose behaviour justifies FR-223-6.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 222.

## 1. What dragging does today (traced against `main` `90dbc201`)

- **Edge handles exist, mouse-only.** `buildTrack` (`Segments.kt:285-301`) draws `<i class="h l"
  data-e="a">` and `<i class="h r" data-e="b">` on every unlocked bar; an open-ended credits marker gets
  only the left one (222 FR-222-4). `wireDragHandles` (`:1104-1155`) listens for `mousedown` on a handle
  and `mousemove`/`mouseup` on `document`, measures the track's box once, moves the bar's `left`/`width`
  and the playhead to the dragged edge, and on `mouseup` writes through `applyEditInPlace` (`:1038`) —
  optimistic patch, one PUT, revert on failure. Wired once per navigation.
- **The bar's body only selects.** `mousedown` on a bar → `selectMarker` (`:577-586`); a `click` on
  the track that is not a handle → `seekAtClientX` (`:590-606`, 222 FR-222-8). The stylesheet says
  `.sx .track .seg{cursor:grab}` (`segments.css:87`) and nothing grabs. The mockup is the same shape
  (`segments.js:425-450` — `dragHandles` is edge-only, the body `mousedown` selects) and its placeholder
  even reads *"drag on the bar to mark a segment"* (`:289`), a third gesture nobody built.
- **Nothing else moves while you drag.** The row's `.ts-a`/`.ts-b`/`.len` readouts change only on
  release (`patchSegmentDom`, `:1064`). There is no bubble. The `<video>` is never touched during a
  drag, and on release the playhead is set to the dragged edge (`trimPlayheadMs`) **without a seek** —
  so after every handle drag the playhead marker and the picture disagree, the exact thing 222 FR-222-2
  set out to end for clicks.
- **Input model.** `MouseEvent` only: no pointer events, no capture, no `touch-action`. A handle is
  11 × 28 px (`segments.css:88`) on a 46 px track (`:82`). Releasing outside the browser window leaves
  `moveHandler` attached until the next `mouseup` anywhere. `Escape` (`:1229`) navigates back to the
  season sheet or the media page — pressed mid-drag it leaves the view with the drag half done.
- **Evidence is drawn, not used.** `buildEvidenceLane` (`:303-325`) renders black frames, silences,
  the fingerprint's matched span and chapter marks in `.ev` under the two waveform lanes; Jellyfin's
  own candidates render as buttons. None is a snap target. The ±60 s zoom strip (`#seg-wave-zoom`,
  `renderWaveformLanes` `:755-785`, one bar per second) is redrawn on every playhead move and edit and
  has no listeners at all — the most precise surface on the page is inert, and it is centred on the
  selected marker's **start** even while the operator is dragging its end.
- **Keyboard.** `,`/`.` nudge the last-touched edge 40 ms, Shift 1 s (`:1191-1192`); `I`/`O` set an edge
  at the playhead; `L` lock; Space; Enter; row steppers ±1 s. Nothing moves a whole marker.
- **Server.** `PUT /api/segments/{itemId}/{kind}` (`SegmentRoutes.kt:286-308`) validates the kind, the
  order of start and end, and both against the measured length (`validateSegmentEdit`, `:645-652`),
  then upserts. It knows nothing about the unit's other markers.
- **What Ravilo does with an overlap.** `DetailService.toTv` (`:293-310`) hands the TV
  `introStartMs`/`introEndMs`/`creditsStartMs`; the Skip-intro pill is `positionMs in intro`
  (`PlayerScreen.kt:1075-1080`) and the credits card fires at `creditsStartMs`. A credits marker that
  starts inside the intro puts the credits card on top of the intro pill. Overlaps are not an editor
  cosmetic; they reach the living room.

## 2. What the production data says (read-only copy, 2026-09-16 19:44)

| | |
|---|---|
| `media_segment` rows | 15 001 |
| units carrying both an intro and a credits marker | 6 635 |
| credits marker starting **inside or before** the intro | 775 (526 inside, 249 before) |
| … of which within 2 s of the intro's own start | 582, across 32 titles |
| … by source | fingerprint 664 · chapter 59 · heuristic 52 · locked 0 |
| pairs that exactly touch (end == next start) | 1 |
| gap from intro end to credits start on healthy rows (n = 5 860) | median 20.0 min, first quartile 17.7 min |
| credits markers that are open-ended | 7 627 of 7 628 |

Three things follow. **(a)** A no-overlap rule will essentially never fire on healthy data — neighbours
sit a quarter of an hour apart — so it costs the operator nothing and catches exactly a slide that went
too far. **(b)** 775 rows already overlap today, so the rule must be shaped so those rows stay
**fixable** from the editor; a rule that refuses any write while an overlap exists would lock the
operator out of the only rows that need them. **(c)** Those 775 are a **detection defect** — a credits
start written at the intro's own timestamp, 32 shows, every source but manual — outside this phase
(§7, open question 4), but they are the rows the neighbour rule will be exercised on first.

## 3. The interaction model

One drag engine, three grips, one readout. The grips are the **left handle** (moves the start), the
**right handle** (moves the end) and the **body** (slides both, length preserved). For an open-ended
credits marker the body and the left handle are the same grip: the end is the file. A locked bar has
no grips and says so on the first move (FR-189-5's toast). The zoom strip is a second track with the
same three grips at ten times the resolution. Everything is written on release, never during the
move, and a cancel restores everything.

## 4. Requirements

### FR-223-1 — Slide the whole marker

Pointer down on the **body** of an unlocked bar, then a move of at least `DRAG_THRESHOLD_PX = 4`, slides
the marker: start and end move together, the length is preserved to the millisecond, and the slide is
clamped to `[0, duration − length]` before FR-223-6's neighbour clamp. An open-ended marker slides its
start within `[0, duration]`. Below the threshold the release is a click and does what a click does
today — selects the marker and moves the playhead (222 FR-222-8). The cursor is `grab` at rest on an
unlocked body, `grabbing` for the whole drag (set on `document.body`, since a captured pointer's cursor
is decided by the element under it, not by the capturer), `ew-resize` on a handle, `default` on a
locked bar. Release writes through `applyEditInPlace` — one PUT, the same optimistic patch and revert.
A release with unchanged values writes nothing.

### FR-223-2 — One engine, every pointer

The mouse listeners are replaced by **Pointer Events** with `setPointerCapture` on the element that
received `pointerdown`, so mouse, pen and touch share one code path and a drag survives the pointer
leaving the bar, the track or the window. `touch-action: none` on `.track` and on the zoom strip, so a
touch drag never becomes a scroll. The track's box is re-measured on every move (the page may scroll
under a finger). On a coarse pointer (`@media (pointer: coarse)`) the handle's **hit area** grows to at
least 44 px wide by a transparent `::before` while its painted width stays 11 px, and the track grows to
56 px. These rules live in the served `segments.css` and are fenced by `scripts/check-mobile-css.sh`.

### FR-223-3 — You can see what you are doing

While a drag is live:

1. A **timecode bubble** sits above the moving edge — `1:24.0` for a handle, `1:24.0 → 1:54.0 · 30.0 s`
   for a body slide — clamped inside the track's horizontal bounds so it never clips at either end. When
   the edge is snapped, the bubble names the target: `black frames · 1:24.0`, `intro end · 1:54.0`.
2. The marker row's `.ts-a`, `.ts-b` and `.len` update live from the same numbers, so the readout on the
   row and the bubble can never disagree.
3. The playhead rides the moving edge (as today), and **in direct-play mode the picture follows it**:
   `currentTime` set at most once per animation frame, playback paused for the drag and resumed on
   release if it was playing. In **remux mode** the picture does not move during the drag — a seek is a
   transcode — and on release one seek lands on the released edge through `seekToAbsoluteMs`, so the
   playhead once again states where the picture is (222 FR-222-2). This also fixes today's silent
   playhead/picture disagreement after every handle drag.

### FR-223-4 — Snapping to what the page already knows

Within `SNAP_PX` (6 px for a fine pointer, 12 px for a coarse one) of a target the moving edge sticks to
it. Targets, computed **once at pointer-down** from `currentTrimData` and never during the move:

- every evidence span's start and end (black frames, silence, fingerprint match), every chapter mark,
  every Jellyfin candidate's start and end when the candidates have loaded;
- the other markers' edges (an intro start snaps to the recap's end, a credits start to the intro's end);
- the playhead;
- whole seconds — on the zoom strip only, where one pixel is a tenth of a second and the snap is
  meaningful; on the full track a pixel is one to three seconds and whole-second snapping would be
  noise.

The nearest target wins; a tie prefers evidence over the playhead. A body slide snaps whichever of its
two edges is nearest to a target. **Shift held disables snapping** for the drag (Shift is not a
window-manager modifier; Alt-drag moves windows on many Linux desktops, and Alt-arrow is history
navigation in browsers). `S` toggles snapping for the view, and the legend shows the state.

### FR-223-5 — Cancel, and never a stuck drag

`Escape` during a live drag restores the bar, the row readouts and the playhead to their pre-drag
values and does **not** navigate — the trim view's Escape-goes-back binding yields while a drag is
live. `pointercancel`, `lostpointercapture` and a window `blur` restore the same way. Nothing is written
on a cancel. There is no path on which a move handler outlives its drag.

### FR-223-6 — A marker never overlaps its neighbour — one rule, two consumers

Markers of different kinds are intervals that must not intersect; touching (end == next start) is
allowed. An open-ended credits marker's interval runs to the file end. The **stinger is a point inside
the credits by definition** and is exempt from the credits interval, but may not sit inside a recap,
intro or preview. The rule judges only the marker being written, against the others **as stored**:

- a write is refused when, against any other marker, the overlap **after** the write is larger than the
  overlap **before** it — so a new overlap is refused, a worsened one is refused, and any write that
  reduces or clears an existing overlap (the 775 rows) is accepted even when it does not clear it fully;
- the client applies the same rule as a **clamp during the drag**: the bar visibly stops at the
  neighbour's edge and the bubble says `stops at intro end`; a marker already overlapping moves freely
  in the direction that shrinks the overlap and stops in the one that grows it;
- the server applies it in `validateSegmentEdit` → `422 {"error": "credits can't start before the intro
  ends (10:24)"}`, surfaced by the existing 222 FR-222-5 toast.

The rule is implemented **once, in `commonMain`** (`SegmentEditRules`: overlap, neighbour clamp, accept),
compiled into both the backend and the wasm client, and tested in `linuxX64Test` — the "one predicate,
two consumers" shape of 185 FR-185-6, so the clamp the operator feels and the 422 they could otherwise
hit are the same function. The mockup's JS carries a literal copy with the same test cases in comments.

### FR-223-7 — The zoom strip is a second track

`#seg-wave-zoom` overlays the **selected** marker's bar (the visible part of it) with the same three
grips and the same engine, at the strip's resolution (~0.15 s per pixel); a click or drag on empty
strip moves the playhead. The window is centred on the **last-touched edge** (`trimLastEdge`) — today it
is always the start, so the end of a 90 s intro is off the strip while its handle is being dragged —
and it re-centres **on release, never mid-drag**: the ground must not move under the pointer. The label
already says what the strip is centred on; it gains the edge (`±60 s around the intro end (1:54.0)`).

### FR-223-8 — Keyboard parity

With a marker selected: `←`/`→` slide the **whole** marker by 1 s, `Shift` 10 s, `Ctrl` 40 ms; `,`/`.`
keep nudging the last-touched edge; `[`/`]` pick the start/end edge as the last-touched one so the
keyboard can choose an edge without the mouse. All of them go through the same clamp and the same
write path as a drag and are refused with the same toasts. Arrow keys never scroll the page while the
editor owns them. The legend block (`renderTrim`) lists every binding.

### FR-223-9 — The mockup keeps up, and the fences know

`design/app/segments.js` gains the body slide, the bubble, pointer events, snapping and the zoom
overlay; `design/app/segments.css` (shipped verbatim into the app) carries the coarse-pointer hit-area
rule, the bubble and the zoom overlay; `scripts/check-mobile-css.sh` fences the coarse-pointer rule and
the bubble class so a design sync cannot drop them silently (the 14th-incident lesson).

## 5. Non-goals

- Dragging on **empty** track to create a marker (open question 1). The add row already creates one at
  the playhead in a click; a create gesture needs a kind chooser and is its own decision.
- Undo history (open question 2).
- Multi-select or dragging two markers at once.
- Frame-accurate scrubbing on a remux stream — a seek is a transcode; 222 FR-222-2's keyframe rule stands.
- Changing what `detect_segments` writes. The 775 overlapping rows are a detection defect and get their
  own phase (open question 4); this phase only makes them fixable by hand without a fight.
- The season sheet's lanes and the drawer's mini bars stay read-only (163's decision).

## 6. Verification

1. **Unit** (`linuxX64Test`, on the shared rules): a slide preserves length and clamps to the file; a
   handle clamps at 100 ms from its partner; the neighbour rule refuses a new overlap, refuses a
   worsened one, accepts a reduced one and accepts touching; the stinger inside the credits is accepted
   and inside the intro refused; snapping picks the nearest target and honours the radius.
2. **Browser**, with the burned-in-timecode sample 222 still owes: a body slide of a 30.0 s intro lands
   30.0 s long to the millisecond; a handle drag with the bubble reading `1:24.0` writes 84 000 ms; a
   snap onto a black-frame span reads its name; Shift frees it; `Escape` mid-drag restores everything
   and stays on the page; a release in direct play shows the frame under the edge, in remux the toast
   names the keyframe.
3. **Touch**, on the Pixel 9 in Chrome (never the TV): a finger on the enlarged handle drags it, the page
   does not scroll, the bubble stays readable, and a locked bar toasts.
4. **The 775 rows**: open one, drag the credits past the intro's end; the bar stops nowhere, the write is
   accepted, and the TV payload's `creditsStartMs` is now after `introEndMs`.

## 7. Open questions

1. **Drag-to-create on empty track** — the mockup already promises it. Recommendation: yes, as its own
   phase, with the release opening a kind chooser at the pointer.
2. **Undo** — a per-view stack of the last few edits behind `Ctrl+Z`. Recommendation: yes, separately.
3. **Worsening an existing overlap** — refused (as written) or warned? Recommendation: refused; the
   only rows it affects are the 775 broken ones, and "can't get worse" is easy to explain.
4. **The 775 rows** — a follow-up phase for the fingerprint credits-at-intro-start defect (32 titles),
   with a re-detect or a repair sweep; recorded here so it is not lost with the spec that found it.
5. **Snap radius on touch** — 12 px is a guess; adjust after the Pixel 9 pass.
6. **`←`/`→` when the `<video>` has focus** — the element is rendered without native controls today; if
   that changes, the arrows would seek the video and slide the marker at once. Guard by target, as the
   keydown listener already does for inputs.
