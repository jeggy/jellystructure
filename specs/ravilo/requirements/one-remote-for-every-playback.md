# One remote for every playback, whether you cast it, started it here, or opened it from elsewhere

Task: DEB-2185

> A viewer, support ticket 2 (via DEB-2184), and the owner agreeing: it is bad UX that Ravilo has one remote when you
> cast and a different one when you open something already playing.
>
> Owner, 2026-10-23, on DEB-2185's card: *"The current cast remote is the nice looking one and todays playing page is
> a bit bad UI, so that one goes. [Its] functionality needs to be there. So all missing functionality needs to be
> added to the cast remote, so it's not a cast remote anymore, it's a remote session remote."*

## Scope

These Ravilo clients get the remote: the phone, the web app, and the desktop app (Mac and Linux, which share one
layout family). The Android TV app is never a remote, but it is a **place** a remote controls, so it gains volume
reporting (FR-12). The Cast receiver is a place too, and nothing in it changes. Tizen is out of scope.

The backend change is additive only (FR-14).

**Designed in three revisions** under `design/incoming/2026-10-23-one-remote/`, all drawn in the repo by Rannvá:
- round 1, DEB-2187, `49927577`;
- revision 2, DEB-2202, `5c48c448`;
- revision 3, DEB-2222, `7d72f305`, for music and books, the local pair, and the volume outcomes.

The film frames are owner-accepted as drawn in round 1/revision 2. The music and book frames, and every footer, are
revision 3's (`one-remote-a-music*`, `-a-book`, `-a-vol-*`, `-mac-music`, `-web-music-1440`). Where a picture and
this text disagree, this text wins, and the disagreement is a defect to raise.

**Decided by the owner on 2026-10-23 (DEB-2185, card `b22fa326`, and his correction a minute later):**
1. The Cast remote's shape becomes the one remote. Every function of the session remote and of today's music Playing
   page moves into it, and the Playing page goes.
2. Volume is an on-screen slider, with four behaviours he spelled out (FR-10 to FR-12).
3. The button says **Stop** everywhere. Someone else's playback opens the same remote, view-only.

**Taken as a reading, and flagged to him on DEB-2185:** "the Playing page goes" means it goes **everywhere**,
including music playing on this device. That leaves one music screen, not two. FR-4 is built last for that reason.

**Supersedes:**
- R369's build deviation. R369 FR-R369-6 already said the remote *is* Now playing pointed at a session; the build
  made `SessionRemoteScreen` instead. This spec restores the one-screen intent, in the Cast remote's shape.
- R245 FR-R245-7's "no on-screen slider".
- *Stop casting* as the remote's word.

Dated amendments sit in R245, R356, R357, R369 and R371, and in R322 and R337 (the Playing page).

**Not in scope:**
- the support-desk routing (DEB-2184);
- the TV's own Now playing (R380);
- a lock-screen card for a playback this phone did not start;
- Tizen.

## Today (read from `main` at `c3feb3d7`, 2026-10-23)

- **Two remotes.**
  - `CastRemoteScreen` (`ravilo-ui/…/screens/CastRemoteScreen.kt`) reads this app's own link (`ActiveCastSender`:
    the Cast SDK on Android, Ravilo's Cast-protocol sender on the desktop, `ScreenSender` to a paired TV).
  - `SessionRemoteScreen` (`…/sessions/SessionRemoteScreen.kt`) reads the server (`session_detail`, commands to
    `POST /api/tv/playback/sessions/{id}/command`).
  - A music cast uses neither. The music Playing page (`MusicPlayingScreen`) is its remote.
- **The same cast opens both on the phone that started it.** The cast mini bar pushes `Dest.CastRemote`
  (`RaviloApp.kt:3417`). The cast's own *Playing everywhere* row is not `here`, so `openSession` pushes
  `Dest.SessionRemote` (`RaviloApp.kt:1975–1983`).
- **Two mini bars:** `CastMiniBar` and `SessionMiniBar`/`DesktopSessionBar`.
- **Volume.**
  - The Cast remote has a slider on computers only (`RemoteVolume`); a phone uses its keys.
  - The web's slider does nothing: `ScreenSender` has no volume.
  - The session panel (`SessionVolumePanel`) holds a drag until any *different* report arrives. It never reverts a
    change the place did not take.
  - The Cast receiver reports its system volume at once on change (`SYSTEM_VOLUME_CHANGED`, R357 FR-R357-4).
  - The Android TV app reports its **player** level, not the TV's (R357 FR-R357-5), so the TV's own remote is
    invisible to every other app.

## Requirements

### FR-1 — One remote, chosen by what plays, never by how it was reached

Every door opens the **same** remote for the same playback:
- the mini bar;
- a *Playing everywhere* row;
- *Play on {place}* on a title page;
- the hand-off from inside the player;
- starting something on another place;
- the music *Playing* tab (FR-4).

It does not matter whether this app holds a Cast link to the place or reaches it only through the server: the
screen, its controls and its words are identical (`one-remote-a-identity-pair.png`). `SessionRemoteScreen` and
`Dest.SessionRemote` are retired. What plays decides which variant shows: a film or episode (FR-2), or a song or
book (FR-3).

### FR-2 — The remote's shape: the Cast remote's, for films and episodes

The shape is R245 FR-R245-7's, as drawn in `one-remote-a-cast-film.png`, `-a-nextup`, `-a-tracks-sheet`,
`-web-film-1440`, `-web-film-390` and `-mac-film`. Top to bottom:
1. the **place pill** (FR-6);
2. the 16:9 art card (wordmark tile when there is no art);
3. kicker + title;
4. the state line *Playing on {place}* / *Paused on {place}*;
5. the draggable seek bar with times;
6. **−10 s · ⏯ · +30 s**;
7. the volume row (FR-10);
8. the footer: *Audio & Subs* (shipped `player.audio_subs`), *Next episode* (series only), **Stop**.

- *Audio & Subs* opens the **shared** tracks sheet with *Applies on {place}* and the S · M · L size row (FR-R245-8:
  one component, a second implementation forbidden). This replaces the session remote's chips.
- The next-up countdown is mirrored from the place (*Play in N* · *Watch credits*), and a cancel is sent, never
  assumed (FR-R245-9).
- Landscape centres the same layout.

### FR-3 — Music and books in the same shape, with every function of today's Playing page

The music remote is the Cast remote's shape (`one-remote-a-music.png`, revision 3) and carries everything the
Playing page and the session remote do today:
- the place pill;
- *Playing from {context}*;
- title, artist · album, version chips and *Bonus* (R344/R373), ♥;
- ⋯ (the track menu);
- a swipe on the cover for next/previous;
- seek;
- shuffle · previous · ⏯ · next · repeat;
- lyrics (FR-R322-7: synced; the head shrinks to a small cover and the title);
- *Up next* (tap jumps; reorder where the place allows it);
- volume (FR-10);
- **Stop**.

A **book** gets the book variant: chapter line, the whole-book scrubber in book time (R369 FR-R369-9), −30 s · ⏯ ·
+30 s, speed. On the desktop and the web at a desk width, the lyrics sit beside the cover.

Where each part sits is revision 3's:
- `one-remote-a-music.png` (Playing from, ♥ level with the title, version chips, ⋯ in the header's right slot);
- `-a-music-swipe`, `-a-music-lyrics`, `-a-music-upnext` (*Up next* is a sheet over the remote; tap jumps);
- `-a-music-idle` (FR-R322-3's last-played song, paused);
- `-a-book` (chapter strip; speed · −30 s · ⏯ · +30 s · sleep; footer *Chapters* · *Add bookmark* · **Stop**);
- `-mac-music` and `-web-music-1440` (lyrics beside the cover).

The volume block is inline for every kind, and the square art shrinks to make room. The cover is smaller than
today's 330 px, which is a known cost of the owner's inline slider, not a defect. The book's speeds stay
`BOOK_SPEEDS` as shipped; the frame's 1.25× is a stand-in.

### FR-4 — The Playing page is retired everywhere

Music or a book playing **on this device** opens the same remote, with the place pill reading *This phone* /
*This Mac*, and the state line reading *Playing here* (`one-remote-a-music-pair.png`, `-a-music-here`).
- **The music-mode *Playing* tab shows the remote inside the tab, with the bottom nav under it**, as today's tab
  does (`-a-music-here`). The full-screen alternative (`-a-music-full`) is not adopted: keeping the tab is the
  no-change choice.
- **The *Queue* tab stays** as it is. *Up next* on the remote is the same queue as a sheet. `MusicPlayingScreen`, `BookPlayingScreen` and the desktop `DeskPlaying` go once this FR is
built. R322 FR-R322-2/4/6/7 and R337 FR-R337-7 carry dated amendments. Built last (see *Scope*).

### FR-5 — The source is invisible; the fastest path carries the command

1. Both drivers feed one status:
   - this app's own link (`CastRemoteStatus`);
   - the server's `SessionDetail`, mapped into the same status. This is the `SessionSender` R369's dev review
     item 9 designed and never used.
2. A command goes over this app's direct link when it holds one (R356's faster path), and through the server
   otherwise. R369's revision rule (`expect_index`/`expect_item`) covers both paths, so two controllers never
   double-skip.
3. A control the place cannot do (not in `SessionDetail.ops`, or not offered by this app's link) is **absent**.
4. A control that cannot reach the place right now is **greyed**, but only in *Lost contact* (with *Try again*) and
   *{place} is offline · paused at {t}*. A failed move greys nothing (round-1 review, defect 4).

### FR-6 — The place pill: Move to…, Play here

The pill names the place (*Stue TV*, *Stue + Gæsteværelse*, *This phone*) and opens *Move to…*
(`one-remote-a-move-to.png`). That sheet lists the places, with the current one ticked and *Play here* last
(R372 FR-R372-2/3). Through a move, the remote shows *Moving to {place}…*, then either the new place or
*Couldn't move to {place}*. ⏯ keeps the last **reported** glyph throughout (round-1 review, defect 3). The states
are those of `one-remote-a-states-strip.png`.

### FR-7 — Stop

- The word is **Stop** (shipped `cast.stop_room`) on the remote, the mini bar, the desktop capsule and Android's
  media card. *Stop casting* (`cast.stop`) is no longer drawn.
- Stop ends the playback wherever it plays (R369 FR-R369-6; R356 FR-R356-14 for a cast, whose song still comes back
  to the phone paused).
- Stopping **someone else's** playback asks every time with one inline confirm (`one-remote-a-stop-confirm.png`;
  R369 owner decision 1). Your own never asks.
- On the desktop, Stop is drawn once: in the remote's footer while the remote is open, and in the capsule otherwise.

### FR-8 — Someone else's playback: the same remote, view-only

Opened on someone else's playback with the household switch off (`one-remote-a-someone-else.png`), the remote shows:
- the place, the art and the title;
- a read-only progress bar;
- *{person} is watching* / *is listening*.

The transport, volume, the tracks sheet and Stop are **absent**. One line says why there is nothing to press. With
the switch on, it is the full remote, and Stop asks first (FR-7).

### FR-9 — One mini bar

`CastMiniBar` and `SessionMiniBar` become one bar on the phone, and the desktop capsule shows the same thing:
- thumbnail, title, the place in accent, ⏯, a hairline progress line;
- the **+N** chip when more is playing (`one-remote-a-minibar-cast`, `-elsewhere`, `-music`).

Which playback it shows is R368 FR-R368-8's rule (the one this app touched last). A tap opens FR-1's remote.
Swipe-down does nothing (FR-R245-6).

*Amended 2026-10-23 (DEB-2236):* on the desktop capsule and the GNOME bar, the playing **song's** title and artist
open its album, not the remote (FR-R337-6 as amended). There, the artwork is the bar's door to FR-1's remote. A film,
an episode, a book, a song with no album, and the phone's bar are unchanged: a tap anywhere on them opens the remote.

### FR-10 — Volume: an on-screen slider for every place that reports one

- Every remote, on the phone, the web and the desktop, has the volume row for every kind and every place: a TV, a
  speaker, a group, a Ravilo app.
- **One place:** one slider. **Several rooms:** R371 FR-R371-4's master *Volume* plus one slider per room, each
  with its own mute.
- **On the desktop and the web,** the volume block stays inline too. Where a window is too short for it (with three
  rooms the page needs about 1040 px on the Mac and 960 px in a browser), the remote's column scrolls; the capsule
  never covers a slider. The capsule's volume popover (R371) stays for pages other than the remote.
- On the phone, the volume keys move the same level the slider shows (R324's HUD).
- **A place that does not report its level** shows *—*, cannot be dragged, and gives the shipped reason
  *{place} doesn't report its volume* (`volume.no_report`). It never shows a guessed or zero level.

This supersedes R245 FR-R245-7's "no on-screen slider". The owner chose one rule for speakers and screens, knowing a
TV's own remote can move the level too. FR-12 is what makes that fight visible instead of confusing.

### FR-11 — Volume: it opens true, and it does not flicker

1. **Opens true.** The slider shows the level the place last reported. The remote asks for a fresh state when it
   opens: R356 FR-R356-6's ask on the direct link, and the server's `SessionDetail` (which carries `volume`) on
   attach. Until a level is known, the slider shows *—* and is not draggable, **never 0**.
2. **While dragging,** no report moves the thumb. `set_volume` is sent at most once every 200 ms during the drag,
   and once more with the final level on release.
3. **After release, the thumb stays where the viewer left it** while that command is in flight. A report that does
   not match the requested level (± 1) is not drawn during that time. This removes the 2 % → 15 % → 2 % → 15 %
   flicker the owner described.
4. **Settled:** the first report matching the requested level (± 1) ends the in-flight state. From then on the
   slider is the reported level.
5. **Not taken:** if no matching report arrives within **3 s** of release (R369's *Can't reach* window), the thumb
   returns to the **latest reported** level. One line under the slider names the level the place actually has,
   *{place} kept {n} %*, for 3 s (`one-remote-a-vol-reverted-film.png`, `-music`). While a request is in flight
   nothing is marked (`-a-vol-pending-film`, `-music`).
6. **Only this app's own request is ever held.** The thumb never shows a level the place did not report, except the
   viewer's own pending request, and that is bounded at 3 s. This is a command in flight, the same class as R369's
   400 ms dim, not a derived or optimistic state. Constitution invariant 4 is read that way here.

### FR-12 — Volume: it follows every other change, live

1. A level the place reports for any other reason moves the thumb as soon as the report reaches this app, whenever
   this app has no volume command in flight. Other reasons include:
   - the TV's or speaker's own remote;
   - Google Home;
   - another Ravilo app;
   - the Jellyfin dashboard.
2. Each place reports a change **at once**, not on its next heartbeat:
   - **Cast receiver:** unchanged. `SYSTEM_VOLUME_CHANGED` already reports at once (R357 FR-R357-4), and the server
     pushes it to every attached controller as `session_detail`.
   - **Android TV app (amends R357 FR-R357-5):** a session's volume is the TV's **media stream** volume
     (`STREAM_MUSIC`), not the player's own gain, whenever Android reports that stream as adjustable. It reports on
     every change: a listener on the stream, not a poll. A `set_volume` sets that stream. When the TV reports a fixed
     volume (volume handed to a soundbar or receiver over HDMI-CEC, `isVolumeFixed()`), the TV reports **no** level,
     and FR-10's *—* applies. Which case each household TV is in is a QA observation, not an assumption.
   - **Phone, desktop and web as places:** R357's player level, unchanged, reported on change.
3. For a place reached over this app's direct Cast link, the receiver's status on that link moves the thumb the same
   way.

### FR-13 — Platforms

- **Phone:** the remote is full-screen, opened over any page. The music *Playing* tab shows it in the tab
  (revision 3).
- **Web and desktop:** the wide layout (`-web-film-1440`, `-mac-film`, `-mac-music`), with art and remote side by
  side.
- **The web holds no Chromecast link** (`CastSenderWasm` is empty), so every place it controls goes through the
  server. That is exactly FR-5.
- The narrow web (390 px) is the phone's layout.

### FR-14 — Wire: additive only

- No field is removed or retyped.
- `SessionDetail.volume` already exists.
- If the build finds the server path lacks the next-up countdown or a subtitle-size op that the Cast path has, they
  are added as **optional** `SessionDetail` fields and a new `op` value. Older apps ignore both, and an older place
  is simply not offered the op (absent, FR-5).
- The Android TV app's report keeps using R357's existing `volume_percent` / `muted` fields.
- `WireCompatTest` stays green.

### FR-15 — Strings

The remote reuses shipped keys:

| Key | Use |
|---|---|
| `cast.stop_room` | Stop |
| `player.audio_subs` | Audio & Subs |
| `volume.no_report` | |
| `session.move_to` | |
| `session.moving` | |
| `session.move_failed` | |
| `session.place_offline` | |
| `session.stopped_by` | |
| `session.stop_person` | |
| `session.cant_reach` | |
| `cast.play_here*` | |

Round 1's draft table (`One Remote - Directions.html`) maps onto these and is **not** adopted as new keys.

One new key (revision 3's wording; da/fo are drafts):

| Key | en | da | fo |
|---|---|---|---|
| `volume.kept` | {place} kept {n} % | {place} beholdt {n} % | {place} helt {n} % |

`scripts/check-ravilo-strings.sh` green. The lexicon is regenerated (R279/R288).

## What the code makes cheap or expensive

- **Cheap:**
  - `CastRemoteScreen` already reads one status type (`CastRemoteStatus`), which R265 proved by putting
    `ScreenSender` behind it unchanged. A `SessionSender` from `SessionDetail` is the same move.
  - The tracks sheet, the next-up mirror and the states already exist in it.
  - The routing fix is one `openSession` branch plus the mini bar's `onOpen`.
- **Moderate:**
  - The volume in-flight rule is new and pure, and lives in a commonMain function:
    `volumeShown(reported, request, sentAt, now)`.
  - The Android TV app's stream-volume listener is new platform code.
- **Expensive:** FR-3/FR-4. Lyrics, versions, ♥, ⋯, queue and the book variant move into the remote's shape, and
  three screens (`MusicPlayingScreen`, `BookPlayingScreen`, `DeskPlaying`) retire.

## Build order (one engineer; each slice blocked by the one before)

1. **Films and episodes:** FR-1 (for films and episodes), FR-2, FR-5, FR-6, FR-7, FR-8, FR-9, FR-13. The one remote,
   both drivers, one mini bar, *Stop*, view-only.
2. **Volume:** FR-10, FR-11, FR-12 (including the Android TV app's stream volume), FR-15's new key.
3. **Music and books:** FR-3 and FR-4.

## Acceptance

1. **The same screen from both doors.** *Sommeren ’92* cast from the Pixel to Stue TV: the mini bar and the
   *Playing everywhere* row open the same remote. Started from the MacBook on Soveværelse TV and opened on the Pixel:
   the same screen, with only the place name different. *(Tests: a Robolectric `OneRemoteTest`, both sources →
   identical tree; device check.)*
2. **Nothing lost from the session remote.** On a film reached only through the server: *Move to…*, *Play here*,
   *Audio & Subs* (shared sheet), ±10/30 s and Stop all work. On someone else's (switch off), the transport is
   absent. *(Tests: `OneRemoteTest`, `SessionRemoteStateTest` carried over.)*
3. **Volume opens true.** Opening the remote on a TV playing at 37 %: the slider reads 37 % at first draw, or *—*
   until the first report, never 0. *(Pure test of `volumeShown`; device check.)*
4. **No flicker.** Drag from 2 % to 15 % against a place that reports its old level once before obeying: the thumb
   stays at 15 % throughout. *(Pure test with a fake report sequence; device check on a speaker.)*
5. **A change that did not take returns.** A place that never confirms: 3 s after release the thumb is back at 2 %
   and the line shows. *(Pure test.)*
6. **Live from elsewhere.** With the remote open on Stue TV, the TV's own remote changes the volume: the Pixel's
   slider moves within about a second of the receiver's report. The same on Soveværelse TV, or that TV shows *—* if
   its volume is fixed: record which. *(Device check: QA.)*
7. **Stop says Stop**, on the remote, the mini bar, the capsule and the Android media card. *(Strings test;
   screenshot.)*
8. **Music** (slice 3): local and remote music are the same screen, and every Playing-page function in FR-3 is
   present. *(After revision 3; listed here so the slice inherits it.)*
