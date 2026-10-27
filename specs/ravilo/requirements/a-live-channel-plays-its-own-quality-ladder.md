# A live channel plays its own quality ladder

Task: DEB-2627

> Owner, 2026-10-27, on DEB-1076 (Q1 *"Yes"*): a channel whose source is a public adaptive HLS ladder is played from
> **the source's own master playlist**, so the player picks the best rung the line can hold and steps down on its own.
> Jellyfin keeps the lineup, the guide and the tune.

Amends [R177](phase-R177-livetv-viewing.md) D1 (dated note in that file). Leaves [Phase 147](../../requirements/phase-147-livetv-admin-config.md),
R368 and the constitution as they are.

## What a tune does today (verified 2026-10-27)

The investigation assumed Jellyfin remuxes one variant of the source for every viewer. **For Ravilo, it does not.**

- `LiveTvService.tune` (`src/linuxX64Main/.../tv/LiveTvService.kt`) calls `PlaybackInfo`, then `POST /LiveStreams/Open`,
  and hands the client **`MediaSource.Path`** as `hls_url`. It never reads `TranscodingUrl`.
- For an M3U tuner entry whose URL is an HLS master on the open internet, Jellyfin's open is a bookkeeping step. Its
  own log for the production abroad feed reads `Live stream opened after 0.0011ms` and
  `MediaSourceInfo { Protocol: Http, Path: "https://<broadcaster>/…/smil:<feed>.smil?type=m3u8", Container: "hls",
  IsRemote: True }`. No ffmpeg starts for that open.
- So a Ravilo client is **already handed the broadcaster's own master**: five rungs, 1920×1080 at 7.7 Mb/s down to
  426×240 at 0.56 Mb/s. The master, each rung's playlist and the segments all answer
  `Access-Control-Allow-Origin: *`.
- The pinned top-rung remux found on DEB-2624 (pure `-codec copy`, no ABR, about 11 s to the first segment) is
  Jellyfin's **transcoding** URL. Jellyfin's own clients use it. Ravilo never requests it.

The ladder reaches the client, but **the clients do not reliably play it as a ladder.** This is read from the code and
has not yet been seen on a device:

| Client | What it does with a live `hls_url` today |
|---|---|
| Android TV, phone (Media3) | `MediaItem.Builder().setUri(url)` with no MIME type, so Media3 guesses the type from the last path segment. `…smil:<feed>.smil?type=m3u8` reads as `smil` (progressive), so a master whose URL does not end in `.m3u8` probably fails to open. One that does end in `.m3u8` adapts, but under the last **film's** ladder state: `PlayerLadderHints` is never reset for live, and it drives `TimeFirstLoadControl` and `ClimbingTrackSelection`. |
| Browser (hls.js / Safari) | `attachSource` uses hls.js only when the URL contains `.m3u8`. Otherwise it sets `video.src`, which Chrome and Firefox cannot play for HLS. 309's `WebLadderCap` caps any multi-level stream, live included. |
| Mac (AVPlayer) | Adapts on its own, but `MacLadderPeak` sets `preferredPeakBitRate` from the last film's stale `PlayerLadderHints.adaptive`. |
| Linux (libmpv) | ffmpeg sniffs the content, so it opens. mpv's default `hls-bitrate` is `max`: it takes the top rung and **never switches**, so a slow line stalls instead of stepping down. |
| Tizen (`ravilo-screen`), Cast receiver | No live path at all. The Tizen app is receiver-only and plays a VOD ticket. The receiver only negotiates an item. Neither is added here. |

Also missing: **nothing reports live viewing.** `LiveTvService` keeps an in-memory `activeStreams` per device for its
stop watchdog. R368's `playback_session` has no live kind, the admin *Now playing* list never shows a channel, and
`isPlaybackActive()` (which defers background work) does not count a live viewer. Jellyfin only sees the open and the
close.

## Requirements

**FR-1 — The tune says what it handed over.** After the open, the backend classifies `MediaSource.Path`:

1. **`hls_ladder`:** an absolute `http(s)` URL that the backend fetched as an HLS master with **two or more**
   `#EXT-X-STREAM-INF` entries and no protection. "No protection" means no `#EXT-X-KEY` or `#EXT-X-SESSION-KEY` with
   `METHOD=SAMPLE-AES*`, and none with a `KEYFORMAT` other than `identity`.
2. **`hls`:** an HLS playlist that is not a ladder. That covers one variant, a media playlist, or a protected master.
3. **`other`:** anything else, including a Jellyfin-local path (a non-HLS tuner's `LiveStreamFiles` stream).

Rules for the classification:
- **The fetch:** follows at most 3 redirects, times out after 3 s and reads at most 64 KiB. The result is cached per
  (channel id, `Path`) for 1 h, so zapping does not pay for it again.
- **A failed or timed-out fetch:** gives `hls` when the URL contains `m3u8`, and `other` when it does not. A failure is
  a classification, never a tune failure.
- **The client fetches the master itself.** The backend's fetch is only a look. `hls_url` stays exactly `Path`, never
  the URL the backend was redirected to, because a broadcaster's redirect can mint a per-viewer session in its
  rung URLs.

**FR-2 — The wire, additive only.** `LiveTvStreamTicket` gains:
- `stream_kind`: a `String`, one of `hls_ladder` · `hls` · `other`, default `"other"`. It is a plain string, not an
  enum, so a future value can never break a client's decode (`RaviloWireJson` decodes enums strictly).
- `ladder`: a list of `{ bandwidth_bps: Long, width: Int?, height: Int? }`, highest first, default empty. It carries
  the master's rungs and is non-empty only for `hls_ladder`.

`LiveTvTuneRequest` gains `via`, a `String?` that is `null` (the default) or `"jellyfin"` (FR-4). Every existing field
keeps its name and type. An installed client that ignores the new fields gets exactly today's ticket. The R319 wire
baseline for `LiveTvStreamTicket` and `LiveTvTuneRequest` is regenerated in the same commit.

**FR-3 — Every client plays an HLS ticket as HLS, and a ladder as a ladder.** A ticket is HLS when `stream_kind` is
`hls_ladder` or `hls`. When it comes from a backend that predates FR-2 (no `stream_kind`), it is HLS when the URL
contains `m3u8` anywhere, the query included. On an HLS ticket:

1. **Android (Media3):** the `MediaItem` carries `MimeTypes.APPLICATION_M3U8`, so Media3 never guesses from the path.
2. **Browser:** hls.js plays it whatever the URL looks like, or native HLS where hls.js is not supported (Safari).
   `video.src` is never used for an HLS ticket.
3. **Mac:** AVPlayer plays the URL as given. It already detects HLS by content type.
4. **Linux:** mpv plays the URL as given.

A live tune also **starts from no film ladder state.** The VOD ladder hints (`PlayerLadderHints`, `MacLadderPeak`,
`WebLadderCap`, the `TimeFirstLoadControl` keying) are cleared or not applied, so nothing from the last film caps a
channel. On an `hls_ladder` ticket each player adapts on its own:

- **Android:** Media3's own adaptive track selection over the source's rungs. Playback starts at the highest rung its
  bandwidth estimate holds and moves either way as the line changes. DEB-2205's in-place step-down on a stall
  (`setMaxVideoBitrate` to the rung below) stays, and a new tune clears it as today.
- **Browser:** hls.js ABR with no 309 cap. `capLevelToPlayerSize` may stay on, so a small window never fetches 1080p.
- **Mac:** AVPlayer's own ABR, with `preferredPeakBitRate = 0`.
- **Linux:** mpv cannot switch rungs during playback, so Ravilo does it:
  - **Start:** `hls-bitrate` is the highest rung at or below 0.7 × the link estimate, or `max` when there is no
    estimate.
  - **Stall:** a rebuffer that lasts over 5 s, or a second rebuffer within 30 s, reloads the same master at the live
    edge with `hls-bitrate` set to the next rung below. The bottom rung stays where it is.
  - **No climb:** it never climbs back during a tune, and the next tune starts afresh.
  - Each step costs one short rebuffer on Linux only. Everywhere else a step is seamless.

On an `hls` ticket the player plays the one stream it is given, as today.

**FR-4 — Fallback to Jellyfin when the source fails.** A client re-tunes the same channel once, with
`via = "jellyfin"`, in either case:
- the source has given no first frame within 15 s;
- the player raised a fatal error before the first frame, or after one retry of the source itself.

For `via = "jellyfin"` the backend opens through Jellyfin as today and hands back **Jellyfin's `TranscodingUrl`**:
- made absolute against the Jellyfin base;
- carrying the device's own Jellyfin token, the same way `tune` already uses it (never the server key);
- with `stream_kind = "hls"`.

That is the one rendition Jellyfin remuxes (DEB-2624: the top rung, stream-copied). It plays, but it never steps down.

Limits on the fallback:
- **Once per tune.** If the fallback fails too, the player shows today's live error. A zap or a re-tune starts again
  from the source.
- **An old backend:** one that ignores `via` hands back the source again. The second failure then shows the error, as
  any failure does today.
- **No fallback on a channel the backend classified `other`.** That one is already Jellyfin's own stream.

**FR-5 — Live viewing is reported by jellystructure.** It is built on `LiveTvService`'s active streams and is not
written to R368's `playback_session`.
1. `POST /api/tv/livetv/heartbeat` accepts an optional body:
   - `channel_id` and `live_stream_id`;
   - `variant_bandwidth_bps` and `height`, for the rung on screen now;
   - `delivery`: `source` or `jellyfin`.

   A body-less heartbeat keeps working, as today. A heartbeat from a device the backend has no active stream for, but
   with `channel_id` and `live_stream_id` (a backend restart), re-registers it. That way a restart does not end a live
   viewer's row or trip the watchdog. Heartbeats stay every 20 s.
2. The admin's playback sessions list shows each active live stream as a row:
   - the device, the user, the channel's name and the programme on now;
   - the delivery and the rung (*1080p · 7.7 Mb/s*), and the time since the tune.

   The row uses the list's existing row component, and the frontend renders it from server-pushed state only. The row
   goes when the stream stops or the watchdog closes it.
3. `isPlaybackActive()` counts an active live stream, so background work defers for a live viewer as it does for a
   film.
4. **Not to the apps.** Live rows are not added to `session_list` or `/api/tv/playback/sessions`. An installed phone
   has never seen a live kind, and showing live on *Playing everywhere* is its own piece of work with a design round.

**FR-6 — Tizen and the Cast receiver.** Neither has a live path, and none is added here. Whoever adds one
inherits FR-1 to FR-4:
- AVPlay and Shaka both adapt over an `hls_ladder` on their own;
- the receiver needs the source's CORS headers, which the production feeds send.

## Non-goals

- No proxy and no transcode of the source by jellystructure. The client fetches the broadcaster directly, as it does
  today, so the broadcaster sees each viewer's own address.
- No change to how Jellyfin owns the tuner, the lineup or the guide (R177's non-goal stands). The open and the close
  still go through Jellyfin.
- No new viewer-visible surface and no new admin page. FR-5's row lives in an existing list.
- No change to the estate. **Viti's suggested `EnableStreamLooping=false` on the tuner must be checked against FR-1
  before it is made.** Jellyfin's M3U host keeps `Path` as the source URL for an HLS source. If the flip changed what
  `Path` is for a master whose URL does not end in `.m3u8`, a tune would come back `other` and lose its ladder.

## Acceptance

1. **Steps down without stopping.** QA caps the client's line to below the top rung, for example to 2 Mb/s with the
   router's per-client cap.
   - **On a TV and on the phone (Android), on the Mac, and in Chrome:** while a ladder channel plays, the rung on
     screen drops to one the cap holds within 30 s, with no stop and no error. When the cap is lifted it climbs back.
   - **On Linux:** it drops one rung per stall, with at most one short rebuffer per step.
2. **Starts high on a good line:** an uncapped line reaches the top rung (1080p) within 30 s on each of those clients.
3. **A master URL that does not end in `.m3u8` plays** on Android, Chrome and Linux. This is the abroad feed that ends
   `?type=m3u8`, which today probably does not.
4. **Fallback:** a channel whose source is made to fail (a test M3U entry pointing at an unreachable master, in the
   test stack) plays through Jellyfin on the second tune. A fallback that fails too shows today's error.
5. **Old clients:** an APK from before this work, against the new backend, tunes and plays exactly as before.
6. **Admin:** a live viewer appears in the playback sessions list with channel, rung and delivery, and survives a
   backend restart while the viewer keeps watching. Background work defers while they watch.

## Tests

- `linuxX64Test`:
  - **`LiveTvLadderTest`** (new, beside `LiveTvTuneTokenTest`):
    - the master parser: five rungs give `hls_ladder` and a five-entry `ladder`, highest first; one variant gives
      `hls`; `SAMPLE-AES` gives `hls`; a media playlist gives `hls`;
    - the classifier: a relative `Path` gives `other`; a fetch timeout with `m3u8` in the URL gives `hls`;
    - the cache: a second tune within the hour does not fetch again;
    - `hls_url` is `Path` byte for byte, with the fake upstream redirecting;
    - `via = "jellyfin"` returns the fake open's `TranscodingUrl`, absolute, with the device token.
  - **`LiveTvHeartbeatTest`**: a body-less heartbeat still works; a heartbeat with ids after a restart re-registers;
    `isPlaybackActive()` is true while a live stream is active.
  - Updated wire baselines in `WireRoots`.
- **Client:** pure, JVM or desktop tests (`:ravilo-ui:desktopTest`) for:
  - "is this ticket HLS" (`stream_kind`, and the `m3u8`-anywhere rule for an old backend);
  - the Linux rung picker: start rung from the link estimate, next rung below, the bottom rung stays;
  - the fallback decision: first frame timeout, fatal before the first frame, once per tune, never for `other`.
- **On a device (QA):** acceptance 1 to 3, 5 and 6.
