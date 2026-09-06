# Phase 189 — a marker in the segment editor cannot actually be moved

> Live report: *"The segmentation editor is almost working. But it doesn't seem like it works to move
> or change items. Example, after adding a credits segment, it can't drag it around on the timeline,
> nor can I manually click the plus or minus buttons to change the start or end time of the segment."*

## Status
✓ Built 2026-09-06. Not dev-reviewed. **Still not reproduced in a browser** — no headless browser is
available on this host (see the round's memory note) — so this was built and reasoned through against
the source, not watched live. Root-caused by reading
`src/wasmJsMain/kotlin/dev/jellystructure/ui/Segments.kt` against
`src/linuxX64Main/kotlin/dev/jellystructure/server/routes/SegmentRoutes.kt` and
`src/commonMain/sqldelight/dev/jellystructure/db/MediaSegment.sq`.

**Implementation notes:**
- FR-189-1: `correctDuration()` no longer replaces `#seg-track`'s innerHTML. The segment SET doesn't
  change when the video's real duration corrects the estimate — only the scale each bar is positioned
  against — so a new `repositionSegmentBar()` patches each existing `.seg` bar's `left`/`width` style in
  place (the ruler and evidence lane still get a full `outerHTML` swap since they carry no listeners).
  `wireDragHandles` is now wired exactly once per navigation and reads `currentTrimData` fresh on every
  `mousedown` instead of closing over the initial render's `data`, so it keeps working after any number
  of duration corrections or edits — the same "read live state" rule the keydown listener already used.
- FR-189-2: the mouse ± steppers now move by 1000ms (was 40ms — the keyboard `,`/`.` frame-nudge is
  unchanged). Marker-row timecodes gained a tenths-precision formatter (`fmtlt`, `MM:SS.d`) so a 1s click
  is visible without waiting 25 clicks. The rail's keyboard-help block gained a line for the ± buttons
  (it previously only documented the keyboard shortcuts).
- FR-189-3: introduced two new update paths so nothing inside an open trim view calls the old
  video-recreating `renderTrim`/`refreshTrim` again (that function is deleted — it had no remaining
  callers once every site below moved off it):
  - `applyEditInPlace()` — same-marker start/end changes (± steppers, drag-handle release, `I`/`O`).
    Optimistic: patches the bar position and the row's timecodes/length/source badge immediately via a
    new `patchSegmentDom()`, writes in the background, and reverts both the in-memory state and the DOM
    on failure (FR-189-6).
  - `refreshTrimBody()` — structural changes (lock toggle, remove, add, apply-Jellyfin-candidate) that
    change which markers exist or their locked state. Re-fetches, then rebuilds only `.tl`'s and `.mks`'s
    inner content plus the rail (series only) and the confirm chip/duration label — `wireTrim` was split
    into "chrome wired once" (back link, redetect/next, transport buttons, `wireVideo`) and the new
    `wireEditableRegion()`, which both the initial render and every `refreshTrimBody()` call re-wire
    identically. `.vid`/`<video>` is never touched by either path.
  - Marker **selection** (clicking a row or a bar) no longer re-renders at all — a new `selectMarker()`
    just toggles the `sel`/`on` classes and updates the play-pill, in place.
- FR-189-4: `PUT /api/segments/{itemId}/{kind}` now passes `checkedAt = existing?.checkedAt` to
  `upsertSegment`, mirroring the existing `locked = existing?.locked ?: false` — an edit no longer drops
  a row's confirmation. A brand-new marker (`existing == null`) still starts unconfirmed, unchanged.
- FR-189-5: a locked marker now toasts *"{Kind} is locked — unlock it to change the time"* from every
  entry point (mouse ± steppers, drag `mousedown`, and the `I`/`O` keyboard shortcuts, which previously
  silently no-op'ed on a locked segment instead of reaching the toast the lock already had words for).
- FR-189-6: every `SegmentApi` write in this file now checks its boolean result and toasts on failure —
  not just the new `applyEditInPlace` path, but also lock/unlock, remove, add and apply-Jellyfin, which
  previously assumed success unconditionally.
- `compileKotlinWasmJs`/`compileKotlinLinuxX64` clean, `linuxX64Test` green. **No test added for the
  `checkedAt` preservation itself** — there is no in-memory-DB test harness for `MediaSegmentStore` in
  this codebase to build one against cheaply, and none exists for wasmJs UI code at all (no
  `wasmJsTest` source set in the repo) — flagged honestly rather than claimed as covered. The fix is a
  one-line mirror of the already-proven `locked` preservation on the same line.

## Problem

Phase 163 shipped the editing controls and they are all wired: the drag handles call
`SegmentApi.editSegment`, the ± steppers call `nudgeSegment` → `editSegment`, and the PUT route writes
through correctly. The write path is not broken. Four separate defects on the *client* make the
controls behave as if nothing happened.

### 1. The drag handles stop existing the moment the video loads

`renderTrim` draws the timeline from the backend's `durationSec`, which
(`SegmentRoutes.kt:457`, `durationSecOf`) is **TMDB's whole-minute runtime** — an estimate, by design.
When the `<video>` fires `loadedmetadata`, `correctDuration()` (`Segments.kt:655-668`) patches the
timeline to the frame-accurate figure by replacing the track wholesale:

```kotlin
document.getElementById("seg-track")?.innerHTML =
    """<div class="grid"></div>${buildTrack(corrected.segments, newDurationSec, trimSelectedKind)}<div class="play" id="seg-playhead"></div>"""
```

`wireDragHandles` (`Segments.kt:703-749`) attached its `mousedown` listeners to the `.h` elements
that call created, and the marker-select `mousedown` listeners (`Segments.kt:416-426`) to the `.seg`
bars. `innerHTML =` destroys every one of those nodes. **Nothing re-wires them.** The new handles are
inert DOM.

`correctDuration` returns early only when the two durations agree within 1 s, which for a
whole-minute TMDB runtime versus a real file essentially never happens. So the handles die on
every render, a few hundred milliseconds after the view appears — before an operator can reach them.

The bar-click "select this marker" listener dies with them, so clicking a segment on the timeline
also stops selecting it.

### 2. The ± steppers move by one frame, and the readout is whole seconds

`nudgeSegment(data, scope, kind, edge, delta * 40L)` (`Segments.kt:474`) — 40 ms per click, matching
the `,`/`.` keyboard frame-nudge. The timecode it changes is rendered by `fmtl()`
(`Segments.kt:50-53`, `Segments.kt:277-279`), which rounds to whole seconds, and the bar's width
changes by 0.0015 % on a 45-minute episode. **25 clicks are needed before anything on screen
changes.** The control works; its effect is unobservable, which is indistinguishable from broken.

There is no unit label, no per-click feedback, and no coarse step anywhere in the UI — the keyboard's
`⇧,`/`⇧.` second-step has no pointer equivalent.

### 3. Every edit tears down and restarts the video

`refreshTrim` (`Segments.kt:378-384`) re-fetches and calls `renderTrim`, which rewrites
`root.innerHTML` — including the `<video id="seg-video">` element — and `wireVideo` then re-requests
`streamUrl` and re-assigns `video.src`. So a single ± click or a completed drag **restarts the whole
stream from scratch** and pauses playback.

The file's own comments show this was understood and deliberately avoided elsewhere:

> *"a lightweight DOM update, not a full re-render: re-rendering would tear down and recreate
> `<video>`, restarting the stream"* — `Segments.kt:427-428`

The edit path is the one place that does exactly what those comments forbid, and it is the path an
operator uses most.

### 4. Editing a marker silently un-confirms the episode

`upsertSegment` is `INSERT OR REPLACE` (`MediaSegment.sq`), and the manual-edit route
(`SegmentRoutes.kt:205-214`) passes no `checkedAt`, so it defaults to `null`:

```kotlin
val existing = segmentStore.getSegment(itemId, episodeKey, episodeNumber, kind)
segmentStore.upsertSegment(..., SegmentSource.MANUAL, null, locked = existing?.locked ?: false)
```

The route deliberately preserves `locked` from `existing`. It does not preserve `checked_at`. Since
`checked` is `rows.any { it.checkedAt != null }` (`SegmentRoutes.kt:438`), nudging a marker on a
confirmed episode drops that row's confirmation — and if it was the only row, the episode reverts from
**Confirmed** to **not confirmed** in the header chip and the season sheet. Adjusting a marker is the
most likely thing an operator does *after* confirming an episode, so the state they just established
is destroyed by the next refinement.

## Goal

The three ways to change a marker — drag a handle, click ±, press `,`/`.`/`I`/`O` — all work, keep
working for as long as the editor is open, visibly change something on every interaction, and never
disturb playback or the episode's confirmed state.

## Requirements

### FR-189-1 — A control stays wired for the life of the view

No code path may replace listener-bearing DOM without re-attaching its listeners. Concretely:
`correctDuration` must either re-run the track's wiring after rewriting it, or (preferred) stop
rewriting it — patch the existing `.seg`/`.h`/`.ruler` nodes' `style.left`/`style.width` in place, the
same way `updatePlayheadDom` already patches the playhead. Whichever is chosen, dragging a handle must
work at any point after the video has loaded, not only before.

The marker-select `mousedown` on `.seg[data-s]` is covered by the same rule.

### FR-189-2 — A stepper click changes something a human can see

The ± steppers move the edge by a **coarse step of 1 s** (not 40 ms), and the marker row's timecodes
gain **tenths** (`MM:SS.d`) so a fine nudge is still legible. The frame-accurate 40 ms step stays on
`,`/`.`; `⇧,`/`⇧.` stays at 1 s. Hold-to-repeat on ± is out of scope.

The unit must be stated somewhere the operator can see it — the existing keyboard-help block in the
rail (`Segments.kt:306-310`) is the natural home, and it must be corrected to match whatever the ±
buttons actually do.

### FR-189-3 — An edit never restarts playback

After a successful `editSegment`, the view updates the changed marker's bar and its row **in place**.
`renderTrim` (full innerHTML replacement) is reserved for a genuine navigation — opening a different
episode/movie, or the initial load. The `<video>` element and its `src` survive every edit; position
and play/pause state are unchanged.

The same applies to `setLock`, `deleteSegment`, the `＋ Kind` add flow and the "Use Jellyfin's …"
apply — all four currently call `refreshTrim`.

### FR-189-4 — Changing a marker's time never changes whether it was checked

`PUT /api/segments/{itemId}/{kind}` preserves `checked_at` from the existing row exactly as it already
preserves `locked`. This is a route-level fix, not a store-level one: `upsertSegment`'s
`INSERT OR REPLACE` semantics stay as they are (detection paths deliberately reset confirmation), and
the two callers that *should* clear it (`applyConsensusToTargets` immediately re-stamps via
`setChecked`) are unaffected.

Adding a brand-new marker to an already-confirmed episode is **not** confirmed by inheritance — a new
`kind` row starts with `checked_at = null`, which is the existing behaviour and stays.

### FR-189-5 — A locked marker says so instead of doing nothing

`nudgeSegment` returns silently on a locked segment (`Segments.kt:673`), the keyboard handlers skip
silently, and `buildTrack` simply omits the handles. Pressing ± on a locked marker must produce the
toast the lock already has words for — *"{Kind} is locked — unlock it to change the time"* — rather
than no response, since "nothing happens" is the exact symptom this phase exists to eliminate and an
operator cannot distinguish the two causes.

### FR-189-6 — A failed write is reported

`SegmentApi.editSegment` returns `Boolean` and every caller discards it (`Segments.kt:679`, `:739`,
`:766`, `:767`, `:488`, `:559`). A rejected or failed write must surface as a toast and the marker must
snap back to its stored value, so a silent server-side failure can never again look like a dead
control.

## Non-goals

- No change to the detection tiers, the source-precedence rules (Phase 170) or the lock semantics
  (Phase 151's guarantee, extended by 163).
- No change to the season sheet, bulk actions or consensus.
- No new marker kinds, no undo/redo stack, no multi-marker selection on the timeline.
- Not a rewrite of `Segments.kt` into a reactive renderer. The file's existing "patch the DOM you own,
  re-render only on navigation" idiom is correct; this phase applies it consistently.

## Acceptance

1. Open a series episode's trim view, wait for the video to appear, then drag the credits marker's
   right handle. The bar follows the pointer, the write lands, and the handle can be dragged **again**
   without reloading the page.
2. Click `＋ Credits` on a movie with no credits marker, then immediately drag the new marker and then
   click its `+` steppers. Both work.
3. One `+` click moves the end by 1 s and the on-screen timecode changes.
4. With the video playing, click ± five times: the video keeps playing, position does not jump, and
   the marker moves 5 s.
5. Confirm an episode (`Save & next` or the sheet's "Fine as it is"), reopen it, nudge the intro by
   1 s: the header still reads **confirmed**, and the season sheet's checked count is unchanged.
6. Lock a marker, press `+`: a toast explains why nothing moved.
7. With the backend stopped mid-session, drag a handle: a toast reports the failure and the bar
   returns to where it was.

## Source references

- `src/wasmJsMain/kotlin/dev/jellystructure/ui/Segments.kt`
  — `correctDuration` `:655-668` (destroys the track), `wireDragHandles` `:703-749`,
  bar-select `:416-426`, stepper wiring `:467-477`, `nudgeSegment` `:670-682` (40 ms),
  `buildMarkRow` timecodes `:271-284`, `refreshTrim` `:378-384`, `renderTrim` `:339-376`,
  `wireVideo` `:616-648`, `updatePlayheadDom` `:574-582` (the correct in-place idiom),
  keyboard help `:306-310`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/SegmentRoutes.kt`
  — manual-edit PUT `:203-214`, `durationSecOf` `:451-458`, `checked` derivation `:438`.
- `src/commonMain/sqldelight/dev/jellystructure/db/MediaSegment.sq` — `upsertSegment` (`INSERT OR
  REPLACE`), `setChecked`.
- `specs/requirements/phase-163-segment-editor.md` — the phase this repairs.

## Open questions

1. **Not reproduced in a browser.** Every defect above is read off the source and each independently
   produces the reported symptom, but the exact order in which they present (does the first drag work
   before `loadedmetadata` lands?) has not been observed live. A single session with the browser
   console open on `#/segments?series=…&episode=…` would confirm the sequence and is worth doing
   before the fix, so the fix can be verified against the same trace.
2. Should `correctDuration` exist at all? An alternative is to give the backend the real duration —
   `Track` already carries per-file stream data from ffprobe, and Phase 185 added `video_bitrate` to it
   by the same route. A `duration_ms` on `Track` would remove the estimate, the correction and this
   whole class of DOM-replacement bug, at the cost of a scan-time backfill. Recorded as a candidate,
   deliberately out of this phase's scope.
