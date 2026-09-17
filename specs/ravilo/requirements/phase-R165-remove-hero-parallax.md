# Phase R165 — Remove the Home hero parallax (scroll-linked backdrop drift) (FR-RV-PX1)

> The Home screen's hero backdrop drifts vertically as the content list scrolls — a scroll-linked
> **parallax** added in R91. It's a dated effect and unnecessary motion; remove it so the hero backdrop
> stays put while the rows scroll over it. The time-based **Ken Burns** zoom that shares the same layer
> is *not* parallax and is **kept** (its removal is a separate decision). Removing the parallax also
> brings the Compose app back in line with the design mockups, which never had it.

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned).

## Problem
`HeroCarousel` translates its backdrop image up at half the list's scroll speed as the hero leaves the
viewport. It is the only genuine parallax in the app; the HTML/CSS design mockups don't have it (the
mock's `.hero-backdrop` is a static `inset:0; object-fit:cover` image), so R91 introduced motion the
design never specified. Modern TV UIs keep the hero still — the drift adds nothing and is extra
per-frame work.

## Scope (exact — the effect is one line plus its plumbing)
The parallax lives entirely in `ravilo-ui`. It is a single `translationY` fed by the Home list's scroll
offset, plus the parameter / constant / call-site that wire it:

1. `components/HeroCarousel.kt:209` — inside the backdrop's `graphicsLayer` block:
   `translationY = -scrollOffsetPx() * RaviloMotion.HERO_PARALLAX_FACTOR`. **This is the effect.**
2. `components/HeroCarousel.kt:73-74` — the `scrollOffsetPx: () -> Float = { 0f }` parameter (+ KDoc)
   that feeds it (defaulted, so inert unless a caller passes it).
3. `theme/Motion.kt:54-55` — `const val HERO_PARALLAX_FACTOR = 0.5f` (+ comment); referenced only at (1).
4. `screens/HomeScreen.kt:209-212` — the only call site that activates it:
   `scrollOffsetPx = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f }`.

## Requirements

### FR-RV-PX1-1 — Remove the scroll-linked backdrop motion
Delete the `translationY` parallax at `HeroCarousel.kt:209` and its plumbing (the `scrollOffsetPx`
parameter, the `HERO_PARALLAX_FACTOR` constant, and the `scrollOffsetPx = { … }` argument passed from
`HomeScreen`). After removal the hero backdrop is static under the scrolling rows.

### FR-RV-PX1-2 — Keep the graphicsLayer block for Ken Burns
The backdrop's `graphicsLayer` (`HeroCarousel.kt:206-210`) also carries the Ken Burns `scaleX`/`scaleY`
(a **time-based** zoom, R91/R101 — not scroll-linked). Delete **only** the one `translationY` line, not
the whole block; leave `clipToBounds()` (`HeroCarousel.kt:160`) in place — Ken Burns still needs it.

### FR-RV-PX1-3 — Fix the stale comments
Update the parallax mentions in the KDoc/comments at `HeroCarousel.kt:157-159` and `:202-203` so they no
longer describe a parallax that's gone.

### FR-RV-PX1-4 — No other surface changes
`ChannelScreen` already calls `HeroCarousel` **without** `scrollOffsetPx` (per-channel heroes never had
parallax) — unchanged. The design mockups have no parallax — no design change. **Do not** touch the
focus-scale animations (Tile / ChannelCard / CastCircle / EpisodeCard / SeasonPicker / RaviloButton
draw-only focus scale, R42/R43/R47) or the RaviloButton focus *lift* — those are focus-linked, not
parallax.

## Invariants
- **Only scroll/position-linked layer motion is removed.** Focus-scale and the toast slide-in stay.
- **The Ken Burns zoom is retained** unless a separate decision removes it; this phase touches only the
  parallax `translationY`.
- The hero's scrims (tint + floor gradients), slide crossfade, and page dots are untouched.

## Out of scope
- Removing the Ken Burns zoom (a separate call — if wanted, the whole `graphicsLayer` block + the
  `driftEnabled` param + the `kbScale` animatable + the `HERO_KEN_BURNS_*` constants also go).
- Any other animation (focus scale/lift, crossfades, auto-advance).

## Source references
- Code: `ravilo-ui/.../components/HeroCarousel.kt` (`:209` translationY, `:73-74` param, `:157-159` /
  `:202-203` comments, `:206-210` shared graphicsLayer, `:160` clipToBounds); `.../theme/Motion.kt:54-55`
  (`HERO_PARALLAX_FACTOR`); `.../screens/HomeScreen.kt:209-212` (call site).
- Design: `design/ravilo/ravilo.css` (`.hero-backdrop` static `inset:0; object-fit:cover`) — already
  parallax-free; confirms removal re-aligns Compose with the design.
- Related: **R91** (added the hero parallax + Ken Burns), **R101** (scroll-pauses Ken Burns for frame
  budget), **R42/R43/R47** (draw-only focus scale — explicitly NOT parallax, keep).
