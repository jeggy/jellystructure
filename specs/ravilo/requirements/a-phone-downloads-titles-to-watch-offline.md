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

## Amendment 2026-11-09: the design round is adopted

The round `design/incoming/2026-11-09-phone-downloads/` (commit `78228352`) settles the geometry that
"Design round first" above left open. Its `Phone Downloads - Directions.html` is the reference the
Compose port is built from: exact sizes, colours and the three-language string table. The owner picked
from the pictures on card `7c8bec51`. The changes are below, and every FR not named here stands as
written.

**FR-8, amended: the control sits beside Play** (owner: *beside Play*). On the film and episode pages
the action row is **Play · Download · My List**:
- My List becomes a 44 dp icon-only button. Its label stays as the accessibility description.
- **Play must not move.** The round showed that a control which resizes with its state squeezes Play to
  about 82 dp in Danish. So the Download control has **one fixed width per language**, taken from the
  widest of its short labels, and Play takes the rest of the row. Neither changes width while a download
  runs.
- The short labels are *Download*, *Queued*, *n %* (the progress fills the control), *Downloaded*
  (green, as drawn), and, for a failure, a warning glyph with `download.retry` ("Retry"). Tapping *Retry*
  restarts the download. The reason it failed shows in a short message, never inside the control.
- The *Downloaded* control's Play / Delete choice is as drawn in `b-film-downloaded-menu`.

**FR-8, amended: the mark** (owner: *"White chip bottom left. But only shown when it has been downloaded
and for non downloaded things it should show nothing on the poster, like now"*).
- The mark is a **white chip** (`#f3f4fb`, `#0a0c13` arrow-into-a-line glyph 12 × 12, stroke 2.4):
  - on a poster: **20 × 20 dp, radius 7, inset 7 dp from the bottom-left corner**;
  - on a 16:9 episode thumb: **18 × 18 dp, radius 6, inset 5 dp**.
- It appears **only on a title that is fully on this phone**. A title that is not downloaded, or is still
  queued, downloading or failed, shows **nothing new on its card**: posters look exactly as today.
- The mark clears the Continue progress bar by 4 dp and leaves the quality badge (top-right) and the
  watched tick (bottom-right) untouched. An episode that is both downloaded and watched shows both.

**FR-10, amended: every swipe confirms** (owner: *a confirm on every swipe*). A swipe or a single
*Delete* opens a confirm naming the title and what it frees: `download.delete_one_confirm` ("Delete
{title}? This frees {size}."), with Delete and Cancel. Cancel puts the entry back exactly as it was.
Multi-select's *Delete · {size}*, a series header's delete, Delete watched and Delete all confirm the
same way, each naming its size. Delete watched and Delete all live in the page's ⋮ menu, each showing
the space it frees (*Delete watched · 7.5 GB*), beside **Select**.

**FR-9, amended: a Downloading block at the top** (owner: *a Downloading block at the top of the
page*).
- **The block:** above Films sits a **Downloading** block (`download.downloading`). It holds every entry
  that is running, queued, waiting for Wi-Fi, waiting for the server, paused or failed, in queue order.
  Each entry shows its progress or its waiting line, and pause/resume and cancel. The block disappears
  when it is empty.
- **The lists below:** Films and Series list only titles that are fully on the phone. An entry moves
  from the block into its list when it finishes.
- **The storage bar:** one bar with an inline legend (Ravilo · Other apps · Free). Its *Ravilo* segment
  is everything Ravilo holds on the phone, **including other profiles'**, because that is the space the
  app uses. FR-12's *Other profiles on this phone · {size}* row sits directly beneath it. The title bar,
  Profile's Downloads row and Delete all name **this profile's** total.

**FR-5, as drawn.** A season sheet's rows show the season's total per quality. Its button names what
pressing it downloads, counting only episodes not yet on the phone. The `download.season_have` line
between them says how many are already there.

**Strings, amended.**
- `download.sheet_title` reads "Choose a quality".
- `download.empty` is the round's invitation, naming the flight-mode case.
- `settings.wifi_only` gains the sub-line `settings.wifi_only_sub`.
- The round adds, in its table: `download.empty_title`, `download.delete_all_title`,
  `download.space_title`, `download.seg_ravilo`, `download.seg_other`, `download.seg_free`,
  `download.select`, `download.n_selected`, `download.sheet_button`, `download.season_have`,
  `download.loading`, `download.pause`, `download.resume`.
- This amendment adds `download.retry`, `download.delete_one_confirm` and `download.downloading`, to be
  translated alongside the others.
- None names Jellyfin.

**Acceptance, added.**
- **9.** In Danish and Faroese, Play keeps the same width through all five download states.
- **10.** A poster for a title that is not downloaded looks exactly as it does today.
- **11.** A swipe on an entry asks before deleting, and Cancel restores it.
- **12.** A running download shows at the top of the Downloads page.

## Amendment 2026-11-09: slice 1's wire shapes (DEB-4061)

Slice 1 builds FR-2 and FR-4's `original`. FR-4 left the JSON shapes open; these are what the server sends, in
`:shared` `DownloadModels.kt`. Nothing here changes what FR-2 or FR-4 promise.

- **`GET /api/tv/download/options?item_id=&media_source_id=`** answers `{"options":[{quality, height, bytes, exact}]}`.
  - Slice 1 lists `original` only, with Jellyfin's exact `Size`. A file whose size Jellyfin does not give is not offered.
  - The phone's decoders come from an optional `capabilities` query parameter (the JSON of `ClientCapabilities`). Absent, the
    server uses what the device declared at its last play. A phone that has declared no video decoders is not refused.
- **`POST /api/tv/download/start`** takes `{item_id, media_source_id?, quality, capabilities?}` and answers a `DownloadTicket`:
  `kind` (`file`), `quality`, `url`, `bytes`, `expires_at`, `media_source_id`, `subtitles[]` (`index`, `language`, `label`,
  `forced`, `is_default`, `url`), `artwork` (`poster_url`, `backdrop_url`, `still_url`), `item_kind`, `series_id` and `item`.
  - `item` is the page route's own body, kept as raw JSON. For a film (`item_kind: "movie"`) it is `GET /api/tv/movie/{id}`'s;
    for an episode (`"episode"`) it is `GET /api/tv/series/{series_id}`'s, the page that lists the episode.
  - Every `start` carries a per-ticket value in the URL's query, so a second call for the same item is a different URL.
- **Status codes:** `403` for a viewer the operator switched off (FR-2); `404` for a title that is not there, not visible to
  this device, or a series; `400` for a version, quality or codec the server does not offer; `503` when Jellyfin cannot be asked.
- **The switch is read live** from the viewer's Jellyfin account and kept for a minute, so a change reaches the routes within
  a minute and the phone's config on its next fetch.

## Amendment 2026-11-09: slice 2's smaller copies and the download stream (DEB-4062)

Slice 2 builds FR-4's `high` / `medium` / `small` and FR-6. FR-4 and FR-6 left the wire open; nothing here changes what
they promise.

- **Options.** `GET /api/tv/download/options` also lists `high` (1080p), `medium` (720p) and `small` (480p): our encoder's rung of
  that picture box for the phone's codec family (the HEVC or H.264 rule a play uses), at the video bitrate that rung gets (capped
  by the file's own and by the phone's declared decode ceiling).
  - A copy is listed only when the encoder can serve this file (the encoder is on, ffmpeg and a GPU are there, the codec is one the
    GPU decodes) **and** the file is at least that tall (a 720p file offers no `high`) **and** its estimate is under 80 % of the
    original's size (the ladder's "clearly lower" rule). A file whose size Jellyfin does not give offers no copy.
  - `bytes` is (video + audio bitrate) × runtime, `exact: false`; `height` is the rung's. Options never wait on, or hide because of,
    what is playing now.
- **Start.** `POST /api/tv/download/start` with `quality` `high|medium|small` answers `kind: "hls"`, `url` the server-relative
  `/api/tv/stream/dl-<id>/master.m3u8` (public; the id is the capability, as a play's), `bytes` the estimate, and the same
  `subtitles`, `artwork`, `item` as slice 1. The master lists the one rung and the one audio the phone's play would pick (the
  remembered `audio_language` / `audio_variant` the request may carry, else the file's default). The request gains the optional
  `from_segment` (the 2 s segment the phone reached; absent = 0) so a resumed download's first job starts there, not at 0:00.
- **The download stream (FR-6).** Its id starts `dl-`. It is an encoder stream with no play behind it: it is in no play session's
  list, so no play's stop ends it, and it has no tracker entry, no QoE row, no encoder summary and no server-fault note.
  - **One at a time, server-wide.** A `start` from the device that holds it replaces it (a resume, or the next in the phone's
    queue). A `start` from another device answers `503`.
  - **Yields to every play.** A play's start (a film or an episode, direct or encoded) ends the download stream before it plans
    anything, and the process is killed at once, with nothing awaited, so the play's start does not wait for it. While a play is
    live (or within the tracker's 120 s grace after it) a `start` answers `503` and no download job runs.
  - **Waiting.** A `503` with `Retry-After: 5` and `X-Download-State: waiting` is the whole "Waiting for the server" answer: `start`
    when it cannot begin, and every playlist, init and segment request of a `dl-` stream that is not running (preempted, reaped,
    the server restarted). It is never a `404` and never a `410`. The phone shows `download.waiting_server` and asks `start` again
    with `from_segment`.
  - **Reaped.** A download stream with no segment fetched for 10 minutes ends and its files are removed.
- **Not built here.** Subtitles in the HLS (they are VTT files, as slice 1), a copy of an image-subtitle track, and any phone code.

## Amendment 2026-11-10: slice 4's page, deletes and mark (DEB-4071)

Slice 4 builds FR-8's mark and Profile row, FR-9, FR-10 and FR-12's other-profiles line. Nothing above changes what it promises; the
points below settle what the round's pictures and the code left open.

- **The mark and the picture badge.** The round drew the quality badge top-right. The shipped tile draws it **bottom-start**
  (R325, FR-R325-3), the corner the mark owns. So on a title that is both downloaded and badged, the badge **stands on the mark**,
  4 dp clear of it, and a title that is not downloaded keeps the badge exactly where it was. On an episode card the mark stands on
  the *UP NEXT* or *NEW* ribbon when one owns that corner. It never covers the watched tick (bottom-end).
- **Where the mark shows.** On every poster tile and episode card of an Android phone whose config says `downloads`: Home, channels,
  Library, Search, My List, a title's related row and a season's episodes. A *series* card never carries it: a series is not a
  download, an episode is. Nowhere on a TV, the web or the desktops.
- **Watched on the page.** The page's *Watched* and *Delete watched* follow what the server last said, read from the pages the viewer
  opens (a film's page, a series' page); the phone writes nothing. A download whose title the viewer has not opened since it was
  saved shows no *Watched* until slice 5's reconnect refresh (FR-13) sets it. `download.gone` is likewise set by that refresh and is
  only drawn here.
- **Totals.** The title bar, Profile's *Downloads · {size}* and *Delete all* name what **this profile's** downloads hold on the disk
  (a running download's part included, as the round's 27.2 GB against 26.9 GB shows). The storage bar's *Ravilo* part adds the other
  profiles' bytes. What is neither Ravilo's nor free is *Other apps*.
- **Order.** Films are listed by title; a series by title, its seasons ascending, its episodes by number. The *Downloading* block is in
  the order the downloads were asked for.
- **Headings.** *Films* and *Series* read the shipped `nav.movies` and `nav.series` (Movies, Series; Film, Serier; Filmar, Seriur):
  the shipped copy is the source of truth over the round's `row.films`. A series line reads `detail.season`, `detail.episode` and
  `detail.episodes`. No new string.
- **Confirms.** A single delete and a series header read `download.delete_one_confirm`. Multi-select reads `download.n_selected` with
  `download.delete_all_confirm`; *Delete watched* reads its own label with the same sentence; *Delete all* reads
  `download.delete_all_title`; *Other profiles* reads `download.other_profiles` with the sentence. Cancel changes nothing, the
  selection included. A running download's *cancel* is not a delete of a title on the phone and does not confirm.
- **Offline.** The page draws `download.offline` when it is told it is offline. Telling it so, and opening on it, is FR-11 and slice 5's.
- **Artwork.** The page shows the server's poster while online and a title's colour and initials otherwise; the saved artwork reads
  offline in slice 5 (FR-13, FR-16).

## Amendment 2026-11-10: slice 5's offline launch, play, outbox and refresh (DEB-4072)

Slice 5 builds FR-11, FR-12's 401, FR-13's refresh, FR-15 and FR-16. Nothing above changes what it promises; the points below settle what the
FRs left open.

- **"Unreachable" and the launch (FR-11).** The server is unreachable when a call gets no answer at all, or a gateway answer (502 to 504). Any
  other status is an answer, so the server is there. The launch's first config read decides, **once per launch**, and only while the viewer is
  still on the launch Home: a viewer who already moved on stays, and a server that comes back later moves nobody. Home draws first (its snapshot
  or loading shell) and the page replaces it when the read fails; the app does not wait for the read before drawing. Offline, the Downloads page and
  its play do not wait on the config's `downloads` flag, because that flag needs the server.
- **Reconnect (FR-12, FR-13, FR-15).** Offline, the app asks the server every 8 s whether it is back; once per launch it also runs the same steps at
  start. The steps, in this order: **send the outbox**, then **refresh every download**. A 401 on either sends the profile to sign-in with its
  downloads and outbox kept; the server going away again part-way goes back to asking and loses nothing.
- **The conflict rule (FR-15).** The wire has no per-play timestamp, so "the server already holds a later play" is read from the play state it
  already sends: the resume position and the watched flag (`/api/tv/playstate`). Each download keeps the pair the server last gave (read when the
  download starts and at every refresh), and the outbox copies it from the title's **first** entry. At sending time, if the server's pair differs
  from that one, the title was played elsewhere meanwhile and **all its queued entries are dropped**. A title the server holds no user data for reads
  as never played, not as gone. The read is strict: a failed chunk stops the drain instead of being skipped, because a missing answer would otherwise
  read as "never played".
- **What the outbox sends (FR-15).** A title keeps one progress entry (the latest position replaces the earlier) and, if it finished, one watched
  entry after it. Titles go oldest first. The position is sent through `/api/tv/playback/progress` as **paused**, so a position handed over after the
  fact never shows as *Playing now*; the finish is `/api/tv/mark`. A 403 or 404 on a title drops its entries; any other failure stops the drain.
  A position of 0 is never queued.
- **Offline resume (FR-15).** A play from a download starts at the position recorded offline, else at the server's last word. It is shown nowhere
  else, and is gone once the outbox has been sent.
- **The play (FR-16).** Opening a downloaded entry on the Downloads page plays it from local storage, online or off. Nothing reaches the server
  from that play: no start, heartbeat, stop, QoE row, restream or prefetch. The player reads the download's own cache with no network behind it.
  The cast button is absent. The next-episode offer is the next number in the same season, and only when that episode is on the phone; it is not
  offered across a season or over a gap, because offline the phone cannot know that an episode was a season's last.
- **The refresh (FR-13).** Each download's page route body (a film's `GET /api/tv/movie/{id}`, an episode's `GET /api/tv/series/{id}`) is read again
  and saved verbatim. A 404 or 403 marks the entry `download.gone`, and a title that answers again clears the mark. The server's watched flag and
  resume position replace what the phone held, so *Delete watched* follows the server.
- **Artwork.** While the server is unreachable the Downloads page draws a title's saved poster (and an episode's saved still); online it draws the
  server's.
- **Strings.** None added: `download.offline`, `download.watch_downloads` and `download.gone` already shipped with slice 4.

**Not provable in CI, so QA:** playing a downloaded original with every audio track, and a copy, in flight mode; the launch in flight mode opening on
the Downloads page; reconnecting after an offline watch marking the title watched on another device. These need the Pixel (spec Acceptance 2 and 3).
