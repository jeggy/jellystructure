# Phase R326 — The listening mode, as the owner settled it on 2026-09-28

> Owner, 2026-09-28, on the built mockup: *"Clicking on More on the descriptions, let's just expand the text with
> some small animation, instead of the bottom up drawer. The bottom navigation in the music player should also
> always be shown … The 'Back to films & series' should not say 'back'."* — and a round of answers to the music
> and audiobooks questions the three built phases left open.

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `b0b96d0b`) — written 2026-09-28 from the owner's answers and the mockup `design/ravilo/Ravilo Mobile.html` →
`mobile/ravilo-music.js`, `mobile/ravilo-music-player.js`, `mobile/ravilo-books.js`, `mobile/ravilo-music.css`.
**Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review). Number verified free on `main` 2026-09-28. **Amends the built R321, R322 and R323** — where
this phase and theirs differ, this phase wins. The admin side of the same answers is **287**.

## Decisions (owner, 2026-09-28)

| Asked in | Question | Answer |
|---|---|---|
| — | *More* on an artist's biography | **Expands in place**, a short animation, *More ⇄ Less* — no sheet |
| — | The bottom bar in music mode | **On every music page** — album, artist, book, Now playing |
| R321 | The mode row | ***Switch to films & series*** / ***Switch to music*** — no "Back" |
| R323 open 1 | Continue listening | **A row at the top of Listen** while a book is in progress |
| R322 open | Now playing's queue button | **Dropped** when the bar has a Queue tab |
| R322 | A *Mix* row on Listen | **Not in round 1** |
| R321 | Making your own playlists | **Later** — round 1 plays albums, artists, songs and books |
| — | Music in the web app | **Later, its own phase** |
| R323 M7·1 | Playback speed | **Audiobooks only**; films stay at 1× (the video player's ruling stands) |
| R323 | Sleep timer | **Audiobooks only** |
| R323 | When a book ends | **It stops**: *Finished* + *Start over* — never the next book |
| R322 | Gaps between songs of a WMA re-encode | **Accepted silently** — *Convert…* fixes them |
| R322 | After a restart | **The whole last queue is restored, paused** |
| 279 | Where "last played" comes from | **Jellyfin** — the same on every device |
| R323 | Chapters on the book player | **A chapter strip under the transport** and a **small whole-book scrubber** |
| R322 | An artist's music videos | **A *Videos* row on the artist page**, played in the video player |

## Requirements

**FR-R326-1 — A biography expands in place.** Collapsed: three lines with a fade at the bottom and *More*. Tapped:
the block grows to its full height (a ~340 ms height transition on the design's ease), the fade goes, and the
link reads *Less*; *Less* collapses it the same way. No sheet. String `music.less` × en/da/fo.

**FR-R326-2 — The bar is on every music page.** In music mode the bottom bar (R321's *Listen · Browse · Playing ·
Queue · Profile*) stays visible on an album, an artist, a book and the Playing tab; the pushed page stops at the
bar, and the mini bar sits above it. A tab pressed while a detail is open **goes to that tab** (the detail stack
closes). Opening Now playing from the mini bar **goes to the Playing tab** when the bar has one. This supersedes
R321/R322's "one title's detail has no bar" for music mode only — video mode keeps R278.

**FR-R326-3 — The mode row** in Profile reads ***Switch to films & series*** in music mode and ***Switch to music*** in
video mode (`mode.switch_video` / `mode.switch_music` × en/da/fo: *Skift til film og serier* · *Skift til filmar og
seriur*). **Switching from Profile stays on Profile** (owner, 2026-09-28): only the bottom bar changes — the pill stays
on Profile, the other four items become the new mode's — so the viewer can switch straight back or pick a tab
themselves; a short toast names the mode (*Music & audiobooks* / *Films & series*). The mode row re-renders in
place with its new label.

**FR-R326-4 — Continue listening** is the first row on **Listen** while any book is in progress (R323 FR-R323-1's
card, one per book); absent otherwise. The Audiobooks chip in Browse keeps its own copy.

**FR-R326-5 — Now playing's queue button is not drawn** when the bar has a Queue tab.

**FR-R326-6 — Listen has no Mix row** in round 1.

**FR-R326-7 — Speed and the sleep timer are the book player's only.** The music player has neither, anywhere.

**FR-R326-8 — A finished book stops** on *Finished* with *Start over*; the next book in a series is not queued.

**FR-R326-9 — The book player** gains a **chapter strip** under the transport (the chapters as proportional
segments, the current one lit, a tap jumps) and replaces the book hairline with a **small whole-book scrubber**
labelled *Book* (`ab.whole_book` × 3) with its own position/remaining.

**FR-R326-10 — Gaps are accepted silently** on WMA re-encodes; no notice in the player.

**FR-R326-11 — After a restart** the last queue is restored whole, at the song and position it was left, **paused**.
With nothing restorable, R322 FR-R322-3's last-played song, paused.

**FR-R326-12 — "Last played" is Jellyfin's** (279's `UserData.LastPlayedDate`), so it is the same on every device.

**FR-R326-13 — An artist's music videos** are a *Videos* row on the artist page (16:9 tiles), which open the video
player; absent when the artist has none.

## Out of scope

Playlists (create · add · reorder), a Mix row, music in the web app — each a later phase.

## Acceptance

1. On an artist, *More* grows the text in place and *Less* folds it back; no sheet opens.
2. On an album, the bottom bar is visible; pressing *Queue* closes the album and shows the queue.
3. Profile reads *Switch to films & series* in music mode.
4. With a book in progress, Listen opens on Continue listening.
5. The music player has no speed or sleep control; the book player has both.
6. A restarted app shows the last queue, paused where it was left.

## Dev review (2026-09-28, against `main` `32daeee2`)

Most of this phase is already the built state; the review names what actually changes. Ten items.

1. **Already as decided — nothing to build:** Continue listening on Listen (`MusicListenScreen.kt:62–77`, R323
   FR-R323-2 as built); Now playing has no queue button (R322's open question 1 resolved as the Queue tab); no speed
   or sleep timer in the music player (`MusicPlayerScreens` has neither); a finished book stops (R323 FR-R323-6); the
   Videos row (R321 FR-R321-11); no gap notice exists (FR-R326-10); last played is Jellyfin's played list
   (`MusicTvService.lastPlayed`, 279 FR-279-11 — FR-R326-12).
2. **The biography (FR-R326-1):** `MusicDetailScreens.kt:174/202` — `bioOpen` exists and *More* sets it, but the text
   keeps `maxLines = 2`. Make it `if (bioOpen) Int.MAX_VALUE else 3` inside `animateContentSize()` (~340 ms, the app's
   easing), the label *More ⇄ Less* (`music.less`, new); the same on the audiobook page (`AudiobookScreens.kt:340`).
   Three lines collapsed, as the mockup draws it, not two.
3. **The bar on every music page (FR-R326-2):** `RaviloApp.kt:893` `barShows(d) = bottomBarShows(d) && !(MusicPlaying
   && !portrait)`, and `bottomBarShows` excludes detail destinations (R278). In music mode `Dest.MusicAlbum`,
   `MusicArtist`, `Book` and `MusicPlaying` show the bar; the pushed page's bottom inset already follows `barShows`
   (`:1074`, `:2042`, `:2052`, `:2062`), so content stops at the bar and the mini bar sits above it. A tab tapped with a
   detail open pops the music stack to that tab's root (no behaviour exists today because the bar was hidden there).
   Opening Now playing from the mini bar already goes to the Playing tab (`Dest.MusicPlaying` is the tab).
4. **The mode row (FR-R326-3) — keep the built card, apply the behaviour.** The shipped control is R321 FR-R321-3's
   two-half card (`ListeningModeCard`: *Films & series* | *Music & audiobooks*, the current half lit); it never said
   *Back* — the owner's words were about the mockup's row. The card shows both modes and marks the current one, which
   is clearer than a single *Switch to…* line, and it is the dev-reviewed R321 shape, so it stays. What changes:
   switching **stays on Profile** (today `onSwitch` lands on the new mode's first tab), the bar re-renders around the
   Profile pill, and a toast names the mode (`mode.music_books` / `mode.video` reused). No `mode.switch_*` strings.
   Design drift accepted, as for translations — if the owner wants the one-line row literally, it is a rendering
   swap inside `ListeningModeCard`.
5. **No Mix row (FR-R326-6):** the server builds it (`MusicTvService.kt:167`, `MusicRow("mix", …)`) and the phone
   draws `MixCard` (`MusicListenScreen.kt:104–135`) — remove both (279 amended). Old phone, new server: no mix row
   arrives; new phone, old server: `key == "mix"` is skipped — additive both ways.
6. **Chapter strip and the whole-book scrubber (FR-R326-9):** `ChapterSeekBar` (`BookPlayerScreens.kt:138`) stays;
   the hairline (`:139`, `:457`) becomes the small labelled scrubber (`ab.whole_book`) on `MusicEngine.seekBook` ✓;
   the strip is the chapters as proportional segments (`BookMath.chapterAt` ✓) under the transport, tap → the
   chapter's start.
7. **Restore after restart (FR-R326-11):** `MusicEngine.loadPaused(tracks, index, positionMs, context)` exists (R322
   FR-R322-3, one song). Persist the queue on every change in `MusicDeviceStore` (per-device prefs, exists) as JSON —
   the `MusicTrackItem`s, index, position, context, repeat, shuffle (a 30-song queue ≈ 10 KB) — and restore in
   `attach()`; fall back to `/tv/music/last-played`. A track that no longer exists drops silently.
8. **Speed and the sleep timer** stay the book player's (`BookPlayback.kt`) ✓; `ab.speed_note` and friends untouched.
9. **Strings:** `music.less` only. The design's `mode.switch_video` / `mode.switch_music` are not adopted (item 4).
10. **Wire:** none new; the mix row's removal is a server-side omission (279 amended in place).

## Build notes (2026-09-29)

Built from the dev review (commit `b0b96d0b`): the artist's bio **expands in place** (*More ⇄ Less*, no sheet —
`MusicDetailScreens.kt`); the bottom bar stays on an album, an artist, a playlist, a book and its author (`RaviloApp.kt`
`barShows`); the mode row reads *Switch to films & series* / *Switch to music* (three strings × en/da/fo); *Continue
listening* is a row at the top of Listen when a book is in progress (`MusicListenScreen.kt`); the Mix row is gone
(a stale `mix` context keeps its own label in *Playing from*); speed and the sleep timer are the book player's only
(`BookPlayerScreens.kt`); a finished book **stops** — *Finished* + *Start over*, nothing auto-advances
(`AudiobookScreens.kt`, `BookPlayerScreens.kt`).

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.
