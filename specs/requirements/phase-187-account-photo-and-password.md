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

**Status:** Planned (design-authored 2026-09-03, not yet dev-reviewed).

Design: built into the mockups at `design/ravilo/Ravilo Mobile.html` (profile sheet · Your profile ·
Settings → Account), `design/ravilo/Ravilo TV.html` (photo rendering + Settings → Account → change
password) and `design/app/ravilo-users.html` (read-only photo per user row). No directions file — the
owner picked the shape up front from a written set of options rather than from drawn frames.

## Current state

**The photo is already a solved problem in one direction.** R65 wired the whole read path and it is live:

- `TvRoutes.kt:302` sets `avatarUrl = RaviloImageUrl.avatar(device.jellyfinUserId)` on the login
  response, and `:328` does the same for every profile in the picker list.
- `RaviloArtworkService.kt:126` proxies it from Jellyfin —
  `GET /Users/{userId}/Images/Primary?api_key={token}&fillHeight=160&quality=90` — and caches to disk.
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
forwarding — the read path already asks Jellyfin for `fillHeight=160`, so nothing downstream benefits
from storing a 12 MP phone photo. Centre-crop to square: there is no crop UI in R234 (the owner did not
ask for one) and every surface renders the photo in a circle, so a non-square original must be cropped
somewhere and the server is the one place that does it once for everybody.

**FR-187-7 — A changed photo must not be served stale.** `RaviloImageUrl.avatar(userId)` builds a URL
with no version component, and `RaviloArtworkService` caches the proxied bytes on disk. That is
**R214's exact bug** — Ravilo showed a corrected poster's old bytes because the URL never changed — and
it will reproduce here the first time somebody replaces their photo. The avatar URL therefore needs a
change-keyed component (Jellyfin's own `PrimaryImageTag` for the user if the probe in FR-187-1 confirms
one is exposed; otherwise a server-held "photo last changed" timestamp), the proxy cache must be keyed
on it, and a successful upload or delete must invalidate the entry rather than waiting for a TTL.

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
clients use (`getUsers` is already called there — `TvRoutes.kt:667`). Initials remain the fallback. The
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

1. **Do all three endpoints exist and behave on 10.11.11?** FR-187-1. The image upload's body shape is
   the specific risk: if it is base64-with-header rather than multipart, the client contract in R234
   changes shape too, so probe before either spec is built.
2. **Does changing a password invalidate existing tokens?** FR-187-8. This is the single answer that most
   changes R234's flow, and it cannot be guessed — one household change may sign out three TVs.
3. **Where would a preset colour live?** The mockup lets a viewer pick a colour instead of a photo, but
   Jellyfin's user record has no field for it and this phase deliberately adds no jellystructure-side
   store. Three honest options: drop the presets (initials keep their existing deterministic gradient),
   accept a jellystructure-side column and accept that it is one fact Jellyfin does not own, or store a
   generated solid-colour image *as* the user's photo (which makes "has a photo" and "chose a colour"
   indistinguishable — probably wrong). Owner decision needed; the mockup currently keeps the choice
   client-side, which is not shippable as-is.
4. **Is there a Jellyfin policy flag that forbids a user changing their own password?**
   `JellyfinPolicy` is already parsed from the `AuthenticateByName` response for Phase 142's library
   filtering. If it carries such a flag, FR-187-2's "every signed-in profile" should honour it and R234
   should hide the row rather than let the attempt fail at the server.
