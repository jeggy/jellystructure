# Phase R304 — Profile is a page on the phone

## Scope

Written 2026-09-25 from the owner's ask and the mockup in `design/ravilo/Ravilo Mobile.html` (`?tab=profile`),
drawn the same day; owner answered the design questions before this was written. **Dev-reviewed 2026-09-25
against `main` `e7991df3`**; built 2026-09-25 from that review (all five items) — see *Build* below.
Handset only (**R256**'s `isHandset` seam); the TV's avatar dropdown is unchanged. Uses **R267**'s bottom bar,
**187**'s photo and password screens, **R161/R162**'s viewer language. Amended 2026-10-14 (FR-R304-7, My List
moves the page while it loads — DEB-1174). Phase status lives in `STATUS.md`.

### Build (2026-09-25)

- `Dest.Profile` (+ `scrollTick`) and `Dest.AppLanguage` on the stack; `bottomItemOf` lights PROFILE, so the
  pill slides under it and R275's Back-to-Home ladder covers it (FR-R304-1). One `openProfile()` behind the
  nine AppBar avatar sites: the page on a handset, R170's dropdown on a TV — the phone's dropdown is gone by
  construction (FR-R304-5). The lit avatar in the bar gets the white ring (2 dp gap, 1.5 dp).
- `ProfileScreen.kt` (new): photo (tap → 187's photo screen, no separate row) + name + *Admin* from the
  session's `is_admin`; **My List** row from `browse(kind = "mylist", pageSize = 12)` with the count and *See
  all* → `Dest.Browse(MY_LIST)` (item 4), or FR-R304-2's empty sentence; **Account**: *App language* (current
  endonym) → `AppLanguageScreen`, *Change password*, and a **Settings** row that pushes the existing screen
  (item 2 — it still holds skin, autoplay, tile shape, unpair); **Sign out** with FR-R304-3's sheet
  (`HandsetSheet`, R191's `signOutActiveSession`); the `Ravilo {version} · signed in to {host}` line.
- `AppLanguageScreen`: endonyms from `SUPPORTED_LANGUAGES`, a check on the current one, the one note, and the
  choice written through `putViewerSettings(uiLanguage)` + `LastLanguage.remember` — the per-viewer setting
  (item 1; *this phone only* dropped).
- Ten `profile.*` strings × en/da/fo (item 5), lexicon regenerated with `--update-lexicon` and committed;
  `ProfileStringsTest` (3): every key resolves in every language and none names Jellyfin (FR-R304-6).
- No backend change. **Verified on the Pixel 9 (debug build, 2026-09-25 07:18):** the avatar opens the page
  and the pill slides under it with the ring; photo, name, *Admin*; My List (8) with *See all*; the three
  Account rows; Sign out opens the sheet with FR-R304-3's copy; App language lists the three endonyms with the
  check on the current one; Back from App language returns to Profile, and from Profile to Home. One bug found
  and fixed on the way: Back with the sheet open went to Home (R275's ladder) instead of closing the sheet — the
  screen now owns Back while the sheet is up.

> *"When on Ravilo mobile and clicking on the profile picture, we get a super ugly and buggy dropdown. On
> TV this dropdown is good. But on mobile it's not really working."*

## Why a page

The TV dropdown is a short column of focus stops over the page — right for a remote. On a phone it floats,
clips under the bottom bar and closes on scroll. Profile is already the fifth bottom-bar item; it becomes a
page like the other four.

## Functional requirements

**FR-R304-1 — the tab is a page and takes the pill.** Tapping the avatar in the bottom bar opens the
Profile page and slides R267's pill under it. The lit avatar gets a white ring (2 dp gap, 1.5 dp ring) so
a photo or initials never sit on the gradient unreadably. Tap-on-active scrolls to top (R267's rule).

**FR-R304-2 — the page, top to bottom.**
1. The viewer's photo (76 dp, with an edit badge) and name; below the name, *Admin* for an admin,
   nothing otherwise. **Tapping the photo opens 187's photo screen** — there is no separate *Your photo*
   row (owner decision).
2. **My List** — a poster row with a count and *See all ›* (opens a grid). Empty: *Nothing here yet. Tap
   ＋ My List on any title and it waits for you here — and on your TV.*
3. **Account** — *App language* (shows the current one) and *Change password* (187's screen).
4. **Sign out** — a full-width button, red outline.
5. `Ravilo 1.38 · signed in to {server host}` — one quiet line.

**FR-R304-3 — sign out asks first.** A bottom sheet: *Sign out of this phone?* / *Your TVs and other
devices stay signed in. To watch here again you sign in with your name and password.* / **Sign out** ·
*Stay signed in*. It is the only sheet on this page.

**FR-R304-4 — App language.** A pushed screen listing *English · Dansk · Føroyskt* (endonyms, R203's
list), a check on the current one, and one line: *Menus and buttons only. Audio and subtitles keep
following your profile's languages.* Choosing returns to Profile with *App language: Dansk.* Menus
redraw in place.

**FR-R304-5 — what is gone.** The phone's avatar dropdown/sheet and its Settings screen (which held only
photo and password) are deleted. **No *Switch profile*** on a phone (owner decision: a phone is one
person's). No *Unpair*.

**FR-R304-6 — no Jellyfin in any viewer-facing string.** Owner rule, applied to this page and its sheet:
the role reads *Admin*, the sign-out copy says *your name and password*.

**FR-R304-7 — My List never moves the page while it loads** *(amendment 2026-10-14, DEB-1174, see below)*.
From the moment the Profile page draws, nothing below the My List heading — Account, Sign out, the version
line — changes position because the list arrived. On the phone and in R337's desktop layout alike.
1. **Until the first answer, the slot is the loaded row's height.** It holds placeholder tiles with the
   geometry of a loaded tile — the poster box, the 8 dp gap and the one title line under it — taken from
   the same size source the loaded row uses, so the two cannot drift apart again. A plain poster shape in
   the surface colour; no text, no spinner. (Composing the row's own `Tile` with no title and no image is
   one way to get that by construction.)
2. **The heading line has one height.** The count and *See all ›* reserve their space before the count is
   known; they appear, the line does not grow.
3. **The page remembers the last list it read, for this viewer, while the app runs** — R40's kept-store
   pattern, keyed by the viewer so a second profile on the same device never sees the first one's list.
   A second and later visit draws that list on its first frame and refreshes in place, the way R364's My
   List page does. A refresh that changes which titles are in the list changes the tiles, not the row's
   height. What is drawn is always the server's last answer — nothing is added or removed locally.
4. **The one height change allowed** is the first read of an app run coming back **empty**: the reserved
   row gives way to FR-R304-2's empty sentence, once. A remembered empty list draws the sentence at once.
   A failed read keeps today's behaviour (the empty sentence) and is not remembered.
5. **What a loaded tile looks like does not change in this fix** — the slot is made to match it, not the
   other way round (see *Found 2026-10-14* for the poster's shape, which is a separate question).

**New strings × en/da/fo:** `pf_mylist_empty`, `pf_account`, `pf_app_language`, `pf_app_language_note`,
`pf_signout_title`, `pf_signout_body`, `pf_signout_confirm`, `pf_signout_cancel`, `pf_signed_in_to`,
`pf_lang_changed`. Reuse existing keys for *My List*, *See all*, *Change password*, *Sign out*, *Admin*.

## Open questions

1. **App language: this phone, or this viewer?** The mockup says *this phone only*, but the shipped setting
   (R161, overlay field `ui_language` since R162) is **per viewer** and follows them to every device. Lean:
   reuse the existing per-viewer setting and drop *this phone only* from the row — one setting, not two. If
   the owner really means per-device, it is a new local preference that R279's resolution order must rank
   above `RaviloConfig.uiLanguage`.
   **Closed — dev review item 1: per viewer, through the write that exists.** `SettingsScreen.saveUiLanguage`
   (`:111-118`) already calls `putViewerSettings(uiLanguage)` → `applyViewerSettings` (`TvRoutes.kt:1223`,
   `RaviloConfigService.kt:245`), remembers it in `LastLanguage`, and the config re-pull re-sets
   `WithLocale`. The phone's row calls the same thing; *this phone only* comes off the mockup.
2. My List on the phone: the phone had no My List view before (the old menu sent it to Discover). Confirm
   `GET /api/tv/mylist` (or whatever the TV reads) serves the phone unchanged.
   **Closed — dev review item 4:** My List is `BrowseKind.MY_LIST("mylist")` (`BrowseScreen.kt:70-74`), a
   kind of the shared browse page — `Dest.Browse(BrowseKind.MY_LIST, …)` is the *See all*, and the row on
   the Profile page reads the same endpoint with a small limit.

## Acceptance

On a handset the avatar opens a page, not a menu; the pill sits under it; the photo opens the photo
screen; Sign out asks first; there is no Switch profile and no *Jellyfin* anywhere on the page. On a TV
nothing changes.

**FR-R304-7 (2026-10-14).** Unit: the My List section measures the same height loading and loaded with
titles, in the phone and the desktop layout, and its heading line measures the same with and without a
count. Device (the Pixel, `.debug` of `main`, a viewer with titles in My List), recorded on screen: after a
fresh launch, tap Profile — the *Account* heading is at the same place on the first frame and once the
posters are in; leave to Home and tap Profile again — the posters are there on the first frame. A viewer
with an empty My List: at most one change, on the first visit of the run, and none on the second.

## Dev review (2026-09-25, against `main` `e7991df3`)

The complaint is where the spec says: on the phone the avatar opens `profileMenuOpen`, an overlay that is
not part of the navigation stack (`RaviloApp.kt:457`, `:924`), which is why Back needed its own
special-casing (`:748-768`) and why it closes on scroll. Five items.

1. **Open question 1 closes: reuse the per-viewer setting.** The viewer-side write exists and is what the
   TV's Settings already uses: `saveUiLanguage` → `putViewerSettings(uiLanguage)` (`SettingsScreen.kt:111-118`)
   → `applyViewerSettings` (`TvRoutes.kt:1223`, `RaviloConfigService.kt:245-253`), with `LastLanguage`
   remembering it across sign-out and `WithLocale` redrawing on the config re-pull (`:110`). The phone's
   *App language* row is that call; *Menus redraw in place* is already true. No new preference, no
   change to R279's ladder.
2. **FR-R304-5 deletes more than it names.** The phone's Settings screen does not hold "only photo and
   password": it holds sign-out (`SettingsScreen.kt:269-277`), unpair (`:284-286`, `:362-364`), the
   language (`:111`) and the viewer settings `applyViewerSettings` accepts — skin, continue-progress,
   autoplay-next, tile shape (`RaviloConfigService.kt:245`). Deleting the screen orphans those. Either the
   Profile page gains a *Settings* row that pushes the existing screen (least work, keeps R229/R234's
   phone layout), or the FR lists each setting and where it now lives. Lean: the row.
3. **Sign-out's copy is true by construction.** `signOutSession` is `DELETE /api/tv/sessions/{userId}`
   (`TvApiClient.kt:463-464`) against this device's token; tokens are per `(device, user)` (141), so the
   TVs really do stay signed in. The role label is available: the session carries `is_admin`
   (`Models.kt:48`). Nothing to build for FR-R304-6 beyond the strings.
4. **My List is the browse kind that already exists** (`BrowseKind.MY_LIST`, `BrowseScreen.kt:74`);
   *See all* pushes it, the Profile row reads it with a limit. Open question 2 closes.
5. **Keys follow R279's namespace, and the lexicon rides with them.** `i18n/en.json` has 365 dotted keys
   (`profile.who`, `profile.switch`, …) and six legacy underscored ones; the ten new strings are
   `profile.mylist_empty`, `profile.account`, … not `pf_*`. R288's `i18n/lexicon/*.txt` is ground truth
   for the Faroese and Danish spellings — regenerate with `--update-lexicon` and commit it with the
   strings, or `check-i18n-spelling.sh` goes red.

**Small corrections.** The TV's dropdown is untouched because the gate is `isHandset` (R256), not the
window: a narrow browser is not a phone and keeps the TV path. FR-R304-2's *Admin* under the name reads
from `is_admin`, never from a Jellyfin policy fetch.

**Net effect.** A `Dest.Profile` page on the handset stack replacing `profileMenuOpen` there, a Settings
row rather than a deleted screen, the language row on the existing write, ten `profile.*` strings ×
three languages plus the lexicon, and the avatar ring in the bottom bar. No backend change.

## Found 2026-10-14, amended 2026-10-14 (FR-R304-7, DEB-1174)

> *"On mobile and clicking the profile tab it provides bad ui. As the my list is loading and then it hops in
> its height so everything below it moves, which is super annoying"* — the owner, 2026-10-14.

Decided from the code at `main` `8134a340`, without a device repro; FR-R304-7's acceptance is the device check
after the build.

- **The placeholder is shorter than what replaces it.** `ProfileScreen` holds `myList` in a plain `remember`
  and reads it in a `LaunchedEffect(Unit)`, so every arrival on the page starts from *unknown*. While unknown
  the phone draws an empty `Box` of `TILE_HEIGHT` = 168 dp (the desktop branch: 207 dp). A loaded tile is
  taller on both: `Tile` draws the poster **plus an 8 dp gap and a title line** under it, which the
  placeholder leaves out; and on the phone the poster's own height is not 168 dp — `Tile` sizes it from the
  TV's 232 dp × the operator's `ui_density` scale (0.82 / 0.91 / 1), and the 112 dp box clamps only its
  width. By reading, the row grows by roughly 45–90 dp on the phone and ~25 dp on the desktop when the
  posters arrive, and Account, Sign out and the version line move down by that much.
- **The heading line grows too.** *See all ›* (13 sp, 6 dp vertical padding) is laid out only once
  `myListTotal > 0`, so the line beside the 17 sp heading gains a few dp in the same frame.
- **An empty list moves it the other way.** The empty sentence is shorter than 168 dp, so for a viewer with
  nothing in My List the page shrinks on arrival instead.
- **Every visit repeats it.** Nothing is kept between visits (unlike R40's kept stores and R364's My List
  page), so tapping Profile, leaving and coming back shows the jump again.
- **Not a cause:** R321's mode card above My List depends on the music answer, which the app asks for at
  launch, not on opening Profile.
- **Seen on the way, not changed here:** by the reading above, the phone tile's poster is 112 dp wide and
  190–232 dp tall — not 2:3 — because `Tile`'s width yields to the 112 dp box and its height does not.
  Whether that is what the Pixel actually shows needs a look on the device before anyone calls it a defect;
  if it is one, it is its own amendment, since it changes what a viewer sees.
