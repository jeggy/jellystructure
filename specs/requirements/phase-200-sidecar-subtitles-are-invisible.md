# Phase 200 — a subtitle in a file beside the video does not exist as far as jellystructure knows

> Live question, 2026-09-06: *"does ravilo show all correct subtitles flag? sometimes something has
> both subtitles within the media file itself and sometimes they live in srt file beside it and
> sometimes both."*
>
> Embedded: yes. Sidecar: never. Both: only the embedded half.

## Status
✓ Built 2026-09-10. Design/audit-authored, not dev-reviewed, not deployed — sidecars will appear on
each title's next scan (FR-200-8), no forced repair rescan run. Ravilo counterpart: **R239** (the
render half, ⚠ Partial — new flag assets not sourced). This phase owns *what languages exist in the
payload*; R239 owns *how the strip draws them*. See STATUS.md for the build summary.

## The finding

`FfprobeRunner.probe(filePath)` (`:122`) runs `ffprobe … '<the video file>'`. It sees the container
and nothing else. `MediaItem.tracks` is therefore **embedded streams only**, and `Track`
(`model/Media.kt:66`) has no field that could even express "this one is a file next to the video" —
`isExternal` exists solely on Jellyfin's `MediaStream` (`auth/Models.kt:192`), which the scanner never
reads. Nothing anywhere in `src/` walks a title's directory looking for `.srt`.

Every subtitle surface inherits that:

| surface | source | sees sidecars |
|---|---|---|
| Ravilo detail flag strip | `DetailService.kt:61` / `:174` ← `item.tracks` | **no** |
| Admin Tracks & subtitles tab | `item.tracks` | **no** |
| Admin pagebar flag strip | `audioFlagsHtml` (`MediaDetail.kt:785`) | n/a — **audio only; there is no subtitle strip in the admin at all** |
| Admin Bazarr card (Phase 157) | Bazarr API | yes — but hides itself entirely unless Bazarr is connected *and* the title is matched, and is labelled *"sidecar files, not embedded tracks"* |
| **The player picker** | `PlaybackService.buildSubtracks:692` ← Jellyfin `MediaStreams` | **yes** |

The last two rows are the whole problem. **The player is right and the detail page is wrong**, so the
product contradicts itself: the page says a title has no Danish subtitles, you press Play, and Danish
is in the picker. R209 went to real trouble to make external delivery work
(`PlaybackService.kt:685-690` records the "Pinocchio" report); nothing was done about the fact that
nothing else in the product knows those tracks exist.

### Confirmed end to end — *40 Days Gone*

| | |
|---|---|
| store (ffprobe) | 32 subtitle tracks, **every one untagged** → `subtitleLanguages = []` → no SUBTITLES flags render |
| Jellyfin | 36 subtitle streams, **4 external**: `dan`, `eng`, `hrv`, `srp` |

Jellyfin reports the same 32 embedded streams as untagged, so this is **purely** the sidecars — not an
ffprobe-vs-Jellyfin disagreement about embedded metadata. The detail page shows nothing about
subtitles for a film that has Danish and English subtitles sitting next to it on disk.

### Scale (production, 2026-09-06)

- **3 880 sidecar subtitle files** across **8 773 video files** — matched to their own video by
  basename, AppleDouble `._` files excluded. None of them exist in the store.
- Of 310 movies, **260 (83 %)** have at least one subtitle language on disk that no surface shows.
- Missing languages by title count: **`da` 175**, `hr` 225, `sr` 211, **`en` 96**, plus `fi`/`no`/`sv`/`fo`.
- **75 movies have sidecars and zero embedded subtitles** — no subtitle information anywhere in the
  product, for a title that has subtitles.

Danish on 175 titles is the number that matters: this is a Danish/Faroese household, and the flag strip
exists precisely to answer *"can we watch this tonight"*.

### Second, independent bug — a series is judged by its first episode

`DetailService.kt:168-169`:

```kotlin
// Use the first episode's scanned tracks for flag strips (no extra Jellyfin round-trip).
val firstEpTracks = uniqueEpisodes.firstOrNull()?.tracks
```

**64 of 182 multi-episode series (35 %)** have episodes that disagree about subtitle languages:

| series | eps | strip shows | present on other episodes |
|---|---|---|---|
| Blended Household | 251 | `eng` | 20 more, incl. `dan` `swe` `nor` `fin` |
| Ruffy | 153 | 9 languages | 11 more, incl. `dan` `nor` `swe` |
| Three and a Half Uncles | 262 | `eng` | `hin` `tam` `tel` |
| Hoyrir tú vindin? | 9 | *nothing* | `fao` |

Independent of the sidecar bug and cheap to fix — the episodes are already in the item.

## The design decision this phase turns on

**Where do sidecar subtitle tracks come from?** Two credible answers, and this is the one thing worth
a dev review before any code.

**Option 1 — scan the directory ourselves (recommended).** `scan_files` already stats the video; add a
`readdir` of its folder and match `<video-basename>.<lang>[.flags].<ext>`. Keeps `Track` sourced from
the filesystem, honours the standing *Ravilo runs off jellystructure data, Jellyfin is for streaming*
rule, adds no outbound request (Phase 182/183 pacing untouched), and works with no Jellyfin
configured. **Cost: our filename parse can disagree with Jellyfin's**, and then the strip and the
picker disagree *differently* instead of agreeing — the failure mode is subtler than today's.

**Option 2 — read Jellyfin's `MediaStreams`.** Exactly what the picker uses, so agreement is
structural rather than maintained. But it makes the catalog depend on Jellyfin for a field the
decoupling plan wants local, costs a per-item round trip in the scan (`prewarmSubtitles` already pays
one, so the shape exists), and yields nothing when Jellyfin is unreachable.

Recommended: **Option 1, plus FR-200-6's reconciliation check** so a divergence from Jellyfin is
detectable instead of silent. Recorded here rather than decided silently, because the argument for
Option 2 — *the two lists must agree, so read the same list* — is genuinely strong.

### What the parser has to handle (real production filenames)

Suffix after the video basename, from the 3 880 real files:

```
.da  .en  .hr  .sr  .no  .fi  .sv  .fo  .de  .is  .nl  .pl     two-letter language
.en.hi  .da.hi  .sr.hi  .hr.hi                                 SDH
.da.forced  .fi.forced  .sv.forced  .en.forced  .no.forced     forced / signs-only
(empty)                                                        bare <stem>.srt — language undetermined
.cc                                                            caption marker, no language
_eng  _swe                                                     underscore separator, 3-letter
.persian  .arabic  .russian  .portuguese-brazil                full language names
```

Also present and correctly excluded by the basename rule: sibling episodes' subtitles in a shared
folder (`Góða ferð Føroyar S01E01.mkv` sitting beside `…S01E05.fo.srt`). A looser match would
attribute five episodes' subtitles to every episode.

`.hi` matters beyond parsing: `Track` has `default` and `forced` but **no SDH flag**, and R195 listed
SDH detection as a backend prerequisite that was deferred. 1 000+ of these files declare it in their
name — the cheapest SDH signal the project will ever get.

## Functional requirements

- **FR-200-1 — ingest sidecar subtitles.** `scan_files` discovers subtitle files that belong to the
  video by basename and records them as subtitle tracks. Applies to movies, music videos and every
  episode. Excludes AppleDouble `._` files.

- **FR-200-2 — a track says where it lives.** `Track` gains `external: Boolean` (defaulted, additive,
  no migration — the Phase 108 JSON-blob pattern) and the sidecar's path. A surface that wants to
  distinguish "in the file" from "beside the file" can; one that doesn't, doesn't have to.

- **FR-200-3 — parse language, forced and SDH from the filename**, covering every shape listed above,
  with the flag order not assumed (`.da.hi` and `.hi.da` both occur in the wild). An unrecognised or
  absent language is `null` — the existing untagged state, which already routes to Triage — never a
  guess. `Track` gains an `sdh` flag, finally supplying R195's deferred prerequisite.

- **FR-200-4 — the series strip is the union across episodes, not episode 1.** `DetailService`'s
  `seriesAudioLangs`/`seriesSubLangs` are built from all episodes' tracks. No extra round trip: the
  episodes are already loaded. Order stays physical-track order of the first episode that has each
  language, so the strip doesn't reshuffle between visits.

- **FR-200-5 — the admin shows subtitles as prominently as audio.** A SUBTITLES flag strip beside the
  existing Audio one in the `media.html` / `series.html` pagebar, and sidecar tracks listed in the
  Tracks & subtitles tab, visually distinguishable from embedded ones. Today an operator cannot see
  from the admin that a title has Danish subtitles at all unless Bazarr happens to be connected.

- **FR-200-6 — prove the two lists agree.** A check that compares our per-title subtitle language set
  against Jellyfin's `MediaStreams` for the same item and reports divergence. This is the guard on the
  Option 1 decision: the entire point of the phase is that the detail page and the picker stop
  contradicting each other, and without this the project has no way to know whether it succeeded.
  Report, don't auto-correct — a divergence is a parser bug to fix, not a value to paper over.

- **FR-200-7 — no playback change.** `buildSubtracks` and the whole R209/Phase 161 delivery-method
  ladder are untouched. This phase changes what the catalog *knows*, never what the player *does*.

- **FR-200-8 — a rescan is required and must be said out loud.** Sidecars appear only on the next
  `scan_files` of each title, so the fix lands gradually over the freshness cadence and the numbers
  above improve slowly rather than at deploy. State it in the build notes; do **not** force a repair
  rescan — Phase 188 is the standing reminder.

## Non-goals

- **Bazarr.** Phase 157 owns downloading and managing sidecars; this phase only makes the ones already
  on disk visible. The Bazarr card stays exactly as it is.
- **Downloading, renaming or deleting sidecar files.** Read-only.
- **Fixing untagged embedded tracks.** *40 Days Gone*'s 32 untagged embedded streams are a real
  separate problem, already visible in Triage, and unaffected either way.
- **`.idx`/`.sub` VobSub pairs** beyond listing them — no delivery work; the player already handles
  what Jellyfin reports.
- **The unmapped-language and `+N` honesty question** — that is R239's, on the render side.

## Verification

`compileKotlinLinuxX64` clean; `linuxX64Test` green. The parser gets a test table built from the real
filename shapes above, including the underscore, full-name, bare and flags-reordered cases and the
sibling-episode exclusion. FR-200-6 run against production: expect the movie count with a
sidecar-language-not-in-store to go 260 → 0.

## Related

- **R239** — the Ravilo render half.
- **R209 / Phase 161** — external vs embedded delivery in the player; the reason the picker is already
  right, and where `isExternal` entered the codebase.
- **Phase 157** — Bazarr; the only surface that shows sidecars today, and only conditionally.
- **R195** — deferred SDH detection, which FR-200-3 supplies for free.
- **Phase 185 / `Track` provenance** — the standing "`Track` comes from ffprobe, never Jellyfin" rule
  that Option 1 preserves and Option 2 would breach.
- **Phase 175** — the unified ingest engine the discovery step has to live inside.
