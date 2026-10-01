# Phase R348 — A password field tells the keyboard it is a password

> Found live on the living-room TV, 2026-10-02 (backend v1.48-33). Urgent: it shows a viewer's password to the room.

## Status

`✓ Built` 2026-10-02 (see Build notes). Written 2026-10-02 (dev-authored), against `main` `145fc0f9`. Number verified free (Ravilo specs top at
R347). Not dev-reviewed.

**Amends** R175 (the sign-in screen) and R234 (change your password). Neither said what the keyboard is told.

## What happens

1. On an Android TV with Gboard (the system keyboard), the viewer focuses **Password** on the sign-in screen and types.
2. The field draws dots, but the keyboard's suggestion strip shows the typed password **in plain text**. Anyone in the
   room can read it, and the keyboard may learn it into its personal dictionary.
3. Cause: the field only *draws* the text masked (`PasswordVisualTransformation`). It never tells the input method it
   is a password. `LoginField` (`LoginScreen.kt`) passes `KeyboardOptions(imeAction = …)` only, so Android gets
   `TYPE_CLASS_TEXT` with no password variation and no "no suggestions" flag. Gboard then treats it as ordinary text:
   it suggests, autocorrects and learns.
4. The same holds for the three fields of **Change your password** (`AccountField` in `AccountScreens.kt`), which set
   `autoCorrectEnabled = false` but no password type. Every platform runs these screens: the phone, the TV, the web
   app and the desktop app.

## Requirements

**FR-R348-1 — Every password field says it is a password.** Each masked field uses one shared set of keyboard
options: `KeyboardType.Password`, `autoCorrectEnabled = false`, `KeyboardCapitalization.None`, plus the field's own
IME action. On Android this sets `TYPE_TEXT_VARIATION_PASSWORD`, so the keyboard shows no suggestions and learns
nothing. On the web it is the browser's password input type. The masking (`PasswordVisualTransformation`) stays.
Covered: the sign-in **Password** field, and **Current password**, **New password** and **Repeat new password**.

**FR-R348-2 — The username field doesn't autocorrect or capitalise.** A username is not prose. The sign-in
**Username** field gets `autoCorrectEnabled = false` and `KeyboardCapitalization.None`. Its type stays text.

**FR-R348-3 — The keys still move on.** Username's IME action is *Next* and moves to Password. Password's action is
*Done* and signs in. On Change your password, *Next* walks Current → New → Repeat and *Done* on Repeat saves.
Unchanged from R175 / R234, now tested.

**FR-R348-4 — One definition.** The options live in one place (`secretKeyboardOptions()` in `SecretKeyboard.kt`), so a future password field
can't forget them. The server-setup address field is not a secret (`KeyboardType.Uri`, R226); it gets
`autoCorrectEnabled = false` only, since an address is not prose either.

**FR-R348-5 — No wire change, no new string.**

## Out of scope

- What a keyboard already learned. Ravilo cannot clear Gboard's dictionary; the viewer can, in Gboard's settings
  (*Delete learned words and data*). Worth telling the household.
- The pairing code in *Add a TV* (`ScreensSheet.kt`): a one-time code already shown on the TV screen.
- The admin web UI's login form (a browser `<input type="password">` already).

## Acceptance

1. On an Android TV, focus **Password** on the sign-in screen and type: the suggestion strip shows nothing typed.
2. The same on the Pixel 9 (sign-in and Change your password): no suggestions, no autocorrect, no capital first
   letter.
3. Username → *Next* reaches Password; Password → *Done* signs in.
4. A Robolectric test reads the `EditorInfo` the password field hands the keyboard and finds the password variation.

## Dev notes

- Compose maps `KeyboardType.Password` to `InputType.TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD` in its
  `EditorInfo` update, and adds `TYPE_TEXT_FLAG_AUTO_CORRECT` only when autocorrect is on.

## Build notes (2026-10-02)

**Built.** `secretKeyboardOptions(imeAction)` and `handleKeyboardOptions(imeAction)` in `ravilo-ui/.../screens/
SecretKeyboard.kt`. `LoginField` (sign-in) and `AccountField` (Change your password) pick one by `masked`, so the
sign-in Password and the three change-password fields carry `KeyboardType.Password`, no autocorrect and no
capitalisation; the sign-in Username carries plain text with no autocorrect and no capitals. The server-setup address
keeps `KeyboardType.Uri` and gains `autoCorrectEnabled = false`. The masking is unchanged. Test tags for the fields:
`LoginTags`, `ChangePasswordTags`.

**Audited:** every `TextField`/`BasicTextField` in `ravilo-ui`. The others are search boxes (Search, Seerr search, music
Browse, the desktop sidebar), a bookmark name (book player) and the six-character *Add a TV* code; none holds a secret.
No platform source set (`androidMain`, `wasmJsMain`, `desktopMain`, `ravilo-web`, `ravilo-desktop`) has its own
password input.

**Verified:** `PasswordKeyboardTest` (Robolectric, `:ravilo-ui:testDebugUnitTest`, TV-sized). It focuses each field and
reads the `EditorInfo` Compose hands the input method: `TYPE_TEXT_VARIATION_PASSWORD`, no `TYPE_TEXT_FLAG_AUTO_CORRECT`
and no `TYPE_TEXT_FLAG_CAP_SENTENCES` on all four password fields; plain text and *Next* on the username; *Next* on the
username moves focus to the password and *Done* on the password submits. With `KeyboardType.Text` put back, the two
password tests fail, so the test catches the bug.

**Owed (device):** acceptance 1–3 on the Stue TV (Gboard) and the Pixel 9. Tell the household that a password the
keyboard already learned stays in Gboard until it is cleared there (Gboard settings → *Privacy* → *Delete learned
words and data*).
