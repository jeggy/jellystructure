# Round 9 — processing as nine features (`processing-features`)

**Lane C, pilot round 2 — drawn in this repository, not in the design tool.** No `DesignSync` call
was made in either direction. There is no package, no pull, no quarantine and no paste block: the
paste row DEB-1680 was cancelled as superseded before any of this was drawn, and its block — the
A/V sync *can't tell* amendment included — is the brief this round was drawn from. Brief and
decisions are Bárður's on DEB-1675, from his investigation on DEB-1664. Reported on DEB-1675.

**Drawn with the house design system (`design/SKILL-house.md`) and nothing else.** The owner's card
on DEB-1076 about adding `frontend-design` beside it has not come back, and that skill is not
selected on this seat, so it played no part and there is nothing to report about what it changed.

## What the round answers

With the pipeline gone, each of the nine features shows its switch, its interval and its state in one
place per surface, and the four surfaces render from **one list in the source** (`pf-data.js`). That
is not a presentational claim: if a fact appears twice in a picture, it appears twice in that array,
which makes "do these four screens agree?" something a reviewer can check rather than trust.

## The exact render command

Run from the repo root. This is the specification of the permanent script, which is the engineer's to
write — `render.mjs` here is this round's prototype and lives beside the round it rendered.

```sh
# once, to put a browser matching tests/package-lock.json in a host-side cache
mkdir -p ~/.cache/ms-playwright
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  sh -c 'cd /work/tests && npx playwright install chromium'

# every render after that
docker run --rm -v "$PWD":/work -w /work \
  -v ~/.cache/ms-playwright:/ms-playwright-host \
  -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  --user "$(id -u):$(id -g)" \
  mcr.microsoft.com/playwright:v1.45.0-noble \
  node design/incoming/2026-10-22-processing-features/render.mjs
```

Everything pilot round 1 recorded about that command held again: `--user` keeps the PNGs out of
root's hands in a shared checkout, the image pin (`v1.45.0-noble`) and the lockfile (1.61.0) are
different on purpose, and Playwright is resolved **by path** because ESM resolves from the importing
file's directory and ignores `NODE_PATH`. Nothing new was learned there, which is itself worth
recording: the recipe is stable.

**One deliberate difference from round 1.** That round copied the three woff2 into the round and
wrote its own CSS, because it was drawing a *public web page* that does not exist in this repo. This
round draws the *admin*, so `frames.html` links `../../app/wf.css` and `../../app/app.css` directly:
every token, component and font in these pictures is the shipped one, and `wf.css`'s own `@font-face`
URLs resolve to `design/app/fonts/` with no copy and no network reference. The round is therefore not
portable outside the repo, which in a lane with no transport costs nothing and buys an exact system.

## Directions, and the recommendation

| Handle | |
|---|---|
| `Settings ▸ Processing — its own tab, nine expanding rows` | **recommended** |
| `Settings — the nine rows stay under Libraries ▸ Scanning` | drawn, not recommended |
| `The Scan dialog — one row per feature, expanding to its parts` | **recommended** |
| `The Scan dialog — two panes, a drill-in at 412` | drawn, not recommended |
| `Activity — Now · Schedule · History` | **recommended** |
| `Activity — one page, the schedule is the page` | drawn, not recommended |
| `A title — Refresh ▾ in the header, the Checks card as the same nine features` | **recommended**, and the only direction the brief did not ask for an alternative to |

**Why the expanding dialog rows over two panes:** at 412 px the expanding dialog holds, and it keeps
one thing the two-pane version gives up — you can see that eight of nine features are fully ticked
without opening anything. The two-pane version is better the moment a feature grows past about seven
parts, and Music already has seven. Worth re-reading in round 2 rather than settling now.

**Why its own Settings tab:** the alternative is drawn, and drawing it is what makes the cost
visible — Libraries is already seven cards long, only five of the nine rows fit before a "show the
other four", and the rows sit under folder mappings they are not about.

## What the drawing found that the brief could not

1. **The constitution rule bites harder than it reads.** "The design never shows a number the page
   would have to work out for itself" rules out the obvious summary strip at the top of Settings —
   *"8 of 9 on · 2 running · 1 246 waiting"* — because every one of those is arithmetic over the
   list. It is not drawn anywhere, and the absence is the point rather than an omission.
2. **The owner's disabled-control rule is the round's spine, not a detail.** Once a control that
   cannot act is *shown, disabled, with its reason* rather than hidden, the same shape appears in
   three more places, and all three are now drawn that way: the `Find new & changed` switch that
   cannot be turned off, `Run now` on a feature that is off in Settings, and every control on
   Activity while the socket is down. One rule, four applications, no new vocabulary.
3. **The run card and the lane card had to be split.** The first render showed the same progress bar
   twice — once in "the run" and once in "the lane" — which quietly repeated the lie the round exists
   to kill. They now say different things: **the run card says what it *queued*, the lane says what
   is being *read*.** That is the queue-only rule made visible instead of stated.
4. **Offline is missing from the brief and the page cannot do without it.** Activity lives on a
   WebSocket; the house system asks for an offline state wherever there is a socket. Drawn
   (`act-offline`): every count is explicitly as old as the last push, and the controls are off
   because they cannot reach anything.
5. **The brief used two names for one measurement** — *A/V sync* in the dialog and the lane,
   *Sound in step with the picture* on a title, which is the product's own name. In a round whose
   question is whether four surfaces read as one model, that is a defect, so the product's name is
   used everywhere and shortened to "sound in step" only where a column forces it.

## Judgement calls the brief delegated

- **A library that is not mapped: shown and explained, not hidden.** A row that vanishes makes an
  admin wonder whether it was ever there. The row is greyed, inert, and carries one sentence saying
  what to do. Cost: one inert row. (The decided model's "shown only when a library is mapped" is
  satisfied either way — this is the reading that fails safely.)
- **Per-library switches have a natural place, and it is not the one expected.** Not in Settings ▸
  Processing, where it would multiply nine rows by four libraries — but on the **library card** under
  Libraries, as a short "this library skips:" line beside the *Scan* button that already scopes a run
  to that library. Round 2's question; nothing here designs it.

## Working copy — to confirm before this is built

| Where | Drawn as | Status |
|---|---|---|
| Make files play directly · the repair kinds | Move the index to the front · Pull subtitles out of the file · Repack the audio · Drop a track no player can read | **working copy** — the real kinds are in the code and were not in the brief. Layout does not change when the names do. |
| Subtitles · what to do when one does not fit | Ask the subtitle app for another one | **working copy** — the real option set is on the subtitle app's card today |
| Intro & credits · the keyword chips | Intro · Opening · Opening Credits · End Credits · Recap | **working copy** — illustrative, not the shipped list |
| The release-age brackets | this year / 1–5 years / older, with 7 d / 30 d / 180 d and 1 y / 2 y / 5 y | stand-in, from the brief |

## Every state, drawn or named by name

**Drawn:** loading · first run · a feature off (in Settings, in the dialog, on Activity at both
widths) · a feature running with progress · waiting while a TV plays · failing (the music database;
the sidecar not answering) · a library not mapped · a run cancelled then continued · a long queue
(9 600) with *Empty* and its confirmation · the dialog with everything ticked · partly unticked
(*Music · 5 of 7*) · its full-rescan face · scoped to one library · a ticked queue-only part after the
run ends · Refresh ▾ on a film and on a series · the Checks card with one stale, one failing and one
*can't tell* · the disabled control with each of the two fixed wordings · the longest strings at both
widths · every surface at 412 px · the light theme.

**Skipped, each by name rather than by omission:**

- **Danish and Faroese** — deliberate: the admin is English only, by the brief's own `FIXED`. The
  house system's standing da/fo state does not apply to this surface.
- **10-foot focus** — not applicable: no TV surface is in this round.
- **Empty** — folded into *first run*, which is this surface's only empty state. A feature list is
  never empty; it is always nine.

## Three defects found only by looking at the renders

Pilot round 1's lesson held, and it cost the same way: none of these four fences can see any of them.

1. **412 px did not hold at all in the first render.** The five-column Settings row kept its columns
   at phone width and turned every label into a column one word wide — "Find new & changed" as four
   stacked lines. The row stacks now. *Nothing in the markup or the fences says "this is unreadable";
   only the picture does.*
2. **Two controls were drawn backwards against the owner's own rule.** `Run now` on a feature that is
   off was live, and the Refresh ▾ item that can still run two of its three checks was greyed out —
   the exact inversion of *shown-and-disabled only where nothing can happen*. Both corrected.
3. **Two wf.css collisions and a clipped dialog.** `.note` is a component in `wf.css`, so the
   dialog's "Cancel starts nothing." rendered as a button; `.pf-age span` uppercased the `.select`
   inside it, so every interval read `EVERY YEAR`; and the dialog's `max-height` clipped the ninth
   feature out of its own picture. All three fixed, and the first two are the kind of thing that only
   happens when a round links the production CSS instead of copying it — which is still the right
   trade.

## The fences

Run on the **staged** directory, because `check-deanonymization.sh` enumerates `git ls-files` and
prints a cheerful OK over an untracked one. All four green: `check-deanonymization.sh`,
`check-mobile-css.sh`, `check-css-scoping.sh`, `check-phases.sh`.

**No real values anywhere, and it is cheap to check here:** every title, person and device in these
pictures is from the brief's `STAND-IN` section — *Midsummer Static*, *Twelve Winters*, *Ice Road*,
*Living-room TV* — and all of them were checked word-bounded against the live library, read-only,
before the brief went out. The third-party tools are named generically throughout ("the music
database", "the downloader apps", "the subtitle app", "the media server", "the request app"), which
is also what the screens should say. There is no `http`/`https` reference, no CDN and no hosted font.
No `content-visibility`, no `backdrop-filter` in any rule, no large `filter: blur`, no infinite
animation.

**One deliberate canvas substitution, labelled where it is made.** `wf.css` gives every `.card`
`backdrop-filter: var(--blur)`, which the standing canvas rule forbids because it breaks the
screenshot tool. Each frame sets `--blur: none`, so the cards are drawn as the flat fill that filter
resolves to. The live admin keeps its glass.

**No `STATUS.md`, no `CLAUDE.md`, nothing under a `specs/` path and nothing under a `scripts/` path
was created by this round.** Nothing is promoted, no production file is touched, no spec is written
and no number or task id appears in any frame.

**The 192 KiB-per-PNG rule does not apply in this lane and was not applied** — it was the `get_file`
truncation wall on the pull, and lane C has no transport. Most desktop frames are over it.

## Files

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `Processing Features - Directions.html` | 25 195 | 319 | `85d7444c0363` |
| `frames.html` | 10 608 | 87 | `6776002347a7` |
| `pf-data.js` | 15 522 | 255 | `d2da75350bcc` |
| `pf-surfaces.js` | 15 547 | 320 | `c6aec550a544` |
| `pf-surfaces2.js` | 22 143 | 435 | `1811a9eccfb8` |
| `processing-features.css` | 19 494 | 319 | `5546b5882f13` |
| `render.mjs` | 3 309 | 80 | `a14f393cef34` |
| `png/processing-features-act-alt.png` | 210 065 | — | `5062a20335ba` |
| `png/processing-features-act-cancel.png` | 222 087 | — | `ba0542ab039e` |
| `png/processing-features-act-empty.png` | 241 597 | — | `acc957838653` |
| `png/processing-features-act-hist.png` | 187 105 | — | `81a9761f8037` |
| `png/processing-features-act-now-phone.png` | 190 809 | — | `549c8e7cd052` |
| `png/processing-features-act-now.png` | 230 686 | — | `15cfcf29005d` |
| `png/processing-features-act-offline.png` | 239 092 | — | `733783d2a41f` |
| `png/processing-features-act-sched-phone.png` | 175 983 | — | `b01676a384bb` |
| `png/processing-features-act-sched.png` | 229 817 | — | `2647910271d8` |
| `png/processing-features-dlg-alt.png` | 104 649 | — | `491fb78d04cb` |
| `png/processing-features-dlg-desktop.png` | 121 770 | — | `98504b9ffd0c` |
| `png/processing-features-dlg-off.png` | 113 680 | — | `fd87a8d65d76` |
| `png/processing-features-dlg-phone-alt.png` | 51 454 | — | `1d944249a5d4` |
| `png/processing-features-dlg-phone.png` | 83 240 | — | `ceb39726b98c` |
| `png/processing-features-dlg-rescan.png` | 122 068 | — | `ebf09fb9a6bf` |
| `png/processing-features-dlg-scoped.png` | 107 542 | — | `4bdeb159616b` |
| `png/processing-features-longest-desktop.png` | 139 025 | — | `9c965de69365` |
| `png/processing-features-longest-phone.png` | 121 512 | — | `c19310a2a3da` |
| `png/processing-features-set-alt.png` | 226 388 | — | `9f0aae17a5d2` |
| `png/processing-features-set-desktop.png` | 318 556 | — | `2908166ac9ca` |
| `png/processing-features-set-firstrun.png` | 248 224 | — | `1977f03f55fe` |
| `png/processing-features-set-interval.png` | 302 418 | — | `cf0c79f11a2e` |
| `png/processing-features-set-light.png` | 316 292 | — | `eae598735ce0` |
| `png/processing-features-set-loading.png` | 128 557 | — | `511279548f9e` |
| `png/processing-features-set-off.png` | 277 613 | — | `eb8645759f28` |
| `png/processing-features-set-phone.png` | 278 374 | — | `6a172fb10fec` |
| `png/processing-features-title-film-pop.png` | 236 686 | — | `dbb9c19c0caf` |
| `png/processing-features-title-film.png` | 250 622 | — | `3563c4a36542` |
| `png/processing-features-title-light.png` | 236 694 | — | `059ba8a4f153` |
| `png/processing-features-title-phone-menu.png` | 161 611 | — | `da6b728ee46f` |
| `png/processing-features-title-phone.png` | 170 599 | — | `1bb2212e651a` |
| `png/processing-features-title-row-pop.png` | 237 296 | — | `cab7008f5943` |
| `png/processing-features-title-series-menu.png` | 235 641 | — | `1bf887ac9a66` |
| `png/processing-features-title-series.png` | 215 616 | — | `f321b7a04fc7` |
| `png/processing-features-toast.png` | 139 308 | — | `927e4427e521` |

**43 files, 6 984 494 bytes.** The table lists 42: it omits `REPORT.md` itself, which cannot state
its own hash. 35 PNGs — 27 desktop at 1440, 8 phone at 412.
