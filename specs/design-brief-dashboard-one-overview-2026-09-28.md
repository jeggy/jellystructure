# Design brief — the Dashboard as one overview of everything that could be fixed

**Date:** 2026-09-28 · **For:** the design project (Cosmos) that owns `design/app/index.html` and `app-shell.js` ·
**Status:** brief, awaiting round-1 directions on the questions in §F · **Source:** the shipped page
(`Dashboard.kt`, v1.44) and its data on the household server on 2026-09-28, read-only; the phases that shaped it
(117, 121, 122, 128, 144, 146, 150, 157, 201, 212, 221, 242, 246, 254, 255, 257, 273, 278, 280, 283).

> Owner, 2026-09-28: *"Currently our dashboard has a lot of different warnings / suggestions and collapsable stuff
> (for some, but not all) and we also have a bunch of numbers of different things that are considered errors under
> the 'Needs your attention' section. … We want to try to re-structure this page, so we can get a better overview
> of everything that we could fix. And also maybe group things by movies/series/music/jellyfin/external-service-
> configs/etc."*

Every title, file name and person below is a fictional stand-in. Every **number** is real, from the household
server this afternoon, so the mockup can be drawn at true weight.

## 0. The page as it stands, top to bottom

The shipped Dashboard is a stack of things that were each added by a phase and never re-planned as a whole:

1. **Pagebar** — *Dashboard* · *Browse library* · **▶ Scan library** (split: *full rescan*) · *next run 13:30*.
2. **Operator findings** (221) — webhook failing, a dead *arr route. **Empty today**, so invisible.
3. **Jellyfin settings advisor** card (212/257) — **`28 to change · 2 critical`**. Folded to the *first* finding,
   the other 31 behind **`+27 more · 4 for information`**. *Open the advisor in Settings →*.
4. **Bazarr settings advisor** card (273) — **`4 to change`**. **Not folded**: all six findings shown, including two
   that are information only.
5. **Scan banner** — one of *Scanning — 412 of 9 590 items…* · *Paused — TV is watching (Stue TV)* · *Stopped —
   Jellyfin still finishing 3 subtitle extractions* · *Scan paused — 412 items already processed* · *Scan complete*
   · *Scan failed to start*. Empty when idle.
6. **Stat grid** — `Movies 321` · `TV episodes 9 269` · **`Items needing attention 2 483`** (red) · `NFO coverage
   99%` · `♪ Music 30 albums · 24 matched · 25 covers` · `Audiobooks 1 book · 14 parts · 5 h 27 min`.
7. **Needs your attention** (117 → 146) — **`2 483 items`** · *Show attention dock* · *Browse all →* · then
   **28 rows**, one per issue type, in a fixed order, every type always shown including `✓ 0`.
8. **Side column** — *Recently processed* (six raw lines) · *Subtitles · Bazarr* card · *Quick actions* (seven
   chips). The *Suggestions · Seerr* card of 274 is spec'd but not on the shipped page.

Three vocabularies, three fold rules, two kinds of count, and a red number that is the sum of things that are not
the same kind of thing. §B says what is wrong; §A first records everything, so the design can decide what to keep.

## A. Inventory — every warning, suggestion and count on the page today

### A1. Needs your attention — the 28 types, with this afternoon's counts

Order and severity are what `Dashboard.kt` hard-codes (146 §A4). *Instances* is what the badge shows; *titles*
appears in brackets when the two differ (`165 (3 titles)`). Each row links to the Library pre-filtered, except
the two segment rows (the segment editor's sheet) and the music/audiobook rows (their own kind, filtered).

| # | Key | Label · plain sentence (as shipped) | Sev | Count | Opens |
|---|---|---|---|---|---|
| 1 | `untagged` | **Untagged audio/subtitle tracks** — Tracks with no language tag — Ravilo and the workbench can't filter by language until these are assigned. | bad | **1 277** (79 titles) | Library |
| 2 | `missing_still` | **Missing episode image** — Episode has no still image — no TMDB still and no screen-grab — so Ravilo shows a blank episode card. | bad | 165 (3 titles) | Library |
| 3 | `missing_artwork` | **Missing poster artwork** — No poster.jpg on disk for this title. | bad | 1 | Library |
| 4 | `cascade_mismatch` | **Wrong default audio track** — The default audio track doesn't match the title's resolved metadata language. | warn | 52 | Library |
| 5 | `language_mix` | **Mixed-language series** — Episodes disagree on audio language — the majority language is used for metadata. | warn | 39 | Library |
| 6 | `multi_default` | **Multiple default audio tracks** — More than one audio track is flagged default — a file should have exactly one. | warn | 18 | Library |
| 7 | `cover_as_video` | **Cover art muxed as a video track** — A still image is muxed as a second video stream — players may open the file but never start the video. Repairable. | bad | 13 (5 titles) | Library |
| 8 | `segments_lowconf` | **Low-confidence segments** — Intro/credits found by heuristic below 0.60 — worth an eyeball. | warn | **539** (94 titles) | Segment editor |
| 9 | `no_segments` | **No intro/credits detected** — Skip Intro/Credits falls back to the fixed end-of-file heuristic. | warn | **217** | Segment editor |
| 10 | `zero_audio` | **No audio tracks** — Zero audio tracks detected — usually a corrupt/truncated file. | bad | 1 | Library |
| 11 | `duplicate` | **Duplicate library entries** — The same Jellyfin item appears more than once. | bad | ✓ 0 | Library |
| 12 | `duplicate_episode` | **Duplicate episode files** — Two files claim the same episode number — Ravilo can only play one, and auto-play-next stalls on the copy. | bad | 12 (3 titles) | Library |
| 13 | `unresolved_jellyfin_id` | **Episode never matched in Jellyfin** — jellystructure read a season/episode from the filename, but Jellyfin never numbered this file — it drops out of playstate, next-episode and Continue Watching. | bad* | 12 (3 titles) | Library |
| 14 | `missing_from_source` | **No longer in Jellyfin** — kept for review, never auto-deleted. | bad | ✓ 0 | Library |
| 15 | `mkv_track_layout` | **Unplayable in Ravilo (MKV structure)** — Tracks element after the first Cluster, or a corrupted declared size. Repair rewrites the header in place. | bad | 1 | Library |
| 16 | `file_damage` | **Damaged video files** — A deep check found parts that cannot be read. jellystructure can replace the file from the clean copy qBittorrent is still seeding. | bad | 44 (12 titles) | Library |
| 17 | `track_ends_early` | **Audio or video stops before the file ends** — viewers hear silence or see black from that point. | bad | 7 (6 titles) | Library |
| 18 | `duration_header_wrong` | **File claims to be longer than it is** — never reaches 90 %, so never marked watched. | warn | 8 (7 titles) | Library |
| 19 | `music_shared_album` | **Albums in several folders** — Two or more folders say they are the same album — often a band's singles whose files all name one compilation. | warn | 10 | Music kind |
| 20 | `music_folder_disagrees` | **Albums whose folder and songs disagree** — The folder's name and the songs' tags name different things. | warn | 12 | Music kind |
| 21 | `music_needs_match` | **Albums need a match** — MusicBrainz found several candidates and none clearly won, or found nothing. | warn | 6 | Music kind |
| 22 | `music_no_cover` | **Albums without a cover** — Matched, but no cover on disk. | warn | ✓ 0 | Music kind |
| 23 | `music_no_picture` | **Artists without a picture** — Neither fanart.tv nor Wikimedia Commons had one. | warn | 8 | Music kind |
| 24 | `music_reencodes` | **Songs a phone plays only by re-encoding** — WMA files. Convert… makes AAC copies and keeps the originals. | warn | 38 | Music kind |
| 25 | `audiobooks_missing_part` | **Audiobooks with a missing part** — The folder's files skip a number. | warn | 1 | Audiobooks |
| 26 | `audiobooks_two_in_one` | **Folder holds two books** | warn | ✓ 0 | Audiobooks |
| 27 | `audiobooks_no_cover` | **Audiobooks without a cover** | warn | 1 | Audiobooks |
| 28 | `audiobooks_no_narrator` | **Audiobooks with no narrator** — For information — a book plays the same without one. | info (dimmed) | 1 | Audiobooks |

\* `unresolved_jellyfin_id` is not in the hard-coded order, so it renders after the list with the default
severity. **Sum: 2 483 instances**, of which **1 277 are one type** and **756 are the two segment types**; the
other 25 types share 450. The stat tile `Items needing attention` shows the same 2 483, but `/api/stats` still
answers `issues: 1277` (untagged only), the number the tile showed before phase 117.

### A2. The Jellyfin settings advisor — 32 findings today (28 to change · 2 critical)

Read-only, suggest-only, silent where the live value already matches (212). Each finding carries: severity ·
summary · Jellyfin's exact on-screen label · the navigation path · current value · recommendation · trade-off ·
what it costs here · an optional action (*Re-check* for exposure, *Apply in Bazarr* on the Bazarr side). The
Dashboard shows the first and folds the rest; Settings shows all, server-wide then per library.

**Server-wide (12):**

| Sev | Finding | Now |
|---|---|---|
| **critical** | Hardware encoding is switched on with no accelerator selected | Hardware acceleration: none · Hardware encoding: On |
| warn | Known proxies is set, but this server still classifies a public caller as in-network *(Re-check)* | Known proxies: one address · a caller presenting a public address |
| warn | `sdc` runs mq-deadline, not BFQ | `/sys/block/sdc/queue/scheduler = mq-deadline` |
| warn | `sdc`'s read-ahead is at the 128 KB default | 128 |
| warn | `sdc`'s max_sectors_kb (1280) is well below what it could carry | 1280 |
| warn | `sda` runs mq-deadline, not BFQ · read-ahead 128 KB · max_sectors_kb 1280 | (three findings) |
| warn | vm.swappiness is 60 and this host has paged 585 GB out to swap | 60 · 47 GB currently in swap |
| info | Jellyfin serves media to callers with no credential at all | Remote access: enabled |
| info | On-demand keyframe extraction is enabled for mkv | — |
| info | Jellyfin reports a pending restart | `HasPendingRestart: true` |

**Per library (20):** Film 4 · Serier 4 · Blandet 4 · Musik Videoer 2 · Bøger 3 · Musik 3. The same four
findings repeat per video library — *chapter image extraction on rotational storage*, *chapter images during the
scan*, *trickplay on rotational storage* (or *"during the scan" is on while trickplay is off*), *LUFS scan on
rotational storage*. Bøger and Musik add *Jellyfin fetches its own metadata for a library jellystructure manages*
(internet providers on); Bøger adds the info *saves artwork into the audiobook folders*; and **Musik carries the
second critical: *Jellyfin's NFO metadata saver is on for a library jellystructure manages*** (which is, today,
rewriting every `album.nfo` 277 writes — see `research-reports/music-tags-in-the-files-2026-09-28.md` §1).

### A3. The Bazarr settings advisor — 6 findings (4 to change)

Same finding shape; *Apply in Bazarr* changes exactly the one setting named (273).

| Sev | Finding | Now |
|---|---|---|
| warn | Bazarr doesn't tell jellystructure when it places a subtitle *(Apply)* | Off |
| warn | Bazarr doesn't align subtitles to the video when it downloads them *(Apply)* | Off |
| warn | Bazarr's sync gives up on large offsets *(Apply)* | 60 |
| warn | Bazarr's sync leaves subtitles made for 25 fps running slow *(Apply)* | On |
| info | Bazarr's upgrades can swap a right subtitle for a wrong one | On, every 12 h for 7 days |
| info | Bazarr searches 40 shows by title | a list of shows without an IMDb id |

### A4. The cards in the side column

**Recently processed** — the last six History entries, `detail` cut at 52 characters, a green or red dot from
whether the action name contains *fail*, *error* or *no_match*. This afternoon all six read the same:

```
● file=Cliff.Cottage.S05E24.NORDiC.ENG.1080p.WEB-DL
● file=Cliff.Cottage.S05E23.NORDiC.ENG.1080p.WEB-DL
● file=Cliff.Cottage.S05E22.NORDiC.ENG.1080p.WEB-DL
● file=Cliff.Cottage.S05E21.NORDiC.ENG.1080p.WEB-DL
● file=Cliff.Cottage.S05E20.NORDiC.ENG.1080p.WEB-DL
● file=Cliff.Cottage.S05E16.NORDiC.ENG.1080p.WEB-DL
```

— one pipeline step (`subtitle_check`) walking one series. It is a log tail, not a summary, and it says nothing
about *what* was processed or what came of it. The mockup's version (*Caminandes 3 · NFO + artwork* · *Wing It! ·
no TMDB match → triage*) is what the card was meant to be.

**Subtitles · Bazarr** (157 + 273) — `Wanted: 9 392` · `Providers: 2/3 healthy` · *Latest: EN provider* · then
273's line: **Fit their video: 103 in sync · 73 out of sync · 82 not for their video · 96 can't tell** · *0 not
offered to viewers · Only report · 0/100 downloads today · Bazarr has not called yet* · and, when the mode is *Ask
me first*, a **Needs your OK (n)** list with *Do it* / *Leave it* per row. Absent when Bazarr is off.

**Quick actions** — *View items needing attention* · *Manage tracks* · *Re-pull artwork* · *Repair corrupt artwork*
· *Sync NFOs to Jellyfin* · *Jellyfin: rescan its library* · *View activity*, with a feedback line under them.

**Suggestions · Seerr** (274, FR-274-14, not on the shipped page yet) — *16 films waiting · 3 new since Tuesday*.

### A5. The same kind of thing, living on other pages

The Dashboard is not the only place that says "something could be fixed". A regrouped overview has to decide
which of these it summarises, links to, or leaves alone:

| Where | What | Example today |
|---|---|---|
| **Activity** | *Queues* card (213): per lane, queued counts | **3 025 segment jobs waiting**, 0 media, 0 subtitles; *last failure: Media item no longer exists* |
| Activity | *Ravilo requests are queuing behind background work* banner (182) | silent today (0 waiting on both gates) |
| Activity | *Outbound pacing* (183) — TMDB 13/s of a 20/s ceiling, MusicBrainz 0/1, AcoustID 0/3, refusals last minute | 0 refusals |
| Activity | *Playback quality* (R216), *AI · sent to Anthropic* (272), *Workers*, *Running now*, *Log* | — |
| **Settings → Libraries** | the advisor's per-library findings inside each mapping card; the *Memory budget* calculator (215) | same 20 findings as A2 |
| Settings → Download tools | Bazarr card + its advisor; every *Test connection* result | *Connected · v3.5.0 · Seerr hides requested titles…* (282) |
| **Library** | *Needs attention* chip, *Missing artwork* chip, the per-kind facets (Match · Cover · Check · Format · Needs) | the same 28 types, as filters |
| **A title's page** | eight banners: *Cover art muxed as video* · *No longer in Jellyfin* · *Unplayable in Ravilo (MKV)* · integrity (254/255) · *coverage* (subtitles) · *NFO drift* · *Jellyfin field lock detected* · *NFO not writable*; plus the metadata-language mismatch card (191) and *Fix now* | — |
| **The floating Triage dock** (app-shell) | steps through flagged items one by one; sidebar shows the count | *2 483 need attention* |
| **`/api/health`** | `job_queues`, both gates, pacing, memory, `mkv_health_swept_at`, music/audiobook health | — |

## B. What is wrong, in the owner's words and in the numbers

1. **Three vocabularies for one idea.** An advisor *finding* (summary · now · recommendation · trade-off · Apply),
   an attention *type* (label · sentence · count · filter) and a *card* (Bazarr's numbers) all mean "here is
   something you could fix", and they look nothing alike. A reader has to learn three grammars.
2. **Three fold rules.** The Jellyfin advisor shows one and hides 31. The Bazarr advisor shows all six. The
   attention list shows all 28 forever, including six zeros. Nothing says why.
3. **The red number is not one number.** `2 483` adds 1 277 untagged *tracks*, 756 *episodes* with segment
   guesses, 44 damaged *files*, 8 *artists* without a picture and 1 audiobook *narrator* that is information only.
   It cannot go down by any one act, so it never reads as progress. And `/api/stats` still says 1 277.
4. **Instances and titles compete.** `165 (3 titles)` — three series with no episode stills — is a small job
   shown as a large number; `539 (94 titles)` is the reverse.
5. **Severity is on two surfaces and not the others.** Findings carry critical/warning/info; attention types carry
   a hard-coded bad/warn/info that the backend does not know about; cards carry none.
6. **The page is ordered by history, not by work.** Findings first because 257 put them there; then a scan banner;
   then stats; then the list; Bazarr in the side column because 157 was the first external card. Music and
   audiobooks are "last: none of it stops a film from playing", which is a fine rule that no other row follows.
7. **Recently processed is a log line**, and today a single step's output six times.
8. **Everything that is not on the page is nowhere.** 3 025 queued segment jobs, a queue's last failure, the gate
   banner and the pacing card live on Activity; the Suggestions card is unbuilt; a title's eight banners are only
   visible on that title.

## C. The proposal — group by what it is about, then by how it is fixed

The owner's grouping, applied to everything in §A. Every existing type and finding lands in exactly one group,
and each group is ordered inside by severity, then by count.

| Group | What lands here today | Count today |
|---|---|---|
| **Films** | untagged tracks · wrong default · multiple default · missing poster · cover-as-video · zero audio · MKV structure · damaged · ends early · wrong length · duplicates · no longer in Jellyfin — *for films* | (split of the 2 483 by kind; the API does not split today — 274-style per-kind counts are new backend work) |
| **Series** | the same file issues *for series* + missing stills · mixed-language series · duplicate episode files · episode never matched in Jellyfin · low-confidence segments · no segments · 3 025 segment jobs waiting | |
| **Music** | shared album · folder disagrees · needs a match · no cover · no picture · re-encodes · (284: files without tags) | 74 |
| **Audiobooks** | missing part · two books in one folder · no cover · no narrator (info) | 3 (1 info) |
| **Subtitles** | Bazarr's advisor (4 + 2) · fit-their-video numbers · *Needs your OK* · wanted · providers · today's budget | 4 to change · 73 + 82 files to decide on |
| **Jellyfin** | the advisor's server-wide Jellyfin findings (hwaccel, anonymous media routes, keyframes, restart pending) · per-library findings (chapter images, trickplay, LUFS, internet providers, **NFO saver**) · *Jellyfin field lock* · NFO drift · NFO coverage 99 % | 2 critical · 18 warn · 4 info |
| **This server** | disk schedulers, read-ahead, max_sectors, swappiness · known proxies · memory budget · gates and pacing | 8 warn |
| **Services** | Seerr (282's *hides requested titles*, suggestions waiting) · Radarr/Sonarr (221's dead route, webhook) · qBittorrent (seeding guard unreachable) · AI (272's limit) · Bazarr connection | 0 today |

Inside every group, one row grammar for all three of today's vocabularies:

> **Label** — one plain sentence · `count` (titles by default; instances on hover or in the sentence) · a
> severity mark · **what fixing means**: *one click here* (Repair · Convert… · Apply in Bazarr · Re-check) ·
> *open the item* (Find match…, the Tracks tab, the segment editor) · *change a setting elsewhere* (Jellyfin's
> exact label and path) · *for information*.

That fourth field is the one the owner asked for: "a better overview of everything that we could fix" is a
question of *how*, and today only the advisor answers it. Three directions to draw on that grammar:

- **Direction 1 · Domain sections (lean).** The eight groups as sections on one page, each headed by its own
  count and severity mark, each folded to its top three rows with *+N more*, empty groups absent. The stat grid
  becomes the section headers. Severity orders rows inside a section; nothing is ordered across sections.
- **Direction 2 · Severity first, domain as chips.** One list, critical → warning → info, every row carrying a
  domain chip (*Series*, *Jellyfin*…), with the chips as filters at the top. Best when there are two criticals;
  worst when 1 277 warnings of one kind bury everything.
- **Direction 3 · Two queues: here and elsewhere.** Left: what jellystructure can fix or open in one click
  (attention types, repairs, Apply in Bazarr). Right: what you must change somewhere else (Jellyfin settings, the
  host, the files on disk). Domain as a secondary heading in both. Truest to "what could I fix right now".

The mockup should carry all three as marked options with the lean marked, as the music brief did (§H there).

## D. Rules to hold, whichever direction wins

1. **One fold rule everywhere**: a group shows its first N (lean 3) and *+N more*; the whole page never scrolls
   past the fold on a 1080p screen with everything folded. *Show all* on the page remembers itself.
2. **Zero is silence, not a row.** A type at zero does not render; a group with nothing does not render; the page
   with nothing renders one sentence (*Nothing needs you. Last scan 13:30, next 14:30.*). The `✓ 0` rows exist so an
   admin can reach an empty filter; that need moves to Library's facets, which already have it.
3. **Counts are titles.** `3 series` not `165`; the sentence carries the instances (*165 episodes across 3
   series*). The stat tile and the sidebar badge count titles too, and `/api/stats.issues` follows or goes.
4. **Severity comes from the server for every row**, the same three words (critical · warning · info) the
   advisor already uses; the hard-coded `ATTENTION_ROW_ORDER` map goes.
5. **Every row says what fixing means**, in one of the four phrasings above, and every row opens somewhere.
6. **Recently processed becomes *Since your last visit*** — what changed, by group: *2 albums matched · 41 subtitles
   checked (7 out of sync) · 1 damaged file replaced* — with the raw log staying on Activity.
7. **Music and audiobooks are groups, not a tail.** The "last because nothing stops a film" rule is replaced by
   the section order (Films · Series · Music · Audiobooks · Subtitles · Jellyfin · This server · Services), which is
   stable and learnable.
8. **The scan banner stays at the top** in every direction; it is the one thing that changes under the admin.

## E. States to draw

All clear · one critical (the NFO saver) · first run before any scan (no counts, one sentence) · Jellyfin
unreachable (the Jellyfin and This server groups replaced by one line) · Bazarr off (Subtitles group absent) ·
Seerr off (no Services row for it) · a scan running, paused for a TV, stopped with subtitles still finishing ·
the 1 277-in-one-type case (the fold must hold) · music library only (Films/Series absent) · everything folded vs
*Show all* · a group whose only rows are information · the Since-your-last-visit strip with nothing new.

## F. Round-1 questions (directions wanted, owner picks)

| # | Question | Lean |
|---|---|---|
| Q1 | Direction 1, 2 or 3 | **1**, with 3's *here / elsewhere* phrasing as the row's fourth field |
| Q2 | Counts: titles or instances on the badge | **Titles**; instances in the sentence |
| Q3 | Zero rows: hidden, or dimmed as today | **Hidden**; Library's facets keep the empty filters reachable |
| Q4 | Fold size per group | **3**, *+N more* |
| Q5 | Do the host findings (disks, swap, proxies) belong on the Dashboard at all, or only in Settings | **On the Dashboard as *This server***, folded, since two of the eight are what made playback stall in September |
| Q6 | Does the segment queue's 3 025 belong under Series, or stay on Activity | **Series**, as one row (*3 025 episodes waiting for intro detection*), since it is why 217 have none |
| Q7 | *Since your last visit* replaces *Recently processed*, or sits beside it | **Replaces**; the log is on Activity |
| Q8 | Quick actions: keep the seven chips, or fold each into the group it serves (*Repair corrupt artwork* under Films/Series, *Sync NFOs* under Jellyfin) | **Fold into groups**; keep *Scan* and *Activity* in the pagebar |
| Q9 | Per-kind split of the film/series issue types needs new counts from the backend (`/api/triage/count` does not split by kind) | **Yes** — presentation phase like 146/257, plus one count endpoint change |
| Q10 | Should the floating triage dock survive, now that each group opens a filtered Library | **Keep**, unchanged; it is the per-item path and the page is the per-type path (146's split) |

## G. Deliverables and numbering

- `design/app/index.html` redrawn on the winning direction, with the eight groups seeded from §A's real counts
  (fictional titles only), the states of §E, and the questions panel; `app-shell.js`'s attention queue re-seeded
  so the dock, the sidebar badge and the page agree.
- One admin phase, prospective **285** (284 is the tags work in
  `research-reports/music-tags-in-the-files-2026-09-28.md`; verify both on `main` before writing): presentation
  first (like 146 and 257), plus per-kind and per-group counts on `/api/triage/count`, server-side severity per
  type, `/api/stats.issues` reconciled, and a *since your last visit* endpoint. Nothing changes in what is
  detected.
- Out of scope: new detections, the Library page, the title pages' banners (they stay where the fix happens).
