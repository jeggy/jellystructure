# Phase R387 — Tapping the IMDb rating opens the title on IMDb

> Owner, 2026-10-15 (DEB-1288, with a phone screenshot of a series detail page): *"I would like this imdb section to
> be clickable (only with touch or mouse). It should not be focusable and navigating up or down around it should
> change nothing, should be exactly how it is now. But when pressed it opens this imdb item in the imdb website in
> your default browser etc. So in browser it just opens a new tab, but on mobile it will open the imdb app (if having
> one) or browser showing this exact item there."*

## Scope

Written 2026-10-15 (PO-authored from DEB-1288). Not dev-reviewed. Builds on R164 (the IMDb chip on the detail hero)
and Phase 131/158 (the stored rating, keyed by the title's IMDb id). Touches the three backend sites that build
`TvImdbRating`, one additive field in `shared`, `ImdbChip` and the two detail screens that place it, and one new
string. No design round: the chip draws exactly as it does today (D5).

## What is there today

- The backend already holds the id: `MediaItem.imdbId` (`tt` + digits) is the key Phase 131/158 fetch the rating by,
  and the admin's title page already links it as `https://www.imdb.com/title/<id>/` (`MediaDetail.kt`, the
  `imdb-pill` and the *id* row).
- The wire does not: `TvImdbRating` carries `aggregate_rating` and `vote_count` only, so no Ravilo client can know
  which IMDb page a chip belongs to.
- `ImdbChip` is a plain `Row` with a `contentDescription` — no click, no focus. It is placed on the Movie detail hero
  (`MovieDetailScreen`), the Series detail hero (`SeriesDetailScreen`), and the browse focus panel and focus line
  (`FocusDetailPanel`, `FocusDetailLine`).
- Ravilo already opens external URLs through Compose's `LocalUriHandler` (`AppUpdate.kt`, `SettingsScreen.kt`, the
  desktop trailer seam), always inside `runCatching`.

## Decisions

**D1 — Pointer only, never in the focus graph.** Compose's `clickable` makes its node focusable, which is exactly
what the owner ruled out. The chip takes a **tap detector** (a pointer-input gesture), not `clickable`, `focusable`,
`dpadFocusable` or a focus requester. A D-pad, a keyboard's arrows/Tab and Enter/OK never reach it, and nothing about
how focus moves on the page changes.

**D2 — The detail hero only.** The owner's screenshot is the detail page, and that is R164's chip. The browse focus
panel and the focus line keep their chip exactly as it is: they describe whatever card is focused or hovered, so a
press there would act on a card the viewer is pointing *past*, not at.

**D3 — Inert on a TV.** On a TV build (`isTvPlatform`) the chip gets no tap handler at all. A TV has no touch screen
and normally no mouse, most Android TV devices have no browser to open, and a press that leaves Ravilo for an app the
remote cannot drive is a break the viewer would notice. The TV chip is exactly R164's.

**D4 — The server sends the id; the client writes the URL in one place.** `TvImdbRating` gains the IMDb id, not a
URL, and one shared function turns it into IMDb's canonical title URL — the same URL the admin already uses. The id
is checked against `tt` + digits on both sides, so a malformed value can never become an arbitrary link.

**D5 — No visual change, so no design round.** The chip keeps R164's pixels: no ripple, no pressed colour, no
underline, no "open externally" glyph, and the hero's layout is unchanged. The one addition is the hand cursor under
a mouse (FR-R387-5), which is the platform's own affordance, not a design.

**D6 — The operating system picks the app.** Ravilo opens an ordinary `https://www.imdb.com/title/<id>/` URL and
stops there. On Android that is a `VIEW` intent: the IMDb app opens it if it is installed and Android routes
imdb.com links to it, otherwise the default browser does. On the web it is a new tab; on the Mac and Linux the default
browser. Ravilo never names a package, never deep-links a proprietary `imdb://` scheme, and never checks whether the
IMDb app is installed.

## Requirements

**FR-R387-1 — The wire carries the id.** `TvImdbRating` gains
`@SerialName("imdb_id") val imdbId: String? = null`. Every backend site that builds a `TvImdbRating`
(`DetailService`, `BrowseService`, `HomeFeedService`) sets it from `MediaItem.imdbId` when that matches
`^tt[0-9]+$`, and to null otherwise — so the type means the same thing wherever it appears. Additive only: an
installed client ignores the key (`RaviloWireJson` has `ignoreUnknownKeys`), and a new client talking to an older
server reads null (FR-R387-3).

**FR-R387-2 — One URL function.** `shared` gains one function from an IMDb id to
`https://www.imdb.com/title/<id>/`, returning null unless the id matches `^tt[0-9]+$`. It is the only place a Ravilo
client writes an IMDb URL.

**FR-R387-3 — A press opens the title.** On the Movie and the Series detail hero, when the chip's rating has a URL
(FR-R387-2 returns non-null), a **tap or a mouse click anywhere on the chip** — the IMDb mark, the rating and the vote
count — opens that URL through `LocalUriHandler`, inside `runCatching`. When there is no URL (no id, or an older
server), the chip is exactly R164's and a press does nothing. A failed open (no app can handle the URL) does nothing
visible: no error, no toast, no crash. For a series the URL is the show's page, matching R164's show-level rating.

**FR-R387-4 — Focus is untouched.** The chip is never a focus target (D1). On every platform:

- D-pad / arrow-key / Tab movement from and to every element around the hero meta row lands exactly where it lands
  today;
- Enter, OK or Space on any focused element never opens IMDb;
- a tap or click on the chip does not move focus. It is otherwise an ordinary press: R337's keyboard mode goes off on
  it as it does on any press (FR-R350-17), no more and no less.

**FR-R387-5 — The hand cursor.** On the desktop and web clients, a mouse over the chip shows the platform's hand
(link) cursor. Nothing else about the chip's appearance changes (D5).

**FR-R387-6 — Not on a TV.** When `isTvPlatform` is true the chip has no tap handler and no link semantics (D3).

**FR-R387-7 — Screen readers can follow it.** Where FR-R387-3 applies, the chip keeps `imdb.a11y` as its description
and gains the link role and a click action labelled by a new string `imdb.open`. This is accessibility semantics
only: it adds nothing to the D-pad / keyboard focus order.

| Key | en | da (draft) | fo (draft) |
|---|---|---|---|
| `imdb.open` | Open on IMDb | Åbn på IMDb | Opna á IMDb |

The note on the key: *"R387 — read aloud as the action on the IMDb chip on a title's page; never drawn."* The shipped
table wins over the drafts.

**FR-R387-8 — Tests.** Unit tests for FR-R387-2 (a well-formed id; null for blank, `tt` alone, a non-`tt` id, and
anything carrying a `/`, `?`, `#` or whitespace). A backend test that a title with a stored rating and id puts
`imdb_id` on the detail payload, and that a malformed stored id puts null. A wire test that a `TvImdbRating` payload
without `imdb_id` still decodes.

## Who it reaches

Everyone on the phone, the web and the Mac/Linux apps who opens a movie or series with an IMDb rating. TV viewers
see no change. An installed app on an older version sees no change until it updates; nothing it reads is altered.

## Acceptance

On a build with this phase on the server and on the client under test, with a title that shows the IMDb chip:

1. **Phone (Android).** On a series page, tap the chip: the IMDb app opens on that show if it is installed and
   Android routes imdb.com links to it, otherwise the default browser opens IMDb on that show. Back returns to the
   same Ravilo page. Repeat on a movie page.
2. **Web.** Click the chip: IMDb opens on that title in a **new tab**, and the Ravilo tab stays where it was. The
   cursor over the chip is the hand.
3. **Mac.** Click the chip: the default browser opens IMDb on that title. The cursor over the chip is the hand.
4. **Keyboard (web or Mac).** Walk the hero with the arrow keys and Tab, before and after: the focus order is
   identical, the chip is never highlighted, and Enter on the neighbouring elements never opens IMDb.
5. **Android TV.** D-pad up and down across the hero meta row lands exactly where it does on the release before this
   one; nothing new takes focus; OK never opens anything.
6. A title with no IMDb rating shows no chip, as today.
7. **Phone, TalkBack** (optional): the chip reads its rating, and its action reads *Open on IMDb*.

## Out of scope

- The chip on the browse focus panel and focus line (D2), and any IMDb badge on poster tiles.
- Links to any other source (TMDB, Rotten Tomatoes, a trailer site, a person's page).
- Any visible change to the chip (D5), and anything on a TV (D3).
- Choosing or detecting the IMDb app (D6).
