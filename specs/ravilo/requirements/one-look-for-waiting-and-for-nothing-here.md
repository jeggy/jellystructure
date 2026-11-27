# One look for waiting and for nothing-here

Task: DEB-5837

> Owner, 2026-11-26 (DEB-5687): *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app
> beautiful in our edge cases, but remember that QoE and performence is most important. We are also now getting more
> and more users."* He picked this piece, polish group C, on the card "Ravilo polish: what to build" the same day.

## Scope

Client only, in `ravilo-ui` commonMain, so it reaches **Android TV, the Android phone, Ravilo Web and the desktop app
(Mac and Linux)** from one change. **Samsung (`ravilo-screen`) and the Chromecast receiver are out:** they do not compile
`ravilo-ui`, Samsung's look is the separate alignment question from DEB-5687, and its robustness gaps are DEB-5685's.

It extends [`a-page-never-opens-black.md`](a-page-never-opens-black.md) (DEB-4091), which gave the music pages and the
two film grids page-shaped skeletons, to every other page that waits. It keeps DEB-2204 FR-4 (reduced motion) and FR-5
(the fade-in over a loading view) from [`focus-rings-and-loaded-content-fade-in.md`](focus-rings-and-loaded-content-fade-in.md),
R212's seeded Home and R304's placeholder tile (FR-R304-7). It finishes what R205 and R262 FR-2 left as a centred line.
It amends DEB-4390 FR-1 in [`artwork-is-on-screen-as-soon-as-the-app-is.md`](artwork-is-on-screen-as-soon-as-the-app-is.md),
with a dated note in that file.

Sibling pieces own the neighbours: a failure's wording and way out, including a failed search and a failed read drawn as
"nothing", is DEB-5835. A title page opening with the tile's own art is DEB-5839. TV approve's busy state is DEB-5835's.
The player's start screen and buffering spinners are designed playback screens (R290, R218) and are not touched.

## Why

Read from the code on 2026-11-27 (DEB-5687's evidence, rows 16, 17, 18 and 21, re-checked at `921645877`):

- **Eight surfaces wait as a bare word or a blank page.** "Loading..." (`loading`) on the collection page
  (`ChannelScreen.kt:203`), Settings on TV and phone (`SettingsScreen.kt:314`) and on the desktop (`:612`), the Discover
  walls (`DiscoverScreen.kt:277`), and both searches (`SearchScreen.kt:432`, `SeerrSearchScreen.kt:204`). Nothing at all
  on a request detail (`DiscoverDetailScreen.kt:80`), the TV code screen before the code arrives (`TvSignInScreen.kt:146`)
  and music search while it asks (`MusicBrowseScreen.kt:431`).
- **Seven loading looks:** shimmer, bare text, an accent spinner (`LiveTvGuideScreen.kt:191`), a grey spinner
  (`UpcomingDetailScreen.kt:146`), two player spinners, nothing, and greyed content.
- **The skeletons are not the page's shape.** `HomeLoadingShell` (`Shimmer.kt:142`) is a fixed 600 dp hero and two rows
  of 210x315 dp posters; a real poster tile is 155x232 dp (`Tile.kt:64`), 138 dp wide on the desktop, and the real hero
  is a share of the screen (`HomeScreen.kt:212-219`). `DetailLoadingShell` (`Shimmer.kt:173`) is a fixed 620 dp block
  where the real hero fills the list's height (R295). Neither draws the app bar. So rows change size when content lands.
- **The shimmer recomposes every frame.** `rememberShimmerBrush` reads the animated value in composition
  (`Shimmer.kt:56-77`), so every skeleton recomposes 60 times a second, and Home's skeleton is on the cold-start path.
- **"No titles match these filters." with no filter set** (`browse.empty`, `SeededBrowseScreen.kt:1154-1158`), including
  Continue Watching's See all. A Home whose feed has no rows draws nothing. Music lists that answer empty draw nothing.
- **Failed artwork leaves an anonymous block.** `RemoteImage` paints the same placeholder colour for an error as for a
  load (`ImageLoader.kt:97-110`), so a failed poster is a flat tile where a missing one gets a title fallback
  (`Tile.kt:266-287`). A failed channel logo is an empty card (`ChannelCard.kt:265`). `TitleLogo` already falls back to the
  title text (`TitleLogo.kt:57-73`) and is the model here.

## Requirements

### FR-1 — One skeleton look, drawn without recomposing

- Every skeleton in `ravilo-ui` uses one brush, `rememberShimmerBrush`, and one primitive, `Modifier.skeleton(shape)`,
  that paints it. `MusicLoading.kt` already uses the brush and moves to the primitive.
- **The sweep is drawn in the draw phase.** The animated value is read inside a draw lambda (`drawWithCache` or
  `drawBehind`), never in composition. A skeleton recomposes zero times per animation frame.
- DEB-4091 FR-2 is unchanged: under `LocalReducedMotion`, or with the window hidden, the skeleton is its base colour and
  starts no animation.
- A skeleton is never focusable. A control that is drawn during loading today (the TV code screen's footer button, a page
  title) stays drawn and keeps focus where it is.

**Performance.** This FR removes per-frame work from the cold start. No skeleton adds a fetch, a delay or a wait: content
replaces it in the frame it arrives, with DEB-2204 FR-5's fade. A seeded Home (R212), a seeded music page (DEB-4091 FR-5)
and a kept store on Back show no skeleton, as today.

### FR-2 — No page waits as a word or a blank

Each surface below shows a skeleton of its own content region, under whatever it already draws above that region:

| Surface | Skeleton |
|---|---|
| Collection page (`ChannelScreen`) | its hero band and rows, as FR-3's Home |
| Settings, TV/phone and desktop | setting rows at the loaded row height, under the page title and the desktop tabs |
| Discover walls (`DiscoverScreen`) | the wall's tile grid in the loaded columns (`TileShimmerGrid`) |
| Request detail (`DiscoverDetailScreen`) | FR-3's detail skeleton |
| Upcoming detail (`UpcomingDetailScreen`) | FR-3's detail skeleton, replacing the grey spinner |
| Live TV guide (`LiveTvGuideScreen`) | channel rows at the loaded row height, replacing the accent spinner |
| TV code screen, before the code | the code's box at its loaded size; the footer button stays |
| Title search and request search, first query | the result grid in the loaded columns |
| Music search, while it asks | the result rows of its first group |

- A search that already shows results keeps them while the next query loads; only a first query shows the skeleton.
  The count line says nothing while the first query loads; it never reads "Loading...".
- After this, no `ravilo-ui` screen draws `str("loading")` as a page's wait and no screen's `Loading` branch is empty.
  The `loading` key stays in the tables: the remote lines still use it for buffering, which is DEB-5836's wording.

### FR-3 — A skeleton is the page's own shape

A skeleton measures what the loaded page measures, so nothing jumps when content lands.

- **Built from the loaded composables.** Skeleton tiles are `Tile(placeholder = true)` (FR-R304-7) at the tile variant and
  width the loaded row uses, poster by default. Row headings, row gaps and padding use the same `raviloRowGap`,
  `raviloHPad` and `raviloItemSpacing` the loaded rows use. `TileShimmer`'s fixed 210x315 dp goes.
- **Home:** the real app bar, drawn as when loaded. The hero band's height comes from the same function the loaded Home
  uses (`HomeScreen.kt:212-219`), with the feed's default share (56 %, R27) until a feed is known, and the desktop rule
  (R337). Then as many skeleton rows as fill the screen, each a heading line and a row of tiles.
- **Film and series page:** the real app bar, a hero band that fills the list's visible height (R295), with the title,
  meta and action lines where the loaded hero puts them. No fixed 620 dp.
- **Acceptance of shape:** on each client's layout (TV, phone portrait, desktop), the first skeleton row's top and
  height equal the loaded first row's, within 1 dp, for a feed whose first row is posters at the default hero share.

### FR-4 — One way to show that an action is busy

Where something waits that has no page shape (a sign-in step, a remote command), it shows one composable,
`WaitIndicator`, in two sizes (14 dp inline, 36 dp alone) and one colour (`textSecondary`). It replaces the
`CircularProgressIndicator` calls outside the film and live players: SSO (`SsoSignInViews.kt:97`), the remote's command
and buffering marks (`OneRemoteScreen.kt`, `OneRemoteMusic.kt`), the screens sheet (`ScreensSheet.kt`) and Upcoming
(`UpcomingScreen.kt:517`). The players' own spinners are out of scope (see Scope).

### FR-5 — An empty answer says so, in words that fit

One composable, `EmptyState(title, body?, action?)`, draws every empty answer: a title line, an optional line under it
and an optional button, centred in the content region. It is Downloads' empty look (`DownloadsScreen.kt:296`) without
the glyph. Where it shows:

| Case | Title | Body | Action |
|---|---|---|---|
| Seeded browse, no filter set, nothing returned (incl. Continue Watching's See all) | `browse.empty_none` | — | — |
| Seeded browse, filters set, nothing matches | `browse.empty` (unchanged) | — | Reset filters (`store.resetFilters()`), `browse.reset_filters` |
| Home, a feed with no hero, no channel and no row | `home.empty` | `home.empty_body` | — |
| My List, empty (`BrowseScreen.kt:349-364`) | `browse.mylist_empty_title` | `browse.mylist_empty_hint` (unchanged) | — |
| A music genre, artist, author or shared playlist that answers empty | `music.empty_list` | — | — |

- "Filters set" is `SeededBrowseStore.hasActiveFilters`; nothing new is derived. The empty line shows only when the
  server answered with nothing. A failed load is FR-3 of DEB-4091 and DEB-5835's, never an empty line.
- On a TV, the Reset filters button takes focus when the grid is empty, where `onEmpty` sends it today (the facet bar);
  Back still leaves the page.

### FR-6 — Artwork that fails still says what it is

- `RemoteImage` takes an optional `onFailed` slot. While a load has failed and waits for DEB-4390's retry, it draws that
  slot over the placeholder colour. A retry that succeeds crossfades the image over it. Without the slot it draws
  today's placeholder.
- **Poster, landscape and square tiles** pass the same title fallback they draw for a tile with no artwork
  (`Tile.kt:266-287`, the title's hue gradient). That fallback shows the title in up to three lines, ellipsised, instead
  of two initials, for both cases.
- **Channel cards** pass the name text they draw for a channel with no logo (`ChannelCard.kt:281-290`).
- **A film or series hero backdrop** that fails keeps the hero's flat surface: the title is already drawn over it.
- No broken-image icon and no error text (DEB-4390 FR-1, amended). The fallback composes once, on the failure; it adds
  nothing per frame, and nothing on focus.

### FR-7 — New viewer text

New keys in `i18n/en.json`, each with a `note` naming this spec. Danish and Faroese are added in `da.json` and
`fo.json` in the same slice, as drafts with a "draft, review before release" note, the repo's pattern (see
`up.episodes_count`). No string names Jellyfin or a status code (Ravilo constitution invariant 12).

| Key | English |
|---|---|
| `browse.empty_none` | Nothing here yet. |
| `browse.reset_filters` | Reset filters |
| `home.empty` | Nothing to watch here yet |
| `home.empty_body` | When titles are added for this profile, they show up here. |
| `browse.mylist_empty_title` | Your list is empty |
| `music.empty_list` | Nothing here yet. |

**Wire:** none. No route, field or schema changes, so an old installed app is unaffected and a new app talks to any
server it talks to today.

## Design

No design round. Every surface reuses an existing component: the shimmer brush, `Tile(placeholder = true)`, the loaded
pages' own layout, Downloads' empty block without its glyph, and the tile's existing title fallback. The one visible
change of look is the fallback's title in place of two initials; it uses the tile's own caption type and the existing
gradient, and is shown to Rannvá on the slice's task as before-and-after screenshots, not drawn as a round.

## What this does not do yet

- The film player's start screen and buffering spinners, and the live player's tuning spinner.
- Samsung's opacity pulse and the Chromecast receiver's loading screen (the Samsung alignment question on DEB-5687).
- "Loading..." used as the word for buffering on the remote lines: DEB-5836.
- A failed search, a failed read drawn as nothing, and TV approve's busy state: DEB-5835.

## Acceptance

1. **No bare wait.** With the backend slowed (a QA `tc` delay), on a phone, Android TV, Ravilo Web and the Mac, each
   surface in FR-2's table shows its skeleton, then content. None shows "Loading..." or only background.
2. **No jump.** A screen recording of a cold start to Home and of opening a film page, on a TV and a phone: the first
   row and the hero do not move or change size when content replaces the skeleton.
3. **Cost.** The desktop test of FR-1 counts zero recompositions of a skeleton across 60 animation frames. On the weakest
   Android TV on the board, a cold start's Home frame timing is no worse than before (profile installed first).
4. **Empty.** Continue Watching's See all with nothing in progress reads `browse.empty_none`; a filter that matches
   nothing reads `browse.empty` with Reset filters, and Reset brings the titles back.
5. **Artwork.** With the image route blocked for one title, its tile shows its title on the gradient, then the poster
   once the route is unblocked, with no relaunch.
6. Danish and Faroese show the new keys in their own language.

## Build order

One engineer, in this order. Each slice cites `(DEB-5837, FR-k)`.

1. **Draw-phase shimmer and `Modifier.skeleton`** (FR-1). `components/Shimmer.kt`, `music/MusicLoading.kt`. Proof: new
   `ShimmerDrawPhaseTest` plus the existing `ShimmerReducedMotionTest`:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*Shimmer*' --console=plain`. **Junior-ready.**
2. **Home skeleton in the page's shape** (FR-3, Home). `components/Shimmer.kt`, `screens/HomeScreen.kt` (hero height
   extracted to a shared function). Proof: `HomeSkeletonShapeTest` measuring skeleton against loaded first row on TV,
   phone and desktop sizes, `--tests '*SkeletonShape*'`. **Junior-ready.**
3. **Detail skeleton in the page's shape** (FR-3, detail). `components/Shimmer.kt`, `screens/MovieDetailScreen.kt`,
   `screens/SeriesDetailScreen.kt`. Proof: `DetailSkeletonShapeTest`, `--tests '*SkeletonShape*'`. **Junior-ready.**
4. **Skeletons on the page surfaces** (FR-2, first half): collection page, Discover walls, request detail, Upcoming
   detail, live guide. `screens/ChannelScreen.kt`, `DiscoverScreen.kt`, `DiscoverDetailScreen.kt`,
   `UpcomingDetailScreen.kt`, `LiveTvGuideScreen.kt`. Proof: `NoBareLoadingTest`, which composes each `Loading` state
   and asserts the skeleton tag and no `loading` text, `--tests '*NoBareLoading*'`. **Junior-ready.**
5. **Skeletons on settings, sign-in and searches** (FR-2, second half): `SettingsScreen.kt` (both layouts),
   `TvSignInScreen.kt`, `SearchScreen.kt`, `SeerrSearchScreen.kt`, `music/MusicBrowseScreen.kt` (results kept while the
   next query loads). Proof: `NoBareLoadingTest` cases for each. **Junior-ready.**
6. **`WaitIndicator`** (FR-4). New `components/WaitIndicator.kt`; the call sites FR-4 lists. Proof: compile on all three
   targets (`:ravilo-ui:compileKotlinDesktop`, `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid`)
   and a grep guard in the test that no `CircularProgressIndicator` remains outside the players. **Junior-ready.**
7. **`EmptyState` and the strings** (FR-5, FR-7). New `components/EmptyState.kt`; `SeededBrowseScreen.kt`,
   `HomeScreen.kt`, `BrowseScreen.kt`, `music/MusicBrowseScreen.kt`, `music/MusicDetailScreens.kt`; `i18n/en.json`,
   `da.json`, `fo.json`. Proof: `EmptyStateCasesTest` (no filter, filter set with Reset, empty feed, empty My List),
   `--tests '*EmptyStateCases*'`, plus `scripts/check-ravilo-strings.sh` and `scripts/check-i18n-spelling.sh`. Two FRs, so not junior-ready.
8. **Artwork fallback** (FR-6). `seams/ImageLoader.kt`, `components/Tile.kt`, `components/ChannelCard.kt`. Proof:
   extend `RemoteImageRetryTest` with a failing fake loader: the slot shows on failure and goes on success,
   `--tests '*RemoteImageRetry*'`. **Junior-ready.**

Slices 1 to 3 go first: they change shared code the others draw with. Acceptance 2, 3 and 5 are QA rows on devices
from the board, after the release that carries them.
