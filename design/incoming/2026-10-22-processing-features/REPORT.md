# Round 9 — processing as nine features (`processing-features`)

**Revision 1, 2026-10-23.** Drawn in this repository. Brief and corrections are Bárður's on
DEB-1675; the owner's four picks are his answers on DEB-1664, 2026-10-23. Round 1 is `62dbcd6e`.

**Drawn with the house design system (`design/SKILL-house.md`) and nothing else.** The owner's card
on DEB-1076 about adding `frontend-design` beside it has not come back, so it played no part again
and there is still nothing to report about what it changed.

## What this revision did

1. **The two-pane dialog became the direction and was drawn in every state the brief names.** It was
   round 1's *alternative*, drawn in two frames; the owner picked it, so it now carries nine frames
   (five desktop, three phone, plus the scoped face) instead of two.
2. **The eight corrections from the round-1 review**, applied wherever each one appeared rather than
   only where the review happened to spot it. Three of them turned out to appear in more than one
   place — see the table below.
3. **The three unchosen directions were removed from the render set**: `set-alt`, `act-alt`,
   `dlg-alt` and `dlg-phone-alt`. They stay in `62dbcd6e`'s history, which is where an unchosen
   direction belongs.

**34 PNGs** — 25 desktop at 1440, 9 phone at 412. Round 1 had 35.

## Frames that changed since `62dbcd6e`

| Frame | |
|---|---|
| `dlg-desktop` | **redrawn** — two panes, everything ticked, File health selected |
| `dlg-music` | **new** — Music · 5 of 7, selected, both unticked parts visible |
| `dlg-off` | **redrawn** — the off feature is now *selected*, so it is inert in both panes |
| `dlg-rescan`, `dlg-scoped` | **redrawn** in the two panes |
| `dlg-phone` | **redrawn** — step one, the feature list, with the drill-in chevron |
| `dlg-phone-parts` | **new** — step two, Music's parts, with the back control |
| `dlg-phone-partly` | **new** — step one with Music · 5 of 7 |
| `act-now`, `act-cancel`, `act-offline`, `act-now-phone` | four queue-work features, not six; the card renamed; no lane claim |
| `act-sched` | the disabled-control popover no longer covers the next row |
| `act-sched-phone` | chip is the state; the chip no longer splits the feature name |
| `set-desktop`, `set-light`, `set-interval`, `set-off`, `set-phone`, `longest-*` | chip is the state, line is when; "the measuring service"; the cron note no longer breaks a sentence across a control |
| `set-firstrun` | "Not run yet" once per row, not twice |
| `title-film`, `title-film-pop`, `title-row-pop`, `title-series`, `title-series-menu`, `title-light`, `title-phone`, `title-phone-menu` | *Make files play directly*; *the NFO files*; no lane claim |
| `toast` | the cancelled toast no longer claims "nothing was left half-written" |
| `set-loading`, `act-hist` | unchanged in content; re-rendered |
| `set-alt`, `act-alt`, `dlg-alt`, `dlg-phone-alt` | **removed** |

## The eight corrections

| # | Where it landed |
|---|---|
| 1 | Three screens said a feature has "its own lane". All three now say the work is read **in the background**, and no screen names a lane. The backend's four lanes are untouched by anything drawn here. |
| 2 | Now lists **intro & credits, file health, subtitles, make files play directly** and nothing else. The card is *Queued work*, not *Lanes*, and the page's own sentence sends you to Schedule for the four that run inside the run. |
| 3 | Dropped from the toast **and** from `act-cancel`, which carried the same claim in different words. Leaving it on the card would have contradicted the toast one click away. |
| 4 | Gone from both dialog faces. The `.foot-note` CSS rule is deleted with a comment saying why, so it does not come back by habit. |
| 5 | **NFO files** on the metadata parts and on the stale line; **the measuring service** for sound-in-step. "Sidecar" appears on no screen, in no caption and in no string. |
| 6 | The Checks card says **Make files play directly**. The header badge still reads *plays directly*, and that is deliberate: it is a fact about the file, not the name of a feature. Say the word and it changes. |
| 7 | Derived from the parts rather than flagged by hand: a feature **all** of whose parts queue work carries the marker on its own row. That is File health **and Subtitles** — the review named File health, but Subtitles' two parts both queue, so it carries it too. |
| 8 | The chip is the state (*On · Running · Holding · Failed*), the line is when. Applied in three code paths, not one: the Settings row, the **phone Schedule card** and the **first-run rows** each had their own copy of the duplication. |

## Nine defects found only by opening the renders

The fences see none of these. This is the third round where looking at every picture was the only
thing that caught them, and the first three are in the work this revision was asked to do.

1. **The queue-only sentence was printed twice in one picture** — the legend's wording repeated
   verbatim inside the right pane. Removed from the pane.
2. **The dialog contradicted Settings.** Music's *Write tags* was drawn as a plain unticked box while
   Settings says tag writing is off, so "you unticked this" and "this is unavailable" looked
   identical. It is now shown disabled with its reason, which is the round's own standing rule.
3. **At 412 nothing said the rows opened a second screen.** The drill-in read as a flat list. A
   feature with parts now carries a chevron; a feature that is one tick does not, because it has
   nothing to open.
4. **"Music" was printed twice on the phone's step two** — once as the screen title, once as the
   pane's heading.
5. **The Schedule popover swallowed the next row.** Open on *Recommendations*, it covered Music's
   name, its failure line and its own *Run now*. In a table it now opens beside the button.
6. **The first pass at correction 8 moved the duplication instead of removing it**: "Failed" beside
   "Failed 40 min ago", "Running" beside "Running · Twelve Winters". The line no longer repeats the
   state word.
7. **At 412 the Queued-work rows were unreadable** — the controls kept their column, leaving about
   150 px for the text, and "1 204" broke across two lines. The controls stack, and the thousands
   separator is non-breaking everywhere.
8. **The long chip split the feature name** on the phone Schedule card: "Recommendations &" / chip /
   "suggestions". The chip takes its own line.
9. **Two sentences broke across a control**: the cron note ("…is kept here, and only here." dangling
   under a full-width select) and the longest-strings popover, which sat on top of the longest string
   the frame exists to test. The first is restructured; the second is closed in that frame and proved
   in the three frames that are about the wording.

## Still open, and worth a sentence from you

- **A dependent part that is off in Settings** (`Write tags`) is drawn shown-and-disabled-with-its-
  reason. The owner fixed that rule for a whole *feature*; extending it to a *part* is my reading,
  not his decision. It is one line to change if you would rather it were hidden or simply unticked.
- **The header badge *plays directly*** was left alone under correction 6, for the reason above.
- **The working copy is unchanged from round 1** and still needs confirming from the code before
  anyone builds: the four repair kinds, the subtitle "does not fit" options, the intro keyword chips.

## The render command

Unchanged from round 1, and it is in that round's section of the history as well:

```sh
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  node design/incoming/2026-10-22-processing-features/render.mjs
```

`render.mjs` is this round's prototype, not the shipped script; the permanent one under `scripts/` is
the engineer's to write.

## The frames still link the production CSS

`frames.html` links `../../app/wf.css` and `../../app/app.css`, so every token, button, badge, toggle
and card in these pictures is the shipped one and the fonts resolve from `design/app/fonts/` with no
copy and no network reference. The one canvas substitution is unchanged and still labelled where it
is made: each frame sets `--blur: none`, because `wf.css` gives every `.card`
`backdrop-filter: var(--blur)` and the standing canvas rule forbids it. The live admin keeps its
glass.

New classes this revision adds to `processing-features.css` — `.sd-top`, `.sd-pane-head`, `.sd-lede`,
`.sd-parts`, `.sd-none`, `.sd-legend`, `.sd-back`, `.sd-one-pane`, `.pick .go` — were checked against
`wf.css` and `app.css` for collisions before being used, which is the check round 1 learned the hard
way (`.note` rendered as a button, `.pf-age span` uppercased a `.select`). Everything new is scoped
under `.sd-two` or carries the `sd-`/`pf-` prefix.

## States

**Drawn:** loading · first run · a feature off (Settings, dialog, Activity, both widths) · a feature
running with progress · waiting while a TV plays · failing (the music database; the measuring service
not answering) · a library not mapped · a run cancelled then continued · a long queue (9 600) with
*Empty* and its confirmation · the dialog everything-ticked · partly unticked · full rescan · scoped ·
a feature off and selected · a part that is off in Settings · both phone steps · a ticked queue-only
part after the run ends · Refresh ▾ on a film and a series · the Checks card with one stale, one
failing and one *can't tell* · the disabled control with each of the two fixed wordings · the longest
strings at both widths · every surface at 412 px · the light theme.

**Skipped, each by name, and unchanged from round 1:** Danish and Faroese (the admin is English only,
by the brief's `FIXED`) · 10-foot focus (no TV surface in this round) · empty (folded into *first
run*; a list of nine features is never empty).

## The fences

Run on the **staged** directory, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked file. All four green: `check-deanonymization.sh`,
`check-mobile-css.sh`, `check-css-scoping.sh`, `check-phases.sh`.

Every title, person and device is the brief's own stand-in — *Midsummer Static*, *Twelve Winters*,
*Ice Road*, *Living-room TV*, *Kite Weather* / *Harbour Lights*, *The Salt Road*. Third-party tools
are named generically throughout ("the music database", "the downloader apps", "the subtitle app",
"the media server", "the request app", "the measuring service"), which is also what the screens
should say. No `http`/`https` reference, no CDN, no hosted font. No `content-visibility`, no
`backdrop-filter` in any rule, no large `filter: blur`, no infinite animation.

**No `STATUS.md`, no `CLAUDE.md`, nothing under `specs/` and nothing under `scripts/` was created or
touched.** Nothing is promoted, no production file is modified, no spec is written, and no phase
number, FR id or task id appears in any frame.
