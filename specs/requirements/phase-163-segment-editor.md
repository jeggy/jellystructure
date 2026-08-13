# Phase 163 — Intro &amp; credits editor, and segments in Jellyfin (FR-SEG2)

**Status:** Planned — design-complete 2026-08-13, **dev-reviewed 2026-08-13** (see the addendum at the
end; §E's publish half is **blocked** — Jellyfin has no Media Segments write API, verified live).
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

---

## Dev-review addendum (2026-08-13 — backend reality check before implementation starts)

Reviewed against the real backend and the **live Jellyfin server** (probed directly, not assumed).
One finding is blocking and changes a headline goal; the rest are gaps to close during build.

### 1. ⛔ **Jellyfin has no Media Segments *write* API. §E's "Write" half is not implementable as specified.**

Verified live against this house's server (**Jellyfin 10.11.11**, comfortably past §E's 10.10
assumption) by reading its own `/api-docs/openapi.json` and probing the endpoint:

- `GET /MediaSegments/{itemId}` is the **only** MediaSegments operation in the entire OpenAPI document
  (`operationId: GetItemSegments`, tag `MediaSegments`). There is no POST, PUT, PATCH or DELETE.
- `POST /MediaSegments/{itemId}` returns **HTTP 405 Method Not Allowed** — the route is GET-only, not
  merely undocumented.
- `MediaSegmentDto` carries only `Id · ItemId · Type · StartTicks · EndTicks`. **There is no provider
  or source field**, so open question 2 ("does the API let us delete only our own segments?") is moot
  in both directions: there is no delete, and nothing to scope one by even if there were.

In Jellyfin, media segments are supplied by **server-side plugins implementing `IMediaSegmentProvider`**;
the server owns the store and exposes it read-only. That makes goal 3 ("Publish confirmed markers to
Jellyfin so every Jellyfin client skips them"), §E's Write bullets, and the second half of build-order
step 6 rest on an API that does not exist. **This needs a product decision before step 6 is scheduled:**

- **(a) Ship our own Jellyfin plugin** implementing `IMediaSegmentProvider`, backed by a
  jellystructure endpoint. This is the only *supported* way to get our markers into every Jellyfin
  client. It is also a genuinely new deliverable: a C#/.NET artifact, a toolchain this repo does not
  have, versioned against Jellyfin's plugin ABI and re-tested on every server upgrade.
- **(b) Publish through a third-party plugin's own REST API.** Nothing suitable is installable from the
  repos this server has configured — the catalog (41 packages across Jellyfin Stable + two private
  repos) contains exactly one segment provider, **"Chapter Segments Provider"**, which derives segments
  from chapter marks and accepts no external input. This route means adding an unvetted third-party
  repo and taking a dependency on that plugin's API.
- **(c) Drop publishing**, keep the editor, the locks and the read-in. Ravilo already consumes our
  markers; this loses only the "every other Jellyfin client" benefit, which is a real loss but leaves
  the phase's main body of work (a proper editing tool) fully intact and independently valuable.

Recommendation: **build steps 1–5 and 7 as specified and defer step 6's publish half behind this
decision**, rather than blocking the whole phase on it. The editor is the valuable part and does not
depend on publishing.

**Also: §E's "Read" currently yields nothing here.** No segment-provider plugin is installed on this
server (`GET /Plugins`: AudioDB, File Transformation, Moonfin, MusicBrainz, OMDb, Studio Images, TMDb —
no Intro Skipper or equivalent), and `GET /MediaSegments/{a real episode id}` returns
`{"Items":[],"TotalRecordCount":0}`. Reading Jellyfin as a candidate source is worth building, but it
will return empty — and the "cheapest confidence signal we have" will not exist — until some provider
is installed. Not blocking; just don't let its emptiness read as a bug during testing.

### 2. ⚠ **There is no "admin's existing Jellyfin session" for §F to use.**

The admin app authenticates with our own `js_session` cookie; it holds no Jellyfin credential. The only
Jellyfin credential on the server is `apiKeys.jellyfinToken` — a **full admin API key**. Ravilo's
existing precedent (`PlaybackService.kt:269`) mints
`{base}/Videos/{id}/stream?Static=true&…&api_key={token}` for the client to fetch directly, but with a
**per-device/per-user** token (`device.jellyfinUserToken`, R175 login), not the admin key.

Reusing that shape in the admin browser would put the admin API key in a URL in the page — visible in
DevTools, browser history and any proxy log, granting far more than playback. That contradicts §F's own
"no new auth surface" and is a materially wider exposure than Ravilo's per-user tokens.

**Do not solve it by proxying the video through this backend.** Ktor Native's CIO server uses `select()`
and dies fatally on FD ≥ 1024 (see the FD_SETSIZE incidents behind phase 134), and pumping long-lived
range requests on the request-serving dispatcher is precisely the ProcessGate class of incident that
took the admin site down during scans. Prefer a **short-lived, single-item, backend-minted signed URL**,
or a dedicated limited-scope Jellyfin key used only by the editor. Decide explicitly; §F currently
under-specifies this as already-solved.

### 3. ⚠ **The back-fill in build-order step 1 silently unprotects every existing manual correction.**

Today's model is one flat `SegmentMarkers` embedded in the media JSON blob (`model/Media.kt:52`):
`introStartMs · introEndMs · creditsStartMs · stinger · source · confidence · manuallyConfirmed`.
Critically, **`manuallyConfirmed` is today's lock** — it is the single flag guarding every detection
write path (`PipelineStepOps.kt:146`, `:292`, `:401`; `Scanner.kt:660`, `:956`).

§D says manual edits set `source = manual` but are **not** automatically locked, and §5 makes
`checked_at` explicitly "not `locked`". So if back-fill maps `manuallyConfirmed = true` onto
`checked_at`, **every marker the operator has already hand-corrected becomes overwritable by the next
`detect_segments` run** — a data-loss-class regression on exactly the data this phase exists to protect.
Back-fill must set **`locked = 1`** for every kind on a `manuallyConfirmed` record. State this in step 1.

Three further back-fill gaps with no source value in the old model:
- **`creditsEndMs` does not exist today** (only `creditsStartMs`). §5's `end_ms` needs a rule — runtime,
  null, or a nullable column.
- **`source`/`confidence` are per-marker-*set*, not per kind.** Both back-filled rows inherit the same
  value even when the intro came from the fingerprint and the credits from the heuristic — the
  evidence lane will attribute one of them wrongly. Consider back-filling `source` only where it is
  unambiguous and leaving the other null.
- **`stinger.atMs` is null in practice** (TMDB flags the *existence* of a stinger, not its time — see
  the phase-150 build notes). An after-credits row back-filled from a stinger has no `start_ms`.

### 4. ⚠ **"Detection writes only where `locked = 0`" is a wider rule than today's, and changes scan behaviour.**

Today detection additionally refuses to overwrite an **already-filled** field
(`PipelineStepOps.kt:147`: `if (current.introStartMs != null || current.creditsStartMs != null) return null`)
— an automatic value is sticky until an explicit re-scan clears it. Under §D, any unlocked value is
re-derived on every run. That is probably the intent (it lets phase-159's better detection improve old
guesses) but it silently changes what a *scheduled* scan does to existing data, and it interacts with
§B's "disagrees with the season" outline, which will move under the operator. Confirm deliberately
rather than inheriting it as a side effect of the remodel.

### 5. `has_segments` is a denormalized column that the new table would strand.

`media.has_segments` (indexed, `Media.sq:17`/`:23`) is recomputed from the blob on every
`MediaStore.updateOne` (`MediaStore.kt:780`, via `TriageDetection.hasAnySegments`). It backs the triage
rows and the dashboard "no segments detected" filter that §G deep-links into. With segments in their own
table, that flag must be recomputed **on segment writes** — not just on item writes — or those rows and
§G's deep links go stale exactly while the editor is in use.

### 6. Ravilo's serving path has to be rebuilt, even though Ravilo itself doesn't change.

§2's "R182 already consumes our markers unchanged" is true of the *client*, but `DetailService.kt:118`
projects `TvSegmentMarkers` from `ep.segments` — the blob. With the table as source of truth that
projection must be rebuilt from rows, including the existing resolution precedence (manual > chapter >
numeric confidence, never a null-vs-number comparison — phase-150 addendum §4). Name it in the build
order so it isn't discovered at step 6; a regression here silently breaks Skip Intro on the TV.

### 7. Open question 4 already has a concrete answer in the current code.

Multi-episode files aren't only a layout question: `detect_segments` **skips `partCount > 1` episodes
entirely** (`PipelineStepOps.kt:188`, and they're excluded from the fingerprint grouping at `:230`), a
documented phase-150 scope limitation. Those rows will therefore be permanently empty until detection
itself is windowed per part. The sheet should render them as **not supported yet**, not as
"nothing found" — the latter reads as a detector failure and will send the operator hunting.

### 8. Smaller notes for build time

- `POST /api/segments/detect` fanning out over a season must go through the **existing**
  `detect_segments` step/pool. That work is `nice`/`ionice`'d and thread-capped after the CPU-starvation
  incident (16 ProcessGate slots could saturate 32 cores and starve the API server); an editor button
  must not spawn ad-hoc ffmpeg outside those gates.
- The sheet payload (`GET /api/segments?series=&season=`) must **not** go through
  `MediaStore.allItems()` — full-library JSON-blob decode per request was the root cause of the
  backend-performance work; query the new table (or one series) directly.
- Keep `/api/segments/*` out of `OPEN_API_PATHS` so AuthPlugin covers it like the rest of `/api/*`.
- §F's short seek loops (open question 3) can only be answered once a stream URL exists — sequence it
  after the §2 auth decision, not before.

### Build-order impact

Steps 1–5 and 7 are sound as written, with §3/§4/§5/§6 above folded into step 1 and the projection
rebuild added. **Step 6 splits**: "read Jellyfin as a source" is buildable now (and will return empty
until a provider plugin exists); "diff-based publish + publish-failure triage row" is **blocked on the
decision in §1** and should not be scheduled until that is settled.
