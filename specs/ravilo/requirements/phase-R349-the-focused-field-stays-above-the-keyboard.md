# Phase R349 — The focused field stays above the keyboard

> Found live on the living-room TV, 2026-10-02 (backend v1.48-33), on the same screen as R348.

## Status

`✓ Built` 2026-10-02 (see Build notes). Written 2026-10-02 (dev-authored), against `main` `145fc0f9`. Number verified free (Ravilo specs top at
R348). Not dev-reviewed.

**Amends** R175 (the sign-in screen), R226 (server setup) and R234 (change your password): their layout and D-pad
paths. What each screen says and does is unchanged, and so is R225's server line. R269 is the Tizen receiver's setup
screen, not this one; it is untouched.

## What happens

1. On an Android TV (960 × 540 dp), the sign-in screen opens with **Username** focused and the system keyboard up.
   The keyboard covers the lower half of the screen.
2. `RaviloApp` already pads every non-playing screen by the keyboard (`safeAreaPadding()` unions `WindowInsets.ime`,
   R261/R274). So the sign-in screen gets about half the height. But its form is a plain centred `Column` that cannot
   scroll: it doesn't fit, and Compose squeezes the children at the bottom to nothing. **Username** sits at the top,
   the **Password** label is cut at the keyboard's edge, and the focused password field can't be seen.
3. *Change your password* (three fields and Save) and *server setup* (the address and Connect) have the same shape:
   a non-scrolling column.
4. With the keyboard closed, the D-pad path is incomplete. Up from **Sign in** relies on Compose's default search;
   *server setup*'s address field swallows Down (a focused text field moves its cursor), so Connect can only be
   reached through the keyboard's *Go*. On *Change your password*, Up from **Current password** goes nowhere.
5. The **Wrong server?** link only changes its text colour when focused. From a sofa that reads as unfocused.
6. *Server setup* (owner's Stue TV, same day): nothing is focused on arrival, neither the address nor Connect. One Up
   focuses the field and opens the keyboard at once. The field's focus is a thin underline.
7. After **Wrong server?** the address field is empty (`RaviloRoot` saves `""` and the screen starts from `""`), so a
   one-letter typo means typing the whole address again.
8. *Server setup* is drawn by `RaviloRoot`, outside `RaviloApp`, so it is not padded by the keyboard at all: the
   keyboard simply covers its lower half.

The rule stands: **the system keyboard only, never a drawn one** (owner, 2026-09-27).

## Requirements

**FR-R349-1 — The form scrolls inside what the keyboard leaves.** The sign-in, *Change your password* and *server
setup* forms sit in one shared container (`KeyboardAwareForm`). It fills the space `safeAreaPadding()` leaves, scrolls
vertically, and keeps today's look when everything fits: the sign-in and setup forms centred, *Change your password*
top-aligned. Nothing is squeezed: every element keeps its height.

**FR-R349-2 — The focused field is brought into view, label included.** When a field gains focus, and again whenever
the space changes while it has focus (the keyboard opening or closing), the field's whole group (its label and its
box) is scrolled into view. A focused button is brought into view the same way (`focusable()` already does it).

**FR-R349-3 — D-pad, keyboard closed: a full path.**
- Sign-in: Username ⇄ Password ⇄ Sign in ⇄ Wrong server? with Down and Up. Down from Wrong server? stays put.
- Change your password: Back ⇄ Current ⇄ New ⇄ Repeat ⇄ Save.
- Server setup: address ⇄ Connect.
Each move is explicit (`onMoveUp`/`onMoveDown` on a field, `onUp`/`onDown` on a button), not left to Compose's
default search, and works whatever the screen height (keyboard open or closed).

**FR-R349-4 — D-pad, keyboard open.** While the system keyboard is up it owns the D-pad; the way on is its action key.
Username *Next* → Password, Password *Done* → sign in (R348 FR-R348-3); Current/New *Next* → the next field, Repeat
*Done* → save; the address *Go* → connect. Unchanged, now tested.

**FR-R349-5 — The link looks focused.** **Wrong server?**, when focused, gets the same treatment as **Sign in**: the
accent fill, `onAccent` text and the focus ring, in a rounded pill. Unfocused, it stays a quiet text link.

**FR-R349-6 — No wire change, no new string, no drawn keyboard.**

**FR-R349-7 — Server setup has a visible focus on arrival, and the keyboard opens on OK.** The address is a focusable
frame around the text field. Focused, the frame shows a 3 dp focus ring and nothing else happens; OK makes the field
editable, focuses it and the system keyboard opens. Leaving the field (Down to Connect, Back) ends editing, so coming
back with Up shows the ring again without the keyboard. On arrival: **Connect** has focus when an address is already
filled in (FR-R349-8), else the address frame.

**FR-R349-8 — Wrong server? keeps the last address.** `RaviloRoot` remembers the address in use when *Wrong server?*
is chosen and the setup field starts with it, shown as typed: the inferred `https://` (R226) left out, a typed
`http://` kept. Memory only, not stored: *Everyone on this TV* (R340) forgets the server on purpose and starts
empty, and so does a first run.

**FR-R349-9 — Server setup pads itself.** Being outside `RaviloApp`, the setup screen applies `safeAreaPadding()`
itself, so FR-R349-1 has a keyboard-sized space to scroll in.

## Out of scope

- A different layout for the keyboard (a two-column form, hiding the logo). Scrolling is enough at 540 dp.
- The TV's search screen (its field is at the top already).

## Acceptance

1. On an Android TV, open the sign-in screen: with the keyboard up, the focused field and its label are visible above
   it. *Next* to Password: Password and its label are visible.
2. Close the keyboard (Back): Down and Up walk Username, Password, Sign in and Wrong server?; the focused one is always
   visible. Wrong server?, focused, is a filled pill.
3. The same on *Change your password* (Settings → Account) and on server setup (*Wrong server?*).
4. *Wrong server?*: the setup screen shows the address that was in use, with **Connect** focused. Up shows the address
   frame's ring with no keyboard; OK opens the keyboard with the cursor in the address.
5. A first run (or after *Everyone on this TV*): the setup screen opens with the empty address frame focused and no
   keyboard until OK.
6. On the Pixel 9, the same forms scroll above the keyboard; nothing else changes.
7. A Robolectric test renders each form in the full TV height and in a keyboard-sized height, focuses each field and
   checks it is displayed, and walks the D-pad path at both heights.

## Dev notes

- `WindowInsets.ime` reaches Compose on the TV: `MainActivity` (TV) sets `setDecorFitsSystemWindows(false)` with
  `adjustResize`, and `RaviloApp` pads by it already. The form needs to react only to the height it is given, so the
  test can stand in for the keyboard with a shorter box.

## Build notes (2026-10-02)

**Built.** `KeyboardAwareForm` and `Modifier.keepInViewWhileFocused()` in `ravilo-ui/.../screens/
KeyboardAwareForm.kt`. The form is a full-size `Column` that scrolls, with its content at least the viewport tall
(so `Arrangement.Center` still centres a form that fits); every child keeps its height. Each field group (label + box)
brings itself into view on focus and again when the form's height changes. Used by `LoginScreen`,
`ChangePasswordScreen` (top-aligned) and `ServerSetupScreen`.

- **Deviation, and why:** not `BoxWithConstraints`. That composes its content during layout, after the screen's own
  `LaunchedEffect`s run, so `usernameFR.requestFocus()` found no node and the sign-in screen opened with nothing
  focused (caught by the test). The viewport is read in a `layout` pass and published through `onSizeChanged`.
- **D-pad (FR-R349-3):** Sign in has explicit `onUp` (Password) / `onDown` (Wrong server?); Wrong server? `onUp` →
  Sign in (Password while signing in), `onDown` stays. Change your password: the header's Back takes a `backFR` and
  `onBackDown` → Current; Current `onMoveUp` → Back; Save `onUp` → Repeat. Server setup: the address frame `onDown` →
  Connect, the field's Down → Connect, Connect `onUp` → the address frame; Connect's highlight now clears on blur (it
  never did).
- **Wrong server? (FR-R349-5):** focused, an accent pill with the focus ring and `onAccent` semibold text.
- **Server setup (FR-R349-7/8/9):** on a TV the address is a focusable frame (3 dp focus ring); OK sets `editing`,
  which lets the field take focus (`focusProperties { canFocus = editing || !isTvPlatform }`) and the keyboard opens;
  the field losing focus ends editing. On a phone, the web or a computer the field takes focus directly, as before
  (a tap on a field that can't take focus would do nothing). Arrival focus: Connect when an address is filled in,
  else the frame. `RaviloRoot` keeps `lastAddress` in memory on *Wrong server?* and passes it as `initialAddress`
  (`addressForEditing`: `https://` dropped, `http://` kept); *Everyone on this TV* clears it. The screen pads itself
  with `safeAreaPadding()`.
- No new string, no wire change, no drawn keyboard.

**Verified:** `KeyboardFormTest` (Robolectric, TV configuration `w960dp-h540dp-television`, 8 tests): at 540 dp and at
a keyboard-sized 240 dp the focused field and its label are displayed; *Next* reaches Password with its label shown; a
focused Password stays shown as the height drops to 240 and returns to 540; the sign-in D-pad walk (Username →
Password → Sign in → Wrong server? → stays → back up) at both heights; Back → Current → *Next* → New → *Next* → Repeat →
Save and back up with the keyboard up; server setup arrives on the address frame with the field not focused, OK focuses
the field, Down/Up between it and Connect at both heights; the prefilled case arrives on Connect with the address
filled in; on a phone the field takes focus directly; `addressForEditing`. With the form's scroll taken out, 5 of
the 8 fail. Plus `PasswordKeyboardTest` (R348), the whole `:ravilo-ui:testDebugUnitTest` (314 tests),
`:ravilo-web:compileKotlinWasmJs`, `-Pravilo.desktopOnly=true :ravilo-desktop:compileKotlinDesktop` and
`:ravilo-android:assembleRelease`.

**Owed (device):** acceptance 1–6 on the Stue TV (Gboard) and the Pixel 9. Not checked on a device: that Gboard on
the TV reports its height through `WindowInsets.ime` (the earlier squeeze says it does); if it doesn't, the form
still scrolls with the D-pad but the keyboard would cover the lower half.

## Found live 2026-10-09

- **Add user: *Cancel* drew over *Sign in* with the keyboard up** (R266's live run, Soveværelse TV debug 1.50-119,
  profile menu ▸ Switch ▸ ＋ Add user). The add-user flow (R175) laid `LoginScreen` in a full-screen `Box` and put its
  Cancel as an overlay pinned 48 dp above the screen's foot. With the system keyboard up the root pads the screen by the
  keyboard (FR-R349-1), so the foot rose to the middle of the form and the overlay sat on *Sign in* — this phase's
  scrolling form never knew the overlay was there. **Fixed 2026-10-09 (FR-R349-1, FR-R349-3):** `LoginScreen` takes an
  optional `onCancel`; when given, *Cancel* is a pill link in the form's own flow under *Sign in* (Sign in ▼ Cancel ▼
  Change server, ▲ back up; Back on it cancels), so it scrolls with the form inside what the keyboard leaves. The
  overlay is gone from `ProfilePickerScreen`. Test: `KeyboardFormTest` — at 540 dp and at the keyboard's 240 dp,
  Cancel is displayed, below Sign in and never overlapping it, the D-pad walks through it, OK cancels.
  **TV re-test owed:** Add user with Gboard up — *Sign in* and *Cancel* both readable and reachable.
