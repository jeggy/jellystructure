# Phase 187 — Let a viewer change their own photo and their own password

> **Renumbered 2026-09-04:** was 186 / R230 in draft. The dev team took **186** (request-intent
> lifecycle cleanup) and **R230** (fully disable Skip Intro / Skip Credits) before these were pushed, so
> this pair is now **187 / R234**. Same collision shape as R196 → R208 and 179 → 180.

> The owner asked for two things Ravilo has never had: uploading a profile photo, and changing a
> password. Investigating turned up a useful asymmetry — **the photo's read path already ships in full**
> (R65), so half of this is already built and nobody noticed; there is simply no way to *write* one.
> The password has nothing at either end. This phase adds the two write paths, both as thin proxies to
> Jellyfin, and puts the photo in the admin's user list. **R234** builds the screens; this phase owns the
> routes, the storage answer and the cache.

**Status:** Planned (design-authored 2026-09-03, not yet dev-reviewed). **FR-187-1's endpoint probe was
run 2026-09-05 against this house's live Jellyfin 10.11.11** and is recorded below: all three operations
exist, none at the path this spec assumed, and the probe turned up three things the design did not
anticipate (an undocumented legacy alias on the read path, `fillHeight`/`quality` being silently ignored,
and an avatar cache with no TTL at all). Open questions 1 and 4 are answered; **open question 2 remains
open and still blocks R234 FR-R234-7** — it needs a write against a throwaway account, which has not been
done.

Design: built into the mockups at `design/ravilo/Ravilo Mobile.html` (profile sheet · Your profile ·
Settings → Account), `design/ravilo/Ravilo TV.html` (photo rendering + Settings → Account → change
password) and `design/app/ravilo-users.html` (read-only photo per user row). No directions file — the
owner picked the shape up front from a written set of options rather than from drawn frames.

## Current state

**The photo is already a solved problem in one direction.** R65 wired the whole read path and it is live:

- `server/routes/TvRoutes.kt:302` sets `avatarUrl = RaviloImageUrl.avatar(device.jellyfinUserId)` on the
  login response, and `:328` does the same for every profile in the picker list. (Path corrected
  2026-09-05 — the file is under `server/routes/`, not `tv/`; the line numbers are right.)
- `RaviloArtworkService.kt:126` proxies it from Jellyfin —
  `GET /Users/{userId}/Images/Primary?api_key={token}&fillHeight=160&quality=90` — and caches to disk.
  Both halves of that URL turned out to be wrong in ways the probe below details: the path is an
  undocumented legacy alias, and `fillHeight`/`quality` are silently ignored.
- `AppBar.kt:299` renders it through `RemoteImage` when non-null, initials otherwise; `LocalUserAvatarUrl`
  (`RaviloApp.kt:152`) carries it, and `StoredSession.avatarUrl` persists it per local session on
  Android and Tizen alike.

So a photo set through Jellyfin's own web UI **already appears** on the TV appbar, in the profile picker
and on the phone today. What is missing is any way to put one there from Ravilo, and any way to remove
one. `RaviloImageUrl.avatar()` also takes no cache-busting parameter — which is exactly the bug shape
R214 fixed for posters, and it will reappear here the moment photos become changeable.

**The password has nothing.** `JellyfinClient` proxies `POST /Users/AuthenticateByName` (`:198`) for
login and nothing else account-shaped; there is no password route, no admin surface, and no client
screen. A viewer who wants a new password has to be given Jellyfin's own web UI, which is the thing
Ravilo exists to avoid.

**Both of those gaps are why this is one phase, not two:** the storage question the owner asked
("where does the image actually live?") is already answered by R65's own choice — Jellyfin's user
Primary image — and honouring that answer is most of the work.

## Goal

A signed-in viewer can set, replace or remove their own photo, and change their own password, without
leaving Ravilo and without an admin. Nothing new is stored in jellystructure: Jellyfin remains the one
owner of both facts, exactly as it already owns the photo on the read side.

## Functional requirements

**FR-187-1 — Confirm both Jellyfin endpoints against 10.11.11 before writing any code.** Phase 163's
standing lesson: `POST /MediaSegments` looked obvious, returned **405**, and cost a designed-and-drawn
§E. The three operations this phase needs are all *believed* to exist but none has been probed on this
house's server:

| operation | believed endpoint |
| --- | --- |
| set photo | `POST /Users/{userId}/Images/Primary` |
| remove photo | `DELETE /Users/{userId}/Images/Primary` |
| change password | `POST /Users/{userId}/Password` |

Probe the live OpenAPI document first, including the **request body shape** for the image upload
(historically base64 text with the content type in the header, not multipart — verify, do not assume)
and the exact field names on the password body (`CurrentPw`/`NewPw`). If any is absent or refuses, stop
and re-scope rather than building around it — and record what the server actually said, the way 163 does.

### ✅ Probed 2026-09-05 against `https://jellyfin.example.net` (10.11.11) — all three exist, none at the believed path

Read-only probe: the live `/api/docs/openapi.json` (2.3 MB) plus `GET` requests against real users.
**Every believed path in the table above is wrong.** None returns 405 the way 163's did — they simply do
not exist, and the real operations live elsewhere:

| operation | believed | **actual on 10.11.11** |
| --- | --- | --- |
| set photo | `POST /Users/{userId}/Images/Primary` | **`POST /UserImage?userId=<uuid>`** |
| remove photo | `DELETE /Users/{userId}/Images/Primary` | **`DELETE /UserImage?userId=<uuid>`** |
| change password | `POST /Users/{userId}/Password` | **`POST /Users/Password?userId=<uuid>`** |

`/Users/**` in this server's whole OpenAPI document is only: `/Users`, `/Users/AuthenticateByName`,
`/Users/AuthenticateWithQuickConnect`, `/Users/Configuration`, `/Users/ForgotPassword`,
`/Users/ForgotPassword/Pin`, `/Users/Me`, `/Users/New`, `/Users/Password`, `/Users/Public`,
`/Users/{userId}` and `/Users/{userId}/Policy`. There is no `/Users/{userId}/Images/*` operation at all.
**On both real endpoints the user id is a *query* parameter and is `required: false`** — omitted, it means
"the caller". That does not change FR-187-2: jellystructure calls Jellyfin with the admin token, so it
must always send the id explicitly, taken from the session.

**Image upload body shape — answers open question 1.** `POST /UserImage` declares content type `image/*`
with schema `{"type": "string", "format": "binary"}`: a **raw image byte body with the real MIME type in
the `Content-Type` header**. Not multipart, and not the historical base64 text this phase's own note
warned about. Responses: `204, 400, 401, 403, 404, 503`. `DELETE /UserImage` takes the same `userId`
query parameter and answers `204, 401, 403, 503`.

**Password body — field names confirmed.** `POST /Users/Password` takes JSON (`application/json`) of
schema `UpdateUserPassword`, whose properties are exactly `CurrentPassword` (the *sha1-hashed* legacy
field), **`CurrentPw`** (plain text), **`NewPw`** (plain text) and `ResetPassword` (bool). So FR-187-3
sends `{"CurrentPw": …, "NewPw": …}` and nothing else — never `ResetPassword`, which is the
admin/forgot-password path, not a viewer changing a password they know. Responses:
`204, 401, 403, 404, 503`, so **a wrong current password is a `401`** and that is the signal FR-187-3
relays for R234's *"That current password isn't right."*

### ⚠ Three findings the design did not anticipate

**1. R65's read path works, but only via an undocumented legacy alias.** The premise of this phase — that
the read path already ships — **holds**: `GET /Users/{userId}/Images/Primary?api_key=…` returns `200
image/jpeg` today (verified against the two users who actually have photos). But that path is *absent
from the OpenAPI document*, so it is an undocumented compatibility alias, not a contract. The documented
route `GET /UserImage?userId=…` returns byte-identical output. **`RaviloArtworkService.kt:126` should move
to `/UserImage` as part of this phase** — not because it is broken, but because it is currently relying on
something the server no longer advertises, and a future Jellyfin is free to drop it.

**2. `fillHeight` and `quality` are silently ignored on the user image endpoint.** `RaviloArtworkService`
asks for `fillHeight=160&quality=90` and believes it receives a thumbnail. It does not: `fillHeight=160`,
`fillHeight=48&quality=50` and no parameters at all every return the **identical 568×568, 22 544-byte
original**. Jellyfin accepts the parameters and disregards them. This inverts FR-187-6's rationale — that
requirement argued a server-side re-encode is cheap because "the read path already asks Jellyfin for
`fillHeight=160`". It does not, and never has. **The re-encode this phase adds is therefore the only thing
bounding avatar bytes anywhere in the system**, which makes FR-187-6 load-bearing rather than tidy: without
it a 12 MP phone upload is served at full resolution to every TV, forever, through a cache that never
expires (see 3).

**3. The avatar cache has no TTL — it is permanent.** FR-187-7 says invalidation must happen "rather than
waiting for a TTL". There is no TTL to wait for: `RaviloArtworkService.readSimple()` returns the cached
file whenever it exists, with no age check, and the cache path is `"$avatarDir/$userId"` — keyed on the
user id alone. `RaviloImageUrl.avatar()` (`RaviloImageUrl.kt:31`) returns
`/api/tv/image/user/$userId/avatar`, likewise with no version component. So today a replaced photo would
be served stale **forever**, on every device, with no self-healing path at all. This is R214 with the
expiry removed.

**The fix FR-187-7 asked for is available.** `UserDto.PrimaryImageTag` is exposed and populated (`GET
/Users` returns e.g. `3b7110fc4514c914bc2591bde89a6879` for the users who have photos, `null` for those
who do not), and `GET /UserImage` accepts a `tag` query parameter. Two caveats found by probing:
Jellyfin **does not validate `tag`** — a deliberately wrong tag still returns `200` and the current image —
so it is usable as a cache-busting key but never as a staleness check; and the tag must therefore be
threaded through *jellystructure's own* URL and cache key (`avatarDir/$userId@$tag`), not merely forwarded
upstream. A user with no photo returns `404` with a JSON body, which `RaviloArtworkService`'s existing R132
"never cache a non-image" guard already handles correctly.

### Still unprobed — both need a write, see open questions 1 and 2

The read-only probe cannot settle these, and neither may be assumed:

- **That `POST /UserImage` accepts what its schema says**, and that a `204` really replaces the image and
  moves `PrimaryImageTag`. Safe to probe on a photo-less test account (`Test Stream` /
  `Test Føroyskt`) and reversible with the `DELETE`.
- **Whether changing a password invalidates existing access tokens** (open question 2, FR-187-8, and
  R234 FR-R234-7's blocked branch). This cannot be answered from the OpenAPI document — the response is a
  bare `204` either way. It needs a real change on a throwaway account: `POST /Users/New`, set a password,
  `AuthenticateByName` for a token, change the password, then re-issue a request with the **old** token and
  see whether it still authorises — then delete the user. Nothing about a real household account should be
  touched to answer this.

**FR-187-2 — One route per write, both scoped to the caller's own account.**
`POST /api/tv/account/photo`, `DELETE /api/tv/account/photo`, `POST /api/tv/account/password`. The
Jellyfin user id is taken **from the session** (`ravilo_device` → `jellyfinUserId`), never from the
request body or a query parameter. There is no route shape in which one viewer can name another
viewer's account — not "and check they match", but no such parameter existing at all. A kids profile is
a signed-in profile and gets the same rights over its own account.

**FR-187-3 — Jellyfin validates the current password; jellystructure never does.** The current password
is passed straight through to Jellyfin's own endpoint and its verdict is relayed. Do not verify it
locally by calling `authenticateByName` first (that mints a second token as a side effect and moves the
authority for "is this the right password" into the wrong process), and never compare, hash or store it.
A rejection comes back as a distinct, translatable outcome so R234 can say *"That current password isn't
right"* rather than a generic failure.

**FR-187-4 — Rate-limit the password route like the login route.** `LoginRateLimiter` exists precisely
because `POST /api/tv/login` is a synchronous credential proxy on an instance that Phase 167 made
internet-exposable; a password-change route that takes a current password is the same exposure with the
same brute-force shape. Reuse the existing limiter rather than adding a second policy, keyed per session
and per source, and make an exhausted limit a plain "try again in a moment" — never a lockout a viewer
can inflict on themselves from the sofa.

**FR-187-5 — The photo lives in Jellyfin's user image; jellystructure adds no store of its own.** This is
the answer to the open question the design pass left: R65 already reads Jellyfin's user Primary image, so
writing anywhere else would create a second representation of one fact and guarantee the two disagree
(the 185/R222 discipline, and the reason `RaviloDeviceService.decodeCapabilities()` is called from both
its consumers rather than duplicated). No new table, no new column, no file under `dataDir` except the
existing proxy cache. A photo set in Jellyfin's own web UI and a photo set in Ravilo are the same photo.

**FR-187-6 — Validate and normalise the upload server-side.** The client may send anything; the server
decides what Jellyfin receives. Enforce a content-type allowlist (JPEG/PNG/WebP), a maximum request size
(reject early, before reading the whole body into memory), and re-encode to a bounded square JPEG before
forwarding. Centre-crop to square: there is no crop UI in R234 (the owner did not ask for one) and every
surface renders the photo in a circle, so a non-square original must be cropped somewhere and the server
is the one place that does it once for everybody. Forward to Jellyfin as a **raw body with the real MIME
type in `Content-Type`** — `POST /UserImage?userId=…`, per FR-187-1's probe — not multipart, not base64.

> **Amended 2026-09-05 after the FR-187-1 probe: this requirement is load-bearing, not tidiness.** Its
> original rationale — "the read path already asks Jellyfin for `fillHeight=160`, so nothing downstream
> benefits from storing a 12 MP phone photo" — is false. Jellyfin 10.11.11 **silently ignores `fillHeight`
> and `quality`** on the user image endpoint and returns the stored original at full size every time
> (measured: `fillHeight=160`, `fillHeight=48&quality=50` and no parameters all return the identical
> 568×568 / 22 544-byte image). Combined with an avatar cache that has no TTL at all, the re-encode here
> is the **only** thing that ever bounds what a TV downloads. Pick the bound deliberately — the read path's
> intent was 160 px, so a 320 px square JPEG covers every surface at 2× — and treat a missing re-encode as
> a shipping blocker rather than an optimisation.

**FR-187-7 — A changed photo must not be served stale.** `RaviloImageUrl.avatar(userId)` builds a URL
with no version component, and `RaviloArtworkService` caches the proxied bytes on disk. That is
**R214's exact bug** — Ravilo showed a corrected poster's old bytes because the URL never changed — and
it will reproduce here the first time somebody replaces their photo. The avatar URL therefore needs a
change-keyed component, the proxy cache must be keyed on it, and a successful upload or delete must
invalidate the entry.

> **Confirmed and sharpened 2026-09-05 by the FR-187-1 probe.**
>
> **The problem is worse than written: there is no TTL.** This requirement said invalidate "rather than
> waiting for a TTL", implying a slow self-heal exists. It does not.
> `RaviloArtworkService.readSimple()` returns the cached file whenever it exists with no age check, and
> the cache path is `"$avatarDir/$userId"` — the user id alone. A replaced photo would be served stale
> **permanently**, on every device, with no recovery short of deleting the file by hand. R214 without the
> expiry.
>
> **The mechanism this requirement hoped for exists.** `UserDto.PrimaryImageTag` is exposed and populated
> (`GET /Users` returns a real tag for users with a photo, `null` for those without), so no server-held
> "photo last changed" timestamp is needed. Concretely:
> - thread the tag into jellystructure's own URL — `RaviloImageUrl.avatar(userId, tag)` →
>   `/api/tv/image/user/$userId/avatar?v=$tag` — since that is the URL Compose's `RemoteImage` and
>   `StoredSession.avatarUrl` actually key on;
> - key the disk cache on `"$avatarDir/$userId@$tag"` so a new tag is a cache *miss* rather than
>   something needing active eviction, and old entries become garbage rather than wrong answers;
> - **do not** rely on forwarding `tag` upstream as a correctness check: Jellyfin **does not validate it**
>   — a deliberately wrong tag still returns `200` and the current image. It is a cache-busting key only.
> - a user with no photo yields `404` + a JSON body, which the existing R132 "never cache a non-image"
>   guard already handles.
>
> **Move the proxy to the documented route while here.** `RaviloArtworkService.kt:126` fetches
> `GET /Users/{userId}/Images/Primary`, which still works but is **absent from 10.11.11's OpenAPI
> document** — an undocumented legacy alias. `GET /UserImage?userId=…` is the documented route and returns
> byte-identical output. Switching is a two-line change and removes a dependency on something the server
> no longer advertises.

**FR-187-8 — Tell the client what happened to its own session.** Jellyfin may or may not invalidate
existing access tokens when a password changes. This decides whether R234 can show *"Password changed"*
and return to Settings, or must sign every device out and send the viewer back to the login screen — and
Phase 141's per-`(device, user)` identity means one household password change could affect several TVs at
once. Probe it (FR-187-1), then make the route's response say plainly whether the caller's own token
survived, so the client renders the truth instead of guessing. If tokens do die, that is a
**re-authenticate**, not a silent failure: nothing may leave a device in a state where it appears signed
in and every subsequent request 401s.

**FR-187-9 — Show the photo in the admin, read-only.** Each user row on **Ravilo → Users & devices**
renders that user's photo in place of its initials chip, from the same `RaviloImageUrl.avatar()` the
clients use (`getUsers` is already called there — `server/routes/TvRoutes.kt:667`, and its `UserDto`
response already carries the `PrimaryImageTag` FR-187-7 needs, so no extra Jellyfin call is required).
Initials remain the fallback. The
admin gets **no** ability to set, replace or clear another user's photo in this phase: a photo is
something a person chooses about themselves, an operator clearing one is a moderation feature, and
moderation needs its own thinking about notification and recourse rather than a quiet button.

**FR-187-10 — Nothing about either operation reaches a TV.** The routes are platform-blind, but the
product decision is not: per R216's no-settings invariant and the owner's pick, a TV renders a photo and
offers nothing about changing one. That is enforced in the client (R234 FR-R234-2), so this phase adds no
TV-specific behaviour and no capability flag — the same route simply never gets called from a TV.

## Non-goals

- **No name editing.** The design labels the row "Photo and name" but only the photo is wired, and
  `displayName` comes from Jellyfin's own user record; renaming is its own decision with its own blast
  radius (every session's cached `displayName`, the profile picker, the admin table) and is not in scope.
- **No admin-side photo management** beyond rendering (see FR-187-9).
- **No preset-colour storage.** The mockup offers a row of preset colour avatars, which is *new stored
  state* with no home in Jellyfin's user record — see open question 3. Nothing is built for it here.
- **No crop, rotate or filter UI** — server-side centre-crop only (FR-187-6).
- **No password policy.** Jellyfin owns whatever minimum it enforces; jellystructure adds no rules of its
  own beyond R234's client-side "at least 6 characters" courtesy check, and never contradicts Jellyfin's
  verdict.
- **No password reset, recovery or admin-initiated change.** This is a viewer changing a password they
  already know. Forgotten passwords stay an admin-and-Jellyfin problem.
- **No per-device or per-profile-on-one-device photo.** One photo per Jellyfin user, everywhere.

## Open questions

1. ~~**Do all three endpoints exist and behave on 10.11.11?**~~ **Mostly answered 2026-09-05** — see
   FR-187-1. All three exist; none is at the believed path (`POST`/`DELETE /UserImage?userId=…` and
   `POST /Users/Password?userId=…`). The body-shape risk this question flagged is **resolved**: the image
   upload is a **raw binary body with the real MIME type in `Content-Type`** — neither multipart nor
   base64 — and the password body is `{"CurrentPw", "NewPw"}`. *Still open:* the read-only probe cannot
   confirm that a `POST` actually replaces the image and moves `PrimaryImageTag`. Probe on a photo-less
   test account (`Test Stream` / `Test Føroyskt`); the `DELETE` makes it reversible.
2. **Does changing a password invalidate existing tokens?** FR-187-8. **Still open, and not answerable
   from the OpenAPI document** — the response is a bare `204` either way. It needs a real password change
   on a **throwaway** account: `POST /Users/New`, set a password, `AuthenticateByName` for a token, change
   the password, re-issue a request with the *old* token, then `DELETE /Users/{userId}`. No real household
   account should be touched to answer it. This is the single answer that most changes R234's flow — one
   household change may sign out three TVs.
3. **Where would a preset colour live?** The mockup lets a viewer pick a colour instead of a photo, but
   Jellyfin's user record has no field for it and this phase deliberately adds no jellystructure-side
   store. Three honest options: drop the presets (initials keep their existing deterministic gradient),
   accept a jellystructure-side column and accept that it is one fact Jellyfin does not own, or store a
   generated solid-colour image *as* the user's photo (which makes "has a photo" and "chose a colour"
   indistinguishable — probably wrong). Owner decision needed; the mockup currently keeps the choice
   client-side, which is not shippable as-is.
4. ~~**Is there a Jellyfin policy flag that forbids a user changing their own password?**~~
   **Answered 2026-09-05: no such flag exists.** `UserPolicy` on 10.11.11 carries 45 properties and not
   one of them gates self-service password change. The only password-adjacent field is
   `PasswordResetProviderId` (which provider handles a *forgotten* password — not a permission), and the
   only account-scope flags are `IsAdministrator`, `IsDisabled` and `IsHidden`.
   `EnableUserPreferenceAccess` governs display preferences, not credentials. So FR-187-2's "a kids
   profile is a signed-in profile and gets the same rights over its own account" stands with nothing to
   honour, R234 hides no row, and open question 5 in R234 (kids profiles) is settled the same way — the
   server will not stop them, so the decision is purely the owner's, and the owner already made it.
   The one flag worth respecting for a different reason is **`IsDisabled`**: a disabled user cannot
   authenticate at all, so it never reaches these routes.
