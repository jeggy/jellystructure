# Phase R321 — A listening mode on the phone: the switch, the bar, and the pages

> Owner, 2026-09-27: *"a music player mode in Ravilo on the phone."* The same evening, on the drawn bar:
> *"let's use the Listen · Browse · Search · Queue design, but with now playing in the middle"* → *"merge Browse
> and Search together, so Playing is in the middle"* → *"on the home we do not want to show now playing on the
> top, it is already shown on the bottom on top of the bottom navbar."*

## Status

`✓ Built` 2026-09-28, **not deployed, not tried on a phone** (build notes at the end). Written 2026-09-28 from `specs/ravilo/design-brief-music-player-on-the-phone-2026-09-27.md` (§A–§C,
§E placement, §H, §I, §J) and the mockup `design/ravilo/Ravilo Mobile.html` (+ `mobile/ravilo-music.js`,
`mobile/ravilo-music.css`), including the owner's three same-evening changes. **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Numbering
verified against `main` `f8bdaab4`: Ravilo taken through **R319**, and **R320** is ours (pending export). The
brief's *"R321's service"* is **R322** here. Builds on **R304** (Profile is a page), **R267/R274/R278** (the
bottom bar), **R277** (the keyboard on Search), **R187** (the browse grid), **R256** (`isHandset`) and **R319**.
It reads **279**.

## Decisions

| # | Question | Answer |
|---|---|---|
| J2 | The bar in music mode | **Owner: Listen · Browse · Playing · Queue · Profile.** Search is merged into Browse, so Playing is in the middle (FR-R321-4). Supersedes the brief's lean and §M7·2 |
| — | Listen's top | **Owner: no Now-playing card** — the mini bar already shows it |
| — | Music videos | Owner: video-mode content, reached from an artist's *Videos* row (FR-R321-11) |
| J1 | The switch's form | **Lean: a mode card at the top of Profile** (the alternatives, *a row in Account* and *a top-row segment*, are drawn). Not yet answered |
| J3 | ♪ beside the brand | **Lean: draw it.** Not yet answered |
| J7 | Playlists this round | **Lean: two states only** (empty, list; the create sheet drawn once, marked phase 2). Not yet answered |
| research 5 | Mode per device or per viewer | **Lean: per device** |

## Requirements

**FR-R321-1 — The mode.** A **per-device** flag, `video` or `music`, stored like `PlaybackPrefsStore`, not on
the server — switching on one phone never switches another. It is remembered across launches. **Sign-out
clears it**, so the next viewer on the phone starts in video mode. The server composes the content (279); the
mode is navigation state. The mode's internal name stays `music` once audiobooks join it (R323).

**FR-R321-2 — Absent, never greyed.** No switch, no bar, no music anywhere when the viewer's account is not
granted the music library (275 FR-275-4), when that library is empty, or on a TV (`isHandset` false). The
Profile page is then exactly R304's. A mode stored for a viewer who lost the grant falls back to video silently.

**FR-R321-3 — The switch (J1).** A **mode card** under the photo/name block on Profile, with two halves:

- *Films & series* with its bar's names underneath (*Home · Library · Search · Discover*);
- *Music & audiobooks* ♪ with its bar's names (*Listen · Browse · Playing · Queue*).

The chosen half takes the brand gradient (Noir: ink on ground). A tap switches, lands on that mode's first tab,
and asks nothing. The same card is in the same place in both modes, which is the way back. The alternatives are
kept drawn: a *Switch to music ›* row under App language, and a *Video / Music* segment in the top row.

**FR-R321-4 — The bar in music mode.** The same bar component (R274's geometry, the sliding pill, 11.5 sp
labels), with a second declared item list: **Listen · Browse · Playing · Queue · Profile**. Playing is third —
the middle.

- `Dest` gains `MusicListen`, `MusicBrowse(chip)`, `MusicPlaying`, `MusicQueue`, `AlbumDetail`,
  `ArtistDetail`, `PlaylistDetail`, `BookDetail`, `AuthorDetail`, and `bottomItemOf` maps them.
- **Tap-on-active:** Listen scrolls to top. **Browse focuses its search field and raises the keyboard** (R277's
  rule — on re-tap only, never on arrival). Playing and Queue do nothing.
- The bar is hidden on one title's detail (album, artist, playlist, book, author — R278's rule), and the mini
  bar stays.
- ♪ beside the brand in the top row (J3) is the only always-visible sign of which mode you are in.

**FR-R321-5 — Listen.** 279 FR-279-2's rows, in order:

1. *Recently added* — square cards 2½ across: title · artist.
2. *Recently played* — five song rows with *See all*, which opens Browse ▸ Songs sorted by *Most played*.
3. *Artists* — circles.
4. *A mix from your library* — only when the server sends it; a four-cover collage that plays shuffled.
5. One row per genre.

There is **no Now-playing card** (owner). An empty library shows one sentence: *Nothing filed as music yet*.

**FR-R321-6 — Browse = the library + search.** The **search field** sits at the top. Under it is a scrollable
chip strip: **Albums · Artists · Songs · Genres · Playlists · Audiobooks** (Audiobooks is R323 and absent without
the books library). The first chip is shown on arrival.

- **A query replaces the chips' content** with grouped results — Songs · Albums · Artists, three each with *See
  all*. Clearing it brings back the chip that was open.
- A song plays in the context of its group. No results: R187's one sentence (*Nothing matches "…"*).
- A **sort pill** in the top row's page slot (Albums/Artists/Songs) opens a sheet: *Recently added · A–Z · Year
  · Most played*.
- **Albums** — a 2-up 1:1 grid, *artist · year*. **Artists** — a 3-up circle grid, *N albums*. **Songs** — a
  dense list. **Genres** — chips with counts → that genre's albums, with a back link.

**FR-R321-7 — Album.**

- A full-bleed square cover. Its colour tints the ground below as a soft gradient (Aurora/Midnight); Noir keeps
  the flat ground.
- Title (24 sp), **artist as a link**, *2004 · 12 songs · 43 min*, and a type badge when not an album
  (*Live · Compilation · Single · EP · Soundtrack*).
- **Play** / **Shuffle**.
- The tracks: number (the playing one shows animated bars; paused, still bars), title (+ *feat.*, or the
  credited artist on a compilation), a lyrics glyph when there are lyrics, length, and ⋯.
- *More from {artist}*.
- Back returns to where it was opened.

**FR-R321-8 — Artist.**

- Circle image over a soft background (the artist background when there is one, else a gradient), name, and
  *Group · 1999–* (only what is known).
- A two-line biography with *More* → a sheet. No provider name.
- **Play all** / **Shuffle**.
- Groups *Albums · Singles & EPs · Compilations · Live*.
- *Songs* by play count — five, with *See all*.
- **Videos** (FR-R321-11).

**FR-R321-9 — My List.** ♡ in a track's ⋯ and on Now playing adds the song to the viewer's My List (279
FR-279-9). The Profile page's My List row lists songs and albums after films.

**FR-R321-10 — Playlists (J7 lean: two states).** The Browse ▸ Playlists chip shows either *No playlists yet ·
Add a song to a playlist from its ⋯ menu* with a *New playlist* button, or a list (four-cover collage · name ·
*N songs*) → a playlist page (Play · Shuffle · songs). *New playlist* opens a name sheet (system keyboard) drawn
once and marked **phase 2**, as is *Add to playlist…* in ⋯. They are brokered to Jellyfin's own playlists
(research §6.5), so they show in every Jellyfin client.

**FR-R321-11 — Videos on an artist.** When 279 returns `videos[]`: a row of 16:9 tiles (*title · length*,
*Music video · 2003*). A tap opens the **video** player (starting it stops the music — R322 FR-R322-12) and Back
returns to the artist. Absent when there are none. Phase 2, drawn once.

**FR-R321-12 — Skins.** Settings → Theme reskins music mode like every other screen: Aurora, Midnight, and Noir
(ink selections instead of gradients, flat grounds, square-ish covers).

**FR-R321-13 — Words.** The keys in brief §I and §M6 × en · da · fo, plus `mnav.now` (*Now playing*),
`mnav.now_short` (*Playing · Afspiller · Spælir*) and `music.nothing_played`. They are drafts: the shipped
`i18n/*.json` wins where a key exists, and R288's lexicon applies. **No viewer string names Jellyfin,
MusicBrainz, a codec, a bitrate or a protocol.**

## Acceptance

1. A viewer without the music grant sees R304's Profile, unchanged. A viewer with it sees the card, and one tap
   lands on Listen with the music bar, Playing in the middle.
2. Sign out and sign in as another viewer: the phone is in video mode.
3. Browse: typing *harbour* shows Songs/Albums/Artists groups in place of the chips, and clearing restores the
   chip. A second tap on Browse raises the keyboard; arriving on Browse never does.
4. Album and artist pages hide the bar and keep the mini bar. Back returns to the chip or row they came from.
5. An artist with a music video shows a Videos row. Tapping a tile plays it in the video player and the music
   stops.
6. Every screen renders in Aurora, Midnight and Noir, on the Pixel 9 and iPhone 16 frames.

## Mockup

`design/ravilo/Ravilo Mobile.html?mode=music` (or `?tab=profile` for the switch). The review panel beside the
phone holds §J and §M7 (localStorage `ravilo-music-q`, mode `ravilo-mode`) and every unreachable state:
account without music · library size · playlists none/two · skins. Its *Jump to* list reaches each page.

## Open questions

1. **The Queue tab and the queue button on Now playing** open the same list, one as a page and one as a sheet.
   Keep both, or drop the button on Now playing when Queue is a tab? Lean: drop the button — the tab is one tap
   away. **Answered by the owner 2026-09-28:** the lean — dropped when the bar has a Queue tab.
2. **Continue listening with no Audiobooks tab.** Under this bar a book in progress is two taps deep (Browse ▸
   Audiobooks), which the research warned against. Lean: Listen gains a *Continue listening* row above
   *Recently added* while a book is in progress (R323 FR-R323-2). **Answered by the owner 2026-09-28:** the lean; drawn.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Ten items; item 3 would fail a CI fence if built as drawn.

1. **The bar's items are a fixed enum.** `RaviloBottomNav(selected, onSelect, userInitials)` iterates
   `BottomNavItem.entries` and derives the pill's position from the index (`components/RaviloBottomNav.kt:84-94`,
   `enum class BottomNavItem { HOME, LIBRARY, SEARCH, DISCOVER, PROFILE }` at `:151`). Parameterise with an
   `items: List<BottomNavSpec>` (glyph · label key · id), keep the enum for the video list; `bottomBarShows(d)`
   and `bottomItemOf(d)` (`RaviloApp.kt:775-787`) learn the music destinations; `Dest` (`:219-326`) gains the
   nine listed.
2. **The mode store has a precedent.** `DeviceLanguageStore` (`screens/LastLanguageStore.kt:16-22`: an expect
   object with `read()` / `write()`, installed per platform) — a per-device `ListeningModeStore` in the same
   shape; **clear it in `signOutActiveSession`** (`screens/SettingsScreen.kt:126/156`) as FR-R321-1 says.
3. **♪ beside the brand must be a drawn glyph.** R315 made every UI glyph a Canvas glyph because the web build
   has no system fonts, and `scripts/check-web-glyphs.sh` fails a literal no bundled font can draw — a `♪`
   character in a Kotlin literal or an i18n value fails CI. Draw a `NoteGlyph` in the `PlayPauseGlyph` idiom.
4. **Browse = search** reuses `SearchScreen.kt`'s `reportTextFieldFocus()` seam
   (`seams/TextFieldFocusBridge.kt:19`) and R277's re-tap rule (`OnReselect`, `RaviloBottomNav.kt:162`). The
   field lives in the page head; the top row's page slot (`AppBar.handsetTopSlot`, `components/AppBar.kt:86-94`)
   holds the sort pill, as FR-R321-6 says.
5. **Detail pages and insets.** `bottomBarShows` already hides the bar on `MovieDetail`/`SeriesDetail`; add
   `AlbumDetail`, `ArtistDetail`, `PlaylistDetail`, `BookDetail`, `AuthorDetail`. `navBarInset`
   (`RaviloApp.kt:989-992`) sums the bar and `castMiniBarHeight` through `miniBarOver(dest)` — the music mini
   bar's height joins that sum (R322).
6. **Strings:** dotted keys in `i18n/{en,da,fo}.json` (R279); `scripts/check-ravilo-strings.sh` fails a
   literal in a text position; regenerate the lexicon (`scripts/check-i18n-spelling.sh --update-lexicon`) and
   commit it with the strings.
7. **Playlists through Jellyfin** (`POST /Playlists`, `/Playlists/{id}/Items`, 12.1 OpenAPI) with the
   viewer's token (`DeviceData.jellyfinUserToken`, `auth/Models.kt:17`) — feasible; phase 2 as written.
8. **The Videos row** opens `Dest.Player` on the `MUSIC_VIDEO` media id — the existing player path;
   `MediaKind.MUSIC_VIDEO` is already on the wire ✓.
9. **Open questions:** 1 — drop the queue button on Now playing (the tab is one tap away) ✓; 2 — FR-R323-2's
   *Continue listening* row on Listen ✓, it is the only one-tap place left for a book.
10. **Wire:** `/tv/config` unchanged; the mode is client-only ✓.

## Build notes (2026-09-28)

Built with R322 on `main` after 279. `:ravilo-ui` compiles for Android and the web, `:ravilo-android` assembles,
`:ravilo-web` compiles; the fences (`check-ravilo-strings`, `check-web-glyphs`, `check-i18n-spelling`,
`check-player-dex`) pass. **Not installed on any device.**

1. **The mode (FR-R321-1):** `ListeningMode` in its own per-device store (`MusicDeviceStore`: SharedPreferences
   `ravilo_music`, localStorage `ravilo.music.*`), remembered across launches — a cold start in music mode opens on
   Listen — and **cleared by `signOutActiveSession`** with the device's saved queue (`forgetListening()`).
2. **Absent, never greyed (FR-R321-2):** music mode needs the phone (`isHandset`), a build that can play
   (`MusicEngine.supported` — false on the web until its player exists, dev review 3), and the viewer's
   `/tv/music/home` answering rows. Asked per viewer; a mode stored for a viewer without it falls back to video.
3. **The switch (J1 → the lean):** `ListeningModeCard` under the photo and name on Profile, the same place in both
   modes; the chosen half on the brand gradient. A tap writes the mode and lands on that mode's first tab.
4. **The bar (FR-R321-4, dev review 1):** `BottomNavItem` gained LISTEN · BROWSE · PLAYING · QUEUE and the bar takes
   an item list (`VIDEO_BAR` / `MUSIC_BAR`) — the enum stays, extended, rather than a separate spec type. The four
   marks are drawn on the design's grid (`MusicGlyphs.kt`); ♪ beside the brand is a drawn `NOTE` glyph in the AppBar's
   new `brandBadge` slot (dev review 3). Re-tap: Listen scrolls to top, Browse raises the keyboard (R277's rule, never
   on arrival), Playing/Queue do nothing. Back follows R275's ladder with **Listen as music mode's Home**. Album,
   artist and playlist pages hide the bar and keep the mini bar.
5. **Listen (FR-R321-5):** the server's rows; no Now-playing card; *See all* on Recently played opens Browse ▸ Songs
   sorted by *Most played*; the mix is a four-cover collage that plays shuffled. Empty ⇒ *Nothing filed as music yet*.
6. **Browse (FR-R321-6):** the search field at the top; a query replaces the chips with Songs · Albums · Artists
   (three each, *See all* expands), a song plays in its group; the chips Albums (2-up) · Artists (3-up) · Songs ·
   Genres (→ that genre's albums with a way back) · Playlists; the sort pill in the top row's page slot. Lists load 60
   at a time as the viewer scrolls.
7. **Album / artist (FR-R321-7/8/11):** as drawn. **Deviation:** the ground under an album's cover is tinted from the
   skin's own accent, not from the cover's colours (no palette extraction yet); Noir stays flat. *Play all* on an
   artist plays every song of theirs, most played first — 279's artist detail now carries them all (≤ 200), the page
   shows five with *See all*. Groups include *Appears on* (someone else's album they are credited on). A music video
   opens the film player (and so stops the song, R322 FR-R322-12).
8. **My List (FR-R321-9):** ♡ in ⋯ and on Now playing marks the song in Jellyfin (`/tv/music/favorite`), and answers at
   once everywhere it is drawn. **Deviation:** Profile's My List row still lists films only.
9. **Playlists (FR-R321-10, J7 → two states):** empty or a list with the four-cover collage → a playlist page (279 gained
   `GET /tv/music/playlist/{id}`). *New playlist* and *Add to playlist…* are shown as *Coming later* (phase 2); no
   create sheet is drawn.
10. **Words (FR-R321-13):** 90 keys × en · da · fo in `i18n/*.json` from the design's drafts, plus the lexicon
    regenerated. Danish *repeat one* is *Gentag sangen* (the spelling fence rejects *én* beside *en*); `music.sort_az`
    joins `browse.sort.az` in the fence's exclusions. Open questions 1 and 2 → the leans (no queue button on Now
    playing; *Continue listening* is R323's).
