# Phase R60 — Android phone (mobile) target (FR-RV-M1)


> Authored from the design project (`design/ravilo/Ravilo Mobile.html`). Broadens the
> constitution's "10-foot, remote-first" framing to **multi-form-factor**. Based on the
> phone-feasibility investigation (summary below).

## Goal

Ship Ravilo on a **Pixel 9 phone** (portrait, touch) from the **same shared Compose
Multiplatform codebase** as Android TV + Web, changing the design as little as possible.
**Scope is Android only for now**; iOS may follow as a separate phase later.

## Feasibility (investigation summary)

- `:ravilo-ui` uses **standard `androidx.compose.material3`** (not `tv-material3`) and is
  form-factor-agnostic; `:shared` + `:ravilo-player` are reused untouched.
- **Touch already works**: `Modifier.dpadFocusable` attaches `detectTapGestures` whenever
  `onSelect` is set — the Web variant already runs entirely on pointer/touch. Tiles, rows,
  hero and player chrome all respond to taps today.
- The only hard TV lock-in lives in the **thin `:ravilo-android` entry module** (manifest +
  Activity), **not** in the shared/design layer: `uses-feature leanback required`,
  `LEANBACK_LAUNCHER`, forced `landscape`, immersive system-bar hiding.

**Implication:** a *functional* phone app needs only a new thin entry module — no shared-UI
fork. Design work is **portrait/touch polish**, not a new screen set.

## Implementation path (entry module only)

- Add a thin **`:ravilo-phone`** module (mirrors `:ravilo-android`, depends on `:ravilo-ui`
  + `:ravilo-player`), **or** a Gradle **`tv` / `phone` product flavor**. Either way only the
  entry module changes:
  - Manifest: `leanback required="false"`, standard `LAUNCHER` category, `sensor`/portrait
    orientation.
  - A phone `MainActivity` that **shows the status/nav bars** (hides them only during
    playback); no global keep-screen-on outside playback.
- No change to `:shared`, `:ravilo-ui`, or `:ravilo-player`.

## Design adaptation (kept minimal — same components, reflowed)

The mockup is the **portrait reflow** of the existing Ravilo system — same Aurora tokens,
tiles, hero, rows, Top 10 — driven by touch:

- **Top app bar** (Ravilo wordmark · search · profile) + a **horizontal tab strip**
  (Home · Movies · Series · Top 10). **No bottom-nav paradigm for v1** — the existing top-nav
  is kept, per the "max reuse" guideline.
- **Portrait hero carousel** — full-width, swipeable (scroll-snap) with dots; Play + Info.
- **Horizontal poster rows** (2:3 tiles) that scroll with the finger; a Channels rail.
- **Detail screen** slides in on tap (backdrop, meta, Play / My List, synopsis, "More like
  this"), back-gesture/button to dismiss.
- **Top 10** as a ranked vertical list (big numerals + poster + in-library/fetching status).
- **Search** uses the **native soft keyboard** (not the D-pad on-screen keyboard from R12).

### Responsive tokens, not new mockups

Sizing differences (10-foot type/spacing → phone) should be handled by making
`theme/Dimens.kt` **responsive** so the same Compose components reflow — not by a parallel
phone screen set. A separate phone visual spec is explicitly **avoided** to preserve the
single shared visual system.

## Scope / invariants

- **Android only, for now.** This phase targets the **Android phone** (Compose, same shared
  codebase). **iOS is explicitly out of scope here** but is a plausible later phase — the
  `:shared` + `:ravilo-ui` Compose Multiplatform stack already keeps that door open (an iOS
  target would add its own thin entry + a platform player, mirroring this module split). No iOS
  work, mockup, or commitment is made now.
- One shared visual system across TV / Web / Android phone; phone is a reflow, not a fork.
- No new navigation paradigm (no bottom nav) for v1.
- TV remains the **primary** form factor; phone is additive.

## Mockup

`design/ravilo/Ravilo Mobile.html` — Pixel 9 portrait, reuses `ravilo-data.js` +
`ravilo-i18n.js` and the Aurora tokens; touch-driven Home / Movies / Series / Top 10 + detail.
