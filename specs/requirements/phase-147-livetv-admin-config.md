# Phase 147 — Live TV configuration in jellystructure (FR-LTV1)

> Jellyfin already supports **Live TV** (tuners + program guide, set up in Jellyfin). This phase lets
> jellystructure **surface that Live TV into Ravilo** and organize how it appears — it does **not** manage
> tuners or guide data. The operator picks which channels appear in Ravilo, sets their number, logo &
> order, chooses the guide refresh cadence, and handles the fact that Jellyfin's channel lineup changes
> over time. The TV-side viewing experience is **[Phase R177](../ravilo/requirements/phase-R177-livetv-viewing.md)**.
> Design mockup: **`design/app/livetv.html`** (new "Live TV" item in the Ravilo sidebar section, Phase 148).

## Goal
An admin can enable Live TV in Ravilo, curate the channel lineup (show/hide · number · logo · order ·
category), set the EPG refresh cadence, and see + resolve lineup changes — all against Jellyfin's Live
TV, which stays the source of truth for tuners and guide.

## Current state (verified)
- jellystructure has **no Live TV surface**. Ravilo streams library media through `/api/tv/**` brokered
  to Jellyfin; there is no channel/guide concept anywhere.
- Jellyfin exposes Live TV over its API: `GET /LiveTv/Channels`, `GET /LiveTv/Programs` (the guide),
  channel images, and a stream endpoint per channel. Tuners + guide are configured in **Jellyfin ▸ Live TV**.
- The constitution holds: jellystructure is the metadata/organization layer; Ravilo streams from Jellyfin.
  Live TV fits that model — jellystructure reads Jellyfin's channels/guide and stores only *presentation*
  overrides; the actual live stream is brokered like any other playback.

## Requirements

### A. Connection & status
1. A **Live TV** admin page (its own item in the Ravilo sidebar section — Phase 148) with a master
   **"Live TV in Ravilo"** enable toggle, a connection/status badge (reachable via Jellyfin), a
   **Test connection**, and a **Refresh from Jellyfin** action.
2. A status summary: channels discovered, channels shown in Ravilo, guide-data kind, last-synced time.
3. A persistent note that tuners + EPG are managed in Jellyfin; jellystructure never touches the tuner.

### B. Channel lineup (the core)
1. Read the channel list from Jellyfin and present it as an editable lineup. Per channel, jellystructure
   stores **presentation overrides keyed by the Jellyfin channel id** (never a positional index):
   `shown` (appears in Ravilo), `number`, `order`, `logo` (use Jellyfin's channel logo; allow an override),
   `category` (News · Sport · Kids · … — operator-assigned grouping, since Jellyfin categories are sparse).
2. UI: a table with drag/`▲▼` reorder, a **show-in-Ravilo** toggle, editable number, logo chip, category,
   and a per-channel **guide** indicator (has EPG data or not). Filters (all · shown · hidden · new ·
   unavailable) + search.
3. Channels are read-only facts from Jellyfin; only the overrides are jellystructure-owned. Nothing here
   edits Jellyfin.

### C. Lineup changes over time (required — the lineup is not static)
1. On each sync, diff Jellyfin's current channels against the stored overrides:
   - **New** channels (present in Jellyfin, no override yet) default to **hidden** and are badged
     "new since sync" — so the Ravilo layout never changes on its own when the provider adds channels.
   - **Removed** channels (an override exists but the channel is gone from Jellyfin) are flagged
     "no longer in Jellyfin" and offered for one-click removal from the layout; until removed they must
     degrade gracefully everywhere (guide/Home/player skip them).
2. A "Lineup changed" banner summarizes `N new · N unavailable` with **Show new** / **Remove missing**
   bulk actions.

### D. Guide (EPG)
1. **EPG source** selector (Jellyfin's guide by default; optional custom XMLTV URL) and a **refresh
   cadence** (15 min / hourly / 6 h / daily) governing how often jellystructure re-pulls now/next + the
   full schedule for Ravilo.
2. No guide *authoring* — jellystructure only chooses source + cadence and caches the result.

### E. Per-user access
1. Which users get Live TV, and which channels each may see, is a **Ravilo-config** concern (global vs
   per-user, like the rest of the Ravilo layout) — see Phase 148 / R177. This phase is the global lineup +
   connection; it only needs to expose the data the per-user layer filters.

### F. How Live TV surfaces in Ravilo (no top-nav tab)
1. Live TV is **not** exposed as a Ravilo top-nav tab. It's surfaced on the Ravilo **Home** — a Home
   **“On now”** row (with a configurable position) and/or a **Live TV collection** in the rail.
2. These placement controls live in the **Ravilo Home layout editor** (Phase 148, `?tab=layout` → a
   “Live TV on Home” section), **shown only when Live TV is enabled on this page**. Disabling Live TV here
   removes that section — and Live TV — from Home. This page owns the *connection + channel lineup*; the
   Home editor owns *placement*. The TV renders it per **R177 §A**.

## Non-goals
- **No tuner or guide management** — HDHomeRun / M3U / XMLTV setup stays in Jellyfin.
- **No DVR / recordings** in v1 (live viewing only).
- **No transcoding/stream config** — live playback is brokered through the existing playback path.
- No per-channel parental logic here (rides the existing Phase 142 user policy on the Ravilo side).

## Acceptance
- With Jellyfin Live TV connected, the Live TV page lists Jellyfin's channels; toggling show/hide, number,
  order, logo and category persists as overrides keyed by channel id and drives what Ravilo shows.
- Adding a channel in Jellyfin surfaces it as **new + hidden** (Ravilo unchanged until shown); removing one
  flags it **unavailable** and it disappears from Ravilo once pruned.
- EPG source + cadence are configurable; the guide Ravilo renders reflects them.
- Everything is read-only against Jellyfin; no tuner/guide edits originate here.
- Live TV surfaces per the placement config (Home “On now” row and/or a Live TV collection) — **never as a
  Ravilo top-nav tab**.

## Status note
Design-authored, `Planned`, not yet dev-reviewed. Exports to
`specs/requirements/phase-147-livetv-admin-config.md`; `scripts/check-phases.sh` will flag it for a
`STATUS.md` row. **Next admin number after this is 148.**

## Dev-review addenda (2026-07-10)
Verified against the **live Jellyfin** (`jellyfin.example.net`) and the backend code. Headline: **this is
buildable and end-to-end testable** — this Jellyfin already serves 7 Live-TV channels + a 1658-program
7-day guide — but several API assumptions need correcting, and the feature is fully greenfield (a
whole-repo grep for `LiveTv|Program|epg|guide|LiveStream` returns nothing).

**A. Live TV is present and testable, but there are NO tuners — do not gate on tuners.** `GET /LiveTv/Info`
returns `IsEnabled:true` with **`Tuners:[]`**, yet `GET /LiveTv/Channels` returns **7 channels** (DR
Ramasjang, DR1, DR2, DRTV, DRTV Ekstra, KVF 1, KVF 2 — all with `ImageTags.Primary` logos) and
`GET /LiveTv/Programs` returns **1658** guide entries over a 7-day window (`/LiveTv/GuideInfo`). This is
an **M3U/HLS-provider** setup, not hardware tuners. §A2's status summary and §A3's "tuners managed in
Jellyfin" note must **not** imply a tuner exists — gate "Live TV available" on **channel count > 0**, not
on tuners.

**B. Jellyfin gives channels NO number and NO category — both overrides are genuinely required (validates
§B), but there is nothing to seed a default from.** A channel object carries `Name, Id, ChannelId,
ChannelType, MediaType, ImageTags.Primary, CurrentProgram` — but **no `Number`/`ChannelNumber`**. So §B1's
stored `number` override is necessary; its default must be **derived** (e.g. the order index), since
Jellyfin supplies none. Likewise **programs carry no category data**: across 400 sampled guide entries
only `IsSeries` was ever set — zero `IsKids/IsNews/IsSports/IsMovie`, zero `Genres`. §B1's operator-
assigned `category` is therefore not merely "since Jellyfin categories are sparse" — they are **absent**;
category is 100% jellystructure-owned. (Downstream consequence for R177's per-program filter chips — see
that spec's addendum §B.)

**C. `CurrentProgram` is embedded on each channel — the "On now" data needs no separate Programs fetch.**
Each channel embeds `CurrentProgram {Name, StartDate, EndDate, RunTimeTicks, ChannelId, IsSeries}`. So the
Home "On now" row (§F, R177 §B) and the player channel bar render now-playing + live-progress from the
single `/LiveTv/Channels` call; only the full guide grid (R177 §C) needs `/LiveTv/Programs`.

**D. Live playback is an infinite HLS stream with an explicit open/close lifecycle the VOD path lacks.**
Channel `PlaybackInfo` returns one MediaSource: `Protocol:Http`, `Path: …/master.m3u8`,
**`IsInfiniteStream:true`, `RequiresOpening:true`, `RequiresClosing:true`**. Jellyfin requires
`POST /LiveTv/LiveStreams/Open` before, and a close after — a lifecycle `PlaybackService.startPlayback`
(VOD: resume position, `buildSubtracks` MediaStreams walk, stop-watchdog position tracking) does **not**
implement and must not reuse verbatim. A live tune is a **sibling method**, not a branch of
`startPlayback`. 147 must expose the tune/stream endpoint R177's player consumes.

**E. Greenfield + a hard naming dependency on Phase 148.** No `/LiveTv/*` client methods and no live
PlaybackService path exist. New `JellyfinClient` methods must reuse the `OutboundHttp.withPermit`
FD-guarded pattern (mandatory — the FD_SETSIZE ceiling). Storage for the per-channel override map (keyed
by Jellyfin channel id): a new **`21.sqm`** table (next migration is 21) *or* a JSON store (`JsTagStore`
pattern) — but **if a JSON/standalone store is used, its write path must call
`TvEventBus.notifyConfigChanged`/`notifyGlobalConfigChanged` explicitly**, or the R33 live-push the specs
rely on won't fire. **Critically, the word "Channel" already means the Ravilo filter-collection**
(`shared.tv.Channel`, `ChannelConfig`, `ChannelLogoStore`, `/tv/channel/{id}`). Live TV cannot cleanly own
"Channel" until **Phase 148 §D** renames collections → "Collections" — treat 148 §D as an ordering
prerequisite.

**F. Drop the custom-XMLTV-URL EPG source for v1 (§D1).** The spec's own premise is that Jellyfin stays
the guide's source of truth (§intro, §A3). A jellystructure-side "custom XMLTV URL" would make
jellystructure fetch + parse a *second* guide — a large scope contradicting that premise. Recommend v1 =
**cadence-only** (how often we re-pull Jellyfin's `/LiveTv/Programs`), no alternate source. Also: the
embedded `CurrentProgram`/now-next rolls over at every program boundary, so now/next needs a **shorter
refresh than the full schedule** (same insight as the Home feed cache being too coarse — R177 §C).

**G. Scope is otherwise sound** — read-only against Jellyfin, no DVR, presentation-overrides-only,
per-user access deferred to the Ravilo config layer — all correct and consistent with the constitution.
