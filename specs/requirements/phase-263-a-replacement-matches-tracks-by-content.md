# Phase 263 — A replacement puts each track's own sound under its own label

## Status

`✓ Built` 2026-09-26, the day it was written — not dev-reviewed, **not deployed**. Amends phase 254
(FR-254-10 and FR-254-12). Found on 2026-09-22 while repairing *Hoppe Hares Byggebande* by hand and
recorded then without a spec; the hand repair used the corrected procedure below, and the shipped job
has never run on production (no `file_replace_from_source` row in `media_job`, nothing in
`.js-quarantine` but the hand repair) — so nothing needs undoing.

### Build (2026-09-26)

- `StreamShape`, `TrackPair`/`PairedBy`, `TrackPairer` (commonMain, pure) · `FileRepairPlan` takes the
  pairs, maps in library order, builds the two `streamhash` commands and one proof per identical group ·
  `FileIntegrityService.planning`/`repairPlanFor`, `replaceFromSource` rewired, `packetCounts` gone ·
  `GET /api/media/{id}/health/integrity/plan` · the banner's row fetches its plan when opened.
- **The first rule was wrong on production, and that is why FR-263-1 reads as it does.** Scored by plain
  overlap with "no runner-up within half", it paired 17 of the 36 damaged production files and refused
  19: forced subtitles whose cues are a subset of the full track's (both "hold" every forced packet),
  and one title whose release muxes **one** audio track three times under three labels (identical
  `streamhash` over the whole file — 17 files carry such a duplicate). Scoring by similarity both ways
  and treating window-identical source tracks as a group pairs **36 of 36**.
- **Verified on real files without touching one** (a throwaway harness driving the real `TrackPairer` and
  `FileRepairPlan` through real ffmpeg): the 36 production files pair with no refusal and 33 map the
  source out of order (acceptance 4); the 20 quarantined damaged originals, each repaired into a scratch
  directory from its seeding copy, all come out with equal `streamhash`es, zero damage lines, every
  position holding the library's own track and the library's labels in place — while 254's `-map 0`
  plan, judged by the same position check, fails at 5 of 7 positions on every one. The swap never ran.
- 30 unit tests (16 pairing, 14 file integrity); `compileKotlinLinuxX64` and `compileKotlinWasmJs` clean.

## What is wrong, measured 2026-09-26

Phase 254's *Replace from clean copy* stream-copies the seeding copy with `-map 0` and then stamps the
library copy's language, title and dispositions onto the result **by stream index**. That is only right
when the library file's tracks are in the source's order. In this library they usually are not: the
operator's own edits (phase 254 names them as the reason a file was rewritten at all) put Danish first
and default across the series, while the seeding copies are still in release order.

- One damaged episode, library copy vs its seeding copy:
  library `v · dan · swe · nor · eng · fin · s:swe`, source `v · eng · dan · fin · nor · swe · s:swe`.
  The job would write `language=dan` + `default` onto the source's **English** track, `swe` onto its
  Danish, and so on down the list — every repaired file would play English under the Danish label.
- **All three of FR-254-10's checks pass on that wrong file.** (1) It demuxes clean — it came from a
  clean source. (2) Its per-stream packet counts equal the source's — the order *is* the source's.
  (3) Its flags equal the library copy's — it re-reads the metadata the copy command just wrote. 254's
  own dry run saw the symptom (*"flags differ from the source's on five of seven streams"*) and read it
  as the carry-over doing its work.
- **Every damaged file on production today, surveyed:** 44 files in 8 titles carry a damaged
  `file_integrity` row. 8 have no seeding copy. Of the 36 that do, **33 have their tracks in a different
  order from the source** — the button on their pages would mislabel all 33. 3 are in source order.
- Re-ordering the tracks in the admin afterwards does not help: the order would then be right and the
  content still wrong.

### What does tell the tracks apart: their packets

A stream copy moves packets byte for byte, so a track in the library copy and the same track in the
seeding copy carry identical packets, whatever either file calls them. Measured on all 20 damaged
episodes of the series that have a source, reading only the **first 120 s** of each file
(`ffmpeg -t 120 -i f -map 0 -c copy -f framemd5 -`, ~0.5 s per file):

- Every library track found **98.8–100 %** of its packets (by size + MD5) in exactly one source track —
  the right one — including the video of files damaged 93 KB and 12 MB in (the demuxer resyncs; only
  the packets in the damaged spots are lost).
- A track shares **0–3** packets with any other track. The 1–3 are byte-identical frames between two
  dubs (quiet stretches over the same music bed); they never came close to deciding anything.
- The subtitle track carried 22–24 packets in the window and matched on all of them.
- After a scratch copy with the maps in the library's order, the new file's per-stream `streamhash`
  equals the source's under the same maps, byte for byte, and each position of the new file holds the
  packets the library copy held at that position.

## Requirements

- **FR-263-1 — each library track is paired with a source track by its content.** Both files' first
  120 s are read as per-packet hashes (one `framemd5` pass each, niced; the job through
  `SegmentProcessGate`, the page through `ProcessGate`'s interactive reserve). For a library track with
  packets in that window, the candidates are the source tracks of the **same kind and codec** (the codec
  is the release's; no edit in jellystructure changes it). Each is scored by **similarity**: packets in
  common over packets in either — so a full subtitle track that contains every cue of a forced one scores
  below the forced one. The pair is the best candidate, accepted only above **0.5**, and only when no
  other candidate (other than one byte-identical to it in the window, FR-263-8) comes within 10 % of it.
  Labels play no part: a track the operator relabelled is still found.
- **FR-263-2 — a track with nothing to compare falls back to its label, and only unambiguously.** A
  library track with no packets in the window (a forced subtitle whose first cue is later, a font
  attachment) is paired with the one remaining source track of the same kind, codec and language — and
  for an attachment, the same file name. None or more than one ⇒ refuse.
- **FR-263-3 — refuse rather than guess.** No repair runs, and the sentence says which track, when a
  library track's packets match no source track well enough, when two library tracks pair with the same
  source track, when a label fallback is ambiguous, or when the track counts differ (254's existing
  refusal). A refusal leaves the library file untouched, as every 254 refusal does.
- **FR-263-4 — the copy is written in the library's order.** `-map 0:<source index>` once per library
  track, in library order, and the library's language, title and dispositions by **output** position.
  `-map 0` is gone from the command.
- **FR-263-5 — verified by what the tracks carry, not by what they are called.** Before the swap, in
  addition to 254's zero-damage-lines and flags checks:
  1. **exact:** the new file's per-stream `streamhash` equals the source's under the same maps (this
     replaces 254's packet counts, which the wrong file passes; the same pass over the source is the
     source's own FR-254-9 cleanliness check, and the same pass over the new file is its damage check,
     so the job reads no more than before);
  2. **the goal itself, without the pairing:** each position of the new file holds more than half of the
     packets the library copy held at that position in the first 120 s. This check does not read the
     pairing, so a pairing or command-building mistake cannot pass it.
- **FR-263-6 — the page asks for the command when the operator opens a file.** The pairing reads two
  files, so it no longer runs while the title page loads (254's status route already says it never reads
  a media file; it did — one `ffprobe` per damaged file). `GET /api/media/{id}/health/integrity/plan?path=`
  answers one file: the command, or the refusal sentence, plus the pairing, one line per track — *"1 dan
  ← source 2 · by content (3 717 of 3 717 packets)"*, *"6 swe ← source 6 · by label"*. The banner's
  per-file row fetches it when opened. The job and the page build the plan with the same code, so the
  page still cannot print a command the job does not run (FR-254-12).
- **FR-263-7 — the printed command checks itself the same way.** The snippet is copy → the new file's
  damage test → **`streamhash` of the source under the maps equals `streamhash` of the new file** (the
  demuxer's complaints kept beside the hashes, so a damaged source fails it too) → FR-263-8's proof per
  group → quarantine and swap. Check 2 of FR-263-5 stays in the job (it needs the pairing-free
  comparison, not a shell one-liner).
- **FR-263-8 — source tracks identical in the window are one group, and the group is proved identical.**
  When a library track's best candidates are byte-identical to each other in the window, the window
  cannot say which is which — and measured, it does not need to: one title's release carries a single
  audio track three times, labelled `swe`, `dan` and `nor`, with equal `streamhash`es over the whole
  file. The library tracks that match a group are paired within it by label (the source track carrying
  the same language, where exactly one does), then in order; more of ours than the group holds refuses.
  Which one lands under which label then changes no byte **only if** the group is identical across the
  whole file, so the job reads that from its source pass and refuses when it is not, and the snippet
  proves it per group (`streamhash` of the group's tracks, one distinct hash). The page marks these
  pairs so the operator can see a label, not the content, chose between them.

## Non-goals

- Reading beyond the first 120 s to place a track the window cannot (open question 1).
- Matching renamed files to a source (254's open question 1, unchanged).
- Any change to the lossy repair, the deep check, the sweep, or phase 261's schedule.

## Open questions

1. Three same-language subtitle tracks (full, SDH, forced) where the window holds no cue from two of
   them: labels cannot tell them apart and this phase refuses. A second pass over both files, subtitle
   streams only, would place them at the cost of a full read of each. Left out until a refusal like that
   is seen on a real file.

## Acceptance

1. Unit: the measured layout (library `v dan swe nor eng fin s`, source `v eng dan fin nor swe s`, three
   packets shared between two dubs) pairs 0←0 1←2 2←5 3←4 4←1 5←3 6←6, every pair by content.
2. Unit: a relabelled track is paired by its packets, not its label; a forced subtitle pairs with the
   forced track, not the full one containing its cues; three window-identical tracks form one group
   paired by label; a track matching nothing, two tracks claiming one source track, a close runner-up,
   more tracks than an identical group holds, an ambiguous label fallback, and a different track count
   each refuse with a sentence naming the track.
3. Unit: `FileRepairPlan` maps the source in library order, never `-map 0`, stamps flags by output
   position, and prints a snippet that compares the two `streamhash`es and proves each identical group
   before the swap, and only ever reads the source.
4. On the 36 damaged production files with a source, the pairing places every track with no refusal,
   and the 33 reordered ones map the source out of order. **Met 2026-09-26** (harness, see §Build); the
   endpoint itself needs a deploy to be seen.
5. On production, after deploy: *Replace from clean copy* on a reordered title leaves every episode with
   the library's labels over the library's own audio (the decisive check: decode 12 s of each audio
   track and compare with the quarantined original's same-labelled track).
