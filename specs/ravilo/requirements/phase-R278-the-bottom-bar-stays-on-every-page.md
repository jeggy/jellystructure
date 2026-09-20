# Phase R278 — The bottom bar stays, except where the picture is

> Owner, on a Pixel 9: *"Some pages lose the bottom navbar. Let's make sure all pages have it except
> while watching actual media and except the media detail pages. Going to Discover and then clicking
> something gives you a list of items, missing the bottom navbar."*

## Status

`✓ Built` — design-authored 2026-09-20 from an owner report on a Pixel 9 Pro, **built and verified on
that Pixel the same day**. Not dev-reviewed.

### Build (2026-09-20)

`bottomBarShows(dest)` is the new predicate, read by all three call sites; `bottomItemOf` goes back to
answering only "which item is this page". The lit item is `bottomItemOf(dest)` or the nearest
answering entry below it on the stack; `RaviloBottomNav` takes a genuinely nullable `selected`, parks
the pill's last position in a `LaunchedEffect` and does not draw it when nothing is lit. The three
non-Home bar items switch from `replaceTop` to `resetTo`, and `alreadyHere` reads `bottomItemOf(dest)`
rather than the lit item.

⚠ One claim in the first draft of this spec was **wrong and is corrected above**: the account screens
do have a lit item, because they are pushed over a page that answers. The no-pill path is a guard.

### Verified on the Pixel 9 Pro, 2026-09-20 (debug)

Acceptance 1–6 pass. Discover → TV 2's list: the bar is there, Discover lit, grid not clipped. Tap
**Home** from that list: Home, and one Back exits the app — proving the list was not left underneath
it (with `replaceTop` it would have popped back to the list instead). A series detail: no bar; Back
returns to the list with Discover lit. Settings from the profile menu: the bar is there, lighting the
page it was opened over.

Acceptance 7 (the mini bar docking on a pushed page) is **not verified** — no cast was running. It
reads the same predicate as the content padding, which is verified.

## Context — one function answering two questions

`bottomItemOf(dest)` maps four destinations to the four bar items. It is also, everywhere it is used,
the test for **whether the bar exists at all** — the bar's own `if`, the content's bottom padding,
and the cast mini bar's dock all read `bottomItemOf(dest) != null`.

Those are different questions. *Which item is lit* has only four answers. *Is there a bar* has many
more, and conflating them means every pushed screen loses it: a taxonomy or seeded list opened from
Discover, a channel, Search's Seerr overflow, My List, Settings, Your profile, the TV guide. The
viewer's way back to Home becomes the system Back alone, on the one screen shape where a bar would
help most — a long list you scrolled into from somewhere else.

R267 FR-R267-7 made the absence deliberate — *"absent on anything pushed over a page (detail, player,
remote, account): those sit on higher layers and have their own Back"* — and on the evidence of the
built app that was the wrong call for everything except the two cases below. **This phase supersedes
that part of FR-R267-7.**

## Functional requirements

### FR-R278-1 — the bar is on every page but three kinds

It is **absent** only where:

- **the picture is playing** — `Player`, `LiveTv`, and `CastRemote`, which is the remote for a picture
  playing somewhere else. These own the whole panel and are immersive by R261's rule;
- **the page is one title's detail** — `MovieDetail`, `SeriesDetail`, and Discover's own two detail
  screens (`DiscoverItem`, `UpcomingDetail`), which are the same shape for a title the household does
  not have yet. A detail page is a decision about one thing, with Play as its subject;
- **there is no profile yet** — `Login` and `ProfilePicker`. This one is mine, not the owner's, and it
  is the smaller claim: a bar offering four pages to someone who has not chosen a profile offers four
  pages that do not exist for them, and R275 FR-R275-2 already keeps those two screens on the
  platform's own Back for the same reason.

Everywhere else it is present, including every pushed list and the account screens.

### FR-R278-2 — a pushed page lights the section it belongs to

`bottomItemOf` keeps answering only for the four pages themselves. When the current destination is not
one of them, the lit item is the **nearest one below it on the stack** — so a list opened from
Discover keeps Discover lit, a channel opened from Home keeps Home lit, and the pill does not jump or
disappear as you go one level in.

This includes the screens reached from the profile menu — My List, Settings, Your profile — which are
pushed **over** whatever page was showing, so that page stays lit. It is the convention every tabbed
app follows (a tab stays lit while you are inside its stack) and the honest reading of where Back
will take you. It does not contradict FR-R267-5d: Profile still never takes the pill, because
*opening the menu* changes no page.

If nothing below answers either, **no item is lit** and the pill is not drawn. In a running app that
cannot happen — everything is pushed over a section eventually, and the two destinations that answer
nothing (`Login`, `ProfilePicker`) have no bar at all — so this is a guard, not a behaviour. It
exists because `selectedIndex` otherwise defaults to `0`, which would light **Home** on a page that
is not Home: a wrong statement is worse than no statement.

### FR-R278-3 — tapping an item from a pushed page goes to that page

A bar item tapped while on a pushed screen **resets** to that section rather than replacing the top of
the stack. Replacing it would leave the section it was pushed from underneath — `[Discover, Search]`,
where Back then returns to Discover rather than following FR-R275-4's ladder. A bottom bar is a
top-level switch; taking it should not leave a stack behind.

"Already here" keeps meaning **this destination is that page**, not "this page belongs to that
section". Otherwise tapping Discover from a list opened out of Discover would count as a re-tap and do
nothing, which is the bug this phase started from, one level down.

### FR-R278-4 — everything that offsets by the bar follows it

The content's bottom padding and the cast mini bar's dock read the same "is there a bar" answer, so a
pushed list is not clipped by a bar that is now over it, and the mini bar keeps docking against it.
One predicate, read in three places, per R267 FR-R267-12's reason for existing.

## Non-goals

- The TV and the web app's wide layout: all of this is inside the existing `handset` gate.
- Per-section back stacks. There is one stack, and this phase does not add four.
- Changing what any of the pushed screens themselves draw.

## Acceptance

On a Pixel 9 (debug build):

1. Discover → a taxonomy or seeded list: the bar is there, **Discover** is lit, and the list is not
   clipped by it.
2. From that list, tap **Home**: Home, with Home lit and nothing left on the stack behind it.
3. Home → a channel: the bar is there with Home lit. Back returns to Home.
4. Open a movie or series detail: **no bar**. Back returns to the list, with the bar and the right
   item lit.
5. Play something: no bar. Leave the player: the bar is back.
6. Profile menu → Settings, and → My List: the bar is there, lighting the page they were opened over.
   Tapping an item leaves for that page.
7. The cast mini bar, while a cast runs, docks against the bar on a pushed page as it does on Home.
