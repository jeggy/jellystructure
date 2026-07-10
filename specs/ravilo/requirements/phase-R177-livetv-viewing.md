# Phase R177 — Ravilo: watch Live TV (guide · Now/Next · zapping · player) (FR-RV-LTV1)

> The TV-side of Live TV. Ravilo gains **Live TV**, woven into the home experience (**no top-nav tab**): a
> Home **"On now"** row into a full **EPG guide** (channels × time), channel **zapping** (up/down + number
> entry), a **Now/Next** mini-guide while watching, and a live **player**. Channels + guide come from
> jellystructure (**[Phase 147](../../requirements/phase-147-livetv-admin-config.md)**), which surfaces
> Jellyfin's Live TV. Design mockup: **`design/ravilo/Ravilo Live TV.html`** (`livetv-app.jsx` ·
> `livetv.css` · `livetv-data.js`).

## Goal
A viewer can reach Live TV from **Home** (an "On now" row → the guide), scan a program guide, and watch a
live channel with familiar TV controls (zap up/down, type a channel number, see now/next, jump between
channels) — all streamed through jellystructure from Jellyfin. Live TV is **never** a top-nav tab.

## Current state (verified)
- Ravilo has **no Live TV**. Nav is Home · Movies · Series · Discover (+ avatar menu). Playback is
  library media via `/api/tv/**`.
- Phase 147 provides the channel lineup (shown/number/order/logo/category) + the guide (now/next + full
  schedule) + a live stream per channel, all brokered by jellystructure.

## Requirements

### A. Where Live TV surfaces (no top-nav tab)
1. Live TV is **not** a top-level nav tab — it's woven into the home experience so it never crowds the
   main navigation. It surfaces via (jellystructure-configured, Phase 147 §F): a Home **“On now”** row
   (with an **Open TV Guide** action into the full guide) and/or a **Live TV collection** in the rail.
2. Whether a viewer sees Live TV at all, and which channels, is config-driven (global default or per-user,
   like the rest of the Ravilo layout — Phase 148). Restricted/kids users only ever see channels their
   policy permits (rides Phase 142).

### B. Home "On now" row
1. A row on Home (**"On now"**) of the viewer's channels, each tile showing channel logo/number, the
   current program, its live progress, and next-up. Selecting a tile tunes that channel (opens the player).

### C. EPG guide
1. A full **guide grid** — channels (rows) × time (columns) — with a sticky channel column, a sticky time
   header, a **"now" line**, per-program **live progress**, and **category filter** chips. D-pad navigable;
   selecting a program tunes its channel (you watch live, not the future slot). Channel logos/numbers/order
   come from Phase 147.
2. **Density** (compact / spacious) is a presentation option. The mockup also prototyped **list** and
   **timeline** guide layouts as alternatives (`epgLayout` tweak) — the shipping default is the **grid**;
   list/timeline are optional follow-ups, not required for v1.
3. Channels flagged unavailable by Phase 147 never appear; a configured channel that vanishes degrades
   gracefully (skipped, no crash).

### D. Live player
1. Full-screen live playback of the channel's Jellyfin Live TV stream, with a **LIVE** indicator and a
   bottom **channel bar**: logo · number · name · category · current program · times · live progress.
   Chrome auto-hides and re-shows on any key.
2. **Zapping:** ←/→ (or channel up/down) moves to the adjacent channel with a brief **channel banner**;
   typing **0–9** opens a number-entry OSD that tunes on complete/timeout.
3. **Now/Next overlay:** ↑ opens a mini-guide of nearby channels (now + next per channel), D-pad browse,
   Select tunes.
4. **Timeshift (optional):** a buffer-based pause/rewind with a **jump-to-live** on the progress bar,
   where the tuner/stream supports it. No DVR/recordings.

### E. i18n
1. New en/da/fo strings for the Live section, guide, and player chrome (`Strings.kt` +
   `design/ravilo/ravilo-i18n.js`).

## Reuse
Tile/focus model, `AudioFlagStrip` `LANG_CC` for any flag needs, `RaviloButton`, the player chrome
patterns, and the R33 live-config push (so a lineup/enable change reaches the TV without a restart).

## Non-goals
- **No DVR / recordings / catch-up** beyond the live buffer (Phase-147 non-goal too).
- **No tuner or guide authoring** (Jellyfin owns it).
- List/timeline guide layouts are optional (grid ships first).
- No per-program reminders/series-record in v1.

## Acceptance
- With Live TV enabled, Home shows an **“On now”** row that opens the guide (**no top-nav tab**); the guide
  renders the viewer's channels × time with a now-line and live progress; picking a program tunes the channel.
- In the player, ←/→ zaps with a banner, 0–9 tunes by number, ↑ shows Now/Next and Select tunes; the
  channel bar shows now/next + live progress.
- Restricted/kids viewers see only permitted channels; a removed channel never breaks the guide/player.
- en/da/fo strings present.

## Status note
Design-authored, `Planned`, not yet dev-reviewed. Exports to
`specs/ravilo/requirements/phase-R177-livetv-viewing.md`; `scripts/check-phases.sh` will flag it for a
`STATUS.md` row. **Next Ravilo number after this is R178.**

## Dev-review addenda (2026-07-10)
Verified against the **live Jellyfin** and the Ravilo client. The viewing experience is buildable on real
data (7 channels, a 1658-program 7-day guide — see Phase 147 §A), and the player-engine reuse holds, but
three assumptions need correcting. Depends entirely on Phase 147's backend.

**A. The player engine handles the HLS stream, but the live LIFECYCLE is new.** Live channels are infinite
HLS (`master.m3u8`, `IsInfiniteStream:true`) which Media3/ExoPlayer (Android) and the web `<video>` path
(R157) play natively — §D's player reuse is valid at the engine level. What's **new** vs the VOD player:
Jellyfin's `RequiresOpening/RequiresClosing` open/close lifecycle (Phase 147 §D), **no resume position**,
and **infinite duration** (no fixed end → the seekbar becomes a live-edge indicator, not a scrubber). §D4
timeshift depends on the source HLS carrying a DVR window; the DR/KVF akamai live streams may not — treat
timeshift as genuinely optional/best-effort, verified per-stream, not assumed.

**B. Per-program category filter chips (§C1) have no Jellyfin data — filter by CHANNEL category instead.**
Live probe: guide programs carry no `IsKids/IsNews/IsSports/Genres` (only `IsSeries`, on ~half). So a
per-*program* category filter cannot be populated from Jellyfin. Re-spec §C1's category chips to filter by
the **channel's** operator-assigned category (Phase 147 §B) — which still delivers the value (e.g. "show
only Kids channels" → DR Ramasjang) at channel granularity, not per-slot.

**C. "On now" + channel bar read the embedded `CurrentProgram` — no per-channel guide call, but keep it
short-TTL.** Each channel embeds its current program (name/start/end/runtime), so the Home "On now" row
(§B) and the player channel bar (§D1) render now/next-progress from the channels list alone; only the full
guide **grid** (§C) needs `/LiveTv/Programs`. The embedded now/next rolls over at each program boundary, so
it must **not** sit inside the 5-min Home feed-cache — fetch it live like the Continue-Watching row
(`HomeFeedService` excludes that row from the structural cache; mirror that).

**D. "Rides Phase 142" (§A2) does NOT hold — channels aren't MediaItems.** Phase 142's restricted-user
filter (`MediaItem.visibleTo(device)`) keys on the library id + tags of *catalog items*; **Live-TV
channels are not MediaItems and have no library/tag**, so that predicate cannot gate them. A per-user
Live-TV access model (which users get Live TV, which channels each may see) is a **new** concern belonging
in the Ravilo config/preferences layer (Phase 147 §E / 148) — e.g. a per-user allowed-channel set with a
kid-safe default (the operator flags which channels a kids profile sees, e.g. only DR Ramasjang). Do not
assume 142 covers it.

**E. Channel logos come from Jellyfin, not R133 on-disk artwork.** Channels expose logos via
`ImageTags.Primary` (Jellyfin), which R133's "serve Ravilo artwork from jellystructure's on-disk files"
does **not** cover (there's no on-disk artwork for a Live-TV channel). Live-TV logos need a Jellyfin image
fetch/proxy (cache-friendly) path — a small addition, not free reuse of R133.

**F. Naming + live-push.** R177 uses "channel" for Live TV throughout; this depends on Phase 148 §D freeing
the term from the filter-collection meaning first. The R33 live-config reuse (a lineup/enable change
reaches the TV without restart) is valid **only if** Phase 147's lineup write path fires
`notifyConfigChanged` (see 147 addendum §E).

**G. Otherwise sound.** No-top-nav-tab placement, grid-first guide (list/timeline optional), no DVR, and
the zap / number-entry / now-next player controls are all consistent and reasonable; the 7-channel /
7-day live data is enough to build and verify every section.
