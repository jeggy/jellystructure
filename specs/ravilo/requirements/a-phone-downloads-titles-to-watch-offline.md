# A phone downloads titles to watch offline

Task: DEB-3995

## Goal

The owner wants to watch on the Android phone in flight mode, *"clearly see what's available for
offline and a nice way to clean it up again, so we can be in control of how much space the app is
using"*, and download in *"top notch quality"*. This spec lets a viewer download a film or an episode to
the phone, choosing the quality with its size shown first, play it with no connection, and manage the
space it takes from one Downloads page.

It is **Android phone only**. The TV, Tizen, the Chromecast receiver, the web and the desktops get no
download surface. The layout family decides it: a TV-family Android device never shows a download control.

## Owner decisions (2026-11-09, DEB-3995 card `cebb935b`)

1. **Quality: the viewer chooses each time.** *"So download would say how much space it is and you can
   choose to download a bit lower quality if wanted."* The original file is always offered, with smaller
   copies beneath it, and each choice shows its size (FR-3).
2. **Who may download: everyone,** unless the operator switches a user off with the server's existing
   per-user *allow media downloading* setting (FR-2).
3. **Tidying is by hand:** swipe to delete, Delete watched, Delete all. Nothing deletes itself (FR-10).
4. **Sign-out keeps the downloads,** hidden from other profiles, until they are deleted (FR-12).

## Constitution reading (Tróndur, DEB-3997, 2026-11-08)

Ravilo invariant 4 reads *"no client-invented rows, no derived catalog state."* A phone's own downloads,
and the saved metadata that labels them, are **device state, not catalog state**, the same reading R212
took for its home snapshot. There is no constitution edit. The reading holds only within the bounds that
FR-13 to FR-15 carry as requirements:
- saved copies are verbatim and give way to the server;
- the phone invents no rows;
- offline progress is a queued write;
- the data is keyed per viewer;
- the download settings fall under the constitution's per-user config clause *"The device keeps only a
  cache + truly device-local prefs"*.

Invariant 1 holds: the original's bytes come straight from Jellyfin and a smaller copy from our own
encoder, as for a play. Invariant 12 holds: no viewer string names Jellyfin.

## Current state (verified in code, 2026-11-08)

- **There is no download feature.** There is no `DownloadManager`, `DownloadService` or persistent media
  store. The only `SimpleCache`s are two evictable 64 MB caches in `cacheDir`
  (`NextPrefetchAndroid.kt:44`, `WarmedSegmentsAndroid.kt:37`). Media3 1.8.0's `media3-exoplayer`
  already ships `DownloadManager`, `DownloadService` and `HlsDownloader`, so no new dependency is needed.
- **The original file is already a URL.** A direct play's ticket URL is Jellyfin's
  `/Videos/{id}/stream?Static=true&MediaSourceId=…` with a token in the query
  (`PlaybackService.kt:2583-2590`). It is the whole file and honours `Range`. Tickets live 4 h
  (`PlaybackService.kt:47`).
- **The encoder can produce smaller copies.** It serves a whole-file VOD HLS playlist in 2 s segments
  (`TvRoutes.kt:1545-1590`) on a 4-rung ladder (`EncoderPlan.kt`: HEVC tops 2160p 20 / 1080p 8 /
  720p 4 Mbps; H.264 1080p 12 / 720p 6 Mbps). The stream belongs to a play, and the play's stop ends it
  (`PlaybackService.kt:587`).
- **Subtitles:** text tracks are sidecar WebVTT the app fetches itself (`AppFedSubtitles.kt`). Image
  subtitles are read from the MKV on a direct play and burned in on a transcode.
- **Progress is fire-and-forget.** The heartbeat failures are dropped. The stop is retried 3 times and
  `/tv/mark` once (`PlayerStore.kt:709-719,1250`). There is no outbox.
- **Offline launch:** the first screen comes from local tokens, so nobody is signed out
  (`RaviloApp.kt:775-787`). Home shows R212's snapshot or, after 10 tries, "Couldn't reach the server"
  (`HomeScreen.kt:906-935`).
- **Bottom bar:** the phone's five slots are full (Home · Library · Search · Discover · Profile;
  `RaviloBottomNav.kt:59-71`). Profile's Account card (`ProfileScreen.kt:239-267`) is where Settings and
  Support already sit.
- **User policy:** `JellyfinPolicy` (`auth/Models.kt:62`) does not read `EnableContentDownloading` today.
- **Manifest:** there is no `FOREGROUND_SERVICE_DATA_SYNC`. targetSdk is 36, minSdk 24.

## Requirements

### Who and what

**FR-1 · Phone only, video only.** A Download control appears on the phone layout only, for a film and
for an episode. It does not appear for live TV, trailers, music, audiobooks or a series as a whole (a
season has its own action, FR-5).

**FR-2 · The operator's per-user switch decides.** `JellyfinPolicy` reads `EnableContentDownloading`,
defaulting to `true` when absent. The config the phone receives for its signed-in viewer gains
`@SerialName("downloads") downloads: Boolean = false`. A server with FR-4's routes sends the viewer's
policy value. An older server never sends the field, so the control stays hidden. The download routes
answer `403` for a viewer who is switched off. On that 403 the phone hides the control and shows
`download.not_allowed` ("Downloading isn't switched on for your account."). Downloads already on the phone
keep playing.

### Choosing and starting

**FR-3 · The choice shows its size before anything starts.** Tapping Download opens a sheet. It lists:
- **Original**: the file itself, with its exact size;
- the smaller copies FR-4 offers, each with its resolution and an estimated size marked "about";
- the phone's free space.

The last quality picked for a title of the same kind is preselected, so Download, then confirm, is two
taps. A choice larger than the free space minus 1 GB is disabled, with `download.no_room` ("Not enough
space — free {size} or delete a download"). A choice the phone cannot decode (FR-4) is not listed.

**FR-4 · The server says what can be downloaded.** Two additive routes, both under the device token:
- **`GET /api/tv/download/options?item_id=&media_source_id=`** answers the choices: `quality`
  (`original` | `high` | `medium` | `small`), `height`, `bytes`, and `exact` (true only for `original`).
  - `original` is listed unless the phone's declared decoders (`ClientCapabilities`) cannot play the
    file's video.
  - `high`, `medium` and `small` are our encoder's 1080p, 720p and 480p rungs for this phone's codec
    family. Each is listed only when it is clearly smaller than the original.
  - An estimate is (video + audio bitrate) × runtime.
- **`POST /api/tv/download/start {item_id, media_source_id, quality}`** answers a download ticket:
  - `kind`: `file` for the original, or `hls` for a copy;
  - the URL;
  - `bytes`;
  - every text subtitle track as a VTT URL, with its language and label;
  - the artwork URLs the phone shows offline (poster, backdrop, and an episode's still);
  - `item`: the same detail payload the server sends for that title's page, verbatim.

A ticket's URL may expire. A paused download asks `start` again for a fresh one and resumes where it
stopped, with no second ticket counting as a play.

**FR-5 · A season downloads as its episodes.** A season's page offers **Download season**. It opens the
same sheet, with the season's total size per quality. It queues each episode not already on the phone, in
episode order, at the chosen quality.

**FR-6 · A smaller copy never slows anyone watching.** A `hls` download is a *download stream*, not a
play:
- it does not appear in *Playing now*;
- it writes no QoE or session rows;
- the server runs at most **one** download stream at a time, across every viewer;
- the server does not start one, and stops one already running, when a play needs the encoder, the GPU
  or the source disk.

A stopped or waiting download shows `download.waiting_server` ("Waiting for the server") and resumes on
its own. An original download reads the file the same way a direct play does, so it needs no rule here.

### On the phone

**FR-7 · Downloads run in the background, one at a time, on Wi-Fi by default.**
- **Engine:** Media3's `DownloadService`, as a `dataSync` foreground service with a progress
  notification. `POST_NOTIFICATIONS` is asked for at the first download.
- **Queue:** downloads run one at a time, in the order they were asked for. Each can be paused, resumed
  and cancelled from the Downloads page.
- **Wi-Fi only:** on by default. With the setting on and no unmetered network, the queue waits with
  `download.waiting_wifi` ("Waiting for Wi-Fi").
- **Long downloads:** Android's daily time limit for `dataSync` services pauses a download, and it
  resumes at the next start.
- **Storage:** the files live under `filesDir`, never `cacheDir`, so the system never evicts them.
- **Saved with each title:** its subtitles, its artwork and the ticket's `item` payload, beside the
  media.

**FR-8 · Every place a title appears says whether it is on the phone.**
- **Download control:** on the film and episode pages, the control has five states: *Download* ·
  *Queued* · *n %* · *Downloaded* · *Failed — Retry*. Tapping a *Downloaded* control offers Play and
  Delete.
- **Cards and episode rows:** a title fully on the phone carries the downloaded mark. The mark says
  only what is on this disk and adds nothing to the catalog.
- **Profile:** the Account card gains **Downloads**, showing the total it takes, and it opens the
  Downloads page.

There is no Downloads row on Home or in any feed (FR-14).

**FR-9 · The Downloads page.**
- **Storage bar:** three parts, Ravilo's downloads · other apps · free, each labelled in GB.
- **Content:** films first, then series. Each series is grouped with its seasons and episodes, and every
  title and series total shows its size.
- **Each entry shows:** its quality (`Original`, `1080p`, `720p`, `480p`), and progress when watched.
- **While a download runs:** the entry shows its progress and pause/cancel.
- **Empty:** the page says how to download, in `download.empty`.
- **Offline:** a quiet `download.offline` line ("You're offline — these play without a connection")
  sits at the top.

**FR-10 · Deleting is by hand, and quick.** Nothing deletes itself:
- swipe an entry to delete it, or long-press to select several;
- **Delete watched** removes every download marked watched;
- **Delete all** removes everything for this profile, behind a confirm that says how much space comes
  back.

A series' header deletes the whole series. Every delete frees the space at once, and the storage bar
updates.

**FR-11 · Offline, the app opens to what it can play.**
- **Unreachable launch with downloads:** when the server cannot be reached at launch and the viewer has
  downloads, the app opens on the Downloads page with FR-9's offline line, not on Home's unreachable
  state.
- **Without downloads:** today's behaviour, unchanged.
- **From Home:** Home's unreachable state gains a **Watch your downloads** action whenever the viewer
  has any.
- **While offline:** a tab that needs the server shows its usual unreachable state.
- **Reconnecting:** when the server comes back, the app does not move the viewer. Home and the other
  tabs refresh as they do today.

**FR-12 · Downloads belong to a viewer, and outlast a sign-out.**
- **Per viewer:** saved media, metadata and outbox entries are keyed by user id. Another profile on the
  phone never sees them: not on the page, not as marks, not in the total.
- **Sign-out:** signing out keeps them. Signing back in as the same user brings them back.
- **Other profiles' space:** so the space stays under control, the Downloads page storage bar shows a
  line *Other profiles on this phone · {size}* with **Delete**. That deletes them without showing their
  titles.
- **Rejected session:** a `401` on the first contact after an offline spell sends that profile to
  sign-in, as today, and its downloads are kept exactly as at sign-out. Until the phone reconnects, a
  revoked session keeps playing what it already holds. That is a known, accepted consequence.

### Playing offline and the server's truth

**FR-13 · Saved copies are verbatim, and the server wins.**
- **Verbatim:** each saved `item` is the exact payload the server sent. It is never merged, edited or
  inferred from.
- **Server wins:** once the server answers, every screen shows the server's data. The only exception is
  the Downloads page's own list of what is on the disk.
- **Refresh on reconnect:** each download's `item` is refreshed from the server. A title the server no
  longer offers this viewer (404 or 403) keeps playing until deleted. Its entry is marked
  `download.gone` ("No longer in the library").

**FR-14 · The phone invents no rows.** Downloads is its own page. A "Downloaded" row on Home or in a
channel feed would be a server-composed row and a separate ask. It is not built here.

**FR-15 · Offline progress is a queued write, not local truth.**
- **Outbox:** a play from a download records progress and its finish (the same watched rule as online:
  past 90 % or past the credits marker, `PlayerStore.kt:716`) in a persistent per-user outbox.
- **Sending:** when the server is reachable again, the outbox sends through the existing
  `/api/tv/playback/progress` and `/api/tv/mark` routes, oldest first. Ravilo never writes Jellyfin
  user-data itself.
- **Conflicts:** if the server already holds a later play of that title from another device, the
  server's value stands and the queued one is dropped.
- **Offline resume:** a resume position recorded offline shows only on the downloaded copy while
  offline. Online, the screen shows the server's value.

**FR-16 · An offline play is the same player.** A download plays in the normal phone player:
- **Original:** every audio track and every subtitle track the original carries, plus the saved text
  subtitles.
- **Copy:** the audio Ravilo would have picked to play it, plus the saved text subtitles.
- **Remembered choices:** remembered audio and subtitle choices apply.
- **Next episode:** the next-episode offer appears only when the next episode is also on the phone.
- **Absent online features:** anything that needs the server (casting, *Play on*, restream, Play next
  from the server) is absent while offline, never broken.

**FR-17 · The download settings stay on the phone.** **Wi-Fi only** sits in Settings under a
**Downloads** heading. It is the setting the constitution calls *"truly device-local"*: it depends on
this phone's network, not on the viewer. It is not synced, and it is not part of the viewer's server
config. The last-picked quality (FR-3) is device-local for the same reason.

### Strings

New keys, in every Ravilo language:
- `download.action`, `download.queued`, `download.downloaded`, `download.failed_retry`;
- `download.sheet_title`, `download.original`, `download.about_size`, `download.free_space`,
  `download.no_room`, `download.not_allowed`;
- `download.waiting_wifi`, `download.waiting_server`;
- `download.page_title`, `download.empty`, `download.offline`, `download.gone`;
- `download.delete`, `download.delete_watched`, `download.delete_all`, `download.delete_all_confirm`,
  `download.other_profiles`;
- `download.season`, `download.watch_downloads`, `settings.downloads`, `settings.wifi_only`.

None names Jellyfin.

## Design round first

The download control's states, the quality sheet, the Downloads page (empty, downloading, full,
offline, selecting), the storage bar, the delete confirms, the Profile row, the downloaded mark on a card
and an episode row, and Home's unreachable state with *Watch your downloads* are drawn in a phone design
round before the client is built. That round settles their geometry. The behaviour above is fixed now.

## Wire

Additive only:
- two routes (FR-4);
- one optional config field (FR-2);
- one optional policy field read from Jellyfin.

An old app never calls the routes and ignores the field. An old server hides the control. No field is
changed or removed.

## Non-goals

- Automatic tidying (Netflix-style *delete watched, fetch the next*): the owner chose by hand.
- Downloads on any surface other than the Android phone.
- Saving to an SD card or a folder other apps can open. Downloads live in Ravilo's own storage only.
- Music and audiobook downloads.
- An expiry date on downloads.
- Offline search or browsing beyond the Downloads page.

## Acceptance

1. On the phone, a film's Download opens the sheet with Original at its exact size and at least one
   smaller copy marked "about". The free space is shown, and a choice too big for the phone is disabled.
2. A film downloaded at Original and an episode downloaded at 720p both play in flight mode, with the
   film's every audio track and both titles' subtitles. The app launched in flight mode opens on the
   Downloads page.
3. Watching the episode to the end offline, then reconnecting, marks it watched on the server. Another
   device shows it watched.
4. While a 720p download stream runs, starting a play on the TV stops the download stream, which shows
   "Waiting for the server". The TV's play shows no added start delay, and the download later finishes
   on its own.
5. The storage bar shows Ravilo's total. Delete watched and Delete all free that space at once, which
   the bar and Android's own storage settings both show.
6. Signing out and back in as the same viewer keeps the downloads. A second profile on the phone sees
   none of them and can delete them only as *Other profiles on this phone*.
7. A viewer switched off by the operator sees no Download control, and the routes answer `403`.
8. A TV, the web and the desktop show no download control.
