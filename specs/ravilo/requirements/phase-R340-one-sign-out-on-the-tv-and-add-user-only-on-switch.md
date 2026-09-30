# Phase R340 — One Sign out on the TV, with a choice; Add user only on Switch

> Owner, 2026-09-30: *"On the TV, let's remove the "Add user" option, as this is already an option under the "Switch"
> option. And let's also remove the "Unpair this TV" in the profile menu and then when clicking on "Sign out" we can
> choose in that modal if we want to sign out of this specific user, or all users (including the configuration of which
> server to use)."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Ravilo TV.html` (built there the same day:
`ravilo-app.js` `renderSignoutConfirm`, `.so-*` in `ravilo.css`, `so_*` strings in `ravilo-i18n.js`).
**Dev-reviewed 2026-09-30** (§Dev review). Number verified free (after R339). **Changes** R170 (the profile menu), R175 FR-B4 (the menu's
*Add user* row), R191 FR-R191-3 (two affordances, two confirmations) and R161/R234 (Settings' *Unpair this TV*
section). **Applies to the TV family** (TV, the web app — R337); the phone's Profile keeps R304's one-profile sign-out
sheet.

## Requirements

**FR-R340-1 — The profile menu is Switch · My List · Settings · Sign out.** The **Add user** row is removed: adding a
user is the ＋ tile on *Switch*'s grid (R175's other entry point, unchanged), one click from the menu's header. The
**Unpair this TV** row is removed. The menu's D-pad chain loses both rows and nothing else.

**FR-R340-2 — Settings loses its Unpair section.** Settings ▸ Account's **Sign out** button opens the same dialog as the
menu (FR-R340-3); the separate *Unpair this TV* danger section and its confirmation are gone, so there is one way out
and it always asks.

**FR-R340-3 — Sign out asks which.** One dialog, title **Sign out**, two choices as radio rows, then *Cancel* · the
confirm button:

- **Only {name}** — *Everyone else stays signed in on this TV.* (with no one else signed in: *You are the only one
  signed in, so Ravilo will ask who is watching next time.*). **The default**, and where focus lands. Confirming is
  R191's per-profile sign-out, unchanged: that profile's session is revoked; the others stay; with others left the TV
  shows *Who's watching?*, with none, the sign-in screen.
- **Everyone on this TV** — *Signs out all {n} profiles and forgets the server. Next time Ravilo starts from the
  beginning.* Confirming is what *Unpair this TV* did (R191's `unpairAllSessions`: every cached session revoked
  through `POST /tv/pair/unpair`, `MultiTokenStore.clear()`) **plus the server address is forgotten**
  (`saveBaseUrl` cleared), so the TV opens on R225/R226's server setup, then sign-in. A toast: *Everyone is signed out*.

The confirm button reads **Yes, sign out** for the first and **Sign out everyone** for the second, so the button says
what it will do. D-pad: ↑/↓ between the two rows, OK picks one; ↓ from the second row reaches the buttons; Back
cancels. The dialog is the only confirmation — no second one for *Everyone*.

**FR-R340-4 — Strings** × en · da · fo (da/fo drafts; the shipped table wins): `so_title` *Sign out* · `so_one`
*Only {name}* · `so_one_sub` *Everyone else stays signed in on this TV.* · `so_one_sub_last` *You are the only one
signed in, so Ravilo will ask who is watching next time.* · `so_all` *Everyone on this TV* · `so_all_sub` *Signs out
all {n} profiles and forgets the server. Next time Ravilo starts from the beginning.* · `so_yes_all` *Sign out
everyone* · `toast_signed_out_all` *Everyone is signed out*. **Retired:** `pm_unpair`, `unpair`, `unpair_desc`,
`unpair_confirm`, `unpair_yes`, `toast_unpaired`, and the menu's use of `add_user` (the Switch tile keeps it).

## Invariants

- R191's per-profile sign-out and `POST /tv/pair/unpair`'s behaviour are unchanged; only where they are reached from
  and the copy change.
- The receiver-only TV app (R264/R269) has no profile menu and is untouched; its hold-Back server change stays.

## Acceptance

1. The TV's profile menu reads Switch · My List · Settings · Sign out; *Switch* still has the ＋ Add user tile.
2. Sign out → *Only Eyð* is selected and focused; confirm → Eyð is signed out, the others remain, *Who's watching?*.
3. Sign out → *Everyone on this TV* → *Sign out everyone* → every session is revoked server-side (Users & devices shows
   none for this TV) and the TV opens on server setup.
4. Settings has no Unpair section; its Sign out opens the same dialog.

## Dev review (2026-09-30, against `main` `c4258560`)

Client-only; no server change and no wire change (`POST /tv/pair/unpair` and R191's sign-out stay as they are). Six
items.

1. **The menu** (`ProfileMenu.kt:104–113`): drop the `pm.add_user` and `pm.unpair` rows. The D-pad chain is built from
   the list (R234), so no other row needs rewiring.
   **Keep R234's *Your profile* row in the web app** (`!isTvPlatform && onYourProfile != null`). FR-R340-1's four-row list
   leaves it out, but R340 applies to the web app too. So the TV reads *Switch · My List · Settings · Sign out*, and
   the web app reads *Switch · Your profile · My List · Settings · Sign out*.
2. **The ＋ tile does not use `pm.add_user`.** Switch's grid labels it `profile.add_user` (`ProfilePickerScreen.kt:261`).
   Once the menu row goes, `pm.add_user` has no caller, so it is **retired**, the opposite of what FR-R340-4 says.
3. ***Everyone on this TV* is two calls that already exist:** `unpairAllSessions(apiClient)` (`SettingsScreen.kt:135`),
   then `RaviloApp`'s existing **`onChangeServer`** (`RaviloRoot.kt:119`: `saveBaseUrl("")`, then back to
   `ServerSetupScreen`). So "forgets the server" needs no new plumbing: today's `onUnpaired` / `onUnpair` callers route
   to `onChangeServer` instead of to Login.
   - Add `forgetListening()` to `unpairAllSessions`. Today only `signOutActiveSession` calls it; it is harmless on a TV
     and right on the web app.
   - The revokes are best-effort, as today. The local clear and the forgotten server happen even when the server
     cannot be reached, so the TV always lands on server setup.
4. **One dialog replaces four uses.** `SettingsScreen.kt` defines `UnpairConfirmOverlay` and `SignOutConfirmOverlay`
   (`:361`, `:374`; used by `ProfileMenu.kt`) and opens two `ConfirmOverlay`s of its own (`:269`, `:284`). All four
   become one composable, used from both places.
   Settings' *Unpair this TV* section (`SettingsScreen.kt:654–675`, `onUnpairRequest`) goes. The dialog's D-pad follows
   FR-R340-3, with explicit `FocusRequester`s (R234's chain).
5. **Strings: use the table's names, and cover the one-profile case.** The spec's keys are the mockup's (`so_title`,
   `pm_unpair`, `unpair_desc`, …); the shipped table is dotted.
   - **New:** `signout.title`, `signout.one`, `signout.one_sub`, `signout.one_sub_last`, `signout.all`,
     `signout.all_sub`, `signout.yes_all`, `toast.signed_out_all`. The first choice's button reuses
     `settings.sign_out_yes` (*Yes, sign out*).
   - **`signout.all_sub` needs a one-profile form.** *Signs out all {n} profiles* reads wrongly when one profile is
     signed in; then the two choices differ only by forgetting the server. Add `signout.all_sub_one`, *Signs you out
     and forgets the server. Next time Ravilo starts from the beginning.*
   - **Retired:** `pm.add_user`, `pm.unpair`, `settings.unpair`, `settings.unpair_desc`, `settings.unpair_confirm`,
     `settings.unpair_yes`, `toast.unpaired`, `settings.sign_out_confirm`, `settings.sign_out_desc`. The new dialog says
     what these said.
   - da/fo come from the design's drafts; the Faroese already uses R288's *sjónvarp* and *ambætari*. Regenerate the
     lexicon with `--update-lexicon` in the same commit.
6. **Where R340 applies.** It covers the TV family only (TVs and the web app). The phone keeps R304's sheet, and so does
   the desktop, which under R337 has no profile menu (R337 dev review 4). The receiver-only app is untouched, as the
   spec says.
