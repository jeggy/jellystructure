# Phase R323 — Audiobooks in the listening mode: the shelf, the book, and the book player

> Owner, 2026-09-27: *"Audiobooks: exactly one book, 14 parts, no cover. Draw the shelf and the grid with one
> item and no apology."* Speed *"appears here and ONLY here — the video player's 'no speed' ruling stands."*

## Status

`Planned` — written 2026-09-28 from the phone brief §M1–§M7, the audiobooks research (§3.2, §4, §5, §6) and the
mockup `design/ravilo/mobile/ravilo-books.js` (+ the `bk-*` rules in `ravilo-music.css`). **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below).
Builds on **R321**, **R322**, **280** and **281** (FR-281-10's paths). The research's "R324".

## Decisions

| # | Question | Answer |
|---|---|---|
| M7·2 | Where audiobooks sit | **Owner's bar (R321): a Browse chip, *Audiobooks*.** The brief's lean (a bar item) is superseded. See the open question |
| — | Mode card | *Music & audiobooks* (R321 FR-R321-3) |
| M7·1 | Speed on the book player | **Lean: yes, per book** — only here. Not yet answered |
| M7·3 | Bookmarks this round | **Lean: yes.** Not yet answered |
| M7·4 | The lock screen's side actions for a book | **Lean: −30 s / +30 s** (the alternative is previous/next). Not yet answered |
| M7·5 | Skip silence | **Lean: Settings only, off.** Not yet answered |

## Requirements

**FR-R323-1 — The shelf** (Browse ▸ **Audiobooks**; absent without the books library).

1. **Continue listening** first — one large card per book in progress: the 1:1 cover · title · author · a
   **progress ring** with *3 h 12 min left* · *Chapter 9 · Part 9 of 14* in `--ink-dim`, and a round play button.
   **A tap resumes** — no detour through the book page.
2. **All books** with *N books* — a 2-up 1:1 grid: title, author, a small ring on a started book, and ✓ on a
   finished one.
3. **Authors / Series chips** only when there is more than one author or any series.
4. A **sort** pill: *Recently added · Title · Author · Series*.

States: **one book** (the household's — one card, one cell, no apology); one book not started (no Continue
section); many; empty (*Nothing filed as audiobooks yet*).

**FR-R323-2 — Continue listening on Listen** (R321 open question 2 — lean, not drawn): while a book is in
progress, Listen shows the same card above *Recently added*.

**FR-R323-3 — The book page** (bar hidden, mini bar stays):

- Cover · title · subtitle · **author** (a link → the author page) · *Read by {narrator}* (absent when unknown)
  · *5 h 24 min · 14 chapters* · a series chip *{series} · Book 2 of 5* (→ the shelf's Series chip).
- The ring with *… left*.
- One primary action: **Continue · 2 h 12 min** / **Start** / **Start over** (finished), plus ⋯.
- The description, three lines with *More* → a sheet.
- **Chapters** — heard ones dimmed, the current one with bars, and a tap plays from there.

⋯ holds *Start over* · *Mark as finished* · ♡ My List · *Go to author*.

The **author page** shows a circle, name, *N books*, the biography (when any) and the books grid.

**FR-R323-4 — Now playing, book variant.** The same screen, tab and gestures as R322, with these differences:

- The title line is the **chapter**, and under it *book · author* as links.
- The seek bar is the **chapter's** (*12:40 / 29:05*). Under it is a 2 dp **book** hairline and *3 h 12 min left*.
- **−30 s · play/pause · +30 s** replace previous/next. **Long-press repeats** the skip every ~0.4 s.
- A **speed chip** (*1.0×*) on the left and a **sleep** glyph on the right replace shuffle and repeat.
- The bottom row: **chapters** · **bookmark** · cast · ⋯. There is no lyrics glyph, no queue glyph and no
  shuffle/repeat.
- There is no cover swipe.

**Speed exists here and nowhere else in Ravilo** (M7·1). The video player keeps no speed.

**FR-R323-5 — The sheets.**

- **Speed** — 0.8 · 0.9 · 1.0 · 1.1 · 1.2 · 1.5 · 1.75 · 2.0× as a grid, with *Remembered for this book only*.
  It is stored per book per viewer (280 FR-280-4). Heartbeats and *time left* are in **book** time, not wall time.
- **Sleep timer** — 15 · 30 · 45 · 60 min · *End of chapter* · *Off* (when set). While set, the glyph is lit and
  carries the minutes left. With *Sleep timer fade* on (Settings, default on), **the last 10 s fade out**, then
  it pauses.
- **Chapters / Bookmarks** — two tabs (Bookmarks only per M7·3). Chapters is FR-R323-3's list. Bookmarks lists
  *note or chapter · chapter · offset*, and a tap seeks there. With none: *No bookmarks yet. Tap the bookmark on
  the player to add one.*
- **Add bookmark** — the chapter and offset, an optional one-line note (system keyboard), *Save*, and a
  *Bookmark added* toast.

**FR-R323-6 — States.** Playing · paused · **buffering** (the pulse) · **sleep set** · **at a chapter
boundary** — the next part is preloaded, so the cut is seamless; the chapter line changes and nothing else moves
· **finished** (*Finished* · **Start over** in place of the transport) · **failure** (*Couldn't play this · Try
again. Your place in the book is kept.* · **Try again** · **Close**).

**FR-R323-7 — The mini bar, book variant.** The same geometry as R322 FR-R322-10: cover · **chapter title** /
*book · author* · **−30 s** · play/pause (never *next*). The hairline is the **book's** progress. Swipe-down
stops (J5). Starting a song stops the book, and starting a book stops the music — **one listening queue at a
time**.

**FR-R323-8 — The platform's card.** The notification and lock screen get:

- title = chapter;
- artist = author;
- album = book;
- the cover;
- actions **−30 s · play/pause · +30 s** (M7·4 — Media3 custom commands), because previous/next track is wrong
  for a book.

**FR-R323-9 — Settings ▸ Listening.** One small group, after Playback:

- *Even out volume* (R322 FR-R322-9);
- **Skip silences in audiobooks** — *Long pauses in a recording are shortened*; off; only per M7·5;
  `setSkipSilenceEnabled`;
- **Sleep timer fade** — *The last 10 seconds get quieter before it stops*; on.

Nothing else. Speed lives on the player, per book.

**FR-R323-10 — Words.** Brief §M6's keys × en · da · fo (drafts; the shipped table wins), plus the page words
the mockup added in the same shape (`ab.sort_*`, `ab.note*`, `ab.listening`, `ab.*_sub`, `ab.fail_p`,
`ab.close`). No provider, format or "part file" language reaches a viewer beyond *Part n of m*.

## Acceptance

1. On the household server: Browse ▸ Audiobooks shows one Continue card (if started) and one cell. A tap resumes
   at the book position, even inside a part under 5 minutes.
2. Speed 1.5× on this book: another book starts at 1.0×, and this one is still at 1.5× after a relaunch.
3. The sleep timer set to *End of chapter* pauses at the next chapter start, fading the last 10 s when the fade is
   on.
4. Crossing a part boundary makes no audible gap on direct-play MP3, and the chapter line changes.
5. The lock screen shows the chapter, author, book and cover with ±30 s.
6. Finishing the last part within its last 5 minutes shows *Finished · Start over*, and the admin's Listeners
   tab says *finished*.

## Mockup

`design/ravilo/Ravilo Mobile.html?mode=music` → Browse ▸ Audiobooks. In the review panel: *Audiobooks shelf*
(one book in progress · not started · many · empty), *Book player* (playing · paused · buffering · sleep set ·
at a chapter boundary · finished · failure), the §M7 questions, and *Jump to* → Audiobooks, Book, Book player,
Speed, Sleep, Chapters and Bookmark sheets. *Settings · Listening* is on the same list.

## Open questions

1. **Audiobooks as a Browse chip** buries *Continue listening* two taps deep, the research's reason for a bar
   item (§5 c). FR-R323-2 (a Listen row) is the lean fix. The alternative is the drawn *Home · Library · Now
   playing · Audiobooks* bar. Owner's call.
2. **Chapter vs part** in the seek bar for a one-file M4B with 24 embedded chapters: chapter (drawn). Confirm that
   the book hairline alone is enough for a 12-hour file.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Nine items; item 3 is the one that decides whether a chapter boundary is seamless.

1. **APIs:** `Player.setPlaybackSpeed(float)` and `ExoPlayer.setSkipSilenceEnabled(boolean)` exist in Media3
   1.8; speed is stored per book per viewer through 281's `PUT /music/book/{id}/speed`. A heartbeat position
   is media time whatever the speed, so *book time* is automatic ✓.
2. **±30 s on the lock screen:** two `CommandButton`s bound to custom `SessionCommand`s via
   `setMediaButtonPreferences` (R322's review, item 6), handled in `onCustomCommand`; Android 13+ shows a
   limited number of slots — two is safe.
3. **A seamless boundary needs the next ticket early.** Each part is a Jellyfin play session of its own
   (279 FR-279-6: a queue advance is stop + start; 180's teardown per session), so the phone must fetch the
   **next part's ticket while the current one plays** and hand Media3 the next `MediaItem` before the
   boundary — otherwise the cut is a ~1 s gap plus a start. Say so in the build; FR-R323-6's *seamless*
   depends on it.
4. **Progress:** `PUT /music/book/{id}/progress` at the heartbeat cadence (280's review, item 1).
5. **Finished** = the last part within its last 5 minutes — mirrors `MaxAudiobookResume` ✓.
6. **Sleep timer and fade** are client-only ✓ (a volume ramp over the last 10 s).
7. **Placement:** open question 1 stands with the owner; FR-R323-2's Listen row is the lean fix and costs
   one row.
8. **Strings:** as R321's review, item 6.
9. **Wire:** none beyond 279/281.
