# Phase 161 — Stop double-delivering embedded text subtitles on direct play (FR-SUB1)

> Correction to R55's design: R55 ("Subtitle rendering surface + embedded text subs") deliberately
> made every embedded SRT/ASS/SSA subtitle sideload as a Jellyfin-extracted VTT, unconditionally —
> at the time, the player had no `SubtitleView`/`onCues` wiring at all, so sideloading was the only
> way embedded subs ever reached the screen. R183 later found (as a documented "known adjacent
> issue," not fixed there) that on a **direct-played** file, Media3's `MatroskaExtractor` *also*
> parses those same embedded subs natively from the container — so every text subtitle reaches the
> player twice: once sideloaded, once in-container. Reported live via R195's new two-level picker,
> which made the duplication visible for the first time as e.g. two identical "English" subtitle
> rows — but the duplication itself predates R195 entirely; the old flat picker showed the same 10
> rows for 5 languages, just without grouping to make it obvious.

**Status:** Implemented.

## Bug report / investigation
Live report (photo) via R195's picker: "It's Always Rainy in Pittsburgh" S03E03's Subtitles tab
showed an English entry with **2 versions**, both reading the identical "The full version of
everything spoken." — no way to tell them apart. The user correctly guessed this could be a mixed
embedded+external situation and asked for it to be investigated properly.

Traced end to end against the live library:
- **The source file has exactly one subtitle per language** — confirmed three independent ways: the
  scanned `MediaItem.episodes[].tracks` in `config/jellystructure.db` (5 subrip streams:
  `0:s:0`–`0:s:4`, nor/fin/swe/eng/dan, one each), Jellyfin's own `/Users/{id}/Items/{id}` response
  (`MediaSources[0].MediaStreams`, same 5, all `IsExternal: false`), and the filesystem itself (no
  sidecar `.srt` files next to the `.mkv` — ruling out the "mixed embedded + external file" guess
  directly). The admin's own track list is correct.
- **`MediaSources[0].SupportsDirectPlay: true`** for this title (`mkv` container) — this episode
  direct-plays.
- `PlaybackService.buildSubtracks()` (`PlaybackService.kt:447-492`) gives **every** text subtitle
  stream `deliveryMethod = "external"` with a `.../Subtitles/{index}/0/Stream.vtt` sideload URL,
  unconditionally — no check for whether the file is being direct-played (in which case the
  container already carries that exact stream) or what the client can render in-container.
  `RaviloPlayerAndroid.load()` attaches every non-null-URL `SubTrack` as an external
  `MediaItem.SubtitleConfiguration`, on top of whatever `MatroskaExtractor` already discovers natively
  in the direct-played container — so on this file, `subtitleTracks` (`RaviloPlayerAndroid.kt:253-277`,
  which enumerates every `TRACK_TYPE_TEXT` group from `exo.currentTracks`) reports **10** tracks: the
  5 real languages × 2 (sideloaded + native), the perfectly-even ratio being the tell that this is
  systemic duplication, not real per-language metadata variance.
- **This exact behavior was already documented and deliberately deferred**: R183's "Known adjacent
  issue" section names it, attributes a **4m37s first-play stall** to it on a 26 GB file (Jellyfin
  ffmpeg-extracting every subrip track to VTT during `prepare()`), and proposes the fix implemented
  here as one of two options — left out of R183's scope at the time as "follow-up work with their own
  risk to the R180 picker / R181 track resolution."

## Requirements

### FR-SUB1-1 — A client capability: can this platform render embedded text subs in-container?
`ClientCapabilities` (`shared/.../Models.kt`) gains `supportsEmbeddedTextSubs: Boolean = false`
(conservative default — sideload unless a client actively confirms it doesn't need to). New seam
`ravilo-ui/.../seams/SubtitleCapabilities.kt`: `expect fun supportsEmbeddedTextSubtitles(): Boolean`.
Android actual: `true` (Media3's `MatroskaExtractor` parses embedded SRT/ASS/SSA natively — the same
fact R183's "Known adjacent issue" already established). Wasm actual: `false` (a browser `<video>`
element can't parse an MKV container's embedded text tracks at all, and can't direct-play MKV in the
first place, so this is moot there but stated explicitly rather than left implicit).
`PlayerStore.startSession()` (`PlayerStore.kt`) reports it alongside the existing HDR/AVC capability
seams.

### FR-SUB1-2 — Only skip the sideload when BOTH direct-playing AND the client confirms in-container support
`PlaybackService.startPlayback()` (`PlaybackService.kt:215-280`) reorders: `getPlaybackInfo()`/
`needsTranscode` now resolve *before* `buildSubtracks()` is called (previously the reverse — subtitles
were built without knowing yet whether the item would direct-play or transcode). `buildSubtracks()`
gains an `embedTextSubs: Boolean` parameter computed as `!needsTranscode &&
capabilities.supportsEmbeddedTextSubs`. When true, a text-subtitle stream gets `deliveryMethod =
"embed"`, `url = null` instead of `"external"` with a sideload URL — `RaviloPlayerAndroid.load()`
already skips any `SubTrack` with a null `url` when building `subConfigs` (no client-side change
needed there), so the container's own native track becomes the *only* copy. `restream()`
(`PlaybackService.kt:494-541`, the PGS burn-in re-request path) always passes `embedTextSubs = false`
explicitly — it forces a transcode by construction (`directPlay = false`), so text subs must stay
sideloaded there exactly as before; a transcoded output doesn't preserve the source's embedded subs.

## Invariants
- **A text subtitle is never sideloaded when the client is about to see that exact same stream
  natively in the direct-played container.** The two are never both true at once for the same stream.
- **A transcode always sideloads text subs, unconditionally** (`restream()`, and any
  `startPlayback()` call where `needsTranscode` is true) — a transcoded output doesn't carry the
  source's original embedded subtitle streams, so there is nothing to double.
- **A client that hasn't confirmed in-container text-subtitle support keeps the pre-existing sideload
  behavior** (`supportsEmbeddedTextSubs` defaults to `false`) — this phase only ever *removes*
  redundant sideloading for a client that actively opts in, never silently drops a subtitle a client
  can't otherwise render.

## Out of scope
- **Option 2 from the investigation (defensive client-side dedupe in the R195 picker)** — collapsing
  an embedded+sideloaded pair by comparing MIME type (`application/x-subrip` vs `text/vtt`) as a
  belt-and-braces safety net even if a future case slips past FR-SUB1-2. Not built this pass: FR-SUB1-2
  removes the duplication at its source for the one client (Android) that was ever affected, and
  R195's ordinal-suffix disambiguation (`· 1/2`) already keeps any *other* future duplicate from ever
  rendering as two silently-identical rows again, even without a dedicated dedupe pass.
- **VobSub/DVDSub (`embed`) and PGS (`encode`) subtitle streams** — untouched; they were never
  double-delivered (VobSub already used `deliveryMethod = "embed"`/`url = null` before this phase;
  PGS is never sideloaded as VTT at all, only burn-in transcoded on demand via `restream()`).
- **Pre-extracting text subs in the scan pipeline** (R183's other named option) — would help the
  *transcode* path's VTT-extraction cost too, but is a bigger, separately-risked change; FR-SUB1-2
  alone already eliminates the reported bug and R183's stall for every direct-play case, which is the
  overwhelming majority of this library's subtitle-bearing playback.

## Dev-review addendum (2026-08-10 — implementation notes)
1. Verified live against Jellyfin (`GET /Users/{id}/Items/{id}?Fields=MediaStreams,MediaSources`) that
   `IsExternal: false` and no `Path` on all 5 of this title's subtitle streams, and against the
   filesystem that no sidecar `.srt` exists — ruling out the "mixed embedded + external file" hypothesis
   directly before writing this spec.
2. Verified via `compileKotlinLinuxX64`, `linuxX64Test`, and all 5 Ravilo compile targets. Not yet
   live-retested on-device (deploy is user-initiated) — the way to verify: play this exact episode,
   confirm the Subtitles tab now shows 5 languages with **no** arrow/version-count on any of them
   (single version each), and confirm Jellyfin's server log no longer shows a multi-minute ffmpeg VTT
   extraction burst on this title's first play.

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` — `startPlayback` (reordered),
  `buildSubtracks` (new `embedTextSubs` param), `restream` (explicit `embedTextSubs = false`).
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` — `ClientCapabilities.supportsEmbeddedTextSubs`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/seams/SubtitleCapabilities.kt` — new,
  `expect fun supportsEmbeddedTextSubtitles()`.
- `ravilo-ui/src/androidMain/.../seams/SubtitleCapabilities.kt` — `actual` → `true`.
- `ravilo-ui/src/wasmJsMain/.../seams/SubtitleCapabilities.kt` — `actual` → `false`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerStore.kt` — reports the
  new capability alongside `detectHdrSupport()`/`detectAvcDecoderLimits()`.
- Related: **R55** (the original "sideload every embedded text sub" design this corrects — no spec
  file, `git history` only), **R56** (image-subtitle parity, unaffected), **R183**
  (`phase-R183-dolby-vision-playback.md`, "Known adjacent issue" — names this exact fix as deferred
  follow-up work), **R195** (`phase-R195-same-language-subtitle-picker.md` — the two-level picker
  that surfaced this bug's visibility, and whose ordinal-suffix disambiguation remains the safety net
  for any future duplicate this phase doesn't cover).
