# A failure that will fail again is not retried every hour
Task: DEB-5537

> Owner, 2026-11-25 (DEB-5531): picked *"Stop retrying permanent failures every hour"* from the log review.

## Scope

Backend only. The four hourly retry loops below, plus the WARN lines they print. No route, DTO, client or admin change.
A schema change is allowed only if FR-1's record cannot live in a table that already exists. The precedent for
remembering a per-file outcome against its size and mtime is `AvSyncService.storeSkip`, which uses
`FileIntegrityService.stampOf`. The ingest retry set (`dirty_item`) is
**not** here: its backoff is already promised by `phase-195-ingest-retry-set-can-never-drain.md` FR-195-4, and DEB-5536
restores it.

## What happens today (prod, DEB-5531, one container lifetime of about 18 h)

| Loop | Where | Every hourly scan |
|---|---|---|
| Cover art | `ArtworkDownloader.downloadUnchecked` (`Rejected artwork download (HTTP 404)`) | The same 3 coverartarchive release groups answer 404. 54 WARNs. |
| Music tags | `MusicTagWriter` write batch (`write_tags: N file(s) left as they were`) | 27 files fail: 26 `.m4a` files rejected as *"not a MP3 file"*, and one `.mp3` whose tag *"did not read back"*. 54 WARNs. |
| An unreadable file | `check_track_lengths` / deep check (`ffprobe could not read the file`, `moov atom not found`) | One episode file. 325 failed jobs since 2026-11-10. |
| A file gone from disk | `check_subtitles` (`the file is no longer on disk`, cancelled) | Two films whose DB rows still name files that are no longer there. Enqueued and cancelled each hour. |

None of these can succeed until something outside changes: the file, the remote answer, or the library row.

## Requirements

- **FR-1 — remember the failure against its input.** When one of the four loops fails for a reason that will repeat,
  it records the failure against what it tried. For a file that is the path, size and mtime. For a remote fetch that
  is the URL (or the MusicBrainz release-group id). It also records the reason and when it happened. The same input is
  not tried again by the scheduled pass until either:
  - the input changes (a different size or mtime, or a different URL), or
  - 7 days have passed since the last failure.

  An operator action on that title always tries again and clears the record when it succeeds. That means a title's
  own Refresh ▾ or ↻, *Check now*, or a manual artwork pick.
- **FR-2 — a format the tagger cannot write is not a failure.** `write_tags` does not put a file into the plan when
  the tagger cannot write that container. Today that is every `.m4a`/`.mp4` audio file. Such a file counts as
  *unsupported*, not *failed*: no WARN line, and not in the failed list. A file the tagger can write but whose tag did
  not read back stays a failure, and FR-1 applies to it.
- **FR-3 — a file that is gone ends its rows, it is not checked.** A job whose producer finds the target file is not
  on disk is not enqueued at all. The stored row that names that path is reconciled the way a scan reconciles a
  deleted file, so the hourly freshness list stops naming it (*"1092h unexamined"*). This does not delete anything on
  disk, and it does not touch Jellyfin.
- **FR-4 — say it once.** A failure recorded under FR-1 prints its WARN line when it is recorded. While the record
  holds, a skip prints at most one INFO line per pass, giving counts only (*"skipped 3 known failures; next retry
  …"*), not one line per item.

## Acceptance

1. **Unit tests** (`linuxX64Test`):
   - a failed input is skipped on the next pass, retried after 7 days (injected clock), and retried at once when its
     mtime changes;
   - an operator run ignores the record;
   - `write_tags` with an `.m4a` and an `.mp3` in the plan sends only the `.mp3` and reports the `.m4a` as unsupported;
   - a `check_subtitles` producer with a missing path enqueues nothing.
2. **Prod, read-only, 24 h after the deploy.**
   - The backend log has no `Rejected artwork download (HTTP 404)` and no `write_tags: … left as they were` line
     repeated hour after hour for the same items.
   - `media_job` has at most one failed `check_track_lengths` for the unreadable episode file since the deploy.
   - It has no `check_subtitles` row for the two missing films.
