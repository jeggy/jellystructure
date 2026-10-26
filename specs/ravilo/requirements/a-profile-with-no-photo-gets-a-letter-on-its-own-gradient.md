# A profile with no photo gets a letter on its own gradient

Task: DEB-2596

> Owner, 2026-10-26 (DEB-1076): *"When a user in ravilo doesn't have a profile picture, lets generate one, so it shows
> some letter based on their username with some gradient etc"*

## What a viewer without a photo sees today

The design already settled this. R234 relied on it ("the existing deterministic initials gradient stands", FR-R234-3,
owner 2026-09-05), and `design/ravilo/ravilo-data.js` draws it as `gradientFor()` / `avatarFace()`. **The Compose
clients never built it.** Eight places in `ravilo-ui` draw the viewer's avatar, and they disagree on both the colour
and the letters:

| Where | Background with no photo | Letters for `jeggy` / `Anna Hansen` |
|---|---|---|
| `AppBar.kt` `ProfileAvatar` (TV, web, tablet top bar) | flat `surfaceVariant`, `accent` when focused | `JE` / `AN` (`displayName.take(2)`) |
| `RaviloBottomNav.kt` `ProfileDot` (phone) | flat `surfaceVariant` | `JE` / `AN` |
| `DesktopNav.kt` `ViewerFoot` (Mac, Linux) | the theme's `accentGradient`, the same for everyone | `J` / `A` |
| `ProfileScreen.kt`, two identity blocks | `accentGradient` | `JE` / `AN` |
| `ProfilePickerScreen.kt` (who's watching) | flat | `J` / `AH` (first letter of each of the first two words) |
| `AccountScreens.kt`, two photo blocks (R234) | as drawn there | as drawn there |

So two profiles on one TV look the same, and one viewer's letters change between screens. The admin UI's
*Users & devices* page (`src/wasmJsMain/.../RaviloUsers.kt`, `usrAvatarChip`) already draws a per-user gradient with
initials. That page is a different tree and is out of scope here.

Nothing is stored and nothing goes on the wire. The client already has what this needs: `LocalSession.userId` (the
Jellyfin user id) and `displayName`. **No image is generated and uploaded to Jellyfin.** R234 considered storing a
generated image as the photo and the owner rejected it on 2026-09-05. A face drawn by the client costs nothing and
never needs migrating.

## Requirements

**FR-1 — One face, derived from the profile.** `ravilo-ui` has one pure function that turns a profile into its
no-photo face, plus one composable that draws it:

- **The letters:** from `displayName`, keeping only letters and digits in each whitespace-separated word. A word
  with nothing left is dropped, so `Anna 🎬` counts as one word.
  - One word gives **one** letter: `jeggy` → `J`, `Eyð` → `E`.
  - Two or more words give the first letter of the first word and the first letter of the last word:
    `Anna Hansen` → `AH`, `Jens Peter Hansen` → `JH`.
  - Letters are uppercased locale-independently, so `ø` → `Ø` and `á` → `Á`.
  - A name with no letter or digit at all (empty, or only emoji or punctuation) gives `?`.
- **The gradient:** one of six, picked by a hash of `userId`. The colour stays the same when the viewer renames
  themselves, and it is the same on every device. Hash and ramp are the design's `gradientFor()`, so the mockups and
  the app agree:
  - `h = 0u; for (c in userId) h = h * 31u + c.code.toUInt()` (32-bit unsigned, over UTF-16 code units, the same as
    JS `charCodeAt` with `>>> 0`); index `= (h % 6u).toInt()`.
  - Ramp, in order: `#7b6ef0→#3fb6f5`, `#19d6c6→#2a8cf0`, `#f5b542→#e0792f`, `#e0567a→#7b6ef0`,
    `#e0639a→#b15cd0`, `#2dd49a→#12a3a0`.
  - Drawn at CSS `145deg`: the first colour at the top-left, the second at the bottom-right. In a square of side `s`
    the line runs through the centre along `(sin 145°, −cos 145°)`, length `s·(|sin 145°| + |cos 145°|)`.
- **The letters' look:** white (`#fff`, as `ravilo.css` `.avatar`), bold, `Sora`, centred, font size 40 % of the
  diameter.
- **A photo wins.** When `avatarUrl` is non-null the photo is drawn **on top of** the face, clipped to the circle. A
  photo that is still loading or fails to load leaves the letter showing, never an empty grey circle.

**FR-2 — Every avatar of a viewer draws FR-1's face.** All eight places in the table above draw FR-1's composable and
stop working out initials themselves. The `displayName.take(2).uppercase()` call sites go too. The surfaces keep their
own sizes, rings and dots: R304's white ring on the phone, R386's update dot and DEB-1501's support dot.

- **Focus does not repaint the face** (TV, D-pad, keyboard). `AppBar`'s avatar today turns `accent` when focused. It
  keeps its circle and shows focus with the existing `focusRing` only, as `ravilo.css` `.avatar.focused` draws it. A
  viewer's colour is their identity, so focus must not swap it for the skin's accent.
- Kids profiles get no special case here. The mockups' `★` is a design placeholder, not a requirement.

## Out of scope

- The admin UI's `RaviloUsers.kt` chip (different ramp, 120°, Kotlin `hashCode`). Aligning it is a separate,
  optional piece.
- Music artists' initials (`MusicCommon.kt`). They are not people with accounts.
- Any colour picker, or any stored colour (FR-R234-3, dropped 2026-09-05).

## Verification

- FR-1: a `desktopTest` unit test on the pure function. It covers the letter cases above, and fixed `userId`s mapped
  to their ramp index: write down the expected index from the JS algorithm, not from the Kotlin under test. Run
  through `gradientFor()`'s loop in Node on 2026-10-27, these ids give these indices: `""` → 0, `"a"` → 1,
  `"b"` → 2, `"c"` → 3, `"d"` → 4, `"e"` → 5, `"0123456789abcdef0123456789abcdef"` → 4 (hash 2511650128, past
  2³¹), `"4f1c2a9be7d34c0f8a6b5e2d1c3f9a7b"` → 3 (hash 3769005873, past 2³¹).
- FR-2: the screenshot tests that already draw these surfaces. Then a QA pass on a TV (focus ring, no repaint), a
  phone (bottom bar, Profile page) and the who's-watching picker, with one profile that has a photo and two that
  have none.
