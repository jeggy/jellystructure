# Phase 179 — Text-subtitle sideload still stalls on the transcode path (R183/161's deferred half)

> Live-hit tonight (2026-08-28): playing `Spindlelegs` (65 GB remux) on stue TV, ~90 s into playback, its
> embedded SDH English subtitle track silently died — ExoPlayer logged `Disabling track due to error:
> ... java.net.SocketTimeoutException: timeout / Caused by: SocketException: Socket closed` and never
> retried. Video and audio were unaffected throughout (confirmed: position kept advancing in real time
> for the rest of the session). Traced to a **known, previously-deferred issue**, not a new bug: R183
> found and documented this exact mechanism in 2026-08 (a **4m37s** first-play stall extracting subtitles
> from a 26 GB file), Phase 161 fixed half of it, and explicitly left the other half — the half this
> hit — out of scope as low-priority. **Phase 177 (shipped earlier today) just changed that priority
> calculus** by making the exact tier of file this affects transcode far more often than before.

**Status:** Planned (design-authored, not yet dev-reviewed).

## Root cause

`PlaybackService.buildSubtracks()` (`src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt:543-617`)
decides, per subtitle stream, whether a client renders it natively from the container (`deliveryMethod =
"embed"`, `url = null`, free) or must fetch it sideloaded as Jellyfin-extracted VTT (`deliveryMethod =
"external"`, a `.../Subtitles/{index}/0/Stream.vtt` URL, Jellyfin runs ffmpeg to produce it on demand).
The gate (`startPlayback()`, `:306`):

```kotlin
val embedContainerSubs = !needsTranscode && capabilities.supportsEmbeddedTextSubs
```

**This is a chain of three already-documented facts, plus one new one from today:**

1. **R183** (`phase-R183-dolby-vision-playback.md`, "Known adjacent issue", `:93-102`) first measured this:
   on a direct-played 26 GB file, sideloading every embedded text subtitle made Jellyfin ffmpeg-extract
   all four `subrip` tracks — **21:41:26 → 21:46:04, 4m37s** — delaying first-play start. It named two
   fixes: (A) skip the sideload when the client can render the same stream natively in-container
   (`MatroskaExtractor` does, on Android), or (B) pre-extract text subs in the scan pipeline. Both were
   deliberately deferred as "follow-up work with their own risk."
2. **Phase 161** (`phase-161-embedded-subtitle-double-delivery.md`) implemented fix (A) only —
   exactly the `embedContainerSubs` gate above — and explicitly scoped fix (B) **out**: *"would help the
   transcode path's VTT-extraction cost too, but is a bigger, separately-risked change; FR-SUB1-2 alone
   already eliminates the reported bug and R183's stall for every direct-play case, **which is the
   overwhelming majority of this library's subtitle-bearing playback**"* (`:94-97`, emphasis added).
   It also states as an invariant: *"A transcode always sideloads text subs, unconditionally... a
   transcoded output doesn't carry the source's original embedded subtitle streams"* (`:76-78`) — this
   part is **not** the bug; a transcoded MPEG-TS output genuinely cannot carry the source MKV's embedded
   subtitle stream for `MatroskaExtractor` to find, so `embedContainerSubs` must stay `false` whenever
   `needsTranscode` is `true`. Re-opening that gate is not the fix (see Out of scope).
3. **Phase 177** (shipped today, same session as this report) makes `needsTranscode` true far more often
   for exactly the highest-bitrate tier of the library — a per-codec `VideoBitrate` device-decode-ceiling
   check that correctly forces a transcode for any file the client's decoder can't actually handle, where
   before it would have (incorrectly, silently-broken) direct-played. Spindlelegs (93 Mbps HEVC, exceeds
   stue TV's 60 Mbps ceiling) is exactly this case: before today it always direct-played (broken but
   `embedContainerSubs`-eligible); tonight it correctly transcoded, which forced `embedContainerSubs =
   false`, which routed its SDH English track onto the exact sideload path R183 measured at 4m37s.
4. **New tonight:** the failure mode itself changed shape. R183 saw a foreground stall that eventually
   *succeeded* (4m37s, then cached). Tonight's failure was a **hard client-side abort** — ExoPlayer's
   default `DefaultHttpDataSource` (no custom `DataSource.Factory` is configured anywhere in
   `RaviloPlayerAndroid.kt` — confirmed by grep, only Media3's stock 8000ms connect/read timeouts apply)
   gave up with a read timeout (mid-transfer stall, not a connect failure — consistent with a slow
   trickle of bytes, not a dead connection) roughly 90 seconds after session start, and **never retried**
   — the track was gone for the rest of the session. Plausible mechanism: Jellyfin's own ffmpeg subtitle
   extraction was competing for the same disk/CPU as the concurrent 93→54 Mbps NVENC video transcode
   *of the same source file* — the same family of contention Phase 178 targets for our own pipeline and
   qBittorrent, just from a third source (Jellyfin's own on-demand extraction) that phase explicitly
   named as out of scope. Not independently re-measured under load tonight (see Open questions §1).

**Live-verified tonight** (post-session, system idle, both against `Videos/{id}/{id}/Subtitles/{index}/0/
Stream.vtt`): a repeat request for the already-hit index (5) returned in 39-113ms; a fresh, never-
requested-tonight index (4) returned in 46ms with **two different tokens** (the admin API key and the
real device's own `jellyfin_user_token`), both byte-identical. Consistent with R183's own finding that
*"the extraction is cached afterwards"* and with per-content (not per-token) caching — but not fully
conclusive, since an idle system might simply extract fast regardless of caching. The one real,
timed-out failure tonight happened specifically while the video transcode was also running; every
successful measurement here happened with it idle. See Open questions §1.

## Requirements

### FR-179-1 — Pre-extract embedded text subtitles during the pipeline (R183's deferred Option B)

Add a step to the scan pipeline (`media/PipelineEngine.kt`, Phase 175's step dispatcher) that, for every
text-subtitle stream (`isTextSubtitleStream` / SRT/ASS/SSA — the same predicate `buildSubtracks()`
already uses), issues the same `.../Subtitles/{index}/0/Stream.vtt` request `buildSubtracks()` builds for
a real client — off the playback critical path, at low priority (same `nice`/`ionice` discipline Phase
145 already applies to `fpcalc`/`blackdetect`/`silencedetect`, and the same `defer_while_playing`
awareness Phase 178 just gave `detect_segments`/`fetch_artwork` — this is exactly that category of work).
This warms Jellyfin's own extraction cache (§Root cause, point 4) so that **whichever path a real client
ends up on** — embed or sideload, direct-play or transcode, this device or a different one — a live
playback request never pays R183's original 4m37s cost, and never races a concurrent transcode of the
same file the way tonight's request did.

- **Scope: every text-subtitle stream in the library**, not just files likely to transcode. Predicting
  which files will transcode means predicting per-device decode ceilings (Phase 177), which vary by TV —
  brittle and exactly the kind of guess Phase 177 itself moved away from ("we never invent a ceiling").
  Matches the existing `detect_segments`/`fetch_artwork` pattern: touch everything once, cheaply, in the
  background, rather than special-casing which items need it.
- Only extracts (fetches) — never mutates a media file, never writes an NFO, no drift-detection surface.
- Gated behind the same freshness/re-check logic `scan_files` already uses (`Scan: N item(s) not due for
  a recheck yet`) so a re-scan doesn't re-extract every subtitle on every run.

### FR-179-2 — Bound and recover from a cold or failed sideload client-side

Pre-warming (FR-179-1) doesn't cover every case — a just-added file before its next pipeline run, or a
cache eviction on Jellyfin's side. The client should degrade gracefully rather than permanently losing
the track for the whole session, the way it did tonight:

- Configure an explicit `HttpDataSource.Factory` for **subtitle requests specifically** (distinct from
  the video/HLS segment data source, which must stay tight) with a longer, generous timeout — today it's
  Media3's unconfigured stock 8000ms connect/read, nowhere near R183's measured 4m37s worst case.
- On a subtitle-track load failure, retry once after a short backoff instead of disabling the track for
  good — today's `AudioMediaPlayerWrapper`/ExoPlayer behavior is one-shot with no follow-up attempt.
- **Never blocks or delays video/audio start.** The existing graceful degradation — video/audio play
  fine when a text track fails — is correct and must be preserved exactly. This requirement is about the
  subtitle track's own resilience only.

### FR-179-3 — Make a repeat of tonight diagnosable without another live incident

Tonight's finding required a live logcat pull during an active session to catch. Phase 177's QoE
reporting (`playback_qoe`, `POST /api/tv/playback/qoe`) landed the same day this bug did — extend it
minimally: a subtitle-track load failure (and whether the retry from FR-179-2 recovered it) becomes part
of that existing report rather than a new subsystem. Small scope on purpose — this is instrumentation,
not the fix.

## Invariants

- **Pre-extraction (FR-179-1) never blocks the pipeline's other steps or an operator's manual scan** —
  same deferral/priority discipline as every other heavy pipeline step (Phase 145/170/178).
- **A subtitle track failing — even after the FR-179-2 retry — never delays or blocks video/audio.**
  Matches tonight's actual (correct) behavior, which must not regress.
- **No visible product change.** Still the exact R180 flag-forward picker, still zero codec/delivery-
  method/latency exposure (`FR-RV-ASP1-2`). This phase is a reliability fix behind an existing surface,
  not a design change.
- **`embedContainerSubs`'s gate itself is not touched.** `!needsTranscode && capabilities.
  supportsEmbeddedTextSubs` stays exactly as Phase 161 built it — provably correct, not the bug (see
  Root cause, point 2).

## Out of scope

- **Re-opening `embedContainerSubs`'s transcode gating.** Already covered above — a transcoded MPEG-TS
  output cannot carry the source MKV's embedded subtitle stream; there is no version of this fix that
  lets a transcoding client render text subs natively in-container.
- **PGS burn-in (`encode`) and VobSub/DVDSub (`embed`).** Untouched by this whole chain — PGS was never
  sideloaded as VTT (Phase 161's own "out of scope" note, still true), VobSub/DVDSub was always native.
- **Making Jellyfin's own ffmpeg extraction faster or higher-priority.** Not ours to tune from the
  outside; FR-179-1 sidesteps the need (do it once, ahead of time, when nothing's competing for the disk)
  rather than trying to make the live operation itself faster.
- **Driving Jellyfin's own scheduled tasks or I/O priority** — Phase 178 already named this out of scope
  for the same reason (a real integration with its own risk); this phase's FR-179-1 doesn't need it,
  since pre-warming happens on our own pipeline's schedule, not by controlling Jellyfin's internals.

## Open questions

1. **Not fully verified: is Jellyfin's extraction cache keyed by content (item+stream+index) or by
   request (would bust across different device tokens)?** Tonight's two-token test (§Root cause, point 4)
   returned identical content instantly for both, consistent with content-keyed caching — but the system
   was idle for both checks, so a genuinely cold extraction under idle conditions might just be fast
   regardless of caching. **This needs live verification under real load before FR-179-1 is built** — the
   same kind of check Phase 177 ran against real `PlaybackInfo` before committing to its design. If the
   cache turns out to be request-keyed, FR-179-1's pre-warm (issued with our own admin/pipeline token)
   would not help a real device's differently-tokened request, and the fix would need to change shape
   (e.g., caching the VTT ourselves rather than relying on Jellyfin's cache).
2. **FR-179-1 scope: whole library vs. a narrower set?** Proposed "every text-subtitle stream," matching
   `detect_segments`/`fetch_artwork`'s existing pattern — but worth confirming the actual per-item cost
   (ffmpeg invocation overhead × library size) isn't large enough to warrant narrowing, given Phase 177
   means the "which files might transcode" question no longer has a stable answer per file (it depends on
   which device plays it).
3. **FR-179-2's exact timeout value.** Proposed "longer than the stock 8s, well short of R183's 4m37s
   worst case" — needs a real measurement (same rigor as Phase 177's 0.9 margin / 50% link-fraction
   numbers, which are flagged in that spec as "proposed, not measured" for the same reason) rather than a
   guessed constant.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt:264-320` (`startPlayback`,
  `needsTranscode`/`embedContainerSubs`), `:543-617` (`buildSubtracks`, the branch table).
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerAndroid.kt` — no
  custom `DataSource.Factory` exists yet (confirmed by grep); FR-179-2's addition point.
- `media/PipelineEngine.kt` — Phase 175's step dispatcher; FR-179-1's new step belongs at its one step
  loop, matching Phase 178's own note about where deferral/new steps belong.
- `specs/ravilo/requirements/phase-R183-dolby-vision-playback.md:93-102` — the original 4m37s
  measurement and the two deferred fix options this phase revisits.
- `specs/requirements/phase-161-embedded-subtitle-double-delivery.md` — implemented half of R183's fix;
  `:94-97` explicitly scopes the other half out, on a "overwhelming majority direct-play" assumption
  Phase 177 changes for the highest-bitrate tier of the library.
- `specs/requirements/phase-177-delivery-aware-playback-negotiation.md` — the fix that shifted
  `needsTranscode` from rare-for-this-tier to common-for-this-tier, reactivating R183's deferred half.
- `specs/requirements/phase-178-playback-aware-background-io.md` — the sibling I/O-contention phase;
  explicitly does not cover Jellyfin's own on-demand work (only our pipeline + qBittorrent), which is
  exactly the gap this phase's contention hypothesis falls into (§Root cause, point 4).
- Live evidence: stue TV logcat (`08-28 17:51:36`, `ExoPlayerImplInternal` `SocketTimeoutException`/
  `SocketException: Socket closed` disabling subtitle track id=3), Jellyfin `/Videos/.../Subtitles/...`
  timing checks (idle, 2026-08-28, this session).
