# Something to watch when a film ends

Task: DEB-5841

> Owner, DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful in
> our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece on the card "Ravilo polish: what to build" on 2026-11-26.

## Scope

Touches the shared player and navigation in `ravilo-ui` (`PlayerScreenState`, `RaviloApp`), plus one new page in
`ravilo-ui` common code. No backend change, no wire change, no migration.

**Clients in:** Android TV, phone, Ravilo Web, desktop (Mac and Linux). They share `PlayerScreenState`, so one change
reaches all of them. **Clients out:** Samsung (`ravilo-screen` has its own player, and its open work is DEB-5685), the
Chromecast receiver (no input; the caster's remote shows the end), a cast from the phone or the Mac (the sender is a
remote, not a player), and a phone playing a download (offline, no server to ask).

**Amends** [R182's 2026-11-08 amendment](phase-R182-skip-intro-credits.md) (DEB-3950) FR-1, with a dated note in its
file: a finished film now closes to this page when it has something to offer, and to the page it was opened from
otherwise. DEB-3950's FR-3 (after the end, one Back leaves) is kept. Nothing else in R182, R384 or R375 changes.

## Why

- Today a film ends and the player closes to the film's page (DEB-3950 option A). The viewer then has to find the next
  thing on their own. Netflix, Disney+ and HBO Max all offer something to watch at that point.
- The investigation (DEB-5687, evidence §8) found a next-episode countdown but nothing after a film:
  `CreditsCardMode` (`PlayerScreen.kt:264`) has no suggestion mode, and the card text at `PlayerScreen.kt:2316-2326`
  only ever offers *Skip credits* for a film.
- The list is already on the wire. `MovieDetail.related` (`shared/.../tv/Models.kt:922`) holds up to 12 titles
  (`RELATED_LIMIT`, `DetailService.kt:36`), same genre, visible to this viewer, with `watched` filled in
  (`hydrateRelated`, `DetailService.kt:280`). The film's page already shows it as *More Like This*
  (`MovieDetailScreen.kt:514`). It sits low on that page, under the fold on a TV.

## Requirements

### FR-1 — A finished film opens the after-film page

- **A film** is a play whose item is a `MediaKind.MOVIE`. `Dest.Player` gets a new client-side flag, `isFilm`
  (default `false`), set by every start of a film: the title page's Play, a Home or browse tile's Play on a film card,
  the resume record, and a session hand-over of a film. A music video, an episode or a trailer never sets it.
- **When the player would close a finished film, it opens the after-film page instead.** "Would close" means the
  three places that call `skipCredits()` for a film today: the real end of the file (`endClosesPlayer`, DEB-3950 FR-1),
  the natural-end branch with Skip Credits Off (R230 FR-R230-2), and the viewer pressing *Skip credits*.
- Before it opens the page, the player does everything it does today when it closes: R347's mark-watched, R372's
  end-of-play stop, and releasing the player. **The page holds no stream and no player.**
- The page replaces the player on the back stack (`replaceTop`). Back from the page returns to the page the player
  was opened from (the film's page, or Home, or a browse page), in **one** press. DEB-3950 FR-3 holds.
- Every other way out of the player is unchanged: Back or Stop before the end, a stop from another app's remote, and
  the error screen. A film left before its end never opens the page.
- Skip Credits Prompt, Auto and Off all behave the same way here: the page comes after the film, never during it.

### FR-2 — Nothing plays, loads or starts until the viewer picks

- The page **starts no play, no encoder job, no restream and no prefetch.** It calls no playback route.
- Nothing on the page counts down. There is no timer, no auto-advance and no auto-play. It stays until the viewer
  picks a title or goes Back.
- The player adds no work while it plays. It does not fetch the list, load a suggestion's picture, or prepare a
  suggestion's stream at any point in the film. The prefetch R381 and R384 FR-R384-6 describe stays for the next
  episode only; a film still prefetches nothing.
- Its pictures load through the existing artwork loader when the page opens, and only for the tiles on screen.

### FR-3 — What the page offers

- **The source is `MovieDetail.related` for the film just played.** When the play began on the film's page, the list
  that page already holds is carried in `Dest.Player` (a new client-side field, never saved in the resume record).
  Otherwise the page makes one `GET /api/tv/movie/{id}` (`TvApiClient.getMovie`) when it opens, after the player has
  closed.
- **The picks:** the related titles not marked `watched`, in the server's order, at most 6. The film just played is
  never one of them.
- **No picks, no page.** When the list is empty, every title is watched, the fetch fails, or it has not answered within
  3 s, the page does not stay: it closes to the page the player was opened from, exactly as today. The viewer never
  sees an empty page or an error card here.
- A pick may be a film or a series. Its tile shows what the same tile shows on the film's page: poster, title,
  watched mark, quality badge, upcoming label, downloaded mark.

### FR-4 — Picking a title opens its page

- Selecting a tile opens that title's page (the same `openDetail` the film's page uses). It does **not** start the
  title: the viewer presses Play there. This is the only way a play starts from here.
- The title's page replaces the after-film page, so Back from it returns to the page the finished film was opened
  from.

### FR-5 — Focus, layout and the viewer's text

- **Android TV and Ravilo Web on a TV-sized window:** the page opens with focus on the first tile. Left and Right move
  along the row, Back leaves. There is always exactly one focus target. Tile focus uses `Tile`'s existing focus
  treatment; the page adds no animation of its own and no work to focus motion.
- **Phone:** the same row, scrolled by touch; the system back and the page's back control leave.
- **Desktop (Mac, Linux):** the desktop layout's page frame; pointer, keyboard and Esc as on any other desktop page.
- **Ravilo Web:** the page has no address of its own. A reload while it is open lands on Home, the same as a reload of
  the player does under [DEB-1738](a-reload-or-a-link-opens-the-page-it-names.md).
- **Text:** the kicker is `player.end_of_movie` (*End of film*) with the film's title, and the heading is
  `section.related` (*More Like This*). Both keys exist in English, Danish and Faroese. **No new string.** If the
  design round adds one, it is added to `en.json`, `da.json` and `fo.json` in the same change, and the Danish and
  Faroese values carry a `note` marking them a draft for review, as `fo.json` already does for R343.

## What this does not do yet

- After a series' last episode the player still closes to the series page.
- Samsung and the Chromecast receiver keep today's end.
- No "Because you watched" row on Home, and no new ranking. The list is the one the film's page shows.
- The page does not play a trailer or a preview behind it.
- Nothing is written to the server. The backend cannot tell this page was seen.

## Design

This is a new page, so it needs a design round before slice 3 is built
([`design/PROCESS.md`](../../../design/PROCESS.md)). It reuses `Tile`, `ArrowRow` and the title page's backdrop scrim;
the designer draws only the layout:

1. Android TV, 1920 × 1080: the finished film's backdrop, the kicker and the film's title (or its logo), the heading,
   one row of up to 6 tiles, the first one focused.
2. The same with 2 picks (a short row).
3. Phone, landscape and portrait.
4. Desktop layout (sidebar rail), and Ravilo Web at a narrow width.
5. Light theme and one dark theme for 1 and 3.

Whether the film's own title or logo is shown, and whether a visible back control is needed on pointer clients, is
the designer's call. Any new text goes through FR-5's string rule.

## Acceptance

1. On the TV app, a film played from its page to the end: the after-film page opens with up to 6 unwatched related
   titles, the first focused. The film is ticked. One Back returns to the film's page.
2. The same film started from a Home tile: the page opens the same way, and one Back returns to Home.
3. A film whose related titles are all watched, or have none: the player closes to the film's page, as today.
4. *Skip credits* pressed on a film's credits card: the page opens at once.
5. Back pressed mid-film: the player closes as today; no page.
6. Selecting a pick opens its page, nothing plays; Back from it returns to the page the finished film was opened from.
7. While the after-film page is open, prod shows no encoder job and no open playback session for this device.
8. An episode with a next one, and a series' last episode: unchanged (R384, DEB-3950).
9. The same on the phone, Ravilo Web and the Mac.

## Build order

One engineer. Each slice is one commit citing `(DEB-5841, FR-k)`.

1. **The picks rule (FR-3) — junior-ready.** A pure function `postFilmPicks(related, playedId): List<MediaCard>`
   (unwatched, server order, at most 6, never the played id) in `ravilo-ui/.../screens/PostFilmPicks.kt`. Proof:
   `PostFilmPicksTest` in `commonTest`:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*PostFilmPicksTest*' --console=plain`.
   About 60 lines.
2. **The player routes a finished film (FR-1, FR-2).** `Dest.Player.isFilm` and `related` (client-side, not in the
   resume record), set at each film start in `RaviloApp.kt`; a decision function `filmEndOpensPage(isFilm,
   fromDownload, picksKnownEmpty)` beside `endClosesPlayer` in `PlayerScreenState.kt`; `skipCredits()` calls a new
   `onFilmEnded` input instead of `onBack` when it says so. Until slice 3 lands, `onFilmEnded` is `onBack`. Proof:
   new cases in `PlayerEndClosesTest` (film vs episode vs music video, mid-film Back, download):
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*PlayerEndClosesTest*' --console=plain`.
   About 120 lines.
3. **The page (FR-3, FR-4, FR-5), after the design round.** `Dest.PostFilm`, `PostFilmScreen.kt` reusing `Tile`,
   `ArrowRow` and the detail backdrop; the 3 s fetch and the close when there are no picks; tile select through
   `openDetail` with `replaceTop`. Proof: a desktop Compose test `PostFilmScreenTest` (first tile focused, Back pops
   once, empty list closes, no playback call on the fake `TvApiClient`):
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*PostFilmScreenTest*' --console=plain`,
   plus `:ravilo-ui:compileDebugKotlinAndroid` and `:ravilo-web:compileKotlinWasmJs`. About 180 lines.
4. **Ravilo Web reload (FR-5).** `Dest.PostFilm` has no route, and a reload on it opens Home. Proof: a case in
   `RouteToDestTest`: `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*RouteToDestTest*'
   --console=plain`. About 30 lines.

Device acceptance (1–9) is a QA row on the TV and the phone after slice 3.
