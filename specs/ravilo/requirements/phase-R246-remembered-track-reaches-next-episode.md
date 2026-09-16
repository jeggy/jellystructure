# Phase R246 — A remembered subtitle must actually reach the next episode

> The 2026-09-14 user report, verbatim: *"When choosing a subtitle for a series while watching within
> ravilo and we start playing the next episode, it doesn't remember it and chooses something else
> (probably the default one)."* **R241** diagnosed an ISO-639 granularity mismatch and fixed the
> comparison. On 2026-09-16, with R241's build installed on the stue TV, the report reproduced three
> times in one session. The comparison was never the problem — the resolver is handed language groups
> that are one composition behind the track list it is resolving against, which on the tick the tracks
> arrive means **empty**.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F1). Not dev-reviewed, not built.
Client-only (`ravilo-ui` commonMain); no backend, DTO or admin change.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 218, Ravilo through
R245. Ravilo-only, no admin pair.

## What was observed (stue TV, v1.17, profile `jogvan`)

All on *It's Always Sunny in Philadelphia*, the title R241 was written against.

| Step | File shape (from the production DB) | Expected | Got |
|---|---|---|---|
| S17E07 → Audio & Subs → **Dansk** | Danish embedded, tagged `dan`, PLAIN | Danish subs | Danish subs ✅ |
| Episode rail → **S17E08** | Danish is an **external sidecar** `.da.hi.srt`, tagged `da`, **SDH**, the only Danish version; English embedded, `default: true` | Danish (SDH, the only Danish there is) | **✓ English** |
| Fresh push from the detail page → **S08E04** | Danish external `da` PLAIN; nothing flagged default | Danish (the series memory from S17E07) | **✓ Off** |
| Pick **Dansk** on S08E04 → Next → … → **S08E09** | Danish external `da` PLAIN; nothing flagged default | Danish | **✓ Off** |

A manual pick always works. It never survives to the next file.

**What was selected is, in every case, exactly what the source-default fallback tier produces** —
English where a track carries `default: true`, Off where none does. That is the fingerprint of the
remembered tiers returning `null` outright, not of a near-miss inside a language group.

## What the code does (traced against `main`, 2026-09-16)

`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`:

1. `audioTracks` / `subtitleTracks` are snapshot state (`:290-291`).
2. `audioGroups` (`:421`), `subVersionOptions` (`:427`) and `subGroups` (`:428`) are `remember(...)`
   values — **computed during composition**, from whatever the track lists were at that composition.
3. `currentAudioGroups` / `currentSubGroups` are `rememberUpdatedState(...)` (R196's fix) — also
   refreshed **only at composition**.
4. The poll loop (`LaunchedEffect(Unit)`, `:784` onward, `POLL_MS = 500`) does, in one tick with no
   suspension point between the lines:
   ```kotlin
   audioTracks = player.audioTracks
   subtitleTracks = player.subtitleTracks
   if (playerLoadedForCurrentItem && resolvedForItemId != currentItemId && audioTracks.isNotEmpty()) {
       resolveTrackSelection()          // reads currentAudioGroups / currentSubGroups
       resolvedForItemId = currentItemId
   }
   ```
   `loadedForItemId` (`:779`) is set when the stream ticket loads — before any track exists — so the
   gate opens on **the first tick the tracks arrive**. `resolveTrackSelection()` (`:595`) then calls
   `resolveTrackChoice(seriesChoice, globalChoice, currentAudioGroups, currentSubGroups, audioTracks,
   subtitleTracks)` with fresh *lists* but groups built from the **previous** composition.
5. What the previous composition held:
   - a fresh screen, or the post-advance gap where ExoPlayer's `currentTracks` is empty: **no groups**;
   - a new item that prepared within one tick of the advance: the **outgoing** episode's groups.
6. In `tierSub` (`:3517-3527`): with no groups, `subGroups.firstOrNull { sameLanguage(...) }` is null →
   `return null` for the series tier and the global tier alike → source-default tier (`:3532-3541`)
   → English on S17E08, Off on S08E04/E09. With the outgoing episode's groups, the R241 match
   *succeeds* and is then discarded by `native = group.versions.filter { it.flatIndex <
   subtitleTracks.size }` (`:3524`) — S17E07's Danish sits at flatIndex 3, S17E08 has 3 tracks. Null
   again.
7. The audio tier has the identical lag and falls to the default track the same way — which on every
   file in this library is the right answer by coincidence, so it never looked wrong.

**R241's `sameLanguage()` is correct and stays.** Its test
(`PlayerScreenTrackResolutionTest.kt:210-230`) passes because it calls `resolveTrackChoice` with
groups built from the same list it resolves against — the one thing the composable never does on the
tick that matters. **R196** (`phase-R196-remembered-track-regression.md`) fixed the version of this
where the poll loop saw the *first* composition's groups forever; `rememberUpdatedState` narrowed that
to "the last composition's groups", and the last composition before the tracks-arrive tick is the one
built without them. This is R196's residue, not a new class of bug.

Two things the sweep checked and ruled out, recorded so nobody re-derives them:

- **`persistChoice`'s key.** `seriesKey = currentSeriesId ?: currentItemId` (`:565`, `:597`). Every
  entry point passes a real `seriesId` (`RaviloApp.kt:550, 770, 1006, 1036, 1076, 1118`), and the
  global choice is written alongside (`:575`) — even a wrong series key would have been rescued by the
  global tier. Not the cause.
- **Embedded vs sidecar.** Across all 183 episodes: **77 `dan` tracks, every one embedded; 16 `da`
  tracks, every one an external sidecar** — the granularity split R241 blamed is a perfect proxy for
  "lives in a separate `.srt`". Sideloaded tracks *are* in `exo.currentTracks` by the time the picker
  opens (`RaviloPlayerAndroid.kt:422-441`; `MediaItem.SubtitleConfiguration` at `:226-231`), so they
  are not absent — but every repro involved one, and the fixture in FR-R246-3 keeps them.

## Requirements

**FR-R246-1 — The resolver builds its own groups from the lists it is handed.** `resolveTrackChoice`
loses its `audioGroups` / `subGroups` parameters and derives both, internally, via the same
`buildLanguageGroups` the picker uses, from `audioTracks`, `subtitleTracks` (plus the encode/PGS list,
`originalLanguage` and the picker language it needs for badges). No caller can hand it a stale group
list because no caller hands it a group list. The composable's `audioGroups`/`subGroups` stay for the
picker UI and nothing else.

**FR-R246-2 — One tick, one snapshot.** The poll tick resolves against the exact lists it read in that
tick. Anything `resolveTrackSelection()` consumes that is derived from the track lists is derived
inside the tick, not read from composed state. `currentAudioGroups`/`currentSubGroups` are deleted;
their only reader was the bug.

**FR-R246-3 — A regression test built from the real files.** A commonTest fixture reproducing the
three observed transitions from the production shapes — (a) embedded `dan` PLAIN → external `da`
**SDH-only** + English `default: true`; (b) embedded `dan` PLAIN → external `da` PLAIN with no default
flag; (c) external `da` PLAIN → external `da` PLAIN — asserting Danish is selected on the second file
given a `RememberedChoice` learned from the first. Case (a) also pins R241's FR-R241-2: an SDH-only
group still satisfies a remembered plain choice. These tests call the new tracks-only signature, so
they cannot be satisfied by a resolver that depends on anything but the lists.

**FR-R246-4 — The audio tier is proved the same way.** One fixture where the remembered audio
language is not the file's default (a dubbed kids' title with both tracks), asserting the remembered
one wins on the next file.

**FR-R246-5 — Resolve again if the track set grows.** If a later tick reports a strictly larger track
set for the same item (an HLS session whose subtitle renditions appear after prepare, or a sideload
that arrives late) and the viewer has not picked manually since the first resolve, the resolver runs
once more against the grown set. Keyed on a hash of the track set, not on `resolvedForItemId` alone.

**FR-R246-6 — On-device, on the reported title, before `✓ Built`.** S17E07 → S17E08 and S08E04 →
S08E05 on the stue TV, release build, Danish carried both times. R241 shipped without this and its
own open question said so; this phase does not get to leave it open again.

## Non-goals

- Language name tables, endonyms, flags, the `Dubbed` badge and where `sameLanguage()` lives — R247.
- Picker layout or behaviour (R180/R195/R197/R238 own it).
- Re-tagging the library's sidecar language codes — real per-file data, unrelated.

## Verification

1. `PlayerScreenTrackResolutionTest` green with FR-R246-3/4's cases added; the old
   `resolveTrackChoice(…, audioGroups, subGroups, …)` signature no longer compiles.
2. Release build on the stue TV: the two transitions in FR-R246-6, plus one movie (a movie is its own
   remembered bucket, R181) to confirm nothing regressed for the non-series path.
3. Logcat shows exactly one resolve per item per track-set, none against an empty list.

## Open questions

- **Was the remembered audio language ever reaching a next episode?** The lag is symmetric; the
  default track has masked it. Worth one household check on a dubbed series after the fix.
- Whether FR-R246-5's re-resolve should be allowed to *change* an already-visible selection, or only
  fill in an `Off` that resolved before the subtitle group existed. Recommendation: only the latter.
