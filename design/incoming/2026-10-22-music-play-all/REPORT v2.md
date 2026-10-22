# REPORT v2 — round music-play-all (2026-10-17)

No phase number. Supersedes the v1 recommendation. v1 files are kept as they were (REPORT.md, the v1 canvas and its PNGs).

## What changed and why

The owner said v1 was too much to decide and the buttons were too big. v2 is **one direction with no open decisions**:

- **The quiet pair:** ▶ *Play all* · ⤮ *Shuffle all*, two 28 px pills in the sort pill's own style (fill `rgba(var(--fg),.09)`, 1 px `--line` border, radius 14, Sora 600 12.5 px, 13 px glyphs). No gradient on either pill.
- **Place:** right-aligned on the existing count line (*30 albums* ··· pills). On pages without a count line, they sit alone on a 28 px row: at the top of Listen, under the genre heading, and under the search field. They scroll with the list, and the rule is the same in portrait, landscape and compact desktop.
- **Wide desktop:** the same pills, right of the page title (v1's D1 placement at the smaller size).
- **Tap area:** 44 px. The pill is drawn at 28 px and its hit box extends 8 px above and below.
- **The list it costs on the crowded case** is measured live on the canvas (section 1 note) against today and v1, not typed.

## Package

1. **Directions doc (self-contained):** `ravilo/music-play-all/Music Play All - Directions v2 - self-contained.html`. No CDN, no links out.
2. **PNGs:** 51 new files in `png/`, direction key `quiet`, named `music-play-all-quiet-<surface>-<state>.png`.
3. **Source:** `Music Play All - Directions v2.html` + `music-play-all.css` + `music-play-all-kit.js` + `music-play-all-v2.js` + `fonts/`.

## Files

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `ravilo/music-play-all/Music Play All - Directions v2 - self-contained.html` | 178906 | 701 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/Music Play All - Directions v2.html` | 728 | 16 | `<!doctype html>` | `</html>` |
| `ravilo/music-play-all/music-play-all.css` | 29649 | 321 | `/* music-play-all — Play all / Shuffle all on the music list pages (round slug music-play-all, 2026-10-16).` | `.mpa-dp.q .mpa-bt .ic{width:13px;height:13px}` |
| `ravilo/music-play-all/music-play-all-kit.js` | 27582 | 215 | `/* music-play-all — frame builders (window.MPK). Stand-ins are the brief's and the music mockup's corpus (app/music-data.js);` | `})();` |
| `ravilo/music-play-all/music-play-all-v2.js` | 15733 | 143 | `/* music-play-all v2 — one recommendation, nothing left to pick. The quiet pair (dir Q): 28 px pills in the sort pill's style, on the count  … (cut here, line continues)` | `})();` |

v2 PNGs (51, largest 127544 bytes, all under 192 KiB):

- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-albums.png` — 60375 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-artists.png` — 66254 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-genre.png` — 62692 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-listen.png` — 60888 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-search.png` — 58446 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-compact-songs.png` — 76177 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-albums.png` — 110218 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-artists.png` — 111179 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-capped.png` — 127544 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-focus-play.png` — 103797 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-focus-shuffle.png` — 104322 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-genre.png` — 89926 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-listen.png` — 104322 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-loading.png` — 56192 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-search.png` — 94134 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-skin-midnight.png` — 107351 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-skin-noir.png` — 110107 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-desktop-wide-songs.png` — 115176 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-albums.png` — 61264 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-artists.png` — 68233 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-capped-da.png` — 73216 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-capped-en.png` — 73290 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-capped-fo.png` — 72113 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-casting.png` — 60372 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-crowded-listen.png` — 62448 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-crowded-scrolled.png` — 59573 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-crowded-search.png` — 60357 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-crowded.png` — 59552 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-empty-genre.png` — 35687 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-empty-search.png` — 27021 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-error.png` — 63292 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-from-genre.png` — 68028 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-from-library-da.png` — 69054 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-from-library-fo.png` — 70889 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-from-library.png` — 69069 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-from-search.png` — 71688 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-genre.png` — 59471 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-land-crowded.png` — 40618 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-land-listen.png` — 36189 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-listen.png` — 63261 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-loading.png` — 39493 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-longest-da-genre.png` — 58805 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-longest-da.png` — 59238 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-longest-fo-search.png` — 59715 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-longest-fo.png` — 59193 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-one-song.png` — 32985 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-search.png` — 61304 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-skin-aurora.png` — 59552 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-skin-midnight.png` — 60365 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-skin-noir.png` — 61012 bytes
- `ravilo/music-play-all/png/music-play-all-quiet-phone-songs.png` — 75949 bytes

Reused from v1 unchanged (frames identical): `png/music-play-all-today-phone-crowded.png`, `png/music-play-all-a-phone-crowded.png` (shown as "v1 · the big pair").

## Direction heading, character for character

- `Play all & Shuffle all — v2, the quiet pair` (doc title; in HTML the ampersand is `&amp;`)
- Section headings:
  - `Today · v1 · v2 side by side`
  - `The quiet pair, carried through`
  - `Every v1 option, closed with a default`
  - `What the drawing shows that is new`

## Open decisions

**None.** The one question is "is the quiet pair right?" Every v1 option is closed in the doc's section 3:

- **Directions:** B and C dropped. A replaced by the quiet pair.
- **Placement:** Listen lower dropped; desktop under-the-title dropped; the landscape field-row rule dropped.
- **Error toast:** reuses `music.fail_t`.

**One flag:** the brief asks the pair to read as a sibling of the album page's Play / Shuffle. The quiet pair is the sort pill's style instead. That is an existing style, not a third one, and the album page keeps its gradient Play.

## Every UI string the drawing shows

These are the same as v1:

- *Play all* / *Afspil alle* / *Spæl øll*
- *Shuffle all* / *Bland alle* / *Blanda øll*
- *Playing {shown} of {total} songs* / *Afspiller {shown} af {total} sange* / *Spælir {shown} av {total} sangum*
- *Your library* / *Dit bibliotek* / *Títt savn*
- *Search "{q}"*: English only, because the brief gives no da/fo (finding).

**Finding: a sixth string is shown.** It is the existing *Couldn’t play this* / *Kunne ikke afspille* / *Kundi ikki spæla hetta* (`music.fail_t`) in the error toast. No new string.

## States

**Drawn:**

- **1 · each surface.**
  - Phone 360: Listen, Artists, Albums, Songs, Indie Folk, harbour.
  - Desktop wide: the same six.
  - Desktop compact: the same six.
- **2 · loading.** Phone Songs and desktop Albums.
- **3 · empty.** Search *harbor* and a genre with no songs. The pills are absent.
- **4 · one song.** *kvøldsól*. Both pills stay.
- **5 · capped toast.** Phone en, da and fo; desktop en.
- **6 · Playing from.** *Your library* (en, da, fo), *Indie Folk*, *Search "harbour"*.
- **7 · while music is playing.** 360 dp: Albums, Listen, search, and Albums scrolled.
- **8 · 360 dp and landscape.** Landscape: Albums while playing, and Listen.
- **9 · desktop compact and wide.**
- **10 · desktop keyboard focus.** Play all (Aurora) and Shuffle all (Noir), with the tab order.
- **11 · three skins.** Phone Aurora, Midnight and Noir; desktop Midnight and Noir (Aurora in all the other desktop frames).
- **12 · casting.** Phone. No visual difference.
- **Error.** Phone toast; the pills don't change.
- **Longest string, da and fo.** Phone 360: Albums in both, da Indie Folk, fo harbour. Faroese uses 30 *fløgur* · *Spæl øll* · *Blanda øll* and fits.

**Skipped:**

- **Offline:** drawn as error.
- **Desktop empty, one-song, casting and error:** skipped. The phone carries them, and the behaviour is identical.
- **Desktop Playing from:** the desktop has no such line.
- **Desktop da/fo:** skipped; the compact desktop uses the phone layout.
- **10-foot focus:** **not applicable.** There is no TV surface.

## Existing controls, strings and rows

**One change:** on Artists, Albums and Songs, the existing count line now shares its row with the pills. The count text, size and position (left) are unchanged, but the row grows from text height to 28 px.

No other control, string or row changed. The v1 findings stand: the desktop album page puts its buttons under the title; desktop has no sort pill; desktop search group order; no desktop *Playing from*; *Continue listening* not drawn; the Faroese top row already clips cast at 360; *sangum* vs *løg*.

## Files edited in place

All inside `ravilo/music-play-all/`:

- `music-play-all-kit.js`: added the `Q` direction (a `qrow` helper, plus Q branches in Listen, search, genre and the browse count line, and a `q` class on the desktop pair). The v1 directions render unchanged.
- `music-play-all.css`: appended the v2 block (`.mpa-qrow`, `.mpa-qb`, `.mpa-dp.q`). No existing rule was changed.

**Created:** `Music Play All - Directions v2.html`, `music-play-all-v2.js`, `Music Play All - Directions v2 - self-contained.html`, `REPORT v2.md`, and the 51 PNGs.

**Deleted:** the scratch files `_v2probe.png` and `_v2files.md`.

Nothing outside the folder was touched. v1's self-contained doc is frozen, because it inlines its own copy of the kit and CSS.

## Confirmation

- Nothing called `STATUS.md` or `CLAUDE.md` was created, and nothing under `specs/` or `scripts/`.
- The pre-existing `ravilo/STATUS.md` was not touched (finding, as in v1).

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
