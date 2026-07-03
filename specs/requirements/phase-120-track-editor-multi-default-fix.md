# Phase 120 — Track editor: "Keep this one" resolves multi-default audio for ANY track (FR-TK1)

## Goal
In the Movie/Series detail **"Tracks & order"** editor, when a title has multiple audio tracks all
marked default, clicking **"★ keep this one"** on ANY track — including the **top/first** default track
— must stage the fix, show the mkvpropedit command preview, and enable **Apply**. Today it silently
does nothing when the chosen track is the first default, forcing the user's convoluted two-step
workaround.

## Root cause (verified in code — frontend only; backend is correct)
Everything is in `src/wasmJsMain/kotlin/dev/jellystructure/ui/TrackEditor.kt` (one source, used for
movies `"trk"` and episodes `"te"` via `MediaDetail.kt`).
- "keep this one" mutates the local model correctly: `arr.forEach { it.def = false }; arr[i].def = true`
  (`:575-578`) — it *does* stage "set this one, clear the rest."
- The **default-change diff**, computed identically in three functions, compares only the **first**
  default track's specifier on each side:
  `origDefAudio = audioOriginal.firstOrNull { it.def }?.sp` vs
  `newDefAudio = audioModel.firstOrNull { it.def }`; shows pending/command/apply iff
  `origDefAudio != newDefAudio?.sp`.
  - `renderPending()` (`:401-407`, gates the pending panel; panel hidden when `rows.isEmpty()` `:446-447`)
  - `buildCommandText()` (`:353-359`, gates the command preview)
  - `applyChanges()` (`:498-504`, gates the `MediaApi.setDefaultTrack` call)
- With multiple defaults, `firstOrNull { it.def }` is the **top** track. Clicking "keep this one" on the
  top track leaves it first-default → both specifiers equal → diff **false** → no pending row, no
  command, no Apply, no API call. Clicking a **non-top** track changes the first-default specifier →
  diff **true** → everything appears. This exactly reproduces the reported asymmetry and workaround.
  The diff collapses a multi-default set to its first element and never notices the *other* tracks lost
  their default flag.
- Same latent bug in the **subtitle** twins (`:361-367`, `:426-431`, `:505-511`) and the cascade
  **"Fix default"** button (`:637-643`, silently suppressed when the resolved-language track is already
  one of several defaults).
- Backend is correct: `POST /media/{id}/tracks/default` (`TrackRoutes.kt:176-224`) takes one specifier
  and unconditionally sets it default + clears all same-type tracks via
  `MkvpropeditRunner.setDefault → TrackCommandBuilder.mkvDefault` (`:35-40`, `flag-default=1` on the
  kept track, `0` on the rest). It's an in-place mkvpropedit edit (flag-only, no remux — not a Phase-109
  queued job), so a single call fully resolves the multi-default synchronously.

## Requirements

### A. Per-track default diff
1. Replace the "first-default-specifier" comparison in all three functions (and the subtitle twins)
   with a **per-track flag diff**: a default change exists iff any track's current `def` differs from
   its original `def`:
   `audioModel.any { m -> audioOriginal.find { it.sp == m.sp }?.def != m.def }`
   (mirror the design mockup's builder — `design/app/series.html:844` `if (ob.def!==t.def)`). Apply to
   both the audio and subtitle blocks.
2. This makes "keep this one" on the **top** track register (the other tracks lost their default) → the
   pending row, command preview, and Apply appear. The existing command
   (`mkvDefault(kept.streamIndex, allIndices)`) already sets the kept default and clears the rest, so
   one Apply fully resolves the multi-default.
3. The cascade **"Fix default"** button inherits the fix.

### B. No backend change
`TrackRoutes` and `TriageDetection.hasMultiDefault` (`:29-34`, ≥2 audio defaults) are correct as-is.

## Scope
- `TrackEditor.kt` only: the three diff functions × (audio + subtitle), plus the cascade "Fix default"
  path. No new endpoint; mkvpropedit-vs-ffmpeg routing unchanged (default flags are flag-only
  mkvpropedit).

## Non-goals
- No change to reorder/remove flows or their Phase-109 job queuing.
- No change to the multi-default *detection* (it's correct).

## Acceptance
- A movie/episode with two default audio tracks: clicking "keep this one" on the **top** track shows
  the command preview and an enabled Apply; Apply clears the extra default in one operation and the
  multi-default triage clears.
- The same works on any non-top track (unchanged) and for subtitle defaults.
- The cascade "Fix default" button works even when the resolved-language track was already one of
  several defaults.
- The two-step workaround is no longer needed.
