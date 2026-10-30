# Radio plays beside the live channels and on Music Home

Task: DEB-2628

> Owner, 2026-10-27, on DEB-1076: *"Be able to stream radio channels within both Ravilo TV and Music Mode. In TV mode
> it should just be on the same row as the live channels, but with some small indicator that it's radio"* (08:16Z).
> Q3, 08:26Z: *"yes, a radio row"*, so in Music mode radio is **a Radio row on Music Home**.

This spec amends four other specs, each with a dated note in its own file:
- [R177](phase-R177-livetv-viewing.md) A, B, C and D: radio stations join the live row, the guide and the zap order.
- [Phase 147](../../requirements/phase-147-livetv-admin-config.md) B: the admin lineup marks radio stations.
- [Phase 279](../../requirements/phase-279-api-tv-music.md) FR-279-2: `GET /music/home` gains a Radio row.
- [R321](phase-R321-a-listening-mode-on-the-phone.md) FR-R321-5: Listen shows that row.

It builds on [`a-live-channel-plays-its-own-quality-ladder.md`](a-live-channel-plays-its-own-quality-ladder.md)
(DEB-2627): its `stream_kind`, its heartbeat body and its admin live rows. The constitution is left as it is.

## What is true today (verified 2026-10-27)

**The stations.** DEB-2625 (estate) adds three stations to Jellyfin's Live TV as M3U entries with `radio="true"`, which
Jellyfin lists as `ChannelType: "Radio"`. Fetched from this server:

| | Format | Scheme | CORS |
|---|---|---|---|
| Station 1 | Icecast MP3, 192 kb/s (also an HLS 192 kb/s rendition) | MP3 `http://` only, on port 443; the HLS is `https` | `*` |
| Station 2 | Icecast MP3, 192 kb/s (also AAC 128) | `http://` only | `*` |
| Station 3 | Icecast MP3, 96 kb/s | `http://` only | `*` |

None of the Icecast hosts answers TLS on any port. Station 1 has a radio guide (DEB-2625). Stations 2 and 3 have none.

**What breaks a plain `http://` audio URL:**
- **Browser:** Ravilo Web is served over `https`, and Chrome auto-upgrades mixed-content audio to `https`, which these
  hosts cannot answer.
- **Mac:** App Transport Security blocks it. The app sets only `NSAllowsLocalNetworking`, and only in an update-feed
  override build.
- **Speakers:** the Cast receiver is an `https` page, so the same mixed-content rule applies.
- **Fine:** Android (the manifest sets `usesCleartextTraffic="true"`) and Linux (mpv).

**The server:**
- `JellyfinClient.getLiveTvChannelsOrNull` reads `/LiveTv/Channels` with no `type`, so radio and TV arrive mixed
  together. `JellyfinLiveTvChannel` does not read `ChannelType`.
- `LiveTvService.sync` adds every new channel `shown = false` (Phase 147 C1). DEB-2625 leaves the stations hidden, and
  they stay hidden until this ships.
- `LiveTvService.lineup()` joins the store with `lastLiveChannels`, which is empty after a restart until the next sync.
- `tune` opens through Jellyfin and hands `MediaSource.Path` to the client. For an Icecast URL, that `Path` is the
  `http://` source or a Jellyfin-local `LiveStreamFiles` stream. Which of the two is not verified; FR-4 works for both.

**The clients:**
- **TV mode:** the *On now* row, the guide and the live player are in `ravilo-ui` commonMain. They run on Android TV,
  Mac, Linux and the browser. The phone has no live TV.
- **Music mode:** Music Home (`MusicListenScreen`) runs on the phone, Mac and Linux. The TV has *Now playing* but no
  Music Home (`RaviloApp.kt:777`). The browser has no music mode (`MusicEngineWasm.supported = false`).
  - `MusicListenScreen.ListenRow` draws a row whose key it does not know as an empty album row under the row's title.
    An installed phone sent a `radio` row today would show an empty *Radio* heading.
  - The music engine has no live item. Every seek bar assumes a duration, and every transport shows next and previous.
- **Speakers:** the speaker's custom Cast receiver (`ravilo-cast`) is handed a queue of song ids. It fetches each one
  itself, as its own enrolled device, through `POST /api/tv/music/play`, and loads it `StreamType.BUFFERED`.
- **Kinds:** `SessionKind`, `RemoteModel.kind` and `sessionKindOf` know `film · episode · music · audiobook`. All
  are plain strings.

## Requirements

### Server

**FR-1 — A radio station is marked on the wire, and the mark survives a restart.**
- `JellyfinLiveTvChannel` reads `ChannelType`.
- `LiveTvChannelOverride` gains `kind: String`, `"tv"` or `"radio"`, default `"tv"`. Every sync writes it from
  Jellyfin's `ChannelType`: `"Radio"` gives `radio`, anything else gives `tv`.
  - The kind is stored rather than read live, so `lineup()` after a restart, before the next sync, never presents a
    station as a TV channel.
- `LiveTvChannel` gains `kind: String`, default `"tv"`, filled from the override. It is a plain string, so a future
  kind cannot break a strict decode.

**FR-2 — Only a client that asks for radio is sent radio.** An installed client has no radio player: it would open a
station in the video player, or as an empty row. So:
- `GET /tv/livetv/channels`, `GET /tv/livetv/guide` and `GET /tv/music/home` gain a query parameter `radio`.
- **Without `radio=1`:** they answer exactly as today. No `kind = "radio"` channel, none of its programmes, and no
  Radio row.
- **With `radio=1`:** stations are included under the same rules as TV channels:
  - `shown`, not `unavailable`;
  - in the viewer's visible set (DEB-2520).

  Every client built from this spec sends `radio=1`.
- `GET /tv/livetv/channels` also accepts `kind=radio` or `kind=tv`, so Music mode can ask for stations alone.
- The admin's own endpoints always see every channel. The admin is the same build as the backend.

**Amendment 2026-10-30 (DEB-3067), Tróndur's decision:** *hide* and *kind* decide which lists a station appears in. They
do not refuse a tune or end one already playing, and the tune's gate stays the viewer's own Jellyfin access
(`visibleChannelIds`), so an operator tidying the lineup never cuts someone off mid-programme.

**FR-3 — *Shown* works as for any channel.**
- A station arrives hidden and badged *new* (Phase 147 C1). The operator shows it with the existing *show in Ravilo*
  toggle, and nothing else switches it on.
- **The three stations stay hidden until the release that carries this spec is on prod.** The operator switches them on
  in the admin, and that is the switch-on DEB-2625 deferred. No build slice and no migration flips `shown`.
- Number and order share the one lineup with the TV channels. A station is numbered and placed like any channel, so the
  live row, the guide and zapping all follow the operator's order.

**FR-4 — Every station plays through jellystructure's audio relay.** A station's tune goes through Jellyfin as today
(`PlaybackInfo`, then `LiveStreams/Open`). The backend then classifies the opened `Path` with DEB-2627 FR-1, and:

1. **`hls_ladder` or `hls` on `https`:** handed over as-is. It is played as HLS, like a live channel.
2. **Anything else** (progressive audio, an `http` URL or a Jellyfin-local stream): the ticket carries a relay URL
   instead.
   - `hls_url` is the path `/api/tv/livetv/radio/{relay_id}`. A radio-aware client resolves it against its own server
     base, and only when `stream_kind` is `"audio"`.
   - `relay_id` is a 128-bit random value minted per tune.
3. **`LiveTvStreamTicket` gains** (additive):
   - the `stream_kind` value `"audio"`;
   - `content_type: String?`, the upstream's audio type (`audio/mpeg`, `audio/aac`, …), filled for `"audio"`;
   - `channel_kind: String`, default `"tv"`.

`GET /api/tv/livetv/radio/{relay_id}`:
- **Auth:** neither `<audio>` nor the receiver's media element can send a device header, so the route is listed in
  `AuthPlugin` beside the logo proxy, with the `relay_id` as its credential.
  - This is the constitution's *"routes fenced to a credential of their own"* (Auth, 4).
  - The `relay_id` is drawn from the platform's secure random source, as the session token is. It is minted only for a
    device-token-authenticated tune, compared in constant time, and never written to a log line. The access log keeps
    only the path up to `/radio/`.
- **Lifetime:** valid while its tune is active. A stop, the watchdog or a re-tune ends it, and then it answers 404.
- **Upstream:** opens `Path` from the server, made absolute against the Jellyfin base when it is relative.
  - It never sends `Icy-MetaData: 1`, so no in-band metadata is mixed into the audio.
  - It connects within 10 s and follows at most 3 redirects.
- **Response:**
  - passes the upstream bytes through as they arrive, with the upstream `Content-Type` and
    `Cache-Control: no-store`;
  - sends no `Content-Length` and `Access-Control-Allow-Origin: *`;
  - an upstream type that is not `audio/*` or `application/ogg` is a 502.
- **Limits:**
  - at most 2 open connections per `relay_id`, so a reconnect can overlap the old one;
  - at most 32 relayed connections server-wide;
  - over either limit is a 503.
- **No fan-out:** each connection opens its own upstream. At 192 kb/s per listener, that is the whole cost.
- **The Ravilo apps' radio always goes through the relay**, even on Android and Linux, which could play the `http`
  source. There is then one path to test, every listener shows on the server (FR-5), and the source never sees a
  listener's address.

**FR-5 — A radio listener is a live viewer.** DEB-2627 FR-5 applies to a station as written:
- the heartbeat body, the re-register after a restart, and `isPlaybackActive()`;
- the admin's live row, which reads *Radio*, the station, the programme on now (when there is one), the format
  (*MP3 · 192 kb/s*), and *relayed*.

Two additions:
- **An open relay connection keeps its tune alive.** The watchdog never closes a tune that has a relay connection open,
  so a phone that sleeps while a speaker plays does not cut the speaker off. It closes the tune 60 s after the last
  connection ends with no heartbeat.
- **A speaker's tune is its own device's.** The receiver tunes as its own enrolled device (FR-10). The admin row names
  the speaker, and the one-stream-per-device rule never makes a phone's local tune and a speaker's tune replace each
  other.

Not added here: a radio session in `session_list`, `/api/tv/playback/sessions` or R368's `playback_session`, the
same as DEB-2627 FR-5.4.

### TV mode (Android TV, Mac, Linux, browser)

**FR-6 — Stations sit in the live row with a radio mark.**
- The *On now* row shows stations among the TV channels, in lineup order. Each station has **a small radio mark** on its
  tile, the one difference from a channel tile.
- A station's tile shows its logo, its programme now and that programme's live progress, as a channel's does.
- **A station with no guide** keeps its name where a channel's name is, and *Live radio* alone takes the programme's
  place, with no progress bar. The name is never drawn a second time at the programme's weight (design round,
  2026-10-27: the literal reading drew it twice).
- **The mark** (owner, 2026-10-27, option A): the broadcast glyph, a dot between a pair of arcs either side. It is
  never the Wi-Fi fan. It sits in a dark plate in the tile's top-right corner, the one corner a channel tile leaves
  empty. Its accessible label is *Radio*.

**FR-7 — The guide shows stations too.**
- The R177 guide lists stations as rows in lineup order. The channel cell carries the same radio mark.
- **A station with no guide** shows one block across the whole window, reading *Live radio*.
- The category chips are unchanged. The operator can give the stations the category *Radio* (Phase 147 B1), and its
  chip then filters them.
- Selecting a station's programme tunes the station, as for a channel.

**FR-8 — Tuning a station opens the radio player, not the video player.** The radio player is R177's live player
with an **audio layout** in place of the picture. It plays the ticket through the same player seam: an `<audio>` or
`<video>` element in the browser, Media3, AVPlayer, mpv.
- **The layout:**
  - the station's logo, large;
  - the station's name and number;
  - a **LIVE** mark;
  - the programme now, with its times and live progress;
  - the next programme.
  - With no guide, it shows the name, *Live radio* and LIVE.
- **Kept from R177's player:**
  - **Zapping** (←/→, channel up/down, 0–9) runs over the **one lineup**. Zapping from a station to a TV channel
    opens the video player, and from a TV channel to a station opens the radio player. The banner and the number
    entry are R177's.
  - **↑ Now/Next** works as in R177, and lists stations with their mark.
  - **No timeshift.** Pause stops the station, and ⏯ again re-tunes it at the live edge.
- **On a TV:**
  - the screen stays on while a station plays, as in the live player;
  - after 5 minutes with no key press, the audio layout dims to 40 % brightness, and any key brings it back. The
    LIVE mark dims with it. The round showed that the burn-in step alone leaves the centre of the logo where it was;
  - the audio layout **moves its content a little every few minutes**, so nothing burns into an OLED panel. The round
    draws how far and how often.
- **Back** stops the station and returns, as from the live player. Radio does not keep playing behind the TV mode
  pages (non-goal).
- **Fallback:** DEB-2627 FR-4 applies. A relay that has given no audio within 15 s re-tunes once with
  `via = "jellyfin"`, which hands back Jellyfin's own transcoding URL as an `hls` ticket.

### Music mode (phone, Mac, Linux)

**FR-9 — A Radio row on Music Home.**
- `GET /tv/music/home?radio=1` adds a row with `key = "radio"`, a localised title *Radio*, and the viewer's stations.
  - `MusicRow` gains `stations: List<LiveTvChannel>`, default empty.
  - The row is present only when the viewer has at least one station.
  - **It is the last row, after the genres** (owner, 2026-10-27, option D), so it pushes nothing the household owns
    down the page.
- Each tile shows the station's logo, its name, and the programme now (or *Live radio*), with the radio mark.
- **Selecting a tile plays the station at once.** There is no station page.
- The phone has no live TV, so this row is where a phone reaches radio.

**FR-10 — A station plays through the music engine as a live item.** `MusicPlayback.playRadio(station)` tunes the
station (FR-4) and plays it on the local engine, or on the linked speaker (FR-11).

While a station plays:
- **The queue is gone.**
  - Starting a station stops what was playing and replaces the queue.
  - Starting any song, album or book stops the station and closes its tune.
  - *Up next* and the queue screens show nothing for a station.
- **The title is the programme now.** The station's name is the artist line, and its logo is the cover.
  - The engine re-reads the station from `/tv/livetv/channels?kind=radio&radio=1` at the programme's end, so the title
    turns over on its own.
  - With no guide, the title is the station's name and the second line reads *Live radio*.
- **No seek, no next, no previous, no shuffle, no repeat.**
  - The seek bar is replaced by a LIVE mark.
  - The transport is ⏯ alone: pause stops the station, and play re-tunes it at the live edge.
  - Every button that does not apply is left out, never greyed. A station that is stopped, or that could not play,
    shows no LIVE mark.
- **The system media controls** show the same:
  - **Android:** Media3 with a live, unseekable item (`C.TIME_UNSET`, no seek or skip commands).
  - **Mac:** `MPNowPlayingInfoPropertyIsLiveStream`.
  - **Linux:** MPRIS with `CanSeek`, `CanGoNext` and `CanGoPrevious` false.
- **The engine owns the heartbeat.** It runs every 20 s, with DEB-2627 FR-5's body, from the engine and not from a
  screen, so a locked phone keeps it going. A stop sends `POST /tv/livetv/stop`.
- **Errors:** a station that fails to start shows the engine's existing *couldn't play* state with the station's name.
  The fallback is the same as FR-8's.

**Amendment 2026-10-30 (DEB-3039): Jellyfin does not say which channel is a station, so the operator does.**
Found by DEB-3006 (QA of DEB-2658) on prod Jellyfin 12.1.0, and it makes two statements in this spec wrong:
- *What is true today* says Jellyfin lists the `radio="true"` entries as `ChannelType: "Radio"`, "verified 2026-10-27".
  It does not. `GET /LiveTv/Channels` answers `ChannelType: "TV"` and `MediaType: "Video"` for all eight channels,
  stations included. `Jellyfin.LiveTv.dll` 12.1.0 reads only `tvg-logo`, `group-title`, `tvg-id`, `channel-id`,
  `tvg-chno` and `tvg-name` from an M3U entry and has no `radio` string, so the attribute is ignored and a refresh will
  not change it.
- FR-1 rests on that premise: `kind` was written from `ChannelType` on every sync, so on that Jellyfin no station was
  ever `radio`, no client saw `kind`, and a tune carried no relay.

**FR-1, as amended.** The kind is **owned by the operator, per channel**, like *shown*, *number* and *order*:
- `LiveTvChannelOverride` gains `kind_set: Boolean`, default `false`. It is stored with the override.
- **Seeding:** a sync writes `kind` from Jellyfin's `ChannelType` (`"Radio"` gives `radio`, anything else `tv`) **only
  while `kind_set` is `false`**. A Jellyfin that does say `Radio` therefore still seeds a station, and the operator's
  Type control (below) shows it as such.
- **The operator's choice is kept:** `PUT /tv/admin/livetv/channels/{id}` accepts `kind` (`"tv"` or `"radio"`; any other
  value is a 400). Setting it stores the kind and sets `kind_set = true`. From then on no sync rewrites it, and a
  channel that leaves and rejoins Jellyfin's lineup keeps it.
- **Nothing is guessed** from a channel's name, logo, stream or M3U entry, and the backend needs no new mount.
- **Prod after this ships:** the three stations are `tv` until the operator sets them to *Radio* once. That is the same
  step as switching them on (FR-3), and the two are done together.
- Everything that reads the kind (FR-2 to FR-12) reads the stored value and is unchanged.
- **Wire:** `LiveTvChannelUpdate` gains `kind: String?` (additive, a request body). `LiveTvChannel.kind` is as before.

**FR-12, as amended.** The lineup table gains a **Type** control per row, *TV* or *Radio*, which sends `kind`. The row
still shows the radio mark beside a station's name, drawn from the same stored kind. It uses the admin's existing select
styling and adds no component class.

**Tests, as amended** (`LiveTvRadioTest`): a Jellyfin that lists every channel as `TV` leaves all four `tv`; an operator
`kind` makes a channel a station and every FR-2 rule then applies; a later sync, with Jellyfin still saying `TV`, keeps
it; a sync does not rewrite a channel whose `kind_set` is `true` even when Jellyfin says `Radio`; the kind and the flag
survive a restart; an unknown `kind` is a 400. Acceptance 2, 3 and 5 now begin with the operator setting the three
stations to *Radio*.

**Amendment 2026-10-30 (DEB-2659):** two facts the spec took as given, found while building FR-10.
- **Linux has no MPRIS surface.** MPRIS is R336's and was never built: no code in `ravilo-ui` or `ravilo-desktop` publishes
  a media player on the bus, so there is nothing for the *Linux* line of *System controls* to configure. The Linux build
  of FR-10 is the engine, the bar and the Playing page; the MPRIS clause (`CanSeek`, `CanGoNext`, `CanGoPrevious` false)
  is carried by R336 when it lands, and is a requirement on it. The Android and Mac lines are built here.
- **A speaker that is connected when a Radio tile is tapped is let go first**, as a song or album started from the phone
  would take its queue, and the station then plays on this device. Playing it *on* the speaker is FR-11's. Until FR-11
  lands, the alternative is a station that is audible on this device while the bar still shows the speaker's queue.

**Amendment 2026-10-30 (DEB-3067): the title follows the clock, and a gap between programmes keeps the guide.** Found by
DEB-3026 (QA of DEB-2659) on prod. FR-10's re-read got the same ended programme back, because the server served a stale
snapshot; [Phase 147](../../requirements/phase-147-livetv-admin-config.md)'s amendment of the same date fixes that for
every channel. It also leaves a case this spec never covered: Station 1's guide has a 5-minute gap at the start of every
hour, when Jellyfin sends no `CurrentProgram`. Today `has_guide` is `live?.currentProgram != null`, so a gap reads false,
and the engine re-reads only while it is true (`radioRefreshDelayMs`). One gap would stop the turnover for good.

- **`has_guide`, as amended (all channels):** true when Jellyfin lists a programme for the channel that is on now or
  starts within the next 24 hours. The day's reach means a station that is off air overnight still has its guide.
  - A channel with no `CurrentProgram` is checked with `GET /LiveTv/Programs` for that window. A channel with one needs
    no second call.
  - **A Jellyfin that does not answer changes nothing:** the channel keeps the `has_guide` it had. So
    `getLiveTvPrograms`, which answers an empty list on failure today, must let the caller tell *none* from *could not
    ask*, as `getLiveTvChannelsOrNull` does.
  - A station whose guide has really run out reads false and stops re-reading. A later tune picks its guide up again.
- **A gap reads as the no-programme state on every surface:**
  - FR-10 (bar, Playing, system controls): the title is the station's name, the second line reads *Live radio*, and the
    LIVE mark stays;
  - FR-6 and FR-9 (tiles): *Live radio*, with no progress bar;
  - FR-8 (radio player): the name, *Live radio* and LIVE;
  - FR-7 (guide): the programmes either side with the gap left empty between them, never the whole-window *Live radio*
    block, which stays for a station with no programmes in the window.
  - A TV channel in a gap keeps R177's *No program info*.
- **The turnover out of a gap** is the engine's existing 60 s re-read, so the next programme's title can arrive up to 60 s
  after it starts.
- **A re-tune never brings back a programme that has ended.** `RadioTuner.start` and `retune` keep the programme from
  before the pause, so *play* after a long pause shows the old title for up to 60 s. A kept programme whose end has passed
  is dropped at the tune, and the station is re-read at once.
- **A speaker is unchanged.** FR-11's title is the programme at the moment of the load, so a station loaded during a gap
  shows its name until it is loaded again.

**Tests, as amended:**
- `linuxX64Test`, with the service's `clock`:
  - an ended programme in the snapshot is not served, and the read re-reads and serves the next one;
  - a gap gives `current_program` null and `has_guide` true;
  - a Jellyfin failure on either call serves no ended programme and leaves `has_guide` as it was;
  - a channel with nothing listed for 24 hours has `has_guide` false and causes no re-read;
  - concurrent reads make one Jellyfin call;
  - a re-read does not move `lastSyncedAt` or notify, and the cadence does not delay it.
- `RadioLiveTest`: a gap keeps the engine re-reading and the title turns over when it ends; a re-tune past a kept
  programme's end drops it.

**FR-11 — A station plays on a speaker like a song.**
- **What the phone or Mac sends:** a station started while a speaker is linked goes to the speaker. The sender's
  `CastLoadData` gains `radio: CastRadioItem?`, carrying `channel_id`, `name`, `logo_url` and `programme`. When it is
  present, `tracks` is empty.
- **What the receiver does:**
  - tunes the station itself, as its own device (`POST /tv/livetv/channels/{id}/tune`);
  - loads the relay URL with `StreamType.LIVE`, the ticket's `content_type`, and metadata whose title is the
    programme, whose artist is the station and whose image is the logo;
  - offers PAUSE and volume only, never SEEK or QUEUE_*;
  - heartbeats while it plays, and stops the tune on stop, on idle, and when a song load replaces the station.
- R371's rooms, *Add a speaker…* and room volume work as for a song. A group plays one relay connection, from the
  group's leader.
- **The remote.** Over the link, the remote shows a station in a **radio shape**: ⏯, and R371's volume block with rooms
  and *Add a speaker…*. It has no shuffle, previous, next, repeat or skip. `RemoteModel.kind` gains `"radio"`, and
  `remoteKindOf` maps a live radio item to it, never to `film`.
- **The speaker's display:** on a speaker that has one, the title is the programme at the moment of the load. It does
  not turn over mid-stream (non-goal). Ravilo's own surfaces turn over as in FR-10.
- **TVs:** a station is never sent to a TV over R380's music road. The phone's speaker list offers radio to speakers
  and speaker groups only (non-goal).

### Admin and strings

**FR-12 — The admin lineup marks stations.**
- Phase 147's lineup table shows the radio mark beside a station's name.
- Its filters gain *Radio*, beside *all · shown · hidden · new · unavailable*.
- A hidden channel that is still badged *new* is drawn at full strength, not at the hidden rows' 50 %, until the
  operator acts on it. Otherwise the stations the operator came to switch on are the faintest rows on the page. This
  holds for any new channel, not only a station.
- The overview's *channels discovered* and *channels shown* count stations as channels.
- The admin renders `kind` from server-pushed state only. The mark is a class added to `design/app/wf.css`, drawn in
  the round's admin frame. The round's *Live now* block on this page is **not** adopted: live listeners show in the
  playback sessions list (FR-5).

**FR-13 — Strings.** New en, da and fo strings in `Strings.kt` (admin) and the `ravilo-i18n` table:
- *Radio* (the row title, the filter and the mark's accessible label);
- *Live radio*;
- the radio player's LIVE label.

These sit beside R177's Live strings.

**Amendment 2026-10-30 (DEB-2660):** four facts the spec took as given, found while building FR-11.
- **The receiver's logic is tested in `:shared`.** `ravilo-cast` has no JS test runner in CI (its only Gradle task is the
  webpack bundle), so a jsTest there would be a private local path. The decisions the receiver makes for a station sit in
  `:shared` as plain functions and one small session class (`CastRadio.kt`: the metadata, the supported commands, the relay
  URL and type, the tune, the 20 s heartbeat and the stop), and `Receiver.kt` is glue over them. The two receiver tests
  of *Tests* (a `radio` load tunes, loads `LIVE` with the ticket's type and offers no seek or queue commands; a song load
  after a station stops the tune) are `CastRadioTest` in `:shared:commonTest`, which runs on `linuxX64` and the desktop in CI.
- **The receiver says it is playing a station.** `CastReceiverMessage` gains `radio: Boolean?` (additive; null from every
  receiver before this). A station's `status` carries the programme as `title` (the station's name when there is none),
  the station as `kicker`, the logo as `art_url`, `playing`, and no queue. The phone reads `radio` and nothing else to know
  what the speaker plays; `CastRemoteStatus` gains `radio`.
- **A station has no session, so its volume block is the phone's own.** FR-11 says R371's rooms work as for a song, but
  they are drawn from the server's session (`SessionVolumePanel` reads a `SessionDetail`), and a station opens none
  (DEB-2627 FR-5.4, and *Non-goals*). The radio shape's volume block therefore reads the link: the speaker's level
  (`CastSender.volume`, `setVolume`) and, where the platform has a `GroupController` (Android 11+), its rooms, their
  levels and *Add a speaker…*. Where it has none (Mac, Linux) the block is the level alone, as a film's is today. The
  block is what the session-backed panel is for a song, and `GroupController` is unchanged.
- **Pause holds the tune.** A CAF `PAUSE` on a live item cannot be turned into a re-tune (the receiver is not asked
  again at *play*), so a paused speaker keeps its tune and its heartbeat, and the relay's per-id limit and watchdog bound
  it. If the relay connection ends meanwhile (the upstream drops a listener that stopped reading), the receiver's
  player finishes; the receiver then re-tunes the station at the live edge, at most three times in 60 s, and after that
  reports `failed` like any item that could not play. This differs from the engine's pause (FR-10: a pause is a stop),
  where the app owns the player.

**Amendment 2026-10-29 (DEB-2657):** the admin has no `Strings.kt` and no string table in any language — every
admin page draws its English text as literals in the Kotlin that renders it. So the admin half of this FR is
*Radio* and *Live radio* as English literals in `ui/LiveTv.kt` and `ui/PlayingNow.kt`, beside the pages' other
words; the da and fo strings are the `ravilo-i18n` table's alone. Giving the admin a translation layer is a
separate piece of work and is not done here.

## Design

The round is `design/incoming/2026-10-27-radio/` (DEB-2640). Its `REPORT.md` and `Radio - Directions.html` are the
reference for every look below. On 2026-10-27 the owner picked:

| Decision | Pick |
|---|---|
| The radio mark | **A, the corner glyph** (FR-6). The same glyph, at its own size, goes on the guide's channel cell under the name (FR-7), the Now/Next list, the zap banner, the Music Home tiles and the admin row with its filter chip (FR-12). |
| The TV radio player | **A, the Now-playing layout** (FR-8). It is one centred block: logo plate, number and name, LIVE with *Radio* beside it, the programme with its times and live progress, then *Next*. A key hint sits at the foot. The zap banner, number entry and Now/Next are R177's overlays, drawn over this layout. |
| The Radio row | **D, the last row** (FR-9), with square logo tiles the size of the album cards: the logo with the mark, the station's name, and the programme now or *Live radio*. |

Drawn as one answer and adopted as drawn:
- **A station in Music mode** (FR-10): phone *Now playing*, the mini bar, and the Mac's bar.
- **The remote's radio shape** (FR-11), with no *Up next* button. The phone's own bottom row keeps its queue button.

**FR-8's burn-in step**, as drawn:
- four positions, moving one step every 7 minutes, a 28-minute loop;
- 56 × 36 px of travel, with a 600 ms glide, or a cut under reduced motion;
- the whole centred block moves.

Before the client slices start, Rannvá promotes the picked frames into the `design/ravilo/` archive and the admin
mark's class into `design/app/wf.css`, through PROCESS.md's gate, and prunes the round.

## Non-goals

- **No ICY now-playing titles.** The relay asks for none. A song title inside a station's stream is its own piece of
  work.
- **No radio on Ravilo Web's music mode** (it has none), **on Tizen or on a TV over R380's music road**, and no Music
  Home on the TV. On a TV, radio is reached in TV mode.
- **No radio in *Playing everywhere*,** the session road or `playback_session` (DEB-2627 FR-5.4).
- **No background radio in TV mode.** Leaving the radio player stops it. Music mode is where radio keeps playing.
- **No change to the estate.** The stations, their guide and their M3U are DEB-2625's.
- **No fan-out in the relay, no transcode, and no recording.**
- **Radio and TV share one lineup.** There is no separate radio lineup and no radio settings page.

## Acceptance

1. **Old clients:** an APK and a Mac build from before this, against the new backend with the stations shown, show no
   station anywhere: not in the live row, the guide or Music Home. They tune and play TV channels exactly as before.
2. **TV mode, on Android TV, Mac, Linux and Chrome:**
   - the stations sit in the live row with the radio mark;
   - tuning one plays its audio in the radio player within 5 s;
   - zapping crosses between stations and TV channels in both directions;
   - Station 1 shows its programme now, and Stations 2 and 3 show *Live radio*.
3. **Music mode, on the phone, Mac and Linux:**
   - the Radio row is on Music Home;
   - a station plays with the programme as the title, no seek, no next or previous, and a LIVE mark;
   - starting an album stops it;
   - the lock screen or system controls show it as live.
4. **Speakers:**
   - from the phone, a station plays on one speaker and on a two-speaker group;
   - the remote shows the radio shape;
   - the speaker keeps playing with the phone locked for 10 minutes;
   - stopping it closes the tune: the admin row goes, and the relay answers 404.
5. **The relay:**
   - a radio tune on a client hands back `stream_kind = "audio"` with a relay path;
   - the Mac and Chrome play Stations 2 and 3, whose sources are plain `http`;
   - a stale `relay_id` answers 404;
   - a third connection on one id answers 503.
6. **Admin:** a radio listener shows as a live row reading *Radio*, with the speaker named when a speaker is playing.
   The lineup marks the stations and filters them.
7. **Restart:** after a backend restart with a station playing, the station keeps playing. Before the next sync,
   `/tv/livetv/channels` without `radio=1` still has no station in it.

## Tests

- **`linuxX64Test`:**
  - **`LiveTvRadioTest`** (new):
    - a sync stores `kind` from `ChannelType`;
    - `lineup()` before a sync keeps `radio`;
    - the three endpoints leave stations and their programmes out without `radio=1` and include them with it;
    - `kind=radio` and `kind=tv` filter;
    - a station tune gives `stream_kind = "audio"`, `content_type` and a relay path;
    - an `https` HLS station is handed over as-is.
  - **`RadioRelayTest`** (new):
    - bytes pass through with the upstream type;
    - no `Icy-MetaData` header goes upstream;
    - a non-audio type is a 502;
    - an unknown or stopped id is a 404;
    - the per-id and server-wide limits answer 503;
    - an open connection holds off the watchdog, which closes 60 s after the last one ends.
  - **Wire baselines** in `WireRoots` for `LiveTvChannel`, `LiveTvStreamTicket`, `MusicRow` and `CastLoadData`.
- **Client** (`:ravilo-ui:desktopTest`):
  - the music engine's live state: no next, previous or seek, the queue cleared, a song replaces the station, and the
    title turns over at a programme's end;
  - `remoteKindOf` gives `radio` for a live radio item;
  - zapping in one lineup opens the radio player for a station and the video player for a channel;
  - `ListenRow` draws a `radio` row from `stations`.
- **Receiver:** `ravilo-cast` unit tests (jsTest):
  - a `radio` load tunes, loads `StreamType.LIVE` with the ticket's type, and offers no seek or queue commands;
  - a song load after a station stops the station's tune.
- **On a device (QA):** acceptance 1 to 7.
