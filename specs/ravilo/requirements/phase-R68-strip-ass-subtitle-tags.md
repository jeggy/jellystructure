# Phase R68 — Strip ASS/SSA subtitle override tags (FR-RV-S3)

> Authored from the design project. Follow-up to **[R55](phase-R55-subtitle-rendering.md)** (subtitle
> rendering surface) and **[R56](phase-R56-image-subtitles.md)** (image-based subs).

## Problem
After R55 embedded text subtitles (SRT/ASS/SSA, sideloaded as Jellyfin VTT) actually draw on screen.
But **ASS/SSA override tags** leak through as literal text: styling/position codes wrapped in braces —
e.g. `{\an8}`, `{\i1}…{\i0}`, `{\pos(…)}`, `{\c&Hxxxxxx&}`, `{\fad(…)}`, drawing blocks `{\p1}…` — and
karaoke/timing tags render as visible garbage in front of (or instead of) the actual line. Faroese /
Danish fan-subbed ASS tracks are common in this library, so the problem is frequent and ugly.

## Goal
Subtitle lines display **only their readable text**. ASS/SSA override blocks are stripped (not shown),
so a cue that should read "Hann kemur aftur" never renders as `{\an8}Hann kemur aftur`.

## Requirements
1. **Strip override blocks.** Before a cue is handed to the subtitle surface, remove every `{…}`
   override block. Handle the common tags (`\anN`, `\i \b \u`, `\pos \move \org`, `\c \1c…\4c`,
   `\fad \t`, `\p` drawing) by dropping the whole brace group rather than trying to render it.
2. **Unescape line breaks + literals.** Convert ASS `\N` (hard break) and `\n` to real line breaks and
   `\h` to a space; collapse the leftover whitespace so a stripped line isn't left with a leading gap.
3. **Drop drawing/karaoke-only cues.** A cue whose content is purely a drawing block (`\p1…`) or pure
   timing with no text becomes empty → don't draw an empty/garbage line.
4. **Apply on both targets.** The strip happens where cues are normalized for the render surface
   (Android `SubtitleView` `onCues` path from R55; web `<track>`/cue path) — one shared cleaner so the
   two targets never diverge.
5. **Text only — no style application.** This phase **removes** override tags; it does **not** honour
   them (no positioning/italics from `\an8`/`\i1`). Faithful ASS styling is explicitly out of scope —
   readable text is the goal.

## Invariants
- One cue-cleaner, shared by Android + web (same rule both sides, like R55/R78's shared components).
- Plain SRT/VTT cues (no braces) pass through unchanged.

## Out of scope
- Honouring ASS styling/positioning (italics, `\an` anchors, colors, fades) — strip, don't render.
- Image-based subs (PGS/VobSub) — owned by R56.
- The subtitle **picker** / track selection (R41/R55).

## Source references
- `ravilo-ui/.../seams/RaviloPlayerAndroid.kt` (cue forwarding to `SubtitleView`, R55),
  `ravilo-ui/.../seams/RaviloPlayerWasm.kt` (web `<track>` cues),
  a shared `commonMain` cue-text cleaner. Mockup: `design/ravilo/ravilo-player.js` (subtitle overlay).
- Related: **R55** (render surface), **R56** (image subs), **R78** (subtitle language flags — detail).
