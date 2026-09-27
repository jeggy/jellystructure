# Ravilo: design mockups vs the shipped app

**Date:** 2026-09-27
**Type:** audit (read-only; nothing was fixed)
**Baseline:** design mirror last synced at `e7991df3` (2026-09-25). Code at `376dda70` (`main`, 2026-09-27).

## Scope and method

Compared every Ravilo surface that has a *built* mockup against the code that ships it:

| Design (what it should look like) | Implementation (what ships) |
|---|---|
| `design/ravilo/Ravilo TV.html` + `ravilo-app.js`, `ravilo.css`, `ravilo-browse.js`, `ravilo-focus.js`, `ravilo-player.js/.css`, `ravilo-livetv.js`, `ravilo-data.js` | `ravilo-ui` (Compose, commonMain: TV, phone and web share it) |
| `design/ravilo/Ravilo Mobile.html` + `mobile/*.css` | `ravilo-ui` on a handset (`LocalHandset`) |
| `design/ravilo/Ravilo Receiver.html` (Chromecast) | `ravilo-cast/…/Receiver.kt` + `cast-receiver/index.html` |
| `design/ravilo/Ravilo Receiver App.html` (receiver-only TV app) | `ravilo-screen/…/Screen.kt` + `ravilo-screen/wgt/index.html` (Tizen, **paused**) |
| `design/ravilo/ravilo-i18n.js` (300 keys × en/da/fo) | `i18n/{en,da,fo}.json` (440 keys) |

The *Directions* canvases (Casting, Mobile Player, Play on a TV, Bottom Nav, Focus Detail, …) were used only for the decisions they record, not compared frame by frame. They are explorations, and several draw options that were rejected. The admin-side Ravilo pages (`app/ravilo-config.html`, `app/ravilo-users.html`) are out of scope. Three cross-cutting items about them are in §7.

**Legend.** **D>C**: in the design, not built. **C>D**: built, not in the design (the mockup is behind). **DIFF**: both exist and disagree. **Design defect**: the mockup is wrong whatever the code does.

## Summary

The core matches well: the TV home, hero, rows, browse page, Discover (order, gating, 6-up walls), focus detail L/J, the TV player (R303 ident, −10/+30, 3.6 s auto-hide, skip intro, credits card, two-level picker), the phone bottom bar, the thumb-rail player, the *Play on a TV* sheet, and the Chromecast receiver. The three skins' colour tokens and tile radii match to the hex value.

The gaps fall into five groups:

1. **The design's string table is broken.** 22 English keys hold Faroese text, and the Faroese table is pre-R287/R288. The English TV mockup therefore renders Faroese in places. Commit `5c5551dc` ("updated designs", 2026-09-25) put the R288 strings into `STR.en` instead of `STR.fo` (§1.1).
2. **The design breaks two of its own rules.** Viewers see the word *Jellyfin* in six places, and a seek shows *Seeking…*, which R218 forbids. The app gets both right (§1.2, §1.3).
3. **Designed and never built:** the detail page's **About** section, which was never spec'd either; the **quality badge** on tiles; the Live TV tile in the Channels rail, whose admin toggle is therefore dead; a viewer-side **Cast or crew** facet; the *Sending to …* connecting bar, whose strings shipped but are never used (§2).
4. **Built and never drawn:** everything from R305–R319 and 269/271 (plates on every studio and network tile, walls hidden when empty, size and *Recommended* sorts, sort direction), the web install card, update toast and favicon, per-profile *Sign out*, the Settings *Playback* toggles, page-level load errors, server setup on the TV/phone app (§3).
5. **Deliberate divergences the design never absorbed:** TV text entry uses the system keyboard where the mockup draws an on-screen QWERTY; the phone Library is a browse grid where the mockup draws rows; the phone Profile keeps a *Settings* row (§4).

---

## 1 · Design defects (fix in the design, not the app)

### 1.1 The design's English table renders Faroese, and its Faroese is out of date

`design/ravilo/ravilo-i18n.js`: 22 keys in `STR.en` hold Faroese values. Examples: `pm_settings: 'Stillingar'`, `pm_unpair: 'Loys hetta sjónvarpið'` (:230), `seg_coming: 'Komandi skjótt'`, `seg_request: 'Umbøn'`, `nav_upcoming`, `up_all: 'Alt'`, `up_series: 'Seriur'`, `up_airs_in/today/tomorrow` (:239), `up_episode: 'Partur'`, `up_new_episode`, `up_missing`, `up_missing_title` (:272), `up_aired_ago`, `up_released_ago`, `slow_tail_measured` (:175), `fd_nodesc` (:183), `seg_networks: 'Sjónvarpsrásir'`, `tx_sub_networks`, `tx_n_networks` (:401), `tx_empty`.

- `git log -S` traces them to `5c5551dc` "updated designs" (2026-09-25). This matches the recorded R288 pull ("`ravilo-i18n.js` took 41 of those strings"): the pull wrote the new Faroese into the **English** block.
- For the same keys, `STR.fo` still holds the pre-R288 wording: *Støðir*, *Søgur*, *Táttur*, *Vantar*, *Sjanrur*, *vangamynd*, *loyniord*. The shipped `fo.json` says *sjónvarpsrásir/Savn*, *Seriur*, *Partur*, *Manglar*, *Sjangrur*, *vangi*, *loyniorð*.
- Across the 196 strings the two tables share, the design disagrees with the shipped table in **72 Faroese** and **19 Danish** translations. The Danish includes a real typo: `pl_loading: 'Indlæder…'` (:354), shipped *Indlæser…*. The shipped JSON wins per CLAUDE.md, so every one of these is design-side staleness.
- The Chromecast receiver mockup has its own string table, also pre-R288 (*servarin/servaranum*, *Skeið/Rað*).

### 1.2 The design shows viewers the word "Jellyfin"; the app never does

Owner rule (2026-09-25; also R234's rejected copy): Ravilo never names Jellyfin to a viewer. `i18n/en.json` complies. The mockups break it here:

- TV sign-in: *Sign in to Jellyfin*, *Use your Jellyfin username and password…*, *Enter your Jellyfin username.* (`ravilo-i18n.js:378` and neighbours, all three languages)
- Toasts: *✓ Marked watched · synced to Jellyfin*, *Season marked watched · synced to Jellyfin* (`:215`), and a hard-coded *✓ Progress saved — jellystructure → Jellyfin* (`ravilo-app.js:260`)
- TV Settings account row, subtitle *admin · jellyfin* (`ravilo-app.js:2039`)
- Synopsis fallback *…pulled live from Jellyfin…* (`ravilo-app.js:1707`, inside dead overlay code)

### 1.3 The TV mockup's seek contradicts R218

`ravilo-player.js:407` commits a scrub by calling `buffer(550, 'Seeking…')`, which draws a centred overlay with text. R218 moment D is *"no overlay and no words at all"*. The app follows the spec: a small spinner beside the timestamp (`PlayerScreen.kt` ~2335).

### 1.4 Stale design content that was retired in code

- **Chart era (phase 136/137):** Top 10 / trending strings (`nav_top10`, `top10_sub`, `weeks_on`, `new_this_week`, `why_trending`, `rank_in`, `views_week`, `via_source`). The Discover-detail kicker (source wordmark · *via* · *#N in region*), the trend badges, and the phone *Request* tab drawn as a numbered Top-10 list with view counts. The app's Request segment shows Seerr feeds with none of this.
- **Code pairing (retired by R175):** `profiles_hint`, `add_title`, `add_sub` (*…Pair a TV and enter this code*), `waiting` (*Waiting for approval…*).
- **Season-wide *Mark all watched*:** `mark_all_*` and `toast_all_*` strings. The app removed that control on request (`SeriesDetailScreen.kt`, R142 comment).
- **Next-up timing:** the design shows the card 34 s before the end (`ravilo-player.js:14`); R111 moved it to 20 s (`PlayerScreen.kt:150`).
- `openOverlay()` in `ravilo-app.js` (the quick Play / My List / Close sheet) is never called: dead mockup code.

### 1.5 The phone subtitle sheet uses the jargon R180 banned

`Ravilo Mobile.html`'s `SUBS` versions read *Full dialogue · sidecar* / *· embedded*, with badges *SDH* and *forced*. These are file provenance and jargon. The TV picker and the app use the plain-language lines (`player.variant_*`: *The full version of everything spoken.*) and badges *Signs only* / *Sound described*.

### 1.6 Hygiene

The phone Profile page footer hard-codes a server hostname in *Ravilo 1.38 · signed in to …*. The repo is public: confirm it is a stand-in and not a household domain. `scripts/check-deanonymization.sh` only knows the values it was given.

---

## 2 · In the design, not built (D>C)

Ordered by how visible the gap is.

| # | What the design has | State in code | Notes |
|---|---|---|---|
| 2.1 | **About section** on movie and series detail, TV and phone: *About* heading and sub, the synopsis, then facts: Runtime · Released / First aired · Director · Studio / Network · Country · Original language · Seasons · In your library since (`ravilo-app.js` `aboutSection()`, Mobile `aboutHTML()`) | Absent. No composable, no i18n keys | **Never spec'd.** It came from focus-detail round 1 direction F (owner picked A+B+C+D+F on 2026-09-05). Round 2 retired A–D; F was built only in the mockups and no phase carries it. It needs a spec, or it should come out of the mockups |
| 2.2 | **Quality / marketing badge on poster tiles** (*4K*, *New Season*, *Top 10*, *Premiere*) | `MediaCard.badge` exists on the wire, but no service fills it (`toMediaCard(badge = null)` everywhere) and `Tile.kt` never reads it. Only `NEW`, `S:E`, *Soon* and ✓ are drawn | The hero badge *is* drawn (`HeroCarousel.kt:222`). The focus-detail panel shows the facts' quality label |
| 2.3 | **Detail hero kicker** (tagline, or *Film*/*Series*, plus *N Seasons* or the year) and the quality `.tag` in the meta line | Absent on movie and series detail | Home's hero has its kicker |
| 2.4 | **Live TV tile in the Channels & Collections rail** (`ravilo-livetv.js` `collectionTile()`) | Not built. **The admin checkbox `show_collection` (`RaviloConfig.kt:1966`) is saved but Ravilo never reads `LiveTvHomePlacement.showCollection`**: a dead toggle | `show_on_now_row` *is* honoured (`HomeStore.kt:167`) |
| 2.5 | **Viewer-side *Cast or crew* facet** on the browse facet bar, with avatar options (`ravilo-browse.js` `facetDefs()` `person`) | `BrowseFacetKey` has no PERSON; a person is only a browse *seed* | R190 lists the viewer facets without Person, so the design is ahead of its own spec |
| 2.6 | **Connecting bar *Sending to {device}…* → *Playing on {device}*** (recorded in CLAUDE.md, 2026-09-18) | `screens.sending` and `screens.playing_on` ship in `i18n/*.json` but are **never referenced**. The bar says *Connecting to {device}…* (`cast.connecting`) | Strings built, UI never wired |
| 2.7 | **Trickplay scrub thumbnail** (TV *Preview* tile) | None. `StreamTicket.trickplayUrl` is always null | Known and out of scope in R218. Listed for completeness |
| 2.8 | **Channel page header**: logo plate, *‹ Home · Channel*, the channel name as h1, and the sentence *The same rows you love, filtered to {name}…* | The name appears only in the app bar (*· {name}*, R136), with the hero and rows below | — |
| 2.9 | **Phone hero cards** with *▶ Play* and *ⓘ Info* buttons | The phone uses the TV's full-bleed hero, without buttons | — |
| 2.10 | **Phone next-up card**: *Next episode · title · Starts in N s · S2 E8*, *Play now* / *Cancel*, 60 s from the end | The phone reuses the TV credits card (*Up Next · Play in 8s · Watch credits*, 20 s). `pl.play_now` ships and is never referenced | — |
| 2.11 | **Library music empty state**: *Nothing filed as music yet* | `lib.empty_music` ships and is never referenced | — |
| 2.12 | **Add-a-TV success**: the sheet comes back with the new TV briefly outlined | `onPaired` just closes the form (`ScreensSheet.kt:116`) | Minor |
| 2.13 | **Guide header** *TV guide · N channels · ● Now · HH:MM*, and guide density compact/spacious | Title only (`LiveTvGuideScreen.kt:295`). No density option anywhere | — |
| 2.14 | **Live TV player hints**: *← → change channel · ↑ Now & next* in the channel bar, the Now/Next header *↑↓ browse · ↵ tune*, and a *No channel N* toast for a bad number | Overlay, zap and number entry exist; the hints and the toast do not | — |
| 2.15 | **Series *✓ Watched* chip** in the hero meta when a whole series is watched | Movies only (`MovieDetailScreen.kt:251`) | — |
| 2.16 | **Row-header chrome**: a *See all ›* hint on the active row and a config chip (e.g. *Continue + Next Up*) | Neither. See-all is a trailing tile only, per R187 | The design's hint also shows on rows that offer no See-all, which contradicts R187. The design should change here |
| 2.17 | **Toasts**: after picking a track (*🔊 Audio · Dansk*), after *Skip intro*, on *✓ Finished*, and when marking watched | None | Some carry the Jellyfin wording from §1.2 |
| 2.18 | **Discover detail *＋ My List*** once a requested title is available | Not present | — |
| 2.19 | **Profile picker footer hint** | Absent | The design text still describes pairing (§1.4) |

---

## 3 · Built, not in the design (C>D: the mockups are behind)

Most of this landed after the 2026-09-25 design sync.

- **R308 (built 09-27):** every Studios and Networks tile sits on the light plate, with names and re-inked logos in one dark ink. The design draws a plate only behind *logo* tiles on the TV (`ravilo.css:734`) and none on the phone (`.txcard`).
- **R310:** a taxonomy wall with no values for this viewer loses its chip, and Discover can open with no chips and one sentence. The design always shows all three walls.
- **R317 / 268:** a *Size* sort. **R318 / 269:** a *Recommended* source order, the Recommended row and its See all. **Sort direction** per field (re-selecting a field flips it; sub-labels *Newest first*, *A–Z*, …). The design's sort is a flat six-item list with no direction.
- **R291:** picker badges *Now showing* / *Last used* and the *Select one to switch instantly* hint. Also *Surround 7.1*.
- **R191:** per-profile ***Sign out*** with a confirmation, in the TV profile menu and in Settings. The design menu has *My List · Add user · Settings · Unpair* only.
- **TV Settings *Playback* section:** *Show progress on Continue Watching* and *Autoplay next episode*. Also *Install Ravilo* on the web. The design's Settings are *Theme · Language · Account · Unpair*.
- **Server setup on the app itself** (R225/R226) and *Wrong server?* on login. The only server-setup screen in the design is the one in the receiver app.
- **R263 web app:** install card, *Ravilo updated · Reload* toast, the iOS < 18.2 unsupported notice (`ravilo-web/…/boot.js`), a floating *⛶ Fullscreen* button, and **R313** favicon. These are drawn only in *Play on a TV - Directions.html* §A, never in `Ravilo Mobile.html`. There is no web-app icons sheet.
- **R280:** page-level load errors (*Sign in again*, *This profile can't see this*, *This isn't here any more*). The design draws only the player's R237 failure card.
- **R300:** portrait subtitle size. CLAUDE.md lists it as "not drawn".
- **R309:** multi-episode range labels in the resume line and button (*S1E1–3*).
- **Upcoming detail** shows cast and runtime (`up.cast`, `up.runtime_min`), which the design lacks. The design shows a *Quality* (profile) stat, which the app omits.
- **Home stale-content banner** *Showing saved content — trying to reconnect* (R212 snapshot cache).

---

## 4 · Both exist, they disagree (DIFF)

| Area | Design | App | Lean |
|---|---|---|---|
| **TV text entry** (search, sign-in, change password) | In-app on-screen keyboards: A–Z/0–9 for search, QWERTY with Shift/Space/Delete for sign-in and password (`ravilo-app.js` `renderSearch`, `renderSignin`) | The system IME on a native field. Deliberate: *"no reusable on-screen keyboard exists in this codebase"* (`AccountScreens.kt:61`); R277 raises the IME on TV entry | Redraw the mockup: the app's choice is recorded, the design's is not |
| **Phone Library body** | Home-style rows filtered by type, meta *N titles · movies* | The R187 browse grid with facet bar, titled *Library* (as R267 FR-5b specifies) | The design should follow the spec |
| **Phone Search** | Placeholder *Search Ravilo*; empty state *Titles and genres — everything this profile can watch*; matches titles **and genres** | Placeholder *Search by title...*; empty state shows *Suggestions* | Decide whether search should match genres |
| **Phone Profile** | Two Account rows, with subs *{Language} · this phone only* and *Used when you sign in*; Settings removed | Three rows: *App language* (name only) · *Change password* · **Settings** (dev review item 2: phone Settings still holds skin, autoplay, tile shape) | The app applied R304 OQ1's lean (drop *this phone only*: `ui_language` is per viewer). The design should follow |
| **Movie action row** | Play · Mark watched · Trailer · ＋My List | Play · Mark watched · ±My List · Trailer | Cosmetic; pick one |
| **Series watched count** | Only in the Episodes heading (*N of M watched*), with a season progress bar | In the hero (all episodes) **and** the Episodes heading (*N / M watched*), no bar | — |
| **Resume line** (series hero) | *Resume S1 · E3 "title" · N min left* / *Up next · S1 · E3 "title"* | `episodeCode · title` in accent, with no *Resume*/*Up next* word and no minutes | — |
| **Next-airing line** | *Next episode · S2:E5 "title" · airs 12 Oct 2026* (localised) | *…S02E05… · airs 2026-10-12*: **the raw ISO date**. Episode cards format dates properly (`formatAirDate`) | Real app bug: the hero skips the formatter |
| **Episode code spelling** | *S1:E3* on the TV, *S2 · E8* on the phone | Five spellings: *S1 · E3* (player kicker), *S1E3* (resume line), *S1:E3* (Continue tile badge), *S1·E3* (Coming Soon card), *S01E05* (next airing) | Pick one |
| **Episode card title** | *3. Title* | *E3 · Title* | Cosmetic |
| **Channel rail heading** | *Channels & Collections* / *Configured in Jellystructure* | *Collections*, no sub (`section.channels_sub` ships, unused) | — |
| **Phone player rail** | Four items: Subtitles · Episodes · Next · Lock (the markup), although the design's own decision says three | Subtitles · Next *or* Episodes · Lock (+ Guide for live) | The app matches the decision; fix the markup |
| **Chromecast next-up** | *Next episode* + a still thumbnail | *Up Next*, no thumbnail | — |
| **Chromecast buffering** | Dimmed picture + overlay with progress + spinner | A bare spinner screen | — |
| **Poster size (TV)** | 210 px wide on the 1920 canvas | 155 dp. At the 2× density R303's own comment assumes, that is 310 px: ~48 % larger | Verify on a TV before acting; TV densities vary |

---

## 5 · Strings in numbers

- The design table has 300 keys; the shipped table has 440. 196 English strings match exactly.
- Of the 104 design keys with no shipped match: 22 are Faroese in the English table (§1.1), 16 are retired chart, pairing or *Mark all* copy (§1.4), 12 are About/fact strings (§2.1), and the rest are copy variants.
- 202 shipped keys have no counterpart in the design table. Many are hard-coded in the mockup HTML instead (the *Play on a TV* sheet, the receivers, the phone profile). The rest are §3's features.
- **Shipped keys no Kotlin (or `boot.js`) ever references (18):** `lib.empty_music`, `screens.sending`, `screens.playing_on`, `screens.airplay_bar`, `pl.play_now`, `player.last_used`, `search.suggestions`, `section.channels_sub`, `profile.switch`, `browse.see_all`, `livetv.now`, `row.continue`, `row.next_up`, `row.new_movies`, `row.new_series`, `row.new_all`, `sonarr.upcoming`, `sonarr.via_sonarr`. The first five are designed features that were never wired (§2). The rest look like leftovers. Row titles come from the server.

## 6 · Receivers

- **Chromecast** (`Ravilo Receiver.html` ↔ `Receiver.kt`): all ten states are present. Idle (mark + sentence), loading (pulse, kicker/title/sub, *Loading…*), paused overlay flash, captions, next-up countdown, no server, busy (*waiting N s*), and *ended* returning to idle. Only the next-up and buffering differences in §4.
- **Receiver app** (`Ravilo Receiver App.html` ↔ `ravilo-screen`), **paused with the rest of Tizen**. Recorded only:
  - The idle layouts differ. The design has a *Ravilo* lockup, the **TV's own name** bottom-left with the server under it, and a 96 px code bottom-right with a hint. The app shows a centred code (64 px) and a server line, with **no TV name**: `deviceName()` is hard-coded *"Tizen TV"* (`Screen.kt:858`).
  - The design's *waiting* state keeps the idle layout with a spinner in the code slot. The app switches to a separate *no server* screen.

## 7 · Adjacent admin-side items (outside this audit's scope)

- `show_collection` is saved in `RaviloConfig.kt` and never read by Ravilo (§2.4).
- 236 §D, the screens table in Settings (kind · platform · paired users · now playing · last seen · revoke per user), is in neither the design nor the code. CLAUDE.md lists it as design work still owed.
- R270's *Play on a TV* help card exists in the code (`Settings.kt:312`).

## 8 · What matched

Checked and aligned:

- TV app bar (four tabs, search, clock, avatar)
- Discover declared order and gating filter (R268), taxonomy wall counts, 6-up TV / 2–3-up phone
- Hero carousel (badge, kicker, logo/title, meta, synopsis, dots)
- The genre row (R221), the flag line (R134/R239 counting), the playback note (R222), the multi-episode card (R179)
- Browse facets (Genre · Type · Maturity range · Year · Watched · Audio · Channel · Quality), Seerr overflow row, person seed and role line
- Coming Soon: filter chips, *Missing* jump, date rail, per-day sections; Seerr search
- Focus detail L and J (fields, *no description yet*, the R242/R255 backdrop)
- TV player: R303 ident with the dark-ink plate, −10/+30, 3.6 s hide, the 30 s episode-rail close, skip intro, the credits card's stinger → next → skip order, the R195 two-level picker, R237 failure copy
- Phone: bottom bar (five items, sliding pill, avatar ring), top row (brand · Library type pill · cast, no clock), the thumb-rail player with its gestures, lock, rotate-only-when-locked, subtitle size, the three-tier *Play on a TV* sheet (busy names the viewer, *In use*, weekday last-seen, Chromecast on Android only, AirPlay footnote, six-character add code), the remote, the mini bar, R299 *couldn't play this*
- The R304 Profile page (identity, My List row, sign-out sheet, version footer)
- Aurora / Midnight / Noir tokens and tile radii (12 / 14 / 6)

## 9 · Limits

- This was read from the source. Nothing was rendered or run on a device. Pixel-level spacing and type sizes were compared only where a mismatch was obvious (§4, last row).
- The *Directions* canvases, `Ravilo Live TV.html` (the standalone prototype that `ravilo-livetv.js` superseded), and the logo and navbar concept files were not compared.
- The web app has no mockup of its own. It was checked only through the shared Compose code and `ravilo-web`'s `boot.js`.

## Suggested triage (not done)

- **Design fixes, cheap and high value:** swap the 22 Faroese values out of `STR.en` and re-pull `fo`/`da` from `i18n/*.json` (§1.1); drop the *Jellyfin* wording (§1.2); make the seek wordless (§1.3); delete the chart-era and pairing remnants (§1.4); replace the phone picker's jargon (§1.5); redraw TV text entry, the phone Library and the phone Profile to match what shipped (§4); draw R308, R310, R317, R291, R191, the Settings Playback toggles and the web install surfaces (§3).
- **App fixes, small:** format the hero's next-airing date (§4); wire the unreferenced strings for the connecting bar, the music empty state and the phone next-up, or delete them (§2.6, §2.10, §2.11, §5); honour or remove `show_collection` (§2.4); pick one episode-code spelling (§4).
- **Needs an owner decision first:** the About section (§2.1: spec it or remove it), the tile quality badge (§2.2), the viewer *Cast or crew* facet (§2.5), and whether search should match genres (§4).
