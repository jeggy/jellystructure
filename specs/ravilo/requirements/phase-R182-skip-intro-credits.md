# Phase R182 — Ravilo: Skip Intro & Skip Credits (FR-RV-SKIP1)

> Turn Ravilo's fixed end-of-file "Next Episode" popup into a real **Skip Intro** + **Skip Credits / Next
> Episode** experience, driven by the per-file segment timestamps **Phase 150** detects. A low-key Skip Intro
> pill during the intro; a stinger-aware credits card at the real credits start. Behaviour (prompt vs auto,
> countdown length) is configured **on the jellystructure side** (Ravilo config → Preferences), not in the
> app. This is the player half of the credits/intro work; the detection + admin half is Phase 150.

## Goal
A viewer sees a **Skip Intro** button while the intro plays and one press jumps past it; at the credits they
get a single clear action — **Skip to scene**, **Next Episode**, or **Skip credits** — plus the ability to
just keep watching the credits. Titles with a scene after the credits are never skipped past. Any title the
backend hasn't scanned behaves exactly as Ravilo does today, so the feature can only improve the experience,
never regress it.

## Current state (verified — research §1)
- **`PlayerScreen.kt`**: `NEXTUP_AT_MS = 20_000L` shows a next-up card whenever 20s remain, then an 8s
  countdown; `durationMs`/`positionMs` are polled live every 500ms. There is **no Skip Intro** affordance and
  **no per-file segment awareness** — a movie's long credits and a sitcom's short tag are treated identically,
  and the card can't appear earlier than 20s from the file's actual end.
- The next-up card + countdown UX is already close to the desired shape and can be **retargeted** rather than
  rebuilt.
- Segment data does not yet reach the player — **Phase 150 §F** threads `Episode.segments` through
  `buildEpisodeContext` → `PlayerScreen`; this phase consumes it.

## Requirements

### A. Skip Intro pill
#### FR-RV-SKIP1-1 — A visibility-timed Skip Intro affordance
While `positionMs` is inside `[introStartMs, introEndMs)`, show a small, low-key **Skip Intro** pill
(bottom-corner, over the video, outside the transport's focus lane). Behaviour:
- On entering the intro window the pill appears with a small **countdown indicator** (configurable **4 / 6 /
  8 s**, default 6 — see §C). This countdown governs **visibility, not an auto-skip**: when it elapses the
  pill **tucks away**.
- The pill **reappears whenever the viewer opens the player controls** (D-pad, tap) and then **stays visible
  until the intro ends**.
- Pressing **OK** on the pill seeks to `introEndMs`.
- Modes (§C): **Prompt** = the above; **Auto** = seek to `introEndMs` automatically when the countdown
  elapses (the countdown is the pre-skip grace so it's never an abrupt jump-cut, and remains cancellable);
  **Off** = never shown.

### B. Stinger-aware credits card
#### FR-RV-SKIP1-2 — Retarget the card to real credits start + priority actions
Trigger the credits card at `creditsStartMs` (fallback: the existing `NEXTUP_AT_MS`-before-end heuristic when
the title has no `creditsStartMs`). The card always offers **Watch credits** (dismiss the card, keep playing)
**plus exactly one** primary action, chosen by priority:
1. **Skip to scene** — if the title has a `stinger` (from Phase 150 §C): seeks to the stinger and pauses
   auto-skip. (Never blindly skip past it.)
2. **Next Episode** — else, if a next episode exists (series): the existing card + 8s countdown ring, which
   auto-advances if untouched.
3. **Skip credits** — else (movie, or last episode, no stinger): seek past the credits / end the title.

**Never more than two buttons.** Movies never show "Next Episode". The auto-advance countdown ring appears
**only** in the Next Episode case; the stinger case shows no countdown (auto-skip is paused) and the plain
skip-credits case simply waits for input.

### C. Behaviour configured on the jellystructure side
#### FR-RV-SKIP1-3 — Server-owned viewer settings, not an in-app screen
Skip behaviour is **not** configurable inside the Ravilo app. It is set in **jellystructure → Ravilo config →
Preferences** (design in `design/app/ravilo-config.html`): **Skip intros** (Off / Prompt / Auto), **Skip
credits** (Off / Prompt / Auto), **Skip button countdown** (4 / 6 / 8 s), **Autoplay next episode**. These are
global defaults with per-user overrides, delivered through the existing server-owned viewer-settings lane
(`SettingsStore` → `RaviloConfig`; contrast **R181**, whose remembered-track memory is deliberately
device-local). The player reads the resolved values; it exposes no local toggle for them.

### D. Graceful fallback
#### FR-RV-SKIP1-4 — Unscanned content is unchanged
A title with no `introStartMs` shows no Skip Intro pill; a title with no `creditsStartMs` keeps the current
`NEXTUP_AT_MS`-before-end next-up card. The feature only ever adds behaviour on top of detected data.

### E. Data
#### FR-RV-SKIP1-5 — Consume the segment DTO
Read `segments` (intro/credits/stinger) from the player context threaded by **Phase 150 §F**. Resolve
positions in ms against the live `positionMs`; never assume a fixed offset.

## i18n
#### FR-RV-SKIP1-6 — New player strings, en/da/fo
New chrome strings — **Skip Intro**, **Skip credits**, **Skip to scene**, **Watch credits**, **Next Episode**,
and the stinger card copy ("There's a scene after this" / "auto-skip is paused…") — localised in `Strings.kt`
and `design/ravilo/ravilo-i18n.js`.

## Reuse (don't rebuild)
The existing next-up card + countdown ring + auto-advance (`PlayerScreen`); the chrome auto-hide / wake
machinery (the Skip Intro pill rides the same show/hide seam); R176 playstate continuity and R179 next-episode
resolution for the Next Episode action.

## Dependencies & relationships
- **Depends on Phase 150** for segment data + the DTO trip (§F). Without it the player has nothing better than
  today's heuristic — which is exactly the graceful-fallback path (§D), so R182 is safe to build against
  partially-scanned libraries.
- **Independent of R181** (default & remembered tracks) though both touch player init — R181 resolves which
  audio/subtitle track plays; R182 resolves intro/credits skipping. No ordering constraint between them.

## Non-goals
- **No detection in the app** — Phase 150 owns all detection; the app only consumes timestamps.
- **No Jellyfin `MediaSegments`** (see Phase 150 non-goals).
- **No in-app settings screen** for skip behaviour (it lives jellystructure-side, §C).

## Acceptance
- On a title with an intro segment, a **Skip Intro** pill appears at the intro, its countdown drains and it
  hides; opening the controls brings it back until the intro ends; OK skips to intro end. *(Verified in the
  design prototype.)*
- At the credits: a movie with an after-credits stinger shows **Skip to scene** + **Watch credits** with a
  "scene after the credits" badge and no auto-skip; a series episode with a next episode shows **Next
  Episode** (+ countdown) + **Watch credits**; a movie/last-episode with neither shows **Skip credits** +
  **Watch credits**. Never more than two buttons; movies never show Next Episode. *(All three verified in the
  design prototype.)*
- Behaviour follows the jellystructure-configured Prompt/Auto/Off + countdown + autoplay; no in-app toggle.
- An unscanned title behaves exactly as the current build.

## Status
Design-complete, **`Planned`**. Design lives in `design/ravilo/ravilo-player.js` + `ravilo-player.css` +
`ravilo-app.js` (segment-aware player context); behaviour config in `design/app/ravilo-config.html`;
exploration in `design/Skip Intro & Credits - Directions.html`. Depends on **Phase 150**.
`scripts/check-phases.sh` will flag it for a `STATUS.md` row — **STATUS.md is code-owned; do not add the row
from the design side.** **Next Ravilo number after this is R183.**

## Source references
- Hardcoded heuristic to replace: `PlayerScreen.kt` (`NEXTUP_AT_MS = 20_000L`, 8s countdown; 500ms poll).
- Segment DTO source: Phase 150 §F (`buildEpisodeContext` → `PlayerEpisodeEntry` → `PlayerScreen`).
- Server-owned settings lane: `SettingsScreen.kt` `SettingsStore` → `apiClient.putViewerSettings`.
- Research: `specs/research-reports/credits-and-intro-detection-2026-07-13.md` (§1 the gap, §6 player UX).
