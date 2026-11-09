# Phone downloads — round report

Drawn 2026-11-09. Surface: the **Ravilo Android phone only**, 412 × 915 dp portrait, linking the
shipped `design/ravilo/mobile/ravilo-phone.css`. No TV, desktop, web, admin or cast frame exists in
this round — those surfaces get no download control at all.

Kind of round: **product design**, as the brief states. It lands under `design/incoming/`.

- `frames.html` — every frame, parameterised `?set=…&lang=en|da|fo`
- `pd-data.js` — the one array every number comes out of
- `pd-kit.js` — the builders
- `phone-downloads.css` — the round's own rules, on top of the shipped phone stylesheet
- `Phone Downloads - Directions.html` — the directions document
- `png/` — 97 PNGs, `phone-downloads-<direction>-<state>[-lang].png`

Every PNG was opened one at a time and magnified before this was written.

---

## The three picks

**1 · The download control on the film page: direction B, "under Play" — against the brief's lean.**

The brief leans to icon-with-label beside Play, and in English, in the *Download* state, that is the
nicer picture. It does not survive its own state machine. Three of FR-8's five states are words, and
two of those words are long. In Danish, `a-film-states-da` shows *Mislykkedes — prøv igen* squeezing
Play down to about **82 dp** — the secondary control ends up **wider than the primary one** — and
across the five states Play's width swings from about 217 dp to about 82 dp, so the page's one
primary action moves and resizes while a download runs. `b-film-states-fo` holds all five states at
a fixed geometry in Faroese, the longest of the three languages.

Direction B also gives the *n %* state something direction A cannot: the control's own width becomes
the progress bar, with the percentage on it. In A the same state is a 60 dp box with a number in it.

The cost is 46 dp of vertical height on the film and episode pages, below the fold of nothing.

**2 · The storage bar: one segmented bar with an inline legend**, as the brief leans. The
alternative is drawn (`alt-downloads-legend-list`) and is not wrong — it is about **100 dp** taller,
which pushes the first download entry below the fold on a 915 dp screen. The bar is the summary and
the list is the detail; the page should reach the detail fast.

**3 · Delete watched in the overflow**, as the brief leans — and the overflow buys something the
alternative cannot have. Each destructive row has room to carry **what it frees**: *Delete watched ·
7.5 GB*, *Delete all · 26.9 GB* (`b-downloads-overflow`). As three buttons across the top
(`alt-downloads-actions-top`) there is no room for a number. The same reasoning put a size on the
multi-select bar's Delete (*Delete · 14.8 GB*, `b-downloads-selecting`), which the brief did not ask
for and the owner's actual ask — being in control of the space — seems to want.

---

## ⚠ The brief's 37.8 GB does not reconcile, and every total here is derived instead

**This is the one thing to settle before the spec is amended.** The brief gives 37.8 GB in three
places — the storage bar's Ravilo segment, Profile's Downloads row, and the Delete-all sentence —
and it also names every entry's exact size. The two cannot both be true:

| | GB |
|---|---|
| Heimferð · Original | 6.2 |
| Brotsjór · 1080p | 7.9 |
| Hraðar Ljós II · 720p | 4.1 |
| Mýrin · 720p | 3.8 |
| Nordvest S01E01–E04 · 720p · 650 MB each | 2.6 |
| Havets Hjarta S02E01 · Original | 2.3 |
| **this profile's downloads** | **26.9** |
| other profiles on this phone | 9.4 |
| **everything Ravilo holds** | **36.3** |

26.9 + 9.4 = 36.3, not 37.8, and 37.8 is not any of the three readings of "the total". So the round
**derived every total from the per-entry sizes** rather than drawing a number the pictures
contradict, and wrote the derivation into `pd-data.js` where it can be checked. Three figures
therefore differ from the brief:

- the storage bar's Ravilo segment is **36.3 GB**, not 37.8;
- Profile's Downloads row is **26.9 GB**, not 37.8 — fixed by FR-12, which says another profile's
  downloads are never in this viewer's total, so the Profile row cannot be the 36.3 figure either;
- Delete all frees **26.9 GB**, not 37.8 — FR-10 says Delete all removes everything *for this
  profile*.

**A decision is needed, and it is Bárður's:** either the spec's 37.8 becomes 36.3 / 26.9 / 26.9, or
the stand-in gains entries that make the entries add up to 37.8. The pictures are self-consistent
under the first reading today.

One consequence worth seeing in the pictures: the Ravilo segment is **36.6 GB** on
`b-downloads-running` and 36.3 GB on `b-downloads-full`, because a download running at 42 % really
has 273 MB of its 650 MB on the disk. That is not a drift between frames; it is the partial file.

**A design choice inside this, flagged because it is mine and not the brief's.** The bar's Ravilo
segment is *everything Ravilo holds*, with FR-12's other-profiles line directly beneath it naming
the 9.4 GB. The alternative was to make the segment this profile's 26.9 GB and let the other
profiles' bytes vanish into "other apps", which would be false — they are Ravilo's. The owner asked
to be in control of *how much space the app is using*, and that is the 36.3 figure; what a delete
frees is the 26.9 figure. Both are on the page, both labelled.

---

## What only the pictures showed

Six, and two of them are the traps `PROCESS.md` warns about by name.

1. **The round-11 z-index trap, again.** `.pd-dlfull`'s label carries `z-index: 2` so the words sit
   over the progress fill. Its positioned ancestor had `z-index: auto`, so that 2 competed at the
   screen's level — and the word **Download** painted on top of the quality sheet that had just
   covered it. At 1× it read as a stray label floating on the sheet. Fixed with
   `isolation: isolate` on the control, and every overlay now declares its own layer rather than
   trusting DOM order.
2. **An unsized inline SVG became a 170 px warning triangle** across the quality sheet's no-room
   row, over the Original row, the shortfall sentence and the action button. An inline `<svg>` with
   no width is sized by its container. `.pd-ico` now carries an 18 px default so it can never
   happen again in this round.
3. **A progress fill escaped its button.** The direction-A control had no `position: relative`, so
   its 42 % fill laid itself against `.pd-dbody` — a translucent purple slab across the title, the
   meta line and half the synopsis.
4. **The legend-list alternative painted no swatches.** The colour rules were scoped to `.pd-leg`,
   so the list's three dots were invisible and the alternative read as three unlabelled rows —
   i.e. the alternative would have lost the comparison for a reason that was not about design.
5. **The empty page claimed 26.9 GB** in its own title bar, and offered an overflow with three dead
   destructive rows. Both are now left off when there is nothing on the phone.
6. **The corpus handed back a name collision.** `RAVILO.episodesFor()` generates episode titles for
   a series it does not list by hand, drawing from the same pool the round's film titles come from
   — so Havets Hjarta S02E01 was titled **Glasberget**, the name of the 4K film on the quality sheet
   two frames earlier. The corpus is not edited; the round steps past a generated title that
   collides with one it already uses, and says so in `pd-kit.js`.

Two more that are judgement rather than defect: the downloaded mark and the watched tick were
drawn twice on the same episode row (thumb and status line) until the picture showed how noisy that
is on a 59 dp row; and the Continue Watching row originally carried no progress bars at all, which
made "the mark must not fight the progress bar" a claim with no picture behind it.

---

## Open questions for Bárður

1. **The 37.8 GB reconciliation above.** Blocking for the amendment, not for the pictures.
2. **A swipe deletes 7.9 GB with no undo.** FR-10 gives swipe-to-delete and says nothing deletes
   itself, but it also gives no undo and no confirm on a single entry. Delete all is behind a
   confirm; one swipe on Brotsjór is not, and it is the easiest gesture on the page to perform by
   accident while scrolling. A snackbar with **Undo** would be a new behaviour and therefore the
   owner's call. Worth asking.
3. **At full swipe travel the entry is unidentifiable.** `b-downloads-swipe` shows the row slid
   88 dp left: the poster is gone and the title reads *"otsjór"*. That is what a committed swipe
   looks like in the shipped Compose gesture, and it means the confirm-free delete is also a
   delete whose target you can no longer read. Related to 2; the two are best answered together.
4. **FR-9's order puts the running download about 700 dp down its own page.**
   `b-downloads-running` is the state's own frame and does not show it;
   `b-downloads-running-scrolled` does. Netflix pins the active download to the top. The round did
   **not** reorder the page, because FR-9 fixes films-then-series and a design round should not
   quietly rewrite an FR — but it probably should be reordered, or the queue given its own block
   above Films.
5. **A season sheet's rows and its button name different numbers, on purpose.** FR-5 says the rows
   show the season's total per quality (*720p about 5.2 GB*), and FR-5 also says only the episodes
   not already on the phone are queued — so the button says *Download · about 2.6 GB* with a
   sentence above explaining the difference (`b-sheet-season`). If the spec wants the button to
   carry the row's number instead, it is a one-line change and the sentence goes.
6. **`download.sheet_title` is drawn as "Choose a quality", not "Download".** The spec names the key
   but not the words. A sheet heading that repeats the button that opened it tells the viewer
   nothing; this one says what to do. Same for the two other strings I would word differently:
   `download.empty` is drawn as an invitation naming the aeroplane case rather than an instruction,
   and `settings.wifi_only` gained a one-line sub (`settings.wifi_only_sub`) because *Wi-Fi only*
   alone does not say whether it is about downloading or playing.
7. **The downloaded mark's treatment.** Recommended as a white chip; the dark-glass alternative is
   drawn beside it at 3× in `b-mark-geometry` so the owner can pick from pictures rather than from
   this sentence.
8. **Nine new string keys** the spec's list does not carry are in the directions doc, marked
   **new**. None is optional — the page cannot be built without a word for *Select*, for *{n}
   selected*, or for the storage bar's three segments.

---

## States drawn, and states skipped

Drawn: all five control states in three languages; the Play/Delete choice; four quality sheets plus
a loading sheet; the season page and its sheet; the Downloads page full, running, empty, offline,
selecting, swiping, overflowing and confirming, each at phone height and the long ones also as a
full-page `tall` frame; the mark on a Home row, in a Library grid and magnified at 3×; Profile's
Downloads row; Home's unreachable state; Settings.

**Skipped, and why:**

- **Loading** — the Downloads page reads a local disk and has no load. The one real round trip is
  FR-4's options call, and that *is* drawn (`b-sheet-loading`).
- **FR-2's switched-off viewer** — the control is hidden, so the frame is today's film page with
  nothing added. `download.not_allowed` is translated in the directions doc but has no frame: it
  appears only on a 403 against a control that should not have been there.
- **Skins** — aurora only. Nothing in this round introduces a colour that is not a shipped token,
  so midnight, noir, graphite and daylight should follow, but they are untested and that is a fact,
  not a claim.
- **The iPhone bezel and landscape** — the surface is Android phone portrait (FR-1).
- **10-foot focus** — no TV surface in this round.

## Canvas and fence notes

- The shipped `.dback` and `.hbadge` are `backdrop-filter` glass; the canvas cannot carry that, so
  the detail frames draw the back button as a flat 55 % tint. The live app keeps its blur.
- No `content-visibility`, no `backdrop-filter`, no large blur, no infinite animation anywhere in
  the round.
- No network reference of any kind: the three faces are the repo's own woff2 and the render ran
  with `--network none`.
- No SVG `<defs>`, gradient or `id` exists in the round, so the hidden-subtree paint-server trap
  cannot apply.
- Nothing by a forbidden name is in these inputs: no `STATUS.md`, nothing under `specs/`,
  `scripts/`, `.github/`, no `*.gradle.kts`, no `i18n/*.json`. **Nothing was dropped from the
  brief's inputs** — there was nothing in them that does not belong here.
- Every title, person and size is the brief's stand-in or the shipped corpus in
  `design/ravilo/ravilo-data.js`. No hostname, no IP literal, no real library title.
