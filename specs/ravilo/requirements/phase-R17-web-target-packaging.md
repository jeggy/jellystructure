# Phase R17 — Web target hardening + packaging (FR-RV17)

**Status:** ✓ Done · _make the canvas web build production-worthy; ship both apps._

## Problem
The Compose-MP **web (canvas)** target has tradeoffs that need explicit attention before release —
input, accessibility, performance, and how the bundle is served alongside the jellystructure admin
app. Plus end-to-end coverage across both Ravilo targets.

## Current state (as-is)
- Screens (R09–R15) run on Android TV + web. The web build is canvas/skiko. Two WASM bundles now exist
  (admin DOM app + Ravilo canvas app).

## Requirements

### Web input & a11y
1. Full **keyboard** navigation (arrows + Enter + Esc map to the focus engine), **pointer/click**, and
   **focus-visible** parity with TV; optional **gamepad**. No interaction that only works with a mouse.
2. Best-effort **accessibility** for the canvas app via Compose's a11y bridge (semantics for focusable
   items, labels on tiles/buttons), acknowledging the canvas tradeoff documented in the constitution.
3. Sensible behavior on resize / non-16:9 windows (the 10-foot layout scales/letterboxes gracefully).

### Browser-native player overlay
4. The browser `<video>` element (R14) is **not** drawn on the skiko canvas — it is a real DOM node
   layered with the canvas. Harden: correct **positioning/resize** of the video under the player
   chrome, **fullscreen** handling, **focus/key passthrough** (the focus engine still drives transport
   controls; the canvas doesn't swallow needed events), and clean teardown on exit. `hls.js`/JASSUB
   load **lazily**, only once playback starts.

### Performance & packaging
5. Acceptable wasm bundle size + startup (lazy where possible); smooth row scrolling/focus on web.
6. Serve **`:ravilo-web`** as its **own** static app with its own entry HTML, **separate** from the
   jellystructure admin bundle — both hostable by the backend or any static host; neither imports the
   other.
7. Android packaging: AAB for Android TV, leanback banner/metadata correct, installable on Google TV.

### Tests
8. End-to-end smoke tests across **both** targets for the core flows: pair → home → open series →
   resume/next episode → search. Reuse the Playwright-in-Docker approach for the web target; an
   instrumented/emulator path for Android TV.

## Invariants
- The web app honors the same **focus-first** model as TV; nothing is mouse-only.
- The two WASM bundles (admin DOM, Ravilo canvas) stay **separate** and coexist.
- Control/data-plane split and server-owned config hold on web exactly as on TV.

## Out of scope
- iOS/desktop targets; offline/downloads; PWA installability (could be a later phase).
