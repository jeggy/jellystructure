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
