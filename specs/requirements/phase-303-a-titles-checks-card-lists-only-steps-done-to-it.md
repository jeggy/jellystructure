# Phase 303 — A title's Checks card lists only the steps done to it

> Found 2026-10-03 from an owner question about a film's page: *"fetch_lyrics · not yet · not yet · next due 2027,
> with the next scan — why do I see this on movies?"*

## Status

`Planned`. Written 2026-10-03 (dev-authored), against `main` `c01c4a3b`. Number verified free (admin specs top at
302, locally and on `origin/main`). Not dev-reviewed.

**Amends** phase 261 FR-261-10 (which steps the Checks card lists).

## What happens

1. `TitleChecks.forItem` (`media/TitleChecks.kt:68`) builds the card from every step in the pipeline. It drops
   `wait`, `notify`, `build_recommendations` (269) and `build_suggestions` (274), because those are not done *to*
   a title (261's open question 2). That list was written before the music phases.
2. Phases 275–284 added seven music steps (`MusicSteps.ALL`: `scan_music`, `scan_audiobooks`,
   `match_musicbrainz`, `fetch_music_artwork`, `fetch_lyrics`, `write_music_nfo`, `write_tags`).
   `MusicSteps.seed` puts them into every existing pipeline, and the built-in default pipeline has them too.
   These steps run over the whole music and audiobook libraries. They never touch a film, a series or a music
   video, and they never write `item_step_run` for one.
3. So every film and series page shows one row for each music step:
   - its raw id (`fetch_lyrics`), because `STEP_LABELS` has no label for it;
   - *not yet* twice, because it has never run for this title and never will;
   - *next due 2027, with the next scan*, which is the generic `else` branch copying the title's own next scan
     date.

   All three parts are false. The card exists to say what this title was checked against and when (FR-261-10).
   A row for something that will never happen to the title breaks that.

## Requirements

**FR-303-1 — Only steps done to a title.** The Checks card lists a pipeline step only if that step runs for
individual films, series or music videos. These steps are never listed:

- the whole-library steps: `build_recommendations`, `build_suggestions` and every step in `MusicSteps.ALL`;
- the pipeline's control steps: `wait` and `notify`.

This applies to every film, series and music video page. It applies whether the step comes from a configured
pipeline, from the built-in default or from `MusicSteps.seed`, and whether it is on or off.

**FR-303-2 — One rule, kept with the steps.** The exclusion is one predicate in `TitleChecks`
(`isTitleStep(step)`). It reads `MusicSteps.isMusic` and the two step constants, and holds no copies of step
names. A music step added to `MusicSteps.ALL` later is left out without touching `TitleChecks`. A future
whole-library step outside music joins the predicate in the phase that adds it. That phase's spec must say so.

**FR-303-3 — Nothing else changes.** The pipeline, the scheduler, `item_step_run`, the pre-run dialog (154), the
Activity steps and the album, artist and audiobook pages stay as they are. Rows for the steps that remain keep
their order, text and actions.

## Not in scope

- A Checks card for albums, artists or audiobooks, where the music steps would belong. Those pages have none
  today. Giving them one is a separate phase.
- Labels for the music steps in `STEP_LABELS`. With FR-303-1 in place the card never shows them.

## Acceptance

1. With the household's pipeline (seeded music steps included), a film's Checks card shows no music step,
   `wait`, `notify`, `build_recommendations` or `build_suggestions`. The same holds for a series and a music
   video.
2. With an empty configured pipeline (the built-in default), the same holds.
3. Every other row reads exactly as before.
4. A unit test runs `TitleChecks.forItem` on a film with a pipeline containing every `MusicSteps.ALL` step, then
   asserts that none of them is on the card and that `scan_files`, `pull_tmdb` and the file checks still are.
