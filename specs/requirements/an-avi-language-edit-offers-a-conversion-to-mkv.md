# An AVI language edit offers a conversion to MKV

Task: DEB-2051

> Owner, 2026-10-20 (DEB-2051): a film's *File path* in the admin shows a garbled name, and *"when trying to add a
> language tag to the audio track … Running this fails."* On 2026-10-21 he picked **"Offer explicit MKV migration"**
> for an AVI that cannot keep a per-stream language, and on 2026-10-22 he answered the four placement questions
> (D1–D5 below).

## Scope

Backend (`TrackRoutes`, `MediaJobQueue`, `WorkFiles`, the 316 mover's ports) and the admin's media detail page
(`MediaDetail.kt`, `TrackEditor.kt`), **films only**. Design: the round quarantined at `596ef867`
(`design/incoming/2026-10-22-avi-mkv-migration/`, DEB-2096), direction **A**, promoted by Rannvá with the changes
listed under *Design*. Investigation: DEB-2051's `investigation` document. Estate facts: Munin, DEB-2075.
Nothing here changes Ravilo or the wire that installed clients read.

## What happens today

**The language edit cannot work on an AVI, and it costs a whole-file rewrite to find that out.** `POST
/api/media/{id}/tracks/language` (`TrackRoutes.kt`) sends any non-MKV file through `FfmpegRunner.setLanguage`: a
stream-copy remux into `<dir>/.jellystructure/remux_<name>`, then `mv` over the original. AVI has no field for a
per-stream language, so FFmpeg exits 0 and writes no tag (reproduced on a synthetic one-second AVI). The post-write
probe catches it and answers **500** with *"the language tag did not persist …"*. The admin reads only the `error`
string and shows *"Some changes failed — see details"*. The track stays *Unknown* and stays in Triage, which is
correct, but the operator is offered a command that can never succeed and given no way forward.

**The garbled path is the file's real name.** Munin's read-only check (DEB-2075) found the double-encoded characters in
the on-disk directory entry itself. Jellystructure's stored `MediaItem.path` and Jellyfin's own database both match it,
so the admin is showing the truth. No product code garbled it, and this spec does not rename it (D3).

## Decisions

| | Question | Answer (owner, 2026-10-22) |
|---|---|---|
| D1 + D5 | Where the conversion lives | **Direction A.** A file-level notice above the Tracks card, reached **only** from a failed language edit. No standing "Convert to MKV…" on every AVI (C) and no drawer in the track row (B). |
| D2 | Where the original goes after a verified conversion | **Into the hidden `.jellystructure/` folder beside it, kept until the owner removes it.** Viewers see only the MKV, and the original is one rename away. |
| D3 | The new file's name | **Fixed: the same name with only the extension changed.** Shown, not editable. Nothing is renamed. |
| D4 | When there is no estimate | **"Estimate unavailable" where the estimate would be.** The row is not hidden. |

Choosing the product direction did not authorize converting any particular production file. A real library file is
converted only when the owner presses *Start migration* himself, or in a QA run he has approved.

## Requirements

**FR-1. A language edit on an AVI is refused before anything is written.** When the file's container is AVI (its
stored probe, falling back to the `.avi` extension), the movie and episode language routes write nothing, start no
remux and create no work file. They answer **422** with:

```json
{"error": "This AVI can't store an audio language, so it was not changed.",
 "reason": "container_cannot_store_language", "container": "avi"}
```

`error` stays the human sentence old admin bundles already show. `reason` and `container` are new fields. Every other
container keeps today's path, including the post-write probe and its 500. The refusal is recorded in the title's
history like other refused edits. The track stays *Unknown* and stays in Triage.

**FR-2. The row says why, and points at the file notice.** After a 422 with that `reason`, the track row reads
*"Not kept — this AVI can't store a language."* and *"Requested: {lang}."*, with a *"Why, and what you can do"* link
that opens the file notice (FR-3). For an episode, the row shows the same sentence **without** the link: the
conversion is films-only (see *Non-goals*).

**FR-3. The file notice explains the limitation and offers the conversion.** It sits above the Tracks card, scoped
to the whole file (*"File · {file}"*), and appears only when the server reports either a FR-1 refusal for this file in
this view or a conversion job for it (FR-8). It carries the *AVI limitation* badge, the title *"This AVI can't keep an
audio language"*, the body naming the track and the language, *"Video and audio are copied without re-encoding"*,
and two actions: **Review the conversion to MKV** and **Keep as AVI**. *Keep as AVI* closes the notice and leaves the
track *Unknown* in Triage. Several untagged tracks share one notice; one conversion carries every requested language.

**FR-4. The preview is computed by the server.** `GET /api/media/{id}/container-conversion` (admin session, else
`401`) returns what the preview shows, read-only:

- Container `AVI → MKV`, extension `.avi → .mkv`, and each stream with its codec, marked *copied untouched*.
- The language requested for each track.
- **Estimate:** temporary space is the source's size, and time is that size divided by the median throughput of the
  last three finished remux-class jobs on the media lane. When either part cannot be worked out, the row reads
  **"Estimate unavailable"** with *"Time and temporary space can't be worked out for this file."* (D4).
- Where temporary files go: *"a hidden folder beside the source, on the same disk."*
- **Old file:** *"{file} moves into a hidden folder beside the new file and is kept until you remove it."* (D2.)
- The safety verdict (FR-5).

**FR-5. A conversion is blocked when it is unsafe or unknowable, and the block says which.** The preview and *Start
migration* both ask, and *Start* asks again on the server, never trusting the preview. In this order:

1. **Path mismatch:** the stored path does not exist on disk, or the item's current Jellyfin path (mapped to the
   backend's view) differs from it. Shows the *Path mismatch* notice, which is visually distinct from the AVI limitation.
2. **Seeding:** `SeedingGuard.check(path, config, inPlace = false)` answers `Blocked`, so a torrent seeds the library
   path itself. Shows *blocked-seeding* with phase 315's sentence and *Open the Seeding tab*. `inPlace = false` is
   correct here: the original keeps its inode (FR-7), so a hard link elsewhere is unaffected. That is the lean in
   `SeedingGuard`'s own documentation.
3. **Unknown:** the guard answers `Unreachable`. Shows *blocked-unknown*, *"So this file is treated as shared."*,
   and *Check again*. Unknown means no.
4. **Name taken:** `<dir>/<base>.mkv` or `<dir>/.jellystructure/<base>.avi` already exists. The blocked notice says
   *"A file named {file} is already there, so nothing was converted."*
5. **No room:** free space on that filesystem is less than the source's size plus the media lane's existing preflight
   margin. The blocked notice gives the space needed and the space free.

**FR-6. Confirming starts one job, and only the press starts it.** The confirmation dialog shows *"Convert {title} to
MKV?"*, the source path, the destination path with its file name **fixed and not editable** (D3), *"Stays in place
until the new file is verified"*, and the actions **Start migration** and **Close**. `POST
/api/media/{id}/container-conversion` re-runs FR-5 and enqueues one `container_convert` job on the media lane with
`deferWhilePlaying = true`. It answers `202` with the job id, or the FR-5 refusal as `409` with `reason`. Saving a
language never starts a conversion as a side effect, and a second POST while a job for the file is queued or
running returns that job.

**FR-7. The job has three steps, and a zero exit code is not success.** It holds `MediaFileLock` on the source path
for the whole job.

1. **Copy streams into MKV:** `ffmpeg -map 0 -c copy` with each requested `language=` into
   `WorkFiles.pathFor(<dir>/<base>.mkv, Kind.CONVERT)` (a new kind, prefix `convert_`). Missing timestamps are
   generated, because some AVI codecs carry none. Owner and mode are copied from the source.
2. **Re-check the new file:** probe it. The stream count, order and codecs match the source, the video stream's
   packet count matches, the duration is within one second, and **every requested track carries its requested
   language**. A clean exit that fails any of these is the *failed-unverified* outcome. The work file is removed and
   nothing is put in place.
3. **Put the new file in place:** re-run FR-5. Then rename the work file to `<dir>/<base>.mkv`, then rename the
   source to `<dir>/.jellystructure/<base>.avi`. That path has no work prefix, so 311's sweep never removes it
   (FR-311-3: *"never touches anything else in the folder"*). The new file goes in first so a scan never sees
   neither file. If the second rename fails, the first is undone and the job fails with the original untouched.

Any failure in any step leaves the original exactly where and as it was, and removes the work file. A cancel or a
restart counts as a failure at the step it reached.

**FR-8. A verified conversion moves the title's identity with it.** After step 3, using the 316 mover's ports
(`LooseFilmMover`):

- Update `MediaItem.path` to the `.mkv`.
- Tell Jellyfin: the new path `Created`, the old one `Deleted`.
- Find the item at the new path, restore its watch data, and remap the stored `jellyfinId` if Jellyfin gave it a new
  one.
- Send Radarr `RescanMovie`, then read the movie's file link. When the link names the `.mkv`, the job records it.
  When it is `0` or still names the `.avi`, the job records that in the title's history and the verified state says
  *"Radarr hasn't taken the new file — re-link it in Radarr."* Radarr has no API to set the link (FR-314-12, as built),
  so the repair is a person. Rows are matched on the file name, never the full path.
- Re-assert the folder's artwork and NFO at once (FR-314-13's mechanism), so the folder is not bare until the next
  hourly pass.

**FR-9. Every state is server-pushed, and the admin derives none.** The media detail payload carries the file's
latest conversion (`state`, `step`, `reason`, and the job id), and job progress arrives on the push that Activity ▸
Jobs already uses. The admin draws direction A's seventeen states from that payload alone: *detail-default,
detail-loading, limitation, preview, estimate-unknown, confirm, focus, blocked-seeding, blocked-unknown,
blocked-path-mismatch, running, verified, failed-tool, failed-unverified, offline, narrow, longest*. Specifically:

- **running** shows *"Step {n} of 3"*, *"The original stays untouched until the re-check passes."* and *"You can
  leave this page …"*. The track row is locked (*"Locked while the conversion runs"*).
- **verified** (*"Converted to MKV — language confirmed"*) lists *Now on disk*: the new file *in place* and the
  original *kept in a hidden folder* (D2). It also says *"Other devices still see the old file until Jellyfin has
  indexed the new one."* until FR-8 finds the item at the new path.
- **failed-tool** (*"The conversion to MKV didn't finish"*) and **failed-unverified** (*"Migration failed — no
  language tag after the re-check"*) are both failures, with *Try again* and *Show job log*. Neither reads as a
  success.
- **offline** says the job runs on the server regardless and shows the last known step.

No state prints a command. The *Command preview* of the track editor is not shown for an AVI.

**FR-10. Strings in en, da and fo.** Every string in the promoted `STR` object (`design/app/container-convert.js`,
see *Design*) goes into the admin's i18n files. A key that already ships (language names, *Cancel*, the
seeding sentence, *Activity ▸ Jobs*) keeps its shipped string. The Faroese and Danish need a native read before the
build is closed (Acceptance 6).

**FR-11. Tests.** Backend, `linuxX64Test`:

- FR-1's refusal writes nothing for an `.avi` and leaves MKV and MP4 on today's path.
- FR-5's five blocks, each in order, with `Unreachable` refused.
- FR-7 on real temp files, using a generated AVI:
  - a verified conversion leaves the `.mkv` with the tag and the original at the hidden path, byte-identical with the
    same inode;
  - a forced no-tag outcome is *failed-unverified* with nothing in place;
  - a failed second rename rolls the first back.
- `WorkFiles.libraryPathOf` reads a `convert_` path back to the `.mkv`.
- 311's sweep rule leaves a 25-hour-old `<dir>/.jellystructure/<base>.avi` in place.

Admin: the state mapping from payload to notice.

## Design

Direction A of the round at `design/incoming/2026-10-22-avi-mkv-migration/` is promoted by Rannvá before the admin is
built, with these changes from the drawn lean:

- **D2:** every *"stays beside the new file"* / *"original · kept beside it"* becomes *"kept in a hidden folder beside
  it, until you remove it"*. The dashed *Tentative* badges go, because the answer is decided.
- **D3:** the confirmation's file name is shown as text, not an input, and *fnameHint* is dropped.
- **FR-5.4 and FR-5.5** reuse the *blocked* notice with their own sentences. No new frame is needed.
- The verified state gains the FR-8 Radarr line, as the same kind of secondary line as *Waiting for Jellyfin to
  index*.
- The round's bare `.btn:focus-visible` is scoped to the card.
- The directions doc's strings table is filled from `STR` (key / en / da / fo).

Directions B and C are not built.

**As promoted (2026-10-22, DEB-2137, `0df6354c`).** This is what the admin is built against:

- **The classes live in `design/app/detail.css`**, the media detail page's own stylesheet, not in a
  `container-convert.css` of their own. That file is already served and already linked by the wasmJs app, so the
  promotion needed no `syncDesignAssets` row, no `index.html` `<link>` and **no `SCOPED_FILES` row** (that fence
  covers a new page-scoped stylesheet). The focus ring is written only under `.cv-card` and `.cv-dlg`.
- **The strings are the `STR` object in `design/app/container-convert.js`** — 97 `migrate.*` keys in en, da and fo.
  That file is a mockup harness and is not served; FR-10 copies from it. The key/en/da/fo table is in the round's
  `REPORT.md`.
- The confirmation's label reads **Destination**, not *Proposed destination*: under D3 nothing about it is proposed.
- **An episode's notice offers nothing.** It carries FR-2's sentence and no *Review the conversion to MKV* button, the
  same as its row (see *Non-goals*).
- **At 640 px and below the track row itself restacks:** the `#` and kind columns are hidden and the row becomes three
  columns. This is direction A's drawn narrow treatment and the one promoted rule that reaches past this flow; it
  applies to every film's Tracks card, AVI or not.
- **The admin build adds four lines to `scripts/check-mobile-css.sh`**, one per hand-authored rule above, so a later
  copy over `detail.css` cannot lose them silently (`design/PROCESS.md`, promotion gate step 2 and invariant 6):

  ```sh
  check "$DETAIL" "container-conversion notice (.cv-card)"          ".cv-card {"
  check "$DETAIL" "container-conversion scoped focus ring"          ".cv-card .btn:focus-visible, .cv-dlg .btn:focus-visible"
  check "$DETAIL" "container-conversion light-theme tints"          "[data-theme=\"light\"] .cv-keep"
  check "$DETAIL" "track row restacks at phone width (FR-9 narrow)" ".trk-main > :nth-child(2), .trk-main > :nth-child(3)"
  ```

## Non-goals

- **No rename of the garbled file name** (D3). The admin keeps showing the real on-disk name. Correcting this one file
  is a production file operation and a separate owner decision.
- **No conversion for episodes.** An AVI episode gets FR-1's refusal and FR-2's sentence. A Sonarr identity move
  has no ready port the way 316's mover is ready for films, so that is its own piece of work if it is ever wanted.
- **No standing action and no bulk conversion.** Only the failed edit leads here (D5).
- **No removal control for the kept original**, and no restore button. It is kept until the owner removes it (D2),
  and restoring is one rename.
- **No conversion of any production file as part of the build.** See *Decisions*.
- No Radarr/Sonarr profile or custom-format change. A newly tagged language is scored by Radarr exactly as an MKV
  language edit is today.

## Known consequences

These are measured on the 314c rename (DEB-869) and are expected here too:

- Radarr re-creates the `moviefile` row, so its `dateAdded` moves to the conversion date.
- Radarr's `MissingFromDisk` on the old path takes the extras it had adopted. FR-8's artwork re-assert answers that.
- Jellyfin may give the title a new item id. Ravilo's *Continue Watching* follows the remapped watch data, but a
  client holding the old id on screen sees it disappear until it reloads.

## Acceptance

1. On the dev stack, a language edit on an AVI answers 422 at once. No work file appears, and the row reads
   *"Not kept"* with the link.
2. The notice, preview, confirmation and job run end to end on a test AVI that is not a library title. The `.mkv`
   carries the tag (ffprobe), the original sits byte-identical at `.jellystructure/<base>.avi`, and the stored path,
   Jellyfin's item and Radarr's file link all name the `.mkv`.
3. Jellyfin, Radarr and Sonarr never list the kept original (311's acceptance 3, extended to it).
4. Each blocked state shows when its condition is set up on dev: a seeded path, qBittorrent stopped, a pre-existing
   `.mkv`, and a stored path that no longer exists.
5. The seventeen states match direction A as promoted, at 1040 px and 390 px.
6. The owner reads the da and fo strings, on the QA row's screenshots, before the build is closed.

## Amendment 2026-10-22 — what the build found (DEB-2051)

Five facts the spec could not know, none of which changes a decision the owner made:

- **FR-1 reads the extension only.** The stored probe is a list of tracks and carries no container field; the language
  route already branches on the file name's extension, so that is what the refusal reads. A case-insensitive `.avi` is
  the whole test. (Adding a container field to the stored record for a file whose name already says what it is would be a
  schema change with no case behind it.)
- **FR-1 covers every route that writes a language.** Besides the movie and episode routes, Triage's two *assign
  language* routes (`POST /api/triage/{id}/tracks/{specifier}/language` and its episode form) ran the same futile remux;
  they refuse the same way.
- **FR-9's payload is `GET …/container-conversion`.** The title's record is stored and shared with the wire installed
  clients read; a job's step and verdict do not belong in it. The endpoint's `latest` carries the file's newest job
  (`state`, `step`, `reason`, the languages it carried, and the two verified flags), and the media page asks it again
  when the push (`JobEvent.MediaJobUpdate`) says a conversion job for the title changed. The job row is the only store:
  `files_done` is the step, `speed` the machine detail a finished row keeps, `error` the sentence — no new table.
- **FR-10's strings are in the shared table.** `i18n/<code>.json` is the one table every client and the admin compile
  against; the admin has no interface language of its own, so it draws the `migrate.*` keys in English unless the URL
  carries `?lang=da` or `?lang=fo` (remembered in `localStorage` as `js-admin-lang`). That is also how the owner reads
  the Danish and Faroese drafts on the QA row (Acceptance 6). Cancel and Close reuse the shipped `action.cancel` and
  `action.close`. Four keys are added beyond the 97 the round drew — `migrate.estTpl`, `migrate.timeSec`,
  `migrate.timeMin`, `migrate.timeHourMin` — because the drawn *estimate* is a fixed example (*"about 2 minutes · 1.4 GB"*)
  and the real one is composed from numbers.
- **A restart closes a running conversion as failed** (FR-7's "a restart counts as a failure at the step it reached"):
  the row is not requeued, the original is as it was, and the person presses *Start migration* again.

Open, for Tróndur: phase 311's sweep removes any file in a `.jellystructure/` folder whose name starts with a work prefix
(`remux_`, `replace_`, `convert_`, …) after 24 hours. A film whose own file name began with one of those would have its
kept original treated as a work file. None exists in the library today; the build does not guard against it.
