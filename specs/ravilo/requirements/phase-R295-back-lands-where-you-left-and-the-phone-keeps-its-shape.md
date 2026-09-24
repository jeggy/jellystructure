# Phase R295 — Back lands where you left, and the phone keeps its shape

> Owner, 2026-09-24, on the findings from that day's TV and Pixel 9 sweep: *"Yes, let's fix it, so going
> back goes exactly onto the same item you originally pressed"* and, of the phone findings, *"Let's fix
> all of these."*

## Status

`Planned` — written 2026-09-24 from the soveværelse-TV and Pixel 9 Pro sweep (Play Store v1.36 and debug
`1.37-7`), the fixes written the same day. Not dev-reviewed. Six small defects, none big enough for a phase
of its own, all of the same kind: the screen the viewer comes back to, or the first frame of the next one,
is not the one they should see.

## Requirements

### FR-R295-1 — Back from a search result lands on that result
**Seen:** on the TV, Search → open a result → Back put focus in the text field and raised the on-screen
keyboard over the results (Play Store v1.36 and debug alike, five times in a row). The viewer had to
dismiss the keyboard and walk back to the tile. On the phone the grid came back scrolled to the top.
**Cause:** R277 FR-R277-1 focuses the field and raises the keyboard "on entry", and a return from a
detail page is an entry: the composable is rebuilt, and only the store (R259) survives it.
**Requirement:** the store remembers which result was opened and on which *visit* of the page.
`Dest.Search` carries a `visit` that is new on every push and kept by `copy()`. Coming Back on the same
visit, the grid opens scrolled to that tile's row and, on a TV, focus lands on the tile itself. Arriving
afresh (a new push, the bottom bar, the app bar's search) keeps R277 exactly: text field and keyboard.
If the opened title is no longer in the results, R277's entry applies.

### FR-R295-2 — On a phone, Back out of the audio & subtitles sheet goes back to the picture
**Seen:** from the versions list, leaving the player took four Backs: versions → languages → close sheet
→ hide chrome → leave. **Cause:** `pickerBack()` ends in `wake()`, so closing the sheet re-raised the
chrome, which then needed its own Back. **Requirement:** on a handset, Back that closes the sheet from
its first level also hides the chrome. The TV is unchanged: its picker closes onto *Audio & Subs*, where
the D-pad needs a focused control to land on.

### FR-R295-3 — The detail hero fills the list's visible height, not the window's
**Seen:** on the phone, a detail page opened after watching sat about 120 px lower than the same page
opened fresh, with Play/Resume cut off at the bottom edge (both builds). **Cause:** the movie and series
heroes were sized to `LocalWindowInfo.containerSize.height`, the whole window, while the list sits
inside the safe-area insets. So the hero always overhung the list by the system bars, and by a different
amount right after the bars-hidden landscape player. **Requirement:** the hero item is
`fillParentMaxHeight()`, the list's own viewport. A TV has no insets, so the number there is unchanged.

### FR-R295-4 — A button's minimum width is the pill's width
**Seen:** a short primary label (*Play*, *Resume · S2E19*) was drawn indented from the page's left edge,
with a gap before *Mark Watched*, on the phone and the TV alike. **Cause:** `RaviloButton`'s callers pass
`widthIn(min = …)` (200 dp on the detail page, sized for the longest label so Play→Resume never shifts
the row). It reached only the invisible, focusable outer box, and the visible pill wrapped its label and
was centred inside it. **Requirement:** the outer box propagates its minimum constraints, so the pill is
at least the caller's width, with the label centred in it.

### FR-R295-5 — The phone's player shows nothing until the window has its final shape
**Seen:** starting playback with the phone standing in landscape, R218's start screen was drawn in
portrait for about a second, then the phone rotated. **Cause:** the phone's screens are pinned to portrait
in the manifest. The player switches to follow the sensor (R244 FR-R244-7) from an effect, after its
first frame, and the rotation that follows is asynchronous. **Requirement:** on a handset, the player
covers everything it draws with plain black until the window's shape changes or 700 ms pass, whichever
is first. A phone held upright never rotates and only waits out the 700 ms, which R218's own 400 ms
debounce mostly overlaps. The TV never waits.

### FR-R295-6 — A tile's two top badges never overlap
**Seen:** in the phone's search suggestions, a *Soon · S01E2…* pill ran underneath the watched ✓ on a
narrow tile. **Cause:** R249's two corners were anchored independently. **Requirement:** both corners
are laid out in one row. The top-end badge keeps its place, and the top-start badge gets the remaining
width and ends in an ellipsis. Same 8 dp inset as before, so a wide TV tile draws exactly what it did.

## Also taken here, owed to R290
**R290 FR-R290-5** — Back while nothing is playing yet (negotiating, or the start screen) now leaves at
once. On a phone it took two presses, because `chromeVisible` starts true and the first Back only hid a
chrome drawn behind the loader. Found by another session's device test on 2026-09-24; built in the same
`playerBack` change as FR-R295-2. R290 is otherwise still `Planned`.

## Noted in the same sweep, not in this phase
- On a phone, returning to a Continue Watching card draws the TV's focus ring and scale on it, and R240's
  focus-detail line appears at the foot of the page, over the next row. Both are TV surfaces reaching a
  touch screen. Recorded for a phase of their own.

## Invariants
- No new strings. No TV pixel moves except FR-R295-4's corrected pill width.
- `PlayerScreen`'s dex register budget (`scripts/check-player-dex.sh`) stays under its limit.

## Verification
- Unit: the search return (store remembers the opened id per visit and forgets it on a new visit).
- Compile: `:ravilo-ui` Android + wasmJs; the release dex guard.
- Pixel 9 (debug build, owner-authorised): each of FR-R295-1…6 captured before and after. The TV only
  when the owner allows it again.
