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

### Amendment 2026-10-27 — radio stations (DEB-2628)
Jellyfin's radio channels (`ChannelType: "Radio"`) sit in the same lineup as the TV channels, and arrive hidden and
badged *new* like any channel (C1). Each sync stores the channel's kind (`tv` or `radio`), so a restart never loses it.
- **B2:** the lineup table marks a station with a small radio mark beside its name.
- **B2:** the filters gain *Radio*.
- **A2:** the overview's counts include stations as channels.

The TV-facing endpoints send stations only to a client that asks with `radio=1`. See
[`radio-plays-beside-the-live-channels-and-on-music-home.md`](../ravilo/requirements/radio-plays-beside-the-live-channels-and-on-music-home.md)
FR-1 to FR-3 and FR-12.

### Amendment 2026-10-30 — the operator sets the kind (DEB-3039)
Jellyfin 12.1.0 lists every channel as `ChannelType: "TV"`, whatever the M3U says, so the sync cannot find the stations.
- **B2:** each lineup row has a **Type** control, *TV* or *Radio*. It is the one place a channel becomes a station.
- A sync seeds a channel's kind from Jellyfin's `ChannelType` only until the operator has set it; after that the
  operator's choice is kept across syncs and restarts.

See the radio spec's amendment of 2026-10-30 (DEB-3039).

### Amendment 2026-10-30 — now/next follows the clock (DEB-3067)
D1 has the cadence govern now/next as well as the full schedule. That cannot stand beside the radio spec's FR-10 (*"so
the title turns over on its own"*) or R177 B1 (the tile shows the *current* program): a one-hour cadence would leave a
finished programme on screen for up to an hour. The code is worse than D1. `lineup()` builds `current_program` from the
snapshot `sync()` took, and `sync()` runs only at backend start, on an admin page open and on *Refresh from Jellyfin*, so
DEB-3026 found a programme still current two hours after its end. Tróndur's decision, on DEB-3066:

- **D1, as amended:** the cadence governs the full schedule grid (`GET /tv/livetv/guide`) and nothing else. The admin's
  label, *Refresh the guide every*, already says that.
- **A programme that has ended is never served as the current one.** A programme whose `end_ms` is at or before now is
  `current_program: null`, whatever the snapshot holds. This binds every reader of `lineup()`: `/tv/livetv/channels`,
  `/tv/music/home` and the admin's channel list.
- **Now follows the clock: when the listed programme ends, we re-read it.** A read re-reads `/LiveTv/Channels` before it
  answers when the snapshot holds an ended programme, or holds no channels at all. A channel in a gap between programmes
  (next bullet) also triggers one when the snapshot is over 60 s old.
  - It happens on read, with no timer: when nobody is reading, nothing is on screen and Jellyfin is not called.
  - Concurrent readers share one re-read, and there is at most one every 5 s whatever triggered it.
  - It replaces the snapshot and nothing else: no lineup diff, no `lastSyncedAt`, no `notifyGlobalConfigChanged`. A
    programme ending is not a config change, and a push would make every client re-fetch on every hour. New and removed
    channels stay `sync()`'s.
  - If Jellyfin does not answer, the ended programme is still not served and the rest of the snapshot stands.
- **A gap between programmes keeps the guide.** `has_guide`, and so B2's guide indicator, stays true through a gap. The
  radio spec's amendment of 2026-10-30 (DEB-3067) defines it and what a gap reads on screen.
- **Not changed:**
  - The wire's shape. `current_program` is already nullable, and every client already draws its absence.
  - `next_program`, which the server has sent as null since this phase was built. R177's *next-up* and the radio
    player's *Next* line have never had anything to draw, and this amendment does not fill them.
  - A tile on a page that is left open. It is as fresh as its page's last read, and no client gains a timer for it.

## Scope
The admin half of Live TV. It is design-authored from `design/app/livetv.html`, and its viewing half is
[R177](../ravilo/requirements/phase-R177-livetv-viewing.md). Amended by DEB-2628, which adds radio stations to the lineup (A2, B2), and by
DEB-3067, which takes now/next off the cadence (D1).
