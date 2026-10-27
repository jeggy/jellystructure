# A device whose Jellyfin user is gone is signed out and removed, and nothing keeps asking for that user

Task: DEB-2672

> Owner, 2026-10-27 (DEB-1076): reported the log flood at 12:17Z, and at 12:28Z said *"yes"* to the recommendation:
> spec it, one small backend build piece, Tróndur places it. He said the noise is harmless until then.

## Scope

Backend, plus one line on the admin's *Users & devices* page. No Ravilo client change: a removed device's next
call is refused exactly as after *Sign out everywhere*, which every client already handles.

This answers phase 258's open question 2 and amends FR-258-3, which said a user missing from `/Users` leaves every
row alone and signs nothing out. The dated amendment is in `phase-258-device-policy-follows-jellyfin.md`. Phase
143's *Sign out everywhere* (`deleteAllForUser` + `SessionService.revokeAllForUser`) is the removal path, reused
and not changed. R231's rule that a failed fetch never overwrites a good value holds throughout.

## What happens today

Measured on prod 2026-10-27, read-only:

- **~67 000 `Jellyfin getUserDataBulk failed: 404 Error processing request.`** lines in two hours, and the same 404
  from `getResumeItemsAll`, `getRecentlyPlayedAll` and `getRecentlyTouched`.
- **41 of the 48 users the refreshers serve no longer exist in Jellyfin.** Every one is a QA throwaway account,
  with **66 `ravilo_device` rows** between them. QA deleted the Jellyfin accounts after each test. Nothing removed
  the devices, so both refreshers still pick them up: the window is 30 days of `last_seen`
  (`RECENTLY_SEEN_WINDOW_MS`, `PlaystateCache.kt:26`, `HomeFeedService.kt:76`).
- **The summary lines say all is well.** `Playstate refresh: 48/48 users refreshed, 0 timed out or failed` and
  `Continue Watching refresh: 48 refreshed, 0 failed`. The cause is `warnOnFailureOrDefault`
  (`JellyfinClient.kt:2030`). It logs the failure, then returns an empty list. To the caller that is a successful
  refresh of a user who has watched nothing.
- **The reconciler already sees it and does nothing about it.** `DevicePolicyReconciler.pass()` reads `/Users`
  every five minutes, at start and on an events-socket connect. It skips a user who is not in the answer
  (`DevicePolicyReconciler.kt:86`, *"absent is not unrestricted and not gone"*). That was phase 258's deliberate
  FR-258-3, and the question it left open is the one this spec answers.
- **Main turns "no Jellyfin configured" into an empty list**, not `null` (`Main.kt`, the `fetchUsers` lambda).
  The policy pass never had to care. This spec does (FR-1).

## Requirements

- **FR-1 — What counts as gone.** A Jellyfin user is *gone* when **two complete `/Users` reads in a row** both lack
  them. Both reads are taken by `DevicePolicyReconciler`'s existing passes; nothing adds a separate poll. A read is
  **complete** only when all of these hold:
  1. it came back 2xx and parsed whole (`getUsersOrNull` answered non-`null`);
  2. it lists at least one user;
  3. it lists at least one administrator. A Jellyfin server always has one, so a list without one is not the
     whole user list.

  A read that fails any of these is **neutral**: it marks nobody, clears nobody, and removes nothing. That includes
  the empty list Main returns when no Jellyfin is configured. The rule runs per user:
  - the first complete read that lacks a user **marks** them;
  - any complete read that lists them **clears** the mark;
  - a second complete read that lacks a marked user makes them **gone**.

  A neutral read between the two leaves the mark as it is. Marks live in memory only, so a restart clears them and
  can only delay a removal, never cause one. Ids are compared after the same `normalizeGuid` that `loginDevice`
  applies, so a format difference never reads as absence.
- **FR-2 — A read that loses everyone removes no one.** If a complete read lists **none** of the users who have a
  device row, nobody is marked, and the pass logs one warning: *"User check: none of the N signed-in users is in
  Jellyfin's answer, so nothing is removed. Is `jellyfinUrl` pointing at the right server?"* This is what a
  repointed or freshly restored Jellyfin looks like. Signing out the whole household on it is the one outcome this
  spec must never produce. Prod passes the check: 7 of 48 users are present.
- **FR-3 — The refreshers stop asking at once.** From the moment a user is **marked**, every background loop that
  reads Jellyfin on a user's behalf skips them. That covers the playstate refresh (`PlaystateCache.refreshAll`), the
  Continue Watching refresh (`HomeFeedService.refreshAllContinueLists`, which runs resume, recently played, recently
  touched and next-up), and any other loop that walks `allDevices()` per user and calls a user-scoped Jellyfin
  endpoint; the build lists the ones it found. Skipping is reversible: a cleared mark puts the user back on the next
  cycle. A skipped user is not counted as refreshed or as failed. They get their own clause, `· N skipped (not in
  Jellyfin)`, so the line still adds up. A request a device makes in person is not skipped; it fails as it does
  today until the removal.
- **FR-4 — A gone user is signed out everywhere.** When a user becomes gone, the server does exactly what the admin's
  *Sign out everywhere* does (`TvRoutes.kt`, `POST /tv/admin/users/{userId}/signout-all`):
  `deviceService.deleteAllForUser(uid)`, which removes every device row and evicts its token from the token cache
  and `DeviceIdentityRegistry`, then `sessionService.revokeAllForUser(uid)`. A tune or playback in flight ends the
  way it ends after that action today. No new teardown path is added.

  In the same step, and so that no path can bring the user back:
  - the user's jellystructure API keys are **revoked** (`api_key.revoked = 1`). The rows stay, so the admin's key
    list still shows what existed;
  - **unredeemed** grants for that user are deleted: `cast_handoff`, `ravilo_pairing`, and `tv_signin_code` rows
    approved for them. An approved code that has not been redeemed yet would otherwise mint a new device for a user
    who no longer exists;
  - the user's entries in the in-memory caches are dropped: the playstate map, the Continue Watching list,
    `lastPassForUser` and the mark itself.
- **FR-5 — The user's own data goes with them.** Nothing can sign in as that id again, because a recreated Jellyfin
  account gets a new id. So the rows that exist only to serve that viewer are deleted in the same step: from
  `ravilo_behaviour`, `ravilo_config`, `recommendation`, `ai_order`, `suggestion_viewer`, `audiobook_progress`,
  `audiobook_bookmark`, `dashboard_visit` and `playback_outbox`. An outbox write would 404 forever for a user who
  is gone.

  Two kinds of row are **kept**, because they are the operator's record rather than the viewer's state:
  - `playback_session`, which ages out under its own 7-day retention;
  - `support_ticket` and `support_ai_turn`. A support conversation is something the admin may still need to read.

  Every delete is keyed on the gone user's id alone. The step runs in one transaction, so a failure leaves the user
  marked and the next pass retries the whole step.
- **FR-6 — The removal is written down twice, once per user.**
  - **Activity** gets one line, category `auth`, at info: *"Signed out <username>: no longer in Jellyfin.
    Removed <n> devices, <k> admin sessions, <a> API keys."* The marking pass also writes one line per newly marked
    user: *"User check: <username> is not in Jellyfin's answer. Their devices will be removed if the next check
    agrees."* A quiet pass writes nothing.
  - **Users & devices**, while a user is marked, shows *"not in Jellyfin · removed at the next check if still
    absent"* on that user's access line. Today that line reads *"policy unknown · Jellyfin did not answer"*, which
    is false here: Jellyfin answered. Once the user is removed, the page shows one `tiny muted` line per removed
    user under the list, for seven days: *"<username> was removed <when>: no longer in Jellyfin (<n> devices)."*
    That list is kept in memory, so a restart empties it; Activity is the durable record. Both are additive fields
    on the overview payload (FR-8). The page uses the classes it already has, so no new CSS and no design round
    is needed.
- **FR-7 — A failed refresh counts as failed.** The user-scoped reads the refreshers use (`getUserDataBulk`,
  `getResumeItemsAll`, `getRecentlyPlayedAll`, `getRecentlyTouched`, `getNextUp`) tell their caller when they
  failed, and with which HTTP status. Each gets a failure-reporting variant beside it, the way `getUsersOrNull` sits
  beside `getUsers`. The existing signature stays and delegates with `.orEmpty()`, so `getUserDataBulk`'s other
  callers keep their behaviour: audiobooks, playback, loose films. One warning line per failed call stays as it is.
  - `PlaystateCache.refreshOne`, through `PlaystateHydrator`, returns `false` when any of its reads failed. The
    summary counts the user as failed, and the user's previous map is kept (R231).
  - `buildCanonicalContinueList` answers `null` when any of its four reads failed, so the previous list is kept and
    the summary counts the user as failed.

  The other ~20 methods that use the same pattern in `JellyfinClient` are out of scope. Only the refreshers' reads
  are changed.
- **FR-8 — A 404 is a hint, never a verdict.** When one of FR-7's reads fails with **404** for a user, the
  refresher asks the reconciler to check that user: `reconcileOnConnect(uid)`, with its existing 60-second
  freshness rule and its mutex, so a burst of 404s costs at most one `/Users` read a minute. The 404 itself never
  marks, skips or removes anyone. Only FR-1's reads do. A 404 for a user who *is* in `/Users`, such as an item
  deleted mid-cycle, simply leaves the user listed and changes nothing.
- **FR-9 — The wire.** No field an installed client reads is removed or retyped. A removed device's next
  `/api/tv/**` call gets the same refusal *Sign out everywhere* produces today, and the client shows its sign-in
  screen as it does then. The admin overview gains two optional fields. `OverviewPolicy.absent: Boolean = false`
  carries FR-6's marked line. `removed_users: [{username, removed_at, device_count}]` sits on the overview body,
  default empty. The admin frontend renders both as pushed. Nothing is derived in the frontend.

## Non-goals

- Reacting to Jellyfin's user-deleted event. As in phase 258, the poll is the floor, and an event that was assumed
  is not one that arrives.
- A setting to switch this off, or a grace period longer than two passes (about five minutes). A user who is not in
  `/Users` already fails every call their devices make. Two passes are there to make sure a bad answer does not
  remove anyone, not to keep the account usable.
- Undo. A deleted Jellyfin account cannot be restored under the same id, so nothing could resume the data.
- Cleaning up QA's own habits. QA deleting its throwaway accounts was correct. This spec makes the server keep up.
- Generalising `warnOnFailureOrDefault` across `JellyfinClient` (FR-7's last line).

## Acceptance

Backend-only checks on prod after the deploy. No device is needed.

1. **Before the deploy**, on a read-only copy of prod's DB and Jellyfin's `Users` table: the distinct
   `ravilo_device.jellyfin_user_id` values that are not in `Users` are 41, holding 66 rows. All 41 are QA
   throwaway names. The 7 present users and their row count are written down. Today's pass reports
   `usersSkipped = 41` and not more, which shows the id comparison matches every real account (FR-1's
   normalisation).
2. **First pass after start:** Activity has 41 *"User check: … is not in Jellyfin's answer"* lines and no removal.
   The next playstate and Continue Watching summaries show `41 skipped (not in Jellyfin)`, and the 404 warnings stop
   (FR-3).
3. **Within two passes, about five minutes:** 41 *"Signed out …"* Activity lines. The 66 rows are gone. No row of
   the 41 is left in FR-5's delete list. The 7 real users' devices, sessions and data are unchanged against
   step 1's counts. *Users & devices* lists the 41 under *removed*.
4. **For the next hour**, there is no `Jellyfin getUserDataBulk failed: 404` line and none from the other three
   reads. The summaries add up: `7/7 refreshed` (or a real failure counted as failed), no `skipped` clause.
5. **A failed `/Users` removes nothing**, checked in `linuxX64Test`, not on prod: with a fake `fetchUsers`, a user
   marked by one complete read, then a `null` read, then an empty read, then a read with no administrator, is still
   marked and not removed. A complete read that lists them clears the mark. A complete read that lists none of the
   users with a device marks nobody (FR-2).
6. **A 404 alone removes nothing**, also in `linuxX64Test`: a refresher whose read 404s for a user who is in the
   fake `/Users` triggers one connect-style pass, and the user is neither marked nor skipped (FR-8).

## Where it lands (for the build)

- `DevicePolicyReconciler`: the complete-read test, the marks, the FR-2 guard, and an `onUserGone(uid, username)`
  callback beside `onPolicyChanged`. It stays a pure decision over `fetchUsers`, so `DevicePolicyReconcilerTest`
  covers FR-1, FR-2 and acceptance 5 without a database.
- `Main.kt` wires `onUserGone` to one function, in `RaviloDeviceService` or beside it, that runs FR-4 and FR-5 in a
  single transaction and then writes FR-6's Activity line. The marked set is readable by both refreshers and by the
  overview route.
- `JellyfinClient`: a failure-reporting variant of each of the five reads named in FR-7. `PlaystateHydrator` uses
  it for the bulk read. `PlaystateCache` and `HomeFeedService`: skip the
  marked users (FR-3), count failures as failed (FR-7), and call `reconcileOnConnect` on a 404 (FR-8).
- `TvRoutes.kt` overview, and `src/wasmJsMain/.../RaviloUsers.kt`, for FR-6 and FR-9's two fields.
- Tests: `DevicePolicyReconcilerTest` (FR-1, FR-2, acceptance 5) and `PlaystateCacheTest` (FR-3, FR-7, FR-8), in
  `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew linuxX64Test --console=plain`. Mind the native rule: no commas in
  backtick test names.
- No migration: nothing gains a column. No new dependency.
