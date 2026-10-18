# An account whose password Jellyfin does not own is changed where it is owned

Task: DEB-1854

> The owner's ask on DEB-1854 (2026-10-19): support Jellyfin servers that check passwords against LDAP, with an
> identity provider as the place a password lives. Investigated on the task (document `investigation`). Owner card
> `73785d9f`, answered 2026-10-19: the TV shows no row (Q1), the optional extras stay out (Q3), the in-app form stays
> for local accounts for good (Q4). Q2 (what an account sees when the server has set no password page) was re-asked
> in plain words and answered on card `20db47f9`, 2026-10-19: the one sentence, FR-6b.

## Scope

Written 2026-10-19 (PO-authored from DEB-1854). Not dev-reviewed. Spans both trees:
- **Backend:** the provider read, one config value, two server-resolved fields on the per-viewer Ravilo config, one
  additive field on the password route's answer, and a username refresh in the existing reconciler.
- **Admin:** one text field.
- **Clients:** the Settings and Profile rows on phone, web and desktop, and the TV's Settings.

Amends Phase 187 (FR-187-3) and R234 (FR-R234-4) by dated amendments in their own files. Sign-in is not touched, and
nothing in `specs/ravilo/constitution.md` changes: the proxied-login invariant ("Authentication & device sign-in")
holds word for word. No design round: the row, its label and its place are R234's, and what is new is one glyph, one
subtitle line and one admin field drawn with existing components.

## What is there today

**Sign-in already works for an account Jellyfin checks elsewhere.** `POST /api/tv/login` relays to
`/Users/AuthenticateByName` (`TvRoutes.kt`), and Jellyfin asks the account's own *authentication provider*
(`Policy.AuthenticationProviderId`) whether the password is right. For an account switched to the LDAP plugin,
that question goes to the directory. A wrong password, or an account the directory refuses, still comes back `401`
and still reads *"Wrong username or password"*. The admin login (`AuthRoutes.kt`) is the same relay plus the
`IsAdministrator` gate.

**Changing the password is a dead end for such an account.** `POST /api/tv/account/password` sends `{CurrentPw,
NewPw}` to `POST /Users/Password?userId=` with the viewer's own token (Phase 187). Jellyfin then:
- checks `CurrentPw` through the account's provider, so a wrong one is still `403`, shown as *"That current password
  isn't right"*;
- for a right one, calls the provider's `ChangePassword`. The LDAP plugin throws `NotImplementedException` whenever
  its *Allow Password Change* is off, which it must be against a read-only directory.

Jellyfin answers `500`, jellystructure answers `502`, and the viewer reads *"Something went wrong"*, which is an
outage sentence for something that is not an outage.

**Nothing reads the provider.** `JellyfinPolicy` (`auth/Models.kt`) parses `IsAdministrator`, `MaxParentalRating`,
`EnableAllFolders`, `EnabledFolders`, `AllowedTags` and `BlockedTags`, and nothing else.

**Where the row is.**
- `SettingsContent`'s Account block (`SettingsScreen.kt`) draws *Change password* on every platform, TV included
  (FR-R234-4).
- The phone also reaches it from Profile (`ProfileScreen.kt`).
- Desktop has it in both settings panels (Mac/Windows and GNOME).
- `ravilo-tizen` has no password row at all and gains nothing here.

**A username is copied, and the copy is never refreshed.** `ravilo_device.jellyfin_username` is written at sign-in
and never again: the reconciler's `updatePolicy` does not touch it. The copy feeds `TvSession.displayName`, the
admin's Users & devices table, remote and screen viewer names, support and audiobook names, and a playback
session's owner name. The LDAP plugin **renames a Jellyfin account by itself** at sign-in whenever the directory's
spelling differs (case included). So a rename is something that happens, not something an operator decides.
Nothing is *keyed* on the name: the device row's key, the Jellyfin `DeviceId` for every later call, and every
client store are by user id.

## Functional requirements

**FR-1 — Probe, in two halves.** Upstream source says the provider ids are
`Jellyfin.Plugin.LDAP_Auth.LdapAuthenticationProviderPlugin` and
`Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider`, and that `POST /Users/Password` answers a
switched account `500` for a right `CurrentPw` and `403` for a wrong one. This is Phase 163 and 187's standing
lesson: reading is not probing.
- **Before building:** read the `Policy.AuthenticationProviderId` of a local account on the 12.1 server (`GET /Users`,
  read-only) and record it here. FR-2's rule depends on this id alone.
  - **Recorded 2026-10-19 (DEB-1859):** `GET /Users` on the 12.1 server answers every one of its 10 accounts with
    `Policy.AuthenticationProviderId = Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider` (and
    `PasswordResetProviderId = …DefaultPasswordResetProvider`). That is the default id FR-2 compares against; the
    compare is on the trimmed id, ignoring case.
- **In the QA row:** on a throwaway account switched to the LDAP plugin (never a household account), record here
  the switched account's id, the two `POST /Users/Password` answers, and that `AuthenticateByName` still answers
  `401` for a wrong password. This needs the plugin on the server, which is the operator's setup and not part of
  the build, so the build does not wait for it.

**FR-2 — An account's password is *managed elsewhere* when its provider is not Jellyfin's own.**
- jellystructure parses `Policy.AuthenticationProviderId`.
- An account is managed elsewhere when that field is present, non-blank, and not Jellyfin's default provider id (as
  FR-1 records it).
- Absent or blank reads as local, so a Jellyfin that does not send the field keeps today's behaviour exactly.
- The rule names no plugin and no identity provider: any provider that is not Jellyfin's own counts. An LDAP setup
  that *does* allow password changes through the plugin loses the in-app form under this rule. That is accepted:
  the form would send a second copy of the password through one more hop, and the provider's own page is the honest
  place to change it.

**FR-3 — The answer follows a switch without a new sign-in.** During a migration an account is switched to the
plugin while its devices stay signed in, so the answer cannot be fixed at sign-in.
- It is read at sign-in from the `AuthenticateByName` answer's own `User.Policy`.
- It is refreshed by every `DevicePolicyReconciler` pass from the `/Users` answer that pass already fetches, so it
  costs no extra Jellyfin call.
- A change pushes `config_changed` to that viewer's devices, as a policy change does today.
- Where it is kept between passes is the builder's choice, and it is never sent to Jellyfin.

**FR-4 — One optional password page for the installation.**
- `AppConfig` gains a root-level `password_change_url`, next to `public_url` and stored the same way. Its default is
  empty, which means not set.
- The admin's Settings page shows it as one text field with the Jellyfin connection fields, labelled *Password page
  for accounts managed outside Jellyfin*, placeholder `https://`.
- A save refuses anything that is not an absolute `https://` URL and says so beside the field.
- A load never refuses it (Phase 227's rule: a load that refuses one string is an outage). What a client receives is
  only a value that parses as `https://`; anything else is not set.
- No identity provider, host or path is ever a default, a constant or an example in code, config or copy: this
  repository serves installations that are not this household's.

**FR-5 — The viewer's config says which form to draw.** `RaviloConfig` gains two server-resolved fields, resolved on
every read path the way `support_enabled` is, never stored, and omitted at their defaults:

| field | meaning |
|---|---|
| `password_managed_elsewhere: Boolean = false` | FR-2's answer for this viewer |
| `password_change_url: String? = null` | FR-4's value, sent only when `password_managed_elsewhere` is true |

An installed app that does not know the fields ignores them and keeps drawing the form. FR-6c covers what that app
then shows.

**FR-6 — Phone, web and desktop.** Settings' Account block and the phone's Profile row draw one of three things:
- **a. Managed elsewhere, page set.** The same *Change password* row, with an external-link glyph after the label
  and the subtitle `account.pw_elsewhere_sub` under it. Selecting it opens `password_change_url` through
  `LocalUriHandler.openUri`: the phone's browser, a new tab on the web, the default browser on desktop. No in-app
  screen opens, no callback is awaited, and the Ravilo session is not touched. The web route `/account/password`,
  opened directly for such an account, lands on Settings.
- **b. Managed elsewhere, no page set.** No row. In its place, the non-interactive line `account.pw_elsewhere_none`
  (owner, Q2).
- **c. Local.** Today's row and `ChangePasswordScreen`, unchanged (owner, Q4: the form stays for local accounts
  permanently).

**FR-7 — The TV draws nothing for an account managed elsewhere.** On `ravilo-android` (every TV and the streamer),
such an account's Settings has no *Change password* row and no line in its place. This is the owner's answer to Q1,
and it is R234 FR-R234-2's rule that a TV screen never explains where a setting lives elsewhere. The D-pad chain
closes over the gap: the row above links straight to the row below and back. That is R234's own lesson, that a row
the chain cannot reach is a bug. A local account's TV keeps the row exactly as it is.

**FR-8 — The route says so instead of failing.** `POST /api/tv/account/password`:
- reads the account's provider **live** (`GET /Users/{id}` with the server token) before it relays anything;
- for an account managed elsewhere, answers `200` with `{ok: false, managed_elsewhere: true}` and never sends the
  password to Jellyfin;
- if the live read fails, falls through to today's behaviour.

`AccountPasswordResult` gains `managed_elsewhere: Boolean = false`. A current client that receives it shows
FR-6a or FR-6b's line in place of the form. That covers the window before a reconciler pass reaches a screen
already open. An installed app from before this shows its generic error, which is today's sentence and no worse.
The rate limiter still runs first (FR-187-4).

**FR-9 — A renamed account shows its new name.**
- Each reconciler pass also writes the `/Users` answer's `Name` into `ravilo_device.jellyfin_username` for that
  user's rows, and pushes `config_changed` when the name moved, so `TvSession.displayName` follows.
- The admin's Users & devices table shows the live name it already holds.
- `SuggestionService`'s *No thanks* resolves the viewer by user id wherever the stored suggestion carries one. New
  suggestions store the id beside the name (additive), and the name lookup stays as the fallback for rows stored
  before.
- Audit text (`suggestion_*`, `media_job.enqueued_by`, `publish.*_by`) stays as it was written: it is history.
- `isTestAccount`'s `^test` name rule is unchanged. A rename into or out of it changes what that rule already means.

**FR-10 — Strings, en/da/fo at ship.** The shipped table wins over the drafts.

| key | en | da (draft) | fo (draft) |
|---|---|---|---|
| `account.pw_elsewhere_sub` | Opens your account page in the browser. The new password works everywhere that account is used, Ravilo included. | Åbner din kontoside i browseren. Den nye adgangskode virker alle steder, kontoen bruges, også i Ravilo. | Opnar kontusíðuna hjá tær í kaganum. Nýggja loyniorðið virkar allastaðni, har kontan verður brúkt, eisini í Ravilo. |
| `account.pw_elsewhere_none` | Your password is managed outside Ravilo. Ask whoever runs this server how to change it. | Din adgangskode styres uden for Ravilo. Spørg den, der driver serveren, hvordan du skifter den. | Loyniorðið hjá tær verður stýrt uttan fyri Ravilo. Spyr tann, ið rekur ambætaran, hvussu tú skiftir tað. |

Notes:
- `account.pw_elsewhere_sub`: *"Under Change password, for an account whose password lives in another service. Never
  name the service."*
- `account.pw_elsewhere_none`: *"In place of Change password when the server has set no page to send the viewer
  to."*
- Neither string says *Jellyfin*, *LDAP* or a provider's name: R234 FR-R234-10's rejected *"Your Jellyfin
  password"* stands.

**FR-11 — Tests.**
- **Backend:** FR-2's rule (default id → local, another id → elsewhere, absent or blank → local); the reconciler
  refreshing both the provider answer and the username from one `/Users` answer; FR-5's two fields present only when
  they should be and omitted at default; FR-8's route answering an account managed elsewhere without calling
  Jellyfin (a fake client that fails the test if called), and falling through when the live read fails; FR-4's save
  refusing a non-`https` value while a load keeps it.
- **Wire:** a `RaviloConfig` and an `AccountPasswordResult` without the new keys still decode.
- **Client:** FR-6's three cases and FR-7's TV case render from the config's fields, and FR-7's D-pad chain has no
  gap (the R234 `KeyboardFormTest`/`PasswordKeyboardTest` family).

## Non-goals

- **No change to sign-in** on any client or in the admin, and no new login error copy.
- **No password reset or recovery flow.** A forgotten password stays the operator's problem, as Phase 187 already
  says.
- **No signing out of an account the directory has disabled or removed.** Its next sign-in fails; tokens already
  issued stay valid. This would need the identity provider's own API and a new secret (owner, Q3). If wanted, it is
  its own task.
- **No QR or device-code sign-in.** It reverses Phase 141 and R175's "no Quick Connect" (owner, Q3). Its own task, if
  wanted.
- **No identity-provider API call of any kind**, and no provider named in code, config, copy or spec.
- **No deletion of the in-app form** (owner, Q4).
- **No change for Tizen**, which has no password row.

## Acceptance

1. A throwaway account on the LDAP plugin signs in on Ravilo web, phone and a TV with the directory's password, and a
   wrong password gives the usual sentence.
2. With a password page set, that account's phone, web and desktop Settings show *Change password ↗*, and it opens
   the page in the browser. After a change there, the **new** password signs in on Ravilo and the **old** one does
   not.
3. With no page set, the same account sees FR-10's `pw_elsewhere_none` line and no row.
4. That account's TV Settings shows no *Change password* row, and the D-pad moves straight from the row above to the
   row below.
5. A local account sees and can use today's form on every platform, TV included.
6. Switching a signed-in account to the plugin moves its phone's Settings from the form to the link within one
   reconciler pass, without signing in again.
7. Renaming a Jellyfin account (user id unchanged) keeps its devices signed in, and its new name reaches the profile
   picker and the admin's Users & devices table within one pass.
8. The admin login still works for a Jellyfin administrator on the LDAP plugin.

## Who it reaches

Any installation whose Jellyfin uses an authentication provider other than its own, and on this one, every account
moved to the LDAP plugin. A local account, a server without the plugin, and an installed app from before this see no
change, apart from the TV row for an account managed elsewhere, which goes away.
