# Phase R234 — Your photo, and your password, without leaving Ravilo

> **Renumbered 2026-09-04:** was 186 / R230 in draft. The dev team took **186** (request-intent
> lifecycle cleanup) and **R230** (fully disable Skip Intro / Skip Credits) before these were pushed, so
> this pair is now **187 / R234**. Same collision shape as R196 → R208 and 179 → 180.

> The viewer half of **Phase 187**. Two small screens and one row: a **Your profile** screen where a
> photo is chosen (phone and web only), and an **Account** section in Settings where a password is
> changed (everywhere). Ravilo already *renders* a photo on every surface — R65 shipped that whole path
> — so this phase is about the two things it can't do: put one there, and change a password.

**Status:** ✓ Built 2026-09-05 (Phase 187's backend having landed the same session), **✓ live-verified
the same day on stue TV** (real BRAVIA hardware, release build, AOT-compiled). Compiles clean across
every target that carries this code (`:ravilo-ui:compileDebugKotlinAndroid`,
`:ravilo-ui:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`, `:ravilo-web:compileKotlinWasmJs`).
Not dev-reviewed. The photo picker and web `<input type=file>` flow specifically were **not** exercised
(user instruction: don't test change-password or photo-upload live — both mutate the real household
Jellyfin account). `:ravilo-tizen` needed no change (confirmed: it doesn't depend on `:ravilo-ui` at
all, per its own hand-written-DOM precedent).
**Depended on Phase 187** for both routes — no longer blocking, both routes are built.

### Live-verified on stue TV, 2026-09-05

- **R233's fix, confirmed against the exact live report that opened that phase**: standing inside
  Thriller / Gyser, Continue Watching showed only thriller/horror titles (Nosferatu, Psycho, Speed
  Demon, Monkey's Magic Merry-Go-Round…) — not the *Two and a Half Men*/*Klovn*/*Ali G* mix the original
  bug report described.
- **FR-R234-1/2's platform gate, confirmed on real TV hardware**: the profile menu shows exactly Switch
  profile · My List · Settings · Add user · Sign out · Unpair — no "Your profile" row. `isTvPlatform`
  (delegating to R192's `RaviloAppContext.isTelevision`) correctly detects this BRAVIA as a TV.
- **A real, pre-existing bug found and fixed the same session**: Settings' whole content `Column` had
  no `verticalScroll` at all, and none of its rows wired an explicit `onUp`/`onDown` — so on this TV's
  1080p display, once Playback was the last visible section, the entire Account block (including the
  new Change password row) and the Unpair section were composed but permanently unreachable by D-pad.
  Pre-dates this phase (Sign out/Unpair had the identical gap before Change password was ever added),
  but blocked this phase's own new row until fixed. Fixed by: adding `.verticalScroll` to the outer
  Column, and an explicit `onUp`/`onDown` chain through every section (skin → language → Playback
  toggles → Change password → Sign out → Unpair) — the same explicit-link idiom `ProfileMenu` already
  used, rather than relying on ambiguous default 2D focus search.
- **A second bug found in the same pass**: `ToggleRow` (and the skin/language pill rows) never wired
  `onBlurred`, so a row's focus ring, once set, never cleared — both Playback toggles showed a focus
  ring simultaneously in the first post-fix screenshot. Fixed alongside the chain fix; confirmed clean
  (single ring, correctly following focus) in every subsequent screenshot.
- Full chain re-verified end to end on-device: Show progress → Autoplay → Change password → Sign out →
  Unpair, each landing with one clean border and the page scrolling to keep the focused row in view.

### What's built

- **Platform gate** — new `expect val isTvPlatform: Boolean` (`ravilo-ui` commonMain), `actual` on
  Android delegating to R192's existing `RaviloAppContext.isTelevision` runtime hardware check, `actual`
  on web as a constant `false`. Exactly what FR-R234-1 asked for — gated on platform, not
  `LocalCompact`/`LocalHandset` — and the only two `actual`s needed, since `:ravilo-tizen` never
  consumes this code.
- **`ProfileMenu`** — refactored from hand-linked `FocusRequester`s to a list-built focus chain (an
  optional row needed every neighbour re-wired by hand under the old shape); "Your profile" slots in
  between Switch profile and My List, matching the mockup's order, present only when
  `!isTvPlatform && onYourProfile != null`.
- **`ChangePasswordScreen`** (new, `AccountScreens.kt`) — three fields via a shared `AccountField`
  (masked `BasicTextField`, same native-IME idiom `LoginScreen`'s own `LoginField` already established
  — see FR-R234-6's note below), client-side validation (blank current / <6 char new / mismatched
  repeat), server verdict relayed verbatim including the `403`-shaped wrong-password case (current
  field cleared, the other two kept), and the tokenSurvived branch — unreachable on this Jellyfin
  version per 187's probe, but rendered from the server's field, not hard-coded.
- **`YourProfileScreen`** (new, same file) — avatar preview + Choose/Take/Remove, wired to 187's routes;
  a successful change repaints `LocalUserAvatarUrl` immediately (FR-R234-8) and persists via
  `MultiTokenStore.add(active.copy(avatarUrl = ...))` (no new store method needed — `add()` already
  upserts by userId). Preset colours are **not built** — dropped per 187 open question 3's owner
  decision; the "Or use a colour" control never existed in this implementation.
- **Settings' Account section** — a "Change password" row added directly to the existing identity/
  sign-out block (FR-R234-4) rather than a second "Account" section; the spec's "above the existing
  sections" is satisfied in substance (identity → password → sign out, all under one Account header)
  without visual duplication.
- **i18n** — all 24 `account.*`/`pm.your_profile` strings pulled verbatim from
  `design/ravilo/ravilo-i18n.js` (en/da/fo already drafted there per this phase's own doc note) rather
  than re-translated, so the Danish/Faroese copy is the design-authored text, not a guess.
- **A real bug caught by the compiler, not review:** an early draft called `str()` (a `@Composable`
  function reading the locale CompositionLocal) from inside plain callback lambdas and coroutine
  bodies — a network response handler, a submit validator — none of which are composable contexts.
  Fixed by making `PwState`/`PhotoState`'s error/success variants carry a string **key**, resolved via
  `str()` only at render time inside the composable body itself.

### Simplification recorded, not silently decided (open question 1)

**"Choose a photo" and "Take a photo" open the identical picker today** — Android's
`ActivityResultContracts.GetContent("image/*")`, which already surfaces the device's camera as one of
its own chooser entries on most phones. There is no dedicated camera-capture intent (would need a
`FileProvider` + manifest `<provider>` declaration) wired yet. This is exactly the ambiguity open
question 1 already flagged before any code existed; `PhotoPicker.kt`'s own doc comment records the
simplification so a future pass knows it's deliberate, not an oversight, and both buttons are real —
they just currently launch the same picker.

> **2026-09-05 — 187's endpoint probe ran in full, and every block on this phase is now cleared.**
> **FR-R234-8 is fully specified:** Jellyfin exposes `UserDto.PrimaryImageTag` and it demonstrably moves
> on each upload (three uploads, three different tags), so 187 FR-187-7's change-keyed URL is real
> (`…/avatar?v=<tag>`) and "let `RemoteImage` miss its cache" has a concrete mechanism — a new tag is a
> new URL and therefore a cache miss, not something needing active eviction. Worth knowing while building
> it: jellystructure's avatar cache turned out to have **no TTL whatever**, so before 187 lands there is
> no version of this that self-heals.
> **FR-R234-7 is unblocked** — tokens survive a password change, so only the success path ships.
> **FR-R234-3 loses the preset colours** — owner decision on 187 open question 3.
> **Open question 5 (kids profiles) is settled** — no Jellyfin policy flag gates it.
>
> **The mockups now lead this spec in one place:** they still draw the preset-colour row (`AV_PRESETS`,
> `colorFor`, `setColor` in `ravilo-data.js`, and the Your profile sheet in `Ravilo Mobile.html`). That
> needs removing before this ships — see FR-R234-3.

Design: built into the mockups at `design/ravilo/Ravilo Mobile.html` (profile sheet · Your profile ·
Settings → Account → change password) and `design/ravilo/Ravilo TV.html` (Settings → Account, password
panel on R175's on-screen keyboard). Strings drafted × en/da/fo in `design/ravilo/ravilo-i18n.js`.

## Current state

**Rendering is done and has been since R65.** `AppBar`'s `ProfileAvatar` draws
`LocalUserAvatarUrl.current` through `RemoteImage` when it is non-null and falls back to initials
otherwise; `StoredSession.avatarUrl` keeps it per local session; the profile picker gets one per profile
from the login/profiles payload. A photo set through Jellyfin's own web UI already appears on the TV, the
phone and Tizen today. Nothing in Ravilo can set one.

**The surfaces to hang this on also already exist.** `ProfileMenu` (R170) is the avatar dropdown — Switch
profile · My List · Settings · Add user · Sign out · Unpair — reachable on the phone since **R227** made
system Back close it, and `SettingsScreen` got its phone layout fixed in **R229**. Neither has an Account
section, and neither mentions a photo. (Note for anyone reading the mockups: the *phone mockup* had no
profile menu or Settings screen at all before 2026-09-03 and both were built from scratch there — that
was a gap in the design files, not in the app.)

So the viewer-visible gap is exactly two entry points and two forms.

## Goal

A viewer changes their own photo and their own password from the device in their hand, in their own
language, and never sees the words *Jellyfin*, *bitrate* or *token*. A TV viewer's experience does not
change at all.

## Functional requirements

**FR-R234-1 — "Your profile" is phone and web only.** A new row in `ProfileMenu` opens a **Your profile**
screen: the photo at a large size, the viewer's name, and the actions from FR-R234-3. The row and the
screen exist on `ravilo-phone` and `ravilo-web` and are **absent** — not disabled, not hidden behind a
message — on `ravilo-android` (TV) and `ravilo-tizen`. Gate on the platform, not on
`LocalCompact`/`LocalHandset`: a phone in landscape is still a phone, and a 10-foot UI is still a TV at
any window size. Tizen's `ProfileMenuScreen` is a separate hand-written DOM implementation and simply
does not gain the row — the same deliberate per-target omission R190 §C already established.

**FR-R234-2 — A TV renders a photo and offers nothing about changing one.** No row, no copy, no hint, no
"change this on your phone" line. R216's invariant is that the viewer's experience is that playback
simply works and that there is nothing to configure; a TV screen that explains where a setting lives is
still a TV screen that talks about settings. This was an explicit owner decision over the alternative of
a dim pointer line, and it is the reason FR-R234-1 gates on platform rather than screen size.

**FR-R234-3 — Four ways in, one of them web-only.** On the Your profile screen: **Choose a photo** (the
platform gallery/file picker), **Take a photo** (the camera), a row of **preset colours**, and **Remove
photo** — the last shown only when a photo exists, because an action that does nothing is worse than an
absent one. On `ravilo-web` only, the photo circle is additionally a **drop target** for a dragged image
file; the mockup gates that on `pointer:fine`, and the shipped client should gate it on the target rather
than on an input-capability query. Every control is ≥48 dp; the phone form's own type floor is 13 sp
(R229's `LocalCompact` padding conventions apply throughout).

> ~~**Preset colours are blocked on 187's open question 3.**~~ **Resolved 2026-09-05 — the owner chose to
> drop them, so this control comes out.** They were new stored state with no home in Jellyfin's user
> record, and the alternatives (a jellystructure-side column, or storing a generated solid-colour image
> *as* the photo) both cost more than the control was worth. The existing deterministic initials gradient
> stands, exactly as this requirement anticipated.
>
> **Three ways in, then, not four:** Choose a photo · Take a photo · Remove photo, plus the web-only drop
> target. The `photo_presets` string ("Or use a colour") comes out of FR-R234-10's table. **The mockups
> still draw the preset row** — `design/ravilo/Ravilo Mobile.html`'s Your profile sheet and
> `ravilo-data.js`'s `AV_PRESETS` / `colorFor` / `setColor` — so they now lead the spec and must be
> updated before this ships. Note `avatarFace()` still needs its colour input for the *initials*
> fallback; what goes is the viewer-facing **choice**, not the gradient itself.

**FR-R234-4 — Settings gains an Account section, on every platform.** Above the existing sections: the
viewer's own name and photo as a non-interactive identity block, then **Change password**. Present on TV,
phone, web and Tizen, because a password is not a preference — it is the credential the viewer already
had to type once, and the TV is where many of this household's viewers actually sit. This is the one part
of the phase that is not phone/web-only, and it is why the photo and the password ended up on different
screens rather than one.

**FR-R234-5 — Three fields, and the server decides.** Current password, new password, repeat new
password. The client checks only what it can honestly check — a missing current password, a new password
under 6 characters, a mismatched repeat — and sends the rest to Phase 187, which relays Jellyfin's
verdict (187 FR-187-3). A wrong current password is reported from the server, never guessed locally, and
renders as its own sentence rather than a generic error. Fields are masked, never revealed by a toggle
(the owner picked three fields over two-plus-reveal), and the current-password field is cleared on a
wrong-password response while the two new fields are kept — retyping a long new password because the
first field was wrong is the kind of small cruelty that makes people give up.

**FR-R234-6 — The TV reuses R175's on-screen keyboard verbatim.** The password panel on TV is the login
screen's own layout: field rows above, QWERTY grid below, shift/space/delete, D-pad through everything,
the same `signin-field`/`inp`/`live` treatment. A physical keyboard — which some of this household's TVs
have paired — types into the active field, Backspace deletes and Tab moves between the three fields; that
courtesy was added to the login screen in R175 and must not regress here. On phone and web, native
password inputs with `autocomplete="current-password"`/`"new-password"` so the platform's own password
manager works; do not hand-roll a keyboard on a device that has one.

**FR-R234-7 — Say the truth about the session afterwards.** If Phase 187 reports that the caller's token
survived, show *"Password changed."* and return to Settings. If it reports the token is gone, do not
pretend: sign out and route to login with a plain line saying the new password is now the one to use.
Phase 141's per-`(device, user)` identity means other devices may be affected too — if 187's probe shows
that, say so once, in words, rather than letting three TVs discover it separately at their next request.
~~**Blocked on 187 open question 2**~~; build the success path first and keep the branch explicit.

> **✅ Unblocked 2026-09-05 — the probe says tokens survive, so only the success path ships.** On Jellyfin
> 10.11.11 a token minted before a password change still authorises afterwards; only future
> `AuthenticateByName` calls are affected. So: *"Password changed."* (`pw_ok`), return to Settings, no
> sign-out, and **nothing said about other devices** — none of them is affected either, and a line
> reassuring the viewer about TVs that were never at risk only invents a worry.
>
> **Still render from what 187's response says, not from a constant.** This requirement's rule — say the
> truth the server reports — is what keeps the sign-out branch cheap if a future Jellyfin starts revoking.
> Keep reading the field; just do not build a screen for a branch that cannot currently occur.
>
> One thing this does *not* license: telling a viewer that changing a password protects their other
> devices. It does not sign them out. That behaviour would be its own phase, and a sign-out-everywhere
> control is already an explicit non-goal below.

**FR-R234-8 — A new photo appears everywhere, immediately.** On a successful upload or removal, the
avatar updates on the Your profile screen, the profile menu header, the appbar and the profile picker
without an app restart — which means refreshing the session's `avatarUrl` from 187's change-keyed URL
(187 FR-187-7) and letting `RemoteImage` miss its cache. R214 is the standing warning: Ravilo's image cache
outlived a corrected poster and showed the wrong art for days. One painter, one URL, one invalidation.

**FR-R234-9 — Circles, and one crop rule.** Every avatar surface stays a circle at every size, and the
photo fills it with a centre crop (`ContentScale.Crop`) — one rule, so no two surfaces can crop
differently. There is no crop or reposition step (the owner did not ask for one; 187 FR-187-6 crops
square server-side). The **profile picker's** tile is the one exception to the circle: it is a picker
card with its own rounded-square shape, and a photo fills that shape rather than turning one tile into a
circle among squares — a lone circle in that grid reads as a rendering bug.

**FR-R234-10 — Ship fully translated, en/da/fo.** Every string below at ship, like every phase since
R180, with the section and screen titles included — a half-translated Settings screen is worse than an
English one because it reads as broken rather than untranslated.

| key | en |
| --- | --- |
| `account` | `Account` |
| `pm_profile` / `profile_title` | `Your profile` |
| `pw_change` | `Change password` |
| `pw_title` | `Change your password` |
| `pw_sub` | `You'll need the new one the next time you sign in on any device.` |
| `pw_cur` / `pw_new` / `pw_rep` | `Current password` / `New password` / `Repeat new password` |
| `pw_save` / `pw_busy` / `pw_ok` | `Save password` / `Saving…` / `Password changed.` |
| `pw_err_cur` | `Enter your current password.` |
| `pw_err_new` | `Use at least 6 characters.` |
| `pw_err_rep` | `The two new passwords don't match.` |
| `pw_err_wrong` | `That current password isn't right.` |
| `photo_title` / `photo_sub` | `Your photo` / `Shows on every device you watch on — including the TV.` |
| `photo_choose` / `photo_camera` / `photo_remove` | `Choose a photo` / `Take a photo` / `Remove photo` |
| `photo_drop` | `or drop an image here` |
| ~~`photo_presets`~~ | ~~`Or use a colour`~~ — **cut 2026-09-05**, presets dropped (187 OQ3) |
| `photo_saved` / `photo_removed` | `Photo updated.` / `Photo removed — back to your initials.` |

Deliberately rejected copy, recorded so it is not re-proposed: *"Your Jellyfin password"* (the viewer
does not need to know what the server is called — this was live-corrected during the design pass),
*"Upload avatar"* (jargon; and "avatar" is not a word this household uses), *"Change your profile
picture in the Ravilo app on your phone"* as a TV line (FR-R234-2 forbids it entirely).

## Non-goals

- **No name editing.** The Settings row is labelled "Photo and name" in the mockup, but only the photo is
  wired and 187 puts renaming out of scope. Either wire both or relabel the row before shipping — do not
  ship a label that promises a control that isn't there.
- **No photo controls on any TV target**, including a "manage on your phone" hint (FR-R234-2).
- **No crop, rotate, zoom or filter step** (FR-R234-9).
- **No admin capability of any kind** from a Ravilo client — a viewer can act only on their own account
  (187 FR-187-2), and the admin's own view is read-only (187 FR-187-9).
- **No password strength meter, no policy text, no generated suggestions.** One honest length check and
  the server's answer.
- **No new player, playback or Home-feed behaviour.** Nothing in this phase touches a watching path.
- **No sign-out-everywhere control.** `Sign out` and `Unpair` already exist in the profile menu and are
  not being redesigned here, whatever FR-R234-7's answer turns out to be.

## Open questions

1. **Does the phone's file picker cover the owner's "gallery" and "camera" as two entries, or one?** On
   Android a single `GetContent` intent surfaces both, which would make "Take a photo" redundant on some
   devices and essential on others depending on the installed gallery app. Two explicit buttons are drawn
   (deliberately — a viewer who wants the camera should not have to know their gallery app offers it);
   confirm they map to two real intents rather than one dialog shown twice.
2. ~~**Preset colours** — blocked on 187 open question 3, and the whole control may come out.~~
   **Closed 2026-09-05: the control comes out.** Owner decision on 187 OQ3 — presets are dropped and the
   deterministic initials gradient stands. See FR-R234-3; the mockups still draw the row and need
   updating.
3. ~~**Session consequence** — blocked on 187 open question 2 (FR-R234-7).~~
   **Closed 2026-09-05: tokens survive a password change**, so FR-R234-7 ships its success path only —
   *"Password changed."*, back to Settings, no sign-out and no line about other devices.
4. **Does `ravilo-web` get the camera?** `capture` on a file input is a phone-browser affordance; a
   desktop browser will show a webcam or nothing depending on the browser. Current answer: offer
   **Choose a photo** and drag-and-drop on web, and show **Take a photo** only where a camera input is
   actually available — but that is a capability check this spec has not verified against the real
   wasmJS target.
5. **Kids profiles** — the owner's decision was "any signed-in profile, its own password only", which
   includes a kids profile changing its own password and photo. Worth one explicit confirmation, since a
   kid locking themselves out of a TV is a support call to the same household member who set it up.
   **Narrowed 2026-09-05 by 187's probe:** it is now purely a product question, with no technical half
   left. Jellyfin 10.11.11's `UserPolicy` has **no flag** that gates self-service password change (see
   187 open question 4), so there is nothing to consult and nothing that would stop a kids profile at the
   server — the row is shown or hidden entirely by our own choice, and the owner has already made it.
