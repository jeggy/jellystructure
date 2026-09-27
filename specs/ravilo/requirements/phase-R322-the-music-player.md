# Phase R322 — The music player: Now playing as a tab, the mini bar, the queue, and playing in the background

## Status

`Planned` — written 2026-09-28 from the phone brief (§D–§G, §J4–§J6, §M5) and the research report (§5, §6.3,
§6.4), with the mockup `design/ravilo/mobile/ravilo-music-player.js` (+ `ravilo-music.css`), including the
owner's *"a full-screen of what's now playing there. If nothing is playing, then it should just be whatever was
last played in pause mode."* **Not dev-reviewed.** Builds on **R321** and **279**, **R244** (the time bubble,
follow-the-sensor), **R218** (the buffering pulse), **R237** (failure copy), **R245/R267** (the cast mini bar's
docking) and **R292** (the video engine's lifecycle).

## Decisions

| # | Question | Answer |
|---|---|---|
| — | Where Now playing lives | **Owner: the Playing tab, in the middle of the bar** (FR-R322-2) |
| — | Nothing playing | **Owner: the last-played song, loaded and paused** where it was left (FR-R322-3) |
| J4 | Now playing's direction | **Lean: cover-first** (the alternatives, lyrics-first and queue-first, are drawn). Not yet answered |
| J5 | Dismissing the mini bar | **Lean: swipe down stops** (the alternatives are a ✕ that stops, and no dismissal). Not yet answered |
| J6 | *Even out volume* | **Lean: in Settings, on by default.** Not yet answered |

## Requirements

**FR-R322-1 — One service.** A `MediaSessionService` (`RaviloMusicService`) owns its own `ExoPlayer` and
`MediaSession`, and Compose talks to it through a `MediaController` (research §6.3 has the manifest, permissions,
`onTaskRemoved` and resumption).

- It **survives `ON_STOP`**, which is its purpose. R292's video lifecycle is untouched, and R292's spec gains one
  sentence so the two rules are never read as a contradiction.
- Audio attributes are *media/music* with `handleAudioFocus` and becoming-noisy handling.
- `RaviloAppContext.init` is safe from the service.

**FR-R322-2 — The Playing tab.** Now playing **is a page** — the bar's middle item — with the bar under it:

- **no close chevron** (its place is empty);
- **no swipe-down to close**;
- Back follows R275's ladder like any other tab.

The same screen still opens full-screen (no bar, chevron, swipe-down) where there is no Playing tab: the
drawn alternative bars, and landscape (FR-R322-6).

**FR-R322-3 — Nothing playing.** Opening Playing with an empty queue loads the viewer's **last-played song** in
its album context (279 FR-279-11), **paused**, at the position left off, and does not start it. With no history
at all it shows one sentence: *Nothing played yet*.

**FR-R322-4 — The screen (J4: cover-first).** Top to bottom:

1. *Playing from* **{album / artist / playlist / Recently played}** (+ ♪ per J3) and ⋯.
2. The cover (80 % width, rounded, soft shadow) over a ground tinted by the cover; Noir flat.
3. The title (marquee when long); the **artist** and **album** as links (they open the detail, closing nothing
   but the tab's focus); ♡ (My List).
4. The seek bar with R244's time bubble, and *0:42 · −3:10*.
5. **Shuffle · previous · play/pause (64 dp) · next · repeat** (off → all → one, with a *1* badge; a toast names
   the state).
6. **Lyrics** (absent when none — never greyed) · **queue** · **cast** · ⋯.

Previous within the first 3 s goes back a song, and after that it restarts the song. **Swipe the cover** left/right
= next/previous, with the cover sliding off. There are no double-tap seeks (a song is short).

**FR-R322-5 — States.**

- Playing and paused.
- **Buffering** — the play glyph's place shows R218's three-dot pulse and nothing else moves.
- **No cover** — the wordmark tile at cover size.
- **Queue end** — *next* is absent, and after the last song the screen stays on it, paused at 0:00. Nothing
  restarts on its own.
- **Failure** — a sheet: *Couldn't play this · Try again, or skip to the next song* · **Try again** · **Skip**.
  No cause is named.
- **Casting** — *Playing on {TV}* under the credits; the cast glyph lit.

**FR-R322-6 — Landscape** (R244's follow-the-sensor, phone only): the cover on the left, meta, seek, transport
and the bottom row on the right, and a close chevron top-left (landscape is full-screen, not a tab).

**FR-R322-7 — Lyrics.** Synced (279 FR-279-8): lines in Space Grotesk 24 sp.

- The current line is lit (the brand gradient; Noir ink), past lines are dimmed, and the view auto-scrolls to
  keep the current line at ~40 %.
- **Tapping a line seeks there.** Scrolling by hand stops the follow until the next song.
- **Plain** lyrics are body text, unsynced.

The head shrinks to a small cover + title while lyrics show. **Lyrics are a state of the J4 winner**, not a
fourth direction: under *lyrics-first* they open by default when the song has them.

**FR-R322-8 — The queue.** The Media3 playlist is client state.

- **The Queue tab** is a page: *Now playing* (the current song with bars), *Up next* with **drag handles** to
  reorder, **swipe left to remove**, *Clear queue*, and a count + time left (*8 songs · 31 min left*).
- **The queue sheet** (from Now playing's queue glyph) is the same list. See R321's open question 1.
- Tapping a song in the list plays from there.
- Playing an album, artist, playlist, search group or *Recently played* **replaces** the queue with that context
  (Shuffle reorders it with the tapped song first). *Play next* / *Add to queue* insert without replacing, and a
  toast confirms.

**FR-R322-9 — Even out volume (J6).** On by default, in Settings ▸ **Listening**: *Songs from different albums
play at the same loudness*.

- Album gain is applied when playing an album in order.
- Track gain is applied on a mix, shuffle or anything else (279 FR-279-7).
- It is a volume scale, and no limiter is drawn.

**FR-R322-10 — The mini bar.** It docks **on top of the bottom bar** exactly where the cast mini bar docks (64 dp,
opaque, hairline progress on its top edge). It shows the cover, title and artist (+ *· {TV}* when casting),
play/pause, and *next* (absent at queue end).

- **Tap → the Playing tab**, and the bar selects it.
- It is **hidden on the Playing tab**. It shows on every other music page, on a detail page (bar hidden ⇒ at the
  bottom inset), on Profile, **and in video mode** — until a video takes focus.
- **With a film casting too,** the bars stack: the cast bar on top, music under it.
- **Swipe down stops** (J5): the bar follows the finger, fades, and the queue is cleared.

**FR-R322-11 — ⋯ on a track.** A `HandsetSheet` with the cover, title and *artist — album*, then:

- *Play next*
- *Add to queue*
- *Add to playlist…* (phase 2 — R321 FR-R321-10)
- *Go to album*
- *Go to artist*
- ♡ *My List*

Each closes the sheet and toasts where it makes sense.

**FR-R322-12 — Music and video.** Starting a video (on this phone or a cast) **stops** the music, and the
platform's audio focus pauses it first. **Switching mode does not** — the mini bar follows the viewer into
video mode.

**FR-R322-13 — The platform's card (§G).** The notification and lock screen get:

- title = song;
- artist = credited artists joined *, *;
- album = album title;
- artwork = the cover shown in the app, ≥ 512 px, with the wordmark rendered when there is no cover;
- actions previous · play/pause · next.

Media3 draws them. Nothing is drawn by us.

**FR-R322-14 — Skins and frames.** Every state in Aurora, Midnight and Noir, on the Pixel 9 and iPhone 16
frames. The iPhone frame is the design's second frame only — the web app is a later phase.

## Acceptance

1. Play an album, lock the phone, and wait: it keeps playing, and the lock screen shows the song, artists, album
   and cover with previous/next. Removing the app from recents stops it.
2. Kill and relaunch the app, then tap Playing: the last song is shown paused where it stopped, and play resumes
   from there.
3. On the Playing tab there is no chevron and swiping down does nothing. In landscape there is a chevron.
4. A song without lyrics has no lyrics glyph. On a synced song, tapping a line seeks there, and scrolling stops the
   follow.
5. Queue: drag reorders and a swipe removes. *Play next* from a track's ⋯ lands directly after the current song.
6. With a film casting, the music mini bar sits under the cast bar. Starting a video on the phone stops the music.
7. A WMA track (HLS) plays and seeks. Nothing on screen differs from an MP3.

## Mockup

`design/ravilo/Ravilo Mobile.html?mode=music` → Playing. The review panel's *Player* preview reaches playing,
paused, buffering, no cover, queue end, failure and casting; plus *Orientation*, *A film is casting too* (the
stacked bars), and the J4/J5 alternatives. *Jump to* opens Now playing, Lyrics, Queue and Track ⋯.

## Open questions

1. Gapless: Media3 does it on direct-play items and not on HLS re-encodes (38 of the household's 60). Accept a gap
   on those until H1's *Convert…* is run? Lean: yes, and say nothing.
2. Resumption after reboot (`onPlaybackResumption`): restore the last queue paused, or only the last song?
   Lean: the queue.
