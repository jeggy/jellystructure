# Phase R337 — The desktop's own layout: a sidebar, a rail, and the phone

> Owner, 2026-09-30: *"We are making our app work on desktop as well … nice mac and nice gnome support … film/series and
> as well Music player. Just like the mobile there is a switch."* — then, picking D1: *"when on a smaller window size,
> we want to be able to share codebase (including kotlin compose). So we would like this view to look the same as the
> mobile view currently looks."* And: *"So in the end we basically have these views: TV (Web is just the same as TV
> (for now)) · Desktop (Macosx and Gnome, maybe more to come in the future) · Mobile (Desktop uses this as well in
> small windows)."*

## Status

`⚠ Partial` 2026-09-30 (§Build notes: built and compiled; not seen on screen — the verification run hit a host incident) — written 2026-09-30 (design-authored) from `design/ravilo/Desktop - D1.html` (round 2, all eleven questions
answered) and `design/ravilo/Desktop - Directions.html` (round 1). **Dev-reviewed 2026-09-30** (§Dev review; the owner took the leans on items 4 and 7 the same day — Q12, Q13). Number verified free: `main`
tops at R335, and R335 names **R336** for MPRIS and the Inhibit portal, so this starts at R337.

**Supersedes R328 D3** (*the TV layout, driven by the keyboard and the mouse*) and the layout half of **FR-R328-5**
(its minimum window size and its menu bar). R328's target, seams, Keychain, update line and closing rule (D7) stand;
R329's engine and its film-player chrome (D4, *the TV's chrome with a pointer*) stand; R330/R331/R333–R335 are
untouched. **Builds on** R256 (the TV layout vs. the phone layout), R267/R304 (the phone's bar and Profile), R321/R326
(the listening mode), R322 (the player and the Playing tab), R324/R330 (*Play on…*).

## The three view families

| Family | Who | Input |
|---|---|---|
| **TV** | Samsung, Android TV, the Chromecast receiver — and **the web app, for now** | a remote; focus-driven |
| **Desktop** | macOS and Linux (GNOME) — more platforms later | a pointer and a keyboard |
| **Mobile** | Android phones, the iPhone web app — and **every desktop window narrower than 600 dp** | touch |

A desktop window is not a fourth layout: it is the desktop family at 600 dp and wider, and the mobile family below.

## Decisions (owner, 2026-09-30)

| # | Question | Decision |
|---|---|---|
| Q1 | Where films ⇄ music lives | **D1: the switch heads the sidebar.** Below 600 dp it is the phone's own switch (Profile's mode card) |
| Q2 | Music while browsing films | **The player bar stays in films mode only while music is playing** (+ Q10) |
| Q3 | Colours | **R338** (five themes, a light + dark pair that follows the system) |
| Q4 / Q9 | Type | **The system font in the chrome** (sidebar, rail, header bars, menus, dialogs) in every theme; Ravilo's Sora + Space Grotesk in titles, tiles and the player. The phone layout keeps its own type |
| Q5 | The queue | **A side panel that pushes the content** at ≥ 1200 dp; **over the content** from 600 to 1199; **the phone's Queue page** below 600 |
| Q6 | The player bar | **The platform's shape:** a floating glass capsule on macOS, a docked bar on GNOME |
| Q7 | Closing the window while music plays | **Keeps playing** (R328 D7, unchanged) |
| Q8 | 600–839 dp | **The sidebar folds into a rail** (icon + label), not a sidebar behind a button |
| Q10 | Music paused while in films mode | **The bar stays until the viewer leaves the page**, then goes |
| Q11 | The phone gets R338 too | **Yes** (R338) |
| Q12 | Profiles on a computer | **One viewer, as on the phone** (owner, 2026-09-30 — dev review 4): Profile is R304's page, there is no *Switch profile*, and Sign out is R304's one-profile sheet |
| Q13 | The music sidebar's Library | **The same list as the phone's Browse chips** (owner, 2026-09-30 — dev review 7): Artists · Albums · Songs · Genres · Playlists · Audiobooks |

## Requirements

**FR-R337-1 — One layout seam, three answers.** `ravilo-ui` gains `LayoutFamily { TV, DESKTOP, PHONE }`, read where
`isTvPlatform`/`isHandset` are read today. TV and web answer `TV`; Android phones and the iPhone web app answer
`PHONE` (R256/R267 unchanged); **the desktop target answers by the window's width class**, re-evaluated on every
resize: **Compact < 600 dp → `PHONE`**, **≥ 600 dp → `DESKTOP`**. The breakpoints are Compose's `WindowSizeClass`
(`material3-adaptive`), so there is one number for each edge, not ours. Crossing an edge keeps the page, the mode,
the scroll position where it maps, and anything playing.

**FR-R337-2 — Compact is the phone, unchanged.** Under 600 dp a desktop window renders the phone's screens as they
are — the bottom bar (R267), the listening mode's bar (R326), Profile (R304) with its mode card, the mini bar, the
Playing tab, the Queue page, sheets as sheets. The desktop adds only a **32 dp strip** at the top for the window's
controls (macOS: the traffic lights; GNOME: the close button on the right) that is also the drag area; the phone's
status-bar and safe-area insets are zero, and the bottom safe area is 6 dp. Touch-only affordances (swipe-down to
hide, long-press) also answer the pointer (drag, right-click). **No screen is re-drawn for this size**: a phone
change is a desktop-compact change by construction.

**FR-R337-3 — Expanded and Large (≥ 840 dp): the sidebar.** A sidebar of fixed width (macOS 232 dp, GNOME 248 dp),
top to bottom:

1. the window controls (macOS: the traffic lights inside the sidebar; GNOME: a header bar titled **Ravilo** with the
   primary menu ☰ on its right);
2. **the switch** — a two-segment control **Films & series | Music** (`mode.video` / `mode.music`), absent — never
   greyed — without a music library (R321's rule);
3. **the search field** for the mode (`desk.search_video` / `desk.search_music`), ⌘F / Ctrl+F focuses it;
4. **the mode's pages:** films — *Home · Discover · My List*, then a **Library** heading with *Films · Series* and
   their counts; music — *Listen · Playing · Queue*, then **Library** with **Artists · Albums · Songs · Genres · Playlists ·
   Audiobooks** — the phone's Browse chips, one list (Q13; R339's order; Audiobooks absent without the books library). *Playing* shows a small live equaliser while
   something plays;
5. at the foot, **the viewer** (photo, name, *Settings · Sign out*), opening R304's Profile content as a page. There
   is no *Switch profile*: a computer holds one viewer, as the phone does (Q12).

The content pane is R256's page for the mode, with desktop density (R328 OQ 2's lean stands: R174's grid config, no
new setting). The mode switch changes the sidebar and the content together; the page shown is the mode's first
unless the viewer was last on another page in that mode this session.

**FR-R337-4 — Medium (600–839 dp): the rail.** The same sidebar folded to a rail (macOS 76 dp inside the same inset
glass panel with the traffic lights; GNOME 84 dp with ☰ above it): the switch as **two stacked icon segments** (film,
note) with the full names as tooltips; *Search* as the first item (a click opens the page with the field focused);
every page as icon + short label (*Audiobooks* reads *Books*), in the sidebar's order, the groups separated by a rule;
the viewer's photo at the foot. A dot on *Playing* replaces the equaliser. Nothing is hidden behind a button — every
page and the switch stay one click away (Q8).

**FR-R337-5 — The platform's shapes; Ravilo's content.**

- **macOS:** the window has a transparent, full-size-content title bar (`apple.awt.transparentTitleBar`,
  `apple.awt.fullWindowContent`) so the traffic lights sit in the sidebar; the sidebar is an **inset glass panel**
  (8 dp from the window edge, 12 dp corners, blurred content behind it); the toolbar above the content is
  transparent, with **glass buttons** for back/forward and *Play on…*, laid over a hero where the page has one; the
  selected sidebar row is filled with the theme's accent.
- **GNOME (every Linux desktop in round 1):** libadwaita's geometry drawn in Compose — an undecorated window with our
  own **header bars** (46 dp, title centred, the close button alone on the right, per GNOME's default layout), a
  split view whose sidebar is a notch lighter than the content, a **toggle group** for the switch, neutral grey
  selection (the accent stays on the content), 6 dp button corners, 12 dp window corners. The header bar is the drag
  area; double-click maximises; the window's edges resize it.
- **Everywhere:** tiles, rows, detail pages, the player and every Ravilo string are the shared composables. Chrome
  sizes (sidebar row 28 dp macOS / 36 dp GNOME) are the platform's; content sizes are Ravilo's desktop density.
- **Fonts (Q9):** the chrome uses the platform's UI font — macOS's system font, GNOME's *Adwaita Sans* (Cantarell
  before GNOME 48) resolved through fontconfig; content uses Sora + Space Grotesk as today.

**FR-R337-6 — The player bar.** In **music mode** the bar is always there (nothing playing ⇒ the last-played song,
paused, where it was left — R322). In **films mode** it is there **only while music plays**; a pause from the bar keeps
it until the viewer leaves the page (Q10); starting a film **pauses** the music (FR-R322-12's rule, unchanged).

- **macOS:** a floating glass capsule at the foot of the content (16 dp from its edges, 62 dp tall, fully rounded):
  artwork · title/artist · previous/play/next · seek with times · lyrics · queue · *Play on…* · volume.
- **GNOME:** a bar docked across the whole window (72 dp, a thin progress line along its top edge), the same
  controls plus shuffle.
- **Medium** drops shuffle, the lyrics button and the volume slider (the volume keys still work). **Compact** is the
  phone's mini bar (FR-R337-2). The bar hides on the Playing page, which repeats it.

**FR-R337-7 — Playing.** In the desktop family the Playing page is the cover (380 dp at Large, 280 dp at Expanded)
beside synced lyrics, the transport under the cover, and the device chip when it plays on a speaker or TV (R324/R330).
Medium stacks the cover above the lyrics.

**FR-R337-8 — The queue by width (Q5).** ≥ 1200 dp: a 300 dp side panel that **pushes** the content. 600–1199 dp: the
same panel **over** the content from the right (macOS: a glass sheet; GNOME: the split view's end pane in overlay
mode); Esc, a click outside it or the queue button again closes it; the capsule shortens to stay clear of it. < 600 dp:
the phone's Queue page. Drag to reorder and remove work with the pointer in all three.

**FR-R337-9 — A film in the window.** Opening a film hides the sidebar, the rail and the bar; R329's player fills the
window with its chrome. The window's own controls float over the picture and hide with the chrome (macOS: the traffic
lights top-left; GNOME: the close button top-right, the back button top-left). **F** or a double-click toggles full
screen (the platform's own — R329 FR-R329-7), **Esc** leaves full screen, then the player.

**FR-R337-10 — Menus and keys.** R328's menu bar gains, under **View**: *Films & Series* ⌘1 · *Music* ⌘2 · *Hide
Sidebar* ⌃⌘S · *Show Queue* ⌥⌘U · *Show Lyrics* ⌥⌘L · *Enter Full Screen* ⌃⌘F. On GNOME the primary menu ☰ holds the
viewer's name, *Films & series* Ctrl+1 · *Music* Ctrl+2, *Settings* Ctrl+, · *Keyboard Shortcuts* Ctrl+? · *About
Ravilo* · *Sign out*, and Ctrl+? opens a shortcuts window. Keys, both platforms (⌘ on macOS, Ctrl on GNOME):
mode 1/2 · search F · play/pause Space and the media key · next/previous song ⌘→/⌘← · seek ±10 s ←/→ in a film ·
volume ⌘↑/⌘↓ · queue ⌥⌘U / Ctrl+U · lyrics ⌥⌘L / Ctrl+L · settings ⌘, · Esc = Back (R328's rule). Arrow keys still
move focus within grids and rows; the sidebar is one Tab stop away. **The mode keys work in the compact (phone)
layout too.**

**FR-R337-11 — Minimum window size** becomes **360 × 600 dp** (was 960 × 600, FR-R328-5), so a window can reach the
phone layout; size, position and the sidebar's shown/hidden state are remembered.

**FR-R337-12 — Closing keeps music (Q7)** as R328 D7 says. On GNOME, the first close while music plays asks the
**Background portal** once (`desk.bg_keep`); allowed, Ravilo keeps playing under *Background Apps* in GNOME's Quick
Settings until quit there; refused, the window closes and the music stops. (If R336 lands first it may carry this with
MPRIS; either way it is one request.)

**FR-R337-13 — Strings** × en · da · fo in `i18n/*.json` (da/fo drafts; the shipped table wins):
`desk.show_queue` *Show queue* · `desk.close_queue` *Close queue* · `desk.show_lyrics` *Show lyrics* ·
`desk.hide_sidebar` *Hide sidebar* · `desk.show_sidebar` *Show sidebar* · `desk.fullscreen` *Enter full screen* ·
`desk.exit_fullscreen` *Exit full screen* · `desk.shortcuts` *Keyboard shortcuts* · `desk.search_music` *Search music*
· `desk.search_video` *Search films & series* · `desk.bg_keep` *Keep playing in the background?* The mode labels reuse
the phone's `mode.*`.

## Out of scope

KDE / other Linux desktops' own shapes (they get GNOME's) · Windows · the web app leaving the TV family · a desktop
music layout in the web app · AirPlay from the Mac (R329 D5) · a mini-player window · menu-bar extras / tray icons.

## Acceptance

1. At 1280 × 800 on the Mac: the glass sidebar with the traffic lights in it, the switch first; ⌘2 shows the music
   sidebar and Listen; the capsule floats at the foot; ⌥⌘U opens the queue as a pushing panel.
2. Narrowed to 1000 dp: the queue opens over the album and Esc closes it. To 720 dp: the rail, with the two-icon
   switch and every page; the capsule without volume and lyrics.
3. Narrowed to 500 dp: the phone's screens exactly (bottom bar, Profile's mode card, mini bar) under a 32 dp strip;
   widening back returns to the rail on the same page, the same song still playing.
4. On GNOME (the Flatpak): the header bars, close on the right, the toggle group, neutral selection, the docked bar;
   Ctrl+? opens the shortcuts window.
5. In films mode with music playing, the bar shows; pause it, open a title → the bar is gone; with nothing playing it
   never shows.
6. A film fills the window; F goes full screen; Esc twice returns to the page with the sidebar.
7. Closing the window while music plays keeps it playing (Dock on macOS; Background Apps on GNOME after the one ask).

## Open questions

1. **GNOME's undecorated window on X11** (R333 D5): resizing edges and the window menu are ours to draw. If that proves
   brittle, the fallback is the system's title bar above our header bar — uglier, never broken. Dev to judge.
2. **Glass on macOS** from Compose: a real `NSVisualEffectView` behind a transparent Compose layer, or a drawn
   approximation (a blurred copy of the content behind the sidebar)? The design only needs it to read as the Mac's.

## Dev notes

- The compact embed in the mockup (`design/ravilo/Ravilo Mobile.html?frame=window`) is the same page as the phone, so
  the design cannot drift from it either.
- `NavigationSuiteScaffold` (material3-adaptive-navigation-suite) already switches NavigationBar → NavigationRail →
  drawer by width; the desktop's sidebar replaces its drawer, the rail is its rail, and Compact hands over to the
  phone's own bottom bar.
- Mockups: `design/ravilo/Ravilo Desktop.html` (**the maintained desktop mockup from 2026-10-01**: platform · window size · theme in a design-only fence; `desktop/ravilo-desktop.js/.css`), `design/ravilo/Desktop - D1.html` (C·a–C·d compact, M·a–M·c rail, E·a/E·b/L·a, S1–S6 shared, the ruler),
  `design/ravilo/Desktop - Directions.html` (round 1; D1·a–D1·d), builders in `desktop-kit.js`.

## Dev review (2026-09-30, against `main` `c4258560`)

Read against `ravilo-ui` (commonMain + desktopMain), `ravilo-desktop`, the Flatpak manifest and the design files. The
direction holds; seventeen items say where the code disagrees with the prose or where the prose leaves a gap. Two need
the owner (items 4 and 7, leans given); everything else is the build's.

1. **`LayoutFamily` is a layout seam, not an input seam.** `isTvPlatform` keeps everything it gates today — R256's
   key-only paths, R314's focus detail, R234's Settings chain. The web app is the TV *family* for layout, but
   `:ravilo-web`'s `isTvPlatform` is `false` and must stay so (it has no D-pad). So FR-R337-1's "read where
   `isTvPlatform`/`isHandset` are read today" narrows to the sites that choose a *layout*. Add
   `expect val isDesktopPlatform: Boolean` (desktop `true`, the rest `false`) and compute the family once in
   `RaviloApp`, beside `compact`/`handset` (`RaviloApp.kt:847–856`), provided as `LocalLayoutFamily`:
   `PHONE` = `handset`; `DESKTOP` = `isDesktopPlatform && !handset`; `TV` otherwise. `isHandset` needs no change on
   the desktop: with the minimum height at 600 dp (FR-R337-11), "the shorter side is under 600" and "the width is under
   600" are the same test.
2. **No `material3-adaptive`.** It is not on the classpath. `NavigationSuiteScaffold` cannot be used anyway: the phone's
   bar is R267's own `RaviloBottomNav` (the sliding pill, R274's geometry) and the sidebar and rail are drawn to the
   platform's shape. The three edges (600 / 840 / 1200) are constants in one place (`Dimens.kt`), measured from
   `LocalWindowInfo` as `compact` already is. A new dependency would also grow the Flatpak's offline sources
   (R333/R334) for three numbers. The dev note about `NavigationSuiteScaffold` is withdrawn.
3. **Music on the desktop has two locks to open.**
   (a) `RaviloApp.kt:879–890`: `musicAvailable`, `booksAvailable` and `inMusic` all require `handset`. They become
   `family != TV`.
   (b) **An existing R335 bug:** `MusicEngineDesktop.kt:43` sets `supported = MacNative.lib != null`, which is `false` on
   Linux, so R335's mpv music engine (FR-R335-8) cannot be reached even once (a) is open. It becomes
   `if (DesktopPaths.isMac) MacNative.lib != null else Mpv.lib != null`. This goes in R337's first commit.
4. **Profile on the desktop works as on the phone (R304) — decided (owner, 2026-09-30): yes, Q12.** `openProfile()`
   (`RaviloApp.kt:859`) opens the Profile page for `PHONE` and for `DESKTOP`. `ProfileMenu`, the TV dropdown, stays
   in the TV family. The spec does not say what follows from this: **the desktop has no *Switch profile***. The
   sidebar's foot and GNOME's ☰ hold only *Settings · Sign out*, and Sign out is R304's one-profile sheet. The reason:
   a window resized across 600 dp must not gain or lose the ability to hold several profiles. Today's desktop (R328's
   TV layout) shows the TV menu with Switch and Add user. A Mac with several profiles signed in before the update
   keeps them stored. Only the active profile is used, and Sign out goes to R191's picker when another profile is
   still stored (the existing path).
5. **The content pane uses the TV family's pages without the TV's top bar.** Each screen draws its own `AppBar` (nine
   call sites). On `DESKTOP`, `AppBar` draws the platform's toolbar or header bar instead (back/forward, the title on
   GNOME, *Play on…*), so no screen has to change. The films pages are R256's TV pages, used with a pointer. **The music
   pages exist only in the phone's shape** (`MusicListenScreen`, `MusicBrowseScreen`, `MusicDetailScreens`,
   `AudiobookScreens`). At 600 dp and up they need wide versions: rows on Listen, a grid for Albums and Artists, and the
   album page's header beside its track list. Take them from `Desktop - D1.html` (E·a, E·b, L·a, M·b, M·c) and
   `desktop-kit.js` (`listen()`, `albumPage()`, `albumsGrid()`). This is the largest part of the music half, and the
   spec does not list it. Build them as width branches inside the same composables, not as new screens, so the
   compact layout stays exactly the phone's. On the desktop, `MusicBrowse(chip)` *is* the sidebar's Library row: the
   page draws no chip strip and no query field, and the sidebar's field sets the same query.
6. **One navigation stack on both sides of 600 dp.** The stack is shared, and crossing 600 dp never rewrites it. The
   sidebar works out its lit row from the stack, the way `bottomItemOf()` does (`sidebarItemOf()`). Three mappings
   need a rule:
   - The phone's Library is `Browse(ALL)`. At 600 dp and up it lights neither Films nor Series, and the page keeps its
     type control.
   - The desktop's Films and Series are `Browse(MOVIES)` and `Browse(SERIES)`. In compact they light Library.
   - `Dest.Search` is the phone's Search page in compact. At 600 dp and up it is the same page, with the sidebar's
     field focused.
7. **The music sidebar should list what the chips list — decided (owner, 2026-09-30): yes, Q13.** FR-R337-3 and the mockup
   show *Artists · Albums · Songs · Audiobooks*. The phone's chips (R339) are *Artists · Albums · Songs · Genres ·
   Playlists · Audiobooks*. Without Genres, the genre drill-in cannot be reached at 600 dp and up. Build the sidebar's
   Library from `MUSIC_CHIPS` (plus Audiobooks) so the two lists cannot drift apart. FR-R339-1 already says "the
   desktop sidebar's Library uses the same order".
8. **Counts.** R310's `FacetsSummary` (`/api/tv/facets/summary`) has no film or series counts. Add two nullable fields,
   `movies` and `series`; when they are absent (an old server), the sidebar shows no number, never *0*. The music
   counts come from each list's existing `total` (`Music.kt:81`).
9. **The film player uses R329's chrome at every width (for the player, FR-R337-9 wins over FR-R337-2).**
   `PlayerScreen.kt:404/3709` and `LiveTvPlayerScreen.kt:107/407` choose the touch chrome from `LocalHandset`. On the
   desktop they must test `handset && !isDesktopPlatform`, or a 500 dp window gets double-tap seek and a brightness
   swipe that no brightness control stands behind. The same goes for `LocalFocusVisible provides !handset`
   (`RaviloApp.kt:975`): on the desktop, focus shows once the keyboard moves it, at any width.
10. **The macOS window.** In `Main.kt`, set three standard JDK properties on the root pane:
    `apple.awt.transparentTitleBar`, `apple.awt.fullWindowContent` and `apple.awt.windowTitleVisible = false`.
    **Open question 2 (glass), lean: draw an approximation.** Compose can make a window transparent only when the
    window is undecorated, and an undecorated window loses the traffic lights. So a real `NSVisualEffectView` behind
    Compose is out of reach without native changes. Draw the sidebar and the capsule as flat panels one step lighter
    than the page, with a 1 dp inner highlight and no blur. Revisit only if it does not read as the Mac's.
11. **The GNOME window (open question 1).** The Flatpak runs on X11 through XWayland (`--socket=x11`, R333 D5), with
    stock OpenJDK 21, so JBR's custom-title-bar API is not there. An undecorated window is still feasible:
    - `WindowDraggableArea` over the header bar;
    - a double-click toggles maximised;
    - 6 dp invisible edge handles set the window's bounds;
    - mutter still tiles and snaps it (Super+arrows).

    What it cannot cheaply have is GNOME's shadow and 12 dp corners: a transparent ARGB window under R335's frame ring
    is untested and may cost frames. **Lean:** square corners and a 1 dp border in round 1 (that is how GNOME draws a
    tiled window anyway), with `-Dravilo.csd=false` falling back to mutter's own title bar.
    Separately, `RaviloMenuBar` draws a Swing menu bar *inside* the window on Linux today (Ravilo · View · Window).
    Under R337 the menu bar is macOS only. Its shortcuts (Ctrl+Q, Ctrl+,, F11) move into one key table in
    `onPreviewKeyEvent` (`Main.kt`'s `playerWindowKeys`), which the Mac's menu items call too. Ctrl+Q stays, because
    GNOME's primary menu holds no Quit.
12. **On Linux, "closing keeps the music" needs a single instance.** The window is hidden and music plays, so the next
    launch from the app grid starts a second JVM: two engines and two sessions. Fix: own the app id `net.jebster.Ravilo`
    on the session bus (a Flatpak app may own its own id without a finish-arg). A second launch then calls `Activate`
    on it and exits.
    This needs a D-Bus client. **One** client covers five needs: this single instance, the Background portal
    (FR-R337-12), R338's appearance, the interface font (item 13) and R336's MPRIS/Inhibit. Use `dbus-java` with the
    JDK's own Unix-socket transport, in desktopMain, loaded on Linux only. It adds one dependency, so R334's sources
    file regenerates on the next release (it already does on every release).
13. **Fonts (Q9).** The runtime is `org.freedesktop.Platform` 26.08, not GNOME's (R333), and it ships neither Adwaita
    Sans nor Cantarell. So "resolved through fontconfig" alone gives DejaVu on some hosts.
    - **GNOME:** read `org.gnome.desktop.interface` `font-name` through the Settings portal (the same client), and
      resolve it with fontconfig, which sees the host's fonts at `/run/host/fonts`. The fallback is `sans-serif`.
    - **macOS:** `FontFamily(SystemFont(".AppleSystemUIFont"))` through Skia. This needs verifying on the Mac; the
      fallback is Helvetica Neue.
14. **Strings: the compact layout says *phone*.** Five shipped strings the desktop reaches in compact are false on a
    computer:
    - `mode.label` *This phone* (`MusicCommon.kt:398`);
    - `profile.signout_title` *Sign out of this phone?*;
    - `this_phone` (`PlaybackNoteLine.kt:52`);
    - `cast.play_here`;
    - `cast.failed_sub`.

    Add `_desk` variants (*This computer* · *Sign out of this computer?* · *this computer* · *Play here* · *…play it
    here*), chosen by `isDesktopPlatform`. The Mac keeps R330's `cast.play_here_mac`. The spec's `desk.*` keys match the
    table's dotted naming.
15. **Keys.** ⌃⌘F already exists (the View menu), and F and Esc exist (`playerWindowKeys`). New: ⌘1/⌘2, ⌘F, ⌃⌘S,
    ⌥⌘U, ⌥⌘L, ⌘↑/⌘↓ and ⌘→/⌘←. They reach `RaviloApp` through `AppCommands`, the channel `OPEN_SETTINGS` already
    uses. The mode keys work in compact, as the spec says.
16. **Wire and installed versions.** The phase is client-only apart from item 8, which is additive and nullable. It
    was checked against the contract `WireCompatTest` holds (every release from v1.23). An old server answers without
    counts, and nothing else changes. No new enum values.
17. **Build order.** R337 comes before R338's desktop half, because R338's macOS and GNOME settings live in R337's
    Settings. The slices, each shippable:
    (a) the family seam, music on the desktop and the R335 fix (items 1 and 3);
    (b) the sidebar and rail, with `AppBar` as the toolbar;
    (c) the wide music pages;
    (d) the bar, queue and Playing by width;
    (e) the GNOME window, the single instance and the Background portal;
    (f) keys, menus and strings.

## Build notes (2026-09-30)

Built from the dev review; compiled for desktop (`:ravilo-ui`, `:ravilo-desktop`), Android and web, all fences green.
**Not seen on screen, not deployed, not device-tested:** the one verification run (the desktop app under Xvfb against
the local stack) ended in a host incident, below, before a single correct frame was captured.

- **The seam (items 1, 3, 9).** `LayoutFamily` + `LocalLayoutFamily`, `LocalWindowWidth`, `WindowWidths`
  (600/840/1200), `isDesktopPlatform`, `isMacPlatform`. Music on the desktop at every width (`listeningLayout`), and
  the R335 bug fixed: `MusicEngineDesktop.supported` now asks for libmpv on Linux. Profile is a page on the desktop, and
  the players keep R329's chrome at every desktop width.
- **The frame.**
  - `components/DesktopNav.kt`: the sidebar (≥ 840) or the rail (600–839), in each platform's shape; the switch; the
    search field (it opens the mode's search page with its field focused); the pages; the counts (`kindCounts` from
    `getFacets`, and each music list's `total` — no server change was needed); the viewer with *Settings · Sign out*,
    and no Switch (Q12).
  - The music Library is `MUSIC_LIBRARY` = the phone's chips (Q13). The rail says *Audiobooks*, not the mockup's
    *Books* (the owner's naming rule).
  - The lit row is derived from the stack. `RaviloApp` puts a `Row` around the page.
  - `AppBar` draws the desktop toolbar: Back when the stack is deeper than one, the title centred on GNOME, *Play on…*,
    and room for the traffic lights when the sidebar is hidden.
- **Music.**
  - `music/DesktopMusicBar.kt`: the bar (capsule on the Mac, docked on GNOME; the medium width drops shuffle, lyrics
    and volume) with Q2/Q10's films-mode rule, and the queue panel (pushes at ≥ 1200, overlays from 600 to 1199).
  - The volume is a new `MusicEngine.setUserVolume`, multiplied into ReplayGain and the sleep fade; a no-op on
    Android and the web.
  - Browse drops its chips on the desktop. Grids take columns by width. The album header sits beside a 240 dp cover.
- **Menus and keys.**
  - Mac: *View* gains the modes, *Hide Sidebar*, *Show Queue*, *Show Lyrics* and *Keyboard Shortcuts*.
  - Linux: no Swing menu bar. GNOME's primary menu ☰ (`DesktopPrimaryMenu`), the shortcuts window
    (`DesktopShortcutsOverlay`), and every key in `desktopKeys`.
  - All of them reach the app through `AppCommand`.
- **The GNOME window (item 11).** Undecorated unless `-Dravilo.csd=false`. `DesktopTitleStrip` (drag, double-click to
  maximise, close) and `DesktopWindowFrame` (6 dp resize edges, 1 dp border). Square corners, as the lean said.
- **Linux platform (item 12).** `LinuxPortal`: GIO through JNA, one session-bus connection, no new artefact for the
  Flatpak's offline sources (a deviation from the `dbus-java` lean). It gives the Settings portal — light/dark live
  (R338's Linux source) and the interface font — and the Background portal (`DesktopBackground`: asked once, the answer
  remembered). `SingleInstance`: a Unix-domain socket in the runtime directory; a second launch shows the first and
  exits.
- **Fonts (Q9).** `SystemUiFont` = the Mac's `.AppleSystemUIFont` or GNOME's interface font (Sora elsewhere), used in
  the sidebar, rail, menus and toolbar.
- **Minimum window 360 × 600.** `WindowBounds` no longer forces a remembered width back up to 960.
- **Strings.** 18 `desk.*` and the computer-worded `mode.label_desk`, `profile.signout_title_desk`, `this_computer`,
  `cast.play_here_desk`, `cast.failed_sub_desk` × en/da/fo. `CastDesktop` said *Play on this Mac* on Linux too — fixed.

**Not done or not verified.**
- The macOS window properties, glass and system font are unverified (no Mac run).
- On Linux, the portal, single instance, CSD drag/resize and Background ask are unverified.
- The wide artist and book headers are the phone's.
- Ctrl/⌘ + arrows are taken even while typing in a field.

**Host incident, 2026-09-30 03:30.** The verification run's first launch landed on a pre-existing Xvfb `:99` (it hosts
the paused Tizen emulator; the test Xvfb could not claim `:99`), where Skiko tried a GL context through the NVIDIA
driver. Around the moment that process was killed, CPU 0 stopped ticking entirely, and since then RCU grace periods
never complete. Container `runc exec` health checks pile up in D state and containers read *unhealthy*, while
production kept serving. A second Xvfb with GLX then hung in the same NVIDIA GBM path. **Lesson:** never run Skiko,
Compose Desktop or Xvfb with GLX on this host; use `Xvfb -extension GLX` and a display number checked free first — or
the Fedora container.

## The Mac pass (2026-09-30, daytime) — the mockup is the target, number for number

The first build was put on the owner's MacBook and rejected on sight: *"It doesn't look in any way like a proper Mac
themed app, like from the designs files."* The dev review's reading — the TV's pages at "desktop density" inside a
drawn sidebar — was a simplification the owner had never been shown. This pass takes `design/ravilo/Desktop - D1.html`
(`desktop-kit.js`, `desktop-directions.css`) as the specification, with every frame rendered to a picture and the
running app's own frames laid beside it. **Seen on the Mac** (macOS 27, a 1× display) at 1200, 1040, 720 and 480 dp;
GNOME's forms were changed with the same numbers from `.gn`/`.gside`/`.gbar` and are **still unseen**.

- **The window.** The page's colour is drawn edge to edge (nothing was behind the sidebar before: the window's own
  grey showed through). The traffic lights are placed by AppKit from `Window.swift` — (29, 29) inside the sidebar's
  glass, (26, 29) in the rail, (18, 16) in the phone layout's strip, (24, 34) beside the film player's Back — and kept
  there across resizes. AWT's view tells AppKit a press on it may move the window, so every click in the title area
  was AppKit's and the toolbar's buttons were deaf; the view now says no (`mouseDownCanMoveWindow`), and empty chrome
  (`Modifier.windowDragArea()`: the sidebar's head, the toolbar, the phone strip) hands the press to
  `NSWindow.performDrag`, which moves, snaps and tiles as a title bar does; a double-click does what System Settings
  says a title bar's does.
- **The sidebar and the rail** to `.mside`, `.seg`, `.sfield`, `.nav`, `.who`, `.rail`: the panel a veil of ink on the
  page's colour, a neutral switch with two equal halves, 28 dp rows with the mockup's own icons (`DeskIcons.kt`, drawn
  from the kit's path data) in the theme's second accent, the selected row tinted with the accent (filled with it in
  Graphite and Daylight), *Library* in sentence case, the viewer with a 26 dp photo.
- **The sidebar's search field is a field.** It was a button that opened a page whose own field then vanished; typing
  did nothing. What is typed now searches the mode — music's results take Browse's place, films' the Search page's —
  ⌘F focuses it, Esc or ✕ empties it, and any page picked from the sidebar ends the search. The rail keeps the page's
  own field.
- **The toolbar** (`.mtb`): a glass pill with Back **and Forward** (a real forward history: the pages Back left, until
  the viewer goes somewhere new) and *Play on…* in a glass pill. Music's detail pages and Playing had a phone's
  floating Back; they have the toolbar now.
- **Density.** 28 dp gutters; tiles 138 / 236 / 150 dp with 13 sp captions; rows 22 dp apart; a tile does not grow
  under focus. The hero is the mockup's band (half the window, never under 380 dp) with **Play** and **More Info** —
  *More Info* where the mockup says *My List*, because the Home feed does not carry a title's My List state and a
  toggle that cannot show its state is not drawn. Browse's filter chips are `.chips b` (the whole bar fits 1200 dp).
  Detail pages' buttons are `.bt` / `.bt.pri`.
- **Music.** Pages say their name (`.h1`) with the sort beside it; grids take 150 dp columns; a song is a 38 dp striped
  row; the album page is `.alh` (210 dp cover, the kind and year, a 36 sp title); **Playing is `.np`** — the cover
  (380 / 280 dp) with the transport under it beside 30 sp synced lyrics, on the blurred cover; the queue panel is
  `.qp` (`.qr` rows, the playing one tinted) and, over the content, the Mac's floating sheet clear of the toolbar, with
  the capsule shortened beside it; the capsule is `.cap-bar` and, in music mode, is there from the start (the
  last-played song loaded paused on entering the mode — it used to appear only after a visit to Playing).
- **Sheets are dialogs.** Every `HandsetSheet` (Play on…, a song's ⋯, the sort, a failure) is a 440 dp card in the
  middle of a computer's window rather than a sheet rising across the sidebar. *Connecting to …* is a glass pill, and a
  server message is a notification's size.
- **The focus ring follows the keyboard**: shown after an arrow or Tab, gone when the pointer is used.
- **Settings** has the toolbar in place of its own Back, and R338's theme section as the mockup's swatches.

**Verified by use on the Mac:** the three things the owner named — *show lyrics* (Playing, synced, the line sung in
ink), *search for music* (the sidebar field), *cast music* (to the bedroom TV: joined, the song on its screen, *Stop
casting* ends it) — plus both themes live, the three width classes, a film in the window and back.

**Not done, and known:**
- A separate Settings window with General · Playback · Account tabs (the mockup's T·e); Settings is a page.
- The book player, the artist page's header and the Profile page are the phone's at the desktop's sizes.
- Rows of tiles have no pointer affordance for scrolling sideways (a mouse without a trackpad needs Shift + wheel).
- The capsule's volume is the app's own; while casting it should be the device's.
- No blur anywhere: the glass is drawn, as the dev review said.
- **The test driver** (`ravilo-desktop/…/TestDriver.kt`, off unless `RAVILO_TESTDRIVER` names a directory) is how
  this was seen and driven over SSH, where macOS gives a remote shell neither the screen nor the keyboard.


## The second pass (2026-09-30, afternoon) — the four things owed, and GNOME seen for the first time

**The four owed items from the Mac pass are built:**

- **Settings is a window** (`DesktopSettingsWindow`, a seam; `DesktopSettingsPanel`). Every way to Settings on a
  computer opens it (the sidebar, ⌘, / the menu, GNOME's ☰, the Profile page) and the app's own page stays where it
  is. 600 points wide, as tall as what it holds, not resizable; Esc closes an open list of choices first, then the
  window. *Change password* and *Sign out* close it and hand over to the app's window.
  - **The Mac** (T·e): three tabs in the window's bar — General (theme, language) · Playback (the playback and
    listening switches) · Account — with the traffic lights over the bar (`ravilo_window_lights_titled`: a window
    with a place of its own for them, by its title).
  - **GNOME** (T·h): *Preferences*, a header bar with the title and the close button, then boxed lists — a switch
    row is a switch, a choice is a combo row (the pick and ▾; the choices under it, the pick ticked), an action is a
    row with ›.
- **The pages that were the phone's:** the **artist** page (`.alh`'s shape with the round picture, the kind and the
  years, a 36 sp name, the biography in place, the backdrop faint behind), a **playlist**, the **audiobook** page
  (cover, *Audiobook · year*, title, author, narrator · length · chapters, where the viewer is, the one action and ⋯;
  chapters striped as an album's songs), the **author** page, the **book's Playing page** (`.np`: the cover, the
  chapter's seek line, the whole book's scrubber, speed · −30 · play · +30 · sleep; the chapters where a song has
  its lyrics, the one being read marked), and **Profile** (the photo, the name, *Settings…* and *Sign out*, then My
  List as a row of the desktop's tiles — the language and the password are in the Settings window).
- **A row of tiles has arrows** (`ArrowRow`): a glass arrow at each end the row can still scroll towards, while the
  pointer is over the row; a click moves it by most of what is on screen. They take a tap, never the focus — a
  focused child makes a row bring itself into view and scroll back to its focused tile.
- **The capsule's volume is the device's while casting** (`CastSender.volume`, the device's level as it reports
  it), and the app's own volume is remembered between launches.

**GNOME, seen (the Fedora container's GNOME session, signed in to the household's server by the owner):**

- **The header bar is a bar** (`.ghb`): always there, the page's colour, the page's name in the middle (the
  sidebar's own name for the page, or the album / artist / book a page gives it); the page starts under it. It used
  to float over a hero, where its buttons were dark ink on a dark picture.
- **The sidebar's header** has the mark beside *Ravilo*, and **moves the window** when dragged (owner: only the
  content's header did).
- **The window's buttons follow the desktop** (owner): `org.gnome.desktop.wm.preferences button-layout`, read
  through the Settings portal and followed live — close alone on the right by default; minimise and maximise when
  the desktop adds them; all on the left where it keeps them there (then they sit first in the sidebar's header, or
  the rail's, or the page's strip when neither shows). The Preferences window keeps its close button on the same
  side. Seen: `icon,menu:minimize,maximize,close`, then a change to `:maximize,close` redrew the buttons at once.
- **The window's corners are round** (owner): the window is see-through and the app is clipped to libadwaita's
  12 dp, with the hairline following the curve; square again when maximised or full screen. The X window has a
  32-bit visual under Mutter. **No shadow yet** — a window that draws its own needs a see-through margin and
  `_GTK_FRAME_EXTENTS`, so that the desktop measures the window without it; not done.
- **Rows had lost the gap above them** (a gap and a row in one `Box` overlap): headings sat on the row above. A
  computer's rows are a column now; the TV keeps the box it has been measured with.
- **The first screen** (server address) had no way to move or close the window; it has the strip and the edges now.
- **A book restored at launch read itself aloud**: mpv starts what it loads, AVPlayer waits. A paused load says
  pause (`MusicEngineDesktop.loadTicket`).
- The theme follows GNOME's Dark Style live (R338), seen both ways.

**Found and not fixed here:** episode badges on Home read `S1:E2` (the owner's rule is `S01E05` everywhere; three
spellings exist across Home, the detail page and Upcoming). The container's session had no audio output at all
(nothing played until a silent sink was made) — an environment matter, noted in its README.

**Seen on the Mac the same afternoon** (the owner handed the Mac over; the test build, silenced with the driver's
`quiet`): the Settings window — its traffic lights at (22, 22) over the tabs' bar by AppKit's own report, the three
tabs, the window as tall as each tab's content (373 / 428 / 219 points); the arrows on a row; the artist, audiobook,
book-player and Profile pages; and **the volume while casting** — with the bedroom TV playing, the capsule's slider
showed the TV's level (zero), ⌘↑ took the TV to 0.10 and ⌘↓ back, watched from a second Cast connection; *Stop
casting* returned the capsule to the Mac's own level.

## The third pass (2026-09-30, late afternoon) — music on a speaker as the computer shows it, and the hand

The owner's router rule made the guest-room speaker reach the server (286's notes), and the sound was right. What
the Mac showed while it played was not. Seen on the Mac with a second Cast connection reading the speaker, the
speaker's volume at 2 %:

- **The counter and the bar.** A speaker's player reports no length for a FLAC, so the bar read `0:00 / 0:00` and
  never moved. The length now comes from the device when it gives one, else from the queue's own entry for the
  song (`mergeCastStatus`, a test beside the others). Seen: `0:13 / 3:19` against the speaker's 14.8 s a moment
  later; paused, the counter holds (0:31 twice, four seconds apart); a click on the bar at the middle gave 1:43
  against the speaker's 104.8 s.
- **Lyrics in step.** The device says where it is about once a second; a line lit a second late reads as wrong.
  Between two reports the position runs on by itself while the device is playing — never more than two seconds
  ahead, never past the end (`MusicPlayback.currentPositionMs`).
- **A speaker that is chosen is where music goes.** After its queue had played out the speaker was still lit as
  chosen, and a song picked then played on the computer. `MusicCast.holdsDevice` — connected and not showing a
  film — now decides where a new queue goes. Seen: *Play* on an album with the speaker idle starts there.
- **The song that follows** (the receiver's own, at a song's end) shows at once: title, `0:29 / 4:11`.
- **Volume** is the speaker's while it plays (seen: a click at a tenth of the slider → 0.11 on the speaker, back
  to 0.03). **The queue panel** lists the speaker's queue. ***Play on…*** names what plays (*Speaker · Playing …*).
- ***Stop casting* keeps what was playing** (FR-R324-5 read as the listener means it): the speaker's queue comes
  back to the computer, paused where it stopped. It used to fall back to the song the hand-off had left behind —
  an album started while casting never reached the computer's own player. The sheet's row and the ⋯ menu's row are
  one call now (`MusicCast.stop`). Seen: stopped at 2:08 of a song, the capsule holds the song at 2:08, paused.
- *1 song*, not *1 songs*, on the queue's line.

**What failed, and was the receiver's** (289): with a song playing, *Next* and *Play* on another album both ended
in silence on the speaker. Fixed in the receiver, deployed the same evening, and seen: a song over a song, an album
over a song, *Next*, *Previous*, on the speaker and on the bedroom TV; and the music moved from the speaker to the
TV by choosing the TV in *Play on…* (R324's note). The Mac's log now carries the receiver's own notes
(`cast: {device} · …`). A song that really cannot be played still says nothing (289's open question 1).

**The hand under the mouse** (owner: *when hovering something that's clickable, the cursor changes to a pointer*).
One modifier, `handCursor()`, and the two places nearly every clickable thing goes through: `dpadFocusable` (a
tile, a button, a row — where a click does what Select does, so not the film player's whole surface, whose tap
only shows its controls) and the music pages' `tap`. Added by hand where a bare `clickable` is used: the sidebar
and the rail, Back / Forward, the cast button, the arrows on a row, the desktop's buttons, the Settings window's
tabs, choices and rows. A disabled Back or Forward and a dimmed *Next* keep the arrow (the dimmed *Next* takes no
click at all now). Sliders keep the arrow, as the Mac's and GNOME's own do; a text field keeps the I-beam. The web
app gets the same hand from the same code; a phone and a TV have no pointer.

No picture of the canvas shows the cursor, so the test driver has `cursor <x> <y>`: it moves there and answers
with the pointer the app asks for. On the Mac: *Hand* on an album tile, a sidebar row, the mode switch, a row of
*Recently played*, *See all*, a row's arrow, *Settings* and *Sign out*, the capsule's buttons, the hero and its
two buttons and dots, a genre chip, *Resume*, *+ My List*, *more*, Back when there is somewhere to go back to, and
in the Settings window a tab, the checkbox and its label, a theme, a language; *Default* on a heading, on empty
page, on a backdrop, on Forward with nothing ahead; *Text* in the search field.

**On GNOME** (the container, the same build): *Hand* on a tile, a sidebar row, the mode switch, the bar's play
button, the cast button, the sidebar's toggle, *See all*; *Default* on a heading, on the header bar (it drags the
window), on the window's own close and maximise buttons (as libadwaita's), on a dimmed *Next*; *Text* in search.

**A window opened with no display** (found there: GNOME's remote login with nobody connected has a 0 × 0 screen):
the saved place is on no display, so the window was centred — on nothing, at −640, −376, its header off the
display that appears when someone connects. With no display at all it opens at 48, 48 (`WindowBounds.load`).

## Triage (2026-10-09, against `main` `12bffb29`)

- **Code: nothing found missing.** **Owed:** macOS window properties, glass and the system font on the Mac; on
  Linux the portal, single instance, CSD drag/resize and the Background ask on a real GNOME desktop.

## Mac live check (2026-10-09, test build `v1.50-117-g3a66ba67` built on the owner's new MacBook (M5 Pro, macOS 27.0.1) in `~/ravilo-test`, **signed ad hoc** (the Ravilo signing key isn't on the new Mac); driven with the in-app test driver)

- The window draws as designed (sidebar with the Films & series / Music switch, Listen and Home pages, the bar);
  `chrome`: traffic lights at (24,34)/(44,34)/(64,34), full-size content view. Glass and the system font look right in
  the shots. The Play on… sheet lists Køkken hub, Stue TV and Soveværelse TV as music targets (R380) and a Chromecast
  as not reachable.
