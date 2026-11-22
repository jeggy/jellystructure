# Every card in the Library grid is a real link

Task: DEB-5345

> Owner, 2026-11-23 (DEB-5345): *"Let's make the items on this page use proper a href links, so that I can easily
> right click to copy link or use cmd + click to open in new tab etc. Currently it seems like it's made with
> javascript, so using cmd + click, just opens it normally"* — about `#/library`.

## Scope

Admin frontend only. No route, no DTO, no schema, no CSS, no Ravilo client.

**What is wrong today.** The film and series grid (`#poster-grid`, `renderLibrary` in
`src/wasmJsMain/kotlin/dev/jellystructure/ui/Library.kt`) draws each card as `<div class="poster" data-id="…">`.
One delegated click listener on the grid then calls `App.navigate("/media/$id")`. A `<div>` has no address, so
the browser offers no *Copy Link* or *Open in New Tab*. A Cmd/Ctrl-click or middle-click is an ordinary click to
the listener, and the item opens in the same tab.

**What is already right.** The Music and Audiobooks kinds of the same page draw every album, artist, song, book
and author as `<a class="mu-cell" href="#/album/…">` (and `#/artist/…`, `#/audiobook/…`,
`#/audiobook-author/…`). `app.css` styles `.poster` as an anchor already (`text-decoration: none;
color: var(--ink)`). The film and series grid is the only part of the page that is not a link.

**Why a new tab works.** The admin session is the `js_session` cookie, and a new tab on the same origin sends it.
The hash router renders `#/media/{id}` on a cold load as it does on a click.

## Requirements

**FR-1 — a card is an anchor to the item's page.** Each card in the Library's film and series grid is
`<a class="poster" href="#/media/{id}" data-id="{id}">`. `{id}` is the same id the click uses today
(`item.jellyfinId ?: item.id`), attribute-escaped. The card's contents are unchanged: the image slot, the title,
the year and the status badge.

**FR-2 — the browser follows the link; nothing intercepts it.** The delegated `click` listener on `#poster-grid`
that calls `App.navigate("/media/…")` is removed. The capture-phase `error` listener for the poster fallback stays.
As a result:
- a plain left-click opens the item in the same tab, as today, and Back returns to the Library;
- Cmd-click (macOS), Ctrl-click and middle-click open it in a new background tab, and Shift-click opens it in a
  new window;
- the context menu offers the browser's own *Open Link in New Tab* and *Copy Link*. The copied address is the
  full URL (`https://<host>/#/media/{id}`);
- hovering a card shows the address in the browser's status bar.

No code may later call `preventDefault()` on a click inside the grid when the click is modified (Meta, Ctrl, Shift
or a non-primary button).

**FR-3 — every way a card reaches the grid draws the same anchor.** Paginated and infinite-scroll slices
(`appendPosterCards`) and cards a live scan adds or replaces (`appendItemToGrid`) all use one card function. A card
that arrives by the scan socket is a link too. The function lives in `commonMain`, next to `SeasonNameHtml.kt`, for
the reason that file gives: the markup is the requirement, and a `linuxX64` test can pin it.

**FR-4 — nothing interactive inside a card.** An `<a>` may not contain another `<a>` or a `<button>`. The card
holds only text, an `<img>` and `<span>` badges, and stays that way. A future control on a card (a menu, a tick)
goes outside the anchor or turns the card into a container with a separate link.

**FR-5 — it looks the same.** No CSS change. The inline `style="cursor:pointer;"` on the card is dropped, because
the anchor and `.poster { cursor: pointer }` already provide it. Hover lift, the poster-placeholder fallback and
the `nth-child` tints are unchanged.

## Not in this piece

- The Music and Audiobooks grids. They already satisfy the owner's ask and are not touched. In Music's
  selection mode, a click on an album still toggles the tick (`muClick` calls `preventDefault`). That is by
  design.
- The command palette (`Shell.kt`) and the post-action redirects (bulk reorder, segment editor). They are not
  items on this page.
- Opening a card in a new tab while signed out goes through the existing sign-in flow. This piece promises
  nothing about where that flow lands.

## Proof

`PosterCardHtmlTest` (`linuxX64Test`) pins FR-1, FR-4 and FR-5 on the card function: the root element is
`<a class="poster" href="#/media/{id}"`; `jellyfinId` is preferred over `id`; a quote in the id or title is
escaped; the card contains no nested `<a` or `<button`; and there is no inline `cursor`. FR-2 is read in the
diff (the listener is gone). On a real browser, QA Cmd-clicks, middle-clicks and right-click → *Copy Link* on a
film card and on a series card.
