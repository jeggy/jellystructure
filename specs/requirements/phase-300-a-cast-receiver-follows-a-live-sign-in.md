# Phase 300 — A cast receiver follows a live sign-in

> Production bug, 2026-10-02: a Mac signed in to Jellyfin again, and from then on every Chromecast, Nest Hub and
> speaker it had once enrolled refused to play — for every sender, silently, until re-paired by hand.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, not device-tested. Written 2026-10-02 (dev-authored), against `main` `f9618d0f`. Number given by the coordinator (admin
specs top at 299). The receiver page (`ravilo-cast`) is served by this backend (`/cast/`), so its half ships here too;
no installed app changes.

**Amends** phase 218 (FR-218-9: the receiver enrols *once per receiver*; FR-218-12: the receiver's row holds "the
enrolling user's" Jellyfin token, which is a copy of one sender's token with that sender's lifetime), and R245/R324's
hand-off (the receiver now redeems every sender's code).

## What was seen (production `v1.48-84-gb8dc5964`, Jellyfin 12.1, 2026-10-02)

1. The Mac test build signed in to Jellyfin fresh at 11:03 UTC (`AuthenticationSucceeded`). A music cast from the Mac
   to the Stue speaker then launched the receiver, and the backend logged `TV: Jellyfin rejected this device's paired
   token (HTTP 401) for user 7450… — negative-cached 10min; re-pair the device`.
2. In `ravilo_device`, the three receivers Stue, Gæsteværelse and Soveværelse TV hold the **same**
   `jellyfin_user_token`. No live device row holds it; it was the Mac's token before 11:03. `GET /Users/Me` with it →
   401. Two other receivers share the Pixel 9's current token → 200.
3. A cast from the Pixel to Stue at 10:16 UTC (the old token still worked) did **not** change Stue's token. Nothing
   ever re-enrols a receiver once it exists.

## Why (root cause)

- **A receiver borrows its sender's Jellyfin token.** `CastService.redeem` (phase 218) copies the minting device's
  `jellyfin_user_token` into the receiver's own `ravilo_device` row. Jellyfin gives one access token per
  (user, DeviceId) and replaces it when that DeviceId signs in again, so the copy dies the moment its sender
  re-authenticates. `ScreenPairingService` (phase 236) does the same for a Tizen/web screen.
- **A receiver enrols once and never again.** The receiver redeems the hand-off code only when it has no device token,
  or when the sender names a different receiver id (`mustEnrol` in `Receiver.kt`). Every sender mints a fresh code
  for every LOAD (`Cast.kt` `cast`/`castMusic`, and the Mac's Cast v2 sender through the same seam), but an enrolled
  receiver drops it unread. So a receiver's token is whatever its *first* sender held on the day it enrolled.
- **Nothing else repairs it.** A dead token is answered with 409 *re-pair it* (`JellyfinReauthRequiredException`),
  which the receiver does not treat as "enrol again", and the server has no path from "this receiver's copy is dead"
  to "this user has a live sign-in elsewhere".
- The same rule also mis-attributes casts between users: a second Jellyfin user casting to a receiver another user
  enrolled is never enrolled, so the receiver plays under the first user's account and library rules.

## Decision

**A borrowed token follows a live sign-in of the same user.** Three paths, each enough on its own for the common case,
together covering every case named above:

1. **Every hand-off adopts the sender's token** (the lean). The receiver redeems every code a sender sends; the server
   stores that sender's current token on the receiver's row for that user. A receiver is never older than its latest
   sender.
2. **A sender's new sign-in moves its receivers with it.** When a device signs in again and its Jellyfin token changes,
   every receiver/screen row of the same user that still holds the old token takes the new one in the same write.
   The production case (the Mac re-authenticating) never reaches a 401.
3. **A rejected borrowed token heals from the user's live devices.** When Jellyfin rejects a receiver's token, the
   server takes the token of the user's most recently seen own-sign-in device that Jellyfin accepts, stores it, and
   carries on. Mid-session failures and receivers whose page is still an old cached copy heal on their next request.

**Rejected: the receiver gets its own Jellyfin token.** jellystructure cannot authenticate as the user without a
password (`AuthenticateByName`), and the server's own API key would hand admin credentials to a receiver (the
2026-08-02 H2 finding). The one password-free way is **Quick Connect**: the server initiates under the receiver's own
DeviceId, the sender's token authorizes the code, the server exchanges the secret for a token bound to the receiver.
It is the better end state (the receiver's lifetime would be its own, and the Jellyfin dashboard would show one device
per receiver instead of a token shared with a phone), but it needs a server setting the household may have off
(*Enable Quick Connect*), three calls per enrolment against an API we have not probed on 12.1, and a fallback to this
phase's behaviour anyway when it is off. Recorded as the follow-up; not built here.

**Rejected: refresh only when the receiver's token is dead.** The server would have to probe Jellyfin on every
hand-off to know, and a token that is alive but belongs to a sender that is about to sign in again is the same bug one
step later. Adopting on every hand-off costs nothing (the code is already minted and sent).

## Requirements

**FR-300-1 — The receiver redeems every sender's code.** A LOAD that carries a non-empty `code` (only a sender's do)
is redeemed with the receiver's stored `receiverId`, whatever the receiver already holds. If the redemption fails
and the receiver already holds a device token, it plays with that token as before (a retried LOAD re-sends a used
code; a network blip must not stop a cast that would have played). With no token, a failure shows the no-server
screen as today. The receiver's own loads (next song, next episode, a restream) carry no code and never redeem.

**FR-300-2 — A redemption stores the sender's token, unless the sender's is known dead.** `CastService.redeem` writes
the minting device's current `jellyfin_user_token` onto the receiver's `(receiverId, user)` row (phase 218's upsert,
same device token, same row). The one exception: when the sender's token is negative-cached (Jellyfin rejected it
twice within ten minutes) and the receiver's own row for that user holds a token that is not, the receiver keeps its
own. A different user's code writes a different row (`PRIMARY KEY (device_id, jellyfin_user_id)`) and hands the
receiver that user's device token, so each user's playback runs under that user's token, ACL and kids rules.

**FR-300-3 — A re-sign-in moves the receivers that borrowed the old token.** When `loginDevice` replaces a non-borrowing
device's Jellyfin token (the row existed with a different token), every row of the same Jellyfin user of kind `cast`
or `screen` whose token equals the old one takes the new one, in the same transaction. Their cached `DeviceData` is
dropped, and the old token's positive validity verdict is forgotten so it is checked again before it is trusted.

**FR-300-4 — A rejected borrowed token heals.** When the paired-token check finds a `cast` or `screen` row's token
rejected, before answering *rejected* it tries, in order: the token now stored on that same row (a hand-off or
re-sign-in may already have replaced the copy this request carried); then the tokens of the same user's own-sign-in
rows (kind not `cast`/`screen`), most recently seen first, at most three distinct tokens. Each is checked with the
same cached check. The first one Jellyfin accepts is stored on the receiver's row and used for this request; none ⇒
*rejected* as today (409 *re-pair*). Only the same Jellyfin user's tokens are ever considered. A TV, phone, Mac or web
row never borrows: its own sign-in is the only source of its token.

**FR-300-5 — A repaired receiver is never blocked by the negative cache.** The cache is keyed by the token string, so a
row that takes a new token is checked afresh; the old token's negative entry stays (it is still dead). Every token
change (FR-300-2/-3/-4) drops the row's cached `DeviceData`, so the next request reads the new token.

**FR-300-6 — The Jellyfin session follows the token.** When a receiver's token changes while its phase-299 bridge is
open, the bridge is restarted with the new token, so the dashboard's session for that receiver runs under a live
sign-in. A bridge in its grace, or not open, is left alone.

**FR-300-7 — Redeeming is not rationed like a sign-in.** `/api/tv/cast/redeem` keeps its guess limiter, but on its own
key, and a successful redemption gives its attempt back: only failed codes count. (Every cast now redeems, and the
household shares one public address with five sign-ins a minute.)

**FR-300-8 — Tokens are never logged or returned.** Log lines name the receiver's device id, the user id and the donor
device id. Responses are unchanged (`PairResult`).

**FR-300-9 — Wire compatibility.** No DTO, route or enum changes. An older sender already mints a code per LOAD. An
older receiver page (cached on a device) never redeems again, and is covered by FR-300-3/-4. A newer receiver against
an older server redeems every code and the older server upserts the token: the same improvement, minus FR-300-2's
exception.

## Healing the three production receivers

After the deploy, nothing has to be run by hand:

- The first request any of them makes (a hand-off's redemption, a heartbeat, a playback start) goes through the
  paired-token check; their stored token is rejected; FR-300-4 finds user 7450's most recently seen own-sign-in row
  that Jellyfin accepts (the Mac, the Linux app or a TV signed in today) and stores its token. That is their next cast
  from any valid sender: the receiver page launches, redeems the sender's code (FR-300-1/-2), and plays with that
  sender's token.
- The negative cache is in memory and empties on the restart the deploy does; it never keyed on the receiver anyway.
- If no device of that user is signed in, nothing can heal them, as today, until a signed-in sender casts.

## Tests (CI)

- `ReceiverTokenPolicyTest` (unit): the hand-off decision (adopt; keep own when the sender's is dead and the
  receiver's is not; adopt when both are dead or the receiver has none), the donor order (same user only, own-sign-in
  kinds only, most recent first, the dead token and blanks skipped, three at most), and which rows follow a re-sign-in.
- `ReceiverTokenRefreshIntegrationTest` (`linuxX64Test`): a fake Jellyfin over loopback whose `/Users/{id}` accepts only
  live tokens, the real `RaviloDeviceService`, `CastService` and paired-token check on a temp database. Sender A enrols
  a receiver; A signs in again (old token now 401) and the receiver follows; B casts and the receiver plays with B's
  token; A casts again and it plays with A's; a receiver left on a dead copy with no hand-off heals from a live device
  on its next check; a second user's code gives the receiver that user's row; a user with no live device stays
  rejected.

## Out of scope

- Quick Connect tokens per receiver (above).
- Re-enrolment for `ravilo-screen` on each play (Tizen work is paused; FR-300-3/-4 cover its rows).

## Build notes (2026-10-02)

**Built, all FRs:**
- `tv/ReceiverTokenPolicy.kt` (new, pure): `borrowsToken` (`cast`, `screen`), `tokenOnHandoff` (FR-300-2), `donors`
  (FR-300-4: same user, own-sign-in kinds, newest first, distinct tokens, dead ones skipped, three at most),
  `followers` (FR-300-3).
- `RaviloDeviceService`: `loginDevice` moves the followers in its own transaction when a non-borrowing row's token
  changes (new `updateUserToken` query in `RaviloDevice.sq`; no schema change, no migration); `storedJellyfinToken`;
  `adoptJellyfinToken` (refuses a non-borrowing row and a token no own-sign-in row of the same user holds); a
  `tokenListener` called after every token change. It implements the new `BorrowedTokenStore`.
- `PlaybackService.kt`: the paired-token check is now `cachedTokenCheck` (unchanged body) plus `pairedToken`, which on a
  rejection of a borrowing row runs `healBorrowedToken`; `tvToken` and `tvTokenForClient` use the token it returns.
  `isTokenKnownDead` and `forgetTokenValidity` read and write the caches under their mutex.
- `CastService.redeem` (now `suspend`) applies `tokenOnHandoff`; its log line says enrolled / re-enrolled / kept.
- `JellyfinSessionBridge.refresh` (FR-300-6): an open bridge (not in its grace) is cancelled and joined, then started
  again with the new row, under the bridge lock, so a racing `connect` cannot start a second loop.
- `Main.kt`: installs the device service as the store, and the listener forgets the old token's validity and refreshes
  the bridge.
- `/api/tv/cast/redeem` (FR-300-7): key `cast-redeem:{client}`; `LoginRateLimiter.refund` gives a working code's
  attempt back.
- `ravilo-cast` `Receiver.kt` (FR-300-1): `mustEnrol` is "the LOAD carries a code"; `enrol()` redeems, goes on with the
  held token when the redemption fails and one is held, and drops the cached config when the device token changes (a
  different user).

**Deviations:** none from the FRs. One addition: the heal remembers the receiver's own Jellyfin identity for the borrowed
token for that request (phase 224's registry), so the call goes out as the receiver rather than as the donor.

**Verified (CI):** `ReceiverTokenPolicyTest` (5) and `ReceiverTokenRefreshIntegrationTest` (fake Jellyfin over the
loopback that accepts only live tokens; the real services, paired-token check and bridge): A enrols, A signs in again
⇒ the receiver follows with no hand-off and its bridge reopens on the new token; B casts ⇒ same row, same device token,
B's token, bridge follows; A casts ⇒ A's; a dead copy no row holds heals from the Pixel's sign-in and is stored; a
second user's code ⇒ their own row and device token, the first user's row untouched; a user with no live sign-in stays
rejected and never gets another user's token; a phone's own dead token is never replaced. Full `linuxX64Test` 874/874,
`:shared:desktopTest`, `:ravilo-castv2:jvmTest`, `:ravilo-cast:jsBrowserProductionWebpack`,
`:ravilo-android:assembleRelease`, and the check scripts green. `CastServiceTest` updated for `suspend redeem`.

**Not covered by CI:** the receiver's FR-300-1 (`ravilo-cast` has no JS test setup; it is a two-line rule, built).

**Needs a deploy and devices:** the backend deploy also serves the new receiver page. Verify: (1) the backend log on
the first request of Stue / Gæsteværelse / Soveværelse TV shows `TV: receiver cast-… (user …) took a live Jellyfin
sign-in from device …` or, with the new receiver page, `Cast receiver cast-… re-enrolled with the sender's sign-in`;
(2) a music cast from the Mac to Stue plays; (3) a cast from the Pixel to Stue plays and the log says re-enrolled;
(4) sign the Mac in again, cast from the Pixel: it plays, and the log has `receiver(s) of user … followed device …`;
(5) the Jellyfin dashboard still controls the speaker (299) after (4).
