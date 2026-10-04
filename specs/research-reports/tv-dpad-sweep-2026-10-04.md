# TV D-pad sweep — 2026-10-04

The owner asked for a D-pad sweep of Ravilo on the living-room TV after two reports: the D-pad getting stuck on the
top bar after filtering Movies, playing and coming back; and a Recommended title vanishing (with focus "going
bananas") after marking it watched. Later in the session the owner added Skip Intro (*"close to impossible to
click"*).

**Device:** Sony BRAVIA (Android TV 12, 960 × 540 dp). **Builds:** the installed `1.47-34-ge12d04eb` first, then,
with the owner's go-ahead, a local release of `main` `1.49-11-g76f351b4` installed in place (same signing key as the
installed sideload) and AOT-compiled. Every finding below was re-checked on 1.49-11 unless noted. Backend: production.

## Findings → specs

| # | Finding | Spec |
|---|---|---|
| 1 | Facet bar scrolled → Down from the app bar does nothing (stuck on the top bar) | R362 FR-1 |
| 2 | Returning with filters applied: the active filter chip is scrolled out of view | R362 FR-4 |
| 3 | Grid Down from column 4 into an off-screen row lands on column 1 | R362 FR-5 |
| 4 | Facet popovers open at the left edge, fixed height | R362 FR-6 |
| 5 | Recommended: a watched title leaves the row on Back | phase 269 amendment |
| 6 | Back to a title that is gone (Home row, filtered grid) → focus on the app bar's Home tab, page still scrolled | R361 |
| 7 | Skip Intro: unreachable once its countdown ends; opening the controls + OK pauses; steals focus from −10 s; ring in Prompt mode | R363 |
| 8 | My List never shows a title added since the app started | R364 |
| 9 | Search's Clear unreachable on a TV | R365 FR-1 |
| 10 | Right from a title page's synopsis jumps to the app bar's Search | R365 FR-2 |
| 11 | Down after opening a season lands on the card under the pill, not the season's start | R365 FR-3 |
| 12 | Coming Soon: Down from the tab lands on *Movies*, not the selected *All* | R365 FR-4 |
| 13 | Back from Settings lands on the page's tab, not the avatar | R365 FR-5 |
| 14 | A collection's Back-to-top leaves focus on a half-hidden tile | R365 FR-6 |
| 15 | A network's browse page says *Discover · Networks* twice | R365 FR-7 |

## What worked well (no spec)

- Continue Watching: play, Back ×2 → focus follows the title after the row re-sorts (R248).
- The series page (R350): Back from the player returns to the episode card that started it; Down from Play goes to
  the open season; *Up next* is where the rail opens.
- Search on a TV (R350): no keyboard on arrival, OK raises it, Back closes it, results → title → Back restores.
- Discover walls keep the column on Down; Back from a network page returns to its tile.
- Settings: every row reachable top to bottom.
- Back from a title to a Movies grid whose title survives restores the tile.

## Open question for the owner: what comes next after an episode you finish

**Seen (2026-10-04, 10:52):** a series was in progress at S03E14 (Continue Watching showed it). Finishing S03E14
(play to the end) made Continue Watching and the series page's primary button jump to **S06E07 — an episode half
watched in August** — instead of **S03E15**, the episode after the one just finished. Jellyfin's own
`/Shows/NextUp` answers S06E07 too.

**Why:** since R343 FR-R343-6 (owner, 2026-10-01) *"there is no in-order pointer of our own — Jellyfin's tracking
decides"*. Jellyfin's Next Up follows the **furthest** episode ever watched (by season/episode number), not the one
watched **last**; S06E06 was watched in August, so S06E07 wins, and its old resume point makes it an "in progress"
pick as well. Anyone re-watching an earlier season, or watching out of order (kids do), is pulled forward to wherever
they once got to.

**Options to talk about:** (a) keep Jellyfin's rule (today); (b) after a finish, the next episode **in order after
the last one played** wins over Jellyfin's Next Up — the pointer R343 dropped, or computed from `LastPlayedDate`
with no table of our own; (c) Jellyfin's *Enable rewatching in Next Up* (`enableRewatching`) where it helps. No spec
until decided.

(The S03E14 finish was this test's; its progress was put back afterwards — unwatched at 6:05, play count 3. A later credits-card test replayed the already-watched S03E08, whose new *last played* date again made Next Up win over Resume (`HomeFeedService`: a finish newer than the resume point picks Next Up); its date was set back to mid-September and Continue Watching shows S03E14 again.)

## Side effects left on the household account

Each change was put back: three films marked watched then un-marked, one film un-marked then marked again, a
series added to and removed from My List (checked against Jellyfin afterwards). Two episodes of two series gained
about 20 s of progress (resumed from Continue Watching / the series page), and the Skip Intro episode has a
last-played date at 0:00 (not watched, no resume point). The TV runs `1.49-11-g76f351b4` (release, sideloaded, AOT); the older `.debug` Ravilo found on it was left alone.

## Technique

`adb` key events with the foreground activity checked before each batch; `uiautomator dump` for text and bounds
(does not report Compose focus — screenshots for rings); the prod DB copy for an episode with a known intro window;
`GET /api/tv/browse?kind=mylist` with the TV's own device token to split client from server (My List). Playback
state from `AudioMediaPlayerWrapper` lines in logcat (position, state, speed) is a cheap way to see whether a key
paused, seeked or skipped.
