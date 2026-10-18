# The admin removes an unused stored logo

Task: DEB-1788

> Tróndur, 2026-10-18 (DEB-1781, filed as DEB-1786): *"Rule on the channel-logo Remove: yes. Spec it, then queue the
> build."* The stored-logo picker has no delete route. Every logo-upload QA pass adds tiles that every admin sees for
> good.

## What happens today

A logo uploaded in the admin is stored by `ChannelLogoStore` as `<config>/channel-logos/<ms>-<name>.<ext>`. It is
served publicly at `/api/tv/channel-logos/<file>`. `GET /api/tv/admin/channel-logos` lists the directory, newest
first. Two admin surfaces show that list as clickable tiles:

- the collection editor's *Uploaded logos — click to use* (`RaviloConfig.kt`, `#ch-logo-library`), and
- the Workbench's channel section, the logo grid with the *Upload* tile (`Workbench.kt`, `.wbc-tile`).

Clicking a tile copies its URL into the logo field. Nothing records which logos are in use. There is no index
besides the directory itself, and no route removes a file, so an upload stays in both pickers until someone deletes it
on the host.

A stored logo's URL can be kept in two places:

| Where | Field | Store |
|---|---|---|
| A collection, in the global layout or in any per-user layout | `ChannelConfig.logo_url` | `ravilo_config` rows (`__global__` and one per user with their own layout) |
| A Live TV channel override | `LiveTvChannelOverride.logo_override_url` | the Live TV store |

No other stored field holds a channel-logo URL.

## Decisions

- **Only an unused logo can be removed.** A logo that is in use is refused, and the refusal names who uses it.
  This is Tróndur's lean. A guard is cheap here, because the two places a URL can live are both known.
- **Removal deletes the file.** The picker *is* the directory listing. "Only drop it from the picker" would need a new
  hidden-list state and would leave a public URL serving a file nobody can see. Nothing uses the file once the guard
  passes, so deleting it breaks nothing.
- **No design round.** The ✕ is the admin's existing remove idiom (`.pipe-x`, `.cw-chip .x`), and `window.confirm` is
  how the admin already confirms a destructive action (revoking a device, deleting a tag or a tracker). One new
  class goes into `design/app/wf.css`.

## Requirements

**FR-1. A route removes a stored logo.** `DELETE /api/tv/admin/channel-logos/{name}` needs an admin session (the same
check as the list and upload routes; otherwise `401`). `{name}` is the stored file name, the last segment of the
logo's `url`. It answers:

- `204 No Content`: the file existed, nothing uses it (FR-2), and it has been deleted.
- `404 Not Found`: no such stored logo. A name with `..`, `/` or `\`, a `.tmp` file, or an extension outside the
  store's allowed set is answered the same way, and nothing is touched.
- `409 Conflict`: the logo is in use. The body is `{"error": "<sentence>", "usedBy": ["<user>", …]}`. The sentence
  names every user, for example *"In use by the collection ‘Collection A’ (everyone's layout) and the Live TV
  channel ‘Channel 3’ — pick another logo there first."* Nothing is deleted.

The removal is logged at info, like the upload (`Channel logo removed: <file>`).

**FR-2. "In use" is decided on the server, at the moment of the request.** A stored file `F` is in use when either
of these holds a value that, without any query string or fragment, ends in `/api/tv/channel-logos/F`. That catches
a relative URL and also an absolute one pasted with the host.

- any collection's `logo_url` in the global layout or in any per-user layout, or
- any Live TV channel override's `logo_override_url`, whether Live TV is switched on or not.

Each user is named in the form the admin shows:

- a collection by its name, and by its layout: *everyone's layout* for the global one, or the user's Jellyfin name
  for a per-user one, falling back to the user id when the name cannot be resolved;
- a Live TV channel by its display name: the admin rename, else Jellyfin's name, else *Channel N*, the order
  `LiveTvService` already uses.

**FR-3. The list says who uses each logo.** Every entry from `GET /api/tv/admin/channel-logos` gets a new field,
`usedBy: List<String>`, empty by default. It holds the same names FR-2 builds, computed on each list request.
This is additive on `ChannelLogo`, like `liveText` (DEB-1511 FR-2). No installed client breaks, and the TV never
reads the admin list.

**FR-4. A tile in either picker that nothing uses has a ✕.** In the collection editor's library and in the Workbench's
logo grid:

- A tile whose `usedBy` is empty gets a small **✕** button on its top-right corner, in the new `wf.css` class
  `.logo-x`, modelled on `.pipe-x` (dim ink, `--bad` on hover). The ✕ is its own `<button>` next to the pick button,
  not inside it, so a click on the ✕ never picks the logo. Its `title` and `aria-label` are *"Remove this logo"*.
- A tile whose `usedBy` is not empty has no ✕. Its `title` adds *"In use by …"* with the names.
- The tile whose URL is in the editor's logo field right now (the ringed tile, `.on` in the Workbench) has no ✕ either,
  even when nothing saved uses it yet. A logo that was just picked and not saved must not be removable from under the
  editor.
- In the Workbench, the live-text caution ⚠ (DEB-1511 FR-2) moves from the top-right corner to the bottom-right, so
  that it and the ✕ do not overlap. The caution itself does not change.

A click on the ✕ asks first: `window.confirm("Remove the logo “<label>”? The file is deleted from the server, and this
can't be undone.")`. On OK the admin calls FR-1, then re-renders the picker from a fresh `GET`. Nothing is dropped
from the screen before the server answers. That keeps the admin's rule of rendering server-pushed state only.

**FR-5. A refused removal is shown next to the picker.** If FR-1 answers `409` (another admin, or another tab, put the
logo to use since the list was read) or any other error, a `alert-bad` line *"Couldn't remove: <server error>"*
appears directly above the tiles. The picker re-renders from a fresh `GET`, so the tile shows its new users. The
line goes away the next time the picker renders after a successful action. It is separate from the editor's
*Upload failed:* line (DEB-1511 FR-1/FR-3), which keeps its own rules.

## Non-goals

- **No refusal on layout save for a missing file.** A layout saved from an editor that was opened before a removal
  can still point at a deleted logo. FR-4's ring rule covers the editor that has the logo picked. A stale second
  tab is accepted, and Ravilo already treats a logo that 404s as no logo.
- **No bulk removal and no "remove all unused".** One tile, one confirm.
- **No client change.** Ravilo never lists stored logos. It only loads the URLs that the layouts name.
- **No Live TV editor picker.** The Live TV override field stays a typed URL. It is only *read* by FR-2.

## Verification

- FR-1/FR-2: a backend test against a temp store and a test DB. Removing an unused logo gives 204 and the file is gone.
  An unknown name, `../x.png` and `a.tmp` each give 404 and touch nothing. A logo named by a global-layout collection,
  by a per-user-layout collection, or by a Live TV override (both relative and absolute URL) gives 409, the body
  names that user, and the file is still there.
- FR-3: the same test lists the store and expects `usedBy` filled on the used logo only.
- FR-4/FR-5: the existing `ravilo-channel-logo-upload.spec.ts` gains one case. Upload a path-only SVG: its tile shows
  a ✕. Accept the confirm: the tile is gone after the re-render. A tile in use shows no ✕, and its title names the
  collection.
- QA on prod, after deploy, with the admin in a browser and `prod-backend`. Run the three cases above against the
  real store, and check that a removed logo's public URL answers 404. That QA run also removes the three test
  uploads that DEB-1645's *build-candidates* note lists from DEB-1597 and DEB-1627, **through the route**. The
  fourth file that note names, the outlined SVG from 2026-10-16, is DEB-1511's converted logo, waiting for the owner
  to pick it. It is not a test upload, and QA leaves it.
