# Phase 303 — A title's Checks card lists only the steps done to it

> Found 2026-10-03 from an owner question about a film's page: *"fetch_lyrics · not yet · not yet · next due 2027,
> with the next scan — why do I see this on movies?"*

## Status

`Planned`. Written 2026-10-03 (dev-authored), against `main` `c01c4a3b`. Number verified free (admin specs top at
302, locally and on `origin/main`). Dev-reviewed 2026-10-04 (section at the end). FR-303-2's predicate moves
next to `MusicSteps` and the engine uses it too (item 3). Acceptance 4 becomes a plain unit test (item 4).

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

## Dev review (2026-10-04, against `main` `f5f8cfc7`)

Read against `TitleChecks`, `PipelineEngine.runPipeline`'s step loop, `MusicSteps`, `StepRunStore`, the music package,
`MusicVideoLinks` and the admin's `MediaDetail.loadChecksCard`. The design holds. Eight items, none for the owner.

1. **The cause is confirmed.** `TitleChecks.kt:70-71` filters out `scan_files` (which it then puts first),
   `wait`, `notify`, `RecommendationsStep.STEP` and `SuggestionsStep.STEP`, and nothing else. Every music step
   falls through to the `else` branch (`:102-117`). There `STEP_LABELS[step] ?: step` gives the raw id, `run` is null
   (so *not yet* for both last and outcome), and `nextDue` reaches *"$nextScanText, with the next scan"*.

2. **One correction to "What happens" 2.** The built-in default pipeline (`rawPipeline`, `PipelineEngine.kt:159`)
   holds **six** music steps. `write_tags` is not in it. It only reaches a pipeline through `MusicSteps.seed`, and only
   when the configured pipeline is not empty. The fix is the same either way, because FR-303-1 leaves out all of
   `MusicSteps.ALL`.

3. **FR-303-2: the predicate goes next to the steps, and the engine uses it too.** The engine already knows which
   steps are whole-library, in three places in its step loop:
   - `:375` skips a music step on anything other than a Library run that has music deps;
   - `:383` skips `build_recommendations` on a `SingleItem` run;
   - `:384` skips `build_suggestions` on a `SingleItem` run.

   A fourth copy in `TitleChecks` is how this bug happened. So: add `object WholeLibrarySteps { fun contains(step) =
   MusicSteps.isMusic(step) || step == RecommendationsStep.STEP || step == SuggestionsStep.STEP }` in
   `config/AppConfig.kt`, beside `MusicSteps`. `TitleChecks` excludes `WholeLibrarySteps.contains(step)` plus the
   control steps `wait` and `notify`. The engine's `:383-384` become one `SingleItem && WholeLibrarySteps.contains(...)`
   skip. `:375` stays as it is, because it also governs Library runs without music deps, but it reads
   `WholeLibrarySteps` for its music half through `MusicSteps.isMusic` as now. A future whole-library step is then
   added in one place, and both the run and the card follow it. That replaces FR-303-2's "the phase that adds it must
   say so".

4. **Acceptance 4 needs no database.** `TitleChecks` takes a `JellystructureDb`, `MediaStore` and four services, and
   no test builds one today. Take the step selection out of `forItem` into a pure companion function
   `cardSteps(pipeline: List<PipelineStep>): List<PipelineStep>` (`scan_files` first, then every step that is neither
   whole-library nor a control step, `distinctBy` step). `forItem` calls it. Acceptance 4 then runs in
   `TitleChecksTextTest` on `cardSteps(...)`:
   - with `MusicSteps.ALL` plus the per-title steps, every music step is gone and `scan_files`, `pull_tmdb` and the
     three file checks remain, in order;
   - with an empty list, it gives `scan_files` alone (today's fallback when the configured pipeline is empty goes
     through `effectivePipeline`, which is unchanged).

5. **Music videos are untouched by every music step.** `MusicVideoLinks.forArtist` is a read at request time
   (`MusicRoutes.kt:409`, `MusicTvService.kt:269`). No music step reads or writes a `media_item`. The music package
   never calls `StepRunStore`, and the engine's `ran()` is called only inside the per-item steps. So FR-303-1's claim
   holds for films, series and music videos alike. There are also **no stray `item_step_run` rows** to clean up, so no
   migration.

6. **No wire or page change.** `loadChecksCard` (`MediaDetail.kt:3260`) renders `checks.steps` in the order it gets
   them and decides nothing from step ids (FR-261-11). `TitleChecksDto` is unchanged. An admin bundle that is already
   open just gets fewer rows. The series page uses the same route and is covered.

7. **A second false row disappears with it.** `forItem` reads `config.scan.pipeline` raw (`:67`), not
   `effectivePipeline`. So with *Fetch lyrics* off in Settings, `fetch_lyrics` was still shown as on with a next-due
   date, even though the engine reports *"lyrics are off in Settings"*. That row is gone with FR-303-1. Nothing else on
   the card is affected by the raw read: the per-title steps have no Settings switch of their own.

8. **Design files.** No mockup draws a title's Checks card (`design/app/media.html` and `series.html` have none). So
   there is nothing to update on the design side.

