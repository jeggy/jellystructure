# Phase 288 — a FLAC without a seek table is converted for a player that cannot scan it

**Status:** Planned → built the same day (see *Build notes*). Dev-authored 2026-09-30, from a device finding.
**Depends on:** 279 (`/api/tv/music/play`), 286 (the Cast receiver plays music), R330 (the Mac hands a song to a TV).

## 1. What happened

2026-09-30, the owner's Mac handed a playing song to the bedroom TV at 2:40. The TV showed the song, then the
queue moved on at once: the song "ended" without a note of it being heard.

Reproduced on the bedroom TV (BRAVIA, Chromecast built-in, Chrome 92 / CrKey 1.56), volume at zero, with a small
Cast sender that prints the receiver's `MEDIA_STATUS`:

| What was loaded at 2:40 | Through | Result |
|---|---|---|
| the song, our receiver | the server's public address (HTTPS, the reverse proxy) | `PLAYING 160` → `BUFFERING 160` → `301.8` (the end) → `FINISHED` |
| the song, **Google's default receiver** | the same address | the same — so it is not our receiver |
| the song, default receiver | Jellyfin's own port on the LAN, plain HTTP | plays from 160, after ~2 s of buffering |
| the same file, default receiver | a plain range server on the LAN | plays from 160, after ~2 s |
| **another FLAC, default receiver** | the public address (HTTPS) | **plays from 160 at once** |

The difference between the two FLACs is one metadata block. The song that fails has **no `SEEKTABLE`**; the one that
plays has one. Without a seek table a player has no index: to start at 2:40 it reads the file **from the beginning**
until it reaches that time (the proxy's log shows `bytes=0-`, `bytes=0-`, `bytes=2228224-` and nothing near the
middle of the 72 MB file). On the LAN that scan succeeds and costs two seconds and half the file; through the public
address the TV's player gives up and reports the end of the stream. With a seek table the seek is one range request.

In this library 10 of 20 FLAC files have no seek table (all ten are 24-bit / 48 kHz files from one encoder; `ffmpeg`
writes none, the reference `flac` encoder writes one by default).

A hand-over is only the first place it shows. **Every seek** in such a song on a Cast device is the same scan.

## 2. What this phase does

A player that cannot seek without an index says so, and the server gives it a stream it can seek in.

- **FR-288-1 — the capability.** `ClientCapabilities.seek_needs_index` (boolean, default `false`, additive). A client
  that sets it is telling the server: *a file I would have to read from its start to find a time in, I cannot seek
  in.* The Cast receiver sets it. No other client does: Media3 (Android) seeks in such a FLAC by binary search,
  AVPlayer and mpv play from a local read, and the web app's Chromium is current.
- **FR-288-2 — the check.** When the capability is set and the song is a FLAC, the server reads the file's metadata
  block headers (the four-byte headers only; a cover picture is skipped, not read) and looks for a `SEEKTABLE`
  block with at least one point. The answer is kept in memory per path and file size + modification time.
- **FR-288-3 — the conversion.** A FLAC with no seek table is negotiated for that client **as if the client did not
  play FLAC**: the profile sent to Jellyfin carries no `flac`, so the answer is the HLS / AAC stream a WMA already
  gets (279's transcode profile). HLS seeks by segment. Everything else about the session is unchanged.
- **FR-288-4 — what cannot be read is not converted.** A file the server cannot open (no `local_path`, a path that
  is not there) is treated as having a seek table: the song plays as it does today. A wrong "no" would only cost
  quality; a wrong refusal would stop the song.
- **FR-288-5 — it counts as a conversion.** 286 FR-286-8 already says a speaker counts against the cast ceiling
  only while it converts; this is such a conversion and is counted the same way. Nothing new.
- **FR-288-6 — old and new together.** A receiver from before this phase sends no flag and behaves as before. A
  server from before this phase ignores the flag. Neither breaks.

## 3. What this phase does not do

- **It does not repair the files.** Adding a seek table is the real fix (then the TV plays the FLAC as it is, and
  seeks at once): it belongs with 284's tag writing (*the file is the record*) — a `SEEKTABLE` written when tags are
  written, and a Dashboard row for FLAC files without one. Open question 1.
- It does not touch films, audiobooks (their parts are MP3/M4B here) or any client but the Cast receiver.
- It does not find out why the TV's player ends the stream instead of failing: the scan is the thing to avoid,
  whatever that player does at the end of it.

## 4. Acceptance

1. The bedroom TV, the song from §1 handed over at 2:40: it plays from 2:40 (an AAC stream), and the queue moves on
   only when the song ends.
2. A seek in that song from the phone's or the Mac's remote lands where it was asked to.
3. A FLAC with a seek table still direct-plays on the TV (`direct_play: true` in the ticket).
4. The phone, the Mac and the web app get the same ticket for the song as before.
5. `FlacIndexTest`: a FLAC header with a seek table, one without, one with an empty seek table, a file that is not
   FLAC, a missing file.

## 5. Open questions

1. **Write the seek table into the file** (owner): with 284's tag writing, on by the same switch? Lean: yes — a
   file without one is slower to seek in on every player, and it is a standard block, not our invention. It needs
   the `flac` tools in the image (`metaflac --add-seekpoint`) or our own frame scanner.
2. **A speaker**: the Nest Wifi points could not be tested (286's step 5a is still not live on Google's side). The
   same capability covers them; verify when a speaker answers.

## Build notes (2026-09-30)

- `shared/…/Models.kt`: `ClientCapabilities.seekNeedsIndex`. `ravilo-cast/…/Receiver.kt`: `audioCapabilities()` sets it.
- `src/linuxX64Main/…/music/FlacIndex.kt`: the block-header reader and its cache; `MusicTvRoutes.kt`: the song's
  capabilities lose `flac` when the file has no seek table.
- Seen on the bedroom TV before the fix (the table above). **Not deployed; acceptance 1–3 wait for a deploy.**
