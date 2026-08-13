# Phase 163 — Intro &amp; credits editor, and segments in Jellyfin (FR-SEG2)

**Status:** Planned — design-complete 2026-08-13, **not yet dev-reviewed**.
**Date:** 2026-08-13
**Builds on:** phase-150 (segment detection), phase-159 (detection accuracy), R182 (Skip Intro /
Skip Credits in Ravilo).
**Design:** `design/app/segments.html` · `segments.js` · `segments.css`; exploration (three
directions, B rejected) in `design/app/Segment Editor - Directions.html`.

---

## 1. Problem

Phase 150 detects intros and credits and Phase 159 made that detection better, but the operator's
only surface is a **read-only bar** in an episode row: you can re-run detection or lock what it
found, and nothing else. When the detector is wrong — a cold open twice the usual length, a
recap matched as the intro, a film with no chapter marks at all — there is no way to say what the
right answer is. Two consequences:

1. **A wrong marker is permanent until detection happens to change its mind.** Locking freezes the
   wrong value; re-scanning re-derives the same wrong value.
2. **Only Ravilo benefits.** The markers live in jellystructure's own DB and reach only Ravilo's
   player. Every other Jellyfin client in the house — the web client, the phone app, an Android TV
   box someone else set up — skips nothing, even though Jellyfin has had a **Media Segments API**
   since 10.10 and its clients already honour it.

## 2. Goals / non-goals

**Goals**
- A real editing tool: view, adjust and lock every segment on a title, watching the actual video.
- Make the **season** the unit of work, not the episode — the realistic job is 22 (or 200) episodes
  where 19 are right and 3 are not.
- Publish confirmed markers to Jellyfin so every Jellyfin client skips them.
- Read Jellyfin's own segments (Intro Skipper and friends) as one more detection source.

**Non-goals**
- No re-design of detection itself (150/159 stand; this consumes their output).
- No chapter editing, no NFO change — segments are not an NFO concept.
- No new Ravilo work: R182 already consumes our markers unchanged.
- Not a video editor. Nothing here re-encodes, cuts or writes a media file.

## 3. Segment kinds

Five kinds, all first-class, matching Jellyfin's own vocabulary so publishing is a straight map:

| Ours | Jellyfin `MediaSegmentType` | Ravilo behaviour today |
|---|---|---|
| Recap | `Recap` | — (R182 ignores it) |
| Intro | `Intro` | Skip Intro pill |
| Next time | `Preview` | — |
| Credits | `Outro` | Skip Credits / Next episode card |
| After-credits | `Outro` (protected) | suppresses auto-skip (stinger) |

`Commercial` is deliberately unmapped — nothing in this library has ad breaks.

## 4. Requirements

### §A The tool (`app/segments.html`)
One fullscreen surface, no sidebar, opened from a season, a single episode row, or a movie's detail
page, and returning where it came from. Two views, one URL each — `segments.html` (sheet, scoped to
the series in context) and `segments.html?movie=<slug>` (trim view, straight in, no sheet behind it).

### §B Season sheet — the front door
- **Every episode on one shared timeline**, one row each, aligned to the longest runtime. An episode
  whose intro starts three minutes in is visible without opening anything; it is drawn with a
  **disagrees with the season** outline.
- Row shows: state (checked · matched, not checked · guessed, not checked · nothing found ·
  disagrees), lock, and whether Jellyfin has it.
- **Season stats**: found, guessed-not-checked, disagreeing, locked, and **what the season agrees
  on** — the median intro of the non-outlier episodes, which is the value bulk-apply writes.
- **Multi-select → bulk**: give them the season's intro · lock · detect again. Plus **select
  everything that needs me**, which picks exactly the rows that are guessed, empty or disagreeing.
- Clicking a row opens a **drawer** below with the player, that episode's timeline, and the four
  decisions that resolve 90% of cases: use the season's intro · trim by hand · lock · fine as it is.

### §C Trim view — one title
- **Real playback**, streamed from Jellyfin through the existing session (§F).
- One timeline: ruler, the segment bars with draggable in/out handles, and beneath it an
  **audio waveform** and an **evidence lane** — the black frames and silences the heuristic used, the
  span the cross-episode fingerprint matched, chapter marks, and anything Jellyfin itself reported.
  The operator can see *why* the detector said what it said.
- **Marker rows** under the timeline: one per kind, timecodes with ±1s steppers, how long it is,
  where it came from, **play the cut** (loops 3 s either side), and the lock.
- Missing kinds are offered as **＋ Recap / ＋ Next time / ＋ After-credits** — adding one is how a
  human marks something detection has no chance of finding.
- **Keyboard**: `I`/`O` set in/out at the playhead, `,`/`.` nudge a frame (`⇧` a second), `L` lock,
  `↵` save and open the next unchecked episode, `Esc` back to the sheet.
- **Review queue** rail: the season, in order, with each episode's state and mini-timeline.

### §D Locks
- **Per marker, not per title** — the credits are usually right when the intro is wrong.
- A locked marker is **never** written by `detect_segments`, on any path (scheduled scan, targeted
  re-detect, event-driven pipeline). Same guarantee as phase-151's artwork lock; implement it the
  same way and test the same holes.
- Any manual edit implicitly sets `source = manual`. **Manual is not automatically locked** — the
  operator locks deliberately, and the UI says what the lock buys.
- Bulk lock exists; bulk unlock deliberately does not.

### §E Jellyfin as source and destination
- **Read**: Jellyfin's `MediaSegments` for an item are pulled at detection time and offered as a
  candidate with `source = jellyfin`, never silently overwriting ours. Where Jellyfin agrees with us
  within a tolerance, the evidence lane says so — that agreement is the cheapest confidence signal
  we have.
- **Write**: confirmed markers are pushed to Jellyfin's Media Segments API so every Jellyfin client
  skips them.
- **Only what a human confirmed is ever published** (decided 2026-08-13, not a setting). A marker
  counts as confirmed when it is **checked** (a human looked at it in the tool) or **locked**. A
  detector-only guess stays jellystructure-side, where Ravilo can still use it with its own
  confidence handling; it is not broadcast to every client in the house. The sheet says which
  episodes are `ready to publish`, which are `not confirmed`, and the Publish button counts only
  the confirmed ones.
- Publishing is **idempotent and diff-based**: replace only the kinds we own for that item, never
  wipe segments some other Jellyfin plugin wrote.
- A publish failure is a **triage row**, not a toast into the void.

### §F Streaming inside the admin
- The player uses the **admin's existing Jellyfin session**; no new auth surface, no public URL.
- **Direct play or nothing.** We request the original stream and, when the browser cannot decode it,
  say so plainly and offer the frame-accurate fallback (timecode entry + evidence lane) rather than
  transcoding. Transcoding a 4K remux so someone can find an intro would put ffmpeg load on the box
  during a scan, which is exactly the class of problem phase-134/135 exists to avoid.
- Seeks are **short and frequent** by nature (a boundary check is a 6-second loop). Confirm the
  Jellyfin seek/transcode-throttle behaviour before building §C's loop control.

### §G Where it shows up elsewhere
Intro &amp; credits is a **peer tab**, not a card buried in Overview or a link stranded in a toolbar —
it is its own body of work (detection state, evidence, a season's worth of decisions) and earns the
same standing as Tracks &amp; subtitles or Artwork.
- **Series detail** (`series.html` / the per-series pages): a dedicated **Intro &amp; credits** tab
  alongside Seasons &amp; episodes — season stats (found · guessed-not-checked · disagreeing · locked ·
  what the season agrees on) and **Open the season editor →** into the sheet. Per-episode rows on
  Seasons &amp; episodes keep their own **Open the editor** shortcut straight into that episode's trim
  view; the season-level entry lives only on the new tab now, not duplicated in that tab's toolbar.
- **Movie detail** (`media.html`): a dedicated **Intro &amp; credits** tab (not Overview) with the
  marker bar, where it came from, whether it is confirmed, and **Open the editor** →
  `segments.html?movie=<slug>`. The tool opens straight into the trim view — a film has no season to
  sheet — with the rail listing **films to check** instead of a season, and Publish sitting in the
  header (there is no sheet behind it to publish from). Most films have credits and no intro; the
  tab says so rather than showing an empty intro slot as a fault.
- Dashboard/triage keeps the existing **low-confidence segments** and **no segments detected** rows;
  both now deep-link into the sheet with that filter applied.

## 5. Data model

```
media_segment(item_id, episode_id?, kind, start_ms, end_ms,
              source(fingerprint|heuristic|chapter|jellyfin|tmdb|manual),
              confidence?, locked, checked_at?, published_at?, published_hash?)
```
- One row per (item, episode, kind). Detection writes only where `locked = 0`.
- `published_hash` is what makes publishing diff-based and cheap to re-run.
- `checked_at` is what "checked" means on the sheet — a human looked at it. It is not `locked`.

## 6. API

```
GET   /api/segments?series=&season=            sheet payload (+ per-episode markers, states)
GET   /api/segments/{itemId}                   one title, incl. evidence + Jellyfin's own
PUT   /api/segments/{itemId}/{kind}            { startMs, endMs, locked? }  → write-through
DELETE/api/segments/{itemId}/{kind}
POST  /api/segments/apply                      { itemIds[], kind, startMs, endMs, lock? }  bulk
POST  /api/segments/checked                    { itemIds[] }
POST  /api/segments/publish                    { itemIds[] | seriesId }  → Jellyfin
POST  /api/segments/detect                     { itemIds[] }  → existing detect_segments step
```
Writes are **write-through** (Phases 71/74): they land on the row immediately and the UI reports
what happened. There is no staged/apply model here.

## 7. Build order

1. `media_segment` table + migration; back-fill from the Phase 150 store; `locked` honoured in
   `detect_segments` on **every** path (the phase-151 lesson).
2. REST surface (§6) + the sheet view, read-only.
3. Bulk apply / lock / checked, and season consensus.
4. Trim view without video: timeline, handles, steppers, evidence lane, keyboard.
5. Playback via the admin's Jellyfin session (§F), then the loop-the-cut control.
6. Jellyfin read-in as a source, then diff-based publish + the publish-failure triage row.
7. Movie entry point; dashboard deep links.

## 8. Notes from building the mockups
- The trim view's video pane needs an explicit height floor in a column layout — its overlay chrome
  (timecode, transport, direct-play badge) is absolutely positioned and will render outside a
  flex-shrunk box rather than force it taller. Real player embed should size the same way (a fixed
  or `aspect-ratio`-based pane, never `flex:1;min-height:0`) so the stage scrolls instead of the
  player collapsing.
- Wording that names *why* a marker matched must branch on movie vs. episode — "same audio in 21
  other episodes" only makes sense for a series; a movie's fingerprint match is against a known
  studio/franchise outro, not sibling episodes. Keep this in mind for any other cross-episode
  language once the real detector's evidence strings are wired in.

## 9. Open questions

1. ~~**Publish everything, or only confirmed?**~~ **RESOLVED 2026-08-13 — confirmed only**, and not
   behind a setting. A high-confidence guess is good enough for Ravilo, which owns its own fallback
   behaviour; it is not good enough to push into every Jellyfin client in the house, where a wrong
   skip is silent and untraceable. Publishing is therefore a deliberate act with a human behind
   each marker.
2. **Does Jellyfin's API let us delete only our own segments?** If provider scoping is coarser than
   assumed, "never wipe another plugin's segments" needs a different mechanism.
3. **Seek behaviour on direct play** for the 3-second loop (§F) — confirm against the real server
   before building the control rather than after.
4. **Multi-episode files (phase-149)** carry per-episode offsets. The sheet's one-row-per-episode
   shape needs a decision for a combined file: one row per episode, or one row per file with
   segments inside each part?

## Relationships
Consumes phase-150/159. Extends phase-151's lock guarantee to segments. Feeds R182 unchanged.
No Ravilo work.
