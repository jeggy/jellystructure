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
