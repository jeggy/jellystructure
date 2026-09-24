# Phase 258 — A device enforces the policy Jellyfin has now, not the one it signed in with

## Status

`Planned` — written 2026-09-24 from a production finding, not dev-reviewed, not built. Spec first.
Amends **Phase 142** (FR-AUTH2: the per-device library allow-list) and its tag follow-up; no Ravilo
client change, no new payload field.

In one line: the admin page said a test account could see four tags; the account's TV was enforcing
one. Both were telling the truth about a different copy of the same policy, and only the TV's copy
decides what plays.

## What the server does today

- **The policy is copied once, at sign-in.** `POST /api/tv/login` (`TvRoutes.kt:318–336`) takes
  `EnableAllFolders`/`EnabledFolders`, `AllowedTags`/`BlockedTags`, `IsAdministrator` and
  `MaxParentalRating` from the `AuthenticateByName` response and writes them into the `ravilo_device`
  row (`allowed_libraries`, `allowed_tags`, `blocked_tags`, `is_admin`, `is_kids`) through
  `RaviloDeviceService.loginDevice()`. Phase 142's own comment states the refresh rule: *"refreshed on
  every login so a Jellyfin-side access change catches up the next time the viewer signs in."*
- **A TV never signs in again.** The device token is durable; `validateDeviceToken()`
  (`RaviloDeviceService.kt:143`) reads the row on every request and touches `last_seen`, nothing else.
  A viewer whose access was widened in Jellyfin on day 1 is still fenced by day-0's copy on day 30.
- **Every read path enforces the row, not Jellyfin.** `MediaItem.visibleTo(device)`
  (`MediaStore.kt:60`) is the library allow-list **and** `passesTagPolicy()` over the row's tags;
  `requireVisible()` (`PlaybackService.kt:400`) refuses playback on it (403 *Item not visible to this
  device*), and `HomeFeedService`, `BrowseService`, `UpcomingService`, `PlaystateCache` and
  `CastService` all filter by the same `DeviceData`.
- **The admin page shows something else.** Settings → Users & devices (`TvRoutes.kt:908–933`) builds
  its *"3 of 6 libraries · allowed tags …"* line from `jellyfinClient.getUsers()` — the **live**
  policy — and never from the rows. So the operator sees the access they granted, while the household
  sees the access that was true at sign-in. Nothing on either side says the two differ.
- **Derived devices inherit the stale copy.** A phone-minted screen or Chromecast device
  (`ScreenPairingService.kt:70–83`, `CastService`) is created from the *phone's* row, so a stale phone
  breeds a stale receiver, and a fresh sign-in on the phone does not reach the receiver it minted.

### Measured, 2026-09-24

Every `ravilo_device` row compared with the user's live Jellyfin policy (`/Users`): **4 of 17 rows
disagree**, all on tags. A household member's two TVs (created 2026-09-04/05) hold one allowed tag
where Jellyfin now grants three — every title under the two newer tags has been invisible on both TVs
for weeks, while the admin page listed the tags as allowed. The test account's two rows hold one of
four. Library sets agree on every row (nobody has changed one since sign-in). This is the same shape as
R202/R231: a rule the spec states, a copy the code keeps, and no mechanism keeping them equal.

## Requirements

- **FR-258-1 — The row follows Jellyfin.** The five policy fields on `ravilo_device`
  (`allowed_libraries`, `allowed_tags`, `blocked_tags`, `is_admin`, `is_kids`) are a **cache of
  Jellyfin's policy with a bounded age**, not a sign-in record. A change made in Jellyfin reaches every
  row for that user within the refresh interval, with no sign-in, restart or admin action.
- **FR-258-2 — One reconcile, all rows.** A reconciler fetches the policies with one
  `jellyfinClient.getUsers()` call (the admin key; the same call the overview already makes) and
  rewrites every row whose stored fields differ, keyed on `jellyfin_user_id` — so the derived screen and
  cast rows are corrected by the same pass as the phone that minted them. It runs **at backend start**
  (the four drifted rows above are corrected by the first deploy, before any request), then **every 5
  minutes**, and **on an events-socket connect** for that device's user when the user's last reconcile
  is older than 60 s (a TV that just woke up gets today's policy before its first Home fetch).
- **FR-258-3 — A failed fetch changes nothing.** Jellyfin unreachable, a non-2xx, or a user id
  missing from the response leaves the row exactly as it is. A refresh may never widen access on an
  error (in particular a missing user is **not** "unrestricted") and never narrow it on an error
  (the row is not cleared, the device is not signed out). Deleting a user in Jellyfin is out of scope
  (see open questions).
- **FR-258-4 — A rewrite is visible to the caches it invalidates, by construction.** Every feed cache
  keys on the row's values (`allowedHash` in `HomeFeedService.kt:164,179,361,792,811`, R233
  FR-R233-5's `(user, visibility scope)` rule), so a changed row misses the cache without an explicit
  invalidation — the phase must **not** add a second invalidation path. R248's `home_changed` push is
  sent to the user's connected devices after a rewrite that changed the fields, so an open Home
  re-fetches rather than waiting for the viewer to leave and return.
- **FR-258-5 — Sign-in keeps working as today.** `loginDevice()` still writes the policy from the
  `AuthenticateByName` response (it is the freshest possible copy); nothing about the login route
  changes. The reconciler is in addition to it, not instead of it.
- **FR-258-6 — The row records when it was last true.** A `policy_refreshed_at` column (epoch millis)
  is written by both paths. The admin overview's user line reads the live policy as today and adds
  nothing while the rows agree; when any of a user's rows disagrees with the live policy (a reconcile
  has not run yet, or has been failing) the line says so in one clause — *"· a device is still on an
  older policy"* — so the contradiction that hid this bug is at least named. No per-device detail, no
  action button.
- **FR-258-7 — One log line per change.** `Policy refreshed: user <name>, device <name>: tags
  <old> → <new>` (and the same for libraries / admin / kids), at info; silence when nothing changed.
  The 5-minute pass logs nothing on a quiet cycle.

## Non-goals

- A Ravilo client change. The client renders what the server sends; it never sees the policy.
- Reacting to Jellyfin's WebSocket. Phase 181 found the listener subscribes to nothing useful and
  `LibraryChanged` is never sent; whether `UserUpdated` reaches an admin-key socket is an open question
  below, and a poll is the correct floor either way.
- Per-request policy fetches inside `validateDeviceToken()`. Authentication must not depend on
  Jellyfin being up, and a fetch per `/api/tv/**` call is the R230-shape (94 requests/user) this
  project just paid for.
- Reworking how a screen or cast device is enrolled. They stay copies of the phone at creation; the
  reconciler is what keeps them right afterwards.
- Age-rating (kids) gating semantics. `is_kids` is refreshed; what it gates is unchanged.

## Open questions

1. Does Jellyfin 12.1 push `UserUpdated` / `UserPolicyUpdated` on the admin key's `/socket` when an
   operator edits a user? If yes, it becomes an extra trigger (with the poll as backstop); measure
   before relying on it — Phase 181's listener is the precedent for a message that was assumed and
   never arrived.
2. A user deleted in Jellyfin: `getUsers()` omits them. Today their rows keep working until the
   Jellyfin user token fails. Should the reconciler treat "absent from `/Users`" as a signal at all
   (FR-258-3 says no, deliberately), and is the answer a separate phase that signs those devices out?
3. Interval. 5 minutes is a guess that keeps `/Users` at ~300 calls/day; the household will not
   notice one minute versus five, an operator toggling a tag and checking the TV will. 60 s on the
   connect path is the one number that matters to them.

## Acceptance (prod backend, no client change)

1. **Before:** the comparison script — every `ravilo_device` row's five fields against `/Users` —
   reports the 4 drifted rows of 2026-09-24.
2. Deploy. Within one minute of start the same script reports **0** drifted rows; the log shows one
   `Policy refreshed` line per corrected row (4), naming the tag sets; the household member's TV shows
   the two newer tags' titles on Home **without** signing in (verified from that device's own token via
   `/api/tv/home`, per the device-token recipe).
3. In Jellyfin, add a tag to the test account. Within 5 minutes its rows carry it and
   `/api/tv/home` for its TV device includes a title under that tag; `POST /api/tv/playback/start`
   for that title succeeds where it returned 403 before. Remove the tag: within 5 minutes both revert.
4. Reconnect a device's events socket (restart the app): if its user's last reconcile is older than
   60 s, one `getUsers()` call is made before Home is served.
5. Stop Jellyfin; wait one interval. No row changes, no device is signed out, the log carries one
   line for the failed pass and no `Policy refreshed` line. Start it; the next pass is quiet.
6. Settings → Users & devices: with a row deliberately edited to an older policy (SQL), the user's
   line carries *"a device is still on an older policy"*; after the next pass the clause is gone.
