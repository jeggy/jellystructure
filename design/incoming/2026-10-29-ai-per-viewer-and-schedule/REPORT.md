# Round `ai-per-viewer-and-schedule` — revision 1, 2026-10-29

Drawn in this repository. The brief is Bárður's, on the round's task. The admin only: no Ravilo
client, no TV, no remote.

**38 PNGs** — 13 frames at desktop (1180, which is what a Settings card really is in a 1440 window
once the 248 px sidebar is taken off) and 6 at 412, each in both themes.

Drawn with the house design system (`design/SKILL-house.md`) and `design/PROCESS.md`.
`frontend-design` was read with them and changed nothing I would single out: everything it is
genuinely for here — hierarchy, restraint, spending boldness once — the house file already says, and
the one place this round spends its accent (the summary's `AI:` clause, and only when it is about to
cost money) is argued from the product and not from the skill.

## What the round draws

Four places, one direction each, extending surfaces that already ship.

| | |
|---|---|
| **AI ▸ Re-rank, the viewer list** | `viewers` · `viewers-long` · `viewers-states` · `viewers-off` |
| **Settings ▸ Processing** | `set-recs` · `set-modes` · `quiet-shapes` |
| **The Scan dialog** | `dlg-none` · `dlg-ai` · `dlg-limit` · `dlg-parts-412` · `toast` |
| **Activity ▸ the AI card** | `act-ai` |
| **Length** | `longest`, at both widths |

Each name carries `-412` and `-light` where it applies, so whoever builds the owner's comment can
tell which picture is which without opening it.

## The five decisions worth your sentence

1. **Every row says which default it is on**, not only the off ones. FR-1.2 asks that the default is
   never silent; a reason drawn only on `off — test account` reads as those rows being special
   rather than as one of two defaults. So `on by default` is drawn too, and a row with a *stored*
   choice says when it was made instead.
2. **A long list folds rather than scrolls.** 14 rows and one fold that says what is inside it and
   that every one of them is off by the rule. Not a scrollbox: that is a second scrollbar inside a
   page that already scrolls, and in this round's own first render it clipped the long list at row
   five — so the fold, which is the whole point of that picture, was not in the picture.
3. **The quiet choice is a select with the chosen option's sentence**, which is exactly what
   *When a subtitle does not fit* already is, two rows up the same page. The seg was drawn and lost:
   `quiet-shapes` has both at the 300 px the control really gets.
4. **The summary is a band above the foot, not a line inside it.** At full tick it is three clauses
   and about 190 characters; in the foot it squeezed *Cancel* and *Start scan*, and at 412 it pushed
   them off the bottom. `dlg-parts-412` is the frame that proves it.
5. **The *and ask AI* tick is always shown**, in all three cases — live, disabled because the part
   is not in this scan (*Tick Suggestions first*), disabled because the limit leaves too little.
   Hidden, the part changed height as its own tick was pressed and the line saying what the AI would
   have cost went with it. This extends the owner's shown-and-disabled rule from a *feature* to a
   *tick*, which is my reading of it rather than his decision.

## Three live defects the pictures found

None of these is this round's own work. All three ship today, none is visible in the markup, and all
three are fixed in the round's CSS so they are picked up with everything else.

1. **A time field clips in a 12-hour locale.** `SettingsProcessing.kt` draws every `type="time"` at
   `width: 112px`. That fits `20:00`; Chromium renders a 12-hour locale as `08:00 PM` plus a picker
   button, about 134 px, so the field cuts its own value and its own button in half. The round draws
   it at 138 px.
2. **The schedule check overflows at 412.** The server's description is put inside a `.badge`, and
   `wf.css` gives every badge `white-space: nowrap`. The longest description the server returns is
   58 characters, so at phone width the badge runs off the right edge of the card and the sentence is
   cut mid-word. The round lets that one badge wrap — a badge is for a status word, and this is a
   sentence.
3. **Activity's job rows are unreadable at 412.** `.jobrow` has no narrow tier at all, so the
   controls keep their columns and leave the text about 40 px: *queued by the schedule* became one
   word per line with the chip drawn over it. Same shape as round 9's finding 7, in a card that
   round did not touch. The round wraps the row.

Add a line to `scripts/check-mobile-css.sh` for each of the three when they are promoted — each is a
hand-authored fix in a design-managed file, and an unlisted fix is one copy away from vanishing.

## Nine more defects found only by opening the renders

The fences see none of these.

1. **A picture contradicted its own ticks.** `dlg-limit` had both parts ticked and a summary reading
   *Skips: … Suggestions*. It was given its summary by hand. The summary is now **derived from the
   ticks** in the builder, so it cannot disagree again.
2. **The summary named five of the dialog's ten parts** while the left pane showed the other five
   ticked — the brief's example line, printed verbatim into a fuller dialog. Caught by an assertion
   written for defect 1. See *What I changed from the brief*.
3. **`RE-RANK WITH AI` broke to two lines** over a 44 px control and read as two column heads. The
   switch column is 92 px.
4. **The long list was clipped at row five** by a `max-height` (decision 2).
5. **`Saving…` replaced the switch**, so the control vanished while it waited and the row jumped.
   The switch stays, dimmed.
6. **"Ran 51 min ago" broke across two lines twice** — first between *51* and *min*, then between
   *min* and *ago*. Both gaps are non-breaking.
7. **`Skips:` ended a line with its own list on the next one.** The three clauses are each their own
   box now, and the bold labels are the separator; there is no middle dot left to dangle at a wrap.
8. **Three things were set in `--ink-dim`** — the column heads, each row's default reason, and the
   two *nothing here yet* values — which is about 2:1 on the light theme's white card. A default
   that cannot be read is a default that is still silent. All three have a light twin.
9. **The fold's sentence was squeezed beside its button at 412** into a three-line column, and
   **the two shapes in `quiet-shapes` were drawn with different label treatments**, which made the
   comparison the frame exists for unfair. Both fixed.

## What I changed from the brief, and why

Two places. Both are the brief's own words disagreeing with the brief's own facts, so neither is a
gap I filled by invention — but both are yours to confirm.

1. **The summary line.** The brief gives it exactly: *Runs: Find new & changed, Metadata & artwork,
   Recommendations · Skips: Intro & credits, Suggestions · AI: none*. That names five of the ten
   tickable parts the dialog has, and the left pane in every one of these pictures shows the other
   five ticked — so printing it verbatim made each picture contradict itself. **Its shape, its three
   clauses and its AI wording are kept exactly**; the two lists are extended to name what the
   picture actually ticks. `aiv-data.js` now asserts that every summary names all ten, once each.
2. **The stand-in count.** The brief says *"Show 4 people plus 5 test accounts"*, and its own named
   list is 5 people and 4 test accounts — Gestur is `on`, so he is not a test account. **The named
   list is what is drawn**, all nine rows, in the brief's order and its own words.

A third, smaller one, for the record: the brief's *4 viewers* and the list's *5 switched on* are
both true at once and the round draws both. Four have been seen on a Ravilo device; Gestur never
has. Every sentence that counts *viewers* counts four, and the list's own line counts five switched
on. `aiv-data.js` asserts that relation rather than trusting it.

## Open questions

1. **Where the new classes go.** The brief says `wf.css`. `vw-*` (the list) and `aiq-*` (the one
   Activity line) belong there — Settings has no page stylesheet of its own. `qv-*` and `sd-*`
   belong in `processing.css` under its `.pfx` wrapper, because that file owns both surfaces, and a
   bare `.sd-does` in the system file would sit in the cascade of every page while the thing it
   styles only ever exists inside `.pfx`. Drawn that way; one line to move either way.
2. **`Remove all 37 waiting` has no confirmation.** The spec names one control and no confirmation;
   *Empty queues*, one card away on the same page, has one for the same shape of action. Drawn as
   the spec says. If a confirmation is wanted it is a `.pf-confirm`, which already exists.
3. **`qa-deb1000-a1b2c3`** carries a task-shaped number into a drawing, which invariant 5 is about.
   It is the brief's exact stand-in and it is what a real QA username looks like, so it is drawn as
   given rather than quietly changed.
4. **The viewer list's filter and tabs are view controls** over a server-sent list. Every count in
   them is the server's, so nothing is derived — but they are the only controls in the round whose
   effect is not a server round-trip, and that is worth your eye.

## The fences

Run on the **staged** directory, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked tree. All four green: `check-deanonymization.sh`,
`check-mobile-css.sh`, `check-css-scoping.sh`, `check-phases.sh`.

Every person, name and value is the brief's own stand-in — Anna, Bjarni, Dávur, Elin, Gestur,
Test Stream and the three `qa-…` accounts — plus five more `qa-…` names built from the same pattern
for the long list, and one long `qa-…` name for the length test. No library title, no filename, no
host, no IP literal, no `http`/`https` reference, no CDN, no hosted font. No `content-visibility`,
no `backdrop-filter` in any rule, no large `filter: blur`, no animation of any kind.

**No `STATUS.md`, no `CLAUDE.md`, nothing under `specs/` and nothing under `scripts/` was created or
touched.** Nothing is promoted, no production file is modified, no spec is written, and no phase
number, FR id or task id appears in any frame.

## The frames link the production CSS

`frames.html` links `../../app/wf.css`, `../../app/app.css` and `../../app/processing.css`, so every
token, card, button, badge, chip, toggle, select, tick, feature row, dialog pane and toast in these
pictures is the shipped one, and the fonts resolve from `design/app/fonts/` with no copy and no
network reference.

**One canvas substitution, and it is the usual one**: each frame sets `--blur: none`, because
`wf.css` gives every `.card` `backdrop-filter: var(--blur)` and the canvas rule forbids it. The live
admin keeps its glass.

**One thing is drawn differently from production on purpose**: the Scan dialog is drawn without its
scrim. `.pfx.pf-scrim` is `position: fixed` and would escape a frame that has no viewport of its
own. The dialog itself is the shipped `.pf-dlg.wide`, unchanged.

New classes were checked against `wf.css`, `app.css` and `processing.css` for collisions before
being used: `vw-*`, `qv-*`, `sd-does`, `sd-ai`, `sd-sum`, `aiq-*`, and the canvas-only `aiv-*`.
None of them exists in any of the three.

## The render

```sh
R=design/incoming/2026-10-29-ai-per-viewer-and-schedule/frames.html
scripts/render-design.sh --html $R --viewport 1280x1000                          --all .frame --prefix aiv-
scripts/render-design.sh --html $R --viewport 1280x1000 --query '?theme=light'   --all .frame --prefix aiv-
scripts/render-design.sh --html $R --viewport 412x900   --query '?w=412'         --all .frame --prefix aiv-
scripts/render-design.sh --html $R --viewport 412x900   --query '?w=412&theme=light' --all .frame --prefix aiv-
```

`scripts/render-design.sh` is the engineer's permanent script; this round wrote no `render.mjs` of
its own and nothing under `scripts/`.

## States

**Drawn:** the list at 9 and at 48 users · a row switched off and what it left behind · a row
waiting for the server · AI off for the installation, every switch shown and disabled with its
reason · the list's first paint · both pickers at the brief's values · all five picker modes, the
new one included · a cron the server refuses · the quiet choice in all three options across the
round · the dialog with one part ticked and one not · with the AI asked for · with both AI ticks
refused by the limit · the 412 drill-in · both toasts · the AI card with 37 waiting · the longest
strings at both widths · every frame in both themes.

**Skipped, each by name:** Danish and Faroese (the admin ships English only) · 10-foot focus (no TV
surface) · offline (drawn for Activity by the `processing-features` round and unchanged by anything
here) · empty (a list of every Jellyfin user is not empty while anyone can sign in) · a failure of
the list's own (this round adds no new failure; the page's existing banner stands).
