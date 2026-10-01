# Phase R344 — A song's version in Ravilo: the badges

> Owner, 2026-10-01: *"Yes lets go with direction A. But this is only in jellystructure, we also want these badges in
> ravilo."*

## Status

`Planned` · **Dev-reviewed 2026-10-01** against `main` `44e26871` (see *Dev review* below). Written 2026-10-01 (design-authored) from `design/ravilo/Ravilo Mobile.html` and `design/ravilo/Ravilo Desktop.html`, both built the same day:
- `mobile/ravilo-versions.js` (`window.RaviloVersions`), on `../app/versions.js`
- `rv-*` in `mobile/ravilo-music.css` and `desktop/ravilo-desktop.css`
- strings `ver.*` in `ravilo-i18n.js`

Dev-reviewed 2026-10-01. The number was checked free on `main` (tree `ef52889`, Ravilo tops at R340; R341–R343 are ours, pending export) on 2026-10-01.

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

## Dev review (2026-10-01, against `main` `44e26871`)

Read against `shared`'s music DTOs (`shared/tv/Music.kt`, `RaviloWireJson`, `WireCompatTest`), `MusicTvService` and
`MusicTvRoutes`, `ravilo-ui`'s music screens (`MusicCommon.kt`, `MusicPlayerScreens.kt`, `MusicDetailScreens.kt`,
`MusicBrowseScreen.kt`, `MusicListenScreen.kt`, `RaviloApp.kt`), the shipped string table (`i18n/*.json`) and the
mockups (`design/ravilo/mobile/ravilo-versions.js`, the `rv-*` rules). **The direction holds, and the client half is
small**: every song the server sends passes through one function, and every song list draws one composable. Twelve
items. One depends on an owner question in 292 (item 3); the rest are the build's. Build after 292's step (b) (its
`MusicVersions.of`).

1. **One server function fills every song.** Every `MusicTrackItem` in `/api/tv/music/**` is made by
   `MusicTvService.trackItem` (`MusicTvService.kt:127–139`): Listen, albums, artists, playlists, search, Songs,
   last-played. `versions` is added there, from 292's `MusicVersions.of`, so no route needs its own code. The field is
   `@SerialName("versions") val versions: List<String> = emptyList()` on `MusicTrackItem` (`shared/tv/Music.kt:42–59`).
   - **Plain strings, never an enum.** `RaviloWireJson` ignores unknown keys (`RaviloWireJson.kt:23`), so an installed
     app skips the field. A new app on an old server gets an empty list and draws nothing. A new optional field is the
     case `WireCompatTest` allows (`aNewOptionalFieldIsFine`).
   - The file's header says *no existing DTO gains a field (R319)* (`shared/tv/Music.kt:7–8`). That was 279's launch
     rule; R319's check allows an optional field. Reword the comment when the field lands.

2. **There is no music bootstrap. Use `MusicHome` (answers open question 1).** The app learns that music exists by
   fetching `/api/tv/music/home` at launch (`RaviloApp.kt:946–947`), and the Listen tab fetches it again. So
   `version_types: [{ key, color }]` goes on `MusicHome` (`shared/tv/Music.kt:73`), as `List<MusicVersionType>` with
   two strings. A new colour reaches the phone on the next launch or the next Listen.
   - **The nine default colours are compiled into the app too.** A chip can be drawn before `MusicHome` has answered
     (a restored queue, Playing on launch), and an old server sends no colours.
   - **An unknown key draws nothing.** The name is Ravilo's own string, and there is none for a key a newer server
     invents. An unreadable colour falls back to the default.
   - `home()` answers an empty `MusicHome` when the viewer has no albums (`MusicTvService.kt:144`). Then there are no
     songs, so no chips.

3. **FR-R344-1's order re-adds a Live the owner removed.** *Automatic ∪ the owner's, minus what the owner removed,
   Session ⇒ Live* puts Live back on a Session whose Live the owner unticked. Ravilo must not have its own rule: it
   sends what 292's function answers. Which answer that is depends on 292's item 7 (**needs the owner**; lean: the
   mockup's invariant, Session always carries Live, which makes the question disappear).

4. **Where the chips go.** One composable, `VersionChips(keys, fold)`, in `MusicCommon.kt`.
   - **`TrackRow`** (`MusicCommon.kt:213–256`) already draws every song list: Listen's *Recently played*
     (`MusicListenScreen.kt:118`), an album (`MusicDetailScreens.kt:169`), an artist's *Top songs* (`:290`), a
     playlist (`:382`), Search (`MusicBrowseScreen.kt:334`), Songs (`:414`) and the phone queue
     (`MusicPlayerScreens.kt:582`, `:624`). Put the title `Text` and the chips in a `Row`, the title with
     `Modifier.weight(1f, fill = false)`, so the title truncates before the chips (`:240–244` today).
   - **Playlists are not in the table, but they get chips** through the same `TrackRow`. Add the row to FR-R344-3.
   - **The desktop's queue side panel is not a `TrackRow`.** `QueuePanelRow` (`MusicPlayerScreens.kt:655`) needs the
     chips by hand.
   - **Now playing on the phone:** `Credits` (`MusicPlayerScreens.kt:382–390`). The chips go first in the artist ·
     album `Row` (`:387`), all of them.
   - **The desktop's Playing page:** `DeskPlaying` (`MusicPlayerScreens.kt:197`). All of them, on their own line under
     the artist line (`:230`).
   - **Nothing** on `MusicMiniBar` (`:673`), `DesktopMusicBar`, the media session, the lock screen or MPRIS.

5. **The fold keys on the layout family (answers open question 2).** `isDesktopLayout` (`Dimens.kt:57`) is true only
   for the desktop family, which R337 gives to a desktop window 600 dp and wider; a narrower window is the phone family.
   `TrackRow` already reads it (`MusicCommon.kt:225`). So: three then *+N* when it is true, two then *+N* otherwise.
   That is the table's split exactly. Agree with the lean.

6. **The colour formula: Compose's `lerp` is already in Oklab.** `androidx.compose.ui.graphics.lerp(c, colors.text,
   fraction)` interpolates in Oklab, close to the mockup's `color-mix(in oklch, …)`. The tint is `c.copy(alpha =
   0.15f)` and the border `c.copy(alpha = 0.42f)`. **But 58 % fails acceptance 5 in Daylight:** Cover reads 4.28 : 1 on
   the page and 3.99 : 1 on a card (292's item 16). Use 58 % on the dark themes and 45 % on Daylight (worst case
   5.19 : 1). Read the theme's light/dark from `RaviloTheme`, not from the system, since the viewer picks the theme.

7. **Screen readers would read the chips one by one.** `TrackRow` is clickable through `tap`
   (`MusicCommon.kt:258`), which merges its children's text, so TalkBack would say *…, Remix, Edit* without the word
   *Version*. Give the chip group `Modifier.clearAndSetSemantics { contentDescription = … }`. Make the string a
   template so Danish and Faroese can order it: `ver.aria` = *Version: {list}*, the list joined with *, *.

8. **Strings.** Add `ver.aria` and the nine `ver.<key>` to `i18n/en.json`, `da.json` and `fo.json` (the table already
   uses dotted keys such as `music.play`; `ver.` is free). Run `scripts/check-ravilo-strings.sh` and
   `scripts/check-i18n-spelling.sh`: the Faroese drafts (*Útgáva*, *Instrumentalt*, *Akustiskt*, *Annað tak*) go
   through R288's lexicon. The shipped table wins. The chip's 10.5 sp is the size the app's other badges already use
   (sizes from 10 to 11 sp appear 57 times in `ravilo-ui`'s commonMain), so it needs no new exception.

9. **The speaker's display (286) is a TV too.** When music plays on a speaker group with a screen, the Cast receiver
   draws Now playing. The table says only *the TV — no music mode*. Lean: no chips there in round 1, the same as the
   mini bar. Add the row to FR-R344-3.

10. **A queue restored after a restart (R322) shows no chips until it is fetched again.** Its songs were saved before
    they had `versions`. That is acceptable; say it in the spec so nobody files it.

11. **Open question 3 (search by version): no, agree.** The server's search matches titles and names
    (`MusicTvService.search`). Matching a type would be a server rule with its own strings, and the Version facet in
    Ravilo is already a non-goal.

12. **Order and tests.** One change, after 292's step (b): the two DTO fields, `trackItem` and `home()`, the default
    colours, `VersionChips`, `TrackRow`, `QueuePanelRow`, `Credits`, `DeskPlaying`, the strings. Tests: in
    `MusicTvServiceTest`, a matched song with a choice and a song with none (`versions` filled, then empty), and
    `version_types` on `home()`; a `ravilo-ui` common test for the fold (two, three, *+N*) and for an unknown key. No
    baseline change in `WireCompatTest` is needed.
