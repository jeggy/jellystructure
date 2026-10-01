# Phase R344 — A song's version in Ravilo: the badges

> Owner, 2026-10-01: *"Yes lets go with direction A. But this is only in jellystructure, we also want these badges in
> ravilo."*

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/ravilo/Ravilo Mobile.html` and `design/ravilo/Ravilo Desktop.html`, both built the same day:
- `mobile/ravilo-versions.js` (`window.RaviloVersions`), on `../app/versions.js`
- `rv-*` in `mobile/ravilo-music.css` and `desktop/ravilo-desktop.css`
- strings `ver.*` in `ravilo-i18n.js`

Not dev-reviewed. The number was checked free on `main` (tree `ef52889`, Ravilo tops at R340; R341–R343 are ours, pending export) on 2026-10-01.

**Depends on** admin 292, which holds the types, each song's set and the household colours. **Builds on** R321/R326 (the listening mode), R322 (the player), R337 (the desktop's layouts) and 279 (`/api/tv/music/**`).

## Requirements

**FR-R344-1 — The server sends each song's version.**
- Every song object in `/api/tv/music/**` gains `versions`: the song's type keys after 292's rules (automatic ∪ the owner's, minus what the owner removed, Session ⇒ Live), in 292's order. An empty array means no version.
- The household's types come once, on the music bootstrap, as `version_types: [{ key, color }]`. When the owner recolours a type in Metadata → Versions, the next fetch carries the new colour.
- Names are **not** sent. They are Ravilo's own strings (FR-R344-4).

**FR-R344-2 — The chips are read-only, and the same as the admin's (direction A).**
- One chip per type sits after the song's title, in the household's colour, with the type's short name.
- The chip is styled as in 292 FR-292-6, at the phone's size: 10.5 px Sora 700, `3px 6px` padding, a 5 px radius.
- It reads in all five themes, because the colour is mixed toward the theme's ink.
- A song with no version shows nothing.
- The viewer cannot change a version. A chip is not a button, and a tap on the row plays the song as today.
- Screen readers hear *Version: Live, Session* after the title.

**FR-R344-3 — Where they show, and how many.**

| Place | Phone (and a desktop window < 600 dp) | Desktop ≥ 600 dp |
|---|---|---|
| Song rows: Listen's *Recently played*, Browse → Songs, Search's songs, an artist's *Top songs* | **two, then +N** | three, then +N |
| An album's track list | two, then +N | three, then +N |
| The queue (sheet / page / side panel) | two, then +N | three, then +N |
| **Now playing / the Playing page** | **all of them**, at the start of the artist · album line | all of them, under the artist · album line |
| The mini bar, the desktop player bar, the lock screen / MPRIS / Now Playing centre | none | none |
| The TV | — (no music mode) | — |

The title keeps its single line and truncates **before** the chips, so the chips are never cut. The +N chip is dashed.

**FR-R344-4 — Strings** × en · da · fo in `i18n/*.json`. da and fo are drafts, and the shipped table wins.

| Key | en | da | fo |
|---|---|---|---|
| `ver.aria` | Version: | Version: | Útgáva: |
| `ver.live` | Live | Live | Live |
| `ver.demo` | Demo | Demo | Demo |
| `ver.remix` | Remix | Remix | Remix |
| `ver.instrumental` | Instrumental | Instrumental | Instrumentalt |
| `ver.cover` | Cover | Cover | Cover |
| `ver.acoustic` | Acoustic | Akustisk | Akustiskt |
| `ver.edit` | Edit | Edit | Edit |
| `ver.alternate` | Alternate | Alternativ | Annað tak |
| `ver.session` | Session | Session | Session |

The chip always uses the short name. The admin's *Alternate version* is *Alternate* here.

## Non-goals (later, own phase)

- **The filter before Shuffle on an artist** (*Without live and remixes*, brief §E). This is the owner's use case, and 292 FR-292-14 is its admin doorway. Neither the phone nor the desktop draws it yet.
- Badges on the TV (no music mode).
- A Version facet in Ravilo's Browse.
- Editing a version from Ravilo.

## Acceptance

1. On the phone, *Recently played* shows **Live at the Harbour**'s *Salt on the Window (live at the harbour, 2011)* with **Live**. *Northern Line (Lighthouse Keepers remix) (extended)* shows **Remix · Edit**.
2. On the album *Tide Tables 1999–2012*, *Northbound (acoustic, alternate take, radio session)* shows **Live · Acoustic +2** on the phone and **Live · Acoustic · Alternate +1** on the desktop. Now playing shows all four.
3. *Salt on the Window* (the studio song) and *Prelude* show no chip anywhere.
4. Changing Live's colour in the admin's Metadata → Versions recolours Live on the phone and the desktop after the next fetch.
5. In Daylight, every chip's text meets 4.5 : 1 against its tint.
6. VoiceOver / TalkBack read *Northern Line … , Version: Remix, Edit*.
7. The mini bar and the lock screen show the title alone.

## Open questions (for the dev review)

1. **`version_types` on the bootstrap, or on every list:** the lean is the bootstrap, because colours rarely change and a stale colour until the next launch is fine.
2. **Desktop fold at three vs. the phone's two:** the lean is three, matching the admin, on rows ≥ 600 dp wide.
3. **Search by version:** should *live* in Search match a song's Live type as well as its title? The lean is no in round 1; the title already says it on most of them.

## Dev notes

- `RaviloVersions.chips(song, fold)` / `.title(song, esc)` in `design/ravilo/mobile/ravilo-versions.js` is the whole rule. The desktop mockup calls it with `fold = 3`, or `9` on Playing.
- The stand-in *Live at the Harbour* heads *Recently added* in the phone mockup, so the chips are on screen at once.
