# Phase R205 — "Loading…" text pinned to the left edge instead of centered (bug fix)

> Found during the 2026-08-18/20 screenshot session (`presentation/observed-issues-2026-08-18.md`,
> item 3). The `Loading…` placeholder measured at x=0 on screen — flush against the left edge, no
> content gutter — on a TV, where overscan makes that specifically bad. Originally guessed to be the
> same composable as item 1 (series detail stuck loading); code inspection shows that's wrong (series
> detail uses a shimmer skeleton, not this text) — the real site is the plain-text Loading state
> shared by `BrowseScreen.kt` and `SeededBrowseScreen.kt`. Correcting the record here rather than
> silently dropping the mistaken link.

**Status:** Implemented.

## Bug report
Self-found while auditing screenshots; the x=0 measurement is real, but which screen it belongs to
was mis-identified before this spec was written (see Investigation).

## Investigation
`BrowseScreen.kt`'s `Loading` branch:
```kotlin
Column(modifier = Modifier.fillMaxSize().padding(top = RaviloDimens.appBarHeight + 24.dp)) {
    when (val s = state) {
        is BrowseState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(str("loading"), ...)
```
`Modifier.weight(1f)` inside a `Column` only distributes **height**; it does not imply
`fillMaxWidth()`. Without an explicit width modifier, `Box` sizes itself to wrap its content (the
`Text`), so the box's own bounds equal the text's bounds — `contentAlignment = Alignment.Center`
centers content *within the box*, which does nothing when the box already fits the content exactly.
The Column's default horizontal alignment (`Start`) then places that content-sized box flush against
the left edge — reproducing the observed x=0 measurement exactly.

The identical pattern exists in `SeededBrowseScreen.kt`, in **two** places (`Loading` and `Error`):
```kotlin
is SeededBrowseState.Loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ... }
is SeededBrowseState.Error   -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ... }
```
By contrast, `ChannelScreen.kt`'s equivalent Loading state already uses `Modifier.fillMaxSize()` (not
`weight(1f)`), which is why it doesn't have this bug — that's the correct pattern being copied here.

Note for the record: `SeriesDetailScreen.kt`'s `Loading` state renders `DetailLoadingShell()` (a full
shimmer skeleton), not this `Text` — so this fix does **not** address item 1 (stuck-on-Loading);
that's handled separately in [[phase-R206]] with an honest note about what could and couldn't be
confirmed.

## Requirements

### FR-RV-R205-1 — Loading/Error placeholder text is actually centered
Add `.fillMaxWidth()` to the `Box` modifier chain (before `.weight(1f)`, order doesn't matter for
these two orthogonal axes) at:
- `BrowseScreen.kt`'s `BrowseState.Loading` branch.
- `SeededBrowseScreen.kt`'s `SeededBrowseState.Loading` branch.
- `SeededBrowseScreen.kt`'s `SeededBrowseState.Error` branch (same bug, same fix, found in passing).

## Invariants
- A `Box(Modifier.weight(1f), contentAlignment = Alignment.Center)` inside a `Column` is a suspicious
  pattern going forward — `weight` alone doesn't grant width, so centering without an explicit
  `fillMaxWidth()`/`fillMaxSize()` silently does nothing. Worth flagging in review if seen again.

## Out of scope
- `ChannelScreen.kt` and `SettingsScreen.kt`'s own Loading-text sites already use `fillMaxSize()` and
  are unaffected — not touched.

## Source references
- Bug sites: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/BrowseScreen.kt`
  (`Loading` branch), `.../screens/SeededBrowseScreen.kt` (`Loading` and `Error` branches).
- Correct reference pattern: `.../screens/ChannelScreen.kt:166` (`Modifier.fillMaxSize()`).
