# Phase R352 — The Mac music test: titles beside chips, the light theme's top bar, the cast's last song

> Found 2026-10-02 testing the Mac app (Compose Desktop) on the owner's MacBook against backend
> `v1.48-34-g0a5a7e59`, at 1280 px and in a 500 px window (which is the phone's layout, R337 FR-R337-2).

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed. Written 2026-10-02 (dev-authored) from the Mac test and the code. Number given by the coordinator.
**Amends** R344 (the title beside the chips), R322 (FR-R322-3's last-played record, FR-R322-8's queue header,
FR-R322-10's mini bar on a computer), R337 (FR-R337-6), R342 (the Dark picture) and phase 230 (the playstate
rotation). No wire change, no new string.

## What was seen, and why

**(a) The queue side panel hides song titles (R344).** With two or three version chips the *Now playing* row's title
shrank to *C…*, and one *Up next* row showed no title at all. `QueuePanelRow` (`MusicPlayerScreens.kt:659`) puts the
title (`weight(1f, fill = false)`) and the chips in one `Row`; Compose measures the unweighted chips first and gives
the title what is left, which in a 300 dp panel can be nothing. `TrackRow` has the same shape. R344 said "the title
truncates before the chips, so a chip is never cut" — true, but it let the chips take the whole line.

**(b) The phone-layout queue (500 px): the chips touch the length** — *+1 4:18*, *Instrumental5:05*. `TrackRow` puts
the length straight after the title column; when the chips reach the column's end there is no gap.

**(c) Daylight theme, 500 px: the top bar has a dark grey band** (pixel values 114 → 251 between y = 32 and y = 90).
`AppBar`'s vignette is a fixed black-to-transparent gradient drawn over the bar in every theme; on a dark theme it is
invisible, on a light one it is a grey smear.

**(d) Relaunch restores the wrong song after a music cast.** *Stop casting* left the speaker's song at 0:46 on
the Mac; after a relaunch the bar showed the song from before the cast, at 3:12. The device's last-played record
(`MusicQueueStore`, R322 dev review 7) is written by the engine's `save()`. The hand-over to the speaker
(`MusicEngine.stopForVideo`) saved the queue as it stood then; *Stop casting* brings the speaker's queue back with
`MusicEngine.loadPaused`, which **never saves** — on either engine. Nothing in between saves what the speaker plays.

**(e) The *Up next* header says *4 songs · 19 min left* over three rows.** `MusicQueueScreen` counts `upNext + 1` and
adds the playing song's remaining time: the mockup's label sat beside *Queue* and described the whole queue; the app
draws it under *Up next*, where it reads as a count of the rows below.

**(f) Paused music shows a mini bar in films mode at 500 px but not at 1280 px.** The 1280 px window follows R337
FR-R337-6 (films mode: the bar only while music plays, and a pause keeps it until the viewer leaves the page — Q2 +
Q10). The 500 px window is the phone's layout and follows R322 FR-R322-10 (the mini bar in video mode whenever a song
is loaded). Both are as written, but Q2/Q10 are the owner's answers for **a computer**; resizing a window should not
change whether paused music stays on screen.

**(g) R342: the music Dock icon is near-black where the films icon is navy.** On a dark system with no icon-style key
(or *RegularAutomatic*), the Swift library picks R342's **Dark** picture, whose tile is `#05070E`. The films icon is
the installed `.icns`, which macOS does not restyle, so it stays navy `#000B25`: the two icons of one app disagree
whenever the Mac is dark. The pictures themselves match the spec (re-rendered: byte-identical).

**(h) A series marked played in Jellyfin took about six minutes to show in Ravilo** (2 → 8 → 12 → 18 → 20 of 20).
`PlaystateCache` (phase 205/230) refreshes every 20 s, but each cycle reads every title and only **one fifteenth** of
the episodes (`idsForCycle`, `EPISODE_SWEEP_CYCLES = 15`), so every episode is re-read once per 5 minutes. A change
made outside Ravilo (Jellyfin's own web UI) reaches the page slice by slice; the series' own row changes in the first
cycle.

## Requirements

**FR-R352-1 — The title takes its space first; the chips fold to fit (amends R344).** One layout for a title with
version chips, used by `TrackRow` and `QueuePanelRow`: the title gets its natural width; the chips fold (fewer named
chips, the rest in *+N*) until title and chips fit the line. Only when the title alone is wider than the line does it
truncate, and then the chips show *+N* alone. The fold limits stay R344's (two on a phone, three on a computer).
Screen readers still hear one sentence for all the versions.

**FR-R352-2 — A gap before the length.** `TrackRow` keeps 8 dp between the title column and the length (and the lyrics
mark), on both layouts.

**FR-R352-3 — The top bar's vignette follows the theme.** Dark themes keep today's black vignette. A light theme uses
its own page colour fading to transparent, so the bar never darkens.

**FR-R352-4 — The last-played record follows what was last played, wherever it played (amends FR-R322-3).**
- `MusicEngine.loadPaused` saves the record (both engines), so *Stop casting* and *Play on this computer/phone* leave
  the speaker's song and position as the record.
- While a speaker or TV plays a music queue, the record follows it: the queue, the song and the position are saved
  when the song changes, when it pauses, and at most every 15 s while it plays.
- A book is untouched (it never casts).

**FR-R352-5 — The *Up next* line counts *Up next* (amends FR-R322-8).** *{n} songs · {t} left* under *Up next*
counts the rows under it and sums their lengths. With nothing up next there is no *Up next* heading and no line.

**FR-R352-6 — A computer keeps one rule for paused music in films mode (amends FR-R337-2 for this one rule).** On a
computer, the phone-layout window (< 600 dp) follows FR-R337-6 in films mode: the mini bar shows while music plays,
and a pause keeps it until the viewer leaves the page. A real phone keeps FR-R322-10.

**FR-R352-7 — The Dark music picture keeps the films icon's navy (amends R342 FR-R342-2).** Dark's tile is `#000B25`,
like Default's and like the installed films icon, so the running icon never changes colour on a mode switch. Clear and
Tinted are unchanged. A Default or Dark tile is drawn edge to edge on the 100-unit grid, exactly as the films icon is
(it was inset by 0.6 units). The 14 pictures are re-rendered with `scripts/render-brand-icons.sh dock`.

**FR-R352-8 — A title whose own state changed is read whole (amends phase 230).** When a background cycle finds a
series whose own row (played, or its played percentage) differs from the last cycle, the same refresh reads that
series' episodes at once, not over the next 5 minutes. At most five series per cycle (the rest are picked up by the
next cycle the same way, and by the rotation).

## Acceptance

1. Mac, 1280 px, music: the queue panel's rows show their titles in full where they fit; a row with three chips and a
   long title shows the title and as many chips as fit, then *+N*.
2. 500 px: *Instrumental 5:05* — a visible gap in every queue row.
3. Daylight at 500 px: the top bar is the page's colour, no grey band. Aurora: unchanged.
4. Cast music to a speaker, skip two songs, *Stop casting*, quit, relaunch: the bar holds the song the speaker played,
   where it stopped. Quit while casting and relaunch: the bar holds the speaker's song.
5. A queue of 1 + 3: *Up next* reads *3 songs · {their total} left*.
6. Mac at 500 px in films mode, music paused from the mini bar: it stays until another page opens, then goes; music
   playing: it stays.
7. Mac in dark mode, music mode: the Dock icon's tile is the same navy as in films mode.
8. Mark a 20-episode series played in Jellyfin's web UI: the series page shows *20 of 20* within about 40 s.

## Build notes (2026-10-02)

Built 2026-10-02 on `main` `145fc0f9`. Not deployed, not device-tested; nothing was run on the Mac.

- **(a) FR-R352-1:** `TitleWithVersions` (a `SubcomposeLayout` in `VersionChips.kt`) measures the title's natural width
  and each allowed chip group (R344's fold down to *+N* alone), and `fitVersions` (pure, unit-tested) picks the split:
  the largest group that fits beside the whole title, else the title truncates and the chips keep the largest group
  within 40 % of the line. Used by `TrackRow` and `QueuePanelRow`.
- **(b) FR-R352-2:** an 8 dp spacer before the lyrics mark / length in `TrackRow`.
- **(c) FR-R352-3:** `AppBar`'s vignette is the theme's background at 55 % on a light theme, black at 55 % (unchanged)
  on a dark one.
- **(d) FR-R352-4:** `loadPaused` saves on both engines (Android, desktop); `MusicCast.follow` saves the speaker's
  queue, song and position on a song change, a pause and every 15 s while linked.
- **(e) FR-R352-5:** *Up next* counts `upNext` and sums its lengths; no heading or line when it is empty.
- **(f) FR-R352-6:** `musicMiniOver` gains the films-mode rule on a desktop platform (`deskBarKeptOn`, now declared
  before it and shared with `deskBarShows`).
- **(g) FR-R352-7:** `render-brand-icons.sh` draws Dark on navy and unstroked tiles edge to edge; the 14 pictures were
  re-rendered (6 changed: Default, Dark and the Tinted tile mask, 512 + 32 px). The Swift style rules are unchanged.
- **(h) FR-R352-8:** `PlaystateCache.episodesOfChangedSeries` (unit-tested) — a background cycle that sees a series'
  own row change reads that series' episodes in the same refresh (≤ 5 series per cycle, own 5 s budget). An open
  series page also takes `playstate_changed` patches for its episodes (the series' own row is left alone, it carries
  no `continue_episode_id`).

**Why (h) took six minutes, for the record:** `PlaystateCache` refreshes every 20 s, but since phase 230 each cycle
reads every title and only one fifteenth of the episodes, so any one episode is re-read once per 5 minutes (plus
timeouts and pool-busy skips: ~6 min). A change made in Ravilo is refreshed at once (the stop / played routes);
a change made in Jellyfin's own UI is only seen by the rotation — and the series page did not listen to
`playstate_changed`.

**Verified:** `VersionChipsTest` (fit cases), `PlaystateCacheTest` (whole-series cases, the patch), `:ravilo-ui`
unit tests, backend `linuxX64Test`, desktop compile, Android release build. **Needs the Mac:** acceptance 1–7; 8 needs
a deployed backend.

## Amendment (2026-10-02) — a title that truncates sits beside *+N* alone

**Seen in the Mac re-test (backend `v1.48-54-gfefa9049`):** at 1280 px the queue panel's current song read *Cave
(acoustic…* beside *Live +2*. The build kept, beside a title that had to truncate, the largest chip group within 40 % of
the line — a deviation from FR-R352-1, which says the chips are *+N* alone whenever the title truncates.

**Built 2026-10-02, not deployed, not device-tested.** `fitVersions` now gives a title that cannot be whole the line less
*+N* alone; the 40 % share (`VERSION_CHIPS_MAX_SHARE`) is gone. Unchanged: a title that fits whole keeps every chip
group that fits beside it. `VersionChipsTest` (11) pins it, including the case a 40 % share would have allowed one named
chip. **Re-test (Mac, 1280 px window, Queue panel open):** a song with a long title and two or more versions playing —
the panel's current row shows the title as long as it can beside *+N* only; a short title still shows its named chips.
