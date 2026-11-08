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
