# The words on screen are right

Task: DEB-5836

> Owner, on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece, polish group B, on the card *Ravilo polish: what to build* on 2026-11-26.

## Scope

Client text only, in `:ravilo-ui` (Android TV, phone, Ravilo Web, desktop Mac and Linux), `:ravilo-screen` (Samsung)
and `i18n/*.json`. No route, no DTO, no schema and no server change. The Chromecast receiver and the legacy
`ravilo-tizen` app draw none of these lines and are out.

It restores FR-R240-11 (the focus detail's counts come from the string table) and amends three shipped specs, each with
a dated note in its own file: R237 (the re-auth heading names the device), R171 and R190 (no "Seerr" in viewer text).
It reuses DEB-2938's `airDateText` for dates. The source is the DEB-5687 investigation, findings 5, 9, 19 and 20.

## Why

What the code does on `main` at `921645877`:

- **"N min left" is wrong on the TV.** `MediaCard.progressPct` is 0..1: the server divides Jellyfin's percentage by 100
  (`tv/ContinueTarget.kt:120`) and the tile bar draws it as a fraction (`Tile.kt:307`). `focusDetailResumeText`
  divides it by 100 again (`FocusDetailFormat.kt:42-44`). A 120-minute film half watched reads "119 min left".
- **A phone says it is a TV.** A play refused for re-auth shows *This TV needs to be signed in again* and *Sign in
  again on this TV* on every client (`PlayerScreen.kt:3137, :3149`). A song that fails on this phone says
  *{device} couldn't play this · Try another title, or play it on this phone* (`OneRemoteScreen.kt:335, :356`).
- **Viewer text names our parts.** *Search Seerr* (`search_seerr`, `SeerrSearchScreen.kt:142`,
  `RequestScreen.kt:169`), *More with {name} · request on Seerr* (`SeededBrowseScreen.kt:653`), *Enter your
  jellystructure server address* (`ServerSetupScreen.kt:135`) and *add feeds in the Ravilo config editor*
  (`RequestScreen.kt:134`). A viewer cannot open the config editor. The Ravilo constitution's invariant 12 says copy
  says what the viewer can do.
- **Dates show as `2026-11-28`.** A series' next-airing line (`SeriesDetailScreen.kt:779`; Samsung
  `ScreenDetail.kt:460`), Coming Soon's *Was due {date}* (`UpcomingScreen.kt:486-492`) and the Coming Soon detail's
  air or release date (`UpcomingDetailScreen.kt:269`) print the ISO string.
- **English in Danish and Faroese.** The TV focus detail builds "1 Season", "N Seasons", "N episodes" and "N min" in
  Kotlin (`FocusDetailFormat.kt:19-31`). The film page writes `"${runtime} min"` (`MovieDetailScreen.kt:289`). The
  episode card writes `"${runtime}m"` (`EpisodeCard.kt:155`), and so does Samsung's episode rail (`Screen.kt:1239`).

## Requirements

### FR-1 — Minutes left is the film's real remainder

Android TV (the focus detail panel and line, `FocusDetailPanel.kt:188`, `FocusDetailLine.kt:97`).

- `focusDetailResumeText` reads `progressPct` as 0..1. Minutes left = `max(1, round(runtime × (1 − pct)))`. A
  120-minute film at 0.5 reads *60 min left*.
- No line when `pct <= 0` or `pct >= 1`. The server's `nextUpLabel` still wins when it is set.
- This restores FR-R240-11. It is the same arithmetic on the focus path: no new state, no extra composition.

### FR-2 — A failure names the device it happened on

The re-auth heading and body of the player's error card (`PlayerSessionErrorOverlay`) pick their keys by client:

| Client | Heading | Body |
|---|---|---|
| Android TV, Samsung | `error.play.reauth.title` (unchanged) | `error.play.reauth.body` (unchanged) |
| Android phone | `error.play.reauth.title_phone` *This phone needs to be signed in again* | `error.play.reauth.body_phone` *Sign in again on this phone to keep watching.* |
| Mac | `error.play.reauth.title_mac` *This Mac needs to be signed in again* | `error.play.reauth.body_mac` *Sign in again on this Mac to keep watching.* |
| Linux | `error.play.reauth.title_desk` *This computer needs to be signed in again* | `error.play.reauth.body_desk` *Sign in again on this computer to keep watching.* |
| Ravilo Web | `error.play.reauth.title_web` *Sign in again to keep watching* | `error.load.reauth.body` (existing) |

- Ravilo Web runs on any device, so its wording names none.
- The choice is one pure function of `isTvPlatform`, `isMacPlatform`, `isDesktopPlatform` and `isWebPlatform`, so it
  can be tested off a device. The `_desk`/`_mac` suffixes follow `cast.play_here_desk` and `cast.play_here_mac`.
- **A song that fails on this device** (`RemoteModel.here`) says `music.fail_t` *Couldn't play this* and
  `music.fail_p` *Try again, or skip to the next song.* in the remote's state lines. These keys exist. A song that
  fails on another place keeps `cast.failed` and `cast.failed_sub`.
- R237's FR-R237-3 actions do not change: REAUTH still offers Sign in, never Retry.

### FR-3 — No internal name in viewer text

Every client that shows these keys (Android TV, phone, Ravilo Web, desktop). Same keys, new text, in en, da and fo
together:

| Key | New English |
|---|---|
| `search_seerr` | *Search to request* |
| `browse.seerr_more` | *More with {name} you can request* |
| `request.empty` | *Nothing suggested yet. Search for a title to request it.* |
| `setup.server_prompt` | *Enter your Ravilo server address* (as `cast.no_server` already says) |

- No key is renamed; the generated table and every call site stay as they are.
- A test over the generated table fails when a viewer string, in any language, contains `Seerr`, `Jellyfin`,
  `jellystructure` or `config editor`. It carries a written allowlist for keys only the admin web reads (today the
  `migrate.*` keys, `ContainerConversionUi.kt`). The words are listed in the test, not guessed.
- The Danish and Faroese texts are drafts with the note *draft, review before release* (the DEB-2078 pattern).

### FR-4 — A date reads as a date

Every surface below goes through `airDateText` (`shared/tv/TileBadges.kt:36`, DEB-2938) with `currentYearUtc()`: *Thu
29 Oct* this year, the `date.month_day_year` form otherwise.

- The series next-airing line after `sonarr.airs`: `SeriesDetailScreen.kt` (Android TV, phone, Ravilo Web, desktop)
  and Samsung's `ScreenDetail.kt:460`.
- Coming Soon's `up.was_due` `{date}` (`UpcomingScreen.kt`), for a single card and an all-missing stack.
- The Coming Soon detail's `up.air_date` / `up.release_date` value (`UpcomingDetailScreen.kt`).
- A blank or malformed date keeps today's raw text, so nothing disappears.
- No new key: `date.*`, `month.*` and the weekday keys exist in en, da and fo.

### FR-5 — Counts and durations in the viewer's language

- **Android TV focus detail** (`focusDetailCountText`): new keys `fd.season_one` *1 Season*, `fd.seasons` *{n}
  Seasons*, `fd.episode_one` *1 episode*, `fd.episodes` *{n} episodes*, joined with ` · ` as today. A film's runtime
  uses the existing `up.runtime_min` *{n} min*. This restores FR-R240-11.
- **Film page meta line** (`MovieDetailScreen.kt:289`, phone, TV, Ravilo Web, desktop): `up.runtime_min`.
- **Episode card badge** (`EpisodeCard.kt:155`) and **Samsung's episode rail** (`Screen.kt:1239`): `up.runtime_min`,
  so the badge reads *45 min*, not *45m*.
- Danish and Faroese get the four new `fd.*` keys as drafts marked for review. The build warns, never fails, on a
  missing key, so a missing translation shows English, not a key.
- **Performance:** the episode card recomposes while focus moves (DEB-5687 finding 6). Its badge text is computed
  once per runtime and language (`remember`), not per frame. The focus detail keeps one `str()` call per part.

## What this does not do yet

- English decade labels in browse filters (`SeededBrowseScreen.kt`), and the server's English fallback title
  *Episode N* (`tv/DetailService.kt:175`). The second needs a wire change and is its own task.
- `up.episodes_one_file` still carries an English `{mins}m`.
- Coming Soon's relative day (local zone) and the detail's (UTC) still disagree, and the Seerr search still says
  *1 results*. Both are DEB-5687 findings outside this group.
- No phrasing review of Danish and Faroese beyond the keys named here.

## Design

None. Every change is text inside an existing component: the error card, the remote's state lines, the request tab,
the setup screen, the focus detail, the meta line and the episode badge. The badge's *45 min* fits the existing pill.
No round.

## Acceptance

- A 120-minute film at 0.5 on Android TV's focus detail reads *60 min left*; 0.99 reads *1 min left*.
- A re-auth refusal on a phone, a Mac, a Linux desktop and Ravilo Web shows its row of FR-2's table; Android TV shows
  today's text. A song failing on this phone shows *Couldn't play this*.
- No string a viewer can see, in en, da or fo, contains `Seerr`, `Jellyfin`, `jellystructure` or `config editor`, and
  the test in FR-3 proves it.
- With the language set to Danish, a series' next airing, Coming Soon's *Was due* and its detail date read as Danish
  dates. The TV focus detail of a one-season series reads the Danish key's text, not *1 Season*.
- `scripts/check-ravilo-strings.sh`, `scripts/check-i18n-spelling.sh` and `:ravilo-i18n:generateRaviloStrings` pass.

## Build order

One engineer. Each slice is one commit, citing `(DEB-5836, FR-k)`.

1. **FR-1, minutes left.** *Junior-ready.* `FocusDetailFormat.kt` (a pure `minutesLeft(runtime, pct)` the composable
   calls), a new `FocusDetailFormatTest` in `ravilo-ui/src/commonTest`. About 40 lines. Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*FocusDetailFormat*' --console=plain`.
2. **FR-5, the focus detail, film page and episode card.** *Junior-ready.* `FocusDetailFormat.kt` (counts take a
   translator, as `airDateText` does), `MovieDetailScreen.kt`, `EpisodeCard.kt`, `i18n/en.json`, `da.json`,
   `fo.json`. About 80 lines. Proof: the same test class with a fake translator, then
   `./gradlew :ravilo-i18n:generateRaviloStrings :ravilo-ui:desktopTest --tests '*FocusDetailFormat*'` and
   `scripts/check-ravilo-strings.sh`.
3. **FR-4, dates in `:ravilo-ui`.** *Junior-ready.* `SeriesDetailScreen.kt` (a pure `nextAiringText` helper),
   `UpcomingScreen.kt`, `UpcomingDetailScreen.kt`, a new `DateTextOnScreenTest`. About 90 lines. Proof:
   `./gradlew :ravilo-ui:desktopTest --tests '*DateTextOnScreen*'`.
4. **FR-4 and FR-5 on Samsung.** Not junior-ready: two FRs, and the proof is a Samsung build. `ScreenDetail.kt:460`,
   `Screen.kt:1239`. About 20 lines. Proof: `./gradlew :ravilo-screen:compileKotlinJs`; the helper itself is covered by
   slice 3 and `:shared:desktopTest --tests '*TileBadges*'`.
5. **FR-2, the device in the failure.** *Junior-ready.* `PlayerScreen.kt` (`PlayerSessionErrorOverlay` only: the
   R237 note says new player code stays out of `PlayerScreen`'s body), `OneRemoteScreen.kt` (`RemoteStateLines`),
   en/da/fo for the eight new keys, a new `ReauthWordingTest` over the pure key chooser. About 100 lines. Proof:
   `./gradlew :ravilo-ui:desktopTest --tests '*ReauthWording*'` and `:ravilo-ui:compileDebugKotlinAndroid`.
6. **FR-3, no internal names.** *Junior-ready.* en/da/fo for the four keys, a new `NoInternalNamesTest` in
   `ravilo-i18n/src/commonTest`. About 60 lines. Proof:
   `./gradlew :ravilo-i18n:desktopTest --tests '*NoInternalNames*'` and `scripts/check-i18n-spelling.sh`.
