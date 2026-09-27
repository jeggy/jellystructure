# Phase 273 — Every subtitle is checked against its own video, and jellystructure steers Bazarr until it fits

> Owner, 2026-09-27, after a viewer found an animated series playing with another episode's Danish subtitles:
> *"Can we investigate and see if we can help with stopping this with jellystructure?"*
>
> And after the research: *"We want jellystructure to solve this and as well as the offset issue. Jellystructure
> should be the brain of everything without redoing something it should guide the tools to do their jobs even
> better. And if some configuration is off, then jellystructure should guide the admin to set the best settings
> in the different tools."*

## Status

`✓ Built` 2026-09-27, **not deployed** (commits `fd1d09ae` → the build-notes commit; see *Build notes* at the
end). Acceptance 1, the read-only *Only report* run on production, waits for a deploy the owner approves.
`Planned` when written 2026-09-27 from two research passes the same day (a single animated series, then every
sidecar in the production library; the reports are private because they name library titles). The owner
answered the open questions the same day (see *Owner decisions*). **Dev-reviewed 2026-09-27** against `main`
`0766a76b`: buildable, three corrections folded into the text below (see *Dev review* at the end). Backend (a check, a hook, a Bazarr steering loop, an advisor) and admin (verdicts on the title
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
| Telling Jellyfin a subtitle changed | **jellystructure** (dev review item 1) | Tells Jellyfin about exactly the file that changed. Bazarr's own integration does nothing without library ids and otherwise falls back to rescanning whole libraries |
| Deciding whether a subtitle fits its video | **jellystructure** | New in this phase |
| Knowing which settings in Bazarr work against that | **jellystructure** (advisor) | New in this phase: says what to change, where, and why, and applies it when the admin presses *Apply in Bazarr* |

jellystructure never talks to a subtitle provider, never downloads a subtitle itself, never writes a subtitle
file directly, and changes a Bazarr setting only when the admin presses *Apply in Bazarr*. Every change to a subtitle on disk goes through a
Bazarr API call, so Bazarr's history, naming and language bookkeeping stay true. This supersedes one sentence
of phase 157 ("jellystructure stores nothing about subtitles"): it still stores nothing *Bazarr* owns, but a
verdict about whether a file fits a video is jellystructure's own finding, like 255's track coverage.

**Rather nothing than wrong** (owner, 2026-09-27: *"I do not want incorrect things. Rather have nothing than
something which is just wrong."*). A subtitle that does not belong to its video, or that is too far off to
read along with, is never kept as a fallback and never shown to a viewer, even when no right one can be found
yet. An empty language is the correct state for a title nobody has a right subtitle for.

## Owner decisions (2026-09-27)

1. **An *Apply in Bazarr* button: yes.** Every advisor finding with a value to set gets one (FR-273-19).
2. **A subtitle only the audio doubts** (explained to the owner in plain terms: files with no subtitle inside
   them can only be compared with who is talking when, which is less certain and wrongly doubted 2 right
   subtitles in the research). Resolved from decision 5: it is **hidden from viewers at once** and **thrown away
   only after the admin says OK** (FR-273-16), because hiding can be undone and a Bazarr blacklist cannot.
3. **Blacklisting: "the best way".** Bazarr's blacklist is used as designed (it deletes and searches again at
   once), with no workaround. A neighbour's candidate that turns out not to fit is deleted without a blacklist,
   because it may be right for its own episode (FR-273-13, FR-273-14).
4. **The hook address: whatever makes the best sense.** The address Jellyfin already reaches jellystructure at
   (165), editable, written into Bazarr by *Apply* (FR-273-9).
5. **Croatian and Serbian stay, under the same rule as every language:** rather nothing than wrong (FR-273-17,
   FR-273-23).

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

  The address is the one Jellyfin already reaches jellystructure at (165's `jellyfin_reach_url`), since Bazarr
  runs beside the backend too; the Bazarr card lets the admin change it. *Apply in Bazarr* (FR-273-19) writes the
  command, so the admin never types it. Bazarr has no way to test a post-processing command, so the card says
  *Waiting for Bazarr's first call* until one arrives and *Last called …* after.

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
  language is empty for those minutes, which is what decision 5 asks for. The blacklist is global in Bazarr,
  which is right here: a provider offers an upload under the label it carries, and that label is the wrong one.
  **At most 3 blacklist rounds per (file, language)** in 24 hours; after that the file waits for FR-273-14's
  neighbour search, and Bazarr's own wanted-search keeps trying on its schedule, each pick checked the same way.
  Every wrong pick is blacklisted for good, so the candidates run out rather than repeat. No workaround for
  "blacklist without deleting" is used.

- **FR-273-14 — When the label is broken, look next door.** When a series has two or more `other_episode`
  verdicts with the same shift (for example, uploads labelled E05 hold E02's content), or a (file, language) has
  used its 3 rounds, jellystructure asks Bazarr for the candidates of the episodes the pattern points at
  (`GET /api/providers/episodes?episodeid=B`) and has Bazarr download the likeliest one **onto the file that needs
  it** (`POST /api/providers/episodes` with the target's ids and the candidate key from B's search; Bazarr does
  not tie a key to the episode it was found for). The result is checked. A miss is deleted through Bazarr
  (`DELETE /api/episodes/subtitles`) **without** a blacklist, because it may well be right for the episode it
  was found under; jellystructure remembers that it was tried for this target and does not try it again there.
  Candidate keys expire after an hour in Bazarr, so search and download happen together.

- **FR-273-15 — A settled subtitle is not lost to a blind upgrade.** jellystructure keeps a copy of every sidecar
  it has judged `in_sync` (a few KB each, in its own store). If Bazarr later replaces it (an upgrade, a new
  search) and the replacement checks worse, jellystructure uploads the kept copy back through Bazarr. An upload is
  recorded at the maximum score, so it is not upgraded again.

- **FR-273-16 — A budget, and a say.** Every Bazarr action that costs a provider download (blacklist rounds,
  neighbour downloads) counts against a daily budget in Settings → Download tools → Bazarr (default 100 a day;
  OpenSubtitles VIP allows 1,000). One setting says what jellystructure does about a bad subtitle:
  - **Fix it** (default on a new install; an existing install starts on *Only report* and the owner switches it
    once, dev review item 8): FR-273-11 to FR-273-15 run on their own for verdicts from a subtitle reference. A
    subtitle that only the speech track doubts (`cant_tell · weak` against speech) is hidden from viewers at once
    (FR-273-17) and waits in *Needs your OK* before Bazarr is asked to throw it away (owner decision 2).
  - **Ask me first**: every action waits in *Needs your OK* with what it would do. Hiding from viewers still
    happens at once.
  - **Only report**: verdicts and advice, no action, nothing hidden. Meant for the first run on production.

### §D — What viewers see

- **FR-273-17 — A wrong subtitle is not offered.** The server leaves a sidecar out of the subtitle list it builds
  for Ravilo, and never chooses it as the default or the remembered track (253, R235), while it is:
  - `not_this_video`, `other_episode` or `longer_video`;
  - `off` or `off_mid_file` by 2 s or more anywhere in the file (offset plus drift at the end), until Bazarr's sync
    makes it `in_sync`. Smaller offsets stay offered while Bazarr syncs them;
  - doubted by the speech track and waiting for the admin (FR-273-16).

  No client change and no new wire value: the track simply is not in the list, and it comes back the moment it
  checks `in_sync`. The same rule decides every place a subtitle language is named (dev review item 2): the
  title's language flags in Ravilo and in the admin must never list a language the player will not offer. This narrows R180's "nothing hidden" invariant to tracks that belong to the video, and says
  so in R180's spec when this is built.

  `cant_tell` subtitles stay offered. Of the checked subtitles, 97% were the right content, so hiding every
  subtitle that cannot be checked would take away far more right ones than wrong ones; the speech track
  (FR-273-5) shrinks that group to files whose audio cannot be read.

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
  | A show searched by title | per show; fixed at the source of Sonarr's ids | an IMDb id on the show's TheTVDB entry | Bazarr has no IMDb id for 41 shows and searches them by title; one got another show's subtitle. The finding names the show and the IMDb id jellystructure knows from TMDB |

  Trade-offs are stated plainly: sync costs an audio decode per download where the file has no embedded
  subtitle, and a larger maximum offset makes a rare spurious alignment more likely (jellystructure's check
  catches that).

- **FR-273-19 — *Apply in Bazarr*.** Every finding with a value to set carries an *Apply in Bazarr* button (owner
  decision 1). It changes exactly that finding's settings and nothing else:
  - It posts only those fields to Bazarr's `POST /api/system/settings`, as the form fields Bazarr's own settings
    page sends (`settings-<section>-<key>`); Bazarr updates only the keys it receives.
  - It re-reads Bazarr's settings. The finding disappears only when Bazarr reports the new value; if it does not,
    the finding stays and says what Bazarr answered.
  - It writes a line to jellystructure's log: who pressed it, which setting, the value before and after.

  | Finding | What *Apply* sets |
  |---|---|
  | The hook | `general.use_postprocessing` on, `general.postprocessing_cmd` = the generated command, both score thresholds for post-processing off |
  | Sync | `subsync.use_subsync` on, `subsync.max_offset_seconds` 300, `subsync.no_fix_framerate` off |
  | Minimum score | the value shown (only when it is above 92%) |
  | A show searched by title | no button: the fix is an IMDb id on the show's TheTVDB entry, which the finding links to |

  The hook finding also keeps a Copy button for the command, for an admin who prefers to paste it.

### §F — The admin sees what happened

- **FR-273-20 — On the title page.** Movie *Tracks & subtitles* and series *Seasons & episodes* show each
  sidecar's verdict in plain words: *In sync*; *12 s late — Bazarr is syncing it*; *Runs 4% slow — Bazarr is
  syncing it*; *This is S03E06's — moved there*; *Not this episode — replaced (2 tried)*; *Can't check — no
  reference in this file*. A sidecar waiting for approval shows the proposed action with *Do it* and *Leave it*.
- **FR-273-21 — On the Dashboard.** A *Subtitles* card beside 157's summary: counts by verdict, what is waiting in
  *Needs your OK*, provider downloads used today against the budget, and when Bazarr last called.
- **FR-273-22 — In History.** Every action jellystructure takes (sync requested, uploaded to another episode,
  blacklisted, neighbour candidate tried, settled copy restored, a setting applied in Bazarr) is written to the
  title's History, or for settings jellystructure's log, with the numbers that caused it, as 170 does for segments.

### §G — Rather nothing than wrong

- **FR-273-23 — A wrong subtitle is never kept as a fallback.** A subtitle that is `not_this_video`,
  `longer_video`, `other_episode` (after FR-273-11 has moved it), or off in a way Bazarr's sync could not fix, is
  removed through Bazarr (FR-273-13) even when no replacement exists. jellystructure never uploads, restores or
  offers a subtitle it has judged wrong: FR-273-11 uploads only to the episode the file belongs to, and FR-273-15
  restores only copies that checked `in_sync`. When the candidates run out, the language stays empty and the
  title page says *No right subtitle found yet · 4 tried · Bazarr looks again in 6 h*. On *Only report* nothing is
  removed and nothing hidden, so the first production run changes nothing.

## API (all admin, all additive)

| Route | Purpose |
|---|---|
| `POST /api/webhooks/bazarr?secret=` | Bazarr's post-processing hook (FR-273-9) |
| `GET /api/subtitles/checks?item=` | verdicts for one title |
| `GET /api/subtitles/summary` | Dashboard card counts, *Needs your OK*, budget |
| `POST /api/subtitles/checks/{id}/approve` · `/dismiss` | act on a waiting proposal |
| `GET /api/bazarr/advisor` | Bazarr findings (FR-273-18) |
| `POST /api/bazarr/advisor/{finding}/apply` | *Apply in Bazarr* for one finding (FR-273-19) |

The Ravilo DTOs do not change (FR-273-17 only removes a track from a list).

## Storage

`subtitle_check` and `subtitle_reference` (FR-273-7), a small `subtitle_settled` table for FR-273-15's copies
(path, language, content, checked_at), the budget ledger, and the *Needs your OK* queue. One forward migration
(58).
Config: `bazarr.check_action` (`fix` · `ask` · `report`), `bazarr.daily_download_budget`, and the address Bazarr
reaches jellystructure at (defaulting to 165's `jellyfin_reach_url`, since both run beside the backend).

## Acceptance

1. **Reproduces the research.** With actions set to *Only report*, a full run on production gives the verdict
   counts of *Today* within a few per class for the 944 sidecars that had a reference, and flags the same 192 by
   duration.
2. **The known cases.** A test pins the animated series' 34 old subtitles with a reference: 15 fit, 19 do not, the
   10 identifiable ones point at the right episodes. The fixture holds cue timings only, under opaque ids: no
   subtitle text and no title enters the repo (dev review item 3).
3. **Speech agrees before it is trusted.** On files with both an embedded reference and stereo audio, speech
   verdicts agree with subtitle verdicts on at least 95% of *fits*, and never call `not_this_video` (FR-273-4).
4. **The hook.** A subtitle Bazarr downloads for a file whose reference is already stored is checked within a
   minute of the download, even while a TV plays (dev review item 5), and *Custom
   Post-Processing* with the generated command pasted in shows *Last called …* on the Bazarr card.
5. **An offset is fixed by Bazarr.** A sidecar measured 50 s late is synced by Bazarr with `max_offset_seconds=120`
   and the embedded reference, re-checks `in_sync`, and History shows both steps.
6. **A mislabelled subtitle moves.** A sidecar identified as its neighbour's is uploaded to the neighbour through
   Bazarr, the neighbour re-checks `in_sync`, and the source is blacklisted and re-searched.
7. **The budget holds.** With a budget of 5, the sixth provider download of the day waits until tomorrow.
8. **Viewers.** A `not_this_video` sidecar, and one 50 s late, are absent from the Ravilo subtitle list and from
   default selection; an `in_sync` sibling in the same language is offered; the late one is offered again once
   Bazarr's sync makes it `in_sync`. The title's detail page, in Ravilo and in the admin, lists the same subtitle
   languages the player offers (dev review item 2).
9. **The advisor is silent where Bazarr is already right**, and on this server today shows the hook, sync, max
   offset, frame rate and title-search findings, and not the minimum score (90% today).
10. ***Apply* changes only what it names.** Bazarr's settings read before and after an *Apply* differ in exactly
    the finding's keys; the finding then disappears; jellystructure's log has the before and after values.
11. **Nothing rather than wrong.** An episode whose candidates are all wrong (the animated series has 13 today)
    ends with no subtitle in that language, not a wrong one, and says so on its page.

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
- **Changing a Bazarr setting without the admin pressing *Apply*.**

## For dev review (answered below, items 4 and 1)

The owner's decisions settled the design questions. These are facts to confirm in Bazarr before building:

1. **What Bazarr's empty `series_library_ids` / `movie_library_ids` mean** for its Jellyfin refresh (every
   library, or none). If none, *Apply* for the Jellyfin finding also fills them from Jellyfin's library list.
2. **Side effects of `POST /api/system/settings`** for the keys this phase writes. Bazarr's `save_settings` sets
   flags such as a scheduler update or a Sonarr/Radarr resync when some keys change; none of ours should start a
   full resync, and a test against a Bazarr container should show it.
3. **When speech-only verdicts may act without approval.** After acceptance 3 has run on a month of production
   data, a follow-up can let confident ones act on *Fix it* like subtitle-reference verdicts.

## Verification plan

Read-only first: run the check with *Only report* on a copy of production's database and the live Jellyfin cache,
compare with the research data, then enable *Ask me first* for one series with a known shift and one with a known
offset, then *Fix it*. Run `scripts/check-phases.sh` and, for the admin, `scripts/check-mobile-css.sh`.

## Dev review (2026-09-27, against `main` `0766a76b`)

Buildable, with three corrections (items 1–3, already folded into the text above) and the answers to *For dev
review* (item 4). Bazarr facts below were read from the running v1.6.1's source (`/app/bazarr/bin/`) and, where
marked, from read-only calls to the production Bazarr. Nothing was written to Bazarr or Jellyfin.

1. **Correction: jellystructure tells Jellyfin, not Bazarr's Jellyfin integration.** Bazarr's
   `jellyfin_refresh_item` returns without doing anything while `series_library_ids` / `movie_library_ids` are
   empty (they are, and Bazarr has no Jellyfin URL or key yet). With them set, an item it cannot find by IMDb,
   TMDB, TVDB id or title falls back to `POST /Library/Media/Updated` over every configured library path, a
   directory rescan of whole libraries for one subtitle; 41 shows here have no IMDb id. jellystructure already has
   the precise tools: `JellyfinClient.notifyLibraryMediaUpdated(path)` (phase 114, one path) and
   `refreshItem(…, full = false)` (`ValidationOnly`). After every hook call and every change it causes, it sends
   the sidecar's own path (`Created`, `Modified` or `Deleted`), and a `ValidationOnly` refresh of that one item if
   the stream list has not changed a minute later. The advisor's Jellyfin finding and its *Apply* (a dedicated
   Jellyfin API key) are removed. Build-time check on the demo Jellyfin, never production: which of the two calls
   makes Jellyfin 12.1 list a new external subtitle and drop a deleted one.
2. **Correction: every surface that names subtitle languages follows FR-273-17.** `DetailService` builds the
   Ravilo detail flags from store tracks, sidecars included (`DetailService.kt:81`, `:200`), and the admin
   pagebar's SUBTITLES strip does the same (200). Hiding a track only in `PlaybackService.buildSubtracks`
   (`:863`) would bring back phase 200's contradiction (the detail says Danish, the player has none). One predicate,
   `SubtitleVerdicts.offered(track)`, is used by `buildSubtracks`, `DetailService` and the admin strip.
   - `buildSubtracks` reads Jellyfin's `MediaStreams`, and `JellyfinMediaStream` (`auth/Models.kt:245`) has no
     `Path`. Add `@SerialName("Path")` and match an external stream to its sidecar by file name within the
     item's folder, since Jellyfin, Bazarr and jellystructure each see the media under a different root.
   - Default and remembered subtitle choice is client-side (R235's rule in `PlayerScreen.kt`, around `:4102`)
     and picks only from the list the server sends, so leaving the track out is the whole change. The spec's
     "(253, R235)" means exactly that; no server-side subtitle default exists to change.
   - In HLS mode (R265) Jellyfin's manifest still carries the rendition, but the picker only offers what
     `SubTrack` lists. Check on the Pixel 9 in HLS mode.
3. **Correction: the test corpus stays out of the public repo.** Acceptance 2's fixture is cue timings only
   (start and end in ms) under opaque ids, plus synthetic cases (shifted, 25 fps-scaled, spliced mid-file,
   another episode). No subtitle text and no title is committed. Acceptance 1 runs against production data on
   this machine, not in CI.
4. **The three questions.**
   1. *Empty Jellyfin library ids*: Bazarr does nothing (above). Moot after item 1.
   2. *Side effects of `POST /api/system/settings`*: the endpoint writes only the `settings-<section>-<key>`
      fields it receives. None of the keys this phase writes (`general.use_postprocessing`,
      `general.postprocessing_cmd`, `general.use_postprocessing_threshold`, `…_movie`, `general.minimum_score`,
      `…_movie`, `subsync.use_subsync`, `subsync.max_offset_seconds`, `subsync.no_fix_framerate`) is in any of
      `save_settings`' trigger lists (scheduler update, Sonarr/Radarr SignalR restart, provider reset, path
      maps, embedded-subtitle reindex; `app/config.py`). `general.upgrade_subs` would reschedule tasks; this
      phase never writes it.
   3. *Speech-only verdicts acting alone*: a follow-up, as written.
5. **The check: code map.**
   - **Parsing.** The backend has no subtitle parser; the only cue handling is in the wasm UI. New
     `subtitles/CueTrack.kt` (commonMain): SRT, ASS/SSA and WebVTT (Jellyfin's extraction answers in WebVTT) to a
     10 Hz bitset, with the forced-track rule (under 3 cues a minute). `.sub`/`.idx` sidecars
     (`SidecarSubtitleScanner.kt:17`) are images and are skipped, not given a verdict.
   - **Correlation.** No FFT exists (segment detection compares Chromaprint hashes). A small radix-2 real FFT in
     commonMain, tested against a naive correlation. Own file first; neighbours only when the own file does not
     fit (about 5% here), so a typical check is five 32k-point transforms. Buffers are reused per job: large
     short-lived arrays are what phases 228/230 fought in Kotlin/Native's GC.
   - **References.** `JellyfinClient.warmSubtitleExtraction` (`:1142`) calls `httpGet(url)` and drops the
     body; it returns the text instead, and `PipelineStepOps.prewarmSubtitles` hands the first qualifying stream
     per file to a `SubtitleReferenceStore`. Bazarr's `s:N` is the subtitle-relative index, so it comes from the
     track's `specifier` (`0:s:N`), not `streamIndex`.
   - **Speech track.** `FfmpegRunner.computeEnvelope` (`:251`, mono 8 kHz) gains a stereo 16 kHz variant that
     yields the envelope from the mid channel plus the speech track in one stream (a `SpeechAccumulator` beside
     `EnvelopeAccumulator`, `:530`). The mono path stays for files that need no speech track. When a file has
     several audio streams, use the one in the title's original language, else `0:a:0`. Mono audio gives
     `cant_tell · mono_audio`. Behind `SegmentProcessGate`, on the segments lane.
   - **The step.** `FileCheckSteps.SUBTITLES = "check_subtitles"` beside `VERIFY` and `LENGTHS`
     (`AppConfig.kt:125`), its own job type and dedupe key `subs:<video path>` in `FileCheckSchedule`, and the
     261 due rule extended so a changed sidecar set (path, size, mtime) makes the file `NO_RESULT`. Lane
     `subtitles`, after prewarm. A check whose references are already stored reads two small files and is exempt
     from 262's deferral, so a hook result appears within a minute; fetching a missing reference, decoding
     speech and asking Bazarr to sync (ffsubsync reads the whole video) all defer while a TV plays.
   - **Storage.** Migration **58** (57 is 272's): `subtitle_check`, `subtitle_reference`, `subtitle_settled`, and
     one `subtitle_action` table for *Needs your OK*, candidates already tried per target, and the budget
     ledger. History through `MediaHistory.record` (`media/MediaHistory.kt:22`).
   - **Initial run size.** 944 sidecars can be checked at once; 1,404 more after prewarm reaches their files;
     about 1,650 files need a speech track, each a full read on the segments lane. The research decoded 164
     episodes in under 6 minutes with 8 workers, so expect several deferred nights with one worker.
6. **Bazarr: code map and facts.**
   - **The hook** goes in `WebhookRoutes.kt` beside the Sonarr/Radarr/Jellyfin routes, with the same
     `constantTimeEquals` secret check (`:349`) and `WebhookStatus.recordArrHit("bazarr")`. Bazarr sets
     `{{series_id}}` to an empty string for a film, so an empty `series_id` means `episode_id` is a Radarr id.
     An episode resolves through Bazarr's own record (`GET /api/episodes?episodeid[]=`), then its
     `sonarrSeriesId` and the series' `tvdbId` to the store item (157's `resolveSeries`, reversed), then the
     sidecar by file name. Paths are never rewritten.
   - **`BazarrClient` additions**, all additive: blacklist (episode and movie), upload (multipart `file`),
     `syncSubtitle` with `reference`, `maxOffsetSeconds`, `noFixFramerate` and `gss` (today it sends none,
     `BazarrClient.kt:254`), `systemSettings()`, `applySettings(fields)`, `episodeById`. Models gain
     `BazarrSeries.imdbId`, `BazarrHistoryEvent.subsId`, `subtitlesPath`, `sonarrEpisodeId`, `blacklisted`,
     `upgradable` and `matches`, and `BazarrProviderResult.releaseInfo`, `uploader` and `origScore`; all are in
     Bazarr's responses today.
   - **Sync** writes `<name>.synced.srt`, renames it over the original and logs action 5. Its callback is chmod,
     store refresh and Plex/Jellyfin refresh, not the custom command, so the hook does not fire and jellystructure
     re-checks after the call returns. The reference must be exactly 3 characters (`s:0`–`s:9`); beyond that, an
     `in_sync` sibling's path or `a:0`.
   - **A manual-download key** lives 1 hour in Bazarr's in-memory cache and is saved against whichever episode
     the POST names (`subtitles/manual.py`), which FR-273-14 relies on.
   - **An upload** runs Bazarr's post-processing, so jellystructure's own uploads come back through the hook.
     They are recognised by content hash (the settled copy, or the moved file) and verified, never acted on again.
   - **The blacklist** logs, then deletes, and re-searches only when the delete succeeded. Used as designed.
   - **The advisor.** `BazarrAdvisorService` in `advisor/`, returning `AdvisorFinding`. `GET /api/system/settings`
     returns every secret Bazarr holds (checked, read-only), so the raw response never leaves the backend and
     findings carry values only. `advisorFindingHtml` (`Settings.kt:1480`) binds a single action today (244's
     `recheck_exposure`); it becomes an action-to-button map and gains `apply_bazarr`. Production today
     (read-only): post-processing off with no command, thresholds off, minimum 90/70, upgrades on, sync off, max
     offset 60, *Do Not Fix Framerate Mismatch* on, golden-section search on. So the advisor opens with the hook,
     sync, max offset and frame rate, plus the title-search shows; the minimum-score finding stays silent.
7. **Budget.** Bazarr made 4,859 downloads in its first 172 days here, about 28 a day, peaking at 717 on the
   import day. A 100-a-day budget for jellystructure's own actions fits well under OpenSubtitles VIP's 1,000.
   Clearing today's 30 wrong subtitles costs about that much; the 274 off ones cost nothing, because sync is
   local.
8. **First production run.** The migration seeds `bazarr.check_action = report` on an existing install and
   `fix` on a new one. The Bazarr card shows what *Fix it* would do from the report run (*would sync 274, replace
   30, hide 30*), and the owner switches it once. This keeps acceptance 1 a read-only run on production without
   changing the default the owner chose.
9. **Tests.** Cue parsers (SRT with BOM, ASS override tags, VTT cue settings); correlation against a naive
   reference; the verdict thresholds on synthetic shifts, scales, splices and wrong episodes; the 261 due rule
   with a changed sidecar; hook parsing for an episode and a film; the Bazarr steering loop against a mocked
   Bazarr (sync then re-check, move then blacklist, three-round cap, neighbour miss deleted without a
   blacklist, budget exhaustion, restore after a worse upgrade); `offered()` shared by `buildSubtracks` and
   `DetailService`; the advisor's silence rule and *Apply* sending only its own keys.

**Net effect.** A cue parser, an FFT, a speech track in the existing decode, one pipeline step, one hook, a
steering loop over Bazarr's own API, one advisor, migration 58, and one shared rule for which subtitles a viewer
sees. No Ravilo client change, and nothing written to Bazarr or Jellyfin except what the admin applies or a
verdict requires.

## Build notes (2026-09-27)

Built on `main` the same day, after the dev review (`fd1d09ae`, `11917d61`, `b9fb3048`, `f858ccdd`, `70108439`,
`58e291d3`). Every requirement is in. Where the build differs from the text above or from the dev review's code
map, the build is recorded here.

1. **Where things live.** The check is backend-only, so it went to `linuxX64Main/…/subtitles/` rather than
   commonMain: `CueParser.kt` (SRT with BOM/CRLF, WebVTT with cue settings, ASS by its `Format:` line;
   `.sub`/`.idx` skipped), `SubtitleTiming.kt` (10 Hz masks, a radix-2 FFT with one plan per check, since a
   shared twiddle cache was not thread-safe), `SubtitleVerdict.kt` (the thresholds and the offered rule as pure
   functions), `SpeechTrack.kt`, `SubtitleReferences.kt` (the dev review's `SubtitleReferenceStore`),
   `SubtitleCheckService.kt`, `SubtitleVerdicts.kt` (the one offered predicate). Bazarr: `bazarr/BazarrSteering.kt`,
   `bazarr/SubtitleHook.kt`, `advisor/BazarrAdvisorService.kt`; routes in `server/routes/SubtitleCheckRoutes.kt`
   (the hook route sits there rather than in `WebhookRoutes.kt`, with the same secret check and
   `recordArrHit("bazarr")`; `/api/webhooks/` was already the open prefix).
2. **The fit (FR-273-4), as calibrated.** Retiming is `t' = t × scale + shift`, searched at five scales (1,
   25/23.976, 23.976/25, 1.001, 1/1.001) over ±120 s. Subtitle-reference thresholds are the spec's. **Mid-file
   chunks are 3 minutes, not 5**, and on the production cue timings the chunk test gave false positives until
   three rules were added: median-of-3 smoothing; a chunk counts only when it beats the global fit by 20%; and a
   chunk whose best lag sits on the ±10 s search edge is ignored. A straight line
   through the chunk shifts decides between the two outcomes: a residual over 1 s is `off_mid_file`, otherwise
   the drift is linear and the sync is sent with `gss`. A throwaway port check (not committed; it reads
   production cue timings) ran these verdicts over the production sidecars with an embedded reference and agreed
   with the research's verdicts on **937 of 944**. The animated series' 34 cases are not pinned in a test,
   because they would name the title (dev review item 3). Synthetic cases cover each verdict.
3. **Speech track (FR-273-5): its own decode, not folded into 222's.** `FfmpegRunner.computeSpeechTrack` decodes
   one audio stream (the title's original language, else the first) as stereo 16 kHz through a 200–3500 Hz band
   and feeds a `SpeechAccumulator`: per 100 ms, the share of frames where mid beats side by 10 dB and loudness is
   above the file's 40th percentile. 222's waveform path is untouched. The decode runs only for a file that
   needs a speech track, on the segments lane, deferred while a TV plays. Mono audio is `cant_tell · mono_audio`.
   References (embedded or speech) are stored per video size and mtime, including "this file has none", so an
   unchanged file is never fetched or decoded twice.
4. **Embedded reference (FR-273-6).** `JellyfinClient.fetchSubtitleText` returns the body that
   `warmSubtitleExtraction` used to drop. `prewarm_subtitles` passes it to `SubtitleReferences`, which keeps the
   first stream that qualifies (≥ 3 cues a minute, not forced) and fetches at most 3 streams for a file with a
   sidecar and no stored reference.
5. **The step (FR-273-8).** `check_subtitles` is a 261 file-check step on the segments lane, dedupe key
   `subs:<video path>`, placed after `prewarm_subtitles`, **weekly** for every age bucket. A file is due when its
   sidecar set (path, size, mtime) or the video changed. A hook call checks inline from stored references; a
   check that needs a fetch or a decode is queued as a job instead. An existing install gets the step once through
   `scan.subtitle_check_seeded`.
6. **Config.** `[subtitle_check]` with `action` (`fix` · `ask` · `report`), `daily_download_budget` (100, 0–1000)
   and `bazarr_reach_url`, not `bazarr.check_action`. It has its own route (`GET`/`PUT /api/subtitles/settings`),
   and a Settings save keeps the stored block, so an older Settings page cannot reset the switch. A new install
   starts on `fix`; an existing one is seeded to `report` once.
7. **Steering (FR-273-11 to 16), as built.** In one pass per video: restore first (FR-273-15; a settled copy
   older than the video is dropped, never restored), then per verdict. `off`: sync with `s:N` from the track's
   specifier (only `s:0`–`s:9`), else an in-sync sibling's path, else `a:N`. `max_offset_seconds` is the first
   of 60/120/300/600 above the shift, `no_fix_framerate=false` when the scale is not 1, and `gss` when the drift
   is linear. A sync is judged by a check made after it, and one still off is replaced. `off_mid_file`,
   `not_this_video` and `longer_video` are replaced through the blacklist, at most 3 rounds a day and within
   the budget. `other_episode` is uploaded to the episode it belongs to, then replaced. Once the day's 3
   blacklist rounds are spent, FR-273-14 applies: when two or more episodes of the season carry the same shift
   (episode N holds N+k's subtitle), the best untried candidate of episode N−k is downloaded onto this one;
   with no such pattern, or none left, the wrong file is removed. Every action on a file's content is keyed by
   its content hash, so nothing is done twice to the same file. A verdict from the speech track only ever
   proposes.
8. **Jellyfin (dev review item 1).** A hook call sends the new sidecar's path (`Created`) and a change
   jellystructure causes sends the video's path (`Modified`) to `Library/Media/Updated`, under the library's
   `jellyfin_path` mapping. Both are followed at once by a `ValidationOnly` refresh of the one item, not a minute
   later. `notifyLibraryMediaUpdated` gained an optional update type. **Unverified:** which of the two calls makes
   Jellyfin 12.1 list a new external subtitle and drop a deleted one. The demo-Jellyfin check was not run.
9. **Phase 157 defects found and fixed on the way.** The manual provider search decoded a bare list and boolean
   fields, where Bazarr answers a `data` envelope with string booleans; history rows carry `language` as an
   object; a manual episode download did not send `seriesid`. All three were silent failures of 157's per-title
   Bazarr actions.
10. **Admin.** Settings → Download tools → Bazarr carries the switch, the budget, the reach address, the
    post-processing command (with Copy; the secret reaches only a signed-in admin, as 165's status does), whether
    Bazarr has called, the advisor with *Apply in Bazarr*, and, while on *Only report*, **what *Fix it* would do
    with the verdicts so far** (dev review item 8; `fix_would` on `/api/subtitles/summary`, from the same
    first-action rule the steering loop uses). Dashboard: fit counts, not offered, mode, downloads against the
    budget, last call, *Needs your OK*; the Bazarr advisor beside Jellyfin's. The title page has a *Do the
    subtitles fit?* card on both tabs, plus verdict chips on the movie's sidecar rows, a SUBTITLES strip without
    hidden sidecars, and *No right subtitle found yet · N tried* for a language left empty (FR-273-23). **Not
    shown:** FR-273-23's *Bazarr looks again in 6 h*, because jellystructure does not read Bazarr's search
    schedule.
11. **R180.** FR-RV-ASP1-4 carries a note narrowing "every track" to tracks that belong to the video.
12. **Tests.** 682 pass (`linuxX64Test`). New: `SubtitleTimingTest` (13: parsers, the FFT against a direct
    correlation, every verdict on synthetic shifts, PAL speed, a splice, another episode, a longer cut, speech,
    a thin embedded track, the offered rule), `SubtitleCheckServiceTest` (5), `BazarrSteeringTest` (6, against
    a fake Bazarr: sync and blacklist once, ask then approve, report does nothing and says what *Fix it* would
    do, no budget, restore after a blind upgrade, a move to the right episode) and `BazarrAdvisorTest` (3).
    Migration 58 passes `verifyCommonMainJellystructureDbMigration`; the admin compiles.

**Still open, all needing the owner:** deploy, then acceptance 1 (the *Only report* run on production, read-only),
then the switch to *Fix it*; the demo-Jellyfin check in item 8; and a Pixel 9 check in HLS mode that a hidden
sidecar is not in the picker (dev review item 2).
