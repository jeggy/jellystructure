# Phase R343 — Reset progress and Shuffle on a series

> Owner, 2026-10-01: *"In ravilo when a series has been fully watched once, then a reset progress should be possible.
> This specifically makes sense for kid shows, as they are watching the same series many times. And let's also
> investigate a shuffle button on series. I don't want these two new buttons to clutter everything."* Then: *"Let's go
> with direction B"* and *"Let's change that to 9+ episodes."*

## Status

`Planned` — written 2026-10-01 (design-authored) from `design/ravilo/Ravilo TV.html` and `design/ravilo/Ravilo
Mobile.html` (built there the same day: `ravilo-app.js` `seriesFinished` / `shuffleOrder` / `shuffleCtx`, the
`data-reset` / `data-shuffle` handlers; `.rs-chip`, `.spill-sh`, `.spill-sep`, `.dnext-ck` in `ravilo.css`; `.mshuf`,
`.mdone`, `.mreset` in the phone file). Directions canvas: `design/ravilo/Start over & Shuffle - Directions.html`
(direction **B** picked; A and C are kept on the canvas as declined). Not dev-reviewed. Number verified free on `main`
(tree `f88c706e`, Ravilo tops at R342 locally; re-checked on `ef52889` the same evening: `main` tops at R340). **Changes** R150-2 (which season a series opens on) and the series
resume pointer behind Play (`SeriesProgress.resumeEpisodeId`, `plan.md`). **Applies to** the TV family (TV, the web
app; R337) and the phone. Stand-in: *Lundin og vinir*, a fictional kids' series, 3 × 13 × 11 min, all watched.

## Today

A series every episode of which this viewer has watched is a dead end:

- The resume pointer finds nothing in progress and nothing unwatched and falls back to the **last** episode. Play reads
  *Play · S03E13*, the hint *Up next · S03E13*, and the page opens on the **last** season (R150-2 picks the first
  unfinished season, else the last).
- Getting back to S01E01 means unticking every episode by hand (39 here). The TV has no *Mark all* since the 09-27
  audit.
- There is no way to play a series in random order.

## Requirements

**FR-R343-1 — Finished.** A series is *finished* for a viewer when that viewer has watched every episode of it **that
is in the library**. Specials (season 0) and missing episodes don't count. A new episode arriving ends the finished
state, and the series behaves as it does today (*Play · S04E01*). Finished is per viewer: another profile's ticks
never count, and a reset (FR-R343-4) never touches them.

**FR-R343-2 — A finished series opens on Season 1, and Play follows on.** On a finished series:

- The page opens on **Season 1**, not on the last season (R150-2's fallback changes from *last* to *first*; its rule
  for an unfinished series is unchanged).
- The hint pill reads **✓ All {n} episodes watched** (`all_watched`) in place of *Up next*. On a finished series it
  replaces the meta row's *✓ Watched*, so the fact is said once.
- **Play does not go to the last episode.** The resume pointer on a finished series is, in order:
  1. an episode with a resume position (started, not finished) ⇒ **Resume · SxxEyy**, as today;
  2. else the episode **after the one most recently played in order** (FR-R343-6), so a rewatch carries on from where
     it is. After the series' last episode it wraps to S01E01;
  3. else **Play · S01E01**.

  The ticks stay (Q1, owner): a rewatch is not a reset. The episode cards keep their ✓, and the *UP NEXT* ribbon
  marks the pointer's episode.

**FR-R343-3 — Reset progress (finished series only).** The Episodes section's header gains one control once the
series is finished, after *{w} of {n} watched* and the bar:

- **TV:** a chip **↺ Reset progress** (`reset_progress`). It makes the header a focus row between the hero's actions
  and the season pills. The first OK **arms** it: the chip shows a warn-coloured ring and reads *Press again · all {n}
  back to unwatched* (`reset_confirm`) for **4 s**, then goes back. A second OK inside the 4 s resets (FR-R343-4).
  There is no dialog and no toast to reach.
- **Phone:** under the Episodes header, one row: *✓ All {n} episodes watched* on the left, **↺ Reset progress** on the
  right (a 44 dp text button). The first tap arms it as on the TV (*Tap again · all {n} back to unwatched*), and the
  second tap resets.

When the series is not finished, the control is absent, not greyed.

**FR-R343-4 — What a reset does.** For **this viewer only**, every episode of the series (every season, specials
included) becomes unwatched with no resume position, and the in-order pointer (FR-R343-6) is cleared. Then:

- the page re-renders as a series nobody has started (*Play · S01E01*, Season 1, no ticks), with focus on Play;
- a toast confirms: *Progress reset · S01E01 is up next* (`reset_done`);
- every other open screen updates through `WatchedBus` (R147/R176): tiles, Continue watching, the series' own card.
  The series leaves Continue watching / Next Up until something is played again (R219 already covers the re-entry).

Nothing else changes: My List, other viewers, the series' own metadata.

**FR-R343-5 — Shuffle.** On every series with **9 or more episodes in the library** (all seasons together, specials
excluded; owner), finished or not:

- **TV:** the season pill row ends with a thin divider and a **Shuffle** pill (shuffle glyph + label, `shuffle`),
  focusable like a season pill. OK starts playback.
- **Phone:** a **Shuffle** chip at the right of the Episodes header (36 dp tall, the whole header row is the hit
  target's height ≥ 44 dp).
- **The order:** every episode of the series in random order, each once (Q4: the whole series, not the page's season;
  Q6: when the last one ends, playback stops as at the end of a series). A **multi-episode file** (R179/R309) is one
  entry and plays whole. A new order is drawn on every press.
- **In the player:** the kicker reads **Shuffle · S02E07** (`shuffle`; R303's top-right identity is unchanged). The
  next-up card's kicker reads **Next · shuffled** (`shuffle_next`) with the next entry's code and title, at the usual
  20 s / credits point (R111, R182). *Next* and auto-advance follow the shuffled order. The Episodes rail keeps the
  season in its own order, with the playing episode lit. Choosing an episode from the rail leaves shuffle and plays on
  in order from there.
- **No resume point (Q5):** a shuffled episode always starts at 0:00. It is ticked when it finishes (the ≥ 90 % rule).
  If the viewer stops early, it **writes no resume position**, so *Resume* on the page and in Continue watching still
  means "where you were in order".
- Shuffle lasts until the player is left. There is no shuffle state to switch off, and it is not remembered.

**FR-R343-6 — Shuffled plays don't move the in-order pointer.** FR-R343-2's step 2 uses the episode most recently
played **in order**. Jellyfin's `LastPlayedDate` moves on every play, shuffled ones included, so it can't be used on
its own. The server keeps, per viewer and series, the last episode played in order (set on every non-shuffled play's
stop/finish, cleared by a reset). A shuffled play is reported with `shuffle: true` and does not update it.

**FR-R343-7 — Strings** × en · da · fo (da/fo drafts; the shipped table wins). Mockup keys:

| Key | en | da | fo |
|---|---|---|---|
| `shuffle` | Shuffle | Bland | Blanda |
| `shuffle_next` | Next · shuffled | Næste · blandet | Næsti · blandað |
| `all_watched` | All {n} episodes watched | Alle {n} afsnit set | Allir {n} partarnir sæddir |
| `reset_progress` | Reset progress | Nulstil | Nullstilla |
| `reset_confirm` | Press again · all {n} back to unwatched | Tryk igen · alle {n} bliver usete | Trýst aftur · allir {n} verða ósæddir |
| `reset_confirm_tap` (phone) | Tap again · all {n} back to unwatched | Tryk igen · alle {n} bliver usete | Trýst aftur · allir {n} verða ósæddir |
| `reset_done` | Progress reset · S01E01 is up next | Nulstillet · S01E01 er næste | Nullstilla · S01E01 er næstur |

`shuffle` may reuse the music table's `music.shuffle` (same three words). The shipped Faroese for *episode* follows
R288; `partarnir` is the draft's word and is the implementer's to align.

## Invariants

- Films are unchanged: *Play Again* and *Mark Watched* stay as they are.
- A series that is not finished looks and behaves exactly as today, except for the Shuffle pill/chip (9+ episodes).
- No new dialog, no new menu, and no new button in the hero's action row (the reason B was picked: the hero stays as
  it is).
- Per-episode watched toggles (R07/R179) are unchanged. No *Mark all* per season comes back.
- The receiver-only TV app (R264/R269) has no detail page and is untouched. A cast started from a shuffled episode
  casts that episode only (open question 2).

## Acceptance

1. Olivar has watched all 39 episodes of *Lundin og vinir*. The page opens on Season 1, Play reads *Play · S01E01*, the
   pill reads *All 39 episodes watched*, every card is ticked, and the Episodes header shows *Reset progress*.
2. Olivar plays S01E01–S01E04 through, leaves, and comes back. Play reads *Play · S01E05*, and the ticks are still
   there.
3. Olivar shuffles, watches S02E07 to the end and stops S01E11 halfway. Play still reads *Play · S01E05*. S01E11 has
   no resume bar, and Continue watching doesn't show it.
4. OK on *Reset progress* once: it reads *Press again · all 39 back to unwatched*. Waiting 4 s puts it back unchanged.
   OK twice: every tick is gone for Olivar only (Eyð's ticks on the same series are unchanged), Play reads *Play ·
   S01E01*, the chip is gone, and the toast says *Progress reset · S01E01 is up next*.
5. A series with 8 episodes has no Shuffle. One with 3 seasons × 4 episodes (12) has it.
6. In a shuffle, the next-up card says *Next · shuffled* with a code that is not the next in order, and the last entry
   ends playback.

## Open questions (leans)

1. **The reset's route.** Lean: one server route, `POST /tv/series/{id}/reset-progress`. It loops the episode ids
   server-side (`DELETE /Users/{u}/PlayedItems/{ep}` plus R185's zeroed position per id), clears FR-R343-6's pointer
   and returns the new `SeriesProgress`, rather than 39 client calls.
2. **Casting a shuffle.** Lean: round 1 casts the current episode only, and the TV shows no shuffle. A shuffled queue
   on the receiver is later, if asked.
3. **The desktop (R337).** Lean: it follows the phone's shape at Compact and the TV family's at Expanded and above,
   whichever detail page that width already uses.
4. **Next Up on Home.** Lean: Home's Next Up / Continue watching use the same in-order pointer (FR-R343-6), so a
   rewatch shows *S01E05*, not Jellyfin's last-played guess. Dev to confirm what `enableRewatching` returns today.

## Mockup notes

- The mockup's Play on a finished series always shows *Play · S01E01*; FR-R343-2's follow-on pointer is not
  simulated.
- The phone mockup lists Season 1 only (no season chips), and its player is a stand-in that doesn't advance a shuffle.
