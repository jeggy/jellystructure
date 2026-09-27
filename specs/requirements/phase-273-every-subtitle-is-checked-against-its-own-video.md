# Phase 273 — Every subtitle is checked against its own video, and jellystructure steers Bazarr until it fits

> Owner, 2026-09-27, after a viewer found an animated series playing with another episode's Danish subtitles:
> *"Can we investigate and see if we can help with stopping this with jellystructure?"*
>
> And after the research: *"We want jellystructure to solve this and as well as the offset issue. Jellystructure
> should be the brain of everything without redoing something it should guide the tools to do their jobs even
> better. And if some configuration is off, then jellystructure should guide the admin to set the best settings
> in the different tools."*

## Status

`Planned` — written 2026-09-27 from two research passes the same day (a single animated series, then every
sidecar in the production library; the reports are private because they name library titles). Not
dev-reviewed. Backend (a check, a hook, a Bazarr steering loop, an advisor) and admin (verdicts on the title
page, a Dashboard card, a Bazarr advisor). **No Ravilo client change and no new wire value for Ravilo**: the
server stops offering a subtitle it knows is wrong, and that is all a viewer sees. Numbering verified against
`STATUS.md` the same day: admin taken through **272**.

Builds on **157** (Bazarr is a first-class connection and owns providers), **200** (a sidecar is a `Track`),
**179/213** (`prewarm_subtitles` and its lane), **222** (one audio decode per file), **255** (a stored finding
about a file, keyed by path, size and mtime), **261** (a check is a pipeline step with a cadence), **262** (defer
while a TV is watching), **165/221** (webhook secret, last-received status), **212/246/257** (the advisor
finding shape and where it is shown).

## The principle this phase follows

jellystructure is the one component that can see the video and the subtitle together, so it is the one that
judges whether they fit. Everything else stays with the tool that already does it:

| Job | Owner | What jellystructure does |
|---|---|---|
| Finding and downloading subtitles, providers, language profiles, history | **Bazarr** | Tells Bazarr which subtitle is wrong (blacklist), which candidate to take (manual download), where a mislabelled file belongs (upload) |
| Retiming a subtitle | **Bazarr** (ffsubsync) | Tells Bazarr which reference to sync against, how far to search and whether to fix the frame rate, then checks the result |
| Extracting embedded subtitles | **Jellyfin** | Keeps the text `prewarm_subtitles` already asks Jellyfin for, instead of throwing it away |
| Telling Jellyfin a subtitle changed | **Bazarr** (its Jellyfin integration) | Advises turning it on |
| Deciding whether a subtitle fits its video | **jellystructure** | New in this phase |
| Knowing which settings in Bazarr work against that | **jellystructure** (advisor) | New in this phase: says what to change, where, and why |

jellystructure never talks to a subtitle provider, never downloads a subtitle itself, never writes a subtitle
file directly and never changes a Bazarr setting on its own. Every change to a subtitle on disk goes through a
Bazarr API call, so Bazarr's history, naming and language bookkeeping stay true. This supersedes one sentence
of phase 157 ("jellystructure stores nothing about subtitles"): it still stores nothing *Bazarr* owns, but a
verdict about whether a file fits a video is jellystructure's own finding, like 255's track coverage.

## Today

Measured on production on 2026-09-27. Scripts and data are kept outside the repo with the reports.

**How the check works.** A subtitle's cue timing is a fingerprint of the dialogue. Two subtitles of the same
edit line up at one offset and one speed, in any language; subtitles of different episodes line up nowhere.
Jellyfin had already extracted 27,520 embedded text subtitles into its cache while pre-warming, and an embedded
subtitle can't be for the wrong episode. Correlating each sidecar's cue on/off track (10 Hz) against its own
file's embedded subtitle, at five speeds and ±120 s, took 15 s for the whole library on six cores, reading no
video. On the animated series' old Bazarr subtitles, the 15 right ones all correlated at ρ ≥ 0.41 and the 19
wrong ones at ρ ≤ 0.13, with nothing in between.

**What the library holds.** 4,988 sidecar subtitles, 3,915 of the episode ones downloaded by Bazarr. The 944
with an extracted embedded reference:

| Verdict | Subs | Share |
|---|---:|---:|
| Right episode, in sync | 610 | 64.6% |
| Right episode, off by 1–5 s | 96 | 10.2% |
| Right episode, 23.976 vs 24 fps (drifts ~1.3 s per half hour) | 67 | 7.1% |
| Right episode, off by 5 s or more (up to 115 s; constant through the file) | 98 | 10.4% |
| Right episode, timed for 25 fps (4% slow) | 13 | 1.4% |
| Does not match its file | 16 | 1.7% |
| Another episode, identified | 5 | 0.5% |
| Timed for a much longer video | 9 | 1.0% |
| No verdict (the embedded track was a forced/signs track not flagged forced; or weak both ways) | 30 | 3.2% |

Examples, described rather than named: a comedy series whose Danish, Croatian and Serbian subtitles run 36–115 s
late across four seasons; a children's animated series with four Danish subtitles each shifted one episode; a
sitcom with the next episode's Serbian subtitle; a Danish documentary series carrying a Croatian subtitle of an
American legal drama; a cartoon whose 11-minute segment files got 22-minute subtitles (two segments each, 76
files). Comparing the last cue with the file's length flags 192 of all 4,988 sidecars, free.

**Where a reference exists.** 944 sidecars have an embedded subtitle already extracted, 1,404 have one not yet
extracted, 1,636 have none but sit beside sidecars in other languages, 1,004 have none and are alone. Other
languages confirm a right subtitle (281 of 281 right pairs agree) but cannot prove one: in all 5 pairs where both
were wrong, the two agreed, because Croatian and Serbian often come from the same mislabelled upload. For files
with no embedded subtitle the audio is the reference: dialogue sits in the centre of a stereo mix, so a speech
track from mid/side energy correlates with a subtitle's cues. On the animated series it separated right from
wrong 95.8% of the time on whole episodes, 94.3% on the first 10 minutes, and 93% without webrtcvad, which
Kotlin/Native does not have. It also called 2 right subtitles wrong, so the audio can confirm but must be
careful about condemning. The 1-second waveform the segment editor already stores scores 67%, which is useless.

**Why Bazarr lets these through.** Its OpenSubtitles provider queries by the show's IMDb id plus TheTVDB's season
and episode, and four score components echo the query: `series` 180 (added to every episode result), `year` 90
(added when the IMDb id matches, which the query guarantees), `season` 30 and `episode` 30. Every result scores
at least 330/360 = 91.7%, above the 90% minimum, so the minimum filters nothing. The only independent evidence is
the hash (359), which matched 18 of 3,915 episode downloads, and the release group (15), which matched 3% of the
right subtitles and none of the bad ones. Right subtitles averaged 93.1%, subtitles matching nothing in their
file 93.4%. Raising the minimum would drop good subtitles, not bad ones. On the animated series, 61% of 207
identified uploads were attached to another episode's page on OpenSubtitles, 9% to the right page under IMDb's
numbering, and 30% matched this library's numbering. Bazarr issue #2215 describes the same shift for another
series; it is open.

**The rest of Bazarr, as configured here.** Audio sync is off (*Enable Automatic Subtitles Audio
Synchronization*), with *Max Offset Seconds* 60 and *Do Not Fix Framerate Mismatch* on. *Custom Post-Processing*
is off. Upgrades are on: for 7 days, every 12 hours, any subtitle under 357/360 is searched again and replaced by
anything that scores higher, by the same blind score. Bazarr's Jellyfin integration is off, so Jellyfin learns
of a new or removed subtitle only at its own scan. 41 of Bazarr's 212 shows have no IMDb id and are searched by
title; 2 of the 4 subtitles fetched that way are wrong, one of them from another show.

## What changes

### §A — A verdict on every sidecar subtitle

- **FR-273-1 — Every sidecar gets a verdict.** Every text sidecar the store knows (phase 200's `external`
  tracks) is checked against its own video. The verdict is one of:

  | Verdict | Meaning | Carries |
  |---|---|---|
  | `in_sync` | Right content, aligned | ρ, z, reference used |
  | `off` | Right content, one constant offset and/or speed away | offset (ms), speed factor |
  | `off_mid_file` | Right content, but the offset changes partway (a different cut) | the per-chunk offsets |
  | `other_episode` | Content of another item in the store | which item (series + S/E, or film) |
  | `not_this_video` | Matches nothing checked | best ρ/z seen |
  | `longer_video` | Cues run well past the end of the file (compilation, longer cut, junk) | last cue vs length |
  | `cant_tell` | No verdict | why: `no_reference`, `reference_unusable`, `mono_audio`, `too_few_cues`, `weak` |

  Embedded subtitles are not checked. They are the reference.

- **FR-273-2 — The reference ladder.** A sidecar is checked against the strongest reference its file has, in this
  order, and the verdict records which one was used:
  1. **An embedded text subtitle of the same file** (any language). Skipped when it has fewer than 3 cues per
     minute: that is a forced or signs track not flagged forced (22 of the 944 above).
  2. **A sibling sidecar already judged `in_sync`** against rung 1 or 3. An unjudged sibling never counts: two
     sidecars from the same wrong upload agree with each other.
  3. **The file's speech track** (FR-273-5).

  Every sidecar also gets the free duration check (last cue vs the video's length) whatever rung it reaches.
  A file with no rung is `cant_tell · no_reference`.

- **FR-273-3 — Which episode it really is.** When a sidecar does not match its own file, it is compared against
  the references of every other file of the same series in the same and adjacent seasons (for a film, only its
  own file). A clear winner makes it `other_episode` pointing at that item. This is what lets a mislabelled
  subtitle be moved to where it belongs (FR-273-11) instead of only thrown away.

- **FR-273-4 — Thresholds, and a corpus that keeps them honest.** Starting values from the research:
  - Against a subtitle reference: *fits* when ρ ≥ 0.35, or ρ ≥ 0.2 with z ≥ 5; *does not fit* when ρ < 0.2 and
    z < 5; otherwise `cant_tell · weak`.
  - Against a speech track: *fits* when z ≥ 5 and the own file beats every neighbour by ≥ 0.8; `other_episode`
    only when a neighbour wins by that margin; **`not_this_video` is never concluded from speech alone** (the
    audio method condemned 2 right subtitles in the research). A sidecar that fits no speech track becomes
    `cant_tell · weak`, which FR-273-14 treats as "ask".
  - `off` when the best fit is at |offset| ≥ 1 s or a speed factor ≠ 1; `off_mid_file` when 5-minute chunks
    disagree by more than 1 s after the first and last chunk are ignored (logos and credits).
  - `longer_video` when the last cue is more than 10% plus 60 s past the file's length.

  The files that have both an embedded reference and stereo audio are the calibration corpus: the speech-track
  verdict must agree with the subtitle verdict on them before speech verdicts are trusted, and a test pins the
  animated series' 34 known cases.

- **FR-273-5 — A speech track per file, from the decode that already happens.** Phase 222 decodes every file's
  audio once for the segment editor's waveform, as mono 8 kHz. That decode becomes stereo 16 kHz and produces,
  in the same pass, both the existing peak envelope (from the mid channel) and a **speech track**: per 100 ms,
  the share of 10 ms frames where mid energy exceeds side energy by 10 dB and mid loudness is above the file's
  40th percentile. About 13 KB per episode. Files that already have a waveform get a speech track only when they
  need one: a sidecar and no usable embedded subtitle (about 1,650 files today). The decode stays on the
  segments lane, behind `SegmentProcessGate`, deferred while a TV plays (262).

- **FR-273-6 — The embedded reference is already being fetched.** `prewarm_subtitles` calls Jellyfin's
  `…/Subtitles/{index}/0/Stream.vtt` for every embedded text stream and discards the body. For each file it
  keeps the cue times of one stream, the lowest-index non-forced text stream with at least 3 cues per minute, and
  stops fetching further streams for this purpose once it has one. No extra request, no extra read of the video.
  Files whose embedded subtitles have never been pre-warmed get one pre-warm call for that one stream when they
  have a sidecar to check.

- **FR-273-7 — Stored like 255.** A `subtitle_check` table keyed by the sidecar's path, with its size and mtime,
  the video's path, size and mtime, the verdict and its numbers, the reference used, and `checked_at`. A verdict
  is reused until either file changes. References are stored per video in `subtitle_reference` (kind:
  `embedded` with stream index, or `speech`; 10 Hz; size and mtime of the video), so a sidecar that arrives later
  is checked in milliseconds.

- **FR-273-8 — A pipeline step with a cadence.** `check_subtitles` joins the file checks of phase 261, one job per
  file on the job queue (213), with its own cadence (default: after every scan for new or changed sidecars, and
  weekly for everything), deferred while a TV plays (262), on the BACKGROUND outbound class (182) for Jellyfin
  and Bazarr calls.

### §B — Hear about a subtitle the minute Bazarr places it

- **FR-273-9 — A Bazarr hook.** `POST /api/webhooks/bazarr?secret=…`, gated by the existing
  `ingest.webhook_secret` (165) and recorded in `WebhookStatus` (221) so the Bazarr card can say when Bazarr last
  called. Bazarr's *Custom Post-Processing* runs a command after every download, upgrade, manual download and
  upload, with these variables among others: `{{episode}}`, `{{subtitles}}`, `{{subtitles_language_code2}}`,
  `{{provider}}`, `{{subtitle_id}}`, `{{score}}`, `{{series_id}}`, `{{episode_id}}` (Sonarr episode id or
  Radarr movie id). `curl` is present in the linuxserver Bazarr image. The advisor (FR-273-18) generates the exact
  command, for example:

  ```
  curl -fsS -m 5 --data-urlencode episode={{episode}} --data-urlencode subtitles={{subtitles}} \
    --data-urlencode language={{subtitles_language_code2}} --data-urlencode provider={{provider}} \
    --data-urlencode subtitle_id={{subtitle_id}} --data-urlencode score={{score}} \
    --data-urlencode series_id={{series_id}} --data-urlencode episode_id={{episode_id}} \
    <address Bazarr reaches jellystructure at>/api/webhooks/bazarr?secret=<secret>
  ```

  Bazarr runs the command inside its download worker and ignores the exit code, so the route **queues and
  answers at once** (202). Items are matched by Bazarr's ids through 157's id join, never by rewriting paths; the
  paths are kept only for Bazarr calls that need Bazarr's own path strings.

- **FR-273-10 — Without the hook, nothing is missed, only later.** Bazarr's episode and movie history (157
  already reads it) is polled every 15 minutes for rows newer than the last seen, and the weekly cadence of
  FR-273-8 catches anything else (a file dropped by hand, a Lingarr translation). The hook makes it immediate;
  it is not required for correctness.

### §C — Steer Bazarr until the subtitle fits

- **FR-273-11 — A mislabelled subtitle goes where it belongs.** When a sidecar is `other_episode` for item B, and
  B has no subtitle in that language or B's is itself not `in_sync`, jellystructure uploads the file to B through
  Bazarr (`POST /api/episodes/subtitles` or the movie equivalent, same language and flags), then treats the source
  as wrong for its own episode (FR-273-13). The file is read from disk before FR-273-13's blacklist deletes it.
  An upload is recorded by Bazarr at the maximum score, so its upgrade job leaves it alone.

- **FR-273-12 — Offsets are fixed by Bazarr's sync, told exactly how.** For `off`, jellystructure calls Bazarr's
  sync (`PATCH /api/subtitles`, `action=sync`) with:
  - `reference`: `s:N` for the embedded stream used in FR-273-2 when its subtitle-relative index is 0–9 (Bazarr
    accepts only a 3-character stream reference); otherwise the path of an `in_sync` sibling sidecar; otherwise
    `a:0`.
  - `max_offset_seconds`: the smallest of Bazarr's own choices (60, 120, 300, 600) that exceeds the measured
    offset.
  - `no_fix_framerate`: `False` when the measured speed factor is not 1.
  Bazarr rewrites the file in place and logs a sync in its history. The hook does not fire for a sync, so
  jellystructure re-checks the file itself when the call returns. Still `off`, or `off_mid_file` from the start
  (ffsubsync applies one offset and speed only): the subtitle is timed for another release and goes to
  FR-273-13 as if wrong.

- **FR-273-13 — Replace what is wrong, through Bazarr's blacklist.** For `not_this_video`, `longer_video`, an
  `other_episode` after FR-273-11, or an `off` that sync could not fix, jellystructure blacklists the subtitle's
  (provider, subtitle id) in Bazarr (`POST /api/episodes/blacklist` or the movie equivalent). Bazarr then deletes
  the file and searches again at once; the new pick arrives through the hook and is checked like any other. The
  blacklist is global in Bazarr, which is right: a mislabelled upload is wrong wherever it is offered. **At most 3
  blacklist rounds per (file, language)** in 24 hours; after that the file waits for FR-273-14's neighbour search
  or for the admin.

- **FR-273-14 — When the label is broken, look next door.** When a series has two or more `other_episode`
  verdicts with the same shift (for example, uploads labelled E05 hold E02's content), or a (file, language) has
  used its 3 rounds, jellystructure asks Bazarr for the candidates of the episodes the pattern points at
  (`GET /api/providers/episodes?episodeid=B`) and has Bazarr download the likeliest one **onto the file that needs
  it** (`POST /api/providers/episodes` with the target's ids and the candidate key from B's search; Bazarr does
  not tie a key to the episode it was found for). The result is checked; a miss is deleted through Bazarr
  (`DELETE /api/episodes/subtitles`) and blacklisted only for that target. Candidate keys expire after an hour in
  Bazarr, so search and download happen together.

- **FR-273-15 — A settled subtitle is not lost to a blind upgrade.** jellystructure keeps a copy of every sidecar
  it has judged `in_sync` (a few KB each, in its own store). If Bazarr later replaces it (an upgrade, a new
  search) and the replacement checks worse, jellystructure uploads the kept copy back through Bazarr. An upload is
  recorded at the maximum score, so it is not upgraded again.

- **FR-273-16 — A budget, and a say.** Every Bazarr action that costs a provider download (blacklist rounds,
  neighbour downloads) counts against a daily budget in Settings → Download tools → Bazarr (default 100 a day;
  OpenSubtitles VIP allows 1,000). One setting says what jellystructure does about a bad subtitle:
  - **Fix it** (default): FR-273-11 to FR-273-15 run on their own for verdicts from a subtitle reference; a
    verdict from speech alone waits in *Needs your OK*.
  - **Ask me first**: every action waits in *Needs your OK* with what it would do.
  - **Only report**: verdicts and advice, no action.

### §D — What viewers see

- **FR-273-17 — A known-wrong subtitle is not offered.** While a sidecar is `not_this_video`, `other_episode` or
  `longer_video`, the server leaves it out of the subtitle list it builds for Ravilo and never chooses it as the
  default or the remembered track (253, R235). An `off` subtitle stays offered while Bazarr fixes it. No client
  change and no new wire value: the track simply is not in the list. This narrows R180's "nothing hidden"
  invariant to tracks that belong to the video, and says so in R180's spec when this is built.

### §E — Guide the admin to the settings that help

- **FR-273-18 — A Bazarr settings advisor.** A `BazarrAdvisorService` reads `GET /api/system/settings` and the
  series list (157's client) on demand, cached 5 minutes, on the BACKGROUND class, and returns findings in phase
  212's `AdvisorFinding` shape (current value, what it costs here, navigation path, exact field label,
  recommendation, trade-off, severity). It follows 212's rules: a finding renders only when the live value
  differs from the recommendation, every finding is tied to this library's own numbers, and a Bazarr that cannot
  answer shows *Couldn't reach Bazarr*, never an empty list. The findings show on the Bazarr card in Settings →
  Download tools and, through `advisorFindingHtml`, on the Dashboard beside the Jellyfin advisor (257). Labels are
  re-extracted from Bazarr's own frontend bundle at build time (as 212 did from Jellyfin's string table); the
  ones below were read from Bazarr v1.6.1 on 2026-09-27.

  | Finding | Bazarr label (section) | Recommended | Why, in this library's numbers |
  |---|---|---|---|
  | The hook is not set | *Custom Post-Processing*, *Command* (Custom Post-Processing) | on, with the generated command | jellystructure hears about a subtitle at the next poll instead of before anyone plays it |
  | The hook skips high scores | *Series Score Threshold For Post-Processing*, and the movies one | off | wrong subtitles score as high as right ones (93.4% vs 93.1%) |
  | Sync is off | *Enable Automatic Subtitles Audio Synchronization* (Audio Synchronization) | on | 274 of 944 checked subtitles are the right episode but off; Bazarr fixes most of them at download, jellystructure only checks |
  | Sync gives up too early | *Max Offset Seconds* | 300 | 58 subtitles here are off by more than 20 s, the largest 115 s |
  | Sync leaves PAL subtitles slow | *Do Not Fix Framerate Mismatch* | off | 13 subtitles here are timed for 25 fps |
  | Upgrades without the hook | *Upgrade Previously Downloaded Subtitles* (Upgrading Subtitles) | shown only while the hook is not set | an upgrade uses the same score that cannot see the episode; with the hook, jellystructure re-checks and restores (FR-273-15) |
  | A minimum score that rejects everything | *Minimum Score For Episodes* / *For Movies* (Search Scores) | ≤ 92% (shown only above) | the score's floor for any labelled result is 91.7%; above it only hash and release-group matches survive, 3% of right subtitles |
  | Jellyfin is not told | *Refresh series metadata after downloading subtitles* (Integrations → Jellyfin) | on | a replaced or removed subtitle stays listed in Jellyfin until its next scan |
  | A show searched by title | per show; fixed at the source of Sonarr's ids | an IMDb id on the show's TheTVDB entry | Bazarr has no IMDb id for 41 shows and searches them by title; one got another show's subtitle. The finding names the show and the IMDb id jellystructure knows from TMDB |

  Trade-offs are stated plainly: sync costs an audio decode per download where the file has no embedded
  subtitle, and a larger maximum offset makes a rare spurious alignment more likely (jellystructure's check
  catches that).

- **FR-273-19 — Suggest, don't change.** Following 212, the advisor never writes Bazarr's settings. Each finding
  gives the value to set and, for the hook, the exact command with a Copy button.

### §F — The admin sees what happened

- **FR-273-20 — On the title page.** Movie *Tracks & subtitles* and series *Seasons & episodes* show each
  sidecar's verdict in plain words: *In sync*; *12 s late — Bazarr is syncing it*; *Runs 4% slow — Bazarr is
  syncing it*; *This is S03E06's — moved there*; *Not this episode — replaced (2 tried)*; *Can't check — no
  reference in this file*. A sidecar waiting for approval shows the proposed action with *Do it* and *Leave it*.
- **FR-273-21 — On the Dashboard.** A *Subtitles* card beside 157's summary: counts by verdict, what is waiting in
  *Needs your OK*, provider downloads used today against the budget, and when Bazarr last called.
- **FR-273-22 — In History.** Every action jellystructure takes (sync requested, uploaded to another episode,
  blacklisted, neighbour candidate tried, settled copy restored) is written to the title's History with the
  verdict numbers that caused it, as 170 does for segments.

## API (all admin, all additive)

| Route | Purpose |
|---|---|
| `POST /api/webhooks/bazarr?secret=` | Bazarr's post-processing hook (FR-273-9) |
| `GET /api/subtitles/checks?item=` | verdicts for one title |
| `GET /api/subtitles/summary` | Dashboard card counts, *Needs your OK*, budget |
| `POST /api/subtitles/checks/{id}/approve` · `/dismiss` | act on a waiting proposal |
| `GET /api/bazarr/advisor` | Bazarr findings (FR-273-18) |

The Ravilo DTOs do not change (FR-273-17 only removes a track from a list).

## Storage

`subtitle_check` and `subtitle_reference` (FR-273-7), a small `subtitle_settled` table for FR-273-15's copies
(path, language, content, checked_at), the budget ledger, and the *Needs your OK* queue. One forward migration.
Config: `bazarr.check_action` (`fix` · `ask` · `report`), `bazarr.daily_download_budget`, and the address Bazarr
reaches jellystructure at (defaulting to 165's `jellyfin_reach_url`, since both run beside the backend).

## Acceptance

1. **Reproduces the research.** With actions set to *Only report*, a full run on production gives the verdict
   counts of *Today* within a few per class for the 944 sidecars that had a reference, and flags the same 192 by
   duration.
2. **The known cases.** A test pins the animated series' 34 old subtitles with a reference: 15 fit, 19 do not, the
   10 identifiable ones point at the right episodes.
3. **Speech agrees before it is trusted.** On files with both an embedded reference and stereo audio, speech
   verdicts agree with subtitle verdicts on at least 95% of *fits*, and never call `not_this_video` (FR-273-4).
4. **The hook.** A subtitle Bazarr downloads is checked within a minute of the download, and *Custom
   Post-Processing* with the generated command pasted in shows *Last called …* on the Bazarr card.
5. **An offset is fixed by Bazarr.** A sidecar measured 50 s late is synced by Bazarr with `max_offset_seconds=120`
   and the embedded reference, re-checks `in_sync`, and History shows both steps.
6. **A mislabelled subtitle moves.** A sidecar identified as its neighbour's is uploaded to the neighbour through
   Bazarr, the neighbour re-checks `in_sync`, and the source is blacklisted and re-searched.
7. **The budget holds.** With a budget of 5, the sixth provider download of the day waits until tomorrow.
8. **Viewers.** A `not_this_video` sidecar is absent from the Ravilo subtitle list and from default selection;
   an `in_sync` sibling in the same language is offered.
9. **The advisor is silent where Bazarr is already right**, and on this server today shows the hook, sync, max
   offset, frame rate, Jellyfin integration and title-search findings.

## Non-goals

- **Downloading or searching subtitles itself**, or holding OpenSubtitles credentials. Every provider action goes
  through Bazarr.
- **Retiming files itself.** Bazarr's sync applies one offset and one speed. A subtitle that needs more (a cut that
  differs mid-file) is treated as the wrong release and replaced. A piecewise sync (alass) would be Bazarr's to
  add; a feature request exists upstream.
- **Naming the episode from the dialogue with a language model.** It worked on the two cases probed and would
  cover files with no reference at all, as a third job on the AI queue (270/272). A follow-up phase.
- **IMDb's episode numbering as a warning before any download.** IMDb's `title.episode` dataset would show which
  series Bazarr will mislabel by design; phase 158 fetches only ratings today. A follow-up.
- **Checking embedded subtitles.** They are the reference.
- **Changing Bazarr's settings for the admin.** Open question 1.

## Open questions

1. **An *Apply in Bazarr* button?** Bazarr's `POST /api/system/settings` could set a recommended value in one
   click. 212 chose suggest-only for Jellyfin; the same is assumed here. Lean: suggest-only, but offer *Apply* for
   the hook command alone, because it is jellystructure's own address and secret.
2. **Speech-only verdicts on *Fix it*.** FR-273-16 makes them wait for approval. Once acceptance 3 has run on a
   month of production data, should confident speech verdicts act on their own?
3. **Blacklist deletes before the next pick is known.** For a few minutes the language is missing. Acceptable,
   since the deleted file was wrong. A "blacklist without deleting" call does not exist in Bazarr; posting a
   subtitle path that does not exist writes the blacklist row and stops before the search (observed by the
   research session), but relying on that is a hack. Dev review to decide.
4. **Where the Bazarr hook address comes from** when Bazarr runs on another host than Jellyfin. Default from
   `jellyfin_reach_url`, editable on the Bazarr card.
5. **Croatian and Serbian** are 59% of the checked sidecars and 23 of the 30 not for their video. Whether they are
   still wanted is the household's call, not this phase's.

## Verification plan

Read-only first: run the check with *Only report* on a copy of production's database and the live Jellyfin cache,
compare with the research data, then enable *Ask me first* for one series with a known shift and one with a known
offset, then *Fix it*. Run `scripts/check-phases.sh` and, for the admin, `scripts/check-mobile-css.sh`.
