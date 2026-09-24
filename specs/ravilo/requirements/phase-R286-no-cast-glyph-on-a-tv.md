# Phase R286 — the cast glyph is not for a TV

> Owner, looking at the stue TV: *"I do not want the Chromecast icon on tv devices."*

## Status

**Built 2026-09-21** (`6afda557`, the same day it was written: seven lines in `components/Cast.kt`),
design-authored from an owner report observed on the stue TV. **Dev-reviewed 2026-09-24 against `main`
`9d2636bb`** — the code is FR-R286-1 verbatim, FR-R286-2 holds by construction, acceptance 4–5 hold from
the diff; **acceptance 1–3 (the stue TV) have not been run** and need the owner's go-ahead.

## Context

`CastButton` (`components/Cast.kt:173`) renders whenever `LocalCast.current != null` — that is, whenever
the **server** says casting is configured. It asks nothing about the device it is drawing on, so the
living-room TV draws a Chromecast glyph in its top row, between *Uppdaga* and the search icon.

Two separate things are wrong with it there.

**It is backwards.** Casting sends playback *to* a screen. The TV **is** the screen: 236 and R264 make
a TV a receiver the phone drives, and R266 would make it a Cast Connect receiver. A household does not
cast from the living-room TV to the bedroom TV, and nothing in the product has ever proposed that they
should.

**It cannot be operated.** The TV app bar's D-pad chain is explicit, not layout-derived: a nav item's
`onRight` goes to `searchFR`, and `SearchIcon`'s `onLeft` goes back to `allFRs.last()` — the last nav
item. `CastButton()` sits between them in the `Row` and **in neither hop**. So on a TV it is an
affordance the viewer can see, cannot focus, and cannot press. That is worse than a wrong button; it
is a control that lies about being a control.

This is why the fix is a deletion and not a focus-chain repair: there is no behaviour behind it worth
making reachable on a TV.

## Non-goals

- **The phone and the web app keep it.** R265's whole point is that a phone sends to a TV; R267 put the
  glyph in the handset top row deliberately. This phase narrows *where* it draws, never what it does.
- **The receiver side.** What a TV does when something is cast *to* it is 236/R264/R266's business and
  is untouched.
- **`RaviloConfig.cast` / `screens`.** No config, route, DTO or backend change. The server keeps
  reporting what it reports; the client stops drawing it on one class of device.

## Functional requirements

### FR-R286-1 — a TV never draws the cast glyph

`CastButton` returns without composing anything when `isTvPlatform` is true.

The gate goes **inside `CastButton`**, not at its three call sites (`AppBar`'s handset row, `AppBar`'s
wide row, `PlayerScreen`'s `castSlot`). One gate is the difference between a rule and a habit: a fourth
call site added later inherits it for free, which is exactly how this glyph reached the player's chrome
in the first place.

`isTvPlatform` is the existing seam (`Platform.kt:14`), the same one R254 uses to keep J on TVs and
`isHandset` consults before it looks at any dimension. It is a platform fact, not a size heuristic —
R256 is the standing reminder that a 540 dp-short-side TV is still a TV.

### FR-R286-2 — the row closes up, and the focus chain is untouched

With the glyph gone the right cluster is search · clock · avatar, and the `Row`'s own `spacedBy` closes
the gap — the same "present or absent, never a gap" rule FR-R267-4 states for the handset.

No `FocusRequester`, no `onLeft`/`onRight` hop and no `dpadFocusable` changes. The chain already skipped
the glyph, so removing it must move nothing: if a D-pad hop has to change, something else is wrong and
this phase has the wrong diagnosis.

## Acceptance

1. The stue TV's top row is *Heim · Filmar · Seriar · Uppdaga* … search · clock · avatar, with no cast
   glyph, while the server still has Chromecast configured.
2. Right from the last nav item still lands on search; Left from search still lands on the last nav
   item. Unchanged, because it never went through the glyph.
3. The player's chrome on a TV draws no cast glyph either.
4. A phone with the same server still draws it, and the sheet still opens.
5. No i18n key is added or removed.

## Open questions

1. **Should the web app on a desktop browser keep it?** It does after this phase — `isTvPlatform` is
   false there, and a browser on a laptop can legitimately send to a TV. If the owner meant "only the
   phone", that is a different gate (`isHandset`), and a narrower one than the report asks for.
   *Dev review:* stands as built — the wasm actual of `isTvPlatform` is `false`, so the browser keeps it.

## Dev review (2026-09-24, against `main` `9d2636bb`)

This one was built before it could be reviewed, so the review is against the code that shipped.

1. **FR-R286-1 is in the code as written.** `CastButton` (`components/Cast.kt:175-181`) opens with
   `if (isTvPlatform) return`, before `LocalCast.current` is even read, with the spec's own reasoning
   as its comment. `isTvPlatform` is the `expect val` at `ui/Platform.kt:14` (the spec's path is right,
   the file sits one directory up from `seams/`). The gate is inside the component, so the three call
   sites — `AppBar.kt:182` (handset row), `AppBar.kt:278` (wide row) and `PlayerScreen.kt:1813` (the
   handset chrome's `castSlot`) — inherit it, as FR-R286-1 asks.
2. **FR-R286-2 holds by construction.** `6afda557` touched `Cast.kt`, the spec and a STATUS row — nothing
   in `AppBar.kt`. The wide row's `CastButton()` sits between the `weight(1f)` spacer and `SearchIcon`
   (`AppBar.kt:275-282`); when it composes nothing, the `Row`'s `spacedBy` closes the gap, and
   `SearchIcon`'s `onLeft = { allFRs.last().requestFocus() }` (`:282`) never went through the glyph.
   Acceptance 2 is a tautology of the diff, which is what the FR wanted.
3. **One direct `PlatformCastButton` lives outside the gate, and that is fine.** `CastRemoteScreen.kt:139`
   calls the platform button itself rather than `CastButton`. A TV cannot reach `Dest.CastRemote` — it
   is pushed only from a phone's mini bar after a cast the TV cannot start — so the rule holds; a
   one-line comment there would keep it from being the "fourth call site" the spec warns about.
4. **Acceptance 4 and 5 hold from the diff.** The phone path is untouched below the gate (`Cast.kt:182`
   onward is the R245/R265 code as it was), and the commit adds no string to any `i18n/*.json`.
5. **Acceptance 1–3 are the stue TV's, and have not been run.** The commit message records the owner's
   observation, not a verification. Per the house rule the TV is not touched without asking; the row
   says `✓ Built` with the on-device check pending, which is what the STATUS conventions mean by
   nuance in the Focus cell.

**Net effect.** Nothing to change. Flip the STATUS row, and run the three TV lines when the owner says go.
