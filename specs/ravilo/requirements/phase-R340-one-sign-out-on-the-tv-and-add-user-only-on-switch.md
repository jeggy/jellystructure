# Phase R340 — One Sign out on the TV, with a choice; Add user only on Switch

> Owner, 2026-09-30: *"On the TV, let's remove the "Add user" option, as this is already an option under the "Switch"
> option. And let's also remove the "Unpair this TV" in the profile menu and then when clicking on "Sign out" we can
> choose in that modal if we want to sign out of this specific user, or all users (including the configuration of which
> server to use)."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Ravilo TV.html` (built there the same day:
`ravilo-app.js` `renderSignoutConfirm`, `.so-*` in `ravilo.css`, `so_*` strings in `ravilo-i18n.js`).
**Not dev-reviewed.** Number verified free (after R339). **Changes** R170 (the profile menu), R175 FR-B4 (the menu's
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
