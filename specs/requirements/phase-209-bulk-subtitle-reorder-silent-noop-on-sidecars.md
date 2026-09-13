# Phase 209 — Bulk subtitle re-order is a silent no-op when subtitles are sidecar files

> User report, 2026-09-14, verbatim: "It doesn't work to mass reorder subtitles for a series." Audio
> mass-reorder on the same wizard was confirmed working the same session — the bug is specific to
> subtitles.

## Status
✓ Built 2026-09-14 — spec'd and fixed same day. Backend + frontend (admin WASM), no Ravilo
counterpart — the bulk track-reorder wizard (Phase 96) is admin-only. `compileKotlinLinuxX64` and
`compileKotlinWasmJs` both clean; not dev-reviewed, not deployed, not live-tested against a real
sidecar-only series.

## The finding

### Why audio works and subtitles don't

`renderBulkReorderWizard` (`BulkReorderWizard.kt`) drives a 3-step wizard: pick a target track order
(Step 1) → server classifies every episode against it (Step 2, dry run) → apply as a background job
(Step 3). Classification happens in `classifyEpisode` (`TrackRoutes.kt:790`):

```kotlin
val tracks = ep.tracks.filter { it.kind == kind && !it.external }
if (tracks.size <= 1) {
    val summary = tracks.toSummary(targetSet)
    return BulkPlanEpisode(ep.filename, code, ep.title, "nothing_to_do", summary, summary,
        "≤1 track of this kind", 0.0, false)
}
```

Excluding `external` tracks is **correct** — a sidecar `.srt` file has no container stream to
reorder, the exact constraint Phase 200 already documented ("a sidecar has no container flag to
set") and that the single-episode editor (`/tracks/reorder`, `TrackRoutes.kt:448`) enforces with an
explicit 400 `"cannot reorder an external subtitle track"`. Audio is virtually never delivered as a
sidecar file, so this filter almost never removes anything for `kind=audio` — which is why the
reporter's audio test passed. This household's library, per Phase 200's own finding, carries
subtitles as sidecar files routinely, so for `kind=subtitle` the filter frequently reduces `tracks`
to 0 or 1, and every one of those episodes is classified `nothing_to_do` with the reason
**"≤1 track of this kind"** — true of the *embedded* count, false-sounding when the episode actually
has three sidecar subtitle files sitting right next to it. Nothing in that string, or anywhere else
in the response, says the word "sidecar."

### The explanation exists but the UI has no way to show it

`BulkPlanEpisode.reason` is rendered in Step 2's per-episode expandable panel
(`buildEpisodeRow2`, `BulkReorderWizard.kt:400-447`), but that panel is `display:none` and the *only*
element wired to reveal it is a "+N" chip that itself only renders when `tracks.size - 3 > 0`
(line 411, `moreCount`). For an episode with 0-2 embedded tracks of the target kind — precisely the
`nothing_to_do` case this bug produces — there is no chip, so there is **no way in the UI to ever see
the reason text**, correct or not. The user sees a page full of "Nothing to do" badges and nothing
else. That is the entire experience the report described as "it doesn't work."

### Step 1 makes it worse before Step 2 even runs

`detectInitialLanguages` (`BulkReorderWizard.kt:54-63`) seeds the Step 1 target-order list by
scanning `ep.tracks` for the selected kind **without excluding `external`**:

```kotlin
for (ep in eps) for (t in ep.tracks) {
    if (t.kind == trackKind && t.language != null) seen.add(t.language.lowercase())
}
```

So Step 1 happily offers "English, Danish" as detected subtitle languages when those languages exist
*only* as sidecar files — languages `classifyEpisode` can never actually act on. The wizard invites
the user to build a plan around tracks it already knows it will reject, then fails to say so.

## Requirements

**FR-209-1 — the reason must name what was excluded.** When `classifyEpisode`'s `tracks.size <= 1`
branch fires and one or more same-kind tracks exist on the episode but were excluded because
`external == true`, the reason string must say so explicitly (count + "sidecar subtitle(s)" /
"external track(s)", plus that a sidecar has no container order to change) instead of the generic
"≤1 track of this kind", which stays only for the case where the episode genuinely has ≤1 track of
that kind at all, embedded or not.

**FR-209-2 — the reason must be visible without a coincidental chip.** Step 2's expand affordance for
the "Reason" panel must not depend on `tracks.size > 3`. Every row gets a way to reveal its reason
(e.g. the status badge itself becomes the toggle), regardless of track count.

**FR-209-3 — Step 1 must not seed target languages from tracks that can never be reordered.**
`detectInitialLanguages` excludes `external` tracks, matching the constraint `classifyEpisode` already
enforces, so the target-order list only ever contains languages the plan could actually act on.

**FR-209-4 — an upfront warning when a whole scope is sidecar-only.** If the selected scope (series or
season) has zero embedded tracks of the selected kind anywhere but does have external tracks of that
kind, Step 1 shows a warning banner before the user proceeds to Step 2, naming the sidecar constraint
and the affected episode count — rather than letting the user build a plan, click through, and land on
a wall of unexplained "Nothing to do" rows.

## Out of scope

- **Making sidecar subtitles reorderable.** They have no container to hold an order; this is a real,
  permanent constraint (Phase 200), not a bug. This phase is entirely about explaining the constraint,
  not lifting it.
- **The apply-time filtering** (`TrackRoutes.kt:566`, `632`) — already consistent with the plan-time
  filter fixed here; no behavior change needed there.
- **The vestigial `partial`/opt-in UI path.** `classifyEpisode` still returns `"partial"` (see the
  `missing.isNotEmpty()` branch, `TrackRoutes.kt:814`) so the opt-in affordance is live, not dead code
  as a prior investigation pass suspected — that suspicion is retracted here after re-reading the
  current function; no change made.

## Open questions

None — this is a self-contained UX/messaging fix with no design decision left open.
