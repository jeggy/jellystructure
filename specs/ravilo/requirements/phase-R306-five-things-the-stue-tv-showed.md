# Phase R306 — Five things the stue TV showed on one morning

> Owner, 2026-09-26: *"Lets fix all those bugs. And one more bug. On the TV for the resume button (i have
> the screen open right now on stue tv, please look) it is cut a bit off on the left side, lets fix this
> as well."* Four of the five were seen during R291's and R265's device pass the same night and morning
> (stue TV, Pixel 9); the fifth was on the owner's screen.
>
> Later the same day, the same row's other edge: *"Currently on tv in Media details page, the trailer
> button is cut off because of the container it lives in. Scrolling works perfectly, but its a bit annoying
> that a button is hidden even with the tv clearly having enough space to show it. Lets investigate if we
> can make the container full width, but only for this row, so that description stays the same."*

## Status

`✓ Built` — written 2026-09-26 from the device pass, not dev-reviewed. **FR-R306-1, -2, -3, -4 and -5
built the same day and seen on the stue TV** (local release build, prod on a local backend build).
**FR-R306-1b built later the same day** at the owner's go-ahead (*"Lets implement that trailer spec"*).

### Build (2026-09-26)

- **FR-1 — `focusBleedScroll`** (`components/FocusBleedScroll.kt`): the scroll viewport widened by
  `FOCUS_BLEED` into the gutter, the content padded back. First tried at 24 dp: the corners came back but a
  one-step edge still showed in the glow's fade (sampled at y = 1010: background `(10,12,19)` jumping to
  `(15,17,28)` at x ≈ 48 px). **40 dp** (inside the 48 dp gutter) leaves a smooth fade. Film and series
  rows both; the other buttons did not move.
- **FR-2 — `resolveStartPositionTicks`** (`PlaybackService.kt`, `StartPositionTest` (4)). On the TV: *Play
  Again* on a watched film carrying 10:50 logged *"from 0 — played, so Jellyfin's 650334ms is not a resume
  point (R306)"* and played from the start.
- **FR-3 — `playbackFailed`** on the seam (Android: Media3's `onPlayerError`, reset on load and engine
  release; web: `video.error`), watched by `PlayerStore` every 500 ms while Ready through `FailureLatch`
  (`FailureLatchTest` (3): a stale flag never fires). Not seen on a device — nothing produces an engine error
  on demand without disturbing Jellyfin; R291's source error, the one seen, is fixed.
- **FR-4 — Android `isPlaying` = `playWhenReady` and neither ended nor idle.** On the TV: three +30 s jumps
  into a transcode and the buffering that followed, recorded at 6 fps — **‖ in every frame**, the seek
  spinner beside the time.
- **FR-5 — `continue_episode_id`**: `ContinueEntry` now carries the episode it is about, `continueEpisodes()`
  reads the cached canonical list, `withContinueEpisodes` lays it on the series' own playstate entry
  (`ContinueEpisodeMergeTest` (3)); the series page's button **and the kicker line above it** use it (the
  kicker, missed at first, still said S13E14 under *Resume · S13E19*). On the TV: tile **S13:E19**, kicker
  **S13E19 · Afsnit 19**, button **Resume · S13E19**.
- Checks: backend suite 522/522; dex guard 245/250 unchanged; `compileKotlinWasmJs` clean.
- **FR-1b — the row spans the page.** The hero column is full width with only its bottom padding; the text
  sits in an inner column carrying today's `fillMaxWidth(0.6f)` and gutter padding unchanged; the row pads
  by the gutter outside `focusBleedScroll`. On the stue TV, film page, nothing focused: **Play Again,
  Watched, My List and Trailer all on screen and whole**; Right ×3 lands on *Trailer* and the row does not
  move; the synopsis and R222's note break on the same words as before; the series page likewise. On the Pixel 9
  (debug build) the film row starts on the text's edge and still scrolls: a swipe brings *Trailer* in whole.

## What is wrong

1. **The focused primary button is cut off on the left.** On a series or film page, the focused *Resume*
   / *Play* button's left edge is square while its right is rounded, and its glow stops on the same
   line. The action row is a `horizontalScroll` row (added so the last button is never squeezed), and a
   scrolling container clips its children along its scroll axis at its own edge. `RaviloButton` grows
   when focused — `BUTTON_FOCUS_SCALE` 1.06 plus a 16 dp glow — and the first button sits exactly on the
   row's left edge, so the growth is cut there. (Compose already inflates the *cross* axis by the maximum
   elevation, which is why the glow below survives.)

   The same row cuts its **last** button on the right, for a different reason. The hero's text column is
   60% of the page on a TV (R145 kept it there; the phone gets the full width) and the action row lives
   inside it: 0.6 × 960 − 2 × 48 = **480 dp** of room. The film row — *Play* (min 200), *Watched*, *My
   List*, *Trailer* (min 130), three 12 dp gaps — is ~640 dp; the series row (*Play* min 220, *My List*,
   *Trailer*) just tips over too. So *Trailer* starts past the viewport and D-pad Right scrolls it in, on
   a screen with ~380 dp of empty gutter to its right. The mockup never asked for this:
   `design/ravilo/ravilo.css` gives the detail hero's body the page (`.dhero-body { left: 64px; right:
   64px }`), caps **only the synopsis** (`.hero-syn`, `.dsyn-block { max-width: 760px }`) and leaves
   `.dactions` unbounded. The 60% column was the port's shorthand for "cap the text", and it caught the
   buttons.
2. ***Play Again* resumes.** On a watched film Ravilo shows *Play Again* (R142: `played` wins), but the
   start takes Jellyfin's saved position whatever `Played` says — a watched film re-watched to 9:37 and
   left restarts at 9:37 under a label that promised the beginning. R185 already made the household rule:
   **a Played item is never treated as in progress, whatever position it carries** (Jellyfin leaks
   positions onto watched titles; 62 of 115 Continue Watching rows once did). Continue Watching and the
   label follow it; the start does not.
3. **A failure after the picture started is an endless spinner.** R237 gave a failed *start* one plain
   sentence and the right actions. An engine error after the first frame (seen: Media3's *"Unable to bind
   a sample queue…"* during R291) is surfaced nowhere: the engine goes idle, R218's stall presentation
   takes it for a wait, and the viewer sees a frozen frame and a spinner until they give up.
4. **The Pause button flips to ▶ while the player buffers.** On Android, the player seam's `isPlaying` is
   Media3's `isPlaying`, which is false while buffering; the web's is `!video.paused`, which is not. Every
   player surface reads it as *"not paused"* — the glyph, what OK does (OK during a buffer would call
   *play*, not *pause*), the paused scrim, cursor hiding, and the `is_paused` sent with every progress
   report. Seen in a 6 fps recording of an audio switch: ▶ for ~0.4 s under a picture that never stopped.
5. **The series page resumes a different episode than Continue Watching shows.** Continue Watching
   (R219) picks each series' entry by *most recent activity* from one canonical list — a tile read
   *S22:E4*. The series page picks *"the first in-progress episode in season order, else the first
   unwatched one"* and read *Resume · S13E14*, an old half-watched episode. Two surfaces, two answers.

## Requirements

**FR-R306-1 — A focused button is never cut by its own row.** The film and series action rows keep
scrolling, but their scroll viewport reaches into the gutter by the focus growth's extent on both sides,
and the content is padded back by the same amount: nothing moves, and the clip no longer falls on a
button. One helper, used by both rows.

**FR-R306-1b — The action row is as wide as the page; the text keeps its column.** On the film and
series pages the hero's text — logo or title, meta, genres, flags, synopsis, R222's playback note — keeps
**exactly** the column it has today (60% of the page less the gutters on a TV, the full width on a phone),
so the synopsis wraps on the same words. The action row leaves that column and spans the page between the
gutters, as `.dactions` does in the mockup: on a TV every button is on screen at once and the row never
scrolls. The scroll, with FR-R306-1's bleed, stays as the narrow-screen fallback — the phone is unchanged.
The buttons do not move: the row starts on the same left edge as before. Shape, so the text column is
not re-derived by accident: the hero column fills the width and keeps only its bottom padding; the text
moves into an inner column that carries today's `fillMaxWidth(0.6f)` and gutter padding as they are; the
row pads by the gutter itself, outside the bleed. The "this Row lives inside the hero's 60%-width column"
comment above both rows is rewritten to say what the scroll is now for.

**FR-R306-2 — *Play Again* plays from the start.** The server starts a **Played** item at 0 unless the
client sends its own position (R292's return from the background, which stays as it is). One line in the
log when a saved position is ignored. Consequence, accepted with R185: a re-watch of a watched title that
is left part-way starts again from the top next time — the same answer Continue Watching already gives,
which does not list it. Applies to every client and to the next-episode start alike (R184's class of bug).

**FR-R306-3 — A failure mid-film says so.** The player seam reports a fatal engine error
(`playbackFailed`, reset on every load); the session store turns it into R237's *error* state, generic
kind — *"Something went wrong"*, **Retry** and **Back**. No new string. A flag left over from before a
load never counts: the store acts only on a failure it saw arrive after the stream was loaded. A stall
that never errors (R291's open question) is not this requirement.

**FR-R306-4 — The Pause button says what the viewer asked for.** Android's `isPlaying` means *wants to
play* — `playWhenReady`, and neither ended nor idle — as the web's already does. Buffering is R218's to
show (`isBuffering`), never a flipped glyph.

**FR-R306-5 — The series page resumes what Continue Watching shows.** `/api/tv/playstate` carries, on a
series' own entry, `continue_episode_id`: the episode that series' entry in R219's canonical Continue
Watching list points at (its in-progress episode or its next-up), read from the same cache — no new
Jellyfin call. The series page's primary action uses it when it names an episode of the series, and falls
back to today's rule otherwise. Additive and optional on the wire: an older client ignores it.

## Non-goals

- R291's endless stall after an audio switch (no error is raised; its cause is still unproven).
- Restoring the viewing data the device pass changed on the owner's account — the owner's call.
- Bringing the synopsis to the mockup's own cap (760 px of 1920 — 380 dp, narrower than today's 480 dp).
  The owner asked for the description unchanged; FR-R306-1b touches the row only.

## Acceptance

1. On the stue TV, a series page and a film page with *Resume* focused: both left corners rounded, the
   glow whole, the buttons exactly where they were.
2. A watched film with a saved position: *Play Again* starts at 0:00 (backend log names the ignored
   position).
3. A mid-film engine error shows R237's card with Retry and Back; Retry continues the film.
4. A short buffer (an audio switch) keeps the Pause glyph; OK during it pauses.
5. A series whose Continue Watching tile shows one episode opens with *Resume* on that same episode.
6. Unit: the playstate merge, the store's failure watcher (including the stale-flag case), the bleed
   leaves the children's positions unchanged.
7. On the stue TV, a film page with a trailer, nothing focused: *Play*, *Watched*, *My List* and
   *Trailer* all on screen, whole; Right from *My List* lands on *Trailer* without the row moving; the
   synopsis breaks on the same words as before. The series page likewise.
8. On the Pixel 9 the film row still scrolls to *Trailer* as it does today.
