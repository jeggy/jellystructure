# AI re-rank is switched per viewer, quiet viewers can be skipped, and Recommendations & suggestions run on a schedule you pick

Task: DEB-2823

> Owner, 2026-10-29 (DEB-2823): *"I just cancelled a lot of stuck AI Suggestions that were stuck in the Activity.
> How come there were so many and how come it was costing so much when we have the "Every week" set here? Also can
> we change this configuration so it has a bit more settings, like every 5 hours or every 1 month, basically cron
> options as well. And maybe something like, skip user if no watch activity since last generation etc."*

## Scope

Backend and admin. No Ravilo client changes, and no change to anything a Ravilo client reads.

The investigation is the `investigation` document on DEB-2823. Measured on prod on 2026-10-29:

- Of the 48 users the re-rank treated as viewers, 44 were QA throwaway accounts (`qa-…`).
- The re-rank job had reached its $5.00 monthly limit, so the rows it held back looked stuck on Activity.
- Three paths queued a paid re-rank whatever *Every week* said:
  - a scan started by hand (FR-272-7, DEB-1664 FR-6.3);
  - a new viewer's first build (FR-270-7);
  - *Rebuild now* (FR-272-6).

**Owner decisions, 2026-10-29, on the DEB-2823 card:**

1. *Test accounts:* "Yes, no recommended for them. But let's make it so, that we can disable AI ranking for
   specific users within the AI Settings. And then as a rule for the QA team, whenever they are creating QA users,
   that should make sure to disable that for those users." This spec reads "no recommended" as **no AI re-rank**.
   A test account keeps its free, standard Recommended row, which is what the card's recommended option offered.
   It is what QA needs to test that row with a throwaway account.
2. *Skipping quiet viewers:* "Should be a setting, so it can be any of your suggestions here." That means three
   choices (FR-2).
3. *A scan started by hand:* "Both should be possible, and the "Scan now" should make it very clear what it does and
   what it skips etc." (FR-4).
4. *The schedule:* the same picker as *Find new & changed*, plus monthly (FR-3).

It changes shipped promises. Each of those specs carries a dated amendment pointing here:

- FR-269-8: the build's cadence and who is rebuilt;
- FR-270-7: who is re-ranked, and the first-build guard;
- FR-272-7: a run started by hand re-ranks only when asked;
- FR-274-2: the test-account rule;
- FR-274-7: the build's cadence;
- DEB-1664 FR-2, FR-3.3, FR-5 and FR-6.3: the keys, when the feature is due, and what the Scan dialog shows and does.

The AI runner itself is unchanged: the queue, the batches, the monthly limit as a hard stop, the validators and
the transcripts.

## Requirements

### FR-1 — AI re-rank is switched per viewer

1. **One switch per Jellyfin user: *Re-rank with AI*.** It lives in the admin under AI ▸ the Re-rank job. The list
   holds every Jellyfin user the server can read (`GET /Users`), not only the recently seen ones. That way a user
   can be switched off before their first device signs in. Each row carries:
   - the name;
   - when a Ravilo device of theirs was last seen, or *never*;
   - whether their row currently follows an AI order, and when it was written;
   - the switch.
2. **The default.** A user with no stored choice is **on**, unless the test-account rule (FR-1.6) matches their
   name, in which case they are **off**. The list says which rows are on or off by that default (*off — test
   account*), so the default is never silent.
3. **Off means no AI for that user, by every path.** A user switched off is never queued for re-rank:
   - not by the scheduled build;
   - not by a run started by hand (FR-4);
   - not by a first build (FR-270-7);
   - not by *Rebuild now*.

   For a user switched off, *Rebuild now* rebuilds their standard list and queues no AI. The control says so
   (FR-11 of DEB-1664: shown, with its reason).

   Switching a user off also does three things at once:
   - deletes their waiting re-rank row in `ai_queue`;
   - deletes their stored AI order and rebuilds their list at once, without AI, so their next Home is in the
     standard order. The AI order is copied into `recommendation` at build time, so deleting it alone would
     change nothing until the next build;
   - leaves a batch that is already out alone. Its answer for that user is dropped when it is read.
4. **Switching a user on** queues nothing by itself. Their next scheduled build or *Rebuild now* re-ranks them.
5. **Stored in the database, not the TOML:** `ai_viewer(user_id PRIMARY KEY, rerank INTEGER, set_by TEXT, set_at
   INTEGER, last_asked_at INTEGER)`. The user id is in the same form as `ai_order.user_id`. A row for a user
   Jellyfin no longer has is not shown, and is deleted the next time the list is read.
6. **The test-account rule is widened.** A name that starts with the word *Test* (FR-274-2, unchanged) **or**
   starts with `qa-`, case-insensitive, is a test account. The rule decides two things:
   - FR-1.2's default;
   - whether the user's watching counts as a source for the household's suggestions (FR-274-2). It does not.

   The rule does not keep anyone out of the free, standard Recommended list (owner decision 1).
7. **Routes (admin, additive):**
   - `GET /api/ai/viewers` answers the list. Every row carries its server-built sentences: the default reason, the
     last-seen line and the AI-order line.
   - `PUT /api/ai/viewers/{userId}` takes `{ "rerank": true|false }` and answers the row. An unknown user is a
     `404`.

   The QA seats use this route (FR-6).
8. **The first-build guard survives a restart.** FR-270-7's *at most once a day for a viewer with no AI order* is
   read from `ai_viewer.last_asked_at`, not from memory. That way a restart no longer buys every new viewer a
   second re-rank the same day.

### FR-2 — A viewer who has watched nothing since the last build

1. **One setting per part**, under Settings ▸ Processing ▸ Recommendations & suggestions. Recommendations and
   Suggestions each get *When nobody has watched anything new*, with three choices:
   - ***Rebuild, skip the AI*** (the default). The free list is rebuilt. The paid AI step is skipped:
     - Recommendations: no re-rank for that viewer. Their stored AI order still applies, filtered to the new
       list, as today.
     - Suggestions: no AI groups for the build; the build keeps the genre groups.
   - ***Skip both***. Nothing is rebuilt for that viewer, or for the household in Suggestions' case. The list
     stays exactly as it is.
   - ***Rebuild and re-rank***. Today's behaviour.

   The keys are `[processing.suggestions]` `recommendations_quiet` and `suggestions_quiet`, each `skip_ai` |
   `skip_both` | `rebuild`. An absent key reads as `skip_ai`.
2. **"Watched something new" is measured from Jellyfin's own last-played dates.** The build already reads each
   viewer's history, and a viewer has watched something new when an item there was played, or started, after the
   reference point:
   - **Recommendations** compares each viewer separately. With *Rebuild, skip the AI*, the reference point is
     their last AI order (`ai_order.ordered_at`). With *Skip both*, it is their last build
     (`recommendation.built_at`). A viewer with no AI order yet is never quiet for the AI step (FR-270-7).
   - **Suggestions** compares the household: the included viewers (FR-1.6) against `suggestion_build.built_at`.
3. **Starter lists are always rebuilt.** They cost nothing and need no viewer.
4. **The build's summary line names the skip.** For example: *rebuilt 4 viewers · 3 re-ranked · 1 skipped,
   nothing new watched*. The history line and the AI job's line say it too.
5. **A run started by hand with the AI ticked (FR-4.3) ignores the rule for the AI step.** The person asked.

### FR-3 — Each part runs on a schedule you pick

1. **The picker.** Recommendations and Suggestions each get the picker that *Find new & changed* has (DEB-1664
   amendment of 2026-10-24, slice 2), plus one mode:
   - *Every N hours* (and the minute past the hour);
   - *Daily at*;
   - *Weekly on*;
   - ***Monthly on*** (new): a day from 1 to 28, and a time. Days 29–31 are left out, so every month has the day.
   - *Custom*: the cron, validated live by `POST /api/config/validate-schedule`, which holds Save while it is
     invalid.

   A blank *Custom* means *only when you scan*.
2. **The keys.** `[processing.suggestions]` gains `recommendations_schedule` and `suggestions_schedule`, each a
   cron. A saved cron that matches a preset shows as that preset.
3. **When a part is due.** A part is due when the cron's first fire after the part's last build has passed:
   `nextFireEpochSec(expr, lastBuilt) <= now`. A part never built is due. The existing feature tick checks it, so a
   fire is run at the next tick. Fires missed in a row are run **once**, never once each. A part that is off, and
   Suggestions without Seerr, are never due (unchanged).
4. **Carry-over, once, at load**, when a schedule key is absent: `*_every` becomes a cron at the **hour and minute
   of that part's last build**. `daily` becomes *daily at* that time, and `weekly` becomes *weekly on* that weekday
   at that time. That way prod keeps building when it builds today. With no build yet: daily or weekly at 04:00,
   and weekly on Monday.
5. **Rollback safety, one release** (DEB-1664 FR-2.4's rule). For one release, every save also writes `*_every`,
   derived from the schedule: `daily` when its fires are at most 36 hours apart, `weekly` otherwise. A release
   later it is no longer written. Both keys round-trip through the admin's config model (DEB-1664 FR-2.5).
6. **The row's interval in words, its next run and the AI tab's estimate follow the schedule.** FR-270-7's monthly
   estimate becomes:

   *runs a month × viewers switched on*

   It says that this is the most a month can cost, before any skip (FR-2) takes some off. Every sentence is built
   on the server (FR-12 of DEB-1664).

### FR-4 — A scan started by hand: the AI only when asked, and the dialog says what it does

1. **Each part's line says what it will do in this scan.** In the Scan dialog, the right pane of Recommendations &
   suggestions gives each part one server-built line. For example:
   - *Rebuilds 4 viewers' lists now — the schedule would next run Tue 20:00*;
   - *Not in this scan — next by schedule Tue 20:00* when unticked.

   The same holds for Music and Audiobooks, whose runs a manual scan also takes off schedule (DEB-1664 FR-6.3).
2. **A dependent tick under each of the two parts: *and ask AI*.** It is unticked whenever the dialog opens, and
   is never remembered (DEB-1664 FR-5.7 does not apply to it). Its line is built on the server:
   - Recommendations: *Re-ranks 4 viewers switched on · up to $0.26 · $0.09 of this month's $5.00 left*.
   - Suggestions: *Asks AI to group the list · up to $0.07*.

   When the AI is off, or the job's limit leaves too little, the tick is shown disabled with its reason (FR-11 of
   DEB-1664).
3. **What it does.** A part ticked without *and ask AI* rebuilds its free lists now, whatever its schedule says. It
   queues no AI, and the quiet rule (FR-2) applies to the rebuild. A part ticked **with** *and ask AI* also queues
   the AI step for every viewer switched on (Recommendations) or for the household (Suggestions), whatever they
   watched. It is the one way to re-rank the whole household by hand.
4. **The summary above *Start scan*.** One line lists what runs and what does not, and the AI line, for example:
   *Runs: Find new & changed, Metadata & artwork, Recommendations · Skips: Intro & credits, Suggestions · AI:
   none*.

   The server sends each part's *runs* and *skips* sentence and the AI sentences. The dialog only shows the ones
   that match its ticks: it selects, it does not compose (DEB-1664 FR-12.1). The started toast and the history line
   name the AI the same way (*AI: re-rank 4 viewers*, or *AI: none*).
5. **`POST /api/processing/scan`** gains `ai`, a list of part ids whose AI step is asked for (default empty). An
   id that names no AI-capable part is ignored. `POST /api/scan` and any caller that does not send it ask for no
   AI.
6. **Other whole-household rebuilds ask no AI.** The loose-films repair's recommendations build
   (`LooseFilmsService.queueRecommendationsBuild`) rebuilds the free lists only.

### FR-5 — Activity says why a request waits

1. FR-272-4's sentence already stands on the job's line, for example *37 waiting — this month's limit: $0.09
   left, the next request could cost up to $0.08*. That stays. The owner read the waiting rows as stuck, so two
   things are added:
   - each waiting row that the limit holds back carries a *held by this month's limit* chip;
   - the job line gets **Remove all waiting**, one control whose count is built on the server, next to each row's
     *Remove* (FR-272-9).
2. **Route (admin, additive):** `DELETE /api/ai/queue?job=` removes every waiting row of that job and answers the
   count. A batch already out is not touched (`POST /api/ai/batches/{id}/cancel` stays the way to stop one).

### FR-6 — The QA team's rule

1. A QA seat that creates a Jellyfin user switches that user's AI re-rank off with `PUT /api/ai/viewers/{userId}`
   `{ "rerank": false }`, straight after creating them. It does this even though the `qa-` default already does:
   the explicit switch is the rule, and the name is the safety net.
2. The rule is written into the QA seats' playbook by the QA Lead. It is not code, and it does not wait for
   anything but FR-1.7 reaching prod.

## Acceptance

1. On a copy of prod's database:
   - every `qa-…` user is listed off by default, and the 4 people are listed on;
   - a scheduled build queues re-ranks for the users switched on only;
   - switching a user off removes their waiting row and their AI order.
2. A scan started by hand with Recommendations ticked and *and ask AI* unticked queues **no** `ai_queue` row. With
   the AI ticked, it queues one per viewer switched on, whatever they watched.
3. With *Rebuild, skip the AI*, a viewer whose newest last-played date is older than their AI order is rebuilt and
   not queued. With *Skip both*, their `recommendation.built_at` does not move.
4. *Every 5 hours* (`0 */5 * * *`) builds once per fire. Two missed fires run once. *Monthly on 1 at 04:00* is due
   on the 1st and not before.
5. A config with `recommendations_every = "weekly"` and a last build on a Tuesday at 20:00 loads as *Weekly on
   Tuesday at 20:00*. A save writes both keys.
6. A restart does not re-ask a first-build re-rank asked within the last day.
7. In the admin, at desktop and 412 px, in both themes:
   - the AI tab's viewer list;
   - the Processing row's two pickers and two quiet-viewer choices;
   - the Scan dialog's lines, the *and ask AI* ticks and the summary.

   Each matches its design round's pictures. Playwright proves that every control acts and that 412 px has no
   horizontal overflow; the likeness is on the QA task.

## Not in this spec

- Changing a job's model or monthly limit. Those are already on the AI tab, and the owner's to set.
- The support job (phase 321). Its input is a ticket, not a viewer.
- Theme tags (job 2). It is off on prod, and its unit is a title.
- Cleaning up devices of deleted users. That is DEB-2675.

## Build order

1. **Backend — stop paying for non-viewers** (FR-1.2–1.8, FR-4.5, FR-4.6, FR-5.2). It needs a migration. Once this
   is on prod, FR-6 can start.
2. **Backend — schedule and quiet viewers** (FR-2, FR-3.2–3.6). It needs no migration, and adds config keys.
3. **Design round** (Rannvá), in parallel with 1 and 2:
   - the AI tab's viewer list;
   - the Processing row with two pickers (monthly added) and two quiet-viewer choices;
   - the Scan dialog with Recommendations & suggestions selected: the lines, the *and ask AI* ticks and the
     summary;
   - the toast.
4. **Admin** (FR-1.1, FR-2.1, FR-3.1, FR-4.1–4.4, FR-5.1), built on the round's pictures.
