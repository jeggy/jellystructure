# Design brief — Music in jellystructure: a fourth kind of thing the admin manages

**Date:** 2026-09-27 · **For:** the design project (Cosmos) that owns `design/app/` · **Status:** brief,
awaiting round-1 directions on the questions in §H; everything else is decided enough to draw into the main
mockups. Source: `specs/research-reports/music-library-and-player-2026-09-27.md` (§1–§4, §7). The phone half
is a separate brief: `specs/ravilo/design-brief-music-player-on-the-phone-2026-09-27.md`.

> Owner's picture, 2026-09-27: *"support for music … a whole new media type … coming from our music library
> in Jellyfin … in jellystructure we should be able to maintain information about this music, just like how we
> do with movies and series via TMDB. But for music we need another provider. Probably MusicBrainz."*

**What the research settled, so the brief can lean on it:** the provider is **MusicBrainz** (artist →
release-group = *the album* → release → recording) with covers from the **Cover Art Archive**, artist images
from **fanart.tv** or Wikimedia Commons, lyrics from **LRCLIB**; Jellyfin already reads `album.nfo` and
`artist.nfo`; the household library is small and hard (60 tracks, 38 of them **WMA** that will always
re-encode on a phone, **no MusicBrainz ids anywhere**, no covers, no disc numbers, partial albums) — so the
admin surfaces are about **matching, confirming and repairing**, not about browsing a finished catalogue.
Music is **not** a `MediaKind`; it is its own trio of artist · album · track, so nothing about movies or series
changes on any page.

## 0. What exists, so nothing is drawn twice

| Have | Where | Reuse |
|---|---|---|
| Library page: kind tabs *Movies · Series · Music videos · All*, multi-axis filters, infinite scroll, the filter workbench | `app/library.html`, `ravilo-builders.js/.css` | A fourth kind, **Music**, with its own views (§A) |
| The detail idiom: pagebar (art, title, ids, external links, drift banner, lock banner, split Save button), URL-addressable tabs, Artwork manager + lightbox, NFO raw viewer, History | `app/media.html`, `series.html`, `detail.css` | The **Album** and **Artist** pages are this idiom (§B, §C) |
| TMDB-id edit + *Clear TMDB match* + the greyed *Re-pull from TMDB* when locked (174); *Find/fix match…* | `media.html` pagebar | The MusicBrainz match chip is the same control with a different provider (§B1) |
| Artwork tab: candidates, *Currently in use*, per-asset *Clear*, locks, real aspect (192) | `media.html` → Artwork | Covers are **1:1**; artist images are 1:1 / 16:9 / logo (§B3, §C2) |
| Metadata page: Studios · Networks · Genres · Tags · Age ratings, logo walls with counts | `app/metadata.html`, `metadata.css` | A **Music genres** view (§D) |
| Settings → Libraries: mapping cards, the Jellyfin settings advisor (212) with findings pinned above | `app/settings.html` | The music library's card + one advisor finding (§E1) |
| Settings → Connections / Download tools cards (Chromecast `cc-*`, Bazarr, Seerr) | `settings.html` | The **Music providers** card (§E2) |
| Activity: Steps card (214), Jobs & workers with lanes (213), **Outbound pacing** card (183), run summary line | `app/activity.html` | New steps, two pacing rows, a summary line (§F) |
| Dashboard attention list, counters, Stop scan | `app/index.html` | Music counters + attention entries (§F) |
| Triage dock + per-title banners | `app-shell.js`, `media.html` | Five music triage types (§G) |
| Tokens `--ok` / `--warn` / `--bad`, Sora · Space Grotesk · JetBrains Mono, Light/Dark/System | `app/wf.css`, `app.css` | Unchanged |

Rules carried over: the admin **shows the arithmetic and the source** (212/215: every finding says where it
came from); a missing cover on the admin side **is** a work item (unlike the viewer side); no class name that
starts with `adv-` (ad blockers hide it — 212's `jfa-*` lesson); write-through editing, no staged saves.

---

## A. Library page — a fourth kind: **Music**

### A1. The kind picker
*Movies · Series · Music videos · Music · All*. Keep *Music videos* as it is; the new tab is **Music**.
(Ravilo's phone Library dropdown currently says *Music* for music videos — that rename is the phone brief's
§0; the admin already says *Music videos*.)

### A2. Three views inside Music, one segmented control under the pagebar
- **Albums** (default) — a **square-art** grid (the poster grid is 2:3; albums need 1:1 cells, same gutter).
  Cell: cover (or the wordmark-on-gradient fallback the Ravilo walls use — but here with a small
  `--warn` dot, because on the admin side *no cover* is a task), title, album artist, year · *N songs*, and
  the **match state** as a corner chip: *matched* (nothing drawn), *unmatched* (`--warn`), *locked*
  (padlock), *needs you* (a candidate list is waiting — `--warn`, count in the pagebar).
- **Artists** — a grid of circles (artist image or initials on the gradient), name, *N albums · N songs*,
  the same match chip.
- **Songs** — a dense table: # · title · artist · album · length · **codec · kbps** (JetBrains Mono) · lyrics
  glyph (present / absent) · match state. This is where *WMA · re-encodes on a phone* is visible per row.

### A3. Filters (the workbench's idiom, music facets)
Match state (matched · unmatched · locked · needs you) · **Cover** (has / missing) · **Artist image**
(has / missing) · **Format** (MP3 · WMA · FLAC · AAC · other — with the note *plays on a phone only by
re-encoding* on WMA) · Genre (music genres, not film genres) · Decade · Album type (album · single/EP ·
compilation · live · soundtrack — MusicBrainz's primary/secondary types) · Lyrics (has / missing) ·
Library. Counts on every facet value as today.

### A4. Bulk actions on a selection
*Match now* (runs the ladder on the selected albums), *Fetch covers*, *Write NFOs*, *Lock match*,
*Clear match*. Same selection bar as the segment editor's season sheet.

### A5. States to draw
Empty library (*Nothing filed as music yet* — a sentence and the library card link); library mapped but not
yet scanned; scanned and **everything unmatched** (the first real state of this household's library — the
whole grid is `--warn` chips and the pagebar says *30 albums · 0 matched · Match now*); partly matched;
a search with no results.

## B. The Album page — `app/album.html`

### B1. Pagebar
Cover (1:1, 160 px, lightbox), title, **album artist** (a link to the Artist page; several credits render
as *A & B* with each a link), year · *N songs · 43 min* · album type badge (*Album*, *Live*, *Compilation*…),
**codec summary** (*MP3 320* or *WMA 128 · re-encodes on a phone* in `--warn` ink), and the **match chip**:

- **Matched:** *MusicBrainz · release-group ▸* (opens the entity on musicbrainz.org), a second line *release:
  {country} {year} · {label}* when a specific release was chosen; *Clear match* in the ⋯ menu; *Lock* toggle.
- **Unmatched:** the chip reads *No MusicBrainz match* in `--warn`; the primary action is **Find match…**
  (§B2).
- **Locked:** padlock + *Locked · won't be re-matched*; *Re-pull from MusicBrainz* greyed with the
  174-style tooltip.
- External links: MusicBrainz · Cover Art Archive · (when known from URL relationships) Discogs · Wikipedia.
- Drift banner and Jellyfin lock banner exactly as `media.html`.
- Split button: **Save → NFO / Sync Jellyfin / Save & Sync** — unchanged idiom, writes `album.nfo`.

### B2. Find match… (the panel this page exists for)
A side panel (like *Find/fix match…* today) with:
- The **query line** pre-filled from the folder (artist · album · year), editable; *Search* runs against
  MusicBrainz at 1 request/second — show a quiet *searching…* and never a spinner without a sentence.
- **Candidates**, each a row: cover thumb (CAA, 250), title · artist, type badge, first release year,
  **agreement**: *12 of 12 tracks agree on position and length* / *2 of 2 (partial album)* / *titles agree,
  lengths off by up to 9 s* — the arithmetic the admin needs; MusicBrainz's own score as a small number.
- The selected candidate expands to its **releases** (country · date · label · format · track count) with
  the best-agreeing release preselected.
- **Fingerprint** row at the bottom: *Identify by sound (AcoustID)* — runs `fpcalc` on the tracks and
  shows what came back (*8 of 12 tracks identified → 1 release-group*); absent, not greyed, when no
  AcoustID key is configured (the row says *Add an AcoustID key in Settings to identify by sound* instead).
- Actions: **Use this match** (+ *and lock*), *Cancel*. On use: the pagebar chip flips, a toast says what
  will now happen (*Cover, genres and NFO will be fetched on the next run — or Run now*).

### B3. Tabs
- **Tracks** — the album's tracks in disc/position order: # · title · length · **codec · kbps · kHz** ·
  recording match (✓ / *unmatched* / *from a different release*) · lyrics (synced ✓ / plain ✓ / none ·
  *Fetch*) · loudness gain (*−9.7 dB*, mono type, with the album gain in the header). Inline **Play** per
  track (the admin's own Jellyfin session, direct play or nothing — the segment editor's rule). Missing
  positions in a partial album are drawn as empty numbered rows (*not in library*) so the gap is visible.
- **Artwork** — *Currently in use* (cover.jpg on disk, with the real aspect and a transparency backing),
  **Candidates** from the Cover Art Archive (front · back · booklet, 500 px thumbs, *approved* marker), from
  fanart.tv (albumcover · cdart), upload, *Clear*, and the per-asset lock (133/151 idiom).
- **Genres & tags** — MusicBrainz genre votes as chips with counts (*grunge 67 · alternative rock 30*),
  the ones written to NFO ticked (threshold rule stated in a hint), plus the JS-tag chips as today.
- **NFO** — raw `album.nfo` viewer.
- **History** — as today.

### B4. States
Unmatched (Find match… prominent; every tab shows what a match would fill); matched, unlocked; locked;
*plays only by re-encoding* note; partial album; cover missing after a match (CAA has nothing — the Artwork
tab says *No cover on the Cover Art Archive for this release-group · upload one*); a match with a **release
that disagrees** (tracks tab marks the disagreeing rows).

## C. The Artist page — `app/artist.html`

### C1. Pagebar
Artist image (circle, 160 px), name, **sort name** (small, mono), *Group · US · 1987–1994* (type · country
· life-span), disambiguation in `--ink-dim`, the match chip (same three states), external links
(MusicBrainz · Wikipedia · Wikidata · official site · Discogs — whichever URL relationships exist), split
button (*Save → NFO* writes `artist.nfo`).

### C2. Body
- **Biography** — from Wikipedia/Wikidata via the URL relationships when available (say the source), else
  the empty state *No biography found* with an inline editor (write-through into `artist.nfo`).
- **Albums** grouped by MusicBrainz type: *Albums · Singles & EPs · Compilations · Live* — square cells as
  §A2; a **greyed row of albums MusicBrainz knows but the library lacks** is a round-1 question (§H4).
- **Artwork** tab: thumb (1:1) · background (16:9) · logo (transparent, on a checker or a dark plate as 232
  decides) — candidates from fanart.tv and Commons with the licence line for Commons files; *Currently in use*.
- **Genres**, **NFO**, **History** as §B3.

## D. Metadata page — music genres

A **Music genres** view beside *Genres* (not merged: a film's *Comedy* and an album's *comedy rock* are
different id spaces — MusicBrainz's genres have their own ids and English names). A wall of genre chips
with counts (albums · songs), the same click-through to the Library filtered. No logos (MusicBrainz genres
have none). Question §H3: one tab with a *Films & series / Music* segment, or two tabs.

## E. Settings

### E1. Libraries — the music library's mapping card
Same card as a film library (Jellyfin name · path mapping · skip · fallback language is **absent** — there is
no language cascade for music; in its place a line *Metadata: MusicBrainz · Covers: Cover Art Archive ·
Artist images: fanart.tv*). Its advisor findings (212's idiom, pinned inside the card):
- **`--bad` — *Jellyfin writes its own NFO files for this library*** (`MetadataSavers: Nfo`): *Jellyfin
  will overwrite the album.nfo and artist.nfo jellystructure writes.* Fix path: *Dashboard → Libraries →
  Musik → Manage library → Metadata savers → untick Nfo*. This is the one finding that exists **today**.
- Silent (✓, not drawn) when online fetchers are off, the NFO reader is on, the LUFS scan is on.

### E2. A **Music providers** card (Connections or Download tools — §H5)
Four rows, each with a status dot and a *Test*:
- **MusicBrainz** — no key; one field **Contact** (an e-mail or URL — *MusicBrainz requires one in the
  request header*), the rate shown as a fact (*1 request per second — MusicBrainz's rule*), last call's
  outcome.
- **Cover Art Archive** — nothing to configure; status only.
- **AcoustID** — *Client key* field, link to *acoustid.org/new-application*, the sentence *Identifies a
  track by its sound when the tags are not enough*; absent key ⇒ the Find-match panel's fingerprint row says
  so (§B2).
- **fanart.tv** — *API key* field (project key), *Artist pictures and logos*; absent key ⇒ artist images come
  from Wikimedia Commons only and the row says so.
- **Lyrics (LRCLIB)** — a switch *Fetch lyrics* (off by default until the owner answers §H6), no key.
Google's card names Google; these name their providers — the admin pays or registers with them.

### E3. Users & devices
One sentence in the per-user row when the user's Jellyfin policy grants the music library: *Music: yes* /
*Music: no library access* — so the admin can see why a phone shows no music mode.

## F. Activity and Dashboard

- **Steps card:** the run gains `scan_music · match_musicbrainz · fetch_music_artwork · write_music_nfo
  (· fetch_lyrics)` in order; *Stop this step* as 214.
- **Outbound pacing card:** two new rows — *MusicBrainz · 1.0/s ceiling (fixed by MusicBrainz) · 0 refused
  last minute* and *AcoustID · 3/s*; the TMDB row unchanged.
- **Run summary line:** *30 albums · 27 matched · 3 need you · 26 covers fetched · 4 without a cover* (plus
  183's *N fields not fetched: MusicBrainz rate limit* shape when it happens).
- **Jobs & workers:** music matching runs in the existing lanes; nothing new to draw unless 213's lane
  list gains a `music` lane (dev decision — draw the card with and without).
- **Dashboard:** a **Music** counter tile (*albums · matched · covers*) beside the film/series tiles; attention
  entries *3 albums need a match*, *4 albums have no cover*, *2 artists have no picture*, and — only if the
  owner picks the conversion in §H1 — *38 songs play on a phone only by re-encoding · Convert…*.

## G. Triage types (the dock and the per-page banner)

| Type | Where it shows | Action |
|---|---|---|
| Album unmatched (after the ladder ran) | dock · Album pagebar | *Find match…* |
| Album match needs you (candidates ≥ 2 with no clear winner) | dock · Album pagebar | *Choose…* (opens §B2 with the candidates) |
| Cover missing (matched, CAA has nothing) | dock · Album → Artwork | *Upload* |
| Artist image missing | dock · Artist → Artwork | *Choose…* / *Upload* |
| Track without a recording match (album matched, one track's position/length disagrees) | Album → Tracks row | *Match this track…* (recording search) |
| Plays only by re-encoding (WMA and friends) | Songs view · Album pagebar note · Dashboard | information, or *Convert…* if §H1 says so |

## H. Round-1 questions (directions wanted, owner picks)

1. **WMA:** draw the *Convert…* action (a one-time jellystructure repair job to Opus/AAC, with the honest
   *already lossy — a little more is lost* sentence and a confirmation), or leave the note as information?
   Lean: draw it as an action but **off by default** in the brief's states — the owner decides.
2. **The candidate row's arithmetic:** *12 of 12 tracks agree* as a sentence (lean) vs a per-track mini
   table inside the candidate?
3. **Music genres:** own Metadata tab vs a segment inside Genres? Lean: own tab.
4. **Artist page — albums the library lacks:** show MusicBrainz's discography greyed (a shopping list, like
   Discover's Request) or only what is on disk (lean: on disk only; a greyed discography is a second feature).
5. **Where the Music providers card lives:** Connections (beside Jellyfin/TMDB, lean — they are metadata
   sources) vs Download tools (beside Bazarr).
6. **Lyrics on by default?** Lean yes for the fetch, since Jellyfin already saves lyrics with media.
7. **Tag writing:** a per-album *Write MusicBrainz ids into the files* action behind a confirmation, or not
   drawn at all (lean: not drawn — nothing touches a media file without being asked, and NFO + sidecars are
   enough for Jellyfin and Kodi).

## I. Deliverables and order

1. `app/library.html` — the Music kind, its three views, the filters, the five states (§A).
2. `app/album.html` (+ `detail.css` additions) — pagebar, Find match… panel, five tabs, the states (§B).
3. `app/artist.html` — pagebar, body, artwork (§C).
4. `app/settings.html` — the library card with its advisor finding; the Music providers card (§E).
5. `app/activity.html` + `app/index.html` — steps, pacing rows, summary line, counters, attention entries (§F).
6. `app/metadata.html` — Music genres (§D).
7. The triage entries in the dock (§G) — drawn once on the Dashboard mock.
Then the specs (274–278 prospectively; verify the numbers against `main` first) are written from the mockups.
