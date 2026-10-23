# Processing runs as nine features, each with its own switch and interval

Task: DEB-1664

> Owner, 2026-10-17 (DEB-1664): *"I'm thinking we should try to do a full re-think of how all of this work
> (configuration and overview wise), we still want all the same functionality and types of scanning etc. But we
> also want it as simple to configure and maintain, but also as powerful as possible at the same time. […] I'm
> open for removing the whole pipeline schedule configurator, so everything that's available will just run
> automatically. And configurations about interval for specific things can still be configured, but not as part
> of the pipeline it self, but rather just it's own setting. I still want to be able to trigger a manual scan,
> and possibility to choose what it will include."*

## Scope

Admin and backend. No Ravilo client calls any route this spec changes, so the wire rule does not bind them. It
**replaces** the configurable scan pipeline (`[[scan.pipeline]]`, the Settings ▸ Libraries ▸ Scanning pipeline
card and its *Add step* palette) with nine fixed features. The engine underneath (`runPipeline`, the step
functions, the four job lanes, the per-title `item_step_run` record) stays.

The investigation, with every trigger, step, lane and per-title button as it was, is the
[investigation document on DEB-1664](/DEB/issues/DEB-1664#document-investigation). The pictures are round 9,
revision 1 (`51206b00`, `design/incoming/2026-10-22-processing-features/`, 34 PNGs on DEB-1675). This spec is
written from them; where a picture and this file disagree, this file wins and the picture is a defect.

### Owner decisions

On DEB-1664's first card (2026-10-17):
1. **Features, not steps.** The order lives in code. Each feature has its own on/off and interval, and
   everything that is on runs by itself. No nightly window.
2. **One manual Scan dialog, grouped by feature, with every part tickable for one run.**

On the second card (2026-10-23), from round 9's pictures:
3. **Settings ▸ Processing is its own tab.**
4. **The Scan dialog has two panes:** the features on the left, the chosen feature's parts on the right; a
   drill-in at phone width.
5. **Activity has three tabs: Now · Schedule · History.**
6. **No per-library switches.** One set of switches for every library.

On DEB-1076 (2026-10-18), carried in as FR-317-41: a control that cannot do anything new is **shown, disabled,
with its reason**, never hidden and never live-but-useless. This spec applies it everywhere (FR-11).

### Estate facts (Munin, DEB-1677, 2026-10-17)

- Nothing on the estate calls `/api/scan`, `/api/pipeline/*` or `/api/jobs` automatically. The only automated
  inbound processing traffic is the Bazarr post-processing hook (`POST /api/webhooks/bazarr`) and the Jellyfin
  Webhook plugin (`POST /api/webhooks/jellyfin`, films, episodes and series). **Both keep working unchanged.**
- Prod runs `scan_schedule = "30 */1 * * *"` with `recheck_unchanged = true` on `scan_files` (weekly / 6 months /
  yearly). Of 200 runs, 169 were webhook ingests, 31 scheduled, 0 manual.
- The demo stack documents `POST /api/scan?full=true` as a hand command; it keeps working (FR-9).

## The nine features

The order and the dependencies between steps are fixed in code (FR-1). These names are the product's names,
and they are the only names on any screen (FR-13). Step ids appear in this file so the engineer can map them;
they never appear in the admin.

| # | Feature | Parts (today's step ids) | Kind | Its settings |
|---|---|---|---|---|
| 1 | **Find new & changed** | none; it is `scan_files`, plus everything a library run does that is not a step today: the Jellyfin id comparison, the retry list, Sonarr's airing check, the remux-leftover sweep and MKV header health | inline; always on | how often to look (FR-3); re-check unchanged titles by release age (this year / 1–5 y / older) |
| 2 | **Metadata & artwork** | TMDB metadata `pull_tmdb` · Artwork `fetch_artwork` · IMDb ratings `sync_imdb_ratings` · NFO files `write_nfo` · Drift check `detect_drift` · Rescan in Radarr/Sonarr `rescan_arr`; plus `sync_jellyfin`, which is **not a part** (FR-1.3) | inline | which titles (missing only / all); overwrite other tools' NFO fields; download missing artwork; re-assert drift automatically; the *arr rescan switch stays on the *arr cards, shown here as a link |
| 3 | **Intro & credits** | one part, `detect_segments` | queues work | on/off; which titles (missing only / all); *Cross-episode intro fingerprinting*; *Trust TMDB stinger tags*; *Extra chapter-title keywords (credits)*, added to the built-in list |
| 4 | **File health** | Read files end to end `verify_files` · Track lengths `check_track_lengths` · Sound in step with the picture `check_av_sync` | queues work | each part on/off; the first two with re-checks by release age; sound in step once per file, and only while its measuring service is configured |
| 5 | **Subtitles** | Pre-warm `prewarm_subtitles` · Check fit `check_subtitles`; plus the Bazarr hook, poll and steering | queues work | each part on/off; *When a subtitle does not fit*: *Fix it* · *Ask me first* · *Only report* (moves here from the Bazarr card) |
| 6 | **Recommendations & suggestions** | Recommendations `build_recommendations` · Suggestions `build_suggestions` | inline, whole library | each part daily or weekly; suggestions only while Seerr is connected; the AI follow-ups stay on the AI tab |
| 7 | **Music** | Read the music library `scan_music` · Match on the music database `match_musicbrainz` · Songs held twice `compare_songs` · Covers & pictures `fetch_music_artwork` · Lyrics `fetch_lyrics` · Album/artist NFO files `write_music_nfo` · Write tags `write_tags` | inline, whole library | the existing provider switches; shown, and explained, when no music library is mapped |
| 8 | **Audiobooks** | one part, `scan_audiobooks` | inline, whole library | none; shown, and explained, when no audiobook library is mapped |
| 9 | **Make files play directly** | one part: the file-fix kinds (*Stereo for everything*, *Surround for lossless-only audio*, *Dolby Vision the TVs play*) and their one-minute tick | queues work | each kind on/off; apply automatically to new files (moves here from the Libraries tab) |

`wait` is removed. `notify` folds into Notifications ▸ *Scan finished* (FR-14).

## Requirements

### FR-1 — Nine features, fixed in code

1. The nine features and their parts are a fixed list in the backend, the single source for every surface:
   Settings, the Scan dialog, Activity, a title's Refresh ▾ and its Checks card. The admin renders that list from
   the server and never keeps a copy of it (FR-12).
2. **The order is a dependency graph in code, not a setting.** Only these orderings matter, and the engine keeps
   them:
   - Find new & changed runs first;
   - TMDB metadata before Artwork, NFO files and Recommendations;
   - NFO files before telling Jellyfin;
   - Pre-warm before Check fit;
   - Read the music library before Match on the music database, which comes before Covers & pictures, Lyrics,
     Album/artist NFO files and Write tags.

   Everything else is independent. The order the old configurator allowed is gone, including the ordering in
   which Rescan in Radarr/Sonarr ran before NFO files: it now always runs after them.
3. **Telling Jellyfin is not a part and cannot be switched off.** The constitution: *"After completing metadata
   or file modifications, the system calls Jellyfin's REST API to trigger a refresh — the user never has to do
   this manually."* `sync_jellyfin` runs whenever NFO files wrote anything, exactly as it does today for the
   titles whose NFO changed.
4. **A part that depends on a switch elsewhere** (Write tags needs tag writing on; Suggestions needs Seerr; Lyrics
   needs its provider; Sound in step needs its measuring service) is part of the list whatever that switch says,
   and carries a reason while it cannot run (FR-11). It is never silently dropped from the list, so *n of m*
   counts stay honest.

### FR-2 — The configuration, and how today's is carried over

1. A new `[processing]` section holds, per feature: `enabled`, its interval (FR-3), the on/off of each part,
   and the feature's options from the table above. Find new & changed has no `enabled` key.
2. Options that already live elsewhere **keep their keys and their meaning**, and `[processing]` does not
   duplicate them; the Processing tab reads and writes them where they are:
   - `[scan] defer_while_playing`; `behavior.overwrite_nfo`; `behavior.fetch_images`;
   - `musicbrainz.enabled`, `music.fetch_lyrics`, `music.write_tags`; `[avsync] enabled`;
   - `[subtitle_check] action` (`fix` / `ask` / `report`);
   - the file-fix kinds' *enabled* and *apply automatically to new files*, which are database rows
     (`file_fix_setting`, `POST /api/file-fix/{kind}/setting`), not TOML.
3. **Carry-over, once, at load.** When `[processing]` is absent, it is derived from the saved `[[scan.pipeline]]`
   and `scan_schedule`:
   - a feature is on when any of its parts is an enabled step; a part is on when its step is enabled;
   - a part's per-step options (scope, overwrite, re-check by release age and the three intervals) carry over
     unchanged;
   - Intro & credits' options are carried from the `detect_segments` step even when it is disabled, because
     the code reads them from there today whether the step is on or not (`detect_fingerprint`,
     `trust_stinger_tags`, `chapter_keywords`);
   - recommendations' and suggestions' `rebuild_every`, drift's `auto_reassert`, and the release-age tiers of
     `scan_files`, `verify_files` and `check_track_lengths` carry over as they are;
   - root `scan_schedule` becomes Find new & changed's interval (FR-3.2); when it is blank and the legacy
     `behavior.scan_interval_hours` is above 0, that becomes *every N hours*;
   - the seed markers (`*_seeded`) are not carried; there is nothing left to seed;
   - **with no saved pipeline, the fresh-install defaults of FR-2.6 apply**, not the engine's built-in step list
     (which leaves NFO files and telling Jellyfin out) and not the admin's old three-step default. That closes
     the investigation's defect 4. Prod has a saved pipeline, so prod is not affected by this branch.

   The derived section is written back on the next save. A carry-over that meets a step id it does not know
   logs it once and drops it.
4. **Rollback safety, one release.** For one release after this ships, every save also writes a
   `[[scan.pipeline]]` derived from `[processing]`, so a rolled-back image still finds a pipeline that does what
   the features said. A release later the block is no longer written. This is the wire rule's "add, deprecate,
   remove a release later", applied to the config file.
5. `PUT /api/config` keeps replacing the whole config. Phase 274's seed-marker bug is the reason the carry-over
   runs on load, on the server, and never in the admin. **Every `[processing]` key round-trips through the
   admin's config model.** Today `auto_reassert` is missing from it, so every Settings save turns *re-assert
   drift automatically* off; that ends here.
6. **Fresh-install defaults:** every feature and part on, except Intro & credits (off, as today) and the parts
   whose own switch is off by default (FR-1.4). Find new & changed every hour; Metadata & artwork re-checks
   unchanged titles weekly / monthly / every 6 months by release age; File health keeps FR-261-6's
   (5 y / 5 y / 5 y to read files, 1 y / 2 y / 5 y for track lengths, sound in step once per file); Check fit
   weekly; Recommendations & suggestions weekly; Music and Audiobooks daily.

### FR-3 — When things run

1. **A new or changed title gets every enabled feature at once.** That is the single-title run today's webhook
   ingest already does (FR-145-7 decides what it tells Jellyfin), now driven by the feature switches instead of
   the pipeline list. A title found new or changed by a scheduled or manual look gets the same.
2. **Find new & changed runs on its own interval.** The picker offers *every N hours*, *daily at*, *weekly on*,
   and *Custom* (the cron of phase 166, still validated before it can be saved). A saved cron that matches a
   preset shows as that preset; anything else shows as Custom with the expression. A fire is still dropped when
   a run is already going.
3. **Every other feature is re-checked when its own interval says so**, independently of Find new & changed:
   - Metadata & artwork: unchanged titles by release age (the three intervals `scan_files` carries today);
   - Intro & credits, File health, Subtitles: each part's existing *due* rule (FR-261-7) decides what to queue;
     the pass that queues runs with every Find new & changed;
   - Recommendations & suggestions: daily or weekly;
   - Music and Audiobooks: their own interval;
   - Make files play directly: its one-minute tick.

   A feature that is off neither runs nor queues anything, and its waiting work is left alone (Empty is the
   operator's choice, FR-8).
4. **Waiting while someone is watching is unchanged.** One household switch (FR-262-1), read live. Scans wait
   whoever started them (FR-213-6). Work queued from a title's own Refresh ▾ or ↻ never waits. *Run anyway* on
   Activity stays the override (FR-178-4).

### FR-4 — Settings ▸ Processing

1. A tab of its own, right after Libraries in the Settings tab list (today: Connections, Libraries, Metadata,
   Downloads, Notifications, AI, Advanced). The tab row in the pictures is stand-in (`set-desktop`).
2. At the top, one card for *Wait while someone is watching*: the `defer_while_playing` switch, moved here from
   where it is today.
   While it holds, the card says which TV is playing.
3. Then the nine features, one row each: the switch, the name, the interval in words, the last-ran line and
   one state chip. A row expands to its parts and options (`set-interval`).
   - Find new & changed's switch is shown on and disabled, with its reason (FR-11).
   - A feature that cannot run because a library is not mapped is shown greyed with one sentence saying what
     to do (`set-firstrun`).
   - "Last ran" appears once per row, in the line, not repeated in the chip.
4. Under the list, one line: there is no order to arrange.
5. **Libraries ▸ Scanning loses the pipeline card, the *Add step* palette and the cron card**, and the Libraries
   tab loses the *Make files play directly* card, whose switches move into feature 9's row. The Bazarr card
   loses *When a subtitle does not fit*, which moves into Subtitles. The library cards' *Scan* button stays and
   opens the dialog scoped to that library (FR-5.6).

### FR-5 — The Scan dialog

1. **Every door opens it:** ▶ *Scan library* and its full face on Dashboard and Library; the command palette's
   *Start full scan*; *Scan now* in the Music and Audiobooks empty states; a library card's *Scan*; and
   Dashboard's *Start new scan* banner. The last two skip the dialog today, against FR-265-2/3; that ends.
2. **Two panes** (`dlg-desktop`). On the left, the nine features, each with its own tick and, on a second line,
   *n of m* or *one tick*, plus *queues work* when every part of it queues work. On the right, the chosen
   feature's parts, each with its own tick.
3. **Find new & changed** is ticked, disabled and labelled *always*.
4. **At the top, one switch: *Re-check everything, not only what's due.*** Off is the normal scan. On lifts the
   due rule for every ticked part for this run only. It replaces the *(full rescan)* split-button face, which
   now opens the dialog with the switch on (`dlg-rescan`).
5. **A feature or part that cannot run is shown disabled with its reason**, in both panes when it is selected
   (`dlg-off`, `dlg-music`): *Off in Settings*, with a link to its row; or the dependent switch's reason, as
   Write tags while tag writing is off. It cannot be ticked for one run either.
6. **Scoped to one library** when opened from that library's card. The title says so, and the features that do
   not apply to that library are listed and explained, not hidden (`dlg-scoped`).
7. **What you untick is remembered, once, for every page** (FR-265-7), per part, and always shown, never
   applied silently.
8. A note under the panes says what *queues work* means: the part is not finished when the scan ends; the
   reading happens afterwards and shows on Activity.
9. **At 412 px it is a drill-in:** the feature list, with a chevron on every feature that has parts; tap one for
   its parts and a back control (`dlg-phone`, `dlg-phone-parts`, `dlg-phone-partly`).
10. *Start scan* starts it and closes the dialog. The toast names what was skipped (*Scan started · skipped
    Intro & credits*); a finished scan's toast says what it queued and links Activity (`toast`). *Cancel* starts
    nothing, and the dialog does not say so in words.

### FR-6 — What a manual scan does

1. It runs Find new & changed over the chosen libraries (all, or the one it was scoped to), then every ticked
   part, in the FR-1.2 order, over the titles that are due, or over every title when *Re-check everything* is on.
2. Ticked queue-only parts queue what is due; the scan does not wait for them.
3. Whole-library parts (recommendations, suggestions, music, audiobooks) run when ticked, whatever their own
   interval says, as a manual run does today (FR-272-7).
4. The run carries its trigger, scope and the parts it skipped, as today's run descriptor does (FR-135-4), and
   History shows them.

### FR-7 — Activity ▸ Now

1. **Three tabs: Now · Schedule · History.** *Now* opens by default (`act-now`).
2. **The run card**: what started it, how long ago, whether it is running, and one line per feature it ran, each
   saying what it did or what it **queued**. *Cancel run* cancels the run; queued work stays queued.
3. **The *Queued work* card lists only the four features that queue work:** Intro & credits, File health,
   Subtitles and Make files play directly. Each line says what is being read now, how many wait, and whether the
   feature is holding for a TV, with *Run now* and *Empty*. It never says a feature has "a lane": **the backend
   keeps its four lanes** (media edits; file checks and intro/credits; subtitle pre-warm; sound in step), the
   one-worker-per-lane rule and the shared pool (FR-213-1) are unchanged, and nothing here changes concurrency or
   disk load. Activity only labels work by feature.
4. **Offline** (`act-offline`): when the socket drops, every value says it is as old as the last push and every
   control is disabled with that reason.
5. The Pacing and AI cards stay as they are.

### FR-8 — Activity ▸ Schedule and History

1. **Schedule** is one row per feature: every · last ran · next · waiting · *Run now* · *Empty*
   (`act-sched`). It is the only place the inline features (Metadata & artwork, Recommendations & suggestions,
   Music, Audiobooks) have *Run now*.
2. **Run now** on a feature runs that feature now, over what is due. On a feature that is off it is disabled
   with *Off in Settings — nothing would run* (FR-11).
3. **Empty** on a feature removes only that feature's waiting jobs, and never what runs (FR-260-1…5): one
   confirmation, no undo, one History entry. Today emptying is by lane (`POST /api/jobs/empty {queues}`), and the
   file-checks lane carries intro detection, waveforms, read-files, track lengths and check-fit together, so
   emptying becomes **by job type**: File health's types, Intro & credits' types (with waveforms), and so on.
   Emptying one feature leaves the others' jobs in place.
4. **History** lists past runs with their trigger, scope, skipped parts and outcome, and opens the log
   (`act-hist`).

### FR-9 — Routes

1. The admin's reads and writes for this spec go through one new route family, `/api/processing/…`: the
   feature list with each feature's state and schedule; save; run (a scan, with libraries, ticked parts and the
   re-check switch); run one feature now; empty one feature; refresh one title for one feature.
2. **The old routes keep working for one release**, mapped onto the features: `POST /api/scan` (with `?full=`,
   `?library=` and the `skipSteps` body), `POST /api/scan/resume`, `POST /api/pipeline/run` and
   `GET /api/pipeline/plan`. Old step ids in `skipSteps` map to their parts; an unknown id is ignored. They are
   deprecated, and removed a release later. Nothing on the estate calls them (DEB-1677); the demo stack's hand
   command keeps working until then.
3. The two webhooks (`/api/webhooks/jellyfin`, `/api/webhooks/bazarr`) are unchanged, and so are
   `POST /api/scan/cancel`, `POST /api/scan/stop-step`, `POST /api/pipeline/{jobId}/run-anyway` (*Run anyway*),
   `GET /api/scan/status`, and the per-job `POST /api/jobs/{id}/cancel` and `/retry`.

### FR-10 — A title: Refresh ▾ and the Checks card

1. **Refresh ▾** in the title's header lists the features that apply to that title (`title-series-menu`):
   Find new & changed, Metadata & artwork, Intro & credits (series), File health, Subtitles and Make files play
   directly. Recommendations, Music and Audiobooks are not there; one line under the menu says why. A series
   chooses the whole series or one season.
2. **Each item runs the same code as the automatic run**: a single-title run of that feature's parts. The
   per-title buttons that today have their own implementations (Re-pull, Save → disk, Sync Jellyfin, IMDb
   re-sync, trailer re-fetch, season sync and the Checks card's ↻ for Scan files and TMDB) call the step
   functions instead, so their side effects are the automatic run's. Where today's side effects differ, the
   automatic run's win, and *Save → disk* stops refreshing Jellyfin (its menu has always said it does not).
3. A Refresh ▾ item covering several parts, of which some can do nothing new (Sound in step on a file already
   measured), stays live, runs the others, and says what it skips (*Runs two of its three checks*).
4. **The Checks card lists the same features**, one row each, with sub-rows for File health's three parts: a
   chip, *last ran*, *next due* and ↻ (`title-film`, `title-series`). It lists only what applies to the title
   (FR-303).
5. **A ↻ updates its own row**, from the server's push, when the run it started finishes. The investigation found
   five rows that never moved after their own ↻ (TMDB, Artwork, NFO, Jellyfin, IMDb): their per-title runs now go
   through the step functions, which write `item_step_run` as the automatic run does.
6. Sound in step's *can't tell* row and its disabled ↻ with the two fixed sentences are FR-317-41, unchanged.
7. The header badge *plays directly* describes the file, not a feature, and stays.

### FR-11 — A control that cannot act is shown, disabled, with its reason

Everywhere in this spec, a control that can do nothing new is visible, disabled, and gives its reason in one
sentence on hover, focus **and** tap. It is never hidden and never live but useless. The cases this spec draws:

- Find new & changed's switch (always on);
- *Run now* on a feature that is off;
- a dialog feature or part that is off in Settings, or whose dependent switch is off (FR-1.4);
- every control on Activity while the socket is down;
- a Checks-card ↻ with nothing new to do (FR-317-41).

### FR-12 — Server-pushed state only

1. Every value on these four surfaces comes from the server: names, the *n of m* counts, states, *last ran*,
   *next*, *waiting*, and every reason sentence. The admin composes none of them and draws no summary that
   would be arithmetic over a list.
2. **Activity stops polling.** Today it polls `GET /api/jobs` every 2 s while visible and every 10 s otherwise,
   and no admin code reads the `media_job` event. It renders pushed events instead, as the constitution requires
   (*"push status updates from backend to frontend; no HTTP polling"*). Per-file check rows are never broadcast
   today, and broadcasting thousands of them is not the fix: the server pushes **one per-feature summary** (what
   is running, how many wait, holding or not) whenever it changes, at most once a second per feature. A first
   `GET` seeds the page, and a reconnect re-seeds it.

### FR-13 — Names and copy

1. The product's names for features and parts, as in the table, everywhere. No step id, phase number, FR id or
   route on any screen.
2. **Sound in step with the picture**, shortened to *sound in step* only where a column forces it. The service
   that measures it is *the measuring service*, never *the sidecar*. The metadata *stale* reason says *the NFO
   files*.
3. No screen says a feature has its own lane, or that a cancelled scan "left nothing half-written".
4. The Settings card's line under *Wait while someone is watching* says what is true (FR-3.4): *Scans wait while
   a TV is playing. A title you refresh yourself never waits.* Round 9's *"Work that you start yourself never
   waits"* is wrong for a manual scan and is not used.
5. The admin stays English only.

### FR-14 — What goes

1. The pipeline configurator: the card, the palette, `[[scan.pipeline]]` as something a person edits, and its
   TOML preview.
2. The `wait` step, with no replacement.
3. The `notify` step and its `pipeline_complete` webhook. Notifications ▸ *Scan finished*
   (`behavior.notify_on_scan_done`, the `scan_complete` webhook) is the one place for it; today a run with both
   sends two webhooks. A saved pipeline whose `notify` was on turns *Scan finished* on during the carry-over
   (FR-2.3).
4. The old dialog's copy: phase numbers, `POST /Items/{id}/Refresh`, the raw `write_tags`, and *"by far the
   slowest step (hours)"* on intro detection, untrue since August.
5. The Jobs & workers view, replaced by Activity's three tabs.

## Acceptance

1. With a prod-shaped `config.toml` (every step enabled, `recheck_unchanged` on, hourly cron), the carry-over
   yields nine features all on, the same per-part options and an hourly Find new & changed, and an hourly run
   does the same work as before. A unit test over the carry-over, plus one with no saved pipeline (the built-in
   default, not three steps).
2. A rolled-back image started on a config the new one saved finds a working pipeline (FR-2.4).
3. The two webhooks behave exactly as before; a webhook title gets every enabled feature at once.
4. The Scan dialog, opened from every door in FR-5.1, unticking one part and starting, runs everything else and
   names the skip in the toast and in History.
5. A title's Refresh ▾ ▸ Metadata & artwork produces the same NFO, artwork and Jellyfin refresh as the automatic
   run for that title, and its Checks-card row moves.
6. *Empty* on File health leaves Intro & credits' waiting jobs in place.
7. Activity draws from pushed events with no periodic `GET` in the network log.
8. Every surface at 412 px holds, as in the pictures, in both themes.

## Not in this spec

- Per-library feature switches (owner, 2026-10-23: one set for every library).
- A nightly heavy-work window (owner, 2026-10-17).
- Any change to what a check measures, or to the four lanes' concurrency.
- The AI tab, the Pacing card and Notifications beyond FR-14.3.
- Ravilo clients.

## Supersedes and amends

Each of these gets a dated amendment, 2026-10-23, pointing here: 135 (FR-135-3's pipeline chips become the run
card), 154 (FR-PIPE1-1…8: the per-step dialog becomes the two-pane dialog), 164 (`detect_segments` is a feature,
not a configured step), 166 (the cron moves to Find new & changed, FR-3.2), 175 (the engine takes features, not a
step list), 213 (FR-213-1's lanes stay, labelled by feature), 261 (FR-261-4's steps become File health's parts;
the Checks card is FR-10), 262 (FR-262-3a: the switch moves to Settings ▸ Processing), 265 (FR-265-1…8: the dialog
is FR-5) and 303 (the Checks card lists features).

## Code anchors (`main` at `51206b00`)

- **Config:** root `scan_schedule` (`config/AppConfig.kt:34`); `ScanConfig` with `[[scan.pipeline]]`,
  `defer_while_playing` and six seed markers (`:283-311`); `PipelineStep` (`:477-504`); the seeds (`:342-474`),
  run once at load (`config/ConfigStore.kt:42-133`); the admin's copy of `PipelineStep`, which lacks
  `auto_reassert` (`api/ConfigApi.kt:104-128`); `PUT /api/config` keeping only the seed markers
  (`ConfigRoutes.kt:217`).
- **Engine:** `effectivePipeline` / `rawPipeline` and the built-in list (`media/PipelineEngine.kt:162-187`);
  `planOrder` (`PipelinePlan.kt:14-18`); each step's options as the engine reads them (`PipelineEngine.kt`
  `:240-827`); `detect_segments`' options read from the raw pipeline (`media/MediaJobQueue.kt:1441-1443`).
- **Routes:** `POST /api/scan` (`MediaRoutes.kt:2044`), `GET /api/pipeline/plan` (`:2071`),
  `POST /api/pipeline/run` (`:2078`), `run-anyway` (`:2109`), `POST /api/scan/resume` (`:2115`),
  `GET /api/scan/status` (`:2137`); cancel and stop-step (`ScanStopRoutes.kt:29,45`); `GET /api/jobs`,
  `/jobs/groups/{type}`, `POST /jobs/empty` (by lane), cancel and retry (`JobsRoutes.kt:54-98`).
- **Queue:** lanes and the pool (`MediaJobQueue.kt:1682-1686`); job types per lane (`:354-375`, `:851-862`,
  `FileCheckSchedule.kt:30-60`); `emptyQueues(lanes, by)` (`MediaJobQueue.kt:726`); per-file rows not
  broadcast (`:819`); `item_step_run` written only for intro detection and pre-warm among queue jobs
  (`:822-834`).
- **Per title:** `item_step_run` (`ItemStepRun.sq:5-12`); `GET /api/media/{id}/checks` (`TrackRoutes.kt:829`),
  `TitleChecks.forItem` (`TitleChecks.kt:70-124`, falling back to `effectivePipeline` only when the pipeline is
  empty) and `STEP_LABELS` (`:427-434`); `nextDueMs` (`FreshnessFilter.kt:70`), `nextCadenceDueMs`
  (`FileCheckSchedule.kt:64-77`).
- **Admin:** the Scanning card and pipeline builder (`ui/Settings.kt:353-442`, `:1123-1160`, `:2918-3521`); the
  tabs (`:71-77`, `SETTINGS_TABS` `:872`); *When a subtitle does not fit* on the Bazarr card (`:605-614`);
  *Scan finished* (`:690-691`); the dialog (`ui/PipelineRunDialog.kt`); Activity's jobs view and its polling
  (`ui/Activity.kt:158-353`, `:637-645`, `:787-875`, `:1057-1242`); the Checks card (`ui/MediaDetail.kt:3268-3368`);
  *Make files play directly* (`ui/SettingsFileFix.kt`).
- **Pictures:** `design/incoming/2026-10-22-processing-features/`, data in `pf-data.js`, the argument in
  `Processing Features - Directions.html`, the report in `REPORT.md`.

## Build order

One engineer, one piece at a time, each a task of its own, in this order. The CSS piece is Rannvá's.

1. **Backend: the feature model and the carry-over.** FR-1, FR-2, FR-3; `GET`/save of `/api/processing`; the
   engine takes the feature switches; the old routes mapped (FR-9.2). No admin change; the old pipeline card
   still reads a pipeline the server derives.
2. **Rannvá: promote round 9's CSS** into the production stylesheets, hand-edited, both themes.
3. **Admin: Settings ▸ Processing** (FR-4), removing the pipeline card (FR-14.1).
4. **Backend + admin: the Scan dialog and its run** (FR-5, FR-6, the run route of FR-9.1).
5. **Backend + admin: Activity** (FR-7, FR-8, FR-12.2), with per-feature *Empty* and *Run now*.
6. **Backend + admin: a title's Refresh ▾ and the Checks card** (FR-10), rewiring the per-title buttons.

Each piece files its own QA row when built, with a written plan and no named seat.
