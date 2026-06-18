# Phase 8 — Three-Way Theme Picker (FR-T1)

**Status:** ✓ Done

## Problem
The current theme toggle is a wide button at the bottom of the sidebar showing a sun/moon icon and
"Toggle theme" text. It is visually heavy, only supports two states (light/dark), and defaults to
`dark` regardless of the user's OS preference.

## Current state (as-is at time of spec)
- `Shell.kt`: `<button id="theme-btn" class="theme-btn wide">` with `<span class="ico">` (sun/moon SVG) + "Toggle theme" text
- `localStorage` key: `js-theme`, values: `"light"` or `"dark"`
- Default when nothing is stored: `"dark"` (hardcoded in `renderShell`)
- `data-theme` attribute on `<html>` drives CSS variable resolution
- Design mockup (`app-shell.js`) has the same structure: `div.switcher > div.sw-row > button.theme-btn.wide`

## Requirements
1. Replace the wide button with a compact three-segment pill control. Segments labelled **Light**, **Dark**, **System** (left to right), text-only.
2. **System** (sync with OS) is the default when nothing is stored in `localStorage`.
3. In System mode:
   - Read `window.matchMedia('(prefers-color-scheme: dark)').matches` to determine the initial theme.
   - Add a `change` listener on that media query; if the OS switches, the app switches immediately without a page reload.
   - `data-theme` on `<html>` is updated to `"dark"` or `"light"` accordingly.
4. `localStorage` stores `"light"`, `"dark"`, or `"system"`. Default (missing key) is treated as `"system"`.
5. The active segment is visually highlighted (filled background, full-contrast label). Inactive segments are muted.
6. The pill must fit inside the existing sidebar footer area without overflowing. Target width: fills the sidebar padding area. Height: compact (~28 px).
7. Apply the theme before the DOM is painted (existing `data-booting` pattern) to prevent flash of wrong theme. System mode at boot must resolve the OS preference synchronously.
8. The sun/moon SVG constants in `Shell.kt` are removed. The old `theme-btn` and its `click` handler are replaced by the segmented control and its handler.
