# Phase R209 — Subtitle delivery: external text subs dropped, PGS double-delivered (bug fix)

> [[phase-161-embedded-subtitle-double-delivery]] (FR-SUB1) taught `buildSubtracks()` to skip
> sideloading a text subtitle when the file direct-plays and the client confirms it can already
> render that same stream natively from the container (`embedTextSubs`/`ClientCapabilities.
> supportsEmbeddedTextSubs`). That phase's own "Out of scope" section asserted PGS subtitles "were
> never double-delivered... only burn-in transcoded on demand via `restream()`" — true at the time
> for the *sideload* mechanism it was investigating, but wrong as a general claim: PGS is a container
> subtitle codec too, and `RaviloPlayerAndroid`'s own doc comment (line 43-44) says Media3's
> `MatroskaExtractor` parses embedded PGS tracks from MKV natively, same as it does SRT/ASS/SSA — the
> exact mechanism FR-SUB1 exists to guard against. Separately, FR-SUB1's own `embedTextSubs` branch
> never checked `JellyfinMediaStream.isExternal` — a field that already existed on the model before
> and after that phase — so it silently drops any text subtitle that's actually a sidecar file next
> to the video (never muxed into the container at all, so `MatroskaExtractor` can never surface it as
> a substitute).

**Status:** Implemented.

## Bug report
Live report while watching "Pinocchio": the Subtitles picker showed Italian and German each with
**2 versions**, but English/Danish/etc. didn't appear in the list at all. Picking the 2nd version of
either duplicated language made English/Danish appear for the first time — but picking one of them
then showed **two subtitle lines overlaid on screen at once**, and this remained the only way to get
English subtitles to work at all.

## Investigation
Two independent bugs in `PlaybackService.buildSubtracks()` (`PlaybackService.kt:465-522`), both on
the direct-play + client-confirms-in-container-support path FR-SUB1 introduced:

**Bug A — an external (sidecar-file) text subtitle is wrongly treated as already-in-container and
dropped entirely.**
```kotlin
(s.isTextSubtitleStream || isTextSubCodec(s.codec)) && embedTextSubs -> SubTrack(
    ..., url = null, deliveryMethod = "embed",
)
```
This branch fires whenever `embedTextSubs` is true (direct play + client capability), for *every*
text-coded stream — with no check of `s.isExternal`. `RaviloPlayerAndroid.load()` builds
`subConfigs` via `subtitles.mapNotNull { sub -> val url = sub.url ?: return@mapNotNull null; ... }`
(`RaviloPlayerAndroid.kt:80-99`) — a `null` url is silently dropped, on the assumption
`MatroskaExtractor` will expose the identical stream from the container instead. That assumption
only holds for a stream that's actually muxed in. Pinocchio's English/Danish tracks are external
sidecar files (Jellyfin still reports them as ordinary `Subtitle`-type `MediaStreams`, just with
`IsExternal: true`) — never part of the container, so `MatroskaExtractor` never surfaces them, and
they vanish from `subtitleTracks` and the picker with no sideload URL to fall back on.

**Bug B — PGS is unconditionally routed to burn-in, even when the client already renders it natively
in-container, causing a double listing and (via the forced restream) a permanently double-rendered
subtitle.**
```kotlin
codec != null && isPgsSubCodec(codec) -> SubTrack(
    ..., url = null, deliveryMethod = "encode",
)
```
Unlike the `isEmbedImageSubCodec` (VobSub/DVDSub) branch directly above it — which correctly treats
that format as already-native, no burn needed — every PGS stream gets `deliveryMethod = "encode"`
unconditionally, regardless of whether the client can already decode it from the container. On
Android it can (`RaviloPlayerAndroid.kt:43-44`), so `MatroskaExtractor` exposes the PGS track as an
ordinary native `TRACK_TYPE_TEXT` group *and* the ticket separately lists it as an "encode" candidate
— the same language shows twice in `subVersionOptions` (`PlayerScreen.kt:368`), one native, one
requiring a burn-in restream.

Picking that "encode" duplicate calls `store.restreamWithSub()` → `PlaybackService.restream()`
(`PlaybackService.kt:525-574`), which forces a real transcode with that subtitle **permanently baked
into the video pixels** for the rest of the session — there is no way to undo it short of restarting
playback. Because `restream()` fetches a fresh `StreamTicket` (a fresh `itemDetail` read + fresh
`buildSubtracks()` call), and Bug A only manifests when `embedTextSubs` is true (the *original*
`startPlayback()` ticket, always direct-play here since Pinocchio direct-plays) — the **restream
ticket's own `buildSubtracks()` call always passes `embedTextSubs = false`** (`PlaybackService.kt:540`,
correct and unchanged: a transcode never preserves the source's original embedded streams) — so it's
the first ticket in the whole session where English/Danish's sidecar files get a real sideload URL
instead of being dropped. That's why they only ever "appeared" after triggering a burn-in restream of
an unrelated language. Selecting English afterward (a native `selectSubtitleTrack` call) only adds an
ExoPlayer text overlay on top of the video — it does nothing to the German/Italian subtitle already
burned into that same video's pixels by the restream, hence the two overlaid lines.

Fixing both bugs together removes the forced detour entirely: with Bug A fixed, English/Danish get a
real sideload URL on the very first (direct-play) ticket, so they show up in the picker immediately;
with Bug B fixed, Italian/German's PGS tracks are native-only (like VobSub already is) with no
"encode" duplicate to accidentally pick, so nothing ever forces an unwanted burn-in restream in the
first place.

## Requirements

### FR-RV-R209-1 — A text subtitle is only treated as embed-and-drop when it's actually in the container
`buildSubtracks()`'s embed-text branch gains `&& !s.isExternal`. An external text stream now always
falls through to the existing sideload (`deliveryMethod = "external"`) branch below it, exactly as it
did before FR-SUB1 existed, regardless of `embedTextSubs`.

### FR-RV-R209-2 — PGS gets the same in-container capability gate text subs already have
The PGS branch is split the same way FR-SUB1 split text subs: `deliveryMethod = "embed"` (no burn, no
duplicate) only when the file direct-plays, the client has confirmed it decodes container subtitles
natively, and the stream isn't external; `deliveryMethod = "encode"` (burn-in via `restream()`,
unchanged) otherwise. This reuses the existing `embedTextSubs` capability signal rather than adding a
new one — `ClientCapabilities.supportsEmbeddedTextSubs`/`RaviloPlayerAndroid`'s doc comment already
establish that Android's "yes" answer comes from `MatroskaExtractor`, which decodes *both* text and
PGS tracks from the same container by the same mechanism, so one flag honestly covers both. The
parameter is renamed `embedTextSubs` → `embedContainerSubs` at its declaration and both call sites
(`startPlayback`, `restream`) to say what it now actually gates; no behavior change to text subs from
the rename itself.

## Invariants
- **A subtitle stream is never silently dropped.** Every `MediaStream` of type `Subtitle` resolves to
  exactly one `SubTrack` with either a real sideload URL, a native in-container "embed" (no URL
  needed because the client already gets it for free), or an "encode" burn-in candidate — never a
  `null`-URL "embed" for a stream the client cannot actually obtain natively.
- **`embedContainerSubs` (renamed from `embedTextSubs`) governs both text and PGS identically** —
  whichever codec, the same three preconditions (direct play, client confirms native support, stream
  not external) decide "native, no duplicate" vs. "sideload/burn," matching FR-SUB1's original
  invariant, just no longer scoped to text codecs only.
- **`restream()` is unaffected** — still always passes `embedContainerSubs = false` (a transcode
  never preserves the source's original embedded streams, text or PGS), so its own ticket's
  subtitles are unchanged by this phase.

## Out of scope
- **VobSub/DVDSub (`isEmbedImageSubCodec`) has the identical unconditional-embed bug shape** — it
  returns `deliveryMethod = "embed"`/`url = null` with no `embedTextSubs`/capability/`needsTranscode`/
  `isExternal` gate at all, unlike both text subs (fixed by FR-SUB1) and PGS (fixed here). On a
  transcoding session, an external client, or an external VobSub/DVDSub stream, this would silently
  drop the same way Bug A did. Found during this investigation but **not fixed** — no live report has
  ever surfaced it (this repo's library apparently has no such case yet, or no one has hit it), and
  fixing three codec branches' worth of gating logic at once in an unverified direction is a bigger
  and differently-risked change than the two live-reported bugs this phase closes. Flagged here the
  same way R183 flagged FR-SUB1 itself as deferred follow-up work.
- **Client-side defensive dedupe in the picker** (R195's ordinal-suffix disambiguation already covers
  any future duplicate that slips past a server-side fix like this one) — unchanged, still the
  fallback safety net, not touched by this phase.
- **Un-baking a subtitle already burned into a still-playing restreamed video** — with Bug B fixed,
  nothing in the picker offers a PGS "encode" duplicate for a stream the client can already render
  natively, so the forced-restream path this bug report hit shouldn't be reachable for this class of
  title anymore. A general "undo a burn-in restream without restarting playback" capability is a
  separate, larger feature and isn't attempted here.

## Dev-review addendum (2026-08-24 — implementation notes)
1. Verified via `compileKotlinLinuxX64` + `linuxX64Test`, and all 5 Ravilo compile targets
   (`:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
   `:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`,
   `:ravilo-phone:compileDebugKotlin`) — all pass, no new warnings introduced.
2. **Not yet live-retested on-device** — backend restart and app deploy are user-initiated (this was a
   live bug report mid-playback; investigation and fix were done without restarting the backend or
   touching any TV/device, per explicit instruction). The way to verify once deployed: replay
   Pinocchio, confirm the Subtitles picker shows Italian/German with a **single** version each (PGS,
   native, no arrow) and English/Danish appear immediately on the very first (direct-play) ticket with
   no need to trigger a restream first.

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` — `buildSubtracks` (both
  branches), `startPlayback` (renamed local), `restream` (renamed call-site argument).
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerAndroid.kt` —
  `load()`'s `subConfigs` (the null-url-drops silently), doc comment establishing Media3 decodes PGS
  natively from MKV.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` —
  `subVersionOptions`/`subGroups` (where the PGS duplicate showed up as a second picker version),
  `choosePick()`'s `store.restreamWithSub()` branch (the forced burn-in path).
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt:597-604` — `isTextSubCodec`,
  `isEmbedImageSubCodec`, `isPgsSubCodec` (codec classification, unchanged).
- Prior related spec: `specs/requirements/phase-161-embedded-subtitle-double-delivery.md` (FR-SUB1 —
  the text-sub half of this same mechanism, and the spec whose "Out of scope" section's PGS claim
  this phase corrects).
