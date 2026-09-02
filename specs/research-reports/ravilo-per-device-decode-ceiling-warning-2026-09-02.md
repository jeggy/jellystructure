# A per-device "this might not play well" warning — what exists, what's missing

**Date:** 2026-09-02

> Triggered by last night's report: *Until Dawn (2025)* — a 2160p HEVC **REMUX**, Dolby Vision Profile
> 7.6 dual-layer + HDR10+, **82 Mbps video bitrate** — stuttered badly on stue TV and was abandoned
> mid-watch. Investigation found the cause matches the pattern from
> `stue-tv-4k-playback-stutter-2026-08-28.md`: the Sony BRAVIA VH21's decoder is rated to **60 Mbps**,
> and this file is ~37% over that. The owner's proposed fix: **sample/record the maximum bitrate a
> device can actually handle, persist it on the jellystructure backend, and warn on Ravilo's (device-
> specific) media detail page before the viewer presses play.** **Scope: research only, no fix
> implemented** — this feeds a design pass, then a spec, then a build.

**Verdict:** most of the *mechanism* this proposal wants already exists — **Phase 177** and **R216**
(designed and built 2026-08-28, the day after the *Longlegs* incident) already probe each device's real
decoder bitrate ceiling and use it to force a transcode instead of a broken direct play. But three things
keep last night from being the case that mechanism was built for:

1. **Last night's playback wasn't Ravilo.** The session was **Wholphin**, a third-party Jellyfin client,
   which negotiates directly against Jellyfin with its own device profile. Phase 177/R216 only run inside
   jellystructure's own `/api/tv/playback/*` path — they cannot see or influence a Wholphin session at
   all, deployed or not. (§2)
2. **Nothing persists the ceiling anywhere a detail page could read it before a session starts.** Today
   the decode ceiling is a value the client computes fresh every `PlaybackInfo` call and hands to the
   server for that one negotiation; it is never written to `ravilo_device` or anywhere else queryable
   at rest. (§3)
3. **The proposal is a direct, deliberate reversal of a stated product invariant.** R216, written five
   days ago: *"No user-visible setting, ever... Ravilo does not expose player choice, bitrate, buffer or
   quality controls... this is a product constraint, not an implementation preference."* That line
   needs to be revisited on purpose, not quietly stepped around. (§5)

None of this means the idea is wrong — it means the design pass has more to resolve than "draw a
warning badge," and this report exists to hand it that context up front.

---

## 1. What Phase 177 / R216 already do (2026-08-28, implemented, not yet on-device verified)

Full detail in `specs/requirements/phase-177-delivery-aware-playback-negotiation.md` and
`specs/ravilo/requirements/phase-R216-player-delivery-resilience.md`. The shape that matters here:

- **The device's decode ceiling is not "learned" — it's asked for, and the answer is exact.**
  `detectDecoderLimits()` (`ravilo-ui/src/androidMain/…/HdrCapabilities.kt`) walks
  `MediaCodecList`/`VideoCapabilities.getBitrateRange()` for the codec the device will actually select,
  and gets back the manufacturer's own declared number. On stue TV this is confirmed (by direct ADB
  read) to be exactly the 60 Mbps the stutter investigation found. **This is a deterministic hardware
  fact, re-derivable identically every time** — there is no sampling, averaging, or history needed to
  know it. R216's own open question #1 flags the one real unknown: whether *other* OEM decoders in a
  real fleet report honest numbers, or optimistic/placeholder ones — untestable with only one device
  in the house.
- This ceiling, plus a declared network link (`detectLinkState()` — kind + Mbps, Wi-Fi frequency band
  included), is sent as part of `ClientCapabilities` at the start of every Ravilo playback session
  (`PlayerStore.kt`).
- The server (`JellyfinClient.deviceProfile()`) turns it into a per-codec `VideoBitrate` condition with
  a 0.9 safety margin, and caps `MaxStreamingBitrate` by the link estimate too. Above the ceiling,
  Jellyfin transcodes instead of direct-playing — **confirmed live** against the real Jellyfin 10.11.11
  instance the same day it was designed (phase-177 doc, "Open questions" §1–2): a 93 Mbps title correctly
  triggered a hardware NVENC re-encode once the condition was in place.
- **The outcome is already recorded, per session** — `POST /api/tv/playback/qoe` → the `playback_qoe`
  table: dropped frames, rebuffer count/duration, bandwidth estimate, the decoder actually used, whether
  it was a direct play, and the link kind/Mbps at the time. Surfaced today only on the admin
  **Activity** page and the **Users & devices** settings tab — diagnostic, not viewer-facing.

**So the "record what a device can handle" half of the ask is already built, verified in principle, and
would already have prevented last night's stutter — for a device running Ravilo.** It hasn't been
on-device verified against a real Wi-Fi dip or rebuffer yet (both phases' own "Open questions" say so),
and it's unclear from here whether the Android build carrying it has actually been installed on either
stue-TV Ravilo instance since 2026-08-28 — worth checking before assuming it's live anywhere.

## 2. Why last night wasn't covered anyway: Wholphin isn't Ravilo

The session that stuttered was:

```
Device: Stue TV | Client: Wholphin 1.0.7-0-g2b9af1e8
Item: Until Dawn | Path: …Until.Dawn.2025.REPACK.UHD.BluRay.2160p…DV.HDR10P.HEVC.HYBRID.REMUX…mkv
PlayState.PlayMethod: DirectPlay
```

Wholphin is an independent third-party Jellyfin client. It authenticates and negotiates
`/Items/{id}/PlaybackInfo` **directly against Jellyfin**, building its own `DeviceProfile` with its own
declared capabilities — jellystructure's `JellyfinClient.deviceProfile()` code never runs for that
session; it only runs for playback jellystructure's own backend initiates on Ravilo's behalf
(`tv/PlaybackService.kt`). There is no code path by which jellystructure can inject a smarter device
profile into another vendor's app's own request to Jellyfin.

**Consequence for this design:** whatever gets built here can only ever warn about — and only ever
protect — playback that goes through **Ravilo**. It has and can have zero effect on Wholphin, the
official Jellyfin apps, or anything else on that TV that talks to Jellyfin directly. Worth stating
plainly in the design brief so it isn't read later as "why didn't the warning fire" for a non-Ravilo
session. (The user's request already scopes this to "the media details page on ravilo" — so this is
confirmation, not a course change.)

## 3. The actual gap: nothing persists a ceiling anywhere queryable at rest

`ravilo_device` (`src/commonMain/sqldelight/dev/jellystructure/db/RaviloDevice.sq`) — one row per
(device, user) pair — carries pairing/session identity (tokens, library ACL, last-seen) and **no
decode-ceiling or link column at all**. The value R216 computes lives and dies inside one
`PlaybackInfo` request/response cycle.

`playback_qoe` (`src/commonMain/sqldelight/dev/jellystructure/db/PlaybackQoe.sq`) is the closest thing
to a history: keyed `(device_id, jellyfin_id, play_session_id)`, indexed by `device_id` and
`updated_at`, holding `direct_play`, `link_kind`/`link_mbps`, `dropped_frames`, `rebuffer_count`/
`rebuffer_ms`, `video_decoder`. But it is explicitly **diagnostic, not a ledger** — retention-pruned
(90 days proposed), never aggregated per device, and by R216's own invariant never read by anything
viewer-facing.

So today, a media detail page has no way to ask "what is this device's known-safe bitrate" — that
number is computed fresh by the device itself at the moment `PlaybackInfo` is called, and thrown away
right after. For a pre-playback warning to exist, **something** has to start persisting it — either the
raw decode ceiling (§1, deterministic — arguably needs no "sampling" at all, just a place to write the
number down after the fact so a detail-page request can read it back), or an aggregate derived from QoE
history (genuinely probabilistic — link quality *does* vary night to night in a way the hardware ceiling
doesn't; this is the piece that would actually benefit from "sampling over time" in the way the request
describes).

**These are two different mechanisms with two different trust models, and the design should treat them
as separate inputs rather than one blended "device ceiling":**

| | Decode ceiling | Link/QoE history |
|---|---|---|
| Source | `MediaCodec.getBitrateRange()`, asked once, same answer every time (on this SoC family) | Accumulated `playback_qoe` rows — varies with Wi-Fi conditions, network congestion, what else is on the spindle |
| Confidence | High immediately — no history needed (modulo R216's "some OEM decoders may lie" open question) | Needs enough sessions to mean anything; a single rebuffer could be a one-off (a phone walking past the router) not a device fact |
| What a "wrong" ceiling costs | Server transcodes unnecessarily — wasted CPU, still watchable | A warning that's wrong is either an unnecessary scare or a false all-clear — costs trust in the feature itself |

## 4. The file side: bitrate isn't in jellystructure's own scanned model today

For a warning to compare "this file" against "this device," it needs the file's own video bitrate.
Checked directly: jellystructure's scanned per-track model
(`src/commonMain/kotlin/dev/jellystructure/model/Media.kt`, `data class Track`) carries `codec`,
`language`, `title`, `default`/`forced`, `width`/`height`, `videoRange` (a simple SDR/HDR flag) — **no
bitrate field.** `FfprobeRunner` (the scan-time ffprobe wrapper) is presumably already reading bitrate
from the same probe that fills the rest of `Track` (ffprobe reports it in the same stream JSON as
codec/resolution) but it is not currently carried through to the stored model.

Two sourcing options for a design to choose between, with different cost:

- **Scan-time:** add `bitRate: Int?` to `Track`, backfill on next scan, plus a small DB
  migration if it needs its own indexed column (it likely doesn't — `Track` rides inside the existing
  `media.json` blob, so this may be a model-only change, no `.sqm` needed — confirm before assuming).
  Available offline, no live Jellyfin dependency, but stale until the next scan for anything just added.
- **Live, at render time:** Jellyfin's own `MediaSources[].MediaStreams[].BitRate` already carries this
  (confirmed live moments ago against both Nosferatu and Until Dawn — see §1's numbers) and jellystructure
  already talks to Jellyfin per-item elsewhere. Always current, costs a live call the detail-page fetch
  doesn't make today (need to check whether `DetailStore` already fetches `MediaSources` for some other
  reason and could piggyback).

## 5. The hard constraint this proposal wants to reverse

R216 (`specs/ravilo/requirements/phase-R216-player-delivery-resilience.md`, "Invariants"):

> **No user-visible setting, ever.** Ravilo does not expose player choice, bitrate, buffer or quality
> controls. Everything here is negotiated or measured automatically; the viewer's experience is that
> playback simply works. This is a product constraint, not an implementation preference.

And separately, R180 (`specs/ravilo/requirements/phase-R180-audio-subtitle-picker-overhaul.md`,
FR-RV-ASP1-2), binding on the player generally: **no codec names, no delivery-method cues** — a viewer
never learns whether they're getting direct play, remux or transcode, or that one is slower than
another. R218 (player buffering states, 2026-08-29) inherited this same constraint for loading copy.

A pre-playback "this may not play well on this device" warning is, by definition, a quality/capability
signal reaching the viewer — the exact thing both of those were written to prevent. This isn't a reason
to drop the idea; last night is a real, repeatable, user-visible failure (the third documented stutter
incident on this TV in three weeks) and "playback simply works" is not actually true for this file today.
But it means the design pass has a product decision to make explicitly, not by drift:

- **A — keep the invariant, don't build a warning.** Rely entirely on Phase 177/R216 to keep improving
  (verify on-device, tune the 0.9 margin and Wi-Fi fraction from real QoE data) so the transcode fallback
  never regresses to a broken direct play. Push people toward Ravilo over Wholphin for exactly this
  reason. Costs: the *transcode itself* still has a real cost the owner will notice (the buffering-states
  report measured ~20s cold start for a heavy 4K NVENC re-encode) — "it plays, but slowly" isn't nothing.
- **B — a narrow, outcome-only heads-up.** Warn in plain language ("this title may not play smoothly on
  Stue TV") **without ever naming bitrate, codec, HDR format, or the word transcode** — preserving the
  *spirit* of "no quality controls" (nothing to configure, no numbers, no jargon) while giving the viewer
  a chance to choose a different night/device rather than being surprised mid-movie. This is the shape
  R153's certification badge and R190's people-filter already use: a plain-language, server-resolved
  signal with no raw numbers surfaced.
- **C — full reversal.** Show real numbers (bitrate, "may transcode," device capability) admin-style even
  inside Ravilo. Not recommended without a much stronger reason — it's a bigger philosophical change than
  this one incident argues for, and undoes work R180/R218 already did deliberately.

This report doesn't pick one — that's the design pass's call — but the choice needs to be made on
purpose and recorded, the way R216's own invariant was.

## 6. Open questions for the design pass

1. **Confidence threshold.** Is the device's `MediaCodec`-reported ceiling trustworthy enough to warn
   from on its own (§1 — deterministic, arguably yes), or should the warning wait for corroborating QoE
   history (§3 — probabilistic, arguably more honest but means a *new* device/title combination never
   warns the first time, which is exactly when last night's incident happened)?
2. **Link quality is the volatile half.** The decode ceiling doesn't change; Wi-Fi conditions do
   (stue TV was seen band-steering to 2.4 GHz mid-evening in the 2026-08-27 investigation). Does a
   warning based on link state need to be time-scoped ("recently struggled") rather than a permanent
   per-device fact, and does that make it a *worse* signal (flickering in and out) rather than a better one?
3. **Where does it live?** Hero banner (always visible, competes with poster/synopsis), a badge near the
   existing `CertBadge`/IMDb-rating chip row (§ precedent: `MovieDetailScreen.kt` already renders one
   server-resolved badge line), or a one-time confirm-to-continue gate at the moment Play is pressed
   (precedent: Phase 154's pre-run "untick slow steps" dialog, though that's an admin surface not Ravilo)?
4. **Copy tone**, matching R180/R218's constraint: what plain-language sentence describes "your TV's
   decoder may not keep up with this file's picture quality" without using the words bitrate, codec,
   transcode, Dolby Vision, or HEVC? Does it name the *device* ("Stue TV") the way multi-device households
   need, or stay generic ("this TV")?
5. **Multi-episode combined files** (Phase 149) — a combined `S01E01–E03` row is one physical file; does
   the warning apply once to the row, or does it need to know the file didn't change per sub-episode?
6. **Does this need an action at all**, given Ravilo has literally no quality/playback controls to offer
   as an alternative (§5's constraint)? A warning with no possible response is just anxiety — "acknowledge
   and continue" may be the only honest action available, which argues for direction B over a blocking
   gate.
7. **Should the admin side (Users & devices, where Phase 177 QoE already surfaces) show the *same*
   per-device ceiling record this introduces**, so there's one persisted fact instead of two representations
   of it drifting apart?
8. **Non-Ravilo playback stays invisible, always** (§2) — should the detail page say so anywhere, or is
   silence on this the right call (most viewers won't be running two different Jellyfin clients on the
   same TV)?

## 7. Source references

- `specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md` — the original investigation;
  §2.2 is the 60 Mbps hardware measurement this whole thread traces back to.
- `specs/requirements/phase-177-delivery-aware-playback-negotiation.md` — server negotiation, the
  `VideoBitrate`/`MaxStreamingBitrate` mechanism, the `playback_qoe` ingest endpoint (FR-177-5).
- `specs/ravilo/requirements/phase-R216-player-delivery-resilience.md` — client capability probing
  (`detectDecoderLimits()`, `detectLinkState()`), and the "no user-visible setting, ever" invariant §5
  quotes directly.
- `specs/ravilo/requirements/phase-R180-audio-subtitle-picker-overhaul.md` (FR-RV-ASP1-2) — the
  no-delivery-method-cues constraint, also binding here.
- `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md` — measured real cost of
  the transcode fallback (~20s cold start), and the precedent for a "research feeds a design pass" report
  on this exact feature family.
- `src/commonMain/sqldelight/dev/jellystructure/db/RaviloDevice.sq` — per-device row, no ceiling column.
- `src/commonMain/sqldelight/dev/jellystructure/db/PlaybackQoe.sq` — the existing QoE ledger, session-scoped
  and pruned.
- `src/commonMain/kotlin/dev/jellystructure/model/Media.kt` (`Track`, `:66-82`) — confirms no bitrate
  field in jellystructure's own scanned model today.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/MovieDetailScreen.kt` — the
  existing server-resolved badge pattern (`CertBadge`/`detail.ratingBadge`, Phase 106/R153) a new badge
  would follow.
- Live evidence gathered for this report: Jellyfin `/Sessions` and `/System/ActivityLog/Entries` (both
  clients' real session data, 2026-09-02), Jellyfin `/Items?searchTerm=...` for both titles' real
  `MediaStreams`/`BitRate` values.
- Related: **Phase 178** (background I/O — unaffected by this), **Phase 154** (pre-run confirm-to-continue
  precedent), **R153**/**R190** (server-resolved, plain-language badges — the UX shape §6.3 references).

## 8. Suggested next step

Feed §5's three options and §6's open questions into a design pass (a `Directions.html` exploration, same
pattern as R218's cold-start directions or R221's genre chips) before writing a phase spec. Likely splits
into an admin-side persistence piece (a place to write the ceiling down + expose it next to the existing
Phase 177 QoE view) and a Ravilo-side piece (the detail-page treatment) — but that split, and which
numbers they'd take, is a call for after the design pass, not before it. Next unassigned numbers as of
this report: **185 / R222** — reverify against `STATUS.md` and `ls specs/*/requirements/` before filing,
per usual.
