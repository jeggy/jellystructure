# Phase R337 — The desktop's own layout: a sidebar, a rail, and the phone

> Owner, 2026-09-30: *"We are making our app work on desktop as well … nice mac and nice gnome support … film/series and
> as well Music player. Just like the mobile there is a switch."* — then, picking D1: *"when on a smaller window size,
> we want to be able to share codebase (including kotlin compose). So we would like this view to look the same as the
> mobile view currently looks."* And: *"So in the end we basically have these views: TV (Web is just the same as TV
> (for now)) · Desktop (Macosx and Gnome, maybe more to come in the future) · Mobile (Desktop uses this as well in
> small windows)."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Desktop - D1.html` (round 2, all eleven questions
answered) and `design/ravilo/Desktop - Directions.html` (round 1). **Not dev-reviewed.** Number verified free: `main`
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
   their counts; music — *Listen · Playing · Queue*, then **Library** with **Artists · Albums · Songs · Audiobooks**
   (R339's order; Audiobooks absent without the books library). *Playing* shows a small live equaliser while
   something plays;
5. at the foot, **the viewer** (photo, name, *Settings · Sign out*), opening R304's Profile content as a page.

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
- Mockups: `design/ravilo/Desktop - D1.html` (C·a–C·d compact, M·a–M·c rail, E·a/E·b/L·a, S1–S6 shared, the ruler),
  `design/ravilo/Desktop - Directions.html` (round 1; D1·a–D1·d), builders in `desktop-kit.js`.
