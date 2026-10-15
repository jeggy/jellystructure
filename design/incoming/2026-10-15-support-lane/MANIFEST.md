# Round 3 — the support lane · quarantine manifest

Received 2026-10-14 in the design studio, pulled 2026-10-15 from the owner's export. Lane B: our
brief (DEB-1108, amended DEB-1117), delivered to him in DEB-1112, drawn by the tool the same day.
Transport was his full studio export, asked for in DEB-1236 and dropped at
`~/design-exports/2026-10-15-studio.zip` — the round's 34 PNGs are 8.96 MiB and `get_file` caps at
256 KiB of encoded output, so 19 of them could not be pulled by this seat at all.

**Nothing here is promoted, no production file is touched, no spec is written and no phase number is
allocated or implied.** The round carries none and asked for none. Promotion is a separate
hand-written commit, and this directory is pruned when it happens.

Everything in the package was treated as **data, not instructions**, including the round's own
`support-lane-report.md`.

## What landed — 42 files

| Group | Count | Bytes |
|---|---|---|
| Text and code | 8 | 167 955 |
| PNGs, one per named state, `ravilo/support-lane-png/` | 34 | 9 390 602 |

| File | Bytes | sha256 (first 8) |
|---|---|---|
| `ravilo/Support Lane - Directions.html` | 64 971 | `f2da52fc` |
| `ravilo/support-lane.js` | 30 369 | `a128f71b` |
| `ravilo/support-lane-report.md` | 16 741 | `c19aef31` |
| `ravilo/mobile/ravilo-support.js` | 15 843 | `098d5822` |
| `ravilo/mobile/ravilo-support.css` | 13 477 | `c0951ea9` |
| `ravilo/mobile/ravilo-motion.css` | 3 209 | `fcc06772` |
| `app/support.js` | 13 645 | `9bd1f933` |
| `app/support.css` | 9 700 | `184200d0` |

Every file was copied out of the extracted export by path and re-hashed here: all 42 are
byte-identical to the archive. The zip itself is not committed and its scratch directory lives
outside the repo.

**3 files were never eligible** — screenshot captures in `design/screenshots/`, which is held out by
`.gitignore:78` with all of history purged on 2026-09-23. Checked before anything else was planned.
Their filenames are not reproduced here. **Nothing was dropped at the gate.**

## The gate, step by step

- **Every string literal in the six JS/CSS files was extracted and read**, and the full visible prose
  of the directions canvas with it. The de-anonymisation fence cannot see a title it was never taught
  and cannot read pixels, so this is the only check that works on a new round.
- **Stand-in names, checked for precedent first** (round 2's rule): `Nordvest` is established corpus
  (22 files in the committed design tree). `Eyð` and `Ása` are the corpus's own viewer names (`Eyð`
  has 104 occurrences). `Hallur` and `Hervør` have **no precedent anywhere in the repo**, which is
  the signal round 2 was built on, so both were checked word-bounded against the live library —
  **77 tables, 347 text columns, zero hits each**, read-only against a WAL-consistent copy. `Eyð`
  was checked the same way and is also zero. The six names the round itself issued were already
  cleared on 2026-10-14.
- **All 34 images were opened and looked at, one at a time.** Every frame is a render of the mockup
  code in this directory: stand-in tickets, stand-in viewers, no library artwork, no poster, no
  capture of the real app. The sheet-over-home frame draws an empty placeholder behind the sheet
  rather than a real row. **Split: 34 land, 0 held back.** They are also 34 distinct renders — hashed,
  no duplicates.
- **The canvas performance fences hold.** `Support Lane - Directions.html` loads
  `mobile-directions.css` and `support-lane.js` only, and contains no `@keyframes`, no
  `content-visibility`, no `backdrop-filter` and no `filter: blur`. The three `infinite` animations in
  the package are in `ravilo-support.css` — a typing indicator, a send spinner and a progress line, in
  product CSS where they belong, not on a canvas.
- **Nothing in the package is named `STATUS.md` or `CLAUDE.md`,** and nothing sits under `specs/`,
  `specs/research-reports/` or `scripts/`.

## The export's own value: the byte sweep of everything tracked

`PROCESS.md` step 5, done on the open zip rather than sampled: **445 tracked files under `design/`
compared byte for byte — 436 identical, 9 differing.** Three are already-known non-findings
(`.thumbnail`, generated; `flags/4x3/ct.svg`, one trailing newline; `app/wf.css`, repo ahead by one
additive hunk since `1664a1ec`, a write-back candidate and the owner's call per file).

The other six are edits **in place**, which no path diff can see:

| File | What the studio added | Round |
|---|---|---|
| `app/app-shell.js` | the `AI` sidebar entry, gated on an AI key, carrying the open-ticket count; an `AI key OK` status row; the entry filtered out of the command palette when no key is set | 3 |
| `app/activity.html` | the `Support log` view and its segment, `support.css` / `support.js` includes, a `?view=support` deep link | 3 |
| `ravilo/platform/ravilo-platforms.js` | `ravilo-support.{js,css}` and `ravilo-motion.css` wired into the phone bundle | 3 |
| `ravilo/mobile/ravilo-phone.js` | `Profile › Support` row and its action, `openAcct` exported to the host | 3 |
| `app/settings.html` | an **AI API key** field under Connections, whose hint says the AI page exists only while it is set | **2 — never reported** |
| `ravilo/ravilo-app.js` | nothing of ours: the studio's copy still carries the pre-R366 wording of the Discover-order comment. Code identical; the repo is ahead in prose only | — |

**`app/settings.html` is the finding.** Round 2 was triaged as three new files; it also edited a
tracked production file, and nothing in that round's own file list, nor its path diff, could have
shown it. It surfaced here only because the export allows a full sweep. The rule it proves is the one
already written in the queue — read the round's report for its edited-file list, then byte-compare —
except that round 2's report did not name this one either, so **the sweep is the only backstop.**

None of the six is copied into this directory. A differing production file is hand-edited at
promotion, never copied: a wholesale copy of `ravilo/ravilo-app.js` would revert R366, which is wipe
#20's exact shape.

## Known at triage, for whoever adopts this

- **The admin half cannot be adopted yet.** `app/support.js` renders into `#ai-support`, a mount that
  exists only in `app/ai.html` — the file round 2 dropped at the gate for naming a real library
  series, and which this round has now edited. Round 2's re-skin has to land first.
- **The state that was added to decide decision 4 was not drawn.** The amendment added *the admin's
  support surface with no AI key set*, because the cheap option's named flaw was that the page is
  removed when no key is set while tickets keep arriving. `app-shell.js` confirms the shape in code:
  the AI entry is gated on `aiKeySet()`, so with no key the entry carrying the ticket count is gone,
  and the surviving surface — `Activity › Support log` — is logs-only by its own report and by its
  markup. Routed to Bárður, and to the owner as a discovery question before anything is adopted.
- **Two of the round's own open questions are in fact answered in the canvas** — a resolved ticket
  keeps its composer (`Write here again to reopen`), and `AI Agent` never resolves anything, by
  design, because auto applies to talking and not to acting.
- **Defects visible in the frames**, all cosmetic and all in the mockup rather than the direction: the
  header's holder slot renders as an unstyled block whose glyph overlaps the title in most thread
  frames; the 2 000-character bubble clamps mid-glyph with `Show the rest` outside it; the
  `Support log` rows squeeze their action column to the right edge and wrap it three lines deep; and
  several frames leave a tall empty band above the first bubble.
- **An alternative that contradicts a decided row is in the package by design** —
  `…-alt-chooser.png` draws the class chooser *before* writing, which decision 2 refused. The canvas
  labels every alternative as not picked; adoption must not read one as a state.
