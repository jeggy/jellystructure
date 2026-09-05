# R236 — Down from the Home hero can stop working entirely

> Live report (stue TV): *"I feel like the home screen navigation sometimes is buggy. I don't know
> exactly how yet. But when in the home screen and going down and then all the way up again, then
> sometimes I can't go below the hero carousel anymore. I'm not sure if this only happens when doing
> something else first (like opening a movie or something and go back), but at least, sometimes I can't
> get below the carousel, even though I can see everything below it."*

## Status
Planned (spec'd 2026-09-06). Not dev-reviewed. Root-caused by reading `HomeScreen.kt` against
`ContentRow.kt` and `FocusModifiers.kt`; **not reproduced on-device** — see Open questions. The
reporter's own hedge ("only when doing something else first, like opening a movie and going back") is
the decisive clue and it points straight at the mechanism.

## Problem

The hero consumes the Down key and hands focus over explicitly:

```kotlin
val firstRowFR = remember { FocusRequester() }
…
HeroCarousel(
    focusRequester = heroFR,
    onUp   = { navBarFR.requestFocus() },
    onDown = { requestFocusRetrying(scope, firstRowFR) },
)
```
— `HomeScreen.kt:181`, `:260-274`

`firstRowFR` is attached to exactly one thing: **the tile at index 0 of the first row**, wherever that
row happens to be —

```kotlin
focusRequester = fr ?: if (i == 0) firstRowFR else null
```
— `HomeScreen.kt:299` (channel card), `:432` (content tile), plus the On Now guide tile at `:466`

Those tiles live inside a `LazyRow` inside a `LazyColumn`. **A lazy list disposes items that scroll out
of its composed window**, and a `FocusRequester` whose target composable is not currently composed
throws on `requestFocus()`. `requestFocusRetrying` swallows that and retries for 30 frames
(`FocusModifiers.kt:46-54`), then gives up silently — the key press does nothing, and every subsequent
press does nothing too, because the target is still gone.

`dpadFocusable`'s own doc already names this rule:

> *"`focusRequester` is optional — only needed for an explicit entry point or a non-spatial bridge,
> **never one-per-item across a lazy list**."* — `FocusModifiers.kt:70-71`

and `HomeScreen.kt:187-189` claims compliance:

> *"All are single, always-composed requesters — never one-per-item across a lazy list (that was the
> source of the stuck/lag behaviour)."*

`firstRowFR` is a single requester, but it is **not always-composed**: it is bound to a lazy item. The
comment describes the intent; the code does not achieve it.

### Two ways the target disappears

**Horizontal — the one the reporter described.** R139's Back-return restore scrolls the originating row
to the tile the viewer opened:

```kotlin
val idx = items.indexOfFirst { itemKey(it) == restoreItemKey }
if (idx >= 0) {
    runCatching { listState.scrollToItem(idx) }
    runCatching { restoreFR.requestFocus() }
}
```
— `ContentRow.kt:127-142`

Open the 9th tile of the first row, press Back: the row is scrolled to index 8, so index 0 is far
outside the `LazyRow`'s composed window and is disposed. Focus is restored to tile 8. Press Up to the
hero, press Down — `firstRowFR` has no target. **Focus stays on the hero and Down is dead**, for as long
as that row remains scrolled. Exactly: *"opening a movie or something and go back"*, then *"can't go
below the carousel, even though I can see everything below it"* — the row is right there on screen; only
its index-0 tile isn't composed.

Plain horizontal navigation reaches the same state without opening anything: arrow Right far enough
along the first row, then Up, then Down.

**A second, narrower window.** During the R139 restore, `fr` is non-null for the matching item, so
`fr ?: if (i == 0) firstRowFR` gives `firstRowFR` **no target at all** — even when tile 0 *is*
composed — until `onRestored()` clears the store keys and triggers a recomposition. Transient, but it
is a second unattached window layered on the first.

### Why the retry doesn't save it

`requestFocusRetrying` was added by R200 for a target that takes *more than one frame* to attach. Here
the target is not late, it is absent — no number of frames will make a disposed lazy item reappear,
because nothing is scrolling it back. R200's fix addresses timing; this is topology.

## Goal

Down from the hero always moves focus into the content below it, whatever that row is and wherever it
happens to be scrolled — with no dead key presses and no state an app restart is needed to escape.

## Requirements

### FR-R236-1 — The Down bridge targets a container, not a lazy item

`firstRowFR` stops being attached to tile index 0. The bridge targets the **first row itself** — a
composable that is always present while that row is in the LazyColumn's window, carrying
`Modifier.focusRequester(firstRowFR).focusGroup()` on the row's `Column`/`LazyRow` wrapper.

`StaticContentRow`'s `LazyRow` already has `Modifier.focusRestorer()` (`ContentRow.kt:220`), which is
exactly the right primitive for this: focus delegated to the row returns to the row's own
last-focused child, composing and scrolling it into view — including when that child is currently
disposed, which a per-item `FocusRequester` provably cannot do. On first entry, with no remembered
child, it lands on the row's first focusable.

This requires `StaticContentRow` to accept an optional row-level `FocusRequester`. It replaces the
current `firstItemFR` threading through `ContentRowItem`/`OnNowRow` (`HomeScreen.kt:310`, `:315`,
`:320`, `:389`, `:432`, `:452`, `:466`), which exists only to reach index 0.

### FR-R236-2 — Down from the hero cannot be a dead key

Whatever the bridge resolves to, a Down press from the hero must always end with focus somewhere below
the hero. If the explicit bridge fails, fall through to Compose's native focus search
(`focusManager.moveFocus(FocusDirection.Down)`) rather than consuming the key and doing nothing.

`HeroCarousel`'s `onDown` currently consumes Down unconditionally (`dpadFocusable` treats a non-null
callback as "consumed"), which is what turns a failed bridge into a completely inert key. The callback
must be able to decline.

### FR-R236-3 — Down from the app bar has the same guarantee

`AppBar`'s `onDown` (`HomeScreen.kt:339-352`) uses the same `firstRowFR` in the hero-less case and is
subject to the identical failure. It gets the same treatment. Its hero case (scroll to top, then focus
the hero) is already correct and stays.

### FR-R236-4 — The R139 restore does not steal the bridge's target

With FR-R236-1 the row-level requester is independent of `restoreItemKey`, so the second failure window
above disappears by construction. State it as a requirement so a future refactor cannot reintroduce
`fr ?: rowFR` on the same element.

### FR-R236-5 — A regression guard for the class, not just the case

The repo has now shipped R200, R201, R223, R232 and a Settings focus-chain fix, all the same shape: a
`FocusRequester` pointed at a composable that may not be composed. Add a lint-level guard —
minimally, a documented convention plus a comment at each remaining call site, ideally a check in
`dpadFocusable`'s doc-tested contract — that a `FocusRequester` used as a cross-screen bridge is never
attached inside a `LazyRow`/`LazyColumn` item.

Sweep the other screens for the same pattern while here: `ChannelScreen`, `SeededBrowseScreen`,
`DiscoverScreen` and `UpcomingScreen` all build rows the same way.

## Non-goals

- No change to R137's retained scroll, R139's Back-return tile restore, or R55's back-to-top.
- No change to the hero's Left/Right paging, Ken Burns drift, or auto-advance.
- No change to the bring-into-view spec (R140) or row framing.
- No backend change.

## Acceptance

1. Stue TV, Home. Press Down into the first row, press Right eight times, press Up to the hero, press
   Down: focus enters the first row (on the tile it was last on) — **every time**, not sometimes.
2. Same, but instead of pressing Right: open the 9th tile, press Back to return, press Up, press Down.
   Same result.
3. Scroll several rows down, press Up repeatedly back to the hero, press Down: enters the first row.
4. Hero-less feed (a user whose config has no heroes): Down from the app bar enters the first row after
   the same horizontal-scroll manipulation.
5. A feed whose first row is the channel rail, and one whose first row is On Now: both behave the same.
6. No visible delay on the ordinary path — the common case must still resolve on the first frame.

## Source references

- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeScreen.kt`
  — `firstRowFR` `:181`, the "always-composed" comment `:182-196`, hero `onDown` `:273`,
  channel card `:299`, content-row threading `:310`/`:318-320`/`:389`/`:432`, On Now `:315`/`:452`/`:466`,
  AppBar `onDown` `:339-352`, entry-focus effect `:203-213`, back-to-top `:217-229`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/ContentRow.kt`
  — `focusRestorer()` `:220`, the R139 restore effect `:125-142`, per-item `fr` `:230-233`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/focus/FocusModifiers.kt`
  — `requestFocusRetrying` `:31-54` (and R200's note on what it was for), the
  "never one-per-item across a lazy list" rule `:62-71`, key consumption `:116-125`.
- Same failure shape, previously: `phase-R200-*`, `phase-R201-*`, `phase-R223-detail-focus-scroll-bugs.md`,
  `phase-R232-series-detail-nav-polish.md` (FR-R232-3), and the 2026-09-05 Settings focus-chain fix
  (`df22966e`).

## Open questions

1. **Not reproduced on-device.** Per the standing "no TV without asking" rule this was not tested on
   stue TV. The mechanism is read off the source and matches the reporter's own conditions precisely,
   but the *"sometimes"* qualifier means there may be a second contributing path (e.g. the LazyColumn
   disposing the whole first row while the hero is focused, which the hero's own
   `onFocusChanged { scrollToItem(0) }` should prevent but was not verified). The two acceptance
   reproductions above are deterministic and can be run first, before the fix, to confirm.
2. Does `focusRestorer()` on a `LazyRow` reliably compose-and-scroll a disposed remembered child on
   Android TV's Compose version in this project, or does it only restore among currently-composed
   children? This is the load-bearing assumption of FR-R236-1 and should be verified in isolation
   before the rest is built — if it does not, the bridge must instead scroll the row to index 0 first
   (awaited, then focus, per R232 FR-R232-3's sequencing lesson) rather than requesting focus
   concurrently.
3. R232 FR-R232-3 found that a concurrent scroll + `requestFocusRetrying` pair loses its retry budget
   to unrelated recomposition. If FR-R236-1's fallback ends up needing a scroll, it must use R232's
   await-then-focus sequencing, not a second concurrent pair.
