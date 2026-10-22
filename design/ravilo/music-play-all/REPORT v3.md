# REPORT v3 — round music-play-all (2026-10-17)

No phase number. This supersedes v2. The v1 and v2 files are kept as they were.

## What changed and why

The owner asked to merge the two buttons, and never to give the control a row of its own.

**v3 is one split control: ▶ *Play all* | ▾.**

- Tapping *Play all* plays. The ▾ opens a one-item menu: ⤮ *Shuffle all*.
- Its shape is the album page's *Play album ▾*. Its fill is the sort pill's: `rgba(var(--fg),.09)`, a 1 px `--line` border, and a `--line-2` divider.
- It is 38 px in the top bar and 28 px on a text line. The tap area is 44 px either way.

**Where it sits.** It always shares an existing row:

- **Listen:** the top bar, between Ravilo and cast.
- **A search:** the top bar.
- **Artists / Albums / Songs:** the count line, right-aligned.
- **A genre:** the *‹ Genres* line, right-aligned.
- **Desktop wide:** the page head, right of the title.
- **Compact desktop and landscape:** the same as the phone.

**Why not the top bar everywhere:** on Artists, Albums and Songs the sort pill already fills the top-bar slot, and the two together overflow 360 dp. The count line is free.

**Measured live on the canvas:**

- The list it costs on Albums (section 1 note): today 388 px, v2 375 px, v3 375 px.
- Top-bar fit at 360 in da/fo on Listen and a search (state "longest string" note): cast ends at 342 px, the margin edge, in every case.

## Package

1. **Directions doc:** `ravilo/music-play-all/Music Play All - Directions v3 - self-contained.html`. No CDN, no links out.
2. **PNGs:** 61 new files, direction key `split`, named `music-play-all-split-<surface>-<state>.png`.
3. **Source:** `Music Play All - Directions v3.html` + `music-play-all.css` + `music-play-all-kit.js` + `music-play-all-v3.js` + `fonts/`.

## Files

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `ravilo/music-play-all/Music Play All - Directions v3 - self-contained.html` | 184329 | 744 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/Music Play All - Directions v3.html` | 716 | 16 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/music-play-all.css` | 31596 | 342 | `/* music-play-all — Play all / Shuffle all on the music list pages (round slug music-play-all, 2026-10-16).` | `.land .mpa-bkrow{padding-right:40px}.land .mpa-bkrow .mpa-back{margin-left:40px}` |
| `ravilo/music-play-all/music-play-all-kit.js` | 28938 | 221 | `/* music-play-all — frame builders (window.MPK). Stand-ins are the brief's and the music mockup's corpus (app/music-data.js);` | `})();` |
| `ravilo/music-play-all/music-play-all-v3.js` | 17864 | 158 | `/* music-play-all v3 — the split: one control, ▶ Play all \| ▾ (▾ opens Shuffle all), never on a row of its own.` | `})();` |

v3 PNGs (61, largest 125397 bytes, all under 192 KiB):

- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-albums.png` — 58872 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-artists.png` — 64843 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-genre.png` — 61989 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-listen.png` — 62877 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-search.png` — 62105 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-compact-songs.png` — 74358 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-albums.png` — 107839 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-artists.png` — 110301 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-capped.png` — 125397 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-focus-menu.png` — 106683 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-focus-play.png` — 101580 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-genre.png` — 90290 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-listen.png` — 102862 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-loading.png` — 54497 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-search.png` — 92687 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-skin-midnight.png` — 107509 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-skin-noir.png` — 109848 bytes
- `ravilo/music-play-all/png/music-play-all-split-desktop-wide-songs.png` — 113110 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-albums.png` — 59861 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-artists.png` — 67300 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-capped-da.png` — 72495 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-capped-en.png` — 72399 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-capped-fo.png` — 71496 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-casting.png` — 58917 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded-genre.png` — 60422 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded-listen-scrolled.png` — 64841 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded-listen.png` — 64606 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded-scrolled.png` — 59573 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded-search.png` — 62641 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-crowded.png` — 58070 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-empty-genre.png` — 35687 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-empty-search.png` — 27021 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-error.png` — 61873 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-from-genre.png` — 68028 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-from-library-da.png` — 69054 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-from-library-fo.png` — 70889 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-from-library.png` — 69069 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-from-search.png` — 71688 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-genre.png` — 60938 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-land-crowded.png` — 39147 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-land-listen.png` — 43465 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-listen.png` — 64755 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-loading.png` — 37824 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-da-genre.png` — 61302 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-da-listen.png` — 66048 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-da-search.png` — 61715 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-da.png` — 58613 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-fo-genre.png` — 61031 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-fo-listen.png` — 65021 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-fo-search.png` — 60431 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-longest-fo.png` — 58264 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-menu-albums.png` — 59645 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-menu-genre.png` — 60715 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-menu-listen.png` — 65988 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-one-song.png` — 37108 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-search.png` — 60862 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-skin-aurora.png` — 58070 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-skin-midnight.png` — 58857 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-skin-noir-listen.png` — 64494 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-skin-noir.png` — 59373 bytes
- `ravilo/music-play-all/png/music-play-all-split-phone-songs.png` — 75059 bytes

The v1 `today-phone-crowded` frame and the v2 `quiet-phone-crowded` frame are shown again in section 1 for comparison. Their PNGs exist from earlier rounds.

## Headings, character for character

- **Doc title:** `Play all & Shuffle all — v3, one split button` (in HTML the ampersand is `&amp;`).
- **Section headings:**
  - `Today · v2 · v3, and the menu`
  - `The split, carried through`
  - `What v3 replaces`
  - `What the drawing shows that is new`

## Findings

- **The top-bar slot rule is bent.** FR-R267-5c says the slot is *"filled only with the page's own filter"*. v3 puts *Play all ▾* there on Listen and a search, which is a change to an existing rule.
- **The ▾ needs an accessible name.** It should reuse the album page's ▾ label. If that has none, it is a new string, and none is drafted here.
- **Shuffle is two taps now** (▾, then *Shuffle all*).
- **The genre menu covers the *Indie Folk* heading while open.** That only lasts while the menu is open.
- **Carried from v1/v2:**
  - The desktop album page puts its buttons under the title.
  - The desktop has no sort pill and no *Playing from* line.
  - The desktop search group order differs from the requirement.
  - *Continue listening* is not drawn.
  - At 360 the Browse top row already clips cast in Faroese.
  - Faroese *sangum* vs *løg*.
  - *Search "{q}"* has no da/fo.
  - `ravilo/STATUS.md` pre-exists and was not touched.

## Every UI string the drawing shows

- *Play all* / *Afspil alle* / *Spæl øll*: the split.
- *Shuffle all* / *Bland alle* / *Blanda øll*: the ▾ menu.
- *Playing {shown} of {total} songs* / *Afspiller {shown} af {total} sange* / *Spælir {shown} av {total} sangum*.
- *Your library* / *Dit bibliotek* / *Títt savn*.
- *Search "{q}"*: English only.
- **Finding: a sixth string is shown.** The existing *Couldn’t play this* / *Kunne ikke afspille* / *Kundi ikki spæla hetta* (`music.fail_t`), in the error toast.

## States

**Drawn:**

- **1 · each surface.**
  - Phone 360: Listen, Artists, Albums, Songs, Indie Folk, harbour.
  - Desktop wide: the same six.
  - Desktop compact: the same six.
- **The open menu.** Phone count line, top bar, and *‹ Genres* line; desktop via keyboard.
- **2 · loading.** Phone Songs and desktop Albums.
- **3 · empty.** Search *harbor* (the top-bar slot is empty) and a genre with no songs (the *‹ Genres* line as today).
- **4 · one song.** *kvøldsól*, with the menu open.
- **5 · capped toast.** Phone en, da and fo; desktop en.
- **6 · Playing from.** *Your library* (en, da, fo), *Indie Folk*, *Search "harbour"*.
- **7 · while playing.** 360 dp: Albums, Listen, search, Indie Folk, Albums scrolled, Listen scrolled.
- **8 · landscape.** Albums while playing, and Listen.
- **9 · desktop compact and wide.**
- **10 · desktop focus.** *Play all* focused, and ▾ opened by keyboard with *Shuffle all* focused (Noir). Tab order: ① Play all, ② ▾.
- **11 · skins.**
  - Phone: Aurora, Midnight, Noir, and Noir on Listen.
  - Desktop: Midnight and Noir.
- **12 · casting.** No visual difference.
- **Error.** Phone toast.
- **Longest string, da and fo.**
  - Top bar: Listen and harbour, in both languages.
  - Count line: Albums, in both languages.
  - *‹ Genres* line: Indie Folk in Danish, and in Faroese with the menu open.

**Skipped:**

- **Offline:** drawn as error.
- **Desktop empty, one-song, casting and error:** the phone carries these.
- **Desktop *Playing from*:** the desktop has no such line.
- **Desktop da/fo:** skipped; the compact desktop uses the phone layout.
- **10-foot focus:** **not applicable.** There is no TV surface.

## Existing controls, strings and rows

- **Count line on Artists / Albums / Songs:** it now shares its row with the split. The text is unchanged, and the row grows to 28 px.
- ***‹ Genres* line:** it now shares its row with the split. Its height is unchanged (44 px min).
- **Top bar on Listen and a search:** it gains the split in its page slot (see the first finding).
- **Otherwise:** no control, string or row changed.

## Files edited in place

All inside `ravilo/music-play-all/`:

- `music-play-all-kit.js`: added the `S` direction (a `split()` helper, plus S branches in the slot, the count line, the genre back line, Listen and the desktop head) and guarded the old C menu to `dir === 'C'`. The v1 and v2 directions render unchanged.
- `music-play-all.css`: appended the v3 block (`.mpa-sp`, `.mpa-spm`, `.mpa-bkrow`). No existing rule changed.

**Created:** `Music Play All - Directions v3.html`, `music-play-all-v3.js`, `Music Play All - Directions v3 - self-contained.html`, `REPORT v3.md`, and the PNGs above.

**Deleted:** the scratch captures `0[1-4]-_v3probe.png`.

## Confirmation

- Nothing called `STATUS.md` or `CLAUDE.md` was created, and nothing under `specs/` or `scripts/`.

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
