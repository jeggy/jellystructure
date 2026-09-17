# Phase 233 — A credits marker belongs to the end of the file, an intro to the start

> 775 production episodes carry a **credits marker that starts inside the intro**, so *Skip Credits*
> is offered during the opening theme. Found 2026-09-16 while building 223 (its open question 4) and
> left as "needs its own phase". Three causes, one missing rule: nothing in `detect_segments` ever
> asks **where in the file** a marker sits.

## Status

`✓ Built` 2026-09-17 — written the same day from a read-only copy of the production database, not
dev-reviewed, **not deployed**; `linuxX64Test` 382/0 (+13: `SegmentPositionRulesTest`,
`SegmentPositionDetectionTest`). Acceptance 2 and 3 are production observations still owed — they need
a deploy and one scheduled `detect_segments`. Backend-only (`SegmentPositionRules.kt` in `commonMain`,
`SegmentDetection.kt`, `PipelineStepOps.kt`, `PipelineEngine.kt`, `DetailService.kt`). No wire change,
no schema change, no new string.

**Found while building:** only **1 175 of 15 001** marker rows belong to a file whose length is
stored (`Track.durationMs`, phase 222, fills in as files are re-examined). So the no-I/O trigger of
FR-233-5 also takes TMDB's whole-minute runtime — as a *trigger only*, and only for a marker wrong by
a wide margin (intro ending past 70 %, credits starting before 30 %); the purge that follows always
measures the real file. By that estimate the defect is wider than the overlap query shows: about
**405 "intro" rows sit in the second half** (19 titles — an outro theme with no credits row beside it
overlaps nothing) and about **310 credits rows in the first half**.

**Numbering:** verified against `STATUS.md` 2026-09-17 — admin taken through **232**.

## Measured (production copy, 2026-09-17)

`media_segment` holds 7 373 intro and 7 628 credits rows. Joined per episode, **775 credits rows start
before the intro has ended** (32 titles; 582 within 2 s of the intro's own start; none manual, none
locked).

| credits source | intro source | file | where the pair sits | rows | what actually happened |
|---|---|---|---|---|---|
| fingerprint | fingerprint | < 20 min | second half | 312 | the pair is the **outro** theme; the *intro* is the wrong row |
| fingerprint | fingerprint | < 20 min | first half | 171 | the pair is the **intro** theme; the *credits* is the wrong row |
| fingerprint | fingerprint | duration not stored | — | 172 | same two shapes (La Curva, 3-minute files) |
| chapter | fingerprint | ≥ 20 min | first half | 59 | a chapter titled **"Opening Credits"** matched the keyword `credits` |
| heuristic | fingerprint | < 20 min | second half | 49 | the heuristic credits is *right*; the "intro" around it is the outro theme |
| other | | | | 12 | mixtures of the above (two-cartoon files, mid-file title cards) |

Median affected episode: **7 minutes** (min 3, max 59).

### Cause 1 — the two fingerprint windows cover the same audio

The intro pass fingerprints the **first 900 s** (`FINGERPRINT_WINDOW_SEC`), the outro pass the **last
300 s** (`OUTRO_FINGERPRINT_WINDOW_SEC`). Any file shorter than 20 minutes has them overlapping; a
5-minute cartoon has the head window covering the *whole file* and the tail window covering all of
it too. `findIntroMatch` returns the single best repeated run, so both passes find the **same**
theme — whichever of the opening and closing themes correlates best — and one writes it as `intro`,
the other as `credits`, at the same timestamp (Æbler i natkjole S01E02: intro 333 713 ms, credits
333 650 ms, file 350 824 ms).

### Cause 2 — "Opening Credits" is a credits keyword hit

`fromChapters` matches `title.contains("credits")`. Its only guard is *not in the first 60 s*.
Evidence rows on production: **68 accepted** `Opening Credits` chapters between 61 s and 435 s (It's Always Rainy in Pittsburgh: 104 s). Sixteen more were saved only by the 60 s guard.

### Cause 3 — nothing checks a position, anywhere

The 159 guardrails bound an intro's *length* (360 s) and *start* (1 200 s) — absolute numbers that
mean nothing on a 5-minute file. The heuristic scans the last 180 s, which on a 5-minute file is 60 %
of it. No tier, and no read path, knows the file's duration when it accepts a marker.

## Requirements

**FR-233-1 — One position rule, in `commonMain`.** `SegmentPositionRules`: an **intro** is plausible
iff it **ends at or before half** the file's duration; a **credits** marker iff it **starts at or
after half**. Other kinds are not judged. An unknown duration judges nothing (the rule answers
*plausible*). Half is deliberately coarse: it is not an estimate of where themes live, it is the one
line no intro and no end-credits can be on the wrong side of — and it makes an intro/credits overlap
impossible by construction. Lives beside `SegmentEditRules` so the editor can adopt it later.

**FR-233-2 — Every automatic write passes the rule.** Chapter, heuristic, intro-fingerprint and
outro-fingerprint writes each check their result against FR-233-1 with that file's duration
(`Track.durationMs` when stored — phase 222 — else one `ffprobe` duration call, cached per run) and
write **nothing** on a failure. A manual write is never judged: the operator may know about a
20-minute post-credits reel.

**FR-233-3 — The fingerprint windows stop overlapping.** Before correlation the head fingerprint is
cut at half the file and the tail fingerprint's frames before half are dropped (its
`windowStartMs` advanced by exactly the frames removed). The on-disk fingerprint cache is untouched —
the cut is applied to the loaded frames, so nothing is re-decoded. This is what lets the intro pass
find the *real* intro on a short file instead of losing it to a stronger closing theme; FR-233-2
alone would only turn a wrong marker into a missing one.

**FR-233-4 — A chapter that names the opening is not the credits.** A title matching an INTRO
keyword can never be the credits chapter; `opening credits` joins the INTRO keywords (it is an exact
intro, with bounds — better than a fingerprint). The credits chapter additionally passes FR-233-1
when the duration is known; the 60 s guard remains for when it is not. Evidence rows label such a
chapter `intro`.

**FR-233-5 — Existing wrong rows repair themselves, with no migration.** At the start of an
episode's detection, every row for it that is **not manual, not locked and not checked**, and that
fails FR-233-1, is deleted (with its evidence) — then detection runs as for an episode that never had
one. `detect_segments`' `needsDetection` also answers true for an item holding such a row (judged
from stored durations plus the overlap test, no I/O), so the scheduled pipeline picks the 32 titles
up on its own. A human-touched row is never deleted, whatever it says.

**FR-233-6 — The viewer is protected before the repair arrives.** `DetailService.toTv` never serves
an intro/credits pair where credits start before the intro ends: each side of such a pair is served
only if it is manual, locked or checked. Serving no marker is the pre-150 behaviour and is harmless;
serving *Skip Credits* over the opening theme is not.

## Non-goals

- No change to `findIntroMatch`'s scoring, the consensus clustering or the 159 guardrails.
- No startup sweep and no `.sqm` — FR-233-5 rides the existing segments lane and its pacing (213).
- No editor change. `SegmentEditRules` keeps governing manual edits.
- Multi-episode files stay unsupported (163).

## Acceptance

1. `linuxX64Test`: the rule's boundaries; an `Opening Credits` chapter yields an intro and no
   credits; head/tail frame cuts keep absolute timestamps exact; a purge deletes a fingerprint row
   and spares a manual, a locked and a checked one.
2. After one scheduled `detect_segments` on production, the measuring query above returns **0**.
3. Æbler i natkjole S01E02 ends with a credits marker near 333 s and either a first-half intro or none.

## Open questions

1. **Seven item ids in `media_segment` no longer exist in `media`** (`bob-the-builder`, `pratarna`,
   `go-buster`, three `bugs-bunny-builders*`, `byggare-bob-1999`) — slug renames that left their
   segment rows behind. `pruneOrphans` works per item, so nothing ever visits an item that is gone.
   Harmless to viewers, not fixed here.
2. Two-cartoon files (New Wacky Toons: a title card at 638 s of 1 320 s) have a mid-file "intro"
   that passes the rule by 7 s. It is a real repeated title card; whether it should be skippable is a
   product question, not a detection one.
3. Should the editor's evidence lane show *why* a candidate was refused by position? Today a refused
   candidate leaves no trace.
