# A title page in the admin opens the title in Ravilo

Task: DEB-3733

> Owner, DEB-3733, 2026-11-05: *"I would like Ravilo to be in this list as well. Only possible when the "Ravilo Web
> address" is set in the jellystructure settings."* The list is the **External links ▾** menu on a film's or a
> series' page in the admin (today: Open in Jellyfin · View on TMDB · View on IMDb · View on TheTVDB). The owner
> marked it as not prioritised: a piece for the junior developer whenever she is free.

## What the admin does today

`MediaDetail.kt` builds the menu from four optional links. *Open in Jellyfin* is
`<jellyfin_url>/web/index.html#!/details?id=<jellyfinId>`, shown when `jellyfin_url` is set and the item has a
Jellyfin id. The menu is left out entirely when none of the four links exists.

The Ravilo Web address is `ravilo_web_url` (DEB-2233, FR-1). The admin already receives it in `GET /api/config`
(`AppConfig.raviloWebUrl` in `ConfigApi.kt`). `ServiceAddress.effective(raw, what)` in `commonMain` is what the
server treats as the address: trimmed, no trailing slash, and `null` when it is blank or fails validation.

Ravilo Web already opens a pasted link (DEB-1738, FR-2): `#/movie/<id>` opens a film's page and `#/series/<id>` a
series' page, where `<id>` is the item's Jellyfin id. A signed-out visitor signs in first and then lands on the page
(DEB-1738, FR-4). Ravilo Web has no route for a music video, an album, an artist or a book.

## Scope

- **The admin frontend only.** No backend change and no wire change: `ravilo_web_url` is already in the config the
  admin reads, and Ravilo Web already serves the routes.
- **No design round and no CSS.** The new row is one more `menu-item` in a menu that exists, with the same markup as
  its neighbours.
- **Films and series only.** The music pages (album, artist), the book page and a music video get no Ravilo row,
  because Ravilo Web has no page to open for them.
- **Out of scope:** opening the title in a native Ravilo app, or a specific episode.

## Requirements

**FR-1 — One function builds the link.** A new `commonMain` function returns the Ravilo Web address of a title, or
`null` when there is none:

```kotlin
object RaviloLink {
    fun titleUrl(raviloWebUrl: String, kind: MediaKind, jellyfinId: String?): String?
}
```

- The address is `ServiceAddress.effective(raviloWebUrl, "Ravilo Web address")`. When that is `null` (blank or
  invalid), the result is `null`.
- `jellyfinId` blank or `null` → `null`.
- `MediaKind.MOVIE` → `<address>/#/movie/<jellyfinId>`; `MediaKind.TV_SHOW` → `<address>/#/series/<jellyfinId>`;
  `MediaKind.MUSIC_VIDEO` → `null`.
- The id is written as it is, as DEB-1738's FR-2 reads it.

So `https://ravilo.example.org/` with a film `abc123` gives `https://ravilo.example.org/#/movie/abc123`, and an
address with a path, `https://example.org/ravilo`, gives `https://example.org/ravilo/#/series/abc123` for a series.

**FR-2 — The menu shows *Open in Ravilo*.** On a film's or a series' page, when FR-1 gives a link, the External
links menu has a row directly under *Open in Jellyfin* (or first, when there is no Jellyfin row):

- label **Open in Ravilo**, sub-line **Title page in Ravilo Web**, icon `↗`;
- `href` is FR-1's link, `target="_blank" rel="noopener"`, like every other row in the menu.

When FR-1 gives `null`, the row is absent. The menu itself appears when **any** of its five links exists, so an
address with no other link still shows the menu with one row.

**FR-3 — Both renders of the page carry it.** The page is drawn on open and drawn again after the TMDB id is changed
(the second `renderDetailView` call in `MediaDetail.kt`). Both read `raviloWebUrl` from the same `ConfigApi.get()`
they already read `jellyfinUrl` from, so the row never disappears after a TMDB id change.

## Proof

- `RaviloLinkTest` (`linuxX64Test`) covers FR-1: an unset address, an invalid address (`ftp://x`), a trailing
  slash, an address with a path, a film, a series, a music video, and a missing id.
- FR-2 and FR-3 are read on review. The admin frontend has no DOM test harness.
