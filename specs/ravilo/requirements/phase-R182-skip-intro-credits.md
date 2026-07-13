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
credits** (Off / Prompt / Auto), **Skip button countdown** (4 / 6 / 8 s), **Autoplay next episode**
**(Dev review: "Autoplay next episode" already exists end-to-end — this is a de-dup, not a new setting; only
`skipIntro`/`skipCredits`/`skipSecs` are new — see addendum §2)**. These are
global defaults with per-user overrides, delivered through the existing server-owned viewer-settings lane
(`SettingsStore` → `RaviloConfig`; contrast **R181**, whose remembered-track memory is deliberately
device-local) **(Dev review: right *store*, wrong *authoring surface* — `SettingsStore`/`putViewerSettings`
is the on-TV Settings screen, which can only write the current viewer's own override and cannot set a global
default; an admin-configured global-default-plus-override setting must be authored in the admin `RaviloConfig`
editor (the only surface with the R51 scope switcher) — see addendum §1)**. The player reads the resolved
values; it exposes no local toggle for them **(Dev review: the player reads *no* viewer settings today; new
plumbing is required — see addendum §3)**.

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
- Server-owned settings lane: `SettingsScreen.kt` `SettingsStore` → `apiClient.putViewerSettings`
  **(dev review: that's the on-TV authoring surface, which can't set a global default; the actual store is
  `RaviloConfigService` (`ravilo_config` global + `ravilo_behaviour` per-user overlay), authored for an
  admin-set global default via the admin `RaviloConfig` editor — see addenda §1)**.
- Research: `specs/research-reports/credits-and-intro-detection-2026-07-13.md` (§1 the gap, §6 player UX).

## Dev-review addenda (2026-07-13) — reconciled with live code

1. **§C's "two lanes" framing is wrong — there is one store with two authoring front-ends, and the spec
   cites the front-end that can't do the job.** The on-TV Settings screen (`SettingsScreen.kt:51`
   `SettingsStore` → `TvApiClient.putViewerSettings`, `TvApiClient.kt:194` → `PUT /api/tv/settings`,
   `TvRoutes.kt:818`) and the admin Ravilo-config editor (`RaviloConfig.kt` `renderBehaviourGlobal`/`User`
   `:2332`/`:2422` → `RaviloApi.setBehaviour` `:126` → `PUT /api/tv/admin/behaviour`) both emit the **same**
   `ViewerSettingsRequest` DTO (`Models.kt:758`) into the **same** backend, `RaviloConfigService`
   (`ravilo_config` global default under `__global__` + `ravilo_behaviour` per-user overlay; resolution order
   `viewer → admin → global`, `RaviloConfigService.kt:192-206`). So the store the spec names is right, but
   the *authoring* surface it cites (on-TV Settings) **cannot set a global default** — `/tv/settings` always
   writes the requesting device's own user, and `/tv/admin/behaviour` explicitly rejects `scope=global`
   (`TvRoutes.kt:731`). A "global default + per-user override" operator setting must be authored in the admin
   `RaviloConfig` editor — the only surface with the R51 global/per-user scope switcher
   (`RaviloConfig.kt:304-305`) and the only one that writes the global default (via `putGlobalConfig` →
   `PUT /tv/admin/config?scope=global`, `RaviloApi.kt:149`). The design already puts the keys there
   (`design/app/ravilo-config.html:425-429` declare `skipIntro`/`skipCredits`/`skipSecs`), so the *design* is
   correct; only the spec's source-reference line points at the wrong surface.

2. **"Autoplay next episode" is a pre-existing field, not a new R182 setting — and it's currently dead.** It
   already exists end-to-end: `RaviloConfig.autoplayNext` (global default, `Models.kt:601`),
   `BehaviourOverlay.autoplayNext` (per-user, `Models.kt:641`), `ViewerSettingsRequest.autoplayNext`
   (`:761`), authored from **both** surfaces (on-TV `SettingsScreen.kt:399`; admin `RaviloConfig.kt:2388`
   global / `:472` per-user), resolved in `resolveBehaviour`. Treat it as an existing field to *reuse* (a
   de-dup), never a new toggle to add. **Also note it is presently non-functional at the point of use:** the
   next-up card auto-advances on the hardcoded `COUNTDOWN_SECS = 8` (`PlayerScreen.kt:115`, fired at
   `:558-566`) and **never consults `autoplayNext`** — so wiring the player to honour it (below) also *fixes*
   an existing latent gap, it isn't purely additive.

3. **The player consumes no viewer settings today — R182 needs new player-side plumbing, and it's a
   *separate* data path from the Phase 150 §F segment DTO.** `PlayerScreen` (`PlayerScreen.kt:148`) takes only
   plain params; `PlayerStore` (`PlayerStore.kt:28`) never calls `getConfig()`; the call site
   (`RaviloApp.kt:819`) passes no config. Two distinct carriers must reach the player: **(a) per-item segment
   timestamps** (intro/credits/stinger) via the Phase 150 §F episode/player-context DTO (§E), and **(b) the
   resolved per-viewer *behaviour*** (prompt/auto, `skipSecs`, `autoplayNext`) — which is **not** on that
   per-item DTO but on the merged `RaviloConfig` returned by `TvApiClient.getConfig()`
   (`RaviloConfigService.getConfig` overwrites the behaviour fields from `resolveBehaviour`, `:64-79`); the
   in-app `SettingsStore` already reads exactly this path (`SettingsScreen.kt:61`), the player does not yet.
   R182 must newly wire the player to read `getConfig()` at playback and replace the hardcoded
   `COUNTDOWN_SECS = 8` with the resolved `skipSecs`.

4. **Concrete field-addition shape (mirror `autoplayNext`).** Only three fields are genuinely new —
   `skipIntro` (Off/Prompt/Auto), `skipCredits` (Off/Prompt/Auto), `skipSecs` (4/6/8). Add each to
   `RaviloConfig` (global default), `BehaviourOverlay` (+ its `*Writer` tag), `ResolvedBehaviour` /
   `ResolvedBehaviourField<T>` (`Models.kt:656-668`), and `ViewerSettingsRequest`; extend `resolveBehaviour` /
   `applyViewerSettings` / `setAdmin*` / `resetBehaviourField` in `RaviloConfigService.kt`; render them in the
   `RaviloConfig.kt` Behaviour/Preferences section (both global and per-user). No new store, table, or route —
   `/tv/admin/behaviour` + `/tv/admin/config?scope=global` already carry the whole DTO.

Pairs with **[Phase 150](../../requirements/phase-150-intro-credits-segment-detection.md)** (also dev-reviewed
2026-07-13 — see its own addenda; R182 depends on 150's `segments` DTO landing, and is safe to build against a
partially-scanned library via the §D graceful fallback).
