# Phase 12 — Simplify TV Series Language UI (FR-U1)

## Problem
The TV series overview tab has two redundant cards that both show language distribution:
1. A large `tvOverviewBanner` at the top of the main content area with a prose explanation, distribution bars, and the language override input.
2. A smaller `seriesLangCard` in the left rail that also shows distribution bars and the current NFO language — but without the override input.

These duplicate each other and the prose explanation is unnecessary.

## Current state (as-is at time of spec)
- `tvOverviewBanner`: shown for both uniform and languageMix series. Contains: status badge, prose paragraph, distribution bars, episode/track counts, triage link (for languageMix), and `tmdbLangHint` (the override input + save button).
- `seriesLangCard`: in the left rail under the poster. Contains: top-4 distribution bars, read-only "tvshow.nfo language" display. No override input.

## Requirements
1. **Remove** `tvOverviewBanner` entirely from the main content area for TV shows.
2. **Replace** `seriesLangCard` with a combined card that absorbs all functionality from the removed banner while remaining compact enough for the 220 px left rail.
3. The new combined left-rail card must contain, in order:
   - `<h4>` "Series language" with an inline status badge: green `"Uniform"` or amber `"Mixed"`.
   - Subtitle line: `"{N} episodes · {M} tracks total"`.
   - All language distribution bars (not capped at 4), each showing language code, percentage bar, and track count. The primary/resolved language bar is highlighted in `var(--ok)`.
   - If untagged tracks exist (`votes["?"] > 0`): a small inline link to Series Triage.
   - Separator line.
   - Language override control using the **language picker** (Phase 11): label "NFO language", picker pre-filled with `item.resolvedLanguage`, and "Save" button. Same save/API logic as the existing `lang-override-btn`.
   - Small muted note: "Used for TMDB metadata and tvshow.nfo writes. Does not affect audio tracks."
4. No functionality removed. All existing event handlers for the language override save are preserved.
5. The "Uniform" state still shows the green badge, but no full-width prose banner in the main content area.
