# Phase 258 — A device enforces the policy Jellyfin has now, not the one it signed in with

## Status

`✓ Built` 2026-09-25 — from the dev review below, all seven items. `Planned` when written 2026-09-24
from a production finding; **dev-reviewed 2026-09-24 against `main` `9d2636bb`**.

### Build (2026-09-25)

- `DevicePolicyReconciler` (new): one `getUsersOrNull()` per pass; every user with a row compared after
  `DevicePolicy.of()`'s normalisation (the login's four rules, copied — item 4); `refreshPolicy()` rewrites
  all of a user's rows in one `updatePolicy` statement (item 3, migration `49.sqm` + the `.sq` CREATE
  TABLE) and evicts every one of their tokens from `validateDeviceToken`'s five-minute cache (item 1);
  `home_changed` to that user (FR-258-4); one `Policy refreshed` line per (device, field) and silence on a
  quiet pass (FR-258-7). Runs at start in the root scope beside the watchdog loop (item 6), every five
  minutes, and at the top of the `/api/tv/events` handler after registration, bounded to 3 s, when the
  user's last pass is older than 60 s (item 5). A `null` fetch (unreachable, non-2xx, unparseable) or a user
  absent from the answer changes nothing (FR-258-3). `loginDevice` also stamps `policy_refreshed_at`.
- Settings → Users & devices: `OverviewPolicy.known`/`stale` — *policy unknown · Jellyfin did not answer*
  when `/Users` failed (item 2), *· a device is still on an older policy* when any row differs from the live
  policy (FR-258-6, same comparison); the mockup `design/app/ravilo-users.html` carries the chip (item 7).
- Tests: `RaviloDevicePolicyTest` (6) and `DevicePolicyReconcilerTest` (5) in `linuxX64Test`; the
  acceptance script is `scripts/check-device-policy-drift.py`.
- Acceptance 1 reproduced on a copy of prod's DB before deploying: **17 rows · 4 drifted** (the two
  household TVs and the test account's two rows, all on tags). Acceptance 2–6: see below once deployed.

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
   *Dev review:* lean — do not spend the probe. Jellyfin's session manager addresses `UserUpdated` to the
   *edited user's* own sessions, and the 2026-08-30 probe showed the socket delivers nothing it was not
   subscribed to; an admin-key socket would hear about the admin. The connect trigger is the fast path.
2. A user deleted in Jellyfin: `getUsers()` omits them. Today their rows keep working until the
   Jellyfin user token fails. Should the reconciler treat "absent from `/Users`" as a signal at all
   (FR-258-3 says no, deliberately), and is the answer a separate phase that signs those devices out?
3. Interval. 5 minutes is a guess that keeps `/Users` at ~300 calls/day; the household will not
   notice one minute versus five, an operator toggling a tag and checking the TV will. 60 s on the
   connect path is the one number that matters to them.
   *Dev review:* with the token-cache eviction of item 1 below, five minutes is fine; without it, the
   worst case is interval **plus** the cache's own five minutes.

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

## Dev review (2026-09-24, against `main` `9d2636bb`)

Every cited path holds: the login writes all five fields from the `AuthenticateByName` policy
(`TvRoutes.kt:317-334`; `isKids = maxParentalRating != null` at `:325`); `validateDeviceToken` touches
`last_seen` and nothing else (`RaviloDeviceService.kt:143-150`); `visibleTo` is the allow-list and the tag
policy (`media/MediaStore.kt`); the overview builds its access line from the live `getUsers()`
(`TvRoutes.kt:906-932`); a paired screen is minted from the phone's row, token included
(`ScreenPairingService.kt:70-82`); and `allowedHash` is libraries × allowed × blocked in both
`HomeFeedService.kt:164` and `BrowseService.kt:260`. Seven items.

1. **FR-258-4's "by construction" is defeated for five minutes by a cache the spec did not see.**
   `validateDeviceToken` serves `DeviceData` from `tokenCache` for `TOKEN_CACHE_TTL_MS = 5 min`
   (`RaviloDeviceService.kt:16`, `:147`). Every `/api/tv/**` request's `device` — and so its `allowedHash`
   — comes from that cache, not the row; a rewritten row is invisible until the entry ages out, and
   FR-258-2's "a TV that just woke up gets today's policy before its first Home fetch" is false on the
   connect path unless the reconciler evicts first. The revoke paths already do this
   (`tokenCache.remove(...)` at `:245` and `:281`); the reconciler evicts every token of the user it
   rewrote — `remove` only, not `DeviceIdentityRegistry.forget`: the Jellyfin user token has not changed.
   Phase 142's own comment (`:241`) recorded this exact trap for revocation.
2. **`getUsers()` cannot say it failed, and FR-258-3 needs it to.** `JellyfinClient.getUsers`
   (`:381-384`) is `runCatching { … }.getOrDefault(emptyList())`: an unreachable Jellyfin, a non-2xx and
   a body that deserialises to nothing are all the empty list. FR-258-3's "leave the row" is then safe by
   accident (every user is "missing"), but acceptance 5's *one line for the failed pass* and FR-258-7's
   silence on a quiet pass cannot be told apart. Add a `getUsersOrNull()` — `List<JellyfinUser>?`, `null`
   = the fetch failed — R231's shape (`buildCanonicalContinueList`), for the same reason: a failure must
   never look like an answer. The overview's `allFolders = policy?.enableAllFolders ?: true`
   (`TvRoutes.kt:928`) is the same trap in display form — "All libraries" when Jellyfin is down — and can
   read *policy unknown* once it has a way to know.
3. **No `updatePolicy` query exists.** `RaviloDevice.sq` has `insertDevice` (INSERT OR REPLACE),
   `updateAppInfo`, `updateLastSeen` and `updatePublicAddress` (`:52-84`). The reconciler needs one
   `UPDATE … WHERE jellyfin_user_id = ?` covering the five fields and `policy_refreshed_at`, so a user's
   phone, screens and cast rows change in one statement. `policy_refreshed_at` is migration `49.sqm`
   (48 is the highest) **and** the column in the `.sq` `CREATE TABLE` — both, or the generated interface
   and the live schema disagree.
4. **Compare after normalising, exactly as `loginDevice` does, or every pass rewrites every row.**
   `loginDevice` lowercases tags (`encodeTags`) and normalises library GUIDs (`normalizeGuid`, `:81`)
   before storing; Jellyfin's `EnabledFolders` are raw. A reconciler that compares raw against stored
   sees a difference on every row every five minutes and FR-258-7 logs a change that is not one. Route
   the fetched policy through the same two helpers, then compare; `enableAllFolders ⇒ null` and
   `maxParentalRating != null ⇒ isKids` are the other two rules to copy from `TvRoutes.kt:322-325`.
5. **The connect trigger's place is the events handler's top**, after `validateDeviceToken` and before
   `sessionBridge.connect` (`Server.kt`, the `/api/tv/events` route) — and item 1's eviction has to
   happen there before the Home fetch the trigger exists for. Note the handler's own `validateDeviceToken`
   call (`:667`) is a cache hit too.
6. **"Before any request" is stronger than a start hook can promise; drop it.** Run the first pass in
   the root scope at start, beside the 30 s watchdog loop (`Main.kt:440-444`); a request in the first
   second sees the old row for a moment, which is today's behaviour, not a regression. Acceptance 2's
   "within one minute of start" is the right claim.
7. **FR-258-6's clause has its row and its mockup.** The access chips are `RaviloUsers.kt:163-166`
   (`OverviewPolicy` → "N of M libraries · allowed tags …"); the mirror is
   `design/app/ravilo-users.html:124/141` (`usr-access` chips). Add the clause in both in one pass, or
   the next design export removes it from the served page.

**Net effect.** One `getUsersOrNull`, one query + one migration, one normalise-then-compare, one
eviction, one start hook, one connect hook, one clause in two files. The design is right; the cache is
the part that would have made the first deploy look like it had not worked.
