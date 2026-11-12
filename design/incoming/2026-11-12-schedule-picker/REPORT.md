# Round `schedule-picker` — revision 1, 2026-11-12

Drawn in this repository. The brief is Bárður's, on the round's task. The admin only, on a desktop
browser: no Ravilo client, no TV, no remote, no device.

**28 PNGs** — 14 frames, each in both themes. One control, built once, shown in its two homes across
the brief's seven states.

Drawn with the house design system (`design/SKILL-house.md`) and `design/PROCESS.md`.
`frontend-design` was read with them. It changed one thing I would single out: *spend your boldness
in one place*. This control has five things competing to be the loud one — the accent on the chosen
weekdays, the accent on the month-shape seg, the red on a refusal, the amber on the look-ahead note,
and the server's sentence. The sentence is the only one that is always there, so it is the only one
in full `--ink`; everything else is a tint or an edge. Nothing else in the skill disagreed with the
house file.

## What the round draws

| | |
|---|---|
| **The question** | `shapes` — the three candidate shapes, all at 280 px, in the same state |
| **Auto-fetcher side panel** | `panel-weeks` · `panel-floor` · `panel-off` · `panel-cron` · `panel-menu` |
| **Settings ▸ Processing row** | `row-weeks` · `row-fortnight` · `row-lastfri` · `row-decade` · `row-cron` · `row-off` |
| **Length** | `longest-narrow` · `longest-wide` |

Each name carries `-light` where it applies, so whoever builds the owner's comment can tell which
picture is which without opening it.

## The one decision I am asking you to carry

**Shape: `Lines`.** Three were drawn at the width that decides it, and the picture is `sp-shapes`.

The argument that settles it is not in the panel, it is in `row-weeks`: **the lines wrap**, so the
same markup is three lines side by side in a 1100 px feature row and six stacked lines in a 280 px
panel, with nothing choosing between them but the width. That means **one control and no narrow face
to build** — which is exactly the thing `processing.css`'s own header comment records it *could not*
do for the Scan dialog and the Schedule table, where the narrow face is markup and the frontend has
to pick it. FR-3 asks for one control built once; this is the shape that lets that be literally true
rather than true-with-an-exception.

`Sentence` lost on its own picture: at 280 px it wraps mid-phrase, the wrap point moves with the
chosen unit, and the weekday toggles cannot live in running text. `Summary and a sheet` lost on the
requirement: the sentence and the next five runs are the whole of FR-3.8, and behind a sheet they
are not on the page while the choice is being made.

## Five decisions worth your sentence

1. **The foot never collapses, in any state.** Both halves are drawn for *Off* and for both
   refusals. A control that changes height as its own mode is chosen moves under the hand using it.
2. **A refused schedule shows no runs at all** — not the last valid schedule's times. Stale times
   under a refusal are a lie about what the job will do, and they are the easy thing for an
   implementation to do. `panel-floor` and `panel-cron` are the pictures to point the engineer at.
3. **Months ask *a day or a weekday* first**, as a two-cell `.seg`. The two answers need different
   controls; offering both at once is four selects for one choice.
4. **The unit word follows N**, in the closed select *and* in the open menu, so the two cannot
   disagree. This is a small departure from the spec's literal text, which writes the menu as
   *[hours ▾ | days | weeks | months | years]*: at N = 1 the menu reads *hour / day / week*. Every
   calendar does this, and the alternative is a select reading *week* over a menu reading *weeks*.
5. **The classes belong in `wf.css`, not in a page file.** Two page files need the control today —
   `ai.css` (`.afx`) and `processing.css` (`.pfx`) — and FR-5 adds four more settings on two further
   pages. A component two page files need is the shared system's, which is the argument wf.css's own
   `.badge.flat` comment already makes. So the rules are written without a wrapper class. One line
   to move it either way if you disagree.

## One live defect the pictures found

Not this round's work; it ships today, in both homes.

**A time field clips in a 12-hour locale.** `AutofetchHtml.kt:223` draws the auto-fetcher's
`type="time"` at `width:100px` and `SettingsProcessing.kt` draws Find's at `112px`. That fits
`04:00`; Chromium renders a 12-hour locale as `08:00 PM` plus a picker button, about 134 px, so the
field cuts its own value and its own button in half. The round draws it at **138 px**. This is the
same defect round 10 found in `SettingsProcessing.kt` and reported — it is still there, and it is
now in two files rather than one.

Add a line to `scripts/check-mobile-css.sh` for it when the control is promoted: it is a
hand-authored fix in a design-managed file, and an unlisted fix is one copy away from vanishing.

## Six defects found only by opening the renders

None of the four fences can see any of these.

1. **A picture contradicted itself.** The first `longest-narrow` had **all seven weekdays toggled
   on over a list of five Wednesdays**. The cause is worth more than the symptom: the length cases
   were built by patching a copy of a state *inside the builder*, which went round the data file's
   assertions entirely. They are now **states in `sp-data.js`**, asserted like every other one — and
   the assertion that every fire lands on a weekday that is toggled on is what would have caught it.
2. **The refusal drew as two separate red boxes.** It was a `.badge`, every `.badge` in `wf.css` is
   `white-space: nowrap`, and forcing an inline bordered element to wrap gives *each line fragment*
   its own border and fill. At 280 px the 92-character refusal came out as two boxes with a gap,
   which reads as two problems. Same class as round 11's one-outline-drawn-as-two. It is now a block
   note with a single `--bad` edge, the shape `processing.css` already uses.
3. **`Repeat every 1 weeks`**, in three frames.
4. **The open unit menu ran off the panel and cut `Custom (cron)` in half** — the one entry that
   frame exists to show. I had overridden the shipped `.menu`'s `right: 0` to `left: 0`; the unit
   select sits at the right-hand end of its line, so the menu grew off a 280 px panel. Reverted to
   wf.css's own value, which was right all along. Round 8's class exactly: a surface covering the
   very thing its own caption pointed at.
5. **Two adjacent on-days merged into one block — visible only at 4×.** A `.seg` divider is
   `--line`, which disappears into `--hi-soft`. In a single-select seg that never shows, because
   only one cell is ever on; in a multi-select one it is the whole difference between *Monday and
   Tuesday* and one wide cell. Fixed with `--line-2`, which is themed, so it holds in both.
6. **`NEXT FIVE RUNS` stood over `Nothing scheduled.`**, which reads as a broken list rather than an
   answer. The heading is now *Next runs* when the slot is explaining that there are none. The slot
   itself still always stays.

## What I changed from the brief, and why

**One, and it is the brief's own approximation against the shipped code.** The brief says the
auto-fetcher panel is *"~280 px wide"*. The shipped lane is not a fixed 280: `ai.css:129` makes
`.af-det-in` a `minmax(0,1fr) 340px` grid and the schedule control sits in the **first** column, in
an `.af-cfg` flex-wrap row beside `.af-keepf` at 262 px. So the control's real width is
window-dependent and usually wider than 280. **I drew the narrow home at exactly 280 anyway**,
because it is the honest floor — if the control holds there it holds in the real lane — and because
drawing a window-dependent width would make two rounds impossible to diff. Worth a line in the spec
if you would rather it named a floor than an approximation.

## Open questions

1. **The ten-year anchor is the sharpest thing in the round.** `row-decade` is the brief's state 4:
   the person chose *every 10 years, starting 2026*, and the next five runs are **2036, 2046, 2056,
   2066, 2076**. The 2026 they just typed is not in the list, because 1 Mar 2026 is eight months
   behind today and the anchor is where counting *starts*, not when running starts. That is correct
   and it is also the one place in the round where the control can be read as having ignored an
   instruction. I drew **a note saying it in words**, because a preview that silently disagrees with
   the field above it is worse than a long one. What I have not decided, and should not:
   - whether the note is the right answer at all, or whether *starting* should **default forward**
     to the next matching date (FR-3.6 says it defaults to "the next date that matches" — which
     would have made this state impossible to reach by default, and the brief asks for it
     explicitly, so the two are in tension);
   - whether the server should offer the note's wording, like it offers the sentence. Drawn as a
     client string, which is the one string in the round that is not the server's.
2. **The spec's acceptance line for that state reads** *"previews 2026, 2036, 2046 and so on"*.
   Against a clock of 12 Nov 2026 the server cannot offer 2026. The spec line is the series, not the
   preview; worth a word so the engineer does not write a test that can only pass before March.
3. **Save in the Processing home is out of frame.** FR-3.8 holds Save while the schedule is
   invalid; in the panel that is the panel's own Save, drawn held in `panel-floor` and `panel-cron`.
   In a Processing feature row the Save is the page's, at the foot, so `row-cron` shows the refusal
   and the row's *Not saved · the schedule is refused* line but not the held button. That is the
   page as it is, not a gap in the control — but if the page's Save should also say *why* it is
   held, that is a second round's worth of a sentence and not mine to decide.
4. **`Last day` and *day 31* are in the spec and not in a picture.** The brief's seven states do not
   include them and I did not add an eighth; the month-day menu is drawn with its day half in
   `row-lastfri`'s seg, but the *Last day* entry itself is only in the strings table. The spec's own
   acceptance (*day 31 skips November, Last day does not*) is a backend case with no new surface. Say
   if you want it drawn.
5. **The weekday toggles are a multi-select wearing `.seg`'s clothes.** That is new behaviour for a
   shipped appearance. The alternative house vocabulary is seven `.chip.sugg`, which is genuinely
   multi-select but is 7 chips × ~44 px and does not fit 280. Drawn as the seg; worth your eye.

## The fences

Run on the **staged** directory, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked tree. All four green: `check-deanonymization.sh`,
`check-mobile-css.sh`, `check-css-scoping.sh`, `check-phases.sh`. Neither was piped through anything.

Every name and value is the brief's own stand-in: the auto-fetcher **Evening News**, the processing
features **Find new & changed** and **Music**, and `example.net`. No library title, no filename, no
real host, no IP literal, no `http`/`https` reference, no CDN, no hosted font. `sp-data.js` asserts
all of that over its own data as well, because the fence reads the tree and the data is what the
pictures are built from. No `content-visibility`, no `backdrop-filter` in any rule, no
`filter: blur`, no animation of any kind.

**No `STATUS.md`, no `CLAUDE.md`, nothing under `specs/` and nothing under `scripts/` was created or
touched.** Nothing is promoted, no production file is modified, no spec is written, and **no phase
number, FR id or task id appears in any frame or in any file name.** The FR ids in this report and in
the directions document are references in prose, which is what they are for.

## The frames link the production CSS

`frames.html` links `../../app/wf.css`, `../../app/app.css`, `../../app/processing.css` and
`../../app/ai.css`, so every token, card, button, badge, toggle, select, input, menu, feature row and
auto-fetcher panel in these pictures is the shipped one, and the fonts resolve from
`design/app/fonts/` with no copy and no network reference.

**One canvas substitution, and it is the usual one**: each frame sets `--blur: none`, because
`wf.css` gives every `.card` `backdrop-filter: var(--blur)` and the canvas rule forbids it. The live
admin keeps its glass.

New classes were checked for collisions against `wf.css`, `app.css`, `processing.css`, `ai.css`,
`dashboard.css`, `suggestions.css`, `music.css`, `support.css`, `versions.css`, `editions.css`,
`detail.css`, `metadata.css`, `seeding.css` and `segments.css` before being used: **no `.sch-*`
exists in any of them.** `processing.css` owns 23 rules on `.sched`, which is a different class and
no selector here can reach it.

## The render

```sh
R=design/incoming/2026-11-12-schedule-picker/frames.html
scripts/render-design.sh --html $R --viewport 1280x1000                        --all .frame --prefix sp-
scripts/render-design.sh --html $R --viewport 1280x1000 --query '?theme=light' --all .frame --prefix sp-
```

`scripts/render-design.sh` is the engineer's permanent script; this round wrote no `render.mjs` of
its own and nothing under `scripts/`.

## States

**Drawn:** all seven of the brief's states · the unit menu open, showing *Off* first and
*Custom (cron)* last · both homes for the states that have both · the control's two refusals, one
from a cron and one from a setting's own floor · the longest sentence, the longest refusal and the
longest zone name at both widths · every frame in both themes.

**Skipped, each by name:**

- **Loading** — the control's first paint is the setting's, and the settings pages already draw
  their own skeletons; this round adds no new one. The *sentence and next five runs waiting for the
  server* case is real and is not drawn: it is the foot in a pending state, and it is one frame if
  you want it.
- **Empty** — a schedule picker has no empty; *Off* is the nearest thing and it is drawn.
- **Danish and Faroese** — the admin ships English only, so there is no `i18n` string to agree or
  disagree with. The new strings are listed flat in the directions document for whenever that
  changes.
- **10-foot focus** — no TV surface in this round.
- **Offline / lost connection** — the foot is server-pushed, so a dead socket means no sentence and
  no runs. The admin's existing page-level banner owns that, and this round does not change it.
  Worth knowing that it is the one state where the foot is empty for a reason that is not the
  schedule's.
- **Phone width (412)** — the brief says desktop browser. The control holds at 280, which is
  narrower than 412 minus the page's padding, so the narrow face is drawn; I have simply not drawn
  it inside a phone frame.
