# R134 — Merge audio + subtitle flags into one line (detail)

> Builds on **R75** (detail audio-language flags) and **R78** (detail subtitle flags). Movie + series detail.

## Problem

The movie/series detail screen renders the **audio-language flags** and the **subtitle flags** on two
separate rows, stacked vertically with their own spacers. It wastes vertical space in the hero's lower
third and reads as two unrelated strips. They should be **one line**.

## Current

`AudioFlagStrip` (`components/AudioFlagStrip.kt`) is a `Row` of: a tiny uppercase label (`AUDIO` /
`SUBTITLES`, 9sp Sora α0.55) · up to 5 flag images (26×18dp, rounded) · an optional `+N` pill. It is
already parameterised with a `label`, so subtitles reuse it.

Both detail screens stack two of them:
- `MovieDetailScreen.kt` ~201–208 — `if (audio) { Spacer; AudioFlagStrip(audio) }` then
  `if (subtitle) { Spacer; AudioFlagStrip(subtitle, label = "SUBTITLES") }`.
- `SeriesDetailScreen.kt` ~257–264 — identical.

## Requirements

1. Audio flags and subtitle flags appear on a **single horizontal line**: `AUDIO 🅐🅑 · SUBTITLES 🅒🅓`,
   the two groups separated by a subtle divider (a centered dot/pipe in `textSecondary`).
2. Render only the groups that exist: audio-only, subtitle-only, both, or (if neither) nothing — no empty
   label or stray separator.
3. Keep R75/R78 behaviour per group: max 5 flags + `+N`, the existing flag art + sizing, the label style.
4. If the line is too wide for the hero column, it may wrap gracefully (the subtitle group drops to a
   second line) — but the default/common case is one line.

## Approach

Add a `AudioSubtitleFlagLine(audio: List<…>, subtitle: List<…>, modifier)` to `AudioFlagStrip.kt`: a single
`Row` (`verticalAlignment = CenterVertically`) that emits `AudioFlagStrip(audio, "AUDIO")`, then — only when
both are present — a divider, then `AudioFlagStrip(subtitle, "SUBTITLES")`. Replace the two stacked blocks
in both detail screens with one call + one preceding `Spacer`.

## Files

- `ravilo-ui/.../components/AudioFlagStrip.kt` (new combined-line composable; reuse the existing strip).
- `ravilo-ui/.../screens/MovieDetailScreen.kt`, `…/SeriesDetailScreen.kt` (replace the two blocks).

## Out of scope

The flag art, the language-resolution / mapping, and the max-5 cap (R75/R78) — unchanged.
