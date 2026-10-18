# Ravilo Web: a reload or a pasted link opens the page it names, not Home

Task: DEB-1738

> Found on DEB-1733 (2026-10-18), from DEB-1655 flag B. The owner approved building it on DEB-1738 (card `cfc28f6e`,
> 2026-10-18): *"make Ravilo Web open the page in its URL on load"*.

## What the web app does today

R80 gave Ravilo Web hash routes. Every `Dest` has a `toRoute()` (`RaviloApp.kt`), and `push`, `pop`, `resetTo` and
`replaceTop` write it with `history.pushState` / `replaceState` (`RaviloRootActuals.kt`, wasmJs). Browser Back fires
`popstate`, and the listener calls `pop()` **without reading the URL it landed on**.

Nothing reads the URL on load. The first stack comes from the cached sessions alone: none → Login, one → Home, several
→ the profile picker. Then the first composition runs `replaceRoute(stack.last().toRoute())`. So a pasted
`#/movie/<id>` is not just ignored: it is **overwritten** with `#/home` before anything else happens.

Three consequences a viewer sees:

1. A reload on any page lands on Home.
2. A link someone shares, or a bookmark, lands on Home.
3. Browser **Forward** never works. It also fires `popstate`, the listener calls `pop()`, and with Home alone on the
   stack nothing happens. The URL bar then shows the page and the screen shows Home.

R80 has no spec file. Its promise lives only in code comments ("each Dest maps to a hash route"), and it never said the
URL is read. So this is new behaviour, not a restore.

**The ask's example cannot be served.** `#/music/artist/<id>` is a route the web app never writes. Music mode needs
`MusicEngine.supported`, which is `false` on wasm (`MusicEngineWasm.kt`), so no music page is reachable on the web.
FR-2 says what such a link does.

## Scope

- **Ravilo Web only.** Android, the TVs, the phone app, Mac and Linux have no URL. `pushRoute` / `replaceRoute` are
  no-ops there, and the new seam is too (FR-1).
- **No backend change and no wire change.** The fragment after `#` never reaches the server, so `web-static-server`
  and the service worker (R263) keep serving `/` as they do. Every page FR-2 restores loads from routes that already
  exist. The one page that needs more than its id, a channel, is resolved through the existing `GET /api/tv/channels`.
  Prod is not a pre-release gate for this work.
- **No design surface and no new strings.** A link to something that is gone shows the page's existing load error
  (R280's `LoadErrorKind`).
- **Out of scope:** encoding a page's state that the route does not carry today, such as a search query, a Discover
  segment, a Browse facet or a scroll position. A link opens the page as a fresh visit would open it.

## Requirements

**FR-1 — The web app reads its URL once, at load.** A new `expect fun initialRoute(): String?` returns the location's
hash without the leading `#`, or `null` when it is empty. The wasmJs actual reads `window.location.hash`. Every other
target returns `null`. The value is read **before** the first composition writes any route. The R80
`LaunchedEffect(Unit) { replaceRoute(...) }` then writes the route of whatever FR-2–FR-4 put on top. So a restored page
keeps its own URL, and anything that was not restored is rewritten to the page actually shown, as it is today.

**FR-2 — Which routes open their page.** One function, `routeToDest(route, displayName): Dest?`, is the inverse of
`toRoute()` for the routes below. It lives beside `toRoute()` so the two are edited together. `displayName` is the
active session's. An id segment is taken as it is written. A route not listed here, a malformed one, or an unknown kind
returns `null`.

| Route | Opens |
|---|---|
| `/home` | Home (nothing on top) |
| `/movie/<id>` | `MovieDetail` |
| `/series/<id>` | `SeriesDetail` |
| `/channel/<id>` | `ChannelView`, with the `Channel` found by id in `getChannels()`. Not in that list → `null` |
| `/browse/<kind>` | `Browse` for `BrowseKind` `all`, `movies`, `series`, `music` or `my_list`, lowercase as `toRoute()` writes them |
| `/search` | `Search`, as a fresh visit (empty field) |
| `/discover` | `Discover` on the segment a tap on the Discover tab picks today |
| `/discover/<movie\|tv>/<tmdbId>` | `DiscoverItem`. A `tmdbId` that is not an integer → `null` |
| `/discover/search` | `SeerrSearch` |
| `/upcoming/<id>` | `UpcomingDetail` |
| `/livetv-guide` | `LiveTvGuide` |
| `/settings` | `Settings` |
| `/profile`, `/account/profile`, `/account/password`, `/account/language` | `Profile`, `YourProfile`, `ChangePassword`, `AppLanguage` |
| `/support`, `/support/<number>` | `SupportList`, `SupportThread(number)`. DEB-1501's own rule applies: with the lane off, the screen returns as it does today when the flag goes |

These routes are **never** restored. The app opens where it would have opened with no URL:

- **A playback:** `/player/<id>`, `/livetv/<id>`, `/cast`, `/session`. A browser will not start sound without a tap,
  and the route carries none of what the player needs (the queue, the start point, the episode list).
- **A route that is not a place:** `/login`, `/profiles`, `/browse-seed` (R187 says the seed has no URL form),
  `/support/new` (a draft).
- **Every `/music/…` route and `/tv/now-playing`.** The web has no music engine. If a later change gives it one, that
  change adds these routes to the table.

**FR-3 — A restored page sits on top of Home.** With one cached session and a non-null `routeToDest`, the first stack is
`[Home, page]`. For `/home` it is `[Home]`. The page is the only thing pushed, two deep, even where the page is usually
reached through another one (Settings before Change password). The app's own Back from it goes to Home, exactly as Back
does from a page opened by a tap. The R292 player resume record is not consulted on this path. It is never set on the
web, because `rememberSaveable` does not outlive a reload.

**FR-4 — Signed out, or several profiles: sign in first, then the page.** With no cached session (Login), or with
several (the profile picker), the app opens on that screen as today, and the URL shows its route as today. The page
from FR-2 is held in memory for this load. The first time Home is reached afterwards, through `onSignedIn` or
`onProfileSelected`, the held page is pushed on top of it, with the name of the profile that just signed in. Then the
held page is cleared. It is also cleared, and never opened, if the viewer leaves Login through *Change server*. A reload
on the login screen therefore loses it, which is acceptable.

**FR-5 — A page that is gone, or not this viewer's, says so on the page.** A restored detail page whose load answers
404, 403 or any other failure shows that page's existing load error (R280: `GONE`, `FORBIDDEN`, `GENERIC`, …) with its
existing Back. Back leads to Home. Nothing is checked before the push, and no new screen or string is added. The one
exception is FR-2's channel, which is looked up first because `ChannelView` needs the whole `Channel`. A channel id the
list does not hold restores nothing, and the app opens on Home.

**FR-6 — Browser Back and Forward follow the URL they land on.** The `popstate` listener reads the hash it lands on:

- If that route equals the `toRoute()` of the page **under** the top of the stack, the listener calls `pop()` as today.
- Otherwise it opens that route as a load would. Through `routeToDest` (FR-2), it resets the stack to `[base, page]`.
  The base is the bottom of the current stack: Home, or the profile picker's `[ProfilePicker, Home]` pair. A route
  that restores nothing resets to the base alone.

Either way the URL is not written again (R80's `fromHistory` guard). This makes three things work that fail today:
Forward after Back reopens the page; Back after a reload walks the history the viewer had before reloading; and a
`#/…` edited by hand in the address bar opens that page.

**FR-7 — Browser Back from a pasted link leaves the site.** No history entries are invented at load. A pasted or
bookmarked link has one entry, so browser Back from it goes to wherever the tab was before, as on any web page. The
app's own Back (FR-3) goes to Home and rewrites that entry to `#/home` (R80's `replaceRoute` in `pop()`). After that,
browser Back leaves the site. This is decided, not asked: inventing a Home entry under the link breaks FR-6's
"Back walks the history you had" after a reload, and the in-app Back already offers Home.

## What the code makes cheap or expensive

- **Cheap:** every restorable page except a channel is constructed from its id and `displayName` alone. Each one's
  store already loads by id (`MovieDetailStore`, `SeriesDetailStore`, `DiscoverDetailStore`, `UpcomingDetailStore`,
  the support stores). Each already has an error state.
- **The one wait:** a channel needs `getChannels()` before the first stack can hold it. Open on `[Home]` and push the
  channel when the list answers. If the viewer has navigated before the list answers, do not push it.
- **Watch:** `initialStack` is a bare `remember {}` that both the cached-session switch and the R292 record feed. FR-3
  only adds a branch to it. Keep the R292 branch for the platforms that have one.

## Acceptance

Unit (`ravilo-ui` `commonTest`, which also runs on linuxX64 in CI, so **no commas in backtick test names**):

- For every `Dest` FR-2 lists, `routeToDest(d.toRoute(), name)` gives back an equal `Dest`. `ChannelView` is excepted
  because it needs the list. `Search` and `Discover` compare on type and `displayName`.
- Every route FR-2 excludes, a malformed `tmdbId`, an unknown browse kind and `""` give `null`.

**QA proof:** `Devices: Ravilo Web`. Any QA seat may take it; none is named. Use a test household's titles. A test plan
or comment never names a library title, only `<id>`s.

1. Signed in with one profile, open a movie page and copy the URL. Paste it into a **new tab**: the movie page opens.
   The app's Back goes to Home. Browser Back then leaves the site.
2. Home → a series → one of its rails' movies. **Reload**: the movie opens. Browser Back → the series. Browser Back →
   Home. Browser Forward → the series.
3. Edit the hash to a made-up `#/movie/<id>`: the movie page shows its load error, and Back → Home.
4. `#/player/<id>` and `#/music/artist/<id>`: the app opens on Home, and the URL becomes `#/home`.
5. Signed out, paste a series link: Login. Sign in: the series page opens over Home. Then with two profiles cached,
   paste it again: the profile picker. Pick one: the series opens for that profile.
6. `#/channel/<id>` for a channel on Home: the channel page. With a made-up id: Home.
