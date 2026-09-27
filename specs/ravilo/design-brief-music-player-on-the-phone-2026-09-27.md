# Design brief — A music player mode in Ravilo on the phone: switched to from Profile, its own bar, its own player

**Date:** 2026-09-27 · **For:** the design project (Cosmos) that owns `design/ravilo/` · **Status:** brief,
awaiting round-1 directions on the questions in §J; the rest is decided enough to draw into
`Ravilo Mobile.html`. Source: `specs/research-reports/music-library-and-player-2026-09-27.md` (§1.3, §2.4,
§5, §6, §7). The admin half is `specs/design-brief-music-in-the-admin-2026-09-27.md`.

> Owner's picture, 2026-09-27: *"add music streaming to the Ravilo mobile experience. So under the profile tab
> in Ravilo mobile one could click 'switch to music player' and back again. When on standard video mode, it just
> stays the same, but when I'm in music player mode, we get new options within the bottom navbar. So it's kinda
> a new app."*

**Settled by the research, so the brief can lean on it:** the phone only (the TV keeps its one job; the web
app follows in a later round); the **mode is per device** and remembered; the switch lives on the **Profile
page** and is **absent** — never greyed — when the viewer's account has no music library or the library is
empty; music keeps playing **in the background** (lock screen and notification are the platform's — we only
supply title · artist · cover); switching back to video mode does **not** stop the music — starting a video
does; the server composes music Home's rows, the phone renders them; **38 of the household's 60 tracks are
old WMA files that will be re-encoded on every play** — the phone never says so (R180: never a delivery cue).

## 0. What exists, so nothing is drawn twice

| Have | Where | Reuse |
|---|---|---|
| Phone frames: Pixel 9 (412 × 915) and iPhone 16 (`?dev=ios`), three skins, three languages | `ravilo/Ravilo Mobile.html`, `mobile/ravilo-mobile.css` | Every new screen in **both** frames, Aurora + Noir at least |
| The bottom bar: 74 dp, 36 dp gradient pill sliding under the selected item, 11.5 sp labels, opaque, on every page but the player/remote/one title's detail (R267 · R274 · R278) | `.bnav` / `.bn` / `.bnind` | **The same bar with a different set of five** (§B) — the pill, the reselect rule, the docking edge all carry over |
| The Profile page (`?tab=profile`): photo · name · *Admin*, **My List** row, Account (App language · Change password · Settings), **Sign out** behind the sheet, the version line (R304) | `Ravilo Mobile.html` | The switch is a new element on this page (§A) |
| The handset sheet (`HandsetSheet`), the two-level picker as a sheet, the season sheet (R244) | `mobile/ravilo-mobile-player.css` (`mp-*`, `rc-*`) | The queue sheet, the track ⋯ menu, the sign-out-style confirmations |
| The cast **mini bar** (64 dp, never dismisses while casting, docks on top of the bottom bar — R245 FR-R245-6 · R267 FR-R267-8) and the connecting bar | `Ravilo Mobile.html` | The **music mini bar** is this bar's geometry with music content (§E) |
| The cast remote, direction 2 *Now playing*: 16:9 art card on a solid ground, device chip as header (R245) | `Ravilo Mobile.html` | The music *Now playing* screen is its **square** cousin (§D) |
| R218's waiting states (three-dot pulse, *Loading…*), R237's failure copy (nine strings, no product/protocol/code), R261's fullscreen rule | player mockups | Reused verbatim for buffering and failure |
| Wordmark-on-gradient fallback tiles (R243: no logo ⇒ the name as text, **no "no logo" badge ever**) | `ravilo.css`, `Ravilo Mobile.html` | A track/album/artist without art |
| Strings en · da · fo | `ravilo-i18n.js` (design) / `i18n/*.json` (shipped — **wins** where a key exists) | Every new string in all three (§I) |

Rules carried over: **absent, never greyed**; **never name Jellyfin, MusicBrainz, a codec or a protocol** to
a viewer (the profile says *Admin*, sign-out says *your name and password* — same rule); **render-never-compute**
(the phone shows the rows the server sent); 46 dp targets, 13 sp floor (11.5 sp only for the bar's labels);
Noir drops accent tints (R221/R222); **system keyboard only** (no drawn keyboard anywhere); no focus visuals
on a phone (R298).

---

## A. The switch (Profile page)

### A1. What it is
One control on the Profile page that flips the phone between **video mode** (today's app, unchanged) and
**music mode**. Both directions live in the same place: in music mode the Profile page is the same page with
the control reading the other way. Tapping it: the bottom bar's five items **swap** (the pill parks under the
new Home), the app lands on the new mode's **Home**, and a short toast is *not* shown — the bar itself is the
confirmation.

### A2. Three directions to draw (round 1, §J1)
1. **A row in Account** — *Switch to music ›* under App language; cheapest, easiest to miss.
2. **A mode card at the top of the page**, under the photo/name block: two halves, *Films & series* / *Music*,
   the active half lit with the gradient (the pill's own idiom), a one-line sub-caption under each (*Home ·
   Library · Search · Discover* / *Home · Library · Search · Playlists*). **Lean.**
3. **A segmented control in the top row** of every page (brand · [Video | Music] · cast) — always visible,
   but it crowds R267's one page slot and the cast glyph.

### A3. States
- **Absent:** the viewer's account has no music library, or it is empty — the page is exactly R304's page.
  Draw this as the default state so it is clear nothing changes for a household without music.
- **Present, video mode active.** · **Present, music mode active.**
- **First switch ever:** no onboarding sheet; the Home of music mode explains itself (§C1's empty/first
  state).
- **Sign out** clears the mode (the next viewer on this phone starts in video mode).

## B. The bar in music mode

Five items, **Profile always fifth** (it is the way back), the same pill. Directions (round 1, §J2):

| | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| **(a) mirror the video bar — lean, amended by part 2** | Home | Library | Search | **Audiobooks** | Profile |
| (a′) part 1's original | Home | Library | Search | Playlists | Profile |
| (b) the catalogue up front | Home | Albums | Artists | Search | Profile |
| (c) the player up front | Listen | Browse | Search | Queue | Profile |

**Part 2 amendment (§M):** the household also has audiobooks, and a book shelf deserves the fourth item —
*Continue listening* is the one row a listener opens the app for. **Playlists becomes a chip in Library**
(Albums · Artists · Songs · Genres · Playlists). The mode card's second half reads **Music & audiobooks**.

Why (a) leans: the shape is identical to the video bar (Home · Library · Search · Discover · Profile), so the
pill position, R267 FR-R267-9's tap-on-active-scrolls-to-top, R275's Back ladder to Home, the mini bar's
docking edge and every inset rule carry over untouched; *Library* holds Albums · Artists · Songs · Genres as
chips at the top (Discover's own strip idiom, R262/R268), so Albums and Artists do not each need a tab; and
*Playlists* takes Discover's slot as the one page that is "not the catalogue". Glyphs: Home (as today),
Library (a stack of squares — not the video Library's book), Search (as today), Playlists (a list with a
note), Profile (the avatar). Labels × en/da/fo in §I.

The brand in the top row stays; a small **♪ glyph beside the brand mark** in music mode is a round-1 option
(§J3) — it is the only always-visible reminder of which app you are in when the bar is hidden (player).

## C. The pages

### C1. Home (music)
Server-composed rows, in order, all system rows in this round (no admin editor): **Recently added** (albums,
square 2-up-and-a-half cards, title · artist), **Recently played** (tracks, a dense list of 5 with a *See all*),
**Artists** (circles, a horizontal row), **Albums by genre** (one row per genre the library has ≥ 3 albums in),
**Mix** (*A mix from your library* — a single wide card with four covers in a 2×2 collage; **absent** when the
library is too small to mix, which the household's 60 tracks are — draw both). Pull-to-refresh; the top row
is brand · (nothing) · cast.
- **Empty** (the library exists but nothing is filed yet): one sentence, *Nothing filed as music yet*, the
  bar still there.
- **First visit** is not special.

### C2. Library (music)
Chips at the top: **Albums · Artists · Songs · Genres** (scrollable strip like Discover's, the first chip on
arrival, tap-on-active steps to the next like Discover). A sort control in the top row's page slot
(*Recently added · A–Z · Year · Most played*).
- **Albums:** a 2-up grid of square covers, title (1 line) · artist (1 line, `--ink-dim`), a small year.
  No cover ⇒ the title set as a wordmark on the gradient, the caption then carries only the artist (R243's
  rule — never a "no cover" badge).
- **Artists:** a 3-up grid of circles, name, *N albums*.
- **Songs:** a list: cover thumb 48 dp · title · artist — album · length; tap plays the song **in the context
  of the list** (the rest of the list becomes the queue), ⋯ opens the track menu (§F).
- **Genres:** chips with counts → the Albums grid filtered.

### C3. Album
Full-bleed square cover at the top (the cover's own colours bleed into the ground below as a soft gradient —
Aurora/Midnight; Noir keeps the flat ground), title (24 sp), **artist as a link**, *2004 · 12 songs · 43 min*
· type badge (*Live*, *Compilation* — only when not a plain album), two actions **Play** and **Shuffle**
(46 dp, Play primary), then the **track list**: # · title · length, the playing track marked by a small
animated bars glyph in the gradient and its row lit; a **lyrics glyph** on tracks that have them; ⋯ per
track. Missing positions in a partial album are **not** drawn as gaps (the viewer sees an order, never a
reason). Below: *More from {artist}* (a row of the artist's other albums). Back returns to where the album
was opened. The bottom bar is **hidden** here (one title's detail — R278's rule), the mini bar stays.

### C4. Artist
Artist image as a circle over a soft background (fanart background when there is one; else the gradient),
name, *Group · 1987–1994* (only what is known), a two-line biography with *More* → a sheet, **Play all** /
**Shuffle**, then **Albums** (2-up grid grouped *Albums · Singles & EPs · Compilations · Live* — a header only
for groups that exist), then **Songs** (top 5 by play count, *See all*), then — **phase 2, draw once** — a **Videos** row: the
artist's music videos and concert films from the video library as 16:9 tiles, which open the **video**
player and come back here; **absent** when the artist has none. Bar hidden as on a title's detail.

### C5. Search (music)
The system keyboard rises on re-tap only (R277's rule, unchanged). Results grouped **Songs · Albums ·
Artists**, each group capped at 3 with *See all*; a song plays in the context of its group. Empty query
shows *Recently played* and *Artists*. No results: R187's one sentence.

### C6. Playlists
Round 1 draws **two states only**: the **empty** state (*No playlists yet · Add a song to a playlist from its
⋯ menu*) and a **list** of playlists (name · N songs · 4-cover collage). Creating/editing is phase 2
(brokered to Jellyfin's playlists, so they show up in every Jellyfin client) — draw the *New playlist* sheet
(name field, system keyboard) once so the shape is fixed, mark it phase 2.

## D. Now playing (the full-screen player)

Opened by tapping the mini bar or a playing row; **no bottom bar** (the player's rule); swipe down or Back
closes it to wherever the viewer was. Three directions (round 1, §J4):

1. **Cover-first (lean):** large square cover centred (≈ 80 % width, 24 dp radius, the ground tinted from
   it), title (20 sp, marquee if long) · artist · album (links), a seek bar with elapsed / remaining, the
   transport row **shuffle · previous · play/pause (64 dp) · next · repeat** (repeat cycles off → all → one,
   the glyph shows which), a bottom row **lyrics · queue · cast · ⋯**. This is the cast remote's square cousin.
2. **Lyrics-first:** the synced lyrics fill the screen, the current line lit in the gradient and centred, the
   cover small in a top row with title/artist; transport pinned at the bottom.
3. **Queue-first:** the cover as a header, the queue as the body.

Whatever wins, draw the **lyrics** as a state of the same screen (a toggle or a horizontal swipe): synced
lines scroll with the clock and tapping a line seeks (Jellyfin's own behaviour); plain lyrics are a scroll
without highlighting; **no lyrics** ⇒ the glyph is absent (not greyed).

### D1. States
Playing · paused · **buffering** (R218's treatment: the play glyph's place shows the three-dot pulse, nothing
else moves) · **no cover** (the album's wordmark tile at cover size) · **queue end** (the transport's *next*
is absent; when the last song ends the screen stays on it, paused at 0:00 — nothing auto-restarts unless
repeat is on) · **failure** (R237's sheet, its copy reused — *couldn't play this*, *Try again*, *Skip*) ·
**casting** (the device chip appears under the title as in the remote; controls act on the TV; a later
phase — draw once) · **landscape** (the cover shrinks to the left, the list/lyrics take the right; R244's
follow-the-sensor rule; phone only).

### D2. Gestures
Swipe down closes; swipe left/right on the cover = next/previous (with the cover sliding); double-tap does
nothing (no seeking gestures on music — a song is short); the seek bar has R244's time bubble.

## E. The mini bar

Docks **on top of the bottom bar** exactly where the cast mini bar docks (64 dp, opaque, hairline top edge);
when both exist (casting *and* music) they stack, cast on top — draw it. Content: cover 48 dp · title (1
line) · artist (1 line, `--ink-dim`) · **play/pause** · **next** (46 dp each); a **1.5 dp progress hairline**
along its top edge in the gradient. Tap anywhere else opens *Now playing*. **Swipe down = stop and dismiss**
(the queue is gone; nothing else in this app dismisses by swipe, so draw the affordance as a subtle
grabber). It is present on **every music-mode page** including Profile, on one title's detail (where the bar
is not), and — after the viewer switches back to **video mode** — on every video page too, until a video
starts (then it is gone, the music paused by the system). Draw that video-mode frame: the video Home with the
music mini bar over the video bottom bar.

## F. The track ⋯ menu and the queue

- **⋯ (a `HandsetSheet`):** *Play next · Add to queue · Add to playlist… (phase 2) · Go to album · Go to
  artist · ♡ My List* (favourites ride the same server state as films: the row says *My List*, as the Profile
  page does). Row 46 dp, glyph + label, the sheet's grabber.
- **Queue (a sheet from *Now playing*):** *Now playing* (one row, lit), *Up next* (drag handles to reorder,
  swipe left to remove), *Clear queue* at the bottom; the sheet's header shows *N songs · 1 h 12 min left*.
  Empty queue is impossible while something plays; after the last song the sheet shows only the played one.

## G. The platform's own surfaces (nothing to draw, everything to specify)

Android draws the **notification and lock-screen card** from what we supply: title · artist · cover (the
square, ≥ 512 px), the actions previous · play/pause · next. Bluetooth/headphone buttons and *audio becoming
noisy* (unplugging) pause by the platform's rule. A phone call pauses; the music resumes after (audio focus).
The brief needs only the **metadata mapping** written down: title = song, artist = the credited artists
joined *, *, album = album title, artwork = the cover the app shows.

## H. Absent states (what a viewer never sees)

No switch, no bar, no music anywhere for: a viewer whose account is not granted the music library; a TV; the
web app (this round); an empty library. A kids profile follows the library grant only (owner question in the
research §7 — draw nothing special).

## I. Strings (× en · da · fo — drafts; the shipped table wins where a key exists; the lexicon is the build's job)

| key | en | da | fo |
|---|---|---|---|
| `mode.music` | Music | Musik | Tónleikur |
| `mode.video` | Films & series | Film og serier | Filmar og seriur |
| `mode.switch_music` | Switch to music | Skift til musik | Skift til tónleik |
| `mode.switch_video` | Back to films & series | Tilbage til film og serier | Aftur til filmar og seriur |
| `mnav.home` | Home | Hjem | Heim |
| `mnav.library` | Library | Bibliotek | Savn |
| `mnav.search` | Search | Søg | Leita |
| `mnav.playlists` | Playlists | Playlister | Spælilistar |
| `mlib.albums` | Albums | Album | Fløgur |
| `mlib.artists` | Artists | Kunstnere | Tónleikarar |
| `mlib.songs` | Songs | Sange | Løg |
| `mlib.genres` | Genres | Genrer | Sjangrur |
| `mhome.recent_albums` | Recently added | Senest tilføjet | Nýliga lagt afturat |
| `mhome.recent_played` | Recently played | Senest afspillet | Nýliga spælt |
| `mhome.mix` | A mix from your library | Et miks fra dit bibliotek | Ein blanda úr tínum savni |
| `mhome.empty` | Nothing filed as music yet | Ingen musik endnu | Eingin tónleikur enn |
| `music.play` | Play | Afspil | Spæl |
| `music.shuffle` | Shuffle | Bland | Blanda |
| `music.play_all` | Play all | Afspil alle | Spæl øll |
| `music.songs_n` | {n} songs | {n} sange | {n} løg |
| `music.songs_one` | 1 song | 1 sang | 1 lag |
| `music.now_playing` | Now playing | Afspiller nu | Spælir nú |
| `music.up_next` | Up next | Næste | Næst |
| `music.queue` | Queue | Kø | Bíðirøð |
| `music.queue_left` | {n} songs · {t} left | {n} sange · {t} tilbage | {n} løg · {t} eftir |
| `music.clear_queue` | Clear queue | Ryd kø | Tøm bíðirøð |
| `music.play_next` | Play next | Afspil som næste | Spæl sum næst |
| `music.add_queue` | Add to queue | Føj til kø | Legg í bíðirøð |
| `music.add_playlist` | Add to playlist… | Føj til playliste… | Legg í spælilista… |
| `music.go_album` | Go to album | Gå til album | Far til fløgu |
| `music.go_artist` | Go to artist | Gå til kunstner | Far til tónleikara |
| `music.lyrics` | Lyrics | Sangtekst | Tekstur |
| `music.repeat_off` / `_all` / `_one` | Repeat off / Repeat / Repeat one | Gentag fra / Gentag / Gentag én | Endurtak av / Endurtak / Endurtak eitt |
| `music.more_from` | More from {artist} | Mere fra {artist} | Meira frá {artist} |
| `music.no_playlists` | No playlists yet | Ingen playlister endnu | Ongir spælilistar enn |
| `music.no_playlists_hint` | Add a song to a playlist from its ⋯ menu | Føj en sang til en playliste fra dens ⋯-menu | Legg eitt lag í ein spælilista úr ⋯-valmyndini |
| `music.new_playlist` | New playlist | Ny playliste | Nýggjur spælilisti |
| `music.type.live` / `.compilation` / `.single` / `.ep` / `.soundtrack` | Live / Compilation / Single / EP / Soundtrack | Live / Opsamling / Single / EP / Soundtrack | Livandi / Savn / Single / EP / Filmstónleikur |
| `music.stop` | Stop | Stop | Steðga |

Reused as-is: `nav.profile`, `pm.my_list`/`profile.*`, R218's `player.loading`, R237's failure strings,
R187's no-results sentence, the genre names from 271/R312 where a music genre coincides (it usually does not).

## J. Round-1 questions (directions wanted, owner picks)

1. **The switch's form** (§A2): a row · **a mode card (lean)** · a top-row segment.
2. **The bar** (§B): **(a) Home · Library · Search · Audiobooks · Profile (lean, part 2)** · (a′) part 1's
   Playlists in the fourth slot · (b) Albums and Artists as tabs · (c) the player up front.
3. **A ♪ beside the brand in music mode** — draw or not? Lean: draw it small; it is the only reminder on the
   player, where the bar is hidden.
4. **Now playing** (§D): **cover-first (lean)** · lyrics-first · queue-first — lyrics as a state of the winner.
5. **Mini bar dismissal:** swipe-down stops (lean) vs a ✕ that stops vs no dismissal (like the cast bar,
   which never dismisses while casting). Music is different — a viewer wants to *stop* music without opening
   a screen.
6. **Loudness — *Even out volume*:** a switch in Settings (on by default, lean) or not exposed at all.
7. **Playlists in round 1:** two states only (lean) or the whole create/edit flow now.

## M. Part 2 — Audiobooks (added 2026-09-27; source: `research-reports/audiobooks-library-and-player-2026-09-27.md`)

**What changes the drawing:** a book is long, listened to once, in order, over weeks — so the shelf leads
with *Continue listening*, the player gains **speed · sleep timer · chapters · bookmarks · ±30 s**, and the
lock-screen card's side actions become ±30 s instead of previous/next. Everything else — the switch, the
service, the mini bar, the sheets — is part 1's. **Speed exists here and only here**: the video player's
*no speed* ruling (2026-09-16) stands where it was made; draw no speed control anywhere but the book player.

### M1. The **Audiobooks** page (the bar's fourth item)
- **Continue listening** at the top: one large card per book in progress (cover 1:1 · title · author · a
  progress ring with *3 h 12 min left* · *Chapter 9 · Part 9 of 14* in `--ink-dim`); tap = **resume**, no
  confirmation. Absent when nothing is in progress (then *All books* starts at the top — no empty banner).
- **All books:** 2-up square covers, title (1 line) · author (1 line), a small ✓ on finished books, the
  progress ring on started ones. Sort in the top row's page slot: *Recently added · Title · Author · Series*.
- **Authors** and **Series** as chips under the heading, present only when the library has any (the
  household has one book: draw the page with one card, unapologetic).
- **Empty library:** *Nothing filed as audiobooks yet* — one sentence.

### M2. The Book page
Cover (1:1, large) · title · subtitle · **author** (link) · *Read by {narrator}* (absent when unknown) ·
*5 h 24 min · 14 chapters* · series chip *Book 2 of 5* (link to the series list) when known. Actions:
**Continue** (*from 2 h 11 min*) or **Start** when unstarted, and ⋯ (*Start over · Mark as finished · Add
to My List · Go to author*). Description (3 lines + *More*). **Chapters** list: # · title · length, the
current one lit with the bars glyph, finished ones dimmed; tap plays from there. Bar hidden (one title's
detail), mini bar stays.

### M3. Now playing — the book variant of the same screen
Part 1's *Now playing* with these differences: **±30 s** replace previous/next (long-press repeats);
**speed chip** (*1.0×* → a sheet: 0.8 · 0.9 · 1.0 · 1.1 · 1.2 · 1.5 · 1.75 · 2.0, remembered per book); a
**sleep** glyph (sheet: *15 · 30 · 45 · 60 min · End of chapter*; when set, the glyph shows the remaining
minutes; the last 10 s fade); **chapters** glyph (sheet as §M2's list); **bookmark** glyph (adds one at the
position with an optional one-line note — system keyboard; long-press lists them); the seek bar shows the
**chapter** (elapsed / chapter length) with the **book** progress as a thin second line and *3 h 12 min
left* right-aligned; title = chapter, subtitle = book · author. No shuffle, no repeat, no lyrics. States:
playing · paused · buffering (R218) · sleep set · at a chapter boundary (the title swaps, nothing else
moves) · book finished (*Finished · Start over*) · failure (R237). Skip-silence lives in Settings, not here.

### M4. The mini bar
Same geometry as part 1: cover · *chapter title* / *book · author* · **−30 s · play/pause** (not next);
the progress hairline is the **book's**. Swipe-down stops as for music.

### M5. Settings (a small *Listening* group)
*Even out volume* (music, part 1) · *Skip silences in audiobooks* (off) · *Sleep timer fade* (on). Nothing
else; speed is per book on the player.

### M6. Strings (× en · da · fo — drafts)
| key | en | da | fo |
|---|---|---|---|
| `mnav.audiobooks` | Audiobooks | Lydbøger | Ljóðbøkur |
| `mode.music_books` | Music & audiobooks | Musik og lydbøger | Tónleikur og ljóðbøkur |
| `ab.continue` | Continue listening | Fortsæt med at lytte | Hald fram at lurta |
| `ab.continue_from` | Continue · {t} | Fortsæt · {t} | Hald fram · {t} |
| `ab.start` | Start | Start | Byrja |
| `ab.start_over` | Start over | Start forfra | Byrja av nýggjum |
| `ab.left` | {t} left | {t} tilbage | {t} eftir |
| `ab.read_by` | Read by {narrator} | Indlæst af {narrator} | Lisin av {narrator} |
| `ab.chapters_n` | {n} chapters | {n} kapitler | {n} kapitlar |
| `ab.chapter_n` | Chapter {n} | Kapitel {n} | Kapittul {n} |
| `ab.part_of` | Part {n} of {m} | Del {n} af {m} | Partur {n} av {m} |
| `ab.book_of` | Book {n} of {m} | Bog {n} af {m} | Bók {n} av {m} |
| `ab.authors` / `ab.series` | Authors / Series | Forfattere / Serier | Høvundar / Røðir |
| `ab.finished` / `ab.mark_finished` | Finished / Mark as finished | Færdig / Markér som færdig | Liðugt / Merk sum liðugt |
| `ab.speed` | Speed | Hastighed | Ferð |
| `ab.sleep` / `ab.sleep_end_chapter` / `ab.sleep_min` | Sleep timer / End of chapter / {n} min | Sleep-timer / Kapitlets slutning / {n} min | Svøvnur / Enda á kapitli / {n} min |
| `ab.bookmark_add` / `ab.bookmarks` | Add bookmark / Bookmarks | Tilføj bogmærke / Bogmærker | Legg bókamerki afturat / Bókamerki |
| `ab.skip_back` / `ab.skip_fwd` | 30 s back / 30 s forward | 30 sek. tilbage / 30 sek. frem | 30 sek. aftur / 30 sek. fram |
| `ab.skip_silence` | Skip silences in audiobooks | Spring over pauser i lydbøger | Leyp um tøgn í ljóðbókum |
| `ab.empty` | Nothing filed as audiobooks yet | Ingen lydbøger endnu | Ongar ljóðbøkur enn |
| `mlib.playlists` | Playlists | Playlister | Spælilistar |

### M7. Round-1 questions (part 2)
1. **Speed on the book player** — yes (lean) or the video ruling extends to audiobooks too.
2. **The bar's fourth item:** Audiobooks (lean) or keep Playlists and put Books in Library.
3. **Bookmarks in round 1** (lean yes) or later.
4. **Lock-screen side actions ±30 s** (lean) or the platform default previous/next.
5. **Skip silence** exposed at all (lean: Settings only, off).

## K. Deliverables and order

1. Profile page with the switch in its three directions and its absent state (§A) — both frames, Aurora + Noir.
2. The music bar in the three directions on a Home frame (§B); the winner then everywhere.
3. Home (with and without the Mix row; empty), Library's four chips, Album, Artist, Search, Playlists' two
   states (§C).
4. *Now playing* in three directions, then the winner's eight states incl. lyrics and landscape (§D).
5. The mini bar on a music page, on the Profile page, stacked under the cast bar, and on a **video-mode**
   page (§E); the ⋯ sheet and the queue sheet (§F).
6. Strings × en/da/fo (§I) into `ravilo-i18n.js`.
7. **Part 2:** the Audiobooks page (Continue listening · All books · one-book state · empty), the Book page,
   *Now playing*'s book variant with its six states and three sheets (speed · sleep · chapters/bookmarks),
   the book mini bar, the *Listening* settings group, strings (§M).
Then the specs (R320–R324 prospectively — verify against `main` first) are written from the mockups and
dev-reviewed, and the phone build starts with R321's service.

## Non-goals (this round)

The TV; the web app (a following brief — `<audio>` behind the same seam, iOS ≥ 17.5 background rules to
verify on the household iPhone); casting music to a TV or Chromecast beyond one drawn state; **music videos and
concert films inside the music player** — they are video files and stay video-mode content, reached from
an artist's *Videos* row (§C4, phase 2); **ebooks** (the library has none and Ravilo has no reader); offline downloads; scrobbling; an audiobook mode; an admin editor for music Home rows; anything that names how a
track is delivered.
