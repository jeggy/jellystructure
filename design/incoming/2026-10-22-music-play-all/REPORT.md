# REPORT — round music-play-all (2026-10-16)

No phase number. None was asked for, none is used, and every place a spec would carry one is left blank.

## Package

1. **Directions doc (self-contained):** `ravilo/music-play-all/Music Play All - Directions - self-contained.html`. One file with inline CSS, inline JS and the three fonts as data URIs. No CDN and no links out.
2. **PNGs:** `ravilo/music-play-all/png/`, 70 files named `music-play-all-<direction>-<surface>-<state>.png`. They are 1 device px per CSS px and every one is under 192 KiB (largest: 123 KiB).
3. **Source the canvas renders from:** `Music Play All - Directions.html` + `music-play-all.css` + `music-play-all-kit.js` (frame builders) + `music-play-all.js` (canvas layout) + `fonts/`. The hex, px, weight, radius and easing values are in the CSS. The new tokens are listed at the end of the CSS.

Direction keys in the PNG names: `today` (reference, nothing added), `a`, `b` and `c` (phone directions), `d1` and `d2` (wide desktop). Surfaces: `phone`, `phone-land`, `desktop-compact`, `desktop-wide`.

**Capture note:** album covers are CSS gradients, and the PNG capture bands them into steps. This is a rendering artefact, not a design choice. The HTML is the reference.

## Files

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `ravilo/music-play-all/Music Play All - Directions - self-contained.html` | 191763 | 720 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/Music Play All - Directions.html` | 741 | 16 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/music-play-all.css` | 28532 | 308 | `/* music-play-all — Play all / Shuffle all on the music list pages (round slug music-play-all, 2026-10-16).` | `   Everything else (fills, border, weight 600, glyphs, Noir's ink-for-gradient swap, desktop .bt) is reused as-is. */` |
| `ravilo/music-play-all/music-play-all-kit.js` | 26838 | 210 | `/* music-play-all — frame builders (window.MPK). Stand-ins are the brief's and the music mockup's corpus (app/music-data.js);` | `})();` |
| `ravilo/music-play-all/music-play-all.js` | 30434 | 179 | `/* music-play-all — the canvas: what is fixed → directions → comparison → every state (A + D1) → decided rows → strings.` | `})();` |

Binary files (byte size only):

- `ravilo/music-play-all/fonts/jetbrains-mono.woff2` — 31432 bytes
- `ravilo/music-play-all/fonts/sora.woff2` — 25284 bytes
- `ravilo/music-play-all/fonts/space-grotesk.woff2` — 22288 bytes

PNGs (70, largest 126536 bytes):

- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-albums.png` — 59683 bytes
- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-artists.png` — 63926 bytes
- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-genre.png` — 60989 bytes
- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-listen.png` — 59869 bytes
- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-search.png` — 58253 bytes
- `ravilo/music-play-all/png/music-play-all-a-desktop-compact-songs.png` — 72356 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-albums-s1.png` — 60037 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-albums.png` — 60037 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-artists.png` — 62683 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-capped-da.png` — 73477 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-capped-en.png` — 73856 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-capped-fo.png` — 72509 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-casting.png` — 60065 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-crowded-listen.png` — 61747 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-crowded-s7.png` — 59137 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-crowded-scrolled.png` — 58321 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-crowded-search.png` — 58753 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-crowded.png` — 59137 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-empty-genre.png` — 35687 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-empty-search.png` — 27021 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-error.png` — 61592 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-from-genre.png` — 68028 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-from-library-da.png` — 69054 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-from-library-fo.png` — 70889 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-from-library.png` — 69069 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-from-search.png` — 71688 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-genre.png` — 60197 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-land-crowded-scrolled.png` — 39751 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-land-crowded.png` — 42005 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-land-listen.png` — 35711 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-land-search.png` — 38457 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-listen-lower.png` — 61946 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-listen-s1.png` — 61259 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-listen-top.png` — 61259 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-listen.png` — 61747 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-loading.png` — 40281 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-longest-da-genre.png` — 60325 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-longest-da.png` — 60308 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-longest-fo-search.png` — 58969 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-longest-fo.png` — 58839 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-one-song.png` — 33782 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-search.png` — 60025 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-skin-aurora.png` — 59137 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-skin-midnight.png` — 60182 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-skin-noir.png` — 62563 bytes
- `ravilo/music-play-all/png/music-play-all-a-phone-songs.png` — 73528 bytes
- `ravilo/music-play-all/png/music-play-all-b-phone-albums.png` — 60203 bytes
- `ravilo/music-play-all/png/music-play-all-b-phone-crowded-scrolled.png` — 57801 bytes
- `ravilo/music-play-all/png/music-play-all-b-phone-land-crowded-scrolled.png` — 41015 bytes
- `ravilo/music-play-all/png/music-play-all-b-phone-listen.png` — 64795 bytes
- `ravilo/music-play-all/png/music-play-all-c-phone-albums.png` — 59398 bytes
- `ravilo/music-play-all/png/music-play-all-c-phone-crowded-scrolled.png` — 61915 bytes
- `ravilo/music-play-all/png/music-play-all-c-phone-longest-fo.png` — 58419 bytes
- `ravilo/music-play-all/png/music-play-all-c-phone-menu.png` — 59999 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-albums-s1.png` — 102496 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-albums.png` — 109767 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-artists.png` — 114248 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-capped.png` — 126536 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-focus-play.png` — 104284 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-focus-shuffle.png` — 104605 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-genre.png` — 91167 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-listen.png` — 104412 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-loading.png` — 55885 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-search.png` — 93326 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-skin-midnight.png` — 108809 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-skin-noir.png` — 110051 bytes
- `ravilo/music-play-all/png/music-play-all-d1-desktop-wide-songs.png` — 122856 bytes
- `ravilo/music-play-all/png/music-play-all-d2-desktop-wide-albums.png` — 109809 bytes
- `ravilo/music-play-all/png/music-play-all-today-phone-albums.png` — 59312 bytes
- `ravilo/music-play-all/png/music-play-all-today-phone-crowded.png` — 59579 bytes

REPORT.md itself (`ravilo/music-play-all/REPORT.md`) is not measured here — writing its own size would change it.


## Direction headings, character for character

- `Today, with nothing added` (reference)
- `Direction A · The pair heads the list` (recommended)
- `Direction B · The pair stays pinned`
- `Direction C · One shuffle button in the top row`
- `Decision 2 · Where the pair sits on Listen`
- `Decision 3 · Where the pair sits on the desktop`. Its two frames are captioned `D3·a · D1 · right of the title (recommended)` and `D3·b · D2 · under the title`.

## Recommendation

- **Phone and compact desktop: A.** A compact pair, 38 px tall (the sort pill's height), heads each list and scrolls away with it. In landscape it sits on the search field's row.
- **On Listen:** at the top, above *Recently added*. This is the lean.
- **On the wide desktop: D1.** The pair is right-aligned in the page head beside the title. This is the lean.
- **Why A:** on the crowded case (360 dp, mini player docked) it costs 52 px at rest and 0 once the list is scrolled.
  - B pins 114 px on every page and leaves 74 px of list in landscape.
  - C hides *Play all* behind a long press, is an icon-only third button style, and overflows the 360 dp top row (cast is pushed off-screen in Faroese).

## Every UI string the drawing shows

These are the round's own strings:

| en | da | fo | status |
|---|---|---|---|
| Play all | Afspil alle | Spæl øll | settled; already ships as `music.play_all` |
| Shuffle all | Bland alle | Blanda øll | settled |
| Playing {shown} of {total} songs | Afspiller {shown} af {total} sange | Spælir {shown} av {total} sangum | settled |
| Your library | Dit bibliotek | Títt savn | settled; a *Playing from* value |
| Search "{q}" | — | — | settled in English; **the brief gives no da/fo**, so it is drawn in English only (finding) |

**Finding: a sixth string is shown.** The error state's toast reuses the shipped `music.fail_t`: *Couldn’t play this* / *Kunne ikke afspille* / *Kundi ikki spæla hetta*. The string already exists, but this is a new place for it. A specific error line is not proposed.

All other text in the frames is existing and unchanged: chips, row headings, sort pill, counts, *Nothing matches “{q}”*, the bottom bar, and *Playing from {x}* / *Afspiller fra {x}* / *Spælir úr {x}*. The genre value is the genre's own name, *Indie Folk*.

The following are canvas annotations, not UI: the yellow ①② tab-order badges, the dashed outline on the *Playing from* line, and the "compact · 400 dp" window strip.

## States

**Drawn:**

- **1 · each surface with the pair in place.**
  - Phone 360: Listen, Artists, Albums, Songs, genre (Indie Folk), search (harbour).
  - Desktop wide: the same six.
  - Desktop compact: the same six.
- **2 · loading.** Phone Songs and desktop-wide Albums. The pair is drawn and the count is a placeholder.
- **3 · empty selection.** Phone search *harbor* with no results, and a phone genre with no songs. The pair is absent.
- **4 · one song.** Phone search *kvøldsól*. Both buttons stay.
- **5 · capped toast.** Phone en, da and fo; desktop wide en.
- **6 · Playing from.**
  - *Your library*: en, da, fo.
  - *Indie Folk*: en.
  - *Search "harbour"*: en.
- **7 · while music is playing.**
  - Mini player docked, 360 dp: Albums at rest, Albums scrolled, search, Listen.
  - The same case for directions B and C, for comparison.
- **8 · phone at 360 dp and in landscape.**
  - Landscape: Albums at rest, Albums scrolled, Listen, search.
  - Landscape scrolled for B.
- **9 · desktop at both widths.** Compact (400 dp, phone layout) and wide (1120).
- **10 · desktop keyboard focus.** Play all focused (Aurora) and Shuffle all focused (Noir). The tab order is shown.
- **11 · all three skins.** Phone Aurora, Midnight and Noir; desktop Aurora (all other desktop frames), Midnight and Noir.
- **12 · casting.** Phone, with the mini player showing *Stue*. There is no visual difference, as expected.
- **Error.** Phone toast. The buttons do not change.
- **Longest string, Danish and Faroese.** Phone 360: da Albums, fo Albums, da genre, fo search, each with the sort pill in its row.

**Skipped:**

- **Offline.** Not drawn separately. On this surface a lost connection and a failed request both mean "nothing started", so it is treated as error, as the brief leans.
- **Desktop empty, desktop one-song, desktop casting and desktop error.** Skipped. The phone frames carry these states, and the desktop pair behaves identically.
- **6 · Playing from on the desktop.** Skipped: the desktop Playing page has no *Playing from* line today (finding).
- **Desktop da/fo.** The desktop frames are English only. The longest-string case is carried by the phone and the compact desktop, which uses the phone layout.
- ***Indie Folk* and *Search "harbour"* in da/fo.** The genre name doesn't translate, and *Search "{q}"* has no da/fo.
- **10-foot focus.** **Not applicable.** There is no TV surface in this round.

## Existing controls, strings and rows

**None changed** on any of the five surfaces. The following are findings only, not edits:

- The desktop album page puts its buttons under the title and meta, on the left, not right of the title. D1 draws the lean; D2 draws the album page's real geometry.
- The desktop has no sort pill on Artists, Albums or Songs. None was added.
- The desktop search groups are built Artists · Songs · Albums. They are drawn in the requirement's order, Songs · Albums · Artists.
- The desktop Playing page has no *Playing from* line.
- Listen also carries *Continue listening* (a book in progress). The brief's row list omits it, so it is not drawn. If present, the pair sits above it.
- The phone mockup builds the genre view under the Genres chip. It is drawn as the requirement says: the Albums chip filtered, with the sort pill showing.
- At 360 dp the top row (brand · sort pill · cast) already overflows today in Faroese: cast is clipped by 7 px. This round does not change it.
- Faroese: the settled toast says *sangum*, while the shipped set says *løg* for songs (`music.songs_n` = *{n} løg*). The toast is drawn as settled.
- *Profile* (bottom bar) and *Clear* (search field) are hard-coded English in the phone markup, so they stay English in the da/fo frames.
- The empty-genre frame reuses the name *Indie Folk* with 0 albums, because the corpus has no empty genre.

## New tokens

These are the only new tokens:

- `--mpa-pair-h: 38px`: the phone pair's height, equal to the existing sort pill.
- `--mpa-pair-r: 12px`: the phone pair's radius, the album pair's 14 px scaled to 38 px.

Everything else is reused:

- Phone: the gradient for *Play all*, the ghost style for *Shuffle all*, and Noir's ink-for-gradient swap.
- Desktop: the album page's `.bt.pri` / `.bt`, unchanged.
- The focus ring: 2 px `--ring` with a 2 px `--bg` gap.

## Files edited in place

- **No file outside `ravilo/music-play-all/` was edited.** A grep of `app/` and `ravilo/ravilo-i18n.js` for this round's strings and classes finds nothing new.
- **Inside the folder,** the canvas files were already present at the start of this session, written earlier in this round. This session edited two of them in place: `music-play-all-kit.js`, one label, "compact · 400 pt" → "compact · 400 dp"; and `music-play-all.js`, two panel x-positions (X0+1240 → X0+1310, X0+1560 → X0+1630) to stop two pairs of canvas panels overlapping by 8 px.
- **Deleted:** scratch captures `_probe.png`, `_probe0.png` and `_probe1.png`.

## Confirmation

- Nothing called `STATUS.md` or `CLAUDE.md` was created, and nothing under `specs/` or `scripts/`.
- Finding: a `ravilo/STATUS.md` already exists in this project from before this round. It was neither created nor touched.

---

## STRINGS — translation only

| key | en | da | fo |
|---|---|---|---|
| `support.ticket_n` | Ticket #{n} | Sag #{n} | Sak #{n} |
| `support.new_ticket` | New ticket | Ny sag | Nýggj sak |
| `support.sys_received_bare` | Received · #{n} | Modtaget · #{n} | Móttikið · #{n} |
| `support.limit` | You've written a lot today. Try again later. | Du har skrevet meget i dag. Prøv igen senere. | Tú hevur skrivað nógv í dag. Royn aftur seinni. |
| `support.too_long` | That's too long to send in one message. | Det er for langt til at sende i én besked. | Hetta er ov langt til at senda í einum boði. |

**Placeholders.**

- `{n}` is present, unchanged, once in each translation of `support.ticket_n` and `support.sys_received_bare`.
- `#` sits directly against it, and the `·` is the same middle dot (U+00B7).
- `{n}` is absent from the other three, and no placeholder was added to them.

**Sources for each translation.**

- *Sag* / *Sak* follow *Mine sager* / *Mínar sakir*.
- The receipt is the fuller line's opening, unchanged.
- *Prøv igen* / *Royn aftur* and *besked* / *boð* follow the settled set.

**Faroese question.** The bare *support* is wrong in running Faroese. The settled set already treats the word as a feminine noun (*til supportina*), so in a name slot it has to take the case the slot demands:

- After *hjá*, which takes the dative: **Opin · hjá supportini**.
- As the subject, in the nominative: **Supportin tekur sær av hesum**.

The two lines need different forms. That means one `{name}` value of *support* cannot serve both, so the desk's case should be two desk-specific strings rather than a value substituted into `{name}`.
