# Round `auto-fetch-states` — Auto-fetch's missing states

**Revision 1, 2026-10-25.** Drawn in this repository. Brief is Bárður's on DEB-2483. The adopted spec
is `specs/requirements/auto-fetch-turns-a-series-page-into-a-paced-scheduled-download.md` at
`2c3c4f65`. Round 2's design stands and is not redrawn; this round promotes nothing.

**Drawn with the house design system (`design/SKILL-house.md`) and `design/PROCESS.md`.** The
`frontend-design` skill is now selected on this seat and was read with `SKILL-house.md` under it, as
the precedence says. It changed nothing here: this round extends a shipped page with the components
that page already owns, which is exactly the case where the shipped system wins outright. Its one
useful contribution was the discipline of critiquing the render instead of shipping it, which the
lane already mandates.

**88 PNGs** — 22 frames × 1440 and 412 × dark and light. Files are
`auto-fetch-states-<width>-<theme>-<state>.png`; the two forked frames are
`…-steps-mark-…` and `…-steps-rail-…`, so the pick comment can be built from the names.

## What is in the package

| | |
|---|---|
| `Auto-fetch States - Directions.html` | the directions doc: what is fixed, the one fork, every state, the decided rows, and all new strings with Danish and Faroese drafts |
| `frames.html` | the frame registry; it links `wf.css`, `app.css`, `dashboard.css` and `ai.css` rather than copying them |
| `af-states-data.js` | the one array every number in every picture comes out of |
| `af-states-build.js` | the frame builders — round 2's markup wherever round 2 drew it |
| `auto-fetch-states.css` | the new `af-*` components, the ≤640 px tiers, the light-theme twins |
| `png/` | the 88 renders |

## The question, answered

**A row says the *site* has stopped us** with one status slot and no new column: a `badge bad` reading
*Site stopped*, and a line under it naming the host, the short reason and the time. The point of
naming the host on the row is that nobody goes looking for a fault in one recipe. Opening the row puts
a band **above** the recipe and the run history — above, because what it says is true of every fetcher
on the site, not of the recipe below it — carrying the full reason, the time, *Nothing runs on
varpid.example.fo until you resume it*, **Resume site**, and *Run now* and *Re-investigate page* shown
disabled with their reason. No countdown anywhere: a stop is never retried.

**A row says *this needs your number*** with a **neutral** badge, not warn and not bad. Nothing is
broken and nothing was lost; an item is waiting for the admin. The open row gives the item, the page's
own description with where it came from, two empty number fields, **Propose** and **Accept** — each
disabled with its own reason when it cannot do anything.

**The review state shows the proof** as a row in the `af-kv` block under *Pattern*, with a chip
(*✓ proven* / *not proven*) and a sentence saying what an agreement is, so *12 of 12* is readable
rather than magic. A disagreement is not a review state at all: it is the failed state, its reason
naming the check, with the disagreeing rows listed under it.

**The running step is a filled accent square with its number** and *— now* on the label, against an
outlined circle for waiting, a tick for done and a cross for failed. Shape, fill and label, no motion
at all. The alternative is drawn and named; see the fork.

## The one fork, and the recommendation

| Option | Running mark | Gives | Costs |
|---|---|---|---|
| **`mark`** — *recommended* | circle → filled accent **square** with the step number, soft halo; label gains *— now* | reads in greyscale; **keeps the step number**; adds no element | quieter than a spinner in a five-row list |
| `rail` | filled disc with **▶**, a 3 px accent rail down the row, a *now* badge | unmissable from across a desk | the ▶ **takes the step number away** from the one row somebody is watching; louder row, one more element |

The deciding argument is the number. The five steps are referred to by number elsewhere on the page
(*the five steps' outcomes* in the bug-report line), and `rail` is the only option that removes it from
the running step.

## The open decisions, drawn

| | |
|---|---|
| 1 · Where **Resume site** sits | **On the open row only** (the lean). A site-wide action is worth one click of reading first. |
| 2 · Collapsed waiting row's own button | **Only the badge** (the lean). The row opens. |
| 3 · The still marker's shape | **Both drawn and named.** `mark` recommended. |
| §5c · *Tick all* | **Yes, as a ghost button** (the lean), beside the count in both tick states — no frame of its own. |

## Eleven defects found only by opening the renders

The four fences see none of these.

1. **The stop band's actions escaped the frame.** The icon spanned four grid rows and the band has six
   children, so *Resume site* and its reason were placed in column 1 and drawn half off the left edge.
2. **A bare `.badge` is invisible on its own.** `wf.css` gives `.badge` a transparent border and no
   fill, so the neutral *n new items waiting for a number* read as plain bold text in a status slot
   beside three coloured siblings. Drawn now as `.badge.flat` — which `processing.css` already needed
   for the same reason, so **this belongs in `wf.css`**, not in a third page file.
3. **`.af-tbl .acts { justify-content: flex-end }` pushed *Resume site* into the middle of the band**,
   because the band renders inside `tr.af-det td`. Renamed to `.af-acts`. A class collision is the cost
   of linking production CSS instead of copying it, and it is still the right trade — the collision is
   real in the real page too.
4. **The fetchers table kept five columns at 412 px.** This is round 9's documented failure exactly.
   Added the ≤640 px card-per-fetcher tier.
5. **The mapping table kept six columns at 412 px**, turning *matched by description* into a column one
   word wide. Added the ≤640 px row-per-mapping tier.
6. **The read-only recipe widened the page at 412.** `white-space: pre` in a `1fr` grid child has no
   min-width of its own, so *Save*, *Last runs* and the TMDB id were pushed off the right edge.
7. **The open row's band sat outside the card at 412**, because it is a sibling of `.af-det-in` and the
   padding lives on that. Both now sit inside one `.af-open` wrapper.
8. **The stopped row's tint drew as stripes at 412** once the cells became blocks; so did the kept-row
   tint in the mapping table. The tint belongs to the row.
9. **The season field was a `1fr` column and the episode field 96 px** — two fields of the same kind at
   wildly different widths. They are one flex row now.
10. **`HALLENGE`.** `c.id.replace(/^c/, '')` ate the leading letter of `challenge` as well as the one it
    was meant to strip. The causes carry an explicit key now.
11. **A waiting step is `--ink-dim` on the light theme**, which is `rgba(15,17,26,.25)` — under 2:1
    against the card. A waiting step is still a step somebody has to read; given a light twin.

Two more that were numbers disagreeing with each other rather than pixels: the collapsed row still said
`today 04:00` in the *refused again* frame while the band said `16:42`, and the run-history line said
*2 new items* beside a badge saying *3*. Both are derived from the one array now.

## Readings of the spec I made, which the build should confirm

1. **A stopped site disables *Re-investigate page* as well as *Run now*.** FR-2.5 names *Run now*; a
   re-investigation reads the site too, so it cannot run either. Drawn disabled with the same reason.
2. **The collapsed row keeps saying *Site stopped* while resuming.** The site is stopped until the one
   listing request succeeds, and the page draws what the server says. Only **Resume site** changes.
3. **A mapped review has no proof line of the FR-4.2 kind**, because FR-4.2's check is about `pattern`.
   The *Proof* row instead says that each mapping is proved by the row the admin ticks. If that reading
   is wrong, that one sentence is the whole change.
4. **The *n items waiting for a number* row needs nothing different for the not-proven cause**, as the
   brief asks me to confirm. The badge, the status line, the panel and the controls are identical; only
   the panel's lead sentence names the cause. Both are drawn (`waiting-open` and `waiting-three`).
5. **The Dashboard row's fix kind is *Open the item*, not *One click here*** — the reason is worth
   reading before the site is poked again. If **Resume site** should sit on the Dashboard row itself,
   that is a one-line change and a question below.

## Questions for Bárður

1. **The four-row frame needed a fourth fetcher and the corpus has three series.** Four row states need
   four rows. Rather than invent a name, `rows-all` draws a **second Royndin fetcher on
   `play.example.dk`**, and the duplicate name is deliberate: the stop belongs to
   `varpid.example.fo`, so the fetcher for the very same series on another site runs normally one row
   below, which is §1's own argument. It does raise a spec question — is **one series with two fetchers
   on two sites** a configuration Auto-fetch allows? FR-6.1's diff is per-`SxxEyy` in the series folder
   whoever placed it, so it works; FR-3.4/3.5 do not say. If the answer is no, the frame needs a fourth
   stand-in series name from you.
2. **The brief puts Nordvest on mapped numbering; `ai.js` has it on `S{nn}E{nn}`.** I followed the
   brief. Round 2's row reads *Mapped · 8 accepted* in this round's frames.
3. **`ai.js` carries two real-looking TMDB ids** — `118802` for Nordvest and `77410` for Babel Fish —
   against the brief's own rule that a new id is a placeholder from `9000002` up. This round uses
   `9000002` and `9000003`. The shipped `ai.js` is not mine to change and I have not; worth a look from
   whoever owns that file, because the repository is public and those two ids resolve to something.
4. **Should the Dashboard row carry *Resume site* itself?** Drawn as *Open the item* → *Fetchers →*.
5. **The *Accept* reason is drawn once per panel, not once per item.** Three items each repeating
   *Accept is off until a season and an episode are there* read as noise. If a per-item reason is
   wanted, say so.

## Nothing dropped from the inputs

No `STATUS.md`, nothing under `specs/`, `scripts/` or `.github/`, no `CLAUDE.md`, and no file that read
like it was directing an agent turned up in this round's inputs. Nothing was dropped.

## Canvas substitutions, stated

- **`--blur: none` on every frame.** `backdrop-filter` is production CSS (`wf.css` gives every `.card`
  `var(--blur)`) and the canvas rule forbids it because it breaks the screenshot render. The live admin
  keeps its glass; these pictures show the flat fill it resolves to.
- **`.af-step.run .ic::after`'s `animation: af-spin .8s linear infinite` switched off**, which is §6's
  whole subject. No frame in this round animates, and neither option uses even a one-shot transition.
- The shipped aurora wash is `position: fixed` on `body::before` and cannot be scoped to a frame; drawn
  as the same two radial stops, static, no filter.

## Coverage against the house state list

Drawn: **error** (four of them), **the longest string** (Faroese, in `stopped-reasons`), **Faroese**
(the four stop reasons rendered; every other new string drafted in the directions doc). Skipped and
why: **loading** and **empty** are round 2's and this round adds no surface that loads on its own;
**offline** is `ai.html`'s own `.ai-offl` strip, which dims *Resume site*, *Propose* and *Accept* along
with everything else and needs no new drawing; **10-foot focus** does not apply to an admin surface.

## What the `ai.css` promotion will have to carry

Listed in full at the foot of the directions doc so it does not have to be re-derived. In short: delete
`@keyframes af-spin` and the rule that uses it; add the new `af-*` rules and their `[data-theme="light"]`
twins with a line in `check-mobile-css.sh`; move `.badge.flat` into `wf.css`; and take the ≤640 px tiers
for `.af-tbl`, `.af-map` and `.af-kv`, the `.af-det-in > * { min-width: 0 }` fix and the `.af-open`
wrapper along with them.
