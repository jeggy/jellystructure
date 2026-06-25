# Phase R68 — Strip ASS/SSA override tags from rendered subtitles (FR-RV-S3)

## Problem
Selecting subtitles in Ravilo very often shows raw **ASS/SSA override tags** — `{\an1}` (and
`{\pos(...)}`, `{\i1}`, `{\b1}`, `\N`, `\h`) — as literal text before/inside each line, instead of being
applied as styling or removed.

## Findings
- Text subs (SRT/**ASS/SSA**/VTT/mov_text) are sideloaded as Jellyfin **VTT** —
  `PlaybackService.kt:136-144` builds `…/Videos/{id}/{id}/Subtitles/{idx}/0/Stream.vtt?api_key=…`
  (`isTextSubCodec` matches `ass`/`ssa`/`subrip`/… `:233-234`). So Jellyfin transcodes ASS→VTT
  server-side.
- **Jellyfin's ASS→VTT extractor does not strip `{\...}` override blocks** — they survive as literal
  text inside the VTT cue. Ravilo then renders the cue verbatim. A repo-wide search found **zero cue-text
  cleaning** anywhere.
  - **Android** (`RaviloPlayerAndroid.kt:95-99`): `onCues(cueGroup) { view.setCues(cueGroup.cues) }` —
    verbatim passthrough. (MIME falls to `TEXT_VTT` since the URL ends `?api_key=…`, so ExoPlayer parses
    it as WebVTT, which doesn't strip ASS tokens.)
  - **Web** (`RaviloPlayerWasm.kt:47-54`): plain `<track src=…>` — the browser's native VTT renderer
    doesn't strip ASS tags either.
- **The R17 JASSUB path is unreachable** for this: `mountAss` only fires when the URL ends `.ass`/`.ssa`
  (`RaviloPlayerWasm.kt:43`), but the backend always emits `…Stream.vtt?api_key=…`, so every text sub
  goes through the plain `<track>` path. JASSUB (which would render `{\...}` as styling) never runs.
- **Unaffected:** PGS image subs (`deliveryMethod="encode"`, burned in) and VobSub/DVDSub
  (`deliveryMethod="embed"`, bitmaps) — no text to clean.

## Goal
Subtitle cues display clean text — ASS/SSA override blocks removed, line breaks/spaces normalized — on
both Android and web.

## Requirements
1. **Add a shared cue-text cleaner** (a tiny pure-Kotlin helper, e.g. in `:shared` or a UI util) that:
   - removes ASS override blocks: `Regex("\\{\\\\[^}]*\\}")` → `""` (covers `{\an1}`, `{\pos(…)}`,
     `{\i1}`, `{\b1}`, drawing/transform tags, etc.),
   - converts `\N` and `\n` → newline and `\h` → space (the ASS hard-space), and trims.
2. **Apply it on Android** in `RaviloPlayerAndroid.kt` `onCues` (`:96-98`): rebuild each cue via
   `cue.buildUpon().setText(clean(cue.text)).build()` before `view.setCues(...)`. This is the single
   chokepoint for all native + sideloaded text cues.
3. **Apply it on web** in `RaviloPlayerWasm.kt` (`:47-54`): since the browser parses the VTT itself, hook
   a `cuechange`/`addtrack` listener that rewrites `cue.text` on the live `TextTrack`, **or** fetch the
   VTT, scrub it with the same regex, and attach as a `blob:` URL before creating the `<track>`.
4. **(Optional, robustness)** If a future backend change exposes a true `.ass` URL, the R17 JASSUB path
   would then render styling natively — keep that as-is; the regex strip is the fix for the VTT path the
   user actually hits today.

## Scope
- `ravilo-ui/src/androidMain/.../seams/RaviloPlayerAndroid.kt` (`onCues`)
- `ravilo-ui/src/wasmJsMain/.../seams/RaviloPlayerWasm.kt` (`<track>` cue path)
- a shared `cleanCueText(...)` helper (one implementation, both platforms)

## Non-goals
- Not re-implementing libass styling positioning — `{\an1}` etc. are **removed**, not applied (full
  positioning fidelity would need the JASSUB/native-ASS path, out of scope here).
- No backend/PlaybackService change (subs still come as Jellyfin VTT).

## Acceptance
- Playing a movie whose subtitle is ASS-sourced shows clean lines with no `{\…}` tokens, correct line
  breaks, on both Android TV and web.
