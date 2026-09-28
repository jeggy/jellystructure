# Music on the household's speakers: casting Ravilo's music to Google Cast audio devices

**Date:** 2026-09-28 · **For:** the design project (Cosmos) that owns `design/ravilo/` and `design/app/`, and
the dev team · **Status:** research + design brief, awaiting round-1 directions on the questions in §8 ·
**Builds on:** 218 (the receiver, its registration and enrolment), 226/227/235 (registration fields, the public
address, publishing), R245 (sender, mini bar, remote, reconnect), R265/R270 (Ravilo's own device sheet, three
tiers), 236/R264 (screens: the backend drives a device for a phone), 279 (`/api/tv/music/**`), R321/R322 (the
listening mode and the music player), `research-reports/google-free-android-and-foss-cast-sender-2026-09-19.md`.

> Owner, 2026-09-28: *"Now that we have a music app as well, we want to see Google Cast audio devices as well.
> Currently we have two of these available in our household, 'Stue' and 'Gæsteværelse', these are audio only
> speakers and Ravilo music should be able to cast to these devices without any issues and the speakers should
> be able to play even if I close the app fully on my phone and it should be able to take control of those
> devices again if I open up the app again. … (This might require us to create a new Chromecast app?)"*

**The short answer:** no new Cast application. The household's registered receiver is refused by the two
speakers today for exactly one reason — the application is registered without *Supports casting to audio-only
devices* — and that is a checkbox on the existing application in Google's console. Measured this afternoon
(§1): the speaker answers `APP_UNAVAILABLE` for our app id and `APP_AVAILABLE` for Google's default receiver;
the kitchen hub, which has a screen, answers `APP_AVAILABLE` for ours. Everything the owner asked for — play on,
survive the phone closing, be picked up again — is how Cast already works once the receiver is allowed on the
device and **owns the queue** (§3). What is new is small and listed in §6.

## 0. What the household has (measured 2026-09-28, read-only mDNS + one Cast status query)

| Name | Model | Kind | Right now |
|---|---|---|---|
| **Stue** | Nest Wifi point | **audio only** | idle, volume 25 % |
| **Gæsteværelse** | Nest Wifi point | **audio only** | **playing Spotify**, volume 10 % |
| Køkken hub | Google Nest Hub | display + audio | idle (ambient), standby |
| Stue TV · Soveværelse TV | BRAVIA (Chromecast built-in) | display + audio | standby |

The two speakers are Nest Wifi *points*: mesh extenders with a speaker and the Assistant, and as Cast targets
exactly a Nest Audio. Someone in the house already casts Spotify to one of them, which is the experience to
match: pick the speaker in the app, put the phone away, pick it up again later and the app still knows what is
playing. A speaker *group* (Stue + Gæsteværelse together, made in the Google Home app) would appear as a fifth
device and behaves as one target.

## 1. Why the speakers do not show up today, and what it takes

Two facts, both verified:

1. **A Cast device only offers an app it has been told supports it.** The phone's route picker asks each device
   for our app id's availability; an audio-only device answers `APP_UNAVAILABLE` unless the application is
   registered as supporting audio-only devices. Asked directly this afternoon over the Cast protocol:

   | Device | our app id | Google's default receiver (`CC1AD845`) |
   |---|---|---|
   | Stue (speaker) | `APP_UNAVAILABLE` | `APP_AVAILABLE` |
   | Køkken hub (display) | `APP_AVAILABLE` | `APP_AVAILABLE` |

   That is the same mechanism that hid the TVs before the app was published (218's first test, 2026-09-18):
   not a code bug, a registration state. R265's sheet shows no speakers because the SDK's discovery never
   returns them for an app they cannot run.
2. **The flag is a checkbox on the existing application.** Google's registration page: *"Supports casting to
   audio-only devices"*, editable from the console's Applications page (*Edit*), with up to fifteen minutes to
   propagate — and, from experience with publishing, a device may need a restart before it re-asks. **The
   application id does not change.** So 226's steps gain one line and 218's card one state; no second app, no
   second US$5, no second receiver URL.

What Google asks of a receiver that runs on an audio device (its *Audio Devices* rules, the parts that bind us):
never send it a video stream (*"doing so may cause the application to fail"*); no graphics-heavy DOM (no
gradients, rotation, blending — the receiver has no display, but the page still runs); keep the media-source
buffer at ~2 MB and the audio bitrate under 2 Mbps; provide track name, artist, album, album artist as standard
metadata; broadcast media status on the standard namespace so the device's own controls (Assistant, the Home
app, the phone's volume keys) work; and *"critical information, and most of the user interface, must be shown on
the sender"*. The receiver learns it is on such a device from the launch header
`CAST-DEVICE-CAPABILITIES: {"display_supported": false}` or `getDeviceCapabilities()`.

## 2. What plays on a speaker

Google's supported-media list for Chromecast Audio, Google Home and Nest speakers: **FLAC (to 96 kHz/24-bit),
LC-AAC, HE-AAC, MP3, Opus, Vorbis, WAV**, in MP3/MP4/OGG/WAV/WebM containers, plus **HLS and DASH** through the
receiver SDK. No WMA, no ALAC. Against the household library (60 tracks: 38 WMA, 22 MP3):

| File | Phone today (279) | Speaker |
|---|---|---|
| MP3, FLAC, AAC/M4A, WAV | direct play | direct play — the receiver loads the file URL |
| Opus, Vorbis | direct play | listed as supported; one forum thread claims software decoding on audio devices — **test one file** |
| ALAC | direct play | not listed — transcode to AAC |
| **WMA** | Jellyfin transcodes to HLS/AAC 256 k | the same HLS URL; CAF plays audio HLS (the music research §5.4 already notes this) |

So the receiver's device profile for a speaker is 279's phone profile minus Opus-until-tested and ALAC, and
the WMA path is unchanged. 278's *Convert…* removes the transcode for those 38 files everywhere at once.

## 3. Playing on after the phone closes, and taking it back: how it works

This is not a feature to build; it is the shape of Cast, and R245 already relies on it for video. Three parts:

**3.1 The receiver is its own Ravilo device (218, R245 FR-R245-13).** It enrols with a hand-off code, holds a
device token, negotiates its own stream with the backend and reports progress and stop itself. The phone is a
remote from the first second. Kill the phone and the receiver notices nothing. Force-stopping the phone mid-cast
is R245's acceptance test 1 (*the TV keeps playing*), and the household's sender already sets *stop the receiver
when the session ends* to **off**, precisely so an end the SDK decides on its own leaves the device playing.

**3.2 The queue must live on the receiver, not the phone.** R322 FR-R322-8 says *the Media3 playlist is client
state*. On a speaker that is the one thing that cannot be true, or the album stops at the end of the song the
phone left it on. Cast has a receiver-side queue for this: the sender loads a **queue** of items (each our own
`ravilo://track/{id}` with the track's facts as metadata), the receiver plays through it and reports every
change back; the sender's queue view is a mirror of the receiver's, and the SDK's queue operations —
insert, reorder, remove, jump, repeat, shuffle — are standard messages, so *Play next*, *Add to queue* and the
drag-to-reorder of R322's Queue tab keep working while casting. The receiver resolves each item's stream when
that item comes up (CAF calls the receiver's playback-info handler per item), so a token or a WMA transcode is
negotiated one song at a time, never for the whole album up front. Nothing is fetched from the phone.

**3.3 Taking control again is a resume, then a rebuild (R245 FR-R245-5).** On app start the Cast SDK resumes
the saved session against the stored app id; then *exactly one of two things happens*: the session is alive ⇒
the mini bar appears with the **live** position and the queue read from the receiver; or the receiver has
finished or is gone ⇒ **nothing is shown at all**. The household's sender turned the SDK's background
reconnection service **off** (a real incident on 2026-09-26: the killed app was restarted in the background to
resume, frozen by Android, and on the next open the SDK ended the stuck session), so the one foreground
resume at start-up is the reconnect — and it works, verified against the stue TV. For music the same path
rebuilds the Playing tab and the Queue tab from the receiver's media status and queue items. A different phone
in the house running Ravilo sees the speaker as *playing* in the sheet and can join it, since any sender with
the same app id may connect to a running receiver.

One thing the phone can no longer be: the thing that reports playback to Jellyfin while casting. The receiver
does that (218's *Chromecast via Ravilo* identity), so play counts and *Recently played* stay right with the
phone off.

## 4. The three roads, and why the middle one

| Road | What it is | Verdict |
|---|---|---|
| **A · Google's default receiver** (`CC1AD845`, no registration) | send the speaker plain URLs with metadata; Cast's own queue | Works today on every device. But the receiver is not a Ravilo device: no enrolment, no token, no progress reporting, no ACL, and the stream URL must carry a durable key in the query — the class 218 rejected for Jellyfin's own receiver. Rejected. |
| **B · Our receiver, registered for audio devices** (lean) | tick the box; the same `/cast/` bundle detects `display_supported: false` and runs headless; receiver-owned queue | One console edit, one receiver mode, the queue endpoint. Keeps 218/R245's whole model. **Lean.** |
| **C · The backend as the Cast sender** (236's *screens* for speakers) | jellystructure speaks the Cast protocol to the speaker, so an iPhone or any browser can start music on a speaker through the server | The only road that reaches the speakers from the iPhone's web app (no Cast SDK exists in a browser, and these speakers have no AirPlay). But the backend is Kotlin/Native with no TLS socket API, so it needs a helper process (the FOSS report's sender, or a pychromecast sidecar); the household's Music Assistant already does exactly this from the same host. **Later, as its own phase**; the iPhone's music path today is the phone itself. |

## 5. What is already there (so nothing is drawn twice)

- **`ravilo-cast`** (218/R245): the receiver bundle, enrolment by hand-off code, the LOAD interceptor that
  negotiates a ticket, next-episode auto-advance, ten screens, three skins. It loads one item as HLS; it has no
  queue and no audio-only mode.
- **`CastSenderAndroid`** (R245/R265): the SDK, stop-on-end off, background reconnection off, the SDK's
  notification with play/pause · ±30 s · stop casting, session resume on start-up, one `load()` of one item as
  a movie. No queue API used yet.
- **R265's sheet**: tier 1 *on this network*, tier 2 *all your TVs*, tier 3 AirPlay's footnote; Chromecast rows
  inside it, read from the same route list the SDK's dialog read. Speakers would land in tier 1 the moment the
  registration allows them — they are Cast routes like any other.
- **R322**: the Now playing tab draws a *casting* state (*Playing on {TV}* under the credits, the cast glyph
  lit) and stacks the music mini bar under the cast bar; the build note says *no cast glyph — nothing on the
  server casts music yet*.
- **`app/settings.html` → Chromecast card** (218/226/235): three states, eight steps in three groups.
- **`app/ravilo-users.html`**: every device row with kind, platform, version history, the decode ceiling line.

## 6. What is new, by side

**Backend / receiver (prospective admin phase 286 — verify on `main`):**
1. **Registration step**: the Chromecast card's *Register the receiver* group gains the line *Tick "Supports
   casting to audio-only devices" — this is what lets Ravilo see the household's speakers*, and the registered
   state says whether speakers are reachable — honestly, the way 218 FR-218-7 does: *only a real cast confirms
   it*, plus the fast truth 218's test found (a Cast availability query the backend could run itself, §8 Q6).
2. **The receiver's audio-only mode**: on `display_supported: false` no screens are built at all (no idle mark, no
   gradients — the DOM stays empty), the device profile is §2's, the media-source buffer is capped, and the
   metadata block carries title · artist · album · album artist · cover URL so the Home app and the Assistant
   show what is playing. On a display device (hub, TV) a **Now playing** screen: cover, title, artist, album,
   a hairline progress — the receiver's eleventh screen, no controls (FR-R245-15's rule).
3. **The receiver-owned queue**: the LOAD carries a queue of track items; the receiver resolves each item's ticket
   as it comes up (per-item playback-info handler), reports the queue back, advances, honours repeat/shuffle,
   and reports progress and stop for each song (279's `progress`/`stop` per track, so *Recently played* and
   play counts are right).
4. **Users & devices**: a speaker enrols as `kind = cast` with an *audio only* mark; the decode-ceiling line
   reads *audio only*; the session ceiling (218 FR-218-8) counts a speaker as one session **only while it
   transcodes** (a direct-played MP3 costs the server nothing — §8 Q4).
5. **Nothing changes in 279's DTOs**; the receiver reads the same `/api/tv/music/**` the phone reads.

**Phone (prospective Ravilo phase R324 — verify on `main`):**
6. **Speakers in the sheet**: tier 1 lists them with a speaker glyph, a group with a group glyph; a speaker
   already playing Ravilo reads *Playing · {song}* and is tappable (join); one playing something else reads
   *Busy · Spotify* the way a TV names its viewer (R270) — the Cast status carries the running app's display
   name. In **video mode** speakers are absent from the sheet (Google's rule: never send video to an audio
   device), and the sheet says nothing about it.
7. **Now playing while casting**: the tab becomes the remote — the device chip under the title (tap to open
   the sheet), transport acting on the receiver, position from the receiver's reports, the queue mirrored, the
   phone's own volume keys driving the speaker's volume with small steps, *Stop casting* in the ⋯ menu. Lyrics
   stay on the phone (the speaker cannot show them; the position comes from the receiver so they still scroll).
8. **The mini bar** carries *· Stue* and keeps R322's swipe-down-stops — on a cast, swipe-down **stops the
   speaker** too (§8 Q2).
9. **Hand-off both ways**: casting from a song playing on the phone hands position and queue over (R245
   FR-R245-4's rule for music); *Play on this phone* from the remote pulls them back and stops the speaker.
10. **Reconnect**: FR-R245-5 as written, with the Playing and Queue tabs rebuilt from the receiver. A speaker
    that finished the queue while the phone was away ⇒ silence, and the Playing tab shows the last-played song
    paused (R322 FR-R322-3), which is the same picture as never having cast.
11. **The SDK's notification** on the lock screen shows cover · title · artist with play/pause · next ·
    previous · stop casting (music actions, not ±30 s), replacing R322's own media session while a cast runs.

## 7. What to draw

- **The sheet with speakers** (Pixel 9 and iPhone frames; on the iPhone the speaker rows are absent, since no
  browser has a Cast sender — R265's reason, unchanged): idle speaker · speaker playing Ravilo (join) · speaker
  busy with another app · a group · the same sheet in video mode with speakers gone.
- **Now playing, casting**, in all three skins: the device chip, the transport, the queue mirrored, volume from
  the keys, *Stop casting*; then **Playing on Stue** with lyrics scrolling from the receiver's position.
- **The mini bar** with *· Stue*, stacked under a video cast bar (R322's frame, now with a speaker name).
- **Reconnect's two outcomes** for music: the bar appears with the live song, or nothing at all.
- **The receiver on a display** (hub, TV): the Now playing screen at 1920×1080 and on the hub's 1024×600, Aurora ·
  Midnight · Noir, and *ended* as the idle view.
- **Admin**: the Chromecast card's new step and its registered-state line; a speaker row in Users & devices; the
  Dashboard's Services group (from the dashboard brief) with *Chromecast · speakers reachable* / *not registered
  for speakers*.
- **Strings**: `cast.speaker` · `cast.group` · `cast.busy_with` (*Busy · {app}*) · `cast.play_on_phone` ×
  en/da/fo; the rest are R245's and R322's.

## 8. Round-1 questions (directions wanted, owner picks)

| # | Question | Lean |
|---|---|---|
| Q1 | Does starting a cast to a speaker from an album page **replace** the queue with that album (R322's rule) or **add** to what the speaker is playing | **Replace**, as on the phone; *Add to queue* stays the way to append |
| Q2 | Swipe-down on the mini bar while casting: stop the speaker, or only dismiss the bar and leave it playing | **Stop the speaker** — a bar the viewer swiped away must not leave a room playing |
| Q3 | Where the volume lives on the remote: the phone's keys only, or also a slider on Now playing | **Keys + a slider in the ⋯ menu**, since audio devices expose full device volume and small steps are required |
| Q4 | Does a speaker count against the session ceiling | **Only while transcoding** (WMA); a direct play is a file download |
| Q5 | Speaker groups: draw and support in round 1, or later | **Round 1**: a group is one more route; the receiver runs on the group leader |
| Q6 | Should the backend verify the audio-only registration itself with the same availability query used in §1 (it is one message, but over the Cast protocol's TLS on port 8009, which the Kotlin/Native backend cannot open without a helper process) | **No** — say *only a real cast confirms it*, 218 FR-218-7's honesty; revisit with road C, which needs that helper anyway |
| Q7 | The iPhone: say nothing (the sheet simply has no speakers), or a footnote *Speakers need the Android app for now* | **A footnote once**, in the sheet, the way AirPlay's footnote is a footnote |
| Q8 | Music on a TV or the hub: the Now playing screen only, or also the TV's own transport on the remote control | **Screen only**; the phone is the remote (R245 FR-R245-15) |
| Q9 | Lyrics on a display receiver (hub, TV) | **Not in round 1**; the phone has them |

## 9. Two things to test before the phase is committed to

1. **Tick the box, wait, restart Stue, cast one MP3** with the receiver in a headless mode that renders nothing.
   Confirms the registration path, that a Nest Wifi point runs a custom receiver at all, and the 2 MB buffer.
2. **Close the app fully mid-album, wait one song, reopen.** The speaker must be on the next song and the phone
   must show it. That is the owner's sentence, as an acceptance test.

## Sources

- Household LAN, 2026-09-28: mDNS `_googlecast._tcp` (five devices, model and capability flags) and one
  read-only `GET_APP_AVAILABILITY` per device over the Cast protocol (pychromecast in a scratchpad venv).
- Google Cast: *Audio Devices* (developers.google.com/cast/docs/audio), *Registration* (…/registration),
  *Supported Media* (…/media), *Web Receiver Queueing* (…/web_receiver/queueing), *PlayerManager* reference
  (`setMediaPlaybackInfoHandler`), *Integrate Cast into your Android app* (session resumption, the reconnection
  service, `CastOptions.Builder`).
- This codebase: `CastSenderAndroid.kt` (the options and their reasons), `ravilo-cast/Receiver.kt`,
  `tv/CastService.kt`, R245 FR-R245-4/5/13/15, R265, R322 FR-R322-8/10, 279 FR-279-6.
