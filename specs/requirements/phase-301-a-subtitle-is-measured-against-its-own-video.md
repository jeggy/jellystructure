# Phase 301 — A subtitle is measured against its own video, not against another subtitle file

> Written 2026-10-02 from an owner question about the Dashboard's two subtitle rows (*Out of step with their video*,
> 1 050; *Made for a different video*, 209): *"they should be Critical, as they are fully incorrect … the numbers
> are huge."* Before any of them is made louder or fixed, the measurement behind them has to be right. It is not.

## Status

`✓ Built` 2026-10-02, **not deployed** (see Build notes). Written 2026-10-02 (dev-authored), against `main`
`d987ed2c`. Number verified free (admin specs top at 300). Not dev-reviewed.

**Amends** phase 273: FR-273-2 rung 1 (which stream is the embedded reference), FR-273-4 (`longer_video`) and
FR-273-6 (the hand-over from `prewarm_subtitles`). The Dashboard rows themselves are phase 302.

## What was found (production, 2026-10-02)

5 018 sidecars carry a verdict. Production runs *Only report*, so nothing has been changed or hidden.

| Finding | Evidence |
|---|---|
| **The "embedded" reference is often another sidecar.** `SubtitleReferences.fetchEmbedded` asks Jellyfin for `/Videos/{id}/{id}/Subtitles/{track.streamIndex}/0/Stream.vtt` with **ffprobe's** stream index. Jellyfin 12.1 numbers a video's external subtitle files **first** (0 … k−1) and shifts every stream inside the file up by k. | One episode, `/Items?Fields=MediaStreams`: `0 dan ext · 1 hrv ext · 2 srp ext · 3 video · 4 audio · 5 eng · 6 eng SDH`; ffprobe has the English tracks at 2 and 3. Jellyfin's subtitle cache holds `5.srt` and `6.srt` for it. Asked for "2", Jellyfin returned the Serbian sidecar. Against that, the Danish and Croatian files (both in sync with the English track, −0.2 s and −0.7 s) were judged **16 s off**, and the Serbian file, the one that really is 16 s early, was judged **in sync** (ρ 0.95). |
| How often it bites | The `prewarm_subtitles` hand-over fetches by Jellyfin's own index and gets the right text, so only references fetched by the check job itself are wrong. A sample of 60 verdicts against an embedded reference, re-measured against Jellyfin's cached extracts: 52 agree; of 15 `off`, 3 were really in sync; of 2 `off_mid_file`, 1 was in sync. |
| **The hand-over labels the wrong track.** `prewarmSubtitles` maps Jellyfin's stream to a store track by `streamIndex == s.index`: the right text, stored under a sidecar's index (`s:5`). That label is what Bazarr's sync is told to use (`syncReference`), and `0:s:5` does not exist in the file. | `subtitle_reference.source` |
| **One junk line makes a fitting file "made for a different video".** `longer_video` is decided first, from the **end** of the last cue. An ad line after the film ends (a *sync by* credit with an e-mail address), or one that starts in the credits and "lasts" until 22:01:00 (a website's address), is enough. | 24 of 167 `longer_video`: 15 with 1–3 lines starting after the end, 9 whose last line ends 19–20 hours in. All fit the video (z up to 8 against speech). |
| A 22-hour cue also sizes the FFT | `sizeFor(cues.last().endMs)` gives 2²⁰ samples, and the cue fills the whole tail of the signal before centring. |

**Not wrong:** verdicts against the speech track. Fourteen of them, spot-checked against embedded tracks the check
did not use, agreed 14 of 14.

## Requirements

**FR-301-1 — The embedded reference is fetched by Jellyfin's own number for that stream.** The subtitle streams
inside a file are matched by position: ffprobe's `0:s:N` (the track's specifier) is Jellyfin's N-th subtitle
stream that is **not** external, in Jellyfin's index order. Jellyfin's list is read with `/Items?Ids={id}&Fields=MediaStreams`
(one request per video, before the first fetch). A match is trusted only when:
- both sides count the same number of internal subtitle streams;
- where both name a language, they name the same one (`LanguageResolver.sameLanguage`).

Otherwise that candidate is skipped. When Jellyfin's list cannot be read, the fetch fails without storing anything
(the next job tries again). When no candidate can be matched, *none* is stored, and the check falls through to the
speech track as it does for a file with no subtitle inside it.

**FR-301-2 — The hand-over is labelled with the track it really is.** `prewarm_subtitles` maps Jellyfin's stream to
the store's track by the same position rule. The stored source is that track's `s:N`, which is what Bazarr's sync is
given.

**FR-301-3 — A cue is judged by where it starts.** Before any judgement, a cue that lasts longer than 20 s is cut to
20 s. No dialogue line is that long; an ad that "ends" hours later, or a sign left up, is not timing.

**FR-301-4 — A few lines after the video are not "a longer video".** When the video's length is known, cues that
**start** after it are counted. Three or fewer are left out of the judgement: they are an ad or a credit, and a
viewer never sees them. Their count is stored (`cues_past_end`) and named in the verdict's words (*"1 line after the
video ends: an ad or a credit"*). With more than three, FR-273-4's rule stands, applied to cue starts: `longer_video`
when the last cue starts more than 10 % plus 60 s past the end.

**FR-301-5 — What was measured the old way is measured again, once.** One migration:
- deletes every stored embedded reference (Jellyfin keeps its extracts cached, so fetching them again reads no
  media file);
- deletes every verdict made against an embedded or sibling reference, and every `longer_video`;
- deletes the settled copies (FR-273-15) of the videos those verdicts belonged to, since a copy was kept because a
  wrong verdict called it in sync;
- dismisses waiting proposals for sidecars that no longer have a verdict.

The next `check_subtitles` run finds those sidecars due and judges them again.

## Not in scope

- The Dashboard rows, their severity, grouping and fix buttons (phase 302).
- The ±120 s search window. A file off by more stays `not_this_video` or a doubt, as today.

## Acceptance

1. A video with three sidecars and two internal English tracks, where Jellyfin lists the sidecars first: the
   reference fetched is Jellyfin's stream 5 (the first internal track), never 2.
2. Jellyfin and ffprobe disagree on the number of internal subtitle streams: no embedded reference is stored, and the
   verdict comes from the speech track.
3. A subtitle that fits, with one ad line 2 minutes after the video ends: `in_sync`, `cues_past_end = 1`.
4. A subtitle that fits, whose last line ends at 22:01:00: `in_sync`; the FFT is sized from the video, not 22 hours.
5. A subtitle whose lines run on for twice the video: still `longer_video`.
6. After the migration on a copy of production: no embedded reference, no `longer_video`, no verdict against an
   embedded or sibling reference; speech verdicts untouched.

## Verification plan

Unit tests for the stream mapping (pure function) and the cue clean-up; the existing subtitle-check suite. On a copy
of the production database, run the migration and count what is left. `scripts/check-phases.sh`.

## Build notes (2026-10-02)

- **FR-301-1/2:** `SubtitleReferences.jellyfinIndexFor` and `trackForJellyfinStream` match by position among the
  internal subtitle streams, with the count and language checks. `fetchEmbedded` reads Jellyfin's stream list first
  (`getItemMediaStreams`, one request per video, only when no reference is stored). `prewarmSubtitles` maps with
  `trackForJellyfinStream`, so the stored label is the track's own `s:N`.
- Confirmed on production before building: Jellyfin's `/Items?Fields=MediaStreams` for the example episode lists
  `0–2` the sidecars, `3` video, `4` audio, `5–6` the English tracks.
- **FR-301-3/4:** `VerdictRules.clean` (20 s cap, up to 3 lines starting after the end left out and counted), applied
  to the sidecar and to a sibling used as a reference; `judge` takes `lastStartMs` for the longer-video rule.
  `cues_past_end` is stored and named in `verdictWords`.
- **FR-301-5:** migration `65.sqm`. On a copy of production it leaves 3 318 speech-referenced verdicts
  (`in_sync` 1 346, `off` 731, `cant_tell` 1 232, `other_episode` 9), no embedded reference, 1 312 settled copies
  (from 2 515), and nothing measured against an embedded or sibling reference. 1 700 sidecars are judged again by
  the next `check_subtitles` run.
- Tests: three new in `SubtitleCheckServiceTest` (the production numbering, a mismatch in count or language; an ad
  after the end, a 22-hour line, a doubled subtitle still `longer_video`; the clean-up rule). Subtitle and Bazarr
  suites 27/27. `verifyCommonMainJellystructureDbMigration` passes.
- **Owed:** acceptance 6 on production after a deploy the owner approves, and a look at the re-judged counts.
