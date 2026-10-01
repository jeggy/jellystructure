# Phase R350 — D-pad first-class: the series page, Back, Search, Discover, the player and focus you can see

> Owner, 2026-10-02, after an evening with Ravilo on a Sony BRAVIA (960 × 540 dp, release build): *"We want dpad users
> to get like they have first class support."* Eight findings from that session, plus the siblings found while fixing
> them.

## Status

`Planned` — written 2026-10-02 (dev-authored) from the owner's TV session, before any fix. Client-only (`:ravilo-ui`);
no wire change, no server change. Number given by the coordinator.

**Supersedes, in part:**
- **R178 FR-RV-SEL1-1** (hiding the chrome resets the player's focus to Play) — see FR-R350-7. R178's Select guard
  (FR-RV-SEL1-2/3) stays.
- **R251 FR-R251-1/2** (a key that finds the chrome hidden only reveals it) for Left, Right and Up — see FR-R350-7.
- **R346 FR-R346-3**'s opening-season rule — see FR-R350-3. Its Play fallback and counts stay.
- **R277 FR-R277-1**'s TV half (the keyboard raised on arrival) — see FR-R350-4. The phone half stays.

## Requirements

### FR-R350-1 — Down from the hero's primary button focuses the open season's pill
**Seen:** Play → Down (Season 1) → Right, Right (Shuffle) → Down (rail) → Up (Shuffle) → Up (Play) → Down lands on
**Shuffle**, not on the open season.
**Cause:** the pill row carries `focusRestorer()`. In Compose 1.9 a `requestFocus()` on a child also runs the
restorer's enter rule, which sends focus to the last focused child: Down from Play asks for the open season's pill
and gets the restored one.
**Requirement:** Down from Play always focuses the open season's pill (R138/R343). The row keeps its memory for one
direction only: **Up from the rail** returns to the pill that was last focused. Any other entry (a request from
Play, the app bar's Down) goes where it was sent.

### FR-R350-2 — Back from the player returns to the control that started playback
**Seen:** Shuffle → player → Back put focus on *Resume* with the page scrolled to the top.
**Cause:** only the top of the navigation stack is composed. Coming back rebuilds the series page, which always
focuses Play (and re-runs the opening-season rule, so a season the viewer picked was lost too).
**Requirement:** the series page's store remembers which control started playback — Play, Shuffle, or an episode
card (a multi-episode file's card counts as one) — and which season was open. Coming back from the player (or the
cast remote) the page reopens on that season, scrolled so the control is on screen below the app bar, with focus
on it. Read once: any later arrival focuses Play as before. If the card is gone (the season no longer holds it),
focus goes to Play.

### FR-R350-3 — The page opens on the season that holds the primary button's episode
**Seen:** a series whose button reads *Resume · S13E19* (R306's Continue Watching episode) opened on Season 1 (the
first season with anything unwatched); Down focused Season 1 and the rail showed S01E08.
**Requirement:** the opening season is the season holding `primaryEpisodeId` — whatever the primary button plays
(the Continue Watching episode, an episode in progress, the first unwatched counted episode, or S01E01 on a finished
series). The episode rail opens scrolled to that episode. When there is no primary episode the R346 rule stays.
Phone and desktop share the code.

### FR-R350-4 — Search: Back leaves, and the keyboard comes on OK
**Seen:** from the results, the first Back moved focus into the field and raised the keyboard, the second closed the
keyboard, the third left.
**Requirement (TV):**
- Back from the results or the suggestions grid leaves Search, back to where the viewer came from.
- Back while the keyboard is up closes the keyboard (the system's own behaviour); Back from the field leaves.
- Arriving at Search focuses the field **without** raising the keyboard; **OK on the field** raises it. Up from the
  grid's first row returns to the field, also without the keyboard.
- R295's return to the opened result is kept.
**Phone:** unchanged (R277: nothing raised on arrival; tapping the field or the bar's Search raises it; Back from a
scrolled grid scrolls to the top first).

### FR-R350-5 — Discover: the app bar's Down lands on the tab strip
**Seen:** Down from the top bar skipped the tabs (Networks · Studios · Genres · Coming Soon · Request) and landed in
the wall's first tile. Up from the wall did reach the tabs.
**Requirement:** top bar → the selected tab → the content, both ways. With no tabs (R310's empty Discover) the
bar's Down goes to the content as before.

### FR-R350-6 — The player's episode rail is headed by its season
**Seen:** the rail header read *S01E01* while episode 3 played.
**Cause:** the header was `kicker.substringBefore("·")`, written for R346's old `S1 · E5` kicker. Since R346 the
kicker is `S01E05` and the whole code came through.
**Requirement:** the header is the season in the viewer's language — *Season 1* · *Sæson 1* · *Sesong 1*
(`detail.season` + the number), and the season's own name for Specials. Same on the phone's episode sheet.

### FR-R350-7 — The player's chrome stays while paused, and a key that finds it hidden still acts
**Seen:** paused, the chrome auto-hid after a few seconds; the next Right only revealed it (R251) and focus had gone
back to Play (R178), so every Right press after a pause cost an extra press.
**Requirement (TV):**
- **Paused ⇒ the chrome stays up** (the phone already does, R244). Pressing play re-arms the 3.6 s hide.
- **Hiding never moves focus.** The control the viewer last focused is still focused when the chrome comes back.
- **Left, Right and Up** that find the chrome hidden reveal it **and** move, from that remembered control (Left/Right
  on the seek bar scrub, as they do when the chrome is up).
- **Down** that finds the chrome hidden reveals it and moves only within the transport (seek bar → Play); it never
  opens the episode rail from a hidden chrome.
- **OK** that finds it hidden stays R178's play/pause (never fires a hidden control) and puts focus on Play, so the
  highlighted control matches what OK just did.
- The Skip Intro pill, the next-up card, the picker and the episode rail are unchanged.

**Why this and not R251:** R251's rule (*"→ → OK means the same thing whether or not the chrome auto-hid"*) is kept
better by not moving focus at all: the hide timer now changes only what is drawn, never what a key does. Under R251
a viewer who waited and one who didn't reached different controls anyway (hidden: the first → was swallowed). The
one key that keeps a hidden-chrome rule is OK, because it is the one that fires a control.

### FR-R350-8 — Every focusable thing shows it is focused
**Seen:** nothing marked the Home hero as focused on arrival; the series page's Play looked the same before and after
the first key.
**Requirement (TV, and a desktop on the keyboard):**
- The Home hero draws the design's inset ring (`.hero-hit.focused`) while focused.
- A focused `RaviloButton` draws the design's ring outside the button (`.btn.focused`), so the primary button reads
  as focused without a second state to compare it with.
- A focused synopsis draws the design's ring (`.hero-syn.dsyn.focused`), not only a brighter ink.
- The trailer overlay's Close draws a ring when focused.
A handset draws none of this (R298).

### FR-R350-9 — The same class of bug elsewhere
- **Movies / Series browse grid:** Up from any tile in the first row goes to the facet bar (its first chip), never
  past it to the app bar's search or avatar above the right-hand tiles.

### The Mac app (coordinator, 2026-10-02 — the same shared series page, keyboard arrows on a computer)

**FR-R350-10 — An episode card names its episode `S01E05`.** The cards read *E1 · {title}* (and a multi-episode
card's list *E1 · …*). R346 FR-R346-5 is *one spelling on every viewer screen*, with no exemption for a card inside
its season, and the desktop design draws `S01E01` under each card. So the card reads *S01E01 · {title}*. The number
badge on the still stays a bare number (it is a badge, not a code).

**FR-R350-11 — The arrow keys move focus on a computer as the D-pad does on a TV.** On the Mac, Down from a season
pill did nothing, and Up, Right and Down from an episode's *Mark watched* did nothing (Tab worked).
**Cause:** Compose Multiplatform's desktop owner turns only Tab, Shift+Tab and Back into focus moves; Android turns
an unhandled arrow into Compose's 2-D focus search. Every place the app leaves a direction to that search (lazy
rows, the toggle under a card, the pills' Down) was dead on the desktop.
**Requirement:** on the desktop, an arrow no focused element consumed asks the same focus search (one modifier at
the app root). Android unchanged.

**FR-R350-12 — The season pills never hide one behind the window's edge on a computer.** At 1280 px a series with
seven seasons, Specials and Shuffle had Shuffle cut off at the right edge, and a mouse cannot scroll a row sideways.
**Requirement:** on the desktop the pill row wraps onto a second line instead of scrolling; Left/Right still walk
the pills in order, Down goes to the line below and from the last line to the rail. On the TV the row scrolls and a
focused pill is always brought fully into view (R250's gutter).

**FR-R350-2 also covers the Mac's report** that returning from the player reset the series page's scroll position.

## Non-goals
- The sign-in and server-setup screens (another phase is changing them).
- Redesigning the player's focus model or the TV's transport order.
- Back from a related title or a cast face on the series page (it still lands on Play) — recorded below.

## Acceptance (TV, release build)
1. Series with 3+ seasons and Shuffle: Play → Down (open season) → Right ×2 → Down → Up (Shuffle) → Up (Play) →
   Down: the open season's pill.
2. Shuffle → player → Back: focus on Shuffle, pills on screen below the app bar. An episode card → player → Back:
   that card, on the same season.
3. A series whose button reads *Resume · S13E19*: the page opens on Season 13, the rail shows E19; Down from Play
   focuses Season 13.
4. Search → type → Down into the results → Back: back on the page Search was opened from, one press. Search: the
   field is focused, no keyboard; OK raises it; Back closes it; Back again leaves.
5. Discover: Down from the top bar lands on the selected tab; Down again the wall; Up the tab; Up the bar.
6. Player on episode 3: the rail header reads *Season 1*.
7. Pause, wait 10 s: the chrome stays. Play, wait 4 s: it hides; Right ×2 moves two controls from where focus was.
8. Home on arrival: the hero has a ring. A series page on arrival: Play has a ring.
9. Movies: Up from the last tile of the first row lands on a facet chip.

## Verification
Robolectric key-by-key walks under `:ravilo-ui:testDebugUnitTest` (R343's `SeriesDetailFocusTest` and siblings) for
FR-1/2/3/4/5/9, pure tests for FR-6/7, plus the release APK and `check-player-dex.sh`.
