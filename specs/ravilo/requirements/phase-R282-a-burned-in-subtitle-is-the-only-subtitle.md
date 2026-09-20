# Phase R282 — A burned-in subtitle is the only subtitle

> Client half of [[phase-252-a-burn-in-ticket-says-what-it-burned]] — read its Investigation first;
> the measurements live there. Supersedes R56's restream handling and R209's *"no way to undo it
> short of restarting playback"*.

**Status:** ✓ Built 2026-09-20 (same day as written), not dev-reviewed, **not verified on a device.** `:ravilo-ui` Android + wasmJs compile clean; `BurnedInSubtitleTest` 12/12 and the existing `PlayerScreenTrackResolutionTest` 20/20; a local R8 release APK passes `check-player-dex.sh` at **227** registers (was 234; limit 250) — all new state went into `PlayerBookkeeping`. ⚠ The load-effect wiring itself (text off on a burn-in ticket, resolver re-arm on an un-burn) is only exercisable on a device.

## The bug, client side
Report: on *Honeyman*, picking English reloads the film and then shows **two subtitles at once**.

The player has **two independent subtitle mechanisms and nothing that relates them**:

| | text tracks | burn-in |
|---|---|---|
| state | `selectedSub` + ExoPlayer's `trackSelectionParameters` | none — it exists only as a URL parameter |
| set by | `player.selectSubtitleTrack(i)` | `store.restreamWithSub()` |
| cleared by | picking *Off* | **nothing** |

`choosePick()`'s encode branch (`PlayerScreen.kt`) calls `restreamWithSub()` and touches neither
`selectedSub` nor the player. The reload reuses the same `ExoPlayer`; its text selection (type
enabled + an override for the previously chosen group, and the sideloaded groups are rebuilt
identically) survives `load()`. R181's resolver does not re-run either — `resolvedForItemId` still
equals the item. So a text subtitle that was on **stays on, over the burned-in one**.

The same gap produces three quieter faults:
1. After a burn-in the picker still marks the *old* text track (or *Off*) as selected — the English
   the viewer is reading is selected nowhere.
2. Picking *Off* or a text track afterwards cannot remove the burned-in subtitle; picking a text
   track *adds* a second one again.
3. R246's grown-track-set re-resolve is gated on `selectedSub == -1`, which is true during a burn-in
   — a late-arriving sideload could auto-select a text track on top of it.

## Requirements

### FR-R282-1 — A burn-in ticket turns every text track off
When a `Ready` ticket carries `burnedSubtitleIndex` (252 FR-252-1), the load effect, after
`player.load()`, calls `player.selectSubtitleTrack(-1)` and sets `selectedSub = -1`. Unconditional —
not "if one was on".

### FR-R282-2 — While burned in, nothing automatic selects a text track
`resolveTrackSelection()` applies `-1` for subtitles while a burn-in is active (audio resolves as
normal), and R246's re-resolve gate additionally requires no active burn-in.

### FR-R282-3 — The picker shows the burned-in track as the selection
Everything that reads "which subtitle is selected" for display (level-1 row, level-2 focus, the
collapsed row's flag) reads one derived value: the burned-in track's flat index when a burn-in is
active, else `selectedSub`. Server-pushed state, never remembered client-side: it comes from the
current ticket and nowhere else.

### FR-R282-4 — Leaving a burn-in is a restream without one
While a burn-in is active:
- picking the **same** burned track is a no-op (no reload);
- picking **another PGS** track restreams with that index (as today — it replaces the burn);
- picking ***Off* or a text track** persists the choice (R181/R195, unchanged), then restreams with
  index `-1` (252 FR-252-2). When that ticket loads, the resolver is re-armed
  (`resolvedForItemId = null`) and selects against the **fresh** track set from the just-persisted
  choice — by variant signature, then language — because an un-burn may return to direct play, where
  the track list is a different list and a remembered flat index would be a lie.

### FR-R282-5 — The restream carries the session's capabilities
`PlayerStore` keeps the `ClientCapabilities` it built for `startPlayback` and sends them with every
restream (252 FR-252-3), so an un-burn negotiates as the real device.

### FR-R282-6 — No new chrome, no new strings
The reload keeps R218's existing presentation. Nothing tells the viewer a subtitle is "burned in" —
R180 FR-RV-ASP1-2: nothing may vary by delivery method.

## Invariants
- **One subtitle on screen, ever.** Burn-in active ⇒ text renderer disabled; text track active ⇒ the
  ticket has no burn-in.
- The picker's selection always names what is on screen.
- `PlayerScreen`'s register budget (`scripts/check-player-dex.sh`): new state lives in
  `PlayerBookkeeping`; no new `remember` locals in the composable body.

## Out of scope
- Wasm/`ravilo-web` native PGS, the receiver apps, Live TV.
- Auto-selecting a PGS track from a remembered language (R181's scope note stands: a burn-in is
  never started automatically).

## Verification
- Unit (pure): the derived-selection helper and the pick-decision helper.
- Compile: `:ravilo-ui` Android + wasmJs. ⚠ `check-player-dex.sh` needs a release APK — not run here.
- On device (owner-gated, Pixel 9 debug **and** a release build before the TV): 252's three-step
  script, plus navigate away and back (Back → detail → Play) with English still remembered.
