# Phase R304 — Profile is a page on the phone

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockup in `design/ravilo/Ravilo Mobile.html`
(`?tab=profile`), drawn the same day; owner answered the design questions before this was written.
**Dev-reviewed 2026-09-25 against `main` `e7991df3`** (see §Dev review at the bottom: open question 1 closes — the
viewer-side language write already exists and is per viewer; FR-R304-5 deletes more than it lists, because
the phone's Settings screen holds sign-out, unpair and the viewer settings too; the keys follow R279's
`profile.*` namespace; My List is the browse kind that already exists). Not built. Handset only (**R256**'s `isHandset` seam); the TV's avatar dropdown is
unchanged. Uses **R267**'s bottom bar, **187**'s photo and password screens, **R161/R162**'s viewer
language.

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
