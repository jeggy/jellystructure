# The container-migration round (`avi-mkv-migration`)

Quarantine of the **last round that comes from the design tool**. The brief was ours (lane B); the
studio drew it before the owner pasted anything back, and it came home inside the owner's
2026-10-22 export rather than through the pull. Received 2026-10-22, extracted by named path only
from `~/design-exports/2026-10-22.zip` into a scratch directory outside the repo, and written here
exactly as received.

**Nothing here is promoted, no production file is touched, no spec is written and no number is
allocated.** The task id is the identity; a number is never allocated from a package.

## What landed — 27 files, all of them

26 new files, plus the one existing mockup the round edited in place.

| Group | Count | Bytes |
|---|---|---|
| The directions canvas and its stylesheet (`avi-mkv-migration/`) | 2 | 61 646 |
| PNGs, one per named state (`avi-mkv-migration/png/`) | 22 | 4 345 510 |
| The new mockup component the round carried into the full designs (`app/`) | 2 | 20 984 |
| The existing mockup the round edited in place, kept for comparison (`edited-in-place/`) | 1 | 116 612 |

### Added — text

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `avi-mkv-migration/AVI to MKV Migration - Directions.html` | 45 186 | 222 | `e87717d92fe7` |
| `avi-mkv-migration/avi-mkv-migration.css` | 16 460 | 198 | `33c9f174c2cd` |
| `app/container-convert.css` | 6 590 | 72 | `6e8efad395a9` |
| `app/container-convert.js` | 14 394 | 109 | `0cca7f78f20e` |

### Edited in place — kept, not promoted (`edited-in-place/`)

| File | Bytes | sha256 (first 12) | What the round changed |
|---|---|---|---|
| `media.html` | 116 612 | `8bc2195305a3` | one `<link>` to `container-convert.css`, one `<script>` for `container-convert.js`, and a two-line `#cv-mount` slot at the top of the Tracks panel for direction C's standing card |

Diffed hunk by hunk against the tracked `design/app/media.html`: **three hunks, four added lines,
zero deleted lines.** No existing rule, string or script was changed.

### PNGs — 22, by direction

| Direction | Frames |
|---|---|
| `file-action` (drawn in full) | 17 — one per name in the brief's `STATES` list |
| `in-row` (sketch) | 3 — `limitation`, `confirm`, `running` |
| `standing` (sketch) | 2 — `detail-default`, `limitation` |

Every one was opened and looked at before this commit. All 22 show stand-in data only: `Sample Film`,
`demo-film-001` and the invented `/media/movies/...` paths, and nothing else.

## What was dropped at the gate

Nothing from this round. The export is the whole studio, so the rejected-by-name list did its usual
work on everything *outside* the named paths — `CLAUDE.md`, `ravilo/STATUS.md`,
`ravilo/constitution.md`, `ravilo/plan.md` and the whole `presentation/`, `uploads/` and
`screenshots/` trees were never extracted. The round itself created nothing by any of those names,
nothing under `specs/` or `scripts/`, and no file in it carries a phase number or a task id.

## Known, for whoever promotes this

- `app/container-convert.css` ends with a bare `.btn:focus-visible` rule. It is page-wide, not
  scoped to the card, so promotion needs it scoped and a `SCOPED_FILES` row in
  `check-css-scoping.sh` — and, were the file ever to join the served set, both the
  `syncDesignAssets` list and the `<link>` set in `index.html`.
- The directions doc's "new strings" section prints the round's `STR` object rather than filling the
  key/en/da/fo table above it. Every string is there and extractable; the table is not. The Faroese
  is a draft and wants a native read.
