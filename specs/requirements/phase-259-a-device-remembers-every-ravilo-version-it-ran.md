# Phase 259 — A device remembers every Ravilo version it ran

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockup in `design/app/ravilo-users.html`
(drawn the same day; the owner answered the design questions before this was written). **Dev-reviewed 2026-09-25 against `main` `e7991df3`**
(see §Dev review at the bottom: no release list exists server-side, so *skipped* is arithmetic on the plain
`MAJOR.MINOR` numbering or nothing; the three write sites are not in a transaction today; the seed must
pick one of a TV's two rows; the history's delete hook has three sites). Not built. Spec first. Builds on **224** (`ravilo_device.app_version`, the `X-Ravilo-Version` header) and
**256** (FR-256-5's behind count against the deployed backend's own version). Answers 224's §7 open
question 4: history is **a new table**, not a column.

> *"We want to start tracking what Ravilo versions these devices are on. So every time the jellystructure
> backend sees a new version from that device, it should be stored and possible to see the update history
> for this specific device."*

## What the server does today

- 224 FR-224-2 stores **one** `app_version` per `ravilo_device` row and overwrites it on change. The
  previous value is gone the moment the new one lands.
- `ravilo_device`'s key is `(device_id, jellyfin_user_id)`: a TV with two viewers is two rows carrying the
  same version. A version belongs to the **physical device**, not to a viewer on it.
- Users & devices shows `Ravilo 1.18 · TV` (224 FR-224-5) and nothing about when that became true.

## Functional requirements

**FR-259-1 — one table, one row per version change.**
New table `ravilo_device_version` (migration next free; the `.sq` updated byte-for-byte):
`device_id TEXT NOT NULL`, `app_version TEXT NOT NULL`, `platform TEXT`, `first_seen_at INTEGER NOT NULL`
(epoch ms), `observed INTEGER NOT NULL DEFAULT 1`. Keyed by `device_id` only — both viewers' rows on one
TV share one history. Index on `(device_id, first_seen_at)`.

**FR-259-2 — a row is written where 224 already writes, and nowhere else.**
Wherever FR-224-2 detects that a request's `X-Ravilo-Version` differs from the cached `DeviceData`
(`validateDeviceToken`'s change-only write, `loginDevice`, `castRedeem`), the same transaction inserts a
history row **if the device's latest history row names a different version**. Same version again (a second
viewer's row catching up, a re-login) writes nothing. One write per change, never one per request — 224's
rule unchanged. Values are trimmed and capped exactly as FR-224-2 does.

**FR-259-3 — the date is when a request first carried it, never the install time.**
The server cannot know when a build was installed; it knows when it first heard it. `first_seen_at` is
that request's time. A TV that updates overnight and stays off until evening reads *since 19:40*. The UI
says so once, in the page intro, not per row.

**FR-259-4 — history begins honestly.**
The migration seeds one row per distinct `device_id` from the current `app_version` with
`observed = 0` and `first_seen_at` = the migration time. The UI renders an `observed = 0` row as
*already on it when history began · {date}* with no start time. A device with no version at all gets no
seed row.

**FR-259-5 — keep everything.** Owner decision: no retention cap, no pruning. A row is small and a device
changes version perhaps weekly. Revoking a device's **last** `ravilo_device` row deletes its history
(nothing left to show it on); revoking one viewer of two keeps it.

**FR-259-6 — the overview carries the history.**
`OverviewDevice` gains `version_since` (epoch ms of the latest row, null when unobserved) and
`versions: [{ app_version, first_seen_at, observed }]`, newest first. The client computes nothing but
durations and formatting.

**FR-259-7 — the device row (as drawn).**
The caption line becomes `Ravilo 1.38 · TV · since today 08:14`, followed by a *N versions ▾* toggle
(*no update seen* when there is one row). Opening it shows a timeline, newest first: the version (mono),
`first seen → next first seen` with the span (*2 days*, *17 hours*), and one note —
*current* on the newest; *skipped 1.37* / *skipped 1.34 – 1.35* when the step jumps over published
releases (from the same release list 256 FR-256-5 reads; nothing is said when that list is unavailable);
*dev build* for any version with a `-g<sha>` suffix; *already on it when history began · date* for a seed.
The toggle remembers nothing across page loads; all rows start closed.

**FR-259-8 — dev builds are said once.** A device whose history contains a `-g…` version gets one
footnote under its timeline: *A version with a -g… suffix is a development build — never counted as
behind.* No other per-device footnotes.

**FR-259-9 — the page bar says where the latest is.** One chip in the Users & devices page bar:
`latest Ravilo 1.38 · N devices behind` (behind = 256 FR-256-5's comparison, dev builds excluded). Hidden
when the release list is unavailable; *· all up to date* when N is 0.

## Deliberately not in this phase (owner decisions, 2026-09-25)

- **No *went back* flag.** A version lower than the one before it is drawn like any other step.
- **No per-device *N releases behind* warning** in the row. The page-bar chip carries the count.
  (256 FR-256-5's line is superseded **in the device row only**.)
- No notification or triage entry when a device changes version.

## Open questions

1. Does 256's release list give enough to name *skipped* versions (every tag between two), or only the
   latest? If only the latest, drop *skipped …* and keep the rest.
   **Closed — dev review item 1:** there is no list at all, and none is needed. Versions are plain
   `MAJOR.MINOR` (231), consecutive releases differ by one in MINOR, so a step from 1.36 to 1.38 *skipped
   1.37* by arithmetic; from 1.33 to 1.36, *skipped 1.34 – 1.35*. Nothing is said across a MAJOR change.
2. The web app's version moves when the server is deployed, not when a viewer updates. It is recorded the
   same way (a reload is the first request) — confirm the web platform should have history at all, or
   only a current version.
   *Dev review:* record it. A `platform = "web"` row's history is the history of deploys that browser saw,
   which is true and cheap; the *dev build* note (FR-259-8) covers a dev-compose deploy the same way.

## Acceptance

1. A device sending `1.36` then `1.38` yields two rows; a thousand further `1.38` requests yield none.
2. Two viewers on one TV produce one history.
3. After migration every versioned device shows exactly one *already on it when history began* row.
4. The mockup's six devices render from the payload with no client-side version logic beyond formatting.

## Dev review (2026-09-25, against `main` `e7991df3`)

The premise holds: `ravilo_device`'s key is `(device_id, jellyfin_user_id)` (`RaviloDevice.sq:49`), and
224's one `app_version` is overwritten in place by `recordAppInfo` (`RaviloDeviceService.kt:191-197`,
change-only), by `loginDevice`'s `INSERT OR REPLACE` (`:86-110`) and by `CastService.redeem`
(`CastService.kt:122`). The overview builder is `TvRoutes.kt:937-944` and its DTO `RaviloApi.kt:42-52`
— both additive. Seven items.

1. **Open question 1 closes: no release list exists, and *skipped* needs none.** Nothing in the backend
   queries GitHub (256's review found the same); the only version the server knows is its own
   (`ServerVersion.kt:11`). But releases are plain `MAJOR.MINOR` with MINOR rising by one per release
   (231, `deploy-play-store.yml:100-112`), so the versions skipped between two history rows are the
   integers between them — `1.36 → 1.38` skipped 1.37, by arithmetic. Say nothing across a MAJOR change
   (there has never been one). The *behind* count in FR-259-9 is 256 FR-256-5's comparison against
   `ServerVersion.current`, which is what "the release list" meant there too.
2. **"The same transaction" is not what those three sites do today.** `RaviloDeviceService` uses no
   transaction anywhere; each write is one statement. Wrap `updateAppInfo` + the history insert in
   `queries.transaction { }` (the shape `AcquisitionStore.kt:41` and `DirtyItemStore.kt:36` use), in one
   helper called from all three sites, so a crash between the two cannot leave a version without its
   history row. The "latest history row differs" test is one query, `latestForDevice`, inside the same
   transaction.
3. **The seed has to choose between a TV's two rows.** Two viewers' rows on one TV can carry different
   `app_version`s — a viewer not seen since an update still holds the old one (the policy drift 258
   measured has the same shape). Seed from the row with the greatest `last_seen` per `device_id`, not
   from "the current `app_version`" as if there were one.
4. **The history's delete hook is three sites, one helper.** `unpair` (`:208-210`, by token), the
   per-viewer revoke (`:246`, by device+user) and `deleteByUser` (`:282`) all remove `ravilo_device`
   rows; after each, if `getByDevice(deviceId)` is empty, delete the history. Miss one and a revoked TV's
   history lingers with nothing to show it on.
5. **The token cache is on this phase's side.** `recordAppInfo` compares against the cached `DeviceData`
   (`:153`), so a version change is seen on the first request after an update — the 258 trap (a rewritten
   row invisible for five minutes) does not apply here, because the *request* carries the new value.
6. **Migration number.** 258's review also claims `49.sqm`; whichever lands first takes 49, the other 50 —
   and the `.sq` `CREATE TABLE` in the same commit, or the generated interface and the live schema
   disagree (the SQLDelight rule this repo has hit before).
7. **Dev builds are recognisable by two suffixes, not one.** `git describe` yields `1.37-68-gade0523d`
   and, with local changes, `1.37-68-gade0523d-dirty`; the `-g<sha>` test covers both, and a plain `1.38`
   is what GHCR and Play builds carry. FR-259-8's rule holds as written; say `-dirty` once so nobody adds a
   second test for it.

**Small corrections.** FR-259-2's "cached `DeviceData`" is the token cache's copy, refreshed on change —
right, and worth naming (`tokenCache`, `:47`). FR-259-7's "from the same release list 256 FR-256-5 reads"
becomes "by arithmetic on the plain numbering". The mockup the sync delivered (`design/app/ravilo-users.html`)
is the design side; the served row is `RaviloUsers.kt:185-187` with `appVersionLine` at `:85-94`.

**Net effect.** One table + migration, one transactional helper at three sites, one seed query per device,
one delete hook at three sites, two additive DTO fields, the row and the chip in `RaviloUsers.kt`.
