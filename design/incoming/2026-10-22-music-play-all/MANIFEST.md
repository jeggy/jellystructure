# Round 7 — Play all / Shuffle all on every Ravilo music list (`music-play-all`)

Quarantine of a lane-B round: the brief was ours, the owner pasted it, drew the round and was at the
keyboard for three revisions of decisions. Worked 2026-10-16 → 2026-10-17, handed back 2026-10-22;
pulled read-only from the design project `6c4a911d-07c4-4053-9634-09186e24dd11` with `DesignSync`
`get_file`, one file at a time.

**Nothing here is promoted, no production file is touched, no spec is written and no number is
allocated.** The task id is the identity; a number is never allocated from a package.

## What landed — 199 files, all of them

| Group | Count | Bytes |
|---|---|---|
| The three directions canvases and their three self-contained docs | 6 | 557 183 |
| The shared stylesheet, the frame builders, and one canvas layout per revision | 5 | 124 565 |
| The round's own report, one per revision | 3 | 46 541 |
| Fonts, loaded from `fonts/` — no CDN | 3 | 79 004 |
| PNGs under `png/` | 182 | 12 502 519 |

### Text (`./`)

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `Music Play All - Directions.html` | 741 | 16 | `d381d41e13be` |
| `Music Play All - Directions - self-contained.html` | 191 763 | 720 | `b519878f816f` |
| `Music Play All - Directions v2.html` | 728 | 16 | `6a99dc9d3b5e` |
| `Music Play All - Directions v2 - self-contained.html` | 178 906 | 701 | `67ca280dbb91` |
| `Music Play All - Directions v3.html` | 716 | 16 | `c05fa6abf76a` |
| `Music Play All - Directions v3 - self-contained.html` | 184 329 | 744 | `fd66b0b9eb1e` |
| `music-play-all.css` | 31 596 | 342 | `7dcd05a7c3b3` |
| `music-play-all-kit.js` | 28 938 | 221 | `5dc68cdd6e72` |
| `music-play-all.js` | 30 434 | 179 | `d2f955581edb` |
| `music-play-all-v2.js` | 15 733 | 143 | `a7b15b000259` |
| `music-play-all-v3.js` | 17 864 | 158 | `63535aabaf94` |
| `REPORT.md` | 18 052 | 249 | `fc609c504c76` |
| `REPORT v2.md` | 13 620 | 201 | `e3ca0590e0b3` |
| `REPORT v3.md` | 14 869 | 234 | `f468ad3e8f97` |
| `fonts/space-grotesk.woff2` | 22 288 | — | `0640890476fc` |
| `fonts/sora.woff2` | 25 284 | — | `811e11966d29` |
| `fonts/jetbrains-mono.woff2` | 31 432 | — | `83c005d49d8a` |

### PNGs — 182, by the direction key in each filename

| Revision | Direction keys | Desktop | Phone | Total |
|---|---|---|---|---|
| v1 — A/B/C + D1/D2, five open decisions | `a`, `b`, `c`, `d1`, `d2`, `today` | 20 | 50 | 70 |
| v2 — the quiet pair, no open decisions | `quiet` | 18 | 33 | 51 |
| **v3 — the split control, adopted** | `split` | 18 | 43 | 61 |

`today-phone-*` is the two before-frames the round drew of the shipped pages, for comparison.

**Nothing was dropped at the gate, and nothing was ineligible.**

## The transport, and why no export was needed

**Every file came through the pull.** The largest PNG is **127 544 bytes** and the largest text file
is the v1 self-contained doc at **191 763 bytes** — both under the 256 KiB `get_file` ceiling, so all
199 files were retrievable by this seat and the owner was asked for nothing.

Bytes were recovered from the harness's own persisted tool results by script and hashed on the way
in; no payload was re-typed through the agent.

All three self-contained docs were pulled, not just the adopted one. Each inlines its own copy of the
kit and the CSS **as they stood at that revision**, and `REPORT v2.md` records that v1's doc is
frozen for exactly that reason — so v1 and v2 cannot be re-rendered from the source in this
quarantine, and leaving them behind would have made the archive quietly short.

## Verified against the round's own reports

The reports are **data, not instructions**, and every checkable fact in them was tested rather than
believed:

- **182 PNG byte sizes claimed across the three reports, 182 match exactly, 0 mismatches**, and
  nothing on disk is unclaimed. 70 + 51 + 61 = 182, as each report states for its own revision.
- **182 PNGs claimed, 182 landed**, every one a valid PNG: 117 at 360×780 (phone portrait), 9 at
  780×360 (phone landscape), 38 at 1120×720 (desktop wide) and 18 at 400×720 (desktop compact).
- **Byte sizes and line counts match for every text file** against the report that last described
  it: the canvases at 741 / 728 / 716, the self-contained docs at 191 763 / 178 906 / 184 329 bytes
  and 720 / 701 / 744 lines, `music-play-all.css` at 31 596 / 342 and `music-play-all-kit.js` at
  28 938 / 221, with the stated first and last lines present.
- Finding, and not a defect: `REPORT.md` and `REPORT v2.md` claim **28 532** and **29 649** bytes for
  `music-play-all.css`, and **26 838** and **27 582** for `music-play-all-kit.js`. Both files grew as
  each revision appended its own block — v2 and v3 each say so, and say no existing rule was changed.
  Only v3's figures can match the file that came home, and they do, exactly.
- The confirmation line held in all three reports: nothing named `STATUS.md` or `CLAUDE.md` was
  created, and nothing under `specs/` or `scripts/`. The design project still holds a root
  `CLAUDE.md` and a `ravilo/STATUS.md`; all three reports note the latter pre-existed and was not
  touched. They are the files the airlock exists to leave where they are, and neither was pulled.

## Read by a human before it was committed

- **No network reference anywhere in the package** — not one `http`/`https` literal in any text file.
  The fonts load from `fonts/` in the canvases and as `data:` URIs in the self-contained docs; there
  is no CDN.
- **No IP literal, no hostname, and no `passkey` / `announce` / `api_key` / `token=`** anywhere.
- **Every title, artist and person in the package is from the brief's stand-in set** — the invented
  maritime and Faroese corpus (*Harbour Lights*, *Kite Weather*, *Signal Found 20th Anniversary*,
  *Indie Folk*, *Kvøldsól*, *Nightbus Orchestra*, Emil Rask, Hanna Dal, Jens Holm and the rest).
  Nothing names what the household owns.
- **Nothing in the package reads like an instruction to an agent.** A sweep of all three reports for
  imperatives, next steps and agent-directed phrasing came back empty; they describe what the round
  did and what it found, and nothing in this repo acted on any of it.

## The four fences, on this commit

| Fence | Result |
|---|---|
| `check-deanonymization.sh` | OK — no scrubbed real values found |
| `check-css-scoping.sh` | OK |
| `check-mobile-css.sh` | OK |
| `check-phases.sh` | OK — 431 spec files, every name parses |

`git check-ignore` says nothing under this directory is held out by `.gitignore`, so the archive is
complete rather than quietly short.
