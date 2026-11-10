# The TV screen can be set smaller, and its header slimmer

Task: DEB-3964

## Goal

Browsing Ravilo on a TV feels a little too big. The owner wants everything on the screen smaller —
**not crushed together, and never too small to read** — so that wandering through the library when you
don't know what to watch feels good. Two new global settings in jellystructure's Ravilo config do it:

- **Screen scale** — the whole TV screen drawn at 100 / 95 / 90 / 85 %, with its spacing shrinking in
  proportion (so nothing gets closer together than it looks today) and its text shrinking by only half as
  much (so it stays readable).
- **Header** — Standard (today's 60 dp bar) or Slim, a lower top bar whose geometry is drawn in a design
  round first.

## Owner decisions (2026-11-08, DEB-3964 card `140b0f26`)

- **Picked:** Screen scale and Slim header. **Not picked:** hiding the header while browsing, and a denser
  row layout. The owner's words: *"i don't [want] it to feel more crushed together, i'm more thinking the
  sizes of everything could be smaller … but importantly not too small to read. And maybe some small ui
  changes (if you have some ideas). The most important thing is to have it feel nice and very nice to
  browse around when you do not know what to watch."*
- **One value for everyone**, set in jellystructure. No per-viewer choice in Ravilo's own Settings.

## Current state (verified in code, 2026-11-08)

- `RaviloConfig.uiDensity` (`ui_density`: COMPACT / COZY / COMFORTABLE) is the admin's **Content size**,
  in Ravilo config → Behaviour & preferences. It multiplies **tile** width and height only
  (`UiDensity.tileScale()`: 0.82 / 0.91 / 1.0), via `LocalTileScale` in `Tile.kt`. Text, row gaps
  (`RaviloDimens.rowGap` 36 dp), track padding (20 dp), side margins (48 dp), the app bar (60 dp), the
  hero and the detail pages are untouched by it. Prod's global row is on COMPACT.
- A TV is a 960 × 540 dp screen. At prod's settings one Home row takes ≈ 300 dp and ≈ 1.7 rows fit once
  the hero has scrolled away.
- The TV `AppBar` (`ravilo-ui/…/components/AppBar.kt`) is `RaviloDimens.appBarHeight` = 60 dp: a 26 dp
  brand mark and 22 sp wordmark, 15 sp nav pills (12 × 6 dp padding, 11 dp corners), search, the clock
  and the avatar. It overlays the hero transparently and turns solid on scroll (R62).
- The smallest text on the TV browse surfaces is 10 sp (badges, captions).

## Requirements

### Screen scale

**FR-1 · The setting.** `RaviloConfig` gains `@SerialName("ui_scale_pct") uiScalePct: Int = 100`.
`RaviloConfigService.normalize()` snaps it to the nearest of **100, 95, 90, 85** (anything above 100 →
100, below 85 → 85). Global only: it rides the existing `/api/tv/config` payload and its rev, like
`ui_density`. An app that does not know the field ignores it; a server that does not send it means 100.

**FR-2 · Layout shrinks in proportion, text by half as much.** With scale `s = uiScalePct / 100`, the
app supplies, around the screens FR-3 names, a `LocalDensity` of

- `density = base.density × s`, so every dp — tiles, gaps, padding, margins, the bar, the hero's
  dp-sized parts — shrinks by `s` and keeps its proportions (nothing moves closer together relative to
  its size); and
- `fontScale = base.fontScale × t / s` with `t = 1 − (1 − s) / 2`, so text renders at `t` of today's
  size: 97.5 % at 95, 95 % at 90, **92.5 % at 85**. Text therefore grows slightly *relative to* the
  layout around it, which is the "not too small to read" half of the ask.

At 100 the provider is not applied at all (today's tree, byte for byte).

**FR-3 · Where it applies.** On a TV platform only (`isTvPlatform`: Android TV, Tizen), on every
destination **except** the playing ones (`Dest.Player`, `Dest.LiveTv`, `Dest.Remote` — the
`playingFullscreen` set in `RaviloApp`). Watching is not browsing, and subtitle size has its own setting.
The web app in a desktop browser (TV layout family, not a TV platform), the phone and the desktop are
unchanged: they have browser zoom or their own layouts.

**FR-4 · Nothing that reads the real screen is fooled.** `isHandset`, `compact` (R145), the layout family
(R337), and anything else that classifies the device from window size × density reads the **base**
density, computed outside the scaled provider. A TV at 85 % (≈ 1129 × 635 logical dp) is still a TV.

**FR-5 · It multiplies with Content size.** `LocalTileScale` keeps applying inside the scaled tree. At
COMPACT + 85 % a poster is 155 × 0.82 × 0.85 ≈ 108 dp of today's screen wide.

**FR-6 · Live, and from the first frame.** A change in the admin reaches a running TV on the next config
rev, recomposing in place without leaving the page. The value is stored in `HomeSnapshotCache` beside
`tileScale` (R212), so a cold start draws its first frame at the saved scale and never jumps.

**FR-7 · The admin control.** In Ravilo config → Behaviour & preferences (global scope), next to Content
size: a select **Screen scale · whole TV screen** with options `100 % (default)`, `95 %`, `90 %`,
`85 % (smallest)`. `collectConfig` reads it; per-user scope preserves the stored value as it does for
`gridColumns` (R174 FR-D1). Existing CSS only. Admin strings are English like the rest of the editor.

### Slim header

**FR-8 · The setting.** `RaviloConfig` gains `@SerialName("header_style") headerStyle: String = "standard"`
(a string, so a future style never breaks an installed app; an unknown value draws as `standard`).
`normalize()` maps anything but `standard` / `slim` to `standard`. Global only. Admin control: a select
**Header** (`Standard`, `Slim`) next to Screen scale.

**FR-9 · Slim is drawn first.** Slim lowers the TV `AppBar` from 60 dp to the height the design round on
DEB-3964 settles (brief target 44–48 dp) and resizes its parts — brand mark, wordmark, nav pills, search,
clock, avatar — to that round's numbers. It stays always visible and keeps R62's transparent-to-solid
behaviour, its focus order and every D-pad path. Everything that offsets content by the bar reads the
bar's height from `RaviloDimens`, never a literal, so a Slim bar moves the content up by exactly what it
saves. **No build of FR-9 starts before the round is adopted into this file as a dated amendment.**

**FR-10 · Slim and scale compose.** A Slim header at 90 % is the Slim geometry × 0.9 (FR-2 applies to it
like everything else).

## Non-goals

- No per-viewer scale or header choice (owner, 2026-11-08). The wire leaves room for one later.
- No change to row spacing *relative to* tile size — the owner asked for smaller, not tighter.
- The header does not hide or scroll away.
- No change to the player, Live TV or the remote screen.

## Acceptance

1. At 100 % and Standard, every TV screen is pixel-identical to today's build.
2. At 90 % on the stue TV, Home shows more rows and more posters across than at 100 %, with the gaps
   visibly in the same proportion to the tiles, and text visibly *less* reduced than the tiles.
3. At 85 %, a QA seat reads every label on Home, a row's focus line (R240 L), a movie page and a series
   page from sofa distance — the 10 sp badges and captions included. **If the smallest labels are not
   readable at 85 %, that is reported to the owner, not fixed by shrinking less:** dropping 85 from the
   list is his call.
4. The phone, the desktop app and the web app in a desktop browser are unchanged at every scale.
5. Changing Screen scale in the admin re-draws a running TV in place within one config rev; a restart
   draws the first frame at the saved scale.
6. A playing title, Live TV and the remote screen are unchanged at every scale.
7. An app built before this spec, talking to a server after it, draws as it does today.

## Amendment 2026-11-08 — the round is adopted, and two small browse changes join

The design round (DEB-3965, `design/incoming/2026-11-08-tv-slim-header/`, commit `c36de5d9`) came back,
and the owner picked on card `1dd5e02d`: **Slim at 48 dp**, **Surprise me as a labelled pill**, **quieter
row headings that keep See All**, and **the focus ring fixed on both bars**. Every pick was the
recommended one. FR-9 is now buildable, and FR-11 to FR-14 are new.

**FR-9 (completed) · The Slim geometry.** With `header_style = slim` the TV `AppBar` is drawn with these
numbers. Standard keeps today's, which are listed for comparison.

| Part | Standard | Slim |
|---|---|---|
| Bar height (`RaviloDimens.appBarHeight` for the style) | 60 dp | **48 dp** |
| Brand mark | 26 dp | **20 dp** |
| Wordmark | 22 sp, −1 sp tracking | **17 sp, −0.8 sp** |
| Brand mark ↔ wordmark gap | 8 dp | **7 dp** |
| Nav pill type | 15 sp | **14 sp** |
| Nav pill padding | 12 × 6 dp | **11 × 5 dp** |
| Nav pill corner | 11 dp | **9 dp** |
| Gap between top-level items | 18 dp | **14 dp** |
| Search / avatar circle | 40 dp | **32 dp** |
| Search glyph | 22 dp | **18 dp** |
| Avatar initials | 14 sp | **12 sp** |
| Clock | 15 sp | **13 sp** |
| Side gutter | 48 dp | 48 dp (unchanged) |

Discover's title block is not changed. `DiscoverScreen` already starts at `appBarHeight + 24.dp`, so a
Slim bar lifts it by 12 dp on its own.

**FR-11 · The focus ring on the round buttons stands clear of the fill, on both bars.** The search and
avatar circles draw their 2 dp focus ring **2 dp outside** the circle instead of flush against it. That
is the treatment `RaviloButton` already uses (`focusRing(…, outset = …)`). Today the ring (`#8e82ff`) sits
directly on the accent fill (`#7b6ef0`), a 4 % difference that can't be seen from the sofa. This applies
to Standard and Slim alike (owner, 2026-11-08). It is a fix to today's bar, not part of the Slim setting.

**FR-12 · Surprise me, a labelled pill in the bar.** On the TV bar (both styles), in the right-hand cluster
before search, a pill with a sparkle glyph and the label `nav.surprise` ("Surprise me"). The glyph is
deliberately not the shuffle arrows, which `detail.shuffle` already owns. It sits in the bar's focus
order between the last nav tab and search. Pressing it:

- calls a new route `GET /api/tv/surprise?exclude=<id,…>`, which answers `{"id": "…", "kind": "MOVIE" |
  "SERIES"}`. The server picks uniformly at random from what the viewer can see in their libraries:
  **movies they have never played**, and **series where they have played no episode**. Ids in `exclude`
  are left out. When nothing is left, the route answers `204`;
- opens that title's normal detail page, with a small `detail.kicker_surprise` ("SURPRISE") kicker above
  the title and an **Another one** (`detail.another`) ghost button beside Play, at the shipped button
  size;
- **Another one** asks the route again, with every id shown in this run of surprises in `exclude`, and
  *replaces* the detail page rather than stacking on it. One Back always returns to where Surprise me was
  pressed;
- on a `204` the viewer stays where they are and sees a short note `surprise.none` ("You've seen
  everything here"). From the detail page, Another one gets the same note and the page stays.

*Clarified 2026-11-09 (DEB-3984).* "Played" for the pick is **marked played or carrying a resume position**: a film left half-watched
is not offered, and nor is a series whose own row or any episode is played or in progress. An id the server cannot read a state for
counts as unplayed. A failed ask (network, 5xx) leaves the viewer where they are, with no note: `surprise.none` is for a `204` only.

**FR-13 · Surprise me is gated, so an older server never shows a dead pill.** `RaviloConfig` gains
`@SerialName("surprise_me") surpriseMe: Boolean = false`. A server with FR-12's route always sends `true`.
An older server never sends it, so the pill stays hidden. (An absent `/api` route answers `200 text/html`,
not 404, so the app cannot discover the route by probing.) This is a capability field only. It has no
admin control.

**FR-14 · Quieter row headings, See All kept.** On the TV layout a content row's heading drops from
18 sp to **16 sp**. Beside it, after a ` · ` separator, sits the row's count at **13 sp in the dim ink**
(the same style as `ContentRow`'s existing `trailingInfo`). The count is `seedTotalCount ?: items.size`,
so it needs no new wire field. **See All stays where it is, unchanged**, at the row's right end. A row
with no meaningful count (one item or fewer) shows no count. This applies at every header style and
screen scale. The phone and the desktop keep their headings.

**Strings (FR-12).** Four new keys go in `i18n/en.json`, `da.json` and `fo.json`:

| Key | en | da | fo |
|---|---|---|---|
| `nav.surprise` | Surprise me | Overrask mig | Yvirraska meg |
| `detail.another` | Another one | En mere | Eitt annað |
| `detail.kicker_surprise` | SURPRISE | OVERRASKELSE | YVIRRASKAN |
| `surprise.none` | You've seen everything here | Du har set det hele | Tú hevur sæð alt her |

The Faroese strings (the round's draft, and `surprise.none` mine) need a native read before release (*Vel eitt fyri mig*
was offered as an alternative for `nav.surprise`).

**Wire.** A new route (FR-12) and one optional field (FR-13), both additive. Nothing is removed or
retyped.

**Acceptance, added.**
8. With Slim, the scrolled bar measures 48 dp at 100 % and the content starts 12 dp higher than with
   Standard. Focus order and the transparent-to-solid behaviour are unchanged.
9. The search and avatar focus ring is visible from the sofa on both bars.
10. Surprise me never opens a movie the viewer has played or a series they have started. Another one
    never repeats a title within one run. Back returns to the page where the pill was pressed.
11. Against a server without FR-12, the pill is absent.
12. Home rows read `Heading · N` at 16 sp, and every row that showed See All before still shows it.

## Amendment 2026-11-11 — Surprise me leaves the bar and ends Home (DEB-4269)

The owner tried the pill on the stue TV (DEB-799, 2026-11-10 23:32Z): *"I do not like the "Surprise me" button on
the top. Let's rather move it to the bottom of the home screen, so it's after all the content rows."* FR-12's first
paragraph and FR-13's "pill" are replaced as below. What pressing it does (the route, the page it opens, the SURPRISE
kicker, Another one, the `204` note, the 2026-11-09 clarification of "played") is **unchanged**, and so is the server.

**FR-12 (amended) · Surprise me is the last thing on Home.**

- **The bar no longer has it**, on any page, in either header style. The bar's focus walk is back to what it was
  before DEB-3984: Right from the last tab (or the now-playing pill) goes to search, and Left from search comes back.
- **Home ends with one Surprise me button**, after every row Home draws: after the last content row, or after On now
  when On now is the last row. Its top sits one `raviloRowGap` below that row, and its left edge is at the rows' side
  gutter (`RaviloDimens.screenPadH`), the same line the row headings start on. There is no heading above it.
- **Its look is the shipped ghost button**, not a tile, so it never reads as a title: `RaviloButton`'s `GHOST` style
  at its TV size (44 dp, 18 dp side padding, 15 sp, the ghost hairline at rest, the accent border, outset ring, scale
  and lift on focus), with FR-12's sparkle glyph (about 17 dp, in the label's colour) 8 dp before the label
  `nav.surprise`. If `RaviloButton` grows a leading-glyph slot for this, the slot defaults to none, so that every
  existing button stays unchanged. No new string, no new design round: the parts are the shipped button and the
  shipped glyph.
- **Focus.** D-pad Down from any tile of the last row lands on the button, whichever column it is in. Up from the
  button returns to the tile of that row the viewer came down from, or to the row's first tile if that tile is gone.
  Left, Right and Down on the button do nothing. Back on the button is Home's Back, the same as from any row (R55):
  to the top. Focusing the button clears the focus line and backdrop (R240, R242), as focusing On now or the channel
  rail does. Bringing it into view uses Home's existing bring-into-view band and bottom padding, so the last row's
  tiles stay in view above it.
- **The return.** FR-12's "one Back always returns to where Surprise me was pressed" now means: Back from the page it
  opened (after any number of Another one) lands on Home with the button focused (R139's return rule, with the
  button as the remembered place).
- **When Home has no rows**, the button is still drawn whenever Home has loaded. It sits below the hero, or at the top
  of the content under the bar when there is no hero either, and Down from the hero or the bar lands on it, as it
  would land on a first row. A library with nothing left to pick answers `204`, and FR-12's note covers it. Home's
  loading and error states draw no button.
- **Only Home has it.** Movies, Shows, Discover, Music and every other page have no Surprise me. The pages it opens
  keep the kicker and Another one.
- **TV platforms only**, as before (`isTvPlatform`: Android TV, Tizen). The phone, the desktop and the web app in a
  desktop browser are unchanged. The button is inside the scaled tree, so FR-2 applies to it.

**FR-13 (amended) · the gate now hides the button.** Unchanged in substance: without `surprise_me: true` from the
server, Home draws no button and leaves no gap where it would be. The gate is still not in the Home snapshot, so on a
cold start the button can appear only after the first config answer. That is harmless now, because it is below every
row and off screen when Home opens.

**Wire.** None. The route, the field and the strings are as shipped.

**Acceptance, amended.** 10 now ends "…Back returns to Home with Surprise me focused." 11 now reads "Against a server
without FR-12, Home has no Surprise me button." Added:

13. No page's top bar has Surprise me, in either header style. Right from the last tab reaches search.
14. On the stue TV, Down from any tile of Home's last row focuses Surprise me, Up returns to the same tile, and the
    button's label and glyph read from the sofa at 100 % and 85 %.
15. A Home with no rows still shows Surprise me, and Down from the hero (or the bar, with no hero) reaches it.

## Amendment 2026-11-11 — no Header setting: the bar is Standard at the top and Slim once the page has scrolled

Task: DEB-4270. The owner tried the `.debug` build on the stue TV (DEB-799, 2026-11-10): *"I also do not like our
"Header · the TV's top bar" "standard" vs "slim" setting. Let's make it standard by default, but as soon as we have
scrolled a bit down it switches to the slim, and back to standard when on the top again."* The Header setting goes.
Its two geometries stay as FR-9's table draws them, and the page's scroll position chooses between them. Screen scale
(FR-1 to FR-7) and FR-11 to FR-14 are unchanged.

**Supersedes:** FR-8's admin control and its meaning, FR-9's "a Slim bar moves the content up by exactly what it
saves", FR-10's wording and acceptance 8. FR-9's table is kept. Its *Slim* column is now the **collapsed** bar.

**FR-8 (replaced) · The Header setting goes, and its field stays on the wire.**
- *Admin.* The **Header** select (`#beh-header-style`) is removed from Ravilo config → Behaviour & preferences, and
  `collectConfig` stops reading it.
- *Server.* `RaviloConfig.headerStyle` (`header_style`) stays in the model, so a client that sends it, and a stored
  config that carries it, still decode. The server accepts any value, and `normalize()` writes `standard` whatever
  arrives. The payload sends `"header_style":"standard"` whatever is stored, so a config saved as `slim` before
  this amendment is never served as `slim`.
- *Old clients.* A build from DEB-3967 to now, which reads the field, draws the Standard bar always, as every build
  did before DEB-3967. An app older than DEB-3967 never read the field.
- *New clients.* Nothing reads the field. The app does not provide a style from config, and `HomeSnapshotCache`
  stops writing or reading `headerStyle`. The cache decodes with `RaviloWireJson`, which ignores unknown keys, so
  deleting the property is safe. The property in `shared/…/Models.kt` is marked deprecated; removing it is a later
  release's call.

**FR-15 · The bar collapses while the page is scrolled.** On a TV platform (`isTvPlatform`), the `AppBar` is drawn
**collapsed** (FR-9's Slim column, 48 dp) when both of these hold, and **Standard** (60 dp) otherwise:
- the page has scrolled past the threshold below, and
- focus is not inside the bar. Moving focus up into the bar means the viewer is at the top in the owner's sense, so
  the bar opens to Standard and its tabs are full size while the viewer uses them.

*Threshold.* The page counts as scrolled once its list or grid is past **24 dp**: `firstVisibleItemIndex > 0`, or
`firstVisibleItemScrollOffset` over 24 dp in px. That 24 dp is the gap every page already leaves between the bar and
its first content (`appBarHeight + 24.dp`), so the bar collapses at the moment content would start to pass under the
Standard bar. Within 24 dp of the top, the bar is Standard. The same 24 dp is used in both directions. A D-pad step
scrolls in discrete jumps, so it crosses the line once and the bar cannot flap. The predicate is a `derivedStateOf`
boolean, like R109's `appBarScrolled`, so a page is not recomposed on every scroll frame. R62's transparent-to-solid
switch keeps its own predicate (any scroll at all) and is not changed. Between 0 and 24 dp the bar is solid and
Standard, which is how it looks today.

**FR-16 · The content never moves for the bar.** The bar overlays the page, as it already does over the hero (R62).
Every resting offset and every scroll target that reads `RaviloDimens.appBarHeight` reads the **Standard 60 dp again,
always**. The resting offsets are the `appBarHeight + 24.dp` paddings and spacers, `HeroScrims`, `StaleContentBanner`
and the music lists' top padding. The scroll targets are Home's `minTopPx` (FR-R240-9 / R257) and `SeriesDetailScreen`'s
`belowBarPx`. So at every scroll position the page lays out exactly as today's Standard build does, and the focused
tile stays where it is when the bar changes. A collapsed bar uncovers 12 dp of whatever has scrolled under it, and
nothing else changes. `LocalHeaderStyle` and the style-dependent `raviloAppBar` go. The bar reads its live geometry
from its own state, and nothing outside the bar can read it.

**FR-17 · The transition.**
- *Timing.* One `animateFloatAsState` fraction runs 0 → 1 (Standard → collapsed) as a `tween(180)`, the same
  duration as R62's colour change, so the two move together when they coincide.
- *What it drives.* Every number in FR-9's table is linearly interpolated from that one fraction: height, brand mark,
  wordmark size and tracking, gaps, pill type, padding and corner, circle, glyph, initials and clock.
- *Where it is read.* The fraction is read only inside `AppBar`, so the transition recomposes the bar and nothing
  under it.
- *Anchoring.* The bar is anchored to the top of the screen, so its bottom edge rises 12 dp and its parts stay
  vertically centred within it. The 48 dp side gutter does not change.
- *Focus ring.* A focus ring inside the bar follows the item it rings. FR-15 means the bar never collapses while it
  holds focus.

**FR-18 · Which pages.** The bar collapses on the TV pages whose bar sits over their own scrolling list or grid:
- Home, the movie page, the series page, Discover, the seeded See All grid and the channel page. Each already
  derives `appBarScrolled` / `barScrolled` from its list state.
- The Movies / Series / My List grid (`BrowseScreen`, which passes no `scrolled` today). It gains the same predicate
  from its grid state, so switching tabs never shows a different bar on a page that looks the same.
- Music browse and music listen, which also derive `scrolled` from their list.

A TV page with no scroll position of its own keeps the Standard bar always: the Live TV guide and the music player
screens, which pass `scrolled = true`, and every caller that passes nothing. The phone (its handset row), the desktop
(`DesktopToolbar`) and the web app in a desktop browser are untouched. They never drew Slim. The playing destinations
draw no bar.

**FR-10 (reworded) · Collapse and scale compose.** At Screen scale `s`, both ends of the transition are FR-9's
numbers × `s` (FR-2 applies to the bar like everything else). At 90 %, the bar runs from 54 to 43.2 dp of today's
screen.

**Non-goals, added.**
- No per-viewer or per-page choice, and no admin control for the threshold or the collapsed height.
- The bar still never hides or scrolls away. It only shortens.
- No change to R62's solid/transparent rule, R250's opaque bar, the bar's focus order or any D-pad path.

**Wire.** Nothing is added, removed or retyped. `header_style` is still accepted and still sent, now always as
`standard`.

**Acceptance 8 (replaced) and added.**
8. On Home, the movie page, the series page and the Movies grid on the stue TV, the bar measures 60 dp at the top of
   the page. It measures 48 dp after the first D-pad step that moves the content more than 24 dp, and it is back to
   60 dp once the page returns to its top. Pressing Up into the bar on a scrolled page opens it to 60 dp.
13. Across the collapse and the expand, the focused tile's on-screen position does not change. To check, take
    screenshots one frame before the transition and once it has settled, and compare the focused tile's top edge:
    0 px difference. A row heading never sits under the bar.
14. Frame timing on the stue TV, with the frame-timing profile installed. Over 10 collapse/expand cycles on Home, the
    transition frames are compared against a control: the same number of D-pad steps further down the same page, a
    scrolled-to-scrolled move with no transition. The janky-frame share in the transition must not be higher than
    the control's.
15. The admin has no Header control. A config saved with `header_style: "slim"`, by an old admin or straight to the
    API, is accepted and reads back as `standard`. A build from before this amendment, against a server after it,
    draws the Standard bar and never collapses.
16. The Live TV guide and the music player keep the 60 dp bar. The phone, the desktop app and the web app in a
    desktop browser are unchanged.
