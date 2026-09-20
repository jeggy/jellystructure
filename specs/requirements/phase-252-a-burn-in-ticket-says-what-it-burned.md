# Phase 252 — A burn-in ticket says what it burned, and a burn-in can be undone

> Backend half of [[phase-R282-a-burned-in-subtitle-is-the-only-subtitle]]. Third visit to
> `PlaybackService.buildSubtracks()`/`restream()` after [[phase-161-embedded-subtitle-double-delivery]]
> and [[phase-R209-subtitle-embed-detection-bugs]]. Those two fixed the **ticket's list** (a stream
> listed twice). This one is different: the list is right, and the ticket still does not carry the one
> fact the client needs — *which subtitle is already in the picture*.

**Status:** ✓ Built 2026-09-20 (same day as written), not dev-reviewed, **not deployed, not verified against a live session.** `compileKotlinLinuxX64` clean; `BurnInTicketWireTest` 5/5 (the additive-field wire contract, both directions). ⚠ No `PlaybackService` test harness exists — `restreamWithoutBurnIn` is compile-verified and read-verified only. `startPlayback`'s stream-URL choice moved into a shared `streamUrlFor()` (behaviour-identical, but it is a touched hot path).

## Bug report
Owner, 2026-09-20: *"Subtitles do not really work on movies like Honeyman in Ravilo. Wholphin works
fully. When I choose English subtitles the movie reloads and then I get two subtitles printed at the
same time. This has been reported before, long ago."*

## Investigation (measured, 2026-09-20)
**The file.** `Honeyman (2021)` — UHD HEVC / TrueHD Atmos remux. Jellyfin's `MediaStreams`
(12.1.0, read live):

| Jellyfin index | stream |
|---|---|
| 0 · 1 · 2 | `subrip` **external** sidecars — Danish · Croatian · Serbian |
| 3 | video (4K HEVC HDR) |
| 4 · 5 | audio (TrueHD Atmos 7.1 · AC-3 5.1) |
| 6 · 7 · 8 | **`PGSSUB` embedded** — English (SDH) · French · Spanish |

English exists **only as PGS** — a picture format. (Jellyfin numbers external streams first, so its
index 6 is our catalog's `streamIndex 3`; both name the same track — checked, not assumed.)

**The session**, from the production log:
```
20:11:00 PlaybackInfo: item=9a58…3266 directPlay=false transcode=true
20:11:20 PlaybackInfo(burn-in): item=9a58…3266 sub=6 negotiated=true
```
The title **transcodes** (HLS/TS — no container subtitles survive that), so `embedContainerSubs` is
false, so English is correctly listed `deliveryMethod = "encode"` and da/hr/sr are correctly
sideloaded as VTT. Picking English calls `restream(subtitleStreamIndex = 6)`.

**Jellyfin does exactly what it was asked.** Replaying `restream()`'s own `PlaybackInfo` request:
`TranscodingUrl` carries `SubtitleStreamIndex=6&SubtitleMethod=Encode`, `TranscodeReasons` gains
`SubtitleCodecNotSupported`. English is burned into the pixels. That is the reload, and it is right.

**The second subtitle is ours.** The restream ticket lists da/hr/sr as sideloaded text tracks again
(correct — a viewer may want them later) and says **nothing** about index 6 being in the picture.
The client reloads the same `ExoPlayer` with its text-track selection untouched, so whatever text
subtitle was on before the pick **keeps rendering on top of the burned-in English**. ⚠ *Which* text
track was on in the reported session is inferred, not observed (client state is not logged): the
only text tracks this title has are da/hr/sr, and a Danish-remembering profile auto-selects the
Danish sidecar at start. The mechanism itself is read straight from the code. Client detail and
fix: R282.

Two further defects fall out of the same missing fact:
1. **A burn-in cannot be undone.** `restream()` only knows how to burn. Once English is baked in,
   *Off* and every text subtitle leave it there for the rest of the session (R209 noted this in
   passing: *"no way to undo it short of restarting playback"*).
2. `restream()` negotiates with `ClientCapabilities()` defaults because the route never receives the
   session's capabilities. Harmless while it always burns (that always transcodes); wrong the moment
   it is asked for a stream with **no** burn-in, which may be direct-playable.

**Why Wholphin is fine:** it keeps one subtitle selection and hands it to Jellyfin/its own renderer —
there is no second, independent text-track selection to forget about.

**Why 161 and R209 did not catch it:** both reproduced on direct-played files and fixed the list.
This needs a *transcoding* title whose wanted language is PGS-only **and** a text subtitle already
on — i.e. the household's Danish-by-default viewers picking English on a UHD remux.

## Requirements

### FR-252-1 — The ticket names the burned-in subtitle
`StreamTicket` gains `burned_subtitle_index: Int? = null` — Jellyfin's stream index of the subtitle
encoded into this stream's video, `null` when none. `restream()` sets it on a burn-in ticket;
`startPlayback()` never does. Additive with a default: an older client ignores it
(`ignoreUnknownKeys`), and `encodeDefaults = false` keeps it off the wire when null, so no installed
client sees a new required field (the v1.31 lesson, from the other direction).

### FR-252-2 — `restream` with a negative index means "this stream, no burn-in"
`POST /api/tv/playback/restream` with `subtitle_stream_index < 0` negotiates a fresh ticket for the
same item **at `position_ms`**, with no `SubtitleStreamIndex` sent to Jellyfin, exactly as
`startPlayback()` would: `needsTranscode` from `PlaybackInfo`, `embedContainerSubs` from that and the
capabilities, direct-play or `TranscodingUrl` accordingly. `-1` rather than a nullable field so the
wire type does not change; an older backend handed `-1` passes it to Jellyfin, where `-1` already
means *no subtitle* — degraded, never broken.

### FR-252-3 — `restream` may carry the session's capabilities
`PlaybackRestreamRequest` gains `capabilities: ClientCapabilities? = null`. Used by FR-252-2's
negotiation; absent ⇒ `ClientCapabilities()` as today. **The burn-in branch keeps today's
default-capabilities negotiation unchanged** — it forces a transcode regardless, and this phase does
not alter a path that is measured to work.

### FR-252-4 — Session bookkeeping is unchanged in shape
Both branches go through the same `playbackTracker.started()` supersede/`stopAlreadyArrived` handling
`restream()` already has (phase 180), so an un-burn releases the burn-in encode it replaces.

## Invariants
- **At most one subtitle reaches the screen per session state, and the ticket is sufficient to know
  which one is already in the picture.** A client never has to infer a burn-in from a URL.
- A ticket with `burned_subtitle_index` set is always `direct_play = false`.
- The subtitle **list** is unchanged by this phase (161/R209's rules stand).

## Out of scope
- Delivering PGS without a transcode on an HLS stream (Media3 has no standalone `.sup` path).
- The burn-in stream's audio track: Jellyfin picks its default (`AudioStreamIndex=4` here), not the
  viewer's pick. Real, separate, and its own phase.
- **Why Honeyman transcodes at all — that is the root cause, and it is a bug:** the client omits
  `truehd`/`dts` from its declared audio codecs although it bundles the decoder. Fixed by
  [[phase-R283-declare-the-audio-the-player-can-really-decode]]. This phase and R282 remain necessary
  for every title that transcodes for a *legitimate* reason (177's bitrate ceiling, the web client).

## Verification
- Unit: `restream` branch selection and `burned_subtitle_index` presence/absence on the ticket.
- Live (owner-gated, not done here): Honeyman on the Pixel 9 — Danish on → pick English → one
  subtitle; → Off → none; → Danish → Danish only.
