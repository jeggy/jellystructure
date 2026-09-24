# Phase R294 — An MKV whose track list is at the end plays at once

## Status

`Planned` — written 2026-09-24 from a household report and a same-day device investigation, not
dev-reviewed. Spec first. **Reverses Phase 201 FR-201-7** ("Ravilo says nothing new … a client-side
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
- **FR-R294-6 — Upstream.** The same change is offered to androidx/media as an issue or PR; if accepted,
  the vendored copy is deleted on the next Media3 upgrade.

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
