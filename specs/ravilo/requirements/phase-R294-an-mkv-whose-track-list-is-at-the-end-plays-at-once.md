# Phase R294 — An MKV whose track list is at the end plays at once

## Status

**Built 2026-09-24** (`58a0f552`), written the same day from a household report and a device
investigation; verified on the stue TV (release build). **Dev-reviewed 2026-09-24 against `main`
`9d2636bb`** (see §Dev review at the bottom: FR-1 to -5 are in the code and the tests; the factory drops
one setting it claims to forward; FR-R294-6 is re-aimed — upstream already carries the fix; acceptance 2
and 4 are not recorded). **Acceptance 2 and 4 recorded 2026-09-24 on the Pixel 9** (see §Device run, Pixel 9 below); the factory
now forwards its Matroska flags; the file header names upstream's two commits. **✓ Built.** Spec first. **Reverses Phase 201 FR-201-7** ("Ravilo says nothing new … a client-side
guard, if ever wanted, belongs in its own phase") and **retracts 201's Out-of-scope reasoning** for
it (see *Why 201 was wrong*). Companion change built in the same pass: **R292 FR-R292-11's rung 2**
(a recovery seek that moves), which R292 assigns to this work.

> Owner, 2026-09-22: *"Ravilo should play it no matter what, just like wholphin and jellyfin."*
> Owner, 2026-09-22: *"Jellyfin and wholphin can play it without issues, then we should also be able
> to play without issues."*

Research: [`ravilo-cannot-start-a-tracks-at-end-mkv-2026-09-22.md`](../../research-reports/ravilo-cannot-start-a-tracks-at-end-mkv-2026-09-22.md)
(§3 the file, §7 the extractor, §11 the device test).

## What happens today

A Matroska file can carry its `Tracks` element anywhere, with the header's `SeekHead` saying where.
Phase 201 found our own `mkvpropedit` edits evicting `Tracks` to the end of the file; a 2026-09-22
arrival showed release groups ship such files too, so this is no longer only a file we broke.

Media3 1.8.0's `MatroskaExtractor` reads the body linearly. At the first `Cluster` it does follow
`SeekHead` — but **only to Cues**: it records every `SeekHead` entry, keeps the one whose id is
`0x1C53BB6B`, jumps there, and returns to the Cluster. The `Tracks` entry (`0x1654AE6B`) is parsed and
thrown away. On the real file, Cues and Tracks both sit near the end, Tracks directly after Cues, so
the extractor turns back one element too early. It then reads every Cluster with no track list,
dropping every frame, until it meets Tracks again at EOF. The player reports *playing* at 0:00 with
nothing to render. R220's ladder then re-prepares the item and the whole file is read again:

| device | per lap | seen |
|---|---|---|
| stue TV (BRAVIA) | ~38 s, 224 Mbit/s | 2026-09-22 and -24, loops until the viewer gives up |
| Pixel 9 | ~20 s | 2026-09-24 from 0:00; plays only when the start is a resume (a seek) |

Each lap downloads the whole ~1 GB file. Any seek to a *new* position rescues it within ~1.5 s,
because a seek re-reads the file with tracks known. R220 rung 2 is a seek to the *current* position,
which recovered nothing in ~12 runs.

### Why 201 was wrong

201 put this out of scope because *"reading the tail of an HTTP stream before the head is not
something to build into a player."* The player we ship already does exactly that for Cues, on every
Matroska file whose Cues are elsewhere. The fix below is that same jump, applied to one more element.

## Requirements

- **FR-R294-1 — `Tracks` is fetched through `SeekHead` before any Cluster is read.** When the extractor
  reaches the first `Cluster` without having seen `Tracks`, and `SeekHead` gave a position for it, it
  jumps there (`RESULT_SEEK`), parses `Tracks`, and returns to that Cluster. Cues then follow the
  existing path: jump, build the seek map, return. Nothing in the Cluster is consumed before the track
  list exists, so the first frame decodes at once.
- **FR-R294-2 — Only the missing-Tracks case changes.** A file whose `Tracks` precedes the first
  Cluster (every other file in the library) takes exactly Media3's existing path: no extra request, no
  extra seek. A file with no `SeekHead` entry for `Tracks` behaves exactly as today.
- **FR-R294-3 — The fix lives in a vendored copy of Media3's extractor, not a subclass.** `read()` is
  `final` and the seek state is `private`, so a subclass can see where `Tracks` is but cannot jump
  there. `:ravilo-player` carries `RaviloMatroskaExtractor`, a copy of Media3 **1.8.0**'s
  `MatroskaExtractor` (Apache-2.0) in the same package so its package-private collaborators resolve,
  with the change marked and the upstream version named in the file. Upgrading Media3 means
  re-applying the marked diff to the new upstream file; a test fails if the copy is left behind.
- **FR-R294-4 — Wired through one hook.** `RaviloExtractorsFactory` wraps `DefaultExtractorsFactory`,
  forwards its settings (subtitle parser factory, text-track transcoding, GOP-dependency codecs), and
  replaces only the Matroska extractor with the patched one, built with the same flags the default
  would use. It reaches `:ravilo-ui` through `RaviloPlayerEngine.extractorsFactoryProvider`, installed
  beside `renderersFactoryProvider` in both Android activities (R31's GPL-containment pattern);
  `RaviloPlayerAndroid` builds `DefaultMediaSourceFactory(ctx, factory)` when it is set. R292
  FR-R292-10 lists this binding, so a rebuilt engine carries it.
- **FR-R294-5 — Tested against a file that has the defect.** `ravilo-player/src/test/resources/mkv/`
  holds `clean.mkv` and `tracks-after-cluster.mkv` (the same clip after one `mkvpropedit` flag edit;
  `make-fixtures.sh` regenerates both). A unit test runs the patched extractor over the broken fixture
  with a seek-honouring input and asserts: tracks are published, and the **first video sample is from
  the first Cluster**. The same test over Media3's stock extractor must show the current failure, so
  the test proves it tests the defect.
- **FR-R294-6 — Upstream.** ~~The same change is offered to androidx/media as an issue or PR; if accepted,
  the vendored copy is deleted on the next Media3 upgrade.~~ **Re-aimed 2026-09-24 (dev review item 5,
  owner's call):** upstream Media3 already carries an equivalent fix (1.11.0); the file header names the
  upstream commits, no report is filed, and the copy is deleted when `media3-ffmpeg-decoder` lets Media3
  move past 1.8.0. The version test is what makes that upgrade impossible to forget. *Verified 2026-09-24
  against androidx/media:* `d386bbf954` ("MKV: Handle tracks defined in the last cluster") and
  `eb2965ce41` ("Matroska: Fix seekable timeline with Tracks after Clusters", issue #3377).

### Companion: R292 rung 2

- R220's rung 2 becomes a seek that **moves**: forward 1 ms below one second, back 1 ms otherwise
  (a same-position seek is ignored). It gets its own settle time, long enough for a seek over the
  network to show a frame, so the ladder does not escalate to rung 4's full re-prepare before the seek
  lands. Verified on a device against a real stall.

## Non-goals

- Changing the file. Repair stays the admin's action (Phase 201's Fix now); this phase makes the
  player cope whether or not anyone repairs it.
- Cast and Tizen receivers. They are not ExoPlayer. Casting is broken for every file at the time of
  writing (research §11), so their behaviour on this layout is unknown and belongs to that fix.
- Jellyfin's own keyframe extractor, which fails on the same file and falls back (research §11). An
  upstream report, not our code.

## Acceptance

1. **Unit:** FR-R294-5 passes, and fails against stock `MatroskaExtractor`.
2. **Pixel 9, debug build:** the broken episode started from 0:00 shows a picture within the normal
   start time of a clean episode of the same size, with no `video output stalled` line, and one
   `PlaybackInfo`.
3. **Stue TV, release build** (release-only ART verification must be exercised): the same, and a
   clean episode still plays; seeking in both works.
4. **Traffic:** during the start of the broken file the TV reads a few MB, not the whole file.
5. **Rung 2:** with the fix disabled (stock factory), a forced stall at 0:00 recovers at rung 2.

## Dev review (2026-09-24, against `main` `9d2636bb`)

Built before it could be reviewed (`58a0f552`: 10 files, +2,937 lines), so this reviews what shipped.

1. **FR-R294-1 and -3 are as written.** `RaviloMatroskaExtractor.java` is Media3 1.8.0's extractor in the
   `androidx.media3.extractor.mkv` package, Apache-2.0 header kept, eight `RAVILO R294` hunks: the
   `SeekHead` entry for `ID_TRACKS` is recorded (`:843`), a Cluster arriving before Tracks triggers the
   jump (`:776`), Tracks first and then the unchanged Cues logic (`:1964`). The version test
   (`RaviloMatroskaExtractorTest.kt:99-102`, `MediaLibraryInfo.VERSION == "1.8.0"`) is what makes
   "a test fails if the copy is left behind" true.
2. **FR-R294-2 is tested, not assumed.** `aNormalFileIsUntouched` (`:85`) and
   `trackFetchIsTwoSeeksWhenCuesAreAtTheFront` (`:75`), plus a third fixture the spec does not list —
   `tracks-and-cues-at-end.mkv`, the real file's layout, `trackFetchThenCuesWhenBothAreAtTheEnd` (`:80`).
   `make-fixtures.sh` regenerates all three. Add the third to FR-R294-5's list.
3. **FR-R294-4 forwards three settings and silently drops a fourth.** `RaviloExtractorsFactory`
   overrides `setSubtitleParserFactory`, `experimentalSetTextTrackTranscodingEnabled` and
   `experimentalSetCodecsToParseWithinGopSampleDependencies`, and `swap()` rebuilds the Matroska
   extractor from the first two. It does not override `setMatroskaExtractorFlags`: a caller setting
   `FLAG_DISABLE_SEEK_FOR_CUES` on the factory would be honoured by the delegate's instance and then
   discarded by the swap. Nobody sets it today; forward it (one override, one field, one constructor
   argument) so "forwards its settings" is true by construction rather than currently.
4. **FR-R294-5 holds, and the stock-extractor test is the line that proves it.**
   `stockExtractorFindsTracksAtTheEndAndHasDroppedEveryFrame` (`:55`): stock Media3 keeps zero frames on
   both broken fixtures, the copy keeps them all. The eight tests run in CI's `android-release` job
   (`ci.yml:127`, `:ravilo-player:testDebugUnitTest`), added the same day.
5. **FR-R294-6 changes shape: upstream already has the fix.** The fix round's notes record that Media3's
   `main` corrected this same Tracks-after-Cluster case for 1.11.0 (two upstream commits), and that moving
   there is blocked by `org.jellyfin.media3:media3-ffmpeg-decoder` (`libs.versions.toml:72`, `1.8.0+1`;
   newest published 1.9.0+1). If the changelog confirms it, FR-R294-6 is not "file a report" but "name the
   upstream commits in the file header, and delete the copy when the decoder catches up".
   `~/patches/0006-media3-matroska-tracks-after-clusters.patch` is the diff in upstream's own layout,
   ready either way. Check before closing the FR; a report for a fixed bug is noise.
6. **Acceptance: 1, 3 and 5 are done; 2 and 4 are not recorded.** The stue TV run is in STATUS (decoder up
   in under 1 s, one `PlaybackInfo`, no stall, seeking works, a clean episode unchanged) and rung 2 was
   verified against a forced stall. The Pixel 9 debug run (acceptance 2) has not happened, and acceptance
   4 — a few MB read during the start, not the whole file — is implied by the sub-second start but was
   not measured. One reading closes it.
7. **R292 FR-R292-10 is satisfied at `RaviloPlayerAndroid.kt:57-61`**: the factory is read from
   `RaviloPlayerEngine.extractorsFactoryProvider` when the engine is built, so the rebuilt engine R292
   introduces carries it — as long as that line lives in the single re-bind function R292's review asks
   for.

**Small correction.** Phase 201's `mkv_track_layout` finding still rests on "unplayable in Ravilo"
(`Dashboard.kt:347`), which R294 has made false. Its Triage description and 201's Fix-now banner need one
wording pass — the layout is still worth repairing (Jellyfin's own keyframe extractor fails on it, other
clients may), but not for the reason the copy gives. By convention (R269 recording in its own notes rather
than editing R264) that note lives here, not in 201.

**Net effect.** Nothing to change in the extractor. One forwarded flag, one device run, one measurement,
and FR-6 re-aimed at the upstream fix.

## Device run, Pixel 9 (2026-09-24, debug build `1.37-68-gade0523d`, WiFi, prod backend)

The broken episode (E19, unrepaired) and the clean control (E18), each started from 0:00 from the
series page, measured with `logcat` (`ExoPlayerImpl: Init` → the video `MediaCodec` adapter), the
phone's `/proc/net/dev` `wlan0` counter, the backend log and phase 185's start samples.

| run | init → video decoder | stall lines | `PlaybackInfo` | rx first 10 s | rx next 10 s | 185 sample |
|---|---|---|---|---|---|---|
| E19, first start on this build | **0.98 s** | 0 | 1 | *(counter not readable that run)* | 4 MiB | 2 s |
| E18, clean control | **0.63 s** | 0 | 1 | 39 MiB | 5 MiB | 1 s |
| E19, second start | **0.84 s** | 0 | 1 | **41 MiB** | 4 MiB | 1 s |

- **Acceptance 2 — passes.** The broken episode's picture is up within a second, the same as a clean
  episode of the same size (0.84–0.98 s vs 0.63 s); no `video output stalled` line; exactly one
  `PlaybackInfo` per start. The chrome showed 1:20 of 34:28 seventy-five seconds after the tap:
  continuous playback from 0:00.
- **Acceptance 4 — passes, and the number is the ordinary one.** The first ten seconds read 41 MiB —
  Media3's normal initial buffer fill, indistinguishable from the clean episode's 39 MiB — then 4 MiB
  per ten seconds of steady playback. A full-file read would have been ~950 MiB before the first frame.
- Seen on the way, not this phase's: with the chrome up in portrait, the caption sits behind the
  Subtitles · Next · Lock rail (the R300 leftover already recorded); and Back from the player lands the
  series page at the top with the episode row reset to E1, so a viewer who wants the next episode
  re-scrolls (R137 restores Home/Channel/Browse, not the series page — worth its own small phase).
