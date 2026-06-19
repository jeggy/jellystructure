# Phase R02 — Compose Multiplatform scaffolding (FR-RV2)

**Status:** Planned · _proves one shared Compose UI runs on Android TV **and** browser canvas._

## Problem
Ravilo must ship from **one** Compose Multiplatform UI codebase to **two** targets (Android TV +
Web/WASM canvas). Before building screens we need the module skeleton, the build wiring, and a single
shared screen rendering identically on both targets with working focus — to de-risk the toolchain.

## Current state (as-is)
- Repo builds a Kotlin/Native backend + a DOM/WASM admin frontend. No Compose, no Android, no second
  WASM bundle. `:shared` exists after R01.

## Requirements

### Modules & build
1. Add **`:ravilo-ui`** (Compose Multiplatform; targets `androidTarget` + `wasmJs`; depends on
   `:shared`), **`:ravilo-android`** (Android application, Android TV), and **`:ravilo-web`** (Compose
   MP for Web, `wasmJs`, **canvas/skiko**). Apply the Compose MP Gradle plugin.
2. `:ravilo-android` declares the **`android.software.leanback`** feature, the **TV banner**, and a
   `LEANBACK_LAUNCHER` activity; min/target SDK per Android TV guidance. `:ravilo-web` produces a
   wasm bundle with its **own entry HTML**, served separately from the admin bundle.
3. The existing backend + admin frontend builds are unaffected; `./gradlew` builds all modules.

### Shared "hello focus" screen
4. In `:ravilo-ui` `commonMain`, a `RaviloRoot()` composable shows a row of ~6 focusable cards using
   **only multiplatform Compose APIs** (`foundation`, `Modifier.focusable`, `FocusRequester`,
   `onKeyEvent`) — **no Android-only `androidx.tv` artifact** in shared code.
5. A minimal **focus treatment** (scale + ring) and **directional movement** (left/right) driven by a
   shared key handler.
6. Each platform module provides only the entry point + input plumbing: `:ravilo-android` hosts
   `RaviloRoot()` in a leanback `Activity` and forwards **D-pad** key events; `:ravilo-web` hosts it on
   a canvas and forwards **arrow keys + pointer**.

### `expect`/`actual` seams (stubs)
7. Declare `expect` **`RaviloPlayer`** and **`ImageLoader`** in `:ravilo-ui`; provide trivial `actual`
   stubs in each platform module (real impls in R14 / image phases). Just enough to compile + render a
   placeholder.

## Invariants
- **No shared screen depends on an Android-only Compose artifact.** tv-material3, if used at all, is
  confined to `:ravilo-android`.
- The same `RaviloRoot()` renders on **both** Android TV and browser canvas from common code.
- The Ravilo web bundle is **separate** from the jellystructure admin (DOM) bundle; neither imports
  the other.

## Out of scope
- Real screens, theme/skins (R09), networking (uses no live API yet — static placeholder data).
- Real player/image loaders (R14 / later).
