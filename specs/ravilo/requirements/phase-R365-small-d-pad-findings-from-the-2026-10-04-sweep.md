# Phase R365 — Small D-pad findings from the 2026-10-04 sweep

> Owner, 2026-10-04: *"Please just do a bunch of navigating around on the TV and make sure that everything feels
> smooth and makes sense UI/UX wise. And spec any big or small bugs or things that could be improved."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`, = `main` for every screen below). Dev-reviewed 2026-10-04 against `main` `5210045a` (see the end; still `Planned`). Client only. The larger findings of the same
sweep are **R361** (a title that is gone), **R362** (Movies/Series), **R363** (Skip Intro), **R364** (My List) and
phase **269**'s 2026-10-04 amendment. Each item below is small and independent; they can be built and ticked one by one.

## Requirements

### FR-R365-1 — Search: *Clear* is reachable on a TV
**Seen:** with a query typed, the *Clear* label shows top right of Search, but no key reaches it (Up and Right from
the field do nothing); clearing takes the on-screen keyboard's delete key, one letter at a time.
**Why:** R277 FR-3 made it clickable on handsets only and left the TV deliberately (*"a focusable header control
there is a change someone should make deliberately, with a TV in front of them"*).
**Requirement (TV):** *Clear* is focusable when a query is present. **Right** from the field focuses it; OK clears the
query, puts focus back on the field (without raising the keyboard, R350 FR-13) and shows the suggestions; Left or
Down from *Clear* returns to the field. No query, no *Clear* (as today).

### FR-R365-2 — A title page: Right from the synopsis stays on the page
**Seen:** a film or series page, Down to the synopsis (R350's focus ring), **Right** → the app bar's **Search** icon.
**Requirement:** Right (and Left) on the synopsis do nothing; Up goes to the meta/genre row above, Down to the
buttons (unchanged).

### FR-R365-3 — A series page: Down after choosing a season lands on that season's first episode to watch
**Seen:** on the season pills, Right to *Season 7*, OK → Down lands on the card spatially under the pill (E03),
not on the start of the season.
**Requirement:** Down from the **open** season's pill focuses the season's primary episode: the first unwatched
episode in it (R346's counting), else E01; scrolled into view first. Down from a pill that is only focused (not
opened) is unchanged.

### FR-R365-4 — Discover · Coming Soon: Down lands on the selected chip
**Seen:** Down from the *Coming Soon* tab focuses the **Movies** chip (nearest under the tab), while **All** is the
selected one.
**Requirement:** Down from the tab strip into a chip row focuses the **selected** chip (R350 FR-5's rule for the tab
strip, applied to the chip row under it).

### FR-R365-5 — Back from Settings returns to the avatar
**Seen:** on Discover, avatar → *Settings* → Back: focus on the **Discover** tab (the page's default), not on the
avatar the menu was opened from.
**Requirement:** Back from a page opened from the profile menu (Settings, My List, Switch profile) returns focus to
the **avatar** on the page underneath.

### FR-R365-6 — A collection's Back-to-top puts focus at the top
**Seen:** a collection (channel) page with two rows, focus in the second row, **Back** scrolls the page to the top
but leaves focus on the second row's tile, now half off the bottom of the screen; the next Back leaves.
**Requirement:** the collection page's Back-to-top (R55) behaves like Home's and Movies': scroll to the top **and**
focus the app bar's active tab (or the first row's first tile when the page has no app-bar tab), so the second Back
leaves from where the viewer can see.

### FR-R365-7 — A Discover wall's browse page does not say where it came from twice
**Seen:** Discover → Networks → a network: breadcrumb *Discover · Networks*, then the subtitle *From "Discover ·
Networks" · 4 titles*.
**Requirement:** when a breadcrumb is shown, the subtitle carries only the count (*4 titles*).

### FR-R365-8 — Scrubbing moves the handle, not only a hairline
**Seen:** in the player, Up to the seek bar, Right ×120: the time reads *20:21* but the big round handle stays at
*0:01*; the place the video will jump to is a 4 dp white line (`SeekBar`'s "scrub ghost") near the end, with no time
beside it. Watching the handle, it looks as if nothing is happening.
**Requirement:** while scrubbing, the **handle** is drawn at the scrub position (with the focus ring), and the
played fill stays at the real position with a faint marker there, so the viewer sees both where they are and where
they are going. OK commits, Back cancels (unchanged).

### FR-R365-9 — Back after an automatic next episode returns to the episode that was playing
**Seen:** started S03E08 from the series page, the credits card advanced to S03E09, Back: focus on the **S03E08**
card (R350 FR-2: *the control that started playback*).
**Requirement:** when the player has moved on to another episode (next-up card, *Next*, the episode rail), Back
returns to the series page on **that** episode's season with its card focused. R350 FR-2 otherwise unchanged.

### FR-R365-10 — Back to Home's hero returns to the slide that was opened
**Seen:** Home's hero on its 5th slide (a series), OK → the series page → Back: the hero shows its **1st** slide
(focus on the hero), so the title just looked at is gone from view.
**Requirement:** Back to Home with the hero focused shows the slide that was opened, then auto-advance resumes
from there (R58's timer restarts).

## Acceptance

One D-pad walk per item on the TV path, each in a Robolectric test where the screen already has one (Search,
series page, Discover, browse), and a re-check on the Sony.

## Noted, not ours

- At 01:43 the TV's own *BootModeAppToForeground* timeout (Sony) returned the TV to its home screen while Ravilo sat
  idle on Search; Ravilo was not at fault (logcat: `Background timeout reached, starting home intent`).

## Dev review (2026-10-04, against `main` `5210045a`)

Read against each screen named below. Every finding reproduces from the code. FR-6's cause is the same
`requestFocus()` misreading R361's review found in the retry helpers. FR-7 is wider than one page. One item per FR;
two for the owner.

1. **FR-R365-1 (Search · Clear).** *Clear* is a plain `Text` on the TV (`SearchScreen.kt:300-316`; clickable on
   handsets only, by R277's own note). The field's `onPreviewKeyEvent` handles only Down (`:343-351`). Add:
   - in that handler, **Right** while the field is not editing (`edit.readOnly`) and the query is not empty → focus
     a `clearFR`. While the keyboard is up, Right stays the caret's;
   - on *Clear*, a `dpadFocusable(focusRequester = clearFR, onSelect, onLeft, onDown)`. Left and Down go back to the
     field. Up does nothing; it should not reach the app bar from here;
   - OK: **move focus to the field first, then clear the query**. *Clear* leaves the composition once the query is
     empty, and if it still held focus then, focus would fall to Compose's recovery (the app bar).
   Test: `SearchFocusTest` (Robolectric) — type, Right, OK: field focused, query empty, no keyboard.

2. **FR-R365-2 (synopsis Right).** `DetailSynopsis` passes no `onLeft`/`onRight` (`DetailSynopsis.kt` `dpadFocusable`),
   so native search finds the app bar's Search to the upper right. Add `onLeft = {}` and `onRight = {}` there. It is
   one shared component, so it covers the film and series pages at once. Up and Down stay with the callers.
   Test: `SeriesDetailFocusTest` gains "Right on the synopsis keeps it focused".

3. **FR-R365-3 (Down from the open season).** `SeasonPicker` has no Down handler. Down is native into the rail.
   The rail's `focusRestorer()` (`SeriesDetailScreen.kt` rail `ArrowRow`) has lost its saved card (the previous
   season's), so the search takes the card nearest under the pill. The rail has already scrolled to
   `railOpeningIndex(…)` (`:420-423`), so that card is composed at the left.
   - Use that index as the target; don't re-derive "first unwatched". `railOpeningIndex` is the primary episode when
     the season holds it, else the first unwatched, else the start. That is FR-3's rule plus the primary button,
     which is better. Reword FR-3 to "the card the rail opened on".
   - Give `SeasonPicker` an `onDownFromSelected` callback. Attach an `entryCardFR` to the card at that index, the way
     `returnCardFR` is attached (`:409`), and request it with the fixed `requestFocusRetrying` (R361's review, item
     3).
   - Key the rail's `focusRestorer` on `selectedSeasonIdx`, so a season change starts with nothing saved. R350
     FR-R350-1 found that a restorer can redirect a request aimed at a child.
   Test: `SeriesDetailFocusTest`: pill Right, OK, Down → the opening card.

4. **FR-R365-4 (Coming Soon chips).** `DiscoverSegmentBar` (`NavItems.kt:167`) takes `onUp` only, so Down is
   native, and the nearest chip under the tab is *Movies*. The chip row's `filter` is local state in `UpcomingContent`
   (`UpcomingScreen.kt:144`). Simplest fix, inside `FilterChips`: make the chip `Row` a `focusGroup()` with
   `focusProperties { onEnter = { frs[active].requestFocus() } }`. Any directional entry (Down from the tab, Up from
   the date rail) then lands on the selected chip. The chips are not lazy, so their requesters are always attached.
   Test: `DiscoverFocusTest`: the tab, Down → *All* (or whichever is selected).

5. **FR-R365-5 (Back from a page opened from the profile menu).** Pages put arrival focus on `navBarFR`, the active
   tab (for example `DiscoverScreen`'s entry). The avatar's requester is private to `AppBar` (`AppBar.kt:115`).
   - Add the same `AppBar` entry-focus parameter as R364 FR-3.
   - `RaviloApp` sets a one-shot "return to avatar" flag when the menu pushes Settings, My List or Your profile
     (`RaviloApp.kt:2515-2518`). The page under it reads the flag on arrival and clears it.
   - Switch profile resets the stack, so it needs nothing. A page with a pending tile restore never sees the flag,
     because a menu push is always a leaf push.
   Test: `SettingsFocusTest` or `DiscoverFocusTest`, via the menu.

6. **FR-R365-6 (collection Back-to-top): the cause is a shipped bug.** `ChannelScreen.kt:165-170` scrolls to the top,
   then `runCatching { heroFR.requestFocus() }.onFailure { channelBarFR.requestFocus() }`. Without a hero,
   `requestFocus()` returns `false`; it does not throw (Compose 1.9). So `onFailure` never runs, the bar is never
   focused, and focus stays on the second row's tile. That is exactly what was seen. Fix:
   `if (!heroFR.requestFocus()) channelBarFR.requestFocus()`, with the fixed retry for the hero after the scroll.
   Home's rule is the hero when there is one, else the bar (`HomeScreen.kt:256-263`), and this code meant the same.
   Word FR-6 as "the hero if the collection has one, else the app bar". "The first row's first tile" is never
   needed: a collection page always has the bar.

7. **FR-R365-7 (breadcrumb twice): every seeded page, one line.** `RaviloApp.kt:1672` sets
   `subtitle = From "<breadcrumb>"` whenever there is a breadcrumb. So every seeded page says its source twice: a
   row's *See all* (*Home* / *From "Home"*), a cast face, a genre chip and a Discover wall. R187 drew the subtitle as
   `From "<row>"`, with the row's name; the code passes the source instead. Setting `subtitle = null` there fixes
   all of them and leaves the count, per FR-7. See owner question A.

8. **FR-R365-8 (scrub handle).** `SeekBar` draws the handle at `played` and the scrub position only as a 4 dp bar
   (`PlayerScreen.kt:2482-2494`). While `scrubbing`, draw the handle (and its focus ring) at `scrubFrac`, and a small
   marker at `played`. The played fill stays at `played`. `SeekBar` is its own composable, so the player's register
   limit (R363's review, item 9) is not touched. The phone's bar (`PlayerHandsetChrome.kt`) is not in scope.
   Device check only.

9. **FR-R365-9 (Back after the player moved on).** `onNavigateToEpisode` (`RaviloApp.kt:2041`) replaces the player
   for the next episode. The series store's `SeriesReturnTarget` still holds the first episode's card (written once,
   at start, `SeriesDetailScreen.kt:405`).
   - Add `SeriesReturnFocus.EpisodeId(episodeId)`, written from `onNavigateToEpisode` into the series page's store
     (`storeRegistry["series:<name>:<seriesId>"]`).
   - Write it **only when that series page is in the stack below the player**. A player started from Home's
     Continue Watching returns to Home, and a target left in a retained store would fire on a later, unrelated visit.
   - The page resolves it with a pure `returnFocusFor(seasons, episodeId) → Episode(seasonIdx, cardId)`. That gives
     the season holding the episode, and the multi-episode card whose first id holds it. Unit-test it in
     `SeriesEpisodesTest`.
   See owner question B for shuffle.

10. **FR-R365-10 (hero slide on Back).** `HeroCarouselContent` holds `activeIndex` in `remember { 0 }`
    (`HeroCarousel.kt:115`). Only the top of the stack is composed, so Home rebuilds the carousel on Back at slide 1.
    - Hoist it into `HomeStore`, which already outlives the screen like `listState`.
    - Keep the hero's **id**, not its index. A `home_changed` between open and Back can re-order the heroes. Fall
      back to slide 1 when the id is gone.
    - The auto-advance restarts from that slide, because `resetTick` is fresh.
    - The channel page's hero has the same `remember`. Lean: give it the same treatment in this item.

11. **Tests overall.** Items 1–5 each go into an existing Robolectric focus test. Items 6, 8 and 10 are device checks
    (no Home or channel focus test exists; item 6's logic is two lines). Item 9's resolver is a `commonTest`.

**For the owner.**
- **A.** *A browse page opened from a row, a cast face, a genre or a Discover wall all repeat their source under the
  title (`From "Home"` under the "Home" breadcrumb). Drop that line on all of them, keeping only the count?* Lean:
  yes. It is one line of code, and none of them gains from the repeat.
- **B.** *When you started a shuffle from the series page and it has moved on several episodes, should Back land on
  Shuffle (where you started) or on the episode that was playing?* Lean: Shuffle. It is what you pressed, and the
  shuffled episode may sit in any season. A pick from the episode rail leaves the shuffle (R343), so after that Back
  lands on the episode.


## Owner decisions (2026-10-04, after the dev review)

1. **The *From "…"* line under a browse page's title is dropped on every seeded browse page**; the count stays.
2. **Back from a shuffle lands on Shuffle**, not on the episode that was playing.
