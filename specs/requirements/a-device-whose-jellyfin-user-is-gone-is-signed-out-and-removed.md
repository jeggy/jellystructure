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
  can only delay a removal, never cause one. Ids are compared after `normalizeGuid`, on both sides, so a format
  difference never reads as absence. `loginDevice` does not normalise the id: it normalises the allowed libraries
  and stores `jellyfin_user_id` exactly as its caller passed it (corrected 2026-11-01, FR-12).
- **FR-2 — A read that loses everyone removes no one.** If a complete read lists **none** of the users who have a
  device row, nobody is marked, and the pass logs one warning: *"User check: none of the N signed-in users is in
  Jellyfin's answer, so nothing is removed. Is `jellyfinUrl` pointing at the right server?"* This is what a
  repointed or freshly restored Jellyfin looks like. Signing out the whole household on it is the one outcome this
  spec must never produce. Prod passes the check: 7 of 48 users are present. **Restated over the wider set by the 2026-11-01 amendment,
  below.**
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
  is gone. **`ai_viewer` and a queued re-rank joined this list on 2026-11-01, below.**

  Two kinds of row are **kept**, because they are the operator's record rather than the viewer's state:
  - `playback_session`, which ages out under its own 7-day retention;
  - `support_ticket` and `support_ai_turn`. A support conversation is something the admin may still need to read.

  Every delete is keyed on the gone user's id alone. The step runs in one transaction, so a failure leaves the user
  marked and the next pass retries the whole step.
- **FR-6 — The removal is written down twice, once per user.**
  - **A user with no device row reads differently: 2026-11-01 amendment, below.**
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
  freshness rule and its mutex, so a burst of 404s costs at most one `/Users` read a minute (the 2026-11-01
  amendment corrects how that rule holds for an absent user). The 404 itself never
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

Backend-only checks on prod. No device is needed and no device-board row is claimed. Steps 1 to 4 were rewritten on
2026-11-01, because DEB-3284 cleared the state they described. The numbers measured that day are in the amendment
below, and they are not expected values: step 1's count on the day is the baseline.

1. **Before the swap**, on a read-only copy of prod's DB and of Jellyfin's `Users` table, both with their sidecars:
   the candidates of FR-10, split into *listed* and *absent*, with the row count per table for each. Compare after
   `normalizeGuid`: `Users.Id` is stored dashed and uppercase, so a raw comparison reads every account as absent.
   Write down the forms found per table (FR-12). The absent ids are named from `playback_session.owner_name` and
   Jellyfin's `ActivityLogs`. **An absent id with a name that is not `qa-…` goes to Tróndur before the swap**: the
   first pass deletes its data for good. Counts go on the board, never names.
2. **The first two passes after start.** Activity has one *"User check: …"* line per absent id on the first pass and
   no removal. By the second pass (about five minutes) it has one removal line per id. No absent id is left in
   FR-5's delete list, no `api_key` of theirs is unrevoked, and `playback_session`, `support_ticket`,
   `support_ai_turn` and `suggestion_dismissal` have the rows they had (retention aside). **No listed user lost
   anything:** every device and session row from step 1 is still there, and no listed user's count fell to zero in
   any table. A count may otherwise move by normal use, such as a sign-in or a rebuild. The `__global__` row of
   `ravilo_config` hashes the same as in step 1 unless the admin saved it.
3. **Fixture A, a user whose devices stay.** A leftover leak found at deploy time can be extra evidence, never the
   fixture. Create a per-run throwaway local Jellyfin user, sign it in twice through `POST /api/tv/login` with two
   made-up device ids, then delete the user in Jellyfin and leave the rows. The next pass marks them: one *"User
   check"* line, `1 skipped (not in Jellyfin)` on both summaries, `policy.absent = true` on the overview. The pass
   after removes them: one *"Signed out …"* line, both rows gone, `removed_users` lists the user, and the 404s for
   that user stop.
4. **Fixture B, a user whose devices went first, and the id's two forms.** Create a second throwaway user and sign it
   in the same way. Switch its AI re-rank off, which writes its `ai_viewer` row. Pass its id in the dashed form:
   `GET /tv/admin/devices` lists both rows, and `signout-all` answers `devices: 2` and removes them (FR-13). Delete
   the user in Jellyfin. Two passes later: one *"Removed the data of …"* line, no *"Signed out"* line, the `ai_viewer`
   row gone, and the user not in `removed_users`. Read the row from a DB copy, because `GET /api/ai/viewers` deletes
   an absent user's row by itself (DEB-2826, FR-1.5) and would hide the result. For the hour after, no
   `Jellyfin (getUserDataBulk|getResumeItemsAll|getRecentlyPlayedAll|getRecentlyTouched) failed: 404` line, and the
   summaries add up with no `skipped` clause.
5. **A failed `/Users` removes nothing**, checked in `linuxX64Test`, not on prod: with a fake `fetchUsers`, a user
   marked by one complete read, then a `null` read, then an empty read, then a read with no administrator, is still
   marked and not removed. A complete read that lists them clears the mark. A complete read that lists none of the
   users with a device marks nobody (FR-2).
6. **A 404 alone removes nothing**, also in `linuxX64Test`: a refresher whose read 404s for a user who is in the
   fake `/Users` triggers one connect-style pass, and the user is neither marked nor skipped (FR-8). A burst of 404s
   for a user who is **absent** costs one read a minute, so it cannot supply FR-1's two reads seconds apart.
7. **Removal acts on every stored form**, in `linuxX64Test` against a real database, because prod holds only one form
   and the prod DB is never written by hand: a user with rows in every table of FR-4 and FR-5, stored as 32 hex
   digits, as dashed lowercase and as dashed uppercase, loses all of them, and the next pass does not find them again
   (FR-12).
8. **A sentinel is not a user**, in `linuxX64Test`: `__global__`, an empty string and `household` in the candidate
   tables are never marked, and the removal function refuses them. A revoked `api_key`, a redeemed grant, and rows in
   the kept tables do not make an id a candidate (FR-10, FR-11).

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

## Amendment 2026-11-01 — a fresh fixture, users with no device left, and the id's stored form (DEB-3288)

> Tróndur, 2026-11-01 (DEB-3288, from DEB-3285): this makes the owner's 10-27 *"yes"* land and does not change what
> it is, so there is no owner card. Nothing here reaches a viewer. Every id it acts on is one Jellyfin no longer
> lists, and such an account can no longer sign in.

**Why now.** DEB-3284 cleared the 42 users and 78 device rows by hand on 11-01, so the acceptance had lost its
fixture. That clearing also showed FR-5's gap. The reconciler looks only at users who hold a device row, so a user
whose devices went first never becomes gone and their viewer data stays.

### What was measured, 2026-11-01

Read-only copies of prod's DB and Jellyfin's `Users`, with sidecars. The DEB-3284 backup and a backup from 09-25 were
read as well.

- **One form on every row.** Every id in `ravilo_device`, `session`, `api_key`, `cast_handoff`, `ravilo_behaviour`,
  `recommendation`, `ai_order`, `suggestion_viewer`, `audiobook_progress`, `audiobook_bookmark`, `dashboard_visit`,
  `ai_viewer`, `suggestion_dismissal`, `playback_session`, `support_ticket` and `support_ai_turn` is 32 lowercase
  hex digits. The same holds for the 78 rows DEB-3284 removed, and for `ravilo_device` in both backups. `ravilo_pairing`
  and `playback_outbox` are empty. Jellyfin's own `Users.Id` is dashed and uppercase on disk. So **no row is stuck
  today**: FR-12 protects against a form that could arrive, because `loginDevice` stores what it is handed. It does not
  fix a form that is there.
- **DEB-3088's empty read-back.** The 10 rows of that QA user were one id, in the 32-hex form, from 23:29Z to 23:45Z
  on 10-30. A read with that exact string would have listed them, so the miss is on the caller's side. Which id was
  passed is not recorded anywhere, so that part is still inference. A dashed id is the likely one, and it is the form
  Jellyfin's own table shows.
- **The wider set is 110 ids.** Jellyfin lists 7 of them, and 103 are absent. The 6 users with device rows are all
  listed. Of the 103, FR-5's original list accounts for 96 (recommendations alone for 93), `ai_viewer` for 6 more and
  `session` for 1 more. 102 of the 103 have a `qa-…` name in Jellyfin's `ActivityLogs`. The other id has no name
  anywhere, and holds only recommendations and an AI order built on 10-21.
- **What the first pass would delete on those numbers:** 4 650 `recommendation`, 2 250 `ai_order`, 31 `ai_viewer`,
  9 `ravilo_behaviour`, 4 `audiobook_progress`, 1 `audiobook_bookmark`, 2 `dashboard_visit`, 3 `session` and
  1 `cast_handoff` rows. No `ravilo_device` rows, and none of any listed user.
- **One sentinel.** `ravilo_config`'s only row is `__global__` (`GLOBAL_USER_ID`, `RaviloConfigService.kt:28`), the
  household's global config, at 24 KB. A scan of every text column in every table found user ids in the tables named
  here, and in `acquisition.requested_by` and `request_intent.requested_by`. No other table holds a non-id value in a
  user column today. `ai_queue.subject` can hold `household`, but it is a job's key and not a user column.

### The requirements this adds or restates

- **FR-10 — Who is looked at.** The **candidates** of a pass are the distinct ids, after `normalizeGuid`, in:
  - `ravilo_device`;
  - FR-5's delete list, with `ai_viewer` added;
  - `session`, and `api_key` where `revoked = 0`;
  - the unredeemed grants of FR-4: `cast_handoff` where `redeemed_at` is null, `ravilo_pairing`, and `tv_signin_code`
    rows approved for a user.

  FR-1 runs per candidate. The policy half of the pass still walks only the users with a device row. The session and
  key tables are in the set because a user holding only an admin session or a live API key would otherwise never lose
  it, which is worse than leftover recommendations. The revoked and redeemed filters are what make this end: FR-4
  keeps a revoked key's row, so without them a removed user would be a candidate again on every pass.

  **Not sources, and never deleted by this spec:** `playback_session`, `support_ticket` and `support_ai_turn` (FR-5's
  keeps), `suggestion_dismissal.for_user` (the household's *no*, keyed on the title), `acquisition.requested_by` and
  `request_intent.requested_by` (the operator's record of a request), and `screen_pairing` and `sso_attempt` (swept by
  their own expiry).
- **FR-11 — A sentinel is not a user.** An id is a candidate only if its normalised form is exactly 32 hex digits.
  This is a positive test and not a list of known names, so a sentinel added to some table later cannot be read as an
  absent user. It excludes `__global__`. The function that runs FR-4 and FR-5 applies the same test and refuses
  anything else, because that step cannot be undone.
- **FR-2, restated.** Let *H* be the users with a device row and *C* the candidates. A complete read passes the guard
  only if it lists at least one of *H*, or, when *H* is empty, at least one of *C*. **A read that fails the guard is
  neutral:** nobody is marked, a marked user does not become gone, and the pass logs FR-2's one warning, with *N* the
  size of the set it checked.

  *H* stays the test while it has anyone in it, because this is the outcome the spec must never produce. A repointed or
  restored Jellyfin lists none of *H*. A guard over *C* alone would let one data-only id in the answer sign out the
  whole household. A share of *C* would not work either: 103 of 110 are absent on a healthy server. On 11-01 the guard
  passes with 6 of 6 in *H*.
- **FR-5, widened.** Two more things go in the same step:
  - the user's `ai_viewer` row (`RerankSwitch.forget`). `GET /api/ai/viewers` already forgets it, but only when an
    admin opens the page, and DEB-2826 came after this list was written;
  - their queued re-rank requests, `ai_queue` rows of the re-rank job whose subject is `<id>|<scope>`, with FR-12's
    match on the part before the `|`. The switch's off path already deletes a viewer's waiting request.

  A batch already out may still write an `ai_order` for the user afterwards. That makes the id a candidate again and
  it is removed two passes later, which needs no extra rule.
- **FR-6, for a user with no device row.** The name comes from the first of the user's `session`, `api_key` and
  `playback_session.owner_name` rows that has one. Failing those it reads `an old account (<first 8 of the id>)`. On
  11-01 most of the 103 have no name in those tables, and that is fine. The lines:
  - The marking line is unchanged, with that name.
  - A user who held a session or a live key gets FR-6's *"Signed out …"* line, with `0 devices`.
  - A user with neither gets *"Removed the data of <name>: no longer in Jellyfin."* Nothing was signed out, so the line
    does not say so.

  **Users & devices** stays as it is for those holding a device or a session, the only users it has ever listed. A
  user with data only is not on the page and does not join `removed_users`. A hundred lines there for seven days
  would bury the ones that matter, and Activity is the durable record. The first pass writes about two hundred
  Activity lines, once.
- **FR-8, corrected.** The freshness rule does not hold for an absent user as the code stands: `lastPassForUser` is
  written only for a user who was listed, so an absent user is never fresh and every 404 would start a read. A
  hydrate's chunks would then supply FR-1's two reads within seconds. A pass records its time for **every candidate
  it looked at**, listed or not. FR-1's two reads are therefore at least 60 seconds apart.
- **FR-12 — Removal acts on the stored form.** Comparing normalised is not enough. `deleteAllForUser`, `revokeAllForUser`
  and FR-5's deletes match the exact string, so a normalised id handed to a row stored in another form removes nothing
  and reports success, and that user comes back on every pass. Marks and the guard use the normalised id. **Every read
  and delete in FR-4 and FR-5 matches `replace(lower(<column>), '-', '') = :normalisedId`**, new queries beside the
  existing ones. `deleteAllForUser` evicts the tokens of all the rows it found. The policy half passes the row's stored
  id to `refreshPolicy`, as it does today, and looks the user up by normalised id. These tables are small and the step
  is rare, so an unindexed match is fine.
- **FR-13 — The admin routes find a user's rows in either form.** `GET /tv/admin/devices`,
  `POST /tv/admin/users/{userId}/signout-all` and `DELETE /tv/admin/devices/{deviceId}?userId=` compare the raw string
  today, and answer `[]` or `{"ok":true}` whether or not a row matched. All three match as FR-12 does.

  **`signout-all` reports what it removed:** `{"ok":true,"devices":n,"sessions":k}`, additive and admin-only, from a
  `@Serializable` class, because a mixed-type `mapOf` answers 500. It stays 200 at `0`, and `0` is how a caller now
  tells that no row matched. FR-6's counts come from the same numbers. The admin frontend ignores this body
  (`RaviloApi.signOutAll`) and does not change. Other admin routes that take a user id get it from the overview's own
  answer, which is the stored form, and are not changed.

### Unchanged

`RECENTLY_SEEN_WINDOW_MS` stays as it is. AI spend is closed by DEB-2826, not here. There is still no deletion-event
path. There is still no migration and no new dependency: FR-10 to FR-13 add queries, not columns.

### Where this lands (for the build)

- `DevicePolicyReconciler`: `candidates` beside `fetchUsers`, a pure read so `DevicePolicyReconcilerTest` still needs
  no database for FR-1, FR-2, FR-8, FR-10 and FR-11; marks keyed on the normalised id.
- The removal function of the original *Where it lands*: FR-5's additions, FR-11's refusal, FR-12's queries, and the
  name lookup of FR-6. It runs once per user, each in its own transaction.
- `TvRoutes.kt`: FR-13. `RaviloDeviceService.deleteAllForUser` and `SessionService.revokeAllForUser` return their
  counts.
- A new test against a real database (`createDatabase`, as `DevicePolicyReconcilerTest` does) for acceptance 7 and the
  removal half of acceptance 8.
