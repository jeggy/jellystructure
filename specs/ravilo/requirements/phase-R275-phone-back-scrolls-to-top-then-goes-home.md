# Phase R275 — On a phone, Back scrolls to the top, then goes Home, then leaves

> The owner, on a Pixel 9, against the R267 bottom bar: *"just as on the TV, Back should scroll all
> the way to the top, and then Back — if not on the Home screen — should go back to the Home screen.
> Only here, if you press Back again while being at the top, should it actually close the app."*
>
> Three presses, three different meanings, in a fixed order. The TV has had the first one since R55.
> The phone has had none of them: its Back either pops a pushed screen or closes the app.

## Status

`✓ Built` — design-authored 2026-09-20 from an owner report on a Pixel 9 Pro, **built and verified on
that Pixel the same day**. Not dev-reviewed.

### Build (2026-09-20)

- **FR-R275-1/-5** — `backToTopOnBack` becomes a `@Composable` modifier with two paths: the TV keeps
  its `onKeyEvent` verbatim, and off the TV **on a handset** it registers its two lambdas with
  `BackToTopRegistry` through `LocalBackToTop`. Last-in-wins, released only by identity. No call site
  changed.
- **FR-R275-2/-3/-4** — `RaviloApp`'s `PlatformBackHandler` becomes the five-branch `when` the phase
  specifies, with `backGoesHome = handset && bottomItemOf(dest) != null && dest !is Dest.Home` added
  to its `enabled` guard — the missing guard was why Back on Library was reaching the platform
  default at all.

### Verified on the Pixel 9 Pro, 2026-09-20 (debug)

Every acceptance item passes. Home scrolled two rows → Back returns it to the top with the app still
open → Back again exits to the launcher. Library scrolled → Back to the top → Back to Home, pill on
Home. Search with the keyboard up → the platform's own Back dismisses the keyboard first, then the
rules apply. Discover → Back to Home. A title opened from Library → Back returns to **Library**, pill
still on Library. The profile menu closes on Back with Library untouched underneath it.

## Context — why the phone got none of this

`Modifier.backToTopOnBack` (`focus/BackToTop.kt`, R55) is the TV's scroll-to-top, and R262 FR-R262-6
gated it on `isTvPlatform` for a good reason: it works by **consuming a Compose `KeyEvent`**, and a
phone's system Back never becomes one. Android's `OnBackPressedDispatcher` intercepts the gesture and
the button before Compose sees a key at all — the same gap R267's own bug fixes describe twice, and
the reason `PlatformBackHandler` exists. So on a phone the modifier is not merely disabled, it is
**unreachable**: nine call sites that look like they handle Back, and none of them can.

What the phone does instead is `RaviloApp.kt`'s `PlatformBackHandler`: close the profile menu, else
pop when something is on the stack, else exit at Home. The four bottom-bar pages all use
`replaceTop`, so **the stack is size 1 on every one of them** — which means on Library, Search or
Discover the handler is not enabled at all and the platform default runs, measured by R260 to finish
the Activity outright. Back on Library closes the app.

## Functional requirements

### FR-R275-1 — a scrolled page goes to the top first

On a handset, Back on a page that is scrolled away from the top scrolls it back to the top and
**consumes the press**. This is R55's rule, reached through the dispatcher rather than through a key
event, and it applies to every page that already declares itself with `backToTopOnBack` — Home,
Library, Search, Discover, a channel, a seeded browse, the Seerr search. Nothing new is declared: the
call sites keep the shape they have (`atTop` + `onBackToTop`), so a page cannot end up handling Back
differently from how it handles it on the TV.

`onBackToTop` moves focus to a top target as well as scrolling, which matters on the TV and is inert
on touch; that is the call sites' business and is unchanged here.

### FR-R275-2 — at the top and not on Home, Back returns to Home

On a handset, at the top of a **bottom-bar page that is not Home** (Library, Search, Discover), Back
resets to Home — the same destination and the same `resetTo` the Home item in the bar itself uses, so
the two cannot drift.

Deliberately scoped to the bottom-bar pages, by the same `bottomItemOf(dest)` the bar is drawn from.
A pushed screen keeps popping (that is what a stack is for), and Login / ProfilePicker keep the
platform default exactly as `rememberExitAction`'s doc comment requires — an app that has nobody
signed in has no Home to go to.

### FR-R275-3 — at the top of Home, Back leaves

Unchanged from today: `exitApp()`, which is a deliberate exit rather than the platform's Activity
finish. Stated here because it is the third of the three presses and the phase is about their order.

### FR-R275-4 — one order, stated once

Back resolves in exactly this order, and the first match wins:

1. the profile menu is open → close it;
2. the current page is scrolled → scroll it to the top;
3. something is on the stack → pop it;
4. a bottom-bar page other than Home → go Home;
5. Home → leave.

The player and Live TV still own their own Back entirely (`ownsItsOwnBack`), which this phase does
not touch: their two-step chrome-then-exit was live-fixed once already and is the single source of
truth while one of them is on screen.

### FR-R275-5 — the TV keeps the mechanism it has

`backToTopOnBack` keeps its key-event path verbatim for `isTvPlatform`, and gains a second path — a
registration the dispatcher-level handler can consult — used only off the TV and only on a handset.
Two mechanisms because the platforms genuinely deliver Back differently; **one API**, so a screen
declares its top once.

The registration is last-in-wins with an identity check on release, so the screen arriving during an
`AnimatedContent` transition owns Back and the screen leaving cannot clear it on the way out.

## Non-goals

- Changing what the TV does with Back anywhere, including whether a TV's Back at the top of a
  non-Home section should go Home. It currently exits; that is a separate question with its own
  device to be answered on.
- The web app's keyboard Escape/Backspace, which R262 FR-R262-6 deliberately leaves popping. This
  phase is reached through the platform's Back action, which on the web is the browser's own Back —
  and only on a handset-sized window, where the bottom bar is drawn.
- Any change to `backToTopOnBack`'s call sites or to what `onBackToTop` does.

## Acceptance

On a Pixel 9 (debug build), pressing the system Back gesture, navigating to and away from each page:

1. Home, scrolled down two rows: Back scrolls to the top and the app stays open. Back again closes it.
2. Library, scrolled: Back scrolls to the top. Back again lands on Home, with the pill on Home. Back
   again closes the app.
3. Search with the keyboard up: the first Back dismisses the keyboard (the platform's own behaviour,
   not ours), then the rules above apply.
4. Discover: Back returns to Home from any segment, and the segment strip is not what Back steps
   through.
5. A title opened from Library: Back returns to Library — not to Home — and the pill is still on
   Library.
6. While the profile menu is open, Back closes the menu and nothing underneath it moves.
7. During playback: Back still hides the chrome first and exits second.
