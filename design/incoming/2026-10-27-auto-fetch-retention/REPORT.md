# Round `auto-fetch-retention` — report

**Revision 1 · 2026-10-27 · Rannvá.** Brief from Bárður on the round's task: the AI page's Fetchers
tab gains a **page-only** fetcher and the **Keep** (retention) control, from the 2026-10-27 amendment
to `specs/requirements/auto-fetch-turns-a-series-page-into-a-paced-scheduled-download.md` (FR-4.5,
FR-10.6, FR-15, FR-16). An addendum: round 2's page and the states round before this one both stand
and neither is redrawn.

## What came home

| | |
|---|---|
| `frames.html` | the frame registry; renders every state from the data file |
| `af-keep-data.js` | **one array per fact.** Every number, code, date and count in every picture comes from here |
| `af-keep-build.js` | the builders |
| `auto-fetch-retention.css` | the canvas, and the new `af-*` components for promotion |
| `Auto-fetch Retention - Directions.html` | what is fixed, the one direction and its costs, every state, decided rows, the string table, the open questions |
| `png/` | **44 PNGs** — 11 states × 1440 and 412 × dark and light |

Every frame links `wf.css`, `app.css` and the production `ai.css` and is wrapped in `.afx`, so every
card, badge, button, seg, input, toggle, table, tick, proof line and status cell in the pictures is
the shipped one. Nothing is copied.

## The new components, all `af-*`, all for promotion into `ai.css`

| class | what it is |
|---|---|
| `.af-keep` / `.af-num` | the Keep control: a two-option seg with the number inside its own option |
| `.af-opt` | a tick with a sentence beside it, over the shipped `.af-tick` glyph |
| `.af-prev` | the delete preview — the warn line FR-16.7 says replaces a dialog |
| `.af-err` | a refused field: its ring, and the refusal under it |
| `.af-runx` / `.af-sub` | a run and its own lines as one record: one line per placement, deletion and guard |
| `.af-tbl .sched .keep` | *keeps newest 12*, under the schedule — no new column |
| `.af-echo.page` | the two-line echo a page-only fetcher needs |
| `.af-map .se .d` | the date beside the code in *Becomes* |
| `.af-map.no-tick` | a mapping table with no accept-tick gutter (see finding 3) |
| `.af-cfg` / `.af-form` | two alignment corrections to shipped rules (findings 2 and 4) |

`.af-strings`, `.af-strings-col` and `.af-lang` are **canvas only** and are not promoted: they exist
so the longest-string frame can draw each Keep control at its real 300 px width.

## Findings — things the pictures showed, not the markup

**1 · The brief's run record cannot exist as one run.** It asks for *a run record holding a +1 line,
a −1 line and one guard line*. FR-16.5 says a run whose listing could not be read in full **applies
no retention at all, neither rule** — so the run carrying FR-16.7's guard line cannot also carry a
`−1`. Drawn as **two runs**, which also makes them explain each other: yesterday's partial read is
exactly why today's deletion is a *newest 12 kept* one and not a *left the page* one. No string was
invented to make this work; FR-16.7's guard line is verbatim.

**2 · `.af-form`'s `align-items: end` breaks with a hint taller than one line.** The page-only hint
is three lines, and bottom-aligning against it lifted the **TMDB id input clear of the Page URL
input beside it** — two inputs on one row, visibly out of line, in the first render. Top-aligned they
match and the button clears the label row by the label's own height. This is a **shipped** rule and
the correction belongs with the promotion.

**3 · A mapping table with no ticks indents every row against an empty gutter at 412.** `ai.css`'s
≤640 tier gives every `.af-map tr` a 30 px left padding for the accept tick. Only `mapped` numbering
has those ticks, so **today's shipped Royndin review state** already draws that empty indent at phone
width. One modifier class fixes it; it is in this round's CSS as `.af-map.no-tick` and should go into
the promotion whether or not anything else here does.

**4 · `.af-cfg` bottom-aligns, and Keep is three lines taller than a field.** In the open row that
dropped *Name* and *Check* to the floor of the row with nothing above them. Top-aligned, the three
fields read as three fields and the buttons take a line of their own.

**5 · Three tick labels rendered in small uppercase bold.** `wf.css` styles every `.field label` that
way, and a tick's label is a sentence. `ALSO DELETE AN EPISODE ONCE IT LEAVES THE PAGE`, three times
per frame, in the first render. No fence can see this; the PNG showed it immediately.

**6 · `open-runs` first rendered pixel-identical to `open-rest`.** The run record is part of the open
row and was already in both of the other two open-row frames. It became the **close-up of the run
record**, where its lines can be read at 412 as well, rather than a third copy of the same picture.

**7 · The Faroese *The newest* wrapped inside its own option at 412.** `Teir nýggjastu` took two
lines and made that option a line taller than the other. The draft drops the article — `Nýggjastu`,
still *the newest* — and the three languages now draw alike. **A speaker confirms or overrules it.**

## The corpus, and why its numbers are what they are

Three of the brief's numbers have to agree with each other, and only one arrangement makes all three
exact: the run record deletes **exactly one** episode and it is `S2026E286 · 2026-10-13`; lowering
Keep 12 → 8 deletes **exactly four**; and the hint reads *Keeps 12 of the 12 on the page*.

So Dagbók's placed rows just before today's 04:00 run are twelve — `S2026E286`, then `S2026E289` →
`S2026E299`. Today's run places `S2026E300`, which makes thirteen, so the newest twelve are
`S2026E289` → `S2026E300` and `S2026E286` falls out. From then on the folder is **exactly** the
twelve the page lists, which is what makes *12 of 12* true rather than coincidental.

The gap at 2026-10-14 and 2026-10-15 is deliberate and spec-supported: those two were placed and then
removed by hand, so FR-16.2 released their rows (*when the recorded path no longer holds a file, the
row is released and nothing is deleted*). It is the one way a daily programme's folder legitimately
has a hole in it, and it is why the deleted episode can be 2026-10-13.

`af-keep-data.js` **asserts all of this before anything is drawn**, and throws if any of it stops
being true — a loud failure rather than a wrong number in a PNG. `S2026E289` and `S2026E300` are
computed day-of-year, never typed. No date is formatted by the browser: Chromium has no Faroese
locale and falls back to English in silence, so the month name is tabled.

## Canvas substitutions

**One, and it is the usual one:** `--blur: none` inside a frame. `backdrop-filter` **is** production
CSS — `wf.css` gives every `.card` a `var(--blur)` — and the canvas rule forbids it because it breaks
the screenshot render. The live admin keeps its glass; these pictures show the flat fill it resolves
to. There is no second substitution: nothing this round draws animates, in either file.

## Nothing was dropped from the inputs

The brief carried no `STATUS.md`, nothing under `specs/research-reports/`, nothing under `scripts/`
and no `CLAUDE.md`. Nothing in it read as instructions to an agent. The round commits design files
only, and carries no phase number and no task id in any drawing.

## Open questions, all five, in the directions doc as well

1. **Does FR-10.6's Keep hint belong on the review state as it stands?** Its second sentence is a
   statement about future runs, and a fetcher being reviewed has placed nothing, so nothing can be
   deleted when it is saved (FR-16.2). Drawn with the fixed string unaltered and one line beneath it
   saying so. Either that line stays, or FR-10.6 gains FR-16.4's words for the review state.
2. **On a TMDB fetcher choosing *The newest*, is *leaves the page* on or off by default?** FR-16.1
   does not say. **Drawn off** — a TMDB series' back catalogue scrolling off a page is not evidence
   the household is done with it.
3. **Does a list row need to say *page only*?** Drawn without: the *Numbering* cell reads
   `Date · S{year}E{day of year}`, which no TMDB fetcher can show. A `badge flat` beside the name is
   the alternative and costs one more thing on every row.
4. **Activity ▸ History has no drawn entry for a fetcher.** `activity.html` is the scan console.
   FR-11's entry needs a surface and inventing one here would be drawing a screen nobody briefed, so
   the frame draws the entry's **words** in the run record's own components. Is the surface a round of
   its own?
5. **Should the preview name the four episodes, not only count them?** FR-12's route returns what
   saving would delete, so the server could hand over the codes. Drawn as the count alone because that
   is the sentence the spec fixes.

## For the promotion

The round promotes into `ai.css` by hand, under its `.afx` wrapper, as the lane's gate requires:
every `.afx .af-…` rule in `auto-fetch-retention.css` **except** the canvas block and
`.af-strings` / `.af-strings-col` / `.af-lang`. Findings 2, 3 and 4 are corrections to rules already
in `ai.css` and are the only edits in the set that change a shipped line rather than add one; each
one gets a line in `scripts/check-mobile-css.sh`, which is what would notice them going missing
again. `ai.css` already has its `SCOPED_FILES` row and its wrapper class, so nothing new is needed
there.
