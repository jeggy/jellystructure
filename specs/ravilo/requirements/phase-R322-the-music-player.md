# Phase R322 — The music player: Now playing as a tab, the mini bar, the queue, and playing in the background

## Status

`✓ Built` 2026-09-28, **not deployed, not tried on a phone** (build notes at the end). Written 2026-09-28 from the phone brief (§D–§G, §J4–§J6, §M5) and the research report (§5, §6.3,
§6.4), with the mockup `design/ravilo/mobile/ravilo-music-player.js` (+ `ravilo-music.css`), including the
owner's *"a full-screen of what's now playing there. If nothing is playing, then it should just be whatever was
last played in pause mode."* **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Builds on **R321** and **279**, **R244** (the time bubble,
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
   on those until H1's *Convert…* is run? Lean: yes, and say nothing. **Answered by the owner 2026-09-28:** the lean — accept the gap, say nothing; *Convert…* removes it.
2. Resumption after reboot (`onPlaybackResumption`): restore the last queue paused, or only the last song?
   Lean: the queue. **Answered by the owner 2026-09-28:** the whole last queue, paused.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Thirteen items; items 1, 3 and 11 change how it is built, none blocks.

1. **Where the service lives.** `ravilo-ui` has **no** `AndroidManifest.xml` and `media3-session` is its
   androidMain dependency (`ravilo-ui/build.gradle.kts:88`). The `Service` class may sit in `ravilo-ui/androidMain`,
   but its `<service android:foregroundServiceType="mediaPlayback" android:exported="true">` with the
   `androidx.media3.session.MediaSessionService` intent filter, and `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, go in
   `ravilo-android/src/main/AndroidManifest.xml` by FQCN — none of them is there today; `targetSdk 36`
   makes the FGS type mandatory.
2. **`RaviloAppContext.init(context)`** stores `context.applicationContext` and builds the Coil loader
   (`RaviloAppContext.kt:31-50`) — safe from `Service.onCreate()` ✓ (FR-R322-1's third bullet holds).
3. **A second seam — and the web must still compile.** `RaviloPlayer` is an `expect class` with Android and
   wasm actuals; the music engine is a new `expect class RaviloMusicPlayer` (a `MediaController` on Android).
   Its **wasm actual must exist as a no-op** or `:ravilo-web` fails to build, even though the web player is a
   later phase.
4. **Renderers:** reuse `RaviloPlayerEngine.renderersFactoryProvider` (`phone/MainActivity.kt`) so FLAC, Opus
   and ALAC decode through the same FFmpeg audio renderers; there is no ASF extractor, so WMA arrives as HLS ✓.
5. **The dex fence.** `scripts/check-player-dex.sh` guards `PlayerScreen`'s register count (limit 250, cliff
   256) — the music player is **new files** (`MusicPlayingScreen.kt`, `MusicMiniBar.kt`, …) and adds nothing
   to `PlayerScreen`.
6. **Lock-screen buttons:** Media3 1.8.0 has `MediaSession.setMediaButtonPreferences(List<CommandButton>)`
   (verified in the AAR; `setCustomLayout` is the deprecated form). Music keeps the default previous/next;
   R323's ±30 s use it.
7. **Resumption needs a persisted queue.** `onPlaybackResumption` restores from a per-device
   `MusicQueueStore` (expect/actual like `PlaybackPrefsStore`, `screens/PlaybackPrefsStore.kt:7`): track ids,
   index, position. This is also where 279's *last position* lives (279's review, item 5). Open question 2:
   the queue ✓.
8. **Audio focus.** The music engine sets `setAudioAttributes(…, handleAudioFocus = true)` and
   `setHandleAudioBecomingNoisy(true)`; the video engine already sets attributes
   (`seams/RaviloPlayerAndroid.kt:97`), so starting a video pauses music by the platform's rule. FR-R322-12's
   *stops* is then one explicit call (pause → clear queue) from the video start path.
9. **R292's sentence** goes under `### The rule` in `phase-R292-…md` (`:194`) when this is built.
10. **Mini bar stacking:** `miniBarOver(dest)` + `castMiniBarHeight` (`RaviloApp.kt:989-992`,
    `theme/Dimens.kt:62`) → a `musicMiniBarHeight`, both summed when both show ✓.
11. **Even out volume is a turn-down only.** `player.volume = 10^(gain/20)` clamped to ≤ 1: a track louder
    than the target is turned down; a quieter one cannot be raised without a limiter. FR-R322-9 amended to say
    so; the sentence in Settings stays true.
12. **`POST_NOTIFICATIONS`** is a runtime prompt on Android 13+: ask on the **first play**, not at launch;
    without it the service still runs, only the card is missing.
13. **Wire:** none beyond 279.

## Build notes (2026-09-28)

Built with R321 (see its notes for the build and fence results). **Not installed on any device**, so acceptance 1–7
are unverified.

1. **One engine (FR-R322-1).** `MusicEngine` (androidMain) owns an `ExoPlayer` — music attributes with focus
   handling, becoming-noisy, a network wake lock, and the app's FFmpeg renderers when set (dev review 4) — and a
   `MediaSession` over a `ForwardingPlayer` whose *next/previous* come from the queue. `RaviloMusicService`
   (`MediaSessionService`, declared in `:ravilo-android`'s manifest with `mediaPlayback`, dev review 1) hosts that
   session and is started with the first song; removing the app from recents stops it. **Deviation:** Compose calls
   the engine directly (same process) instead of through a `MediaController`. The web actual is a no-op with
   `supported = false` (dev review 3), so music mode never appears there.
2. **One song, one session (279 FR-279-6).** A song's stream is asked for when it starts (which opens its Jellyfin
   session), progress is reported every 10 s and on play/pause, and a skip, the end or a stop reports the stop. ExoPlayer
   holds only the playing song, so **there is a short gap between songs** — open question 1 answered more broadly than
   asked: no gapless in v1.
3. **The Playing tab (FR-R322-2..6, J4 → cover-first):** *Playing from*, the cover (a swipe is next/previous, sliding
   off), the title in a marquee, artist and album as links, ♡, the seek bar with the time bubble, shuffle · previous ·
   play/pause (the buffering pulse in its place) · next (absent at the queue's end) · repeat (off → all → one; one is a
   drawn dot badge, the toast names the state), lyrics (absent without) · ⋯. No queue button (R321 open question 1)
   and **no cast glyph** (nothing on the server casts music yet). Landscape is full-screen with a close chevron.
4. **Nothing playing (FR-R322-3, dev review 7):** the queue this phone saved for this viewer, paused where it was;
   else 279's last-played song at 0:00; else *Nothing played yet*.
5. **States (FR-R322-5):** failure is a sheet with Try again / Skip, no cause named; queue end stays on the last song
   paused at 0:00.
6. **Lyrics (FR-R322-7):** synced lines in Space Grotesk 24 sp, the current one lit (Noir: ink), past ones dimmed,
   followed at ~40 %; a tap seeks; a hand scroll stops the follow until the next song. Plain lyrics are body text.
7. **The queue (FR-R322-8):** the Queue tab — now playing, up next with a drag handle and swipe-left to remove, *Clear
   queue*, *N songs · T left*. Playing a context replaces the queue; *Play next* / *Add to queue* insert and toast. The
   rules live in `MusicQueue` (common, tested).
8. **Even out volume (FR-R322-9, J6 → on):** Settings ▸ Listening; album gain for an album in order, track gain
   otherwise, as a volume scale ≤ 1 (dev review 11).
9. **The mini bar (FR-R322-10, J5 → swipe down stops):** docks on the bottom bar where the cast mini bar docks (under
   it when both show — content pads by the sum, dev review 10), hidden on the Playing tab and wherever a picture plays,
   shown in video mode too. Tap → Playing.
10. **Music and video (FR-R322-12):** a film, Live TV or the cast remote taking the screen stops the song. **Deviation
    from dev review 8:** the queue is kept, paused where it was, rather than cleared. Switching mode does not stop it.
11. **The platform's card (FR-R322-13):** Media3's notification from the item's metadata — title, artists joined ", ",
    album, the cover at 720 px. **Deviation:** no wordmark is rendered for a song without a cover. The notification
    permission is asked on the first play, once per device (dev review 12).
12. **Resumption (open question 2 → the queue):** `onPlaybackResumption` restores this viewer's saved queue and opens
    its song. R292's spec carries the one sentence (dev review 9).
