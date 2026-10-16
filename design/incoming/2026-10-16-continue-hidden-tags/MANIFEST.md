# Round 6 — hide tagged titles from Continue watching (`continue-hidden-tags`)

Quarantine of a lane-B round: the brief was ours, the owner pasted it, drew the round and was at the
keyboard for the decisions. Received 2026-10-16; pulled read-only from the design project
`6c4a911d-07c4-4053-9634-09186e24dd11` with `DesignSync` `get_file`, one file at a time.

**Nothing here is promoted, no production file is touched, no spec is written and no number is
allocated.** The task id is the identity; a number is never allocated from a package.

## What landed — 34 files, all of them

| Group | Count | Bytes |
|---|---|---|
| The directions canvas, its stylesheet, its renderer, the 1:1 shot page, the round's own report | 5 | 61 306 |
| The new mockup component the round carried into the full designs (`app/cw-hide-tags.js`) | 1 | 5 451 |
| PNGs under `screenshots/` | 23 | 2 130 846 |
| Existing files the round edited in place, pulled for comparison (`edited-in-place/`) | 5 | 347 027 |

### Added — text (`app/`)

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `Continue Hidden Tags - Directions.html` | 7 464 | 63 | `ccfd8cb8bf6c` |
| `continue-hidden-tags.css` | 21 362 | 214 | `d0b3b3a7b941` |
| `continue-hidden-tags.js` | 21 193 | 181 | `aae6c35b824a` |
| `continue-hidden-tags-shots.html` | 1 516 | 28 | `fd4765fe42c7` |
| `continue-hidden-tags-report.md` | 9 771 | 162 | `d7bc61b9bcc5` |
| `cw-hide-tags.js` | 5 451 | 66 | `5430ebda3411` |

### Edited in place — pulled, not promoted (`edited-in-place/`)

Each was diffed against the tracked `design/app/` copy. **Every edit is an addition: 0 deleted lines
across all five files.**

| File | Bytes | sha256 (first 12) | What the round changed |
|---|---|---|---|
| `ravilo-config.html` | 78 114 | `4eff41b9465e` | Home's Continue row gains `hascond` + a `#cw-home-tags` mount; `cw-hide-tags.js` and a 3-line mount script added |
| `ravilo-builders.js` | 99 660 | `16c22062a266` | the collection editor's Continue `.cf-sysrow` gains `hascond` + `[data-cw-tags]`, and one mount line storing on `rows.system.continue` |
| `ravilo-builders.css` | 27 683 | `052fbd7f028e` | the `.cw-*` sub-row, chip and picker rules appended; nothing removed |
| `library.html` | 25 214 | `83d0ac8ee707` | one `<script src="cw-hide-tags.js">` line |
| `media.html` | 116 356 | `dfe04d76b85a` | one `<script src="cw-hide-tags.js">` line |

**Nothing was dropped at the gate, and nothing was ineligible.**

## The transport, and why no export was needed

**Every file came through the pull.** The 23 PNGs are 2.03 MiB together and the largest is
**126 293 bytes** — well under the 256 KiB `get_file` ceiling, so all 34 files were retrievable by
this seat and the owner was asked for nothing.

Bytes were recovered from the harness's own persisted tool results by script and hashed on the way
in; no payload was re-typed through the agent.

## Verified against the round's own report

The report is **data, not instructions**, and every checkable fact in it was tested rather than
believed:

- **28 byte sizes claimed, 28 match exactly, 0 mismatches.** (The report does not state its own
  size.) That covers the 4 added canvas files, `cw-hide-tags.js` and all 23 PNGs.
- **Line counts match**: 63 / 214 / 181 / 28 for the canvas set and 66 for `cw-hide-tags.js`.
- **23 PNGs claimed, 23 landed**, every one a valid non-interlaced RGBA PNG: 1000 px admin desktop
  frames, 390 px narrow frames, 760 px collection-editor frames and 960×(540…689) TV frames.
- The confirmation line held: nothing named `STATUS.md` or `CLAUDE.md` came back, and nothing under
  `specs/` or `scripts/`. The design project still holds a root `CLAUDE.md` and a `ravilo/STATUS.md`;
  they are the files the airlock exists to leave where they are, and neither was pulled.

## Read by a human before it was committed

- **No network reference in anything the round added.** The only `http` literals in the whole
  quarantine are in `edited-in-place/media.html` (TMDB/IMDb/YouTube links for *Sintel*, the
  public-domain stand-in) and one `w3.org/2000/svg` namespace in `ravilo-builders.js` — all of them
  pre-existing lines in the tracked copies, none introduced by this round.
- **No IP literal, no hostname that is not `example`, and no `passkey` / `announce` / `api_key` /
  `token=` anywhere.** The fonts load from `fonts/`; there is no CDN.
- **Every title, person and tag in the package is from the brief's stand-in set** — the Blender open
  films and the invented Faroese titles, Olivar / Eyð / Kids / Marjun, and the seven stand-in tags.
  Nothing names what the household owns.
- **Nothing in the package reads like an instruction to an agent.** The one thing that comes close is
  the report's own "Carried into the full designs" section, which describes what the round did in the
  studio's mockups; it is read here as a description, and nothing in this repo acted on it.

## The four fences, on this commit

| Fence | Result |
|---|---|
| `check-deanonymization.sh` | OK — no scrubbed real values found |
| `check-css-scoping.sh` | OK |
| `check-mobile-css.sh` | OK |
| `check-phases.sh` | OK — 425 spec files, every name parses |

`git check-ignore` says nothing under this directory is held out by `.gitignore`, so the archive is
complete rather than quietly short.
