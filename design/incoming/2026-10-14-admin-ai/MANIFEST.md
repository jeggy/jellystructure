# Round 2 — the admin AI page — 2026-10-14

Quarantine commit for DEB-1098. **Received only; nothing promoted.** Promotion is a separate
hand-made commit, and `design/incoming/` is pruned at adoption (`design/PROCESS.md`).

Lane A of `design/PROCESS.md` step 2: the owner designed, this seat pulls and triages. No brief was
written for it — he said only *"We just got some new AI designs in the design tool"* (DEB-1076,
2026-10-14 10:30Z) — so the round's intent is read off the canvas and one confirming question goes
back on DEB-1098 rather than being guessed at in a spec.

Source project — `6c4a911d-07c4-4053-9634-09186e24dd11`, name Jellystructure, owner Jógvan,
type `PROJECT_TYPE_PROJECT`, `canEdit: true`. Read only; nothing was written or deleted there.

## The round is three files, and the baseline named them in one step

Diffed against the DEB-1078 baseline (09:23Z today) rather than re-derived:

| | count |
|---|---|
| studio files, this pass | **620** |
| named by hand in round 1's `MANIFEST.md` | 610 |
| deliberately-unnamed captures (round 1, held in the studio) | 7 |
| 610 + 7 | 617 — the DEB-1078 baseline, matched exactly |
| **new this round** | **3** |

`list_files` returned 648 entries; 28 of them are directories, which is how 648 reconciles to 620
files. The three repo-only documents the baseline recorded (`PROCESS.md`,
`design-project-notes-2026-06-to-2026-10.md`, `github.md`) are still studio-absent, as expected —
they are repo-authored and the studio has no write path here. **No deletion anywhere in the studio.**

The round:

| path | bytes | sha256 (first 8) | `truncated` | verdict |
|---|---|---|---|---|
| `app/ai.css` | 9379 | `9f0a2144` | `false` | **landed** |
| `app/ai.html` | 11187 | `6e44164f` | `false` | **dropped at the gate** — see below |
| `app/ai.js` | 15321 | `54e61f40` | `false` | **dropped at the gate** — see below |

All three came back whole — **nothing hit the 192 KiB wall, so this round needed no export.** Bytes
were lifted out of the harness's own tool results by script and hashed on the way in, never
retyped: a silent corruption in a copy reads later as a design change.

## What was dropped, and why

**`app/ai.html` and `app/ai.js` name a real series from the household's library.** Not in a
filename and not in a capture — in the mockup's *stand-in data*, as the worked example the whole
page is built around: the series name appears 10 times across the two files, together with a source
URL whose path slug is that series' own slug, a Faroese episode name, and a TMDB id presented as
live, including a deep link to that id's episode-edit page.

The title is **not reproduced here, in the commit message, or on the board**, which is why this
section counts and does not quote. It was confirmed against the live library read-only, not guessed:
one row matches it as a whole word in `title`, `originalTitle` and `sortName`, across 19 `path` and
18 `filename` fields — a series the household owns, with its episodes on disk.

Three things are worth recording about how it was caught:

- **`scripts/check-deanonymization.sh` is green on both files.** It matches hashed n-grams of values
  somebody has already scrubbed, and this name was never in its list. The fence is not weaker than
  it was yesterday; this is simply outside what it can see.
- **A human reading every string literal is the only thing that stopped it** — the same sentence
  round 1 had to write about `presentation/presentation-context.md`, and the second time in two
  rounds that the eye caught what the fence structurally cannot.
- **Precedent nearly hid it.** Two of the round's other three stand-in names are the established
  corpus — `Nordvest` has 22 hits in the committed design tree, and `Babel Fish` is named in
  `specs/constitution.md` as a mixed-language series fixture. Only one name in the file had no
  precedent anywhere in the repo, and that was the one that was real. *A round that reuses the
  stand-in corpus almost everywhere is not thereby safe; the exception is the finding.*

Nothing was redacted and re-landed. A quarantine copy is the bytes as received or it is not a
quarantine copy, and a hand-cleaned mockup is a design decision this seat does not get to make.
Both files stay in the studio, whole — we delete nothing there.

## What landed

`app/ai.css` — 9379 B, page-local, every rule `af-`/`tq-` prefixed or under
`main[data-page="ai"]` / `main[data-state=…]`. It holds no string literal of any kind: no title, no
capture, no host, no network reference, no IP literal, no key. It is the one file in the round that
the gate has nothing to say about.

It is also, on its own, not promotable: it is the styling for markup that was dropped. It is
committed because the gate is per file and because the bytes are worth having when the page comes
back re-skinned — not as a half-round waiting to be finished.

**At promotion, whenever that happens:** page-scoped CSS needs a `SCOPED_FILES` row in
`scripts/check-css-scoping.sh` and a wrapper class declared (`design/PROCESS.md` line 155 — one
unscoped file once leaked 160 bare selectors across every page). Not done here; nothing promotes in
this task.

## The fences

Run on this quarantine commit, before anything moved:

| fence | result |
|---|---|
| `scripts/check-deanonymization.sh` | green |
| `scripts/check-css-scoping.sh` | green |
| `scripts/check-mobile-css.sh` | green |
| `scripts/check-phases.sh` | green |

## Not in this round, deliberately

- **`app/wf.css` is mildly stale in the studio again** — the repo moved to 59137 B in `1664a1ec`
  (10-13 10:35), after the 10-13 write-back. One additive repo-only hunk; the studio holds nothing
  of the owner's in that file. Bárður is routing it and the owner decides per file, so it is not
  pushed as part of this round.
- **The support-AI feature under investigation on DEB-1076** arrives as its own brief. It is a
  different feature from this page's Auto-fetch and is not folded into this triage. **Confirmed by
  Bárður on 2026-10-14**, after this round was triaged: it is a *second AI feature on the same
  page*, so the inference above is now a decision and not a reading of the canvas.
- **No phase number anywhere.** Allocation happens at adoption, from `STATUS.md` and the board, and
  numbers never leave the repo.

## Routing — 2026-10-14, after triage

Both questions this round raised went to Bárður, who owns the owner channel, and both are answered.

- **The two dropped files go back to the owner for a re-skin, asked as written.** The question put to
  him is the one in the triage report, unchanged: re-skin the worked example to the stand-in corpus
  and hand it back, or say why the real title was deliberate. The design itself is not in question —
  the recommendation was to change nothing but the example data. Nothing further happens to this
  quarantine directory until a re-skinned page arrives, and that arrival is a new lane-A round with
  its own pull, its own gate and its own manifest; this one is not reopened or patched.
- **Auto-fetch and the support-AI feature are two features sharing one page.** The consequence for
  design, recorded here because it outlives this round: the AI page's shell — the segment switcher,
  the `No AI key` gate that *removes* the sidebar entry rather than greying it, the narrow
  breakpoint — is **shared chrome**, not Auto-fetch's own. A later round that adds the second
  feature extends that shell; it does not draw a second one.

---

## Amendment — 2026-10-15 — the re-skin landed (DEB-1246)

The round-2 note above says this directory is not reopened or patched, and that a re-skinned page
arrives as its own lane-A round. **The owner decided otherwise** (DEB-1076, card `ec82f2e1`,
2026-10-15, verbatim): *"Rannvá swaps the example for a stand-in when she pulls your export, text
only, and asks once to write it back."* So the re-skin is done in this seat rather than by the
studio, and it lands here beside the `ai.css` it belongs to instead of in a fourth directory.

**Source:** the owner's export, `~/design-exports/2026-10-15-studio.zip` (10-15 09:43Z). No new
export and no studio pull. `app/ai.css` in that export hashes `9f0a2144`, byte-identical to the copy
already committed here, and `app/ai.js` hashes `54e61f40`, byte-identical to what round 2 read — so
round 3 edited `ai.html` only (`6e44164f` → `3639f188`, +455 B: the `#ai-support` mount, the
`tk-tabs` switcher and the `support.css` link).

### What changed — text only, by kind

The design, layout, every state, the step list, the review table and the copy structure are exactly
as drawn. Only the worked example's identifying data moved. **The replaced values are named by kind
only; this repository is public.**

| kind | replaced with | precedent for the stand-in |
|---|---|---|
| series name (12 occurrences across the two files) | `Royndin` | new; verified absent from the library |
| URL slug, fetcher id, queue-row id | `royndin` | as above |
| source host | `varpid.example.fo` | `Varpið`, the corpus's Faroese broadcaster — 14 hits in the committed tree |
| TMDB id (was live, and deep-linked to a real episode-edit page) | `9000001` | placeholder above TMDB's TV id range — resolves to nothing |
| the source's episode-numbering word, in the pattern string and three page titles | `partur` | the **shipped** Faroese word for *episode* (`up_episode`, and FR-R343-7's `partur`/`partar`) |
| Faroese episode name | `Útfall` | new; verified absent from the library |
| that episode's overview — it described the real programme's premise | a stand-in synopsis for `Royndin`, same length and shape | — |

### Why the numbering word had to go too, which round 2 did not call out

Round 2 named four identifying items: the series name, the slug, a Faroese episode name and the
TMDB id. There was a fifth. The source's episode-numbering word is **in the live library** — 10
episode titles, 10 paths and 10 filenames on the one matching row — so it is a library string in its
own right, not a convention the design invented. It also had **zero** occurrences anywhere in this
repository, which is the same signal that caught the real title: *the name with no precedent is the
one that is real.* Both of round 2's cleared stand-ins (`Nordvest`, `Babel Fish`) are untouched.

The yearly-season pattern itself stays. It is what steps 3–5, the `Pattern` row, the review table
and the `season/2026/episode/1` deep link all exist to demonstrate; removing it would be a redesign,
and this task is text only.

### The gate

| check | result |
|---|---|
| every string literal in both files read by hand | done |
| every media and episode title in the library, word-bounded, against both files | no hit but generic single English words (`from`, `Still`, `Media`, `Class`, …) that are titles somewhere in the library and ordinary UI prose here |
| every non-ASCII token in both files against the library | `Útfall` — clean |
| residual check for all six replaced items | none present |
| `scripts/check-deanonymization.sh` | green |
| `scripts/check-phases.sh` | green |
| `scripts/check-css-scoping.sh` | green |
| `scripts/check-mobile-css.sh` | green |
| canvas fences, `ai.html` and `ai.js` | clean |
| canvas fences, `ai.css` | **one violation — pre-existing, see below** |

The library was read from a WAL-consistent copy (`db`, `-wal` and `-shm` all copied), opened
read-only, and 591 media rows were checked. `Afsnit 7` / `Afsnit 8` are real library episode titles
and do appear here, on `Nordvest`: they are the generic Danish word for *episode* plus a number,
they name no programme, and they carry the design's own point — the source-incomplete row that has
no episode name to show. Round 2 landed them and its gate cleared them; left as drawn.

### Open at promotion, not fixed here

- **`ai.css` breaks the screenshot tool.** `.af-step.run .ic::after` spins on
  `animation: af-spin .8s linear infinite`, and an infinite animation is a canvas fence. It is round
  2's file, landed before this task and byte-identical in the export, and the repo guards do not
  look for this. Replacing a drawn loading state is a design decision, so it is reported, not
  redrawn.
- **`Activity › Support log` survives a missing AI key, and under the owner's rule it must not.**
  `app-shell.js` gates the *sidebar entry* correctly (`needsAiKey && !aiKeySet()`, lines 103 and
  244), but the `Support log` segment lives inside `activity.html`'s own `viewseg` switcher
  (line 140) and that switcher has no key gate at all — `activity.html` never mentions `aiKeySet`.
  Noted for promotion as instructed; not redrawn.
- **The no-key state for the support lane is not drawn**, and the paste block once promised for it
  is dropped (owner, 2026-10-15).
- **The composer carries no "an AI reads this" line** — checked across round 3's whole Ravilo half
  and found absent, so FR-321-11 needs nothing removed. The `AI Agent` label is present and is the
  only disclosure, per `ad7505a5`.
- **Still not promoted.** `ai.css` needs its `SCOPED_FILES` row and wrapper class, as round 2 noted,
  and promotion is a separate hand-written commit. **No phase number here either.**

### The write-back — 2026-10-15, approved and verified

The owner approved it on DEB-1246 at 08:30:41Z, one request covering both files, as his decision
specified. The push followed the four verified steps and **is proven, not assumed**:

| step | result |
|---|---|
| re-pull both files and hash, to re-prove the precondition | `app/ai.html` `3639f188` (11 642 B), `app/ai.js` `54e61f40` (15 321 B) — **byte-identical to the export**, so the studio had not moved since 09:43Z and the write clobbered nothing of his |
| `finalize_plan` | `localDir` scoped to **this quarantine directory**, writes exactly `app/ai.html` and `app/ai.js`, `deletes: []` |
| `write_files` by `localPath` | 2 written, bytes read from disk |
| re-pull both and compare | `app/ai.html` `121f46d7` (11 653 B), `app/ai.js` `633d69b7` (15 385 B) — **equal to the committed copies here**, both `truncated: false` |

`localDir` was pointed at `design/incoming/2026-10-14-admin-ai/` rather than `design/`, which is the
safer form of the write-back recipe: with the plan's local root inside the quarantine directory, a
mistyped `localPath` cannot reach a production `design/app/` file and push it to the studio. Worth
keeping for every future write-back — the studio and the production tree use the same relative
paths, so `design/` as `localDir` makes `app/ai.html` ambiguous between the two in exactly the way
that matters.

Nothing else in the studio was read, written or deleted, and no file was deleted anywhere. The
studio and this directory now hold the same two files; `ai.css` was not part of the write-back and
is untouched in both.
