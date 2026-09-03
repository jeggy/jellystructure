# Phase R229 — Settings screen unreadable on mobile (TV padding + unweighted toggle row)

> Live bug report from an Android phone: the Appearance/Language pill pickers and the "Show
> progress on Continue Watching" toggle degenerate into unreadable vertical single-letter columns
> ("N / o / i / r", "F / ø / r / o / y / s / k / t") instead of normal pill buttons.

## Status
Implemented (2026-09-03). Compile-clean (`:ravilo-ui:compileDebugKotlinAndroid`); not yet
re-verified on the reporting device (no restart/deploy granted this session, see
[[feedback-no-tv-deploy]] / [[feedback-no-auto-deploy]]).

### Implementation notes (2026-09-03)
- **FR-R229-1:** `SettingsScreen`'s content `Column` (`SettingsScreen.kt:183`, pre-fix) now reads
  `padding(horizontal = if (LocalCompact.current) 20.dp else 80.dp, ...)` — `LocalCompact` (< 600dp
  width, introduced by R145 for exactly this "phone target" purpose) was already threaded through
  `RaviloApp.kt` and consumed by other screens (`MovieDetailScreen.kt`, `SeriesDetailScreen.kt`);
  `SettingsScreen.kt` simply never adopted it. No new composition local, no new plumbing.
- **FR-R229-2:** `ToggleRow`'s label `Text` (`SettingsScreen.kt:~523`, pre-fix) gained
  `Modifier.weight(1f).padding(end = 12.dp)` inside its `SpaceBetween` `Row`. Previously the
  unweighted label was measured up to the row's full incoming width before the On/Off pill was
  measured at all — a long label (e.g. "Show progress on Continue Watching," the longest string in
  this section) left the pill a near-zero-width slot, and its `Text` then wrapped one character per
  line, exactly matching the screenshot. `weight(1f)` reserves the pill's own natural size first;
  the label now wraps to two lines in the remaining space instead, on any width. This fix stands
  regardless of the padding fix — it protects the toggle even at TV width if a translated label
  (da/fo strings run longer than en) is long enough to cause the same squeeze there.
- **Not done (left as documented follow-up, not required to fix the reported bug):** the
  Appearance/Language pill-selector `Row`s (`SettingsScreen.kt:353-386`, `:395-424`) still don't
  wrap — they rely entirely on FR-R229-1's extra width to fit. A `FlowRow` swap (letting a third
  pill drop to a second line under any width, TV included) was suggested during triage as a
  defensive follow-up but is out of scope here since the reported failure is fully explained and
  fixed by FR-R229-1 + FR-R229-2 together — re-open only if a narrower device or a longer
  translated label reproduces the same squeeze on these rows specifically.

## Problem — verified against live code
Two independent layout bugs compounded into the reported screenshot:

1. **`SettingsScreen`'s outer `Column` used a TV-sized fixed `padding(horizontal = 80.dp)`**
   (`SettingsScreen.kt:183`) with no responsive adjustment. On a ~360-400dp-wide phone window that
   leaves only ~200-240dp for content — a fraction of what the Appearance/Language pill rows and
   playback toggles were laid out assuming. Every other recently-touched screen in this module
   (`MovieDetailScreen.kt`, `SeriesDetailScreen.kt`, `PlayerScreen.kt`) already consults
   `LocalCompact`/`LocalHandset` (R145) for exactly this; `SettingsScreen.kt` had zero references to
   either.
2. **`ToggleRow`'s label had no `weight`**, so in a `Row(SpaceBetween)` the label `Text` was
   measured first, up to the full available row width, before the fixed-size On/Off pill got any
   space at all. With the outer column already squeezed by (1), the longest label in this section
   ("Show progress on Continue Watching") consumed essentially the entire row, leaving the pill a
   sliver a few density pixels wide — its own `Text("On"/"Off")` then wrapped to one character per
   line inside that sliver, which is the "o / n" stack visible at the bottom of the screenshot.

Both bugs needed to co-occur to produce the exact screenshot (the toggle-row bug alone would only
bite at genuinely extreme widths; the padding bug alone would just make things tight, not
single-character-per-line broken) — but each is independently wrong and independently worth fixing.

## Goal
Settings renders normally on a phone-width window: the Appearance and Language pill rows show
their buttons at a readable size (wrapping to a second row if the reserved width still isn't
enough, once the width fix lands), and every toggle row's On/Off pill stays a compact, single-line
pill regardless of how long its label is or how narrow the screen is.

## Requirements

### FR-R229-1 — Settings' outer padding scales with window width
`SettingsScreen`'s content `Column` uses `LocalCompact.current` to choose horizontal padding:
20dp on a compact (< 600dp-wide) window, 80dp otherwise (TV, unchanged). Vertical padding and every
other TV-facing dimension in this screen are unaffected.

### FR-R229-2 — A toggle row's pill never loses its own space to its label
`ToggleRow`'s label always yields space to the On/Off pill first — the pill keeps its normal
single-line size on every width, and the label wraps (already supported by `Text`'s default
behavior once it isn't force-fed the whole row) rather than the pill collapsing.

## Invariants (must not change)
- TV layout is byte-for-byte unchanged — `LocalCompact.current` is `false` on any window ≥ 600dp
  wide, so the padding branch and the pill's reserved-first-then-label-wraps ordering both resolve
  to the exact same visual result TV already has today.
- No change to what settings exist, their persistence, or their focus/D-pad wiring — this phase is
  layout-only.

## Non-goals
- Making the Appearance/Language pill `Row`s themselves wrap onto a second line (`FlowRow` or
  similar) — see the Implementation notes' "Not done" entry. Revisit only if reported again after
  this fix ships.
- Any change to `LocalCompact`/`LocalHandset` themselves (R145) — reused as-is.

## Source references
- Bug + fix: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/SettingsScreen.kt`
  (`SettingsScreen` outer `Column` :183, Appearance pills :353-386, Language pills :395-424,
  `ToggleRow` :504-536).
- `LocalCompact`/`LocalHandset` precedent: `RaviloApp.kt:528-547` (R145's own doc comment); prior
  adopters `MovieDetailScreen.kt:220`, `SeriesDetailScreen.kt:395`, `PlayerScreen.kt:1289,1330`.

## Relationships
- Applies R145's existing compact-width pattern to a screen that had never adopted it.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the
  row from the design side.**
