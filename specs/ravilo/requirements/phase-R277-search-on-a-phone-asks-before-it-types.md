# Phase R277 — Search on a phone: the keyboard comes up when you ask for it

> Owner, on a Pixel 9: opening Search should **not** throw the keyboard up. Tapping the field should,
> and so should tapping **Search in the bottom bar while already on Search** — every time, including
> after Back has just dismissed the keyboard. *"Basic rule: if you are on the search page and click
> the search icon again, it should show the keyboard and focus the input."*
>
> Two more from the same screen: the **Clear** button does nothing at all, and the page wastes the
> top of the screen on a bar it does not have.

## Status

`✓ Built` — design-authored 2026-09-20 from an owner report on a Pixel 9 Pro, **built and verified on
that Pixel the same day**. Not dev-reviewed.

### Build (2026-09-20)

- **FR-R277-1** — both auto-focus effects are gated on `!LocalHandset.current`; the TV path is
  byte-for-byte what shipped, moved behind one boolean.
- **FR-R277-2** — `Dest.Search` gains `focusInput`, the bar's `BottomNavItem.SEARCH` branch stops
  being a no-op when already here (`replaceTop(Dest.Search(name, focusInput = alreadyHere))`), and
  the screen consumes it with `replaceTop(dest.copy(focusInput = false))`. `requestFocus()` **and**
  `show()` are both called: Back dismisses the IME without moving focus, so on the second and later
  taps `show()` is the half doing the work.
- **FR-R277-3** — the header's Clear becomes `clickable` **on a handset only**, padded to clear the
  46 dp touch floor without moving its baseline. The gate is not about pointers: `clickable` is
  focusable, and an ungated one would have added a node to the TV's D-pad graph.
- **FR-R277-4** — top padding is `16.dp` on a handset, `appBarHeight + 24.dp` everywhere else.

### Verified on the Pixel 9 Pro, 2026-09-20 (debug)

`dumpsys input_method`'s `mInputShown` is the ground truth throughout.

Acceptance 1–6 pass. Home → Search: `false`, field unfocused, **two full rows of suggestions now
visible** where one and a sliver were before. Tap Search again: `true`. Back: `false`. Tap Search
again: `true` — the repeat is the requirement and it holds. Tapping the field: `true`. Typing
"kalani" returns its one result; **Clear** empties the field, brings the suggestions back and leaves
the keyboard up because focus never moved. Home → Search again: `false`. A result opened and Back:
the query and its result are still there (R259) and the keyboard is still down.

Acceptance 7 (the TV unchanged) is **reasoned, not observed** — every branch this phase adds is
guarded by `LocalHandset`, which `isHandset()` returns `false` for on a TV before it looks at
anything else — since this session has no TV to test on.

## Context

`SearchScreen` was written for a TV, where the rules are the opposite of a phone's. On a TV the
viewer arrives with a D-pad and nothing else, so the field takes focus on entry and the on-screen
keyboard is the screen — `LaunchedEffect(Unit) { requestFocus(); show() }`, plus a second effect that
re-focuses and re-shows whenever focus leaves the results grid.

On a phone both of those are wrong in the same way: the keyboard is **half the screen**, and it
arrives before the viewer has said they want to type. It covers the suggestions they came to look at,
and (before R274) it took the bottom bar with it. A phone viewer who wants to type taps the field;
that is what a field is for.

Three smaller things on the same screen:

- The header's **Clear** is a plain `Text`. It has never been clickable — no `clickable`, no handler.
  It renders as an accent-coloured affordance and does nothing, which is worse than not drawing it.
- The page reserves `appBarHeight + 24.dp` at the top. On a phone R267 gave Search **no top row at
  all**, so that is 84 dp of nothing above the title, on the screen with the least room to spare.
- Re-tapping Search in the bottom bar while on Search is currently a no-op (`if (!alreadyHere)`),
  so the bar's own item is the one control that cannot bring the keyboard back.

## Functional requirements

### FR-R277-1 — arriving at Search does not raise the keyboard

On a handset, entering the Search page leaves the field unfocused and the keyboard down. The
suggestions are visible in full, which is the reason they are drawn.

Off the handset — the TV and a wide web window — today's behaviour is kept **verbatim**: focus on
entry, keyboard shown, and the return-from-grid re-focus. A TV viewer has no other way in, and this
phase must not cost them one.

### FR-R277-2 — tapping Search while on Search raises it, every time

Tapping the bottom bar's Search item while the Search page is already showing focuses the field and
shows the keyboard. **Every time**, including immediately after Back dismissed it.

That "every time" is the whole requirement: a boolean that is set once and stays set fires once and
is then indistinguishable from off. The flag rides on the destination and is **consumed** by the
screen that acts on it — the same shape R243/R262 already use for `Dest.Discover.focusSegment` — so
the next tap sets a fresh one.

Tapping the field itself does the same thing, by being a text field; nothing is added for it.

### FR-R277-3 — Clear clears

The header's **Clear** becomes a real control: it empties the query and puts the page back to its
suggestions. It is drawn only while there is something to clear, as it is today.

It deliberately does **not** grab focus or raise the keyboard. Only the field and the bar's Search
item do that (FR-R277-1/-2), and this keeps that rule with no exception — note that clearing *while
typing* leaves the keyboard up anyway, because focus never moves, which is the behaviour a viewer
expects without anyone having to special-case it.

**Handset only**, and not for the reason it looks like. `Modifier.clickable` is *focusable*, so
applying it unconditionally would put a new node in the TV's D-pad graph on this screen — reachable
by Up from the field, ahead of whatever the viewer expects to land on. Giving the TV a focusable
header control is a deliberate change to a focus chain this project has paid for repeatedly, and it
belongs to whoever makes it with a TV in front of them. The TV keeps the inert label it has today.

### FR-R277-4 — the title uses the space the app bar is not occupying

On a handset the page's top padding is the ordinary gap above a heading, not `appBarHeight + 24.dp`.
The safe-area inset above it is untouched — this reclaims the space reserved for a bar that is not
drawn, not the status bar's.

This is **not** a decision that Search should stay without a top row (R267 lists that as open). It
makes the page honest about the row it has today; if Search is later given one, this padding goes
back with it.

## Non-goals

- Anything about what Search returns, how it is paced, or the results grid's own focus behaviour.
- The TV's search flow, which is the one this screen was built for and keeps.
- Giving Search a top row (R267's own open item).

## Acceptance

On a Pixel 9 (debug build), navigating to and away from the page:

1. Home → Search: the keyboard stays down, the field is not focused, and the suggestions are visible.
2. Tap the field: the keyboard comes up and typing works.
3. Back: the keyboard goes down and the page stays. Tap **Search** in the bottom bar: the keyboard
   comes back. Back, tap again: it comes back again — not just the first time.
4. Type a query, tap **Clear**: the field empties and the suggestions return.
5. Search → Home → Search: still no keyboard on arrival.
6. Open a result, Back: the field still carries the query its results belong to (R259), and the
   keyboard is still down.
7. On the TV, Search is unchanged: the field is focused on entry and the keyboard is up.
