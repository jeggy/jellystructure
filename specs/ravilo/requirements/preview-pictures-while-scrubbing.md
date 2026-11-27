# A picture of the moment while scrubbing

Task: DEB-5849

> Owner, DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful in
> our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

Touches the shared wire (`StreamTicket`), the backend (`tv/`, one new `/api/tv/` route, the admin advisor's copy), the
Compose TV chrome (Android TV, desktop, Ravilo Web) and the Samsung screen. It is built from the DEB-5687 document
`research-scrub-previews` (2026-11-26); its numbered sections are cited as *research §n*.

- **Clients in:** Android TV (D-pad steps on the bar), desktop Mac/Linux and Ravilo Web (while dragging the bar, which
  all three draw with the same TV chrome), Samsung (arrow steps on the bar).
- **Clients out:** the phone (FR-R244-9's "No thumbnail preview" stands; a phone tile needs its own design round and
  task); Chromecast (no overlay of ours on a seek, owner 2026-10-05, `Receiver.kt:387`); the One Remote scrubber when
  casting; downloads played offline (`buildLocalTicket` has no preview data); music and Live TV (no picture to show).
- **No hover.** A mouse over the bar without a button held shows nothing new, as today.
- Makes R218 FR-R218-1 moment D buildable as written ("the scrub tile carries a small spinner while its frame
  resolves"). Today's stand-in, the spinner beside the time (`PlayerScreen.kt:1282-1287`), moves into the tile.
- Amends, each with a dated note in its own file: R376 (its non-goal "Thumbnail scrub previews"), the Samsung full-app
  spec (its "What is not a gap" line), phase 246 FR-246-12 and phase 297 FR-297-3 (the advisor's "Ravilo never shows
  one"). 246 and 297 lose their Status blocks to Scope in the same change.
- Design A2 of the research: the media server's own preview sheets, proxied by our backend. Designs B (our encoder makes
  sheets) and C (one frame per step) are not built.

## Why

Every streaming app shows a picture of where a scrub will land. Ravilo shows a time only, on every client. The field
meant for it, `StreamTicket.trickplay_url`, has no reader anywhere and is set to null at four sites in
`PlaybackService.kt` (`:1509`, `:1694`, `:2628`, `:2711`). With `encodeDefaults = false` it is absent from the wire.

One URL cannot describe the pictures. The media server keeps them as *sheets*: a grid of tiles, several sheets per title
(research §3.4). A client needs the tile size, the grid, the count and the interval as well as an address.

The backend already fetches the item once on every Play (`getItemDetail`, `JellyfinClient.kt:1796`, called once in each
ticket builder). The preview data can ride that call, so Home → title → Play gains no request.

## Preconditions (estate — not ours to execute)

The media server's settings are Munin's and the owner's estate call. This spec changes none of them. It reads what is
there and shows nothing where nothing is.

| Row | What | Who |
|---|---|---|
| **P-0 (Row 0)** | One authenticated probe on the estate's 12.1, before any code: with a viewer's own token, `GET /Items/{id}` for one series episode and read its `Trickplay` property (a map media source id → width → `Width`, `Height`, `TileWidth`, `TileHeight`, `ThumbnailCount`, `Interval`); then `GET /Videos/{id}/Trickplay/{width}/0.jpg` with a header token, with `?apikey=` and with no credential. The answers fix the DTO, whether `Fields=` needs `Trickplay`, and the route's upstream auth. Record the result on DEB-5849. | Munin or anyone the owner allows to probe |
| **P-1** | As read from the option files on 2026-11-26: preview extraction is **on** for the series library, the music-videos library and one mixed library, and **off** for both film libraries. Until a film library is turned on and its backfill finishes, films show the time only. | Munin / owner |
| **P-2** | The server-wide preview settings are the expensive defaults: software decode, every frame, one thread, one 320 px tile every 10 s, 10 × 10 per sheet. Generation is blind to playback and cannot be cancelled from outside (phase 178, phase 246 §9). Turning films on is the heaviest background job this host would run; whether to use key frames only, hardware decoding and a night window is the estate's call. | Munin / owner |
| **P-3** | Once previews are a product feature, the preview page's switches and the per-library switch join the "diff after an upgrade" list (the 12.1 upgrade reset hardware acceleration once). | Munin |

## Requirements

### FR-1 — The ticket carries a preview descriptor, additive and optional

`StreamTicket` gains one field after `stream_bps`: `scrub_previews`, an object, null by default and so absent from the
wire when there is none:

```
scrub_previews: { url, width, height, columns, rows, count, interval_ms }
```

- `url` is a path relative to our server with the literal `{sheet}` in it (FR-3's route).
- `width`, `height` are one tile's pixels. `columns`, `rows` are tiles per row and per column of a sheet — not the
  media server's `TileWidth`/`TileHeight` names, which mean the same thing and read like pixels.
- `count` is the number of tiles; `interval_ms` the time between them. Numbers and one string; no enum.
- `trickplay_url` stays, null for ever, with a one-line doc saying it is retired in favour of `scrub_previews`.
- **Old clients:** every released app decodes with `ignoreUnknownKeys` and ignores the new key. **Old server:** the key
  is absent, a new client reads null and behaves as today. Nothing ships in lockstep. `WireCompatTest` stays green
  untouched.

### FR-2 — The backend fills it from the call it already makes

- The media server's item DTO (`JellyfinItemDetail`, `auth/Models.kt`) gains a nullable `Trickplay` property. An item
  without it decodes as before. If P-0 shows the single-item answer omits it, `Trickplay` is appended to the existing
  `Fields=` list (`JellyfinClient.kt:1805`). Never a second request.
- One helper builds the descriptor. `startPlayback`, `prewarmPlayback`, `restream` and `restreamWithoutBurnIn` all use
  it, so an audio, subtitle or version switch keeps the picture.
- It takes the entry for the ticket's own `media_source_id` (phase 314c versions), never another cut's.
- Width: 320 if offered, else the largest below 320, else the smallest offered.
- No entry, a zero count or a zero interval → null. Music tickets and Live TV are unchanged.

### FR-3 — One sheet route, private like the title itself

`GET /api/tv/previews/{item}/{source}/{width}/{sheet}.jpg`, device token. Not under `/api/tv/image/`: that prefix is
public by item id, and a sheet is frames of the title.

- Runs `requireVisible` for the device's viewer. Not visible → 404.
- `sheet` beyond `ceil(count / (columns × rows)) − 1` → 404 with no upstream call.
- Fetches from the media server with header auth (the `serveAvatar` pattern, `RaviloArtworkService.kt:166`). No media
  server credential reaches a client.
- Caches the bytes on disk under the data directory, in a store with a cap; keyed on item, media source, width, count and
  interval, so a replaced file never serves the old file's frames. Never caches a non-image or an upstream error.
- Answers with `respondCachedBytes` (ETag, 304, `max-age`). Upstream 404 → 404.

### FR-4 — Nothing at Play; one sheet on the first step

The same rule on every client in scope:

1. At Play, on chrome reveal, pause and resume: **no sheet is fetched.** The start path gains only the descriptor's bytes
   in a response it already reads.
2. On the first scrub step (D-pad or arrow step, or the first drag movement): fetch the one sheet holding the target.
3. When the target comes within 3 tiles of a sheet edge, fetch the neighbour. One sheet in flight at a time.
4. Hold at most two decoded sheets, outside the shared image memory cache, and free them when the chrome hides.
   (A full sheet is about 23 MB decoded; research §5.5.)
5. Never prefetch all sheets. A failed fetch is not retried in that scrub; the time shows as today.

TV focus motion gains nothing: the tile draws from memory or not at all.

### FR-5 — Which tile

One pure function in `shared`, used by Compose and Samsung alike:
`n = min(floor(position / interval), count − 1)`, `sheet = n / (columns × rows)`, `k = n mod (columns × rows)`,
`col = k mod columns`, `row = k / columns`, source rectangle `(col × width, row × height, width, height)` in pixels.
Pixel offsets, never percentages: the last sheet may be shorter. Interval or count of 0 → no tile.

### FR-6 — Android TV, desktop and web draw the tile above the bar

- While scrubbing, the TV chrome (`SeekRow`, `PlayerScreen.kt`) shows a tile above the bar at the scrub position, with the
  time under it: the drawn `.pl-trick` spot, 296 × 166 dp, radius 12, clamped so it never leaves the bar's ends.
- The sheet is fetched as bytes through the API client (bearer attached) and decoded once per sheet by a small
  expect/actual (Android `BitmapFactory`, Skia on desktop and web). The tile is `drawImage` of the FR-5 rectangle.
- A tile shorter than 16:9 is letterboxed inside the tile, never stretched.
- Until the sheet is decoded: the tile shows R218's small spinner and the time (FR-R218-1 moment D).
- Descriptor null, or the fetch failed: no tile, and the time label exactly as today. Never an empty frame.
- Android TV: one D-pad step is 10 s on anything over about 14 minutes (`scrubStep`), the estate's tile interval, so each
  press shows the next tile. Desktop and web: shown while the bar is dragged; arrow keys still seek at once.

### FR-7 — Samsung draws the same tile above its time chip

- `#ov-track` (`index.html:59`) gains one child, shown by the existing `#ov-seek.scrub` class, above `#ov-scrub`, its
  look copied from Android TV into `screen.css`.
- The sheet comes through the shared API client with the device token and becomes a blob URL; the tile is
  `background-image` plus `background-position` in pixels from FR-5, scaled as a whole to the drawn width.
- Hidden until its sheet has loaded; the time chip behaves as today meanwhile and always. The blob URL is revoked when the
  chrome hides. A set that fails to load or decode the sheet shows the time only.

### FR-8 — The admin advisor stops saying Ravilo shows no previews

The per-library preview row's trade-off (`JellyfinAdvisorService.kt:226`) says what Ravilo viewers lose: with extraction
off for a library, Ravilo shows the time only while scrubbing its titles. The FR-246-12 comment at `:225` goes. It ships
no later than FR-6, or the dashboard advises turning off the feature's source. Admin only; no viewer text.

### Strings

None. The tile is a picture and a numeric time. The design's fallback label "Preview" is not built, so no key is added to
`i18n/en.json`, `da.json` or `fo.json`.

## Design

No round. Android TV, desktop and web reuse the drawn `.pl-trick` tile (`design/ravilo/ravilo-player.css:117-126`) and
R218's spinner in it. Samsung copies that look into `screen.css`, as the Samsung spec's amendments do. The phone, which
would need a round, is out.

## What this does not do yet

- **Films** show nothing until P-1 and its backfill (estate).
- **Phone** tile (FR-R244-9 stands; its own task after a design round).
- **Hover** previews on desktop and web.
- **Casting:** nothing on the receiver; the One Remote scrubber has no ticket to read.
- **Downloads** offline.
- **Our own sheets** for libraries the media server does not cover (Design B). FR-3's address is ours, so that source
  could come later with no client change.

## Acceptance

1. P-0's answer is recorded on DEB-5849 before slice 2 starts.
2. A series episode with sheets, on Android TV: the first D-pad step on the bar shows the spinner tile, then the picture;
   five more steps inside the sheet make no further request.
3. Same title, counted at the backend: **no** sheet request from Play until the first scrub step.
4. A film from a library with extraction off: the scrub shows the time only, as today, on every client in scope.
5. Samsung: the first arrow step shows the tile with the step's `background-position`; Back hides it.
6. Desktop and web: dragging the bar shows the tile; arrow keys seek as today.
7. An audio or subtitle switch mid-play, then a scrub: the picture still shows.
8. A viewer who may not see the title gets 404 from the sheet route; a sheet index past the end gets 404 with no upstream call.
9. A released Android TV APK against the new backend plays and scrubs exactly as before.
10. The dashboard's per-library preview row no longer says Ravilo never shows one.

## Build order

One engineer, in order; slices 6 and 7 can swap.

1. **Tile arithmetic** (FR-5). New file in `shared/src/commonMain/.../tv/`, test in `shared/src/commonTest`: 0 ms, 9 999 ms,
   10 000 ms, 990 000 ms, 1 000 000 ms, past the end, zero interval/count, short last sheet.
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :shared:desktopTest --tests '*ScrubPreviewTile*' --console=plain`.
   **Junior-ready.**
2. **Wire + ticket** (FR-1, FR-2), after P-0. `shared/.../tv/Models.kt`, `auth/Models.kt`, `auth/JellyfinClient.kt` (only
   if P-0 says so), `tv/PlaybackService.kt` (one helper, four sites). Tests: `SessionWireTest` (absent → null; unknown key
   inside → decodes), a new `PlaybackScrubPreviewTicketTest` beside `PlaybackSubtitleTicketTest` (property → descriptor;
   none → key absent; two widths; two media sources; restream and prewarm carry it).
   `./gradlew linuxX64Test --tests '*ScrubPreview*' --console=plain` and `:shared:desktopTest`.
3. **Sheet route + cache** (FR-3). `server/routes/TvRoutes.kt` beside the subtitle route, a new service beside
   `tv/RaviloArtworkService.kt`, `Main.kt` wiring. Test with a fake media server: refused when not visible; index past
   the end → 404 with zero upstream hits; upstream 404 → nothing cached; second hit from disk; ETag → 304.
   `./gradlew linuxX64Test --tests '*ScrubPreviewRoute*' --console=plain`.
4. **Advisor copy** (FR-8). `advisor/JellyfinAdvisorService.kt`, `JellyfinAdvisorServiceTest`.
   `./gradlew linuxX64Test --tests '*JellyfinAdvisorService*' --console=plain`. **Junior-ready.**
5. **Sheet holder + decode seam** (FR-4). New `ravilo-ui/.../seams/` expect plus Android, desktop and wasm actuals; a holder
   that fetches by FR-4's rule and frees on hide. Test with a counting fake loader: never before the first step; once for
   five steps in a sheet; neighbour near the edge; freed on hide.
   `./gradlew :ravilo-ui:desktopTest --tests '*ScrubPreviewSheet*' --console=plain`.
6. **TV chrome tile** (FR-6). `screens/PlayerScreen.kt` (`SeekRow`), `screens/PlayerScreenState.kt`. `runComposeUiTest`:
   null descriptor → no tile node, time as today; loader that never answers → spinner tile; loader that answers → tile
   inside the bar at fraction 0 and 1. `./gradlew :ravilo-ui:desktopTest --tests '*ScrubPreviewTile*' --console=plain`,
   then `:ravilo-ui:testDebugUnitTest`.
7. **Samsung tile** (FR-7). `ravilo-screen/.../Screen.kt` (paint near `:1674`), `wgt/index.html:59`, `wgt/screen.css:67-68`,
   `tests/mock-jellyfin/server.js` (a preview property on the item), `tests/e2e/ravilo-screen-player.spec.ts`: tile on the
   first arrow press with the right `background-position`; no sheet request before it; none without a descriptor; Back
   hides it. Playwright in docker compose, as CI runs it.
