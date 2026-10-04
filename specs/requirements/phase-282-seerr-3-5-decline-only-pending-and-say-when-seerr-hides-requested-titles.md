# Phase 282 — Seerr 3.5.0: decline only what is pending, and say when Seerr hides requested titles

## Status

`Planned` — written 2026-09-28; dev-reviewed 2026-10-04 (section at the end — FR-282-1/2 confirmed; **FR-282-3/4's
premise is wrong**: in 3.5.0 *Hide requested media* is applied only in Seerr's own web pages, never in the API we read). Prompted by Seerr's v3.5.0 release the same day
(github.com/seerr-team/seerr/releases/tag/v3.5.0); the household's Seerr reports **3.4.1** with an update
waiting. Numbering verified against `main` the same day (admin through 281). Amends phase 186 (FR-186-6).

> Owner, 2026-09-28: *"Are we affected by this, or can we freely upgrade Seerr without affecting any of our
> implementations?"* — then, on the two small things the read-through found: *"create a small spec for us, to
> fix these small issues."*

## What the release changes for us

Read against every Seerr call this codebase makes (`SeerrClient.kt`: `/status`, `/auth/me`, `/search`,
`/discover/*`, `/movie/{id}`, `/tv/{id}`, `/person/{id}/combined_credits`, `POST /request`,
`POST /request/{id}/decline`, `DELETE /request/{id}`, `DELETE /media/{id}`, `/user/jellyfin/{id}`,
`POST /user/import-from-jellyfin`):

- **The announced breaking change does not reach us.** Only `GET /settings/plex/library` and
  `GET /settings/jellyfin/library` lost their `sync`/`enable` parameters (seerr #3321). Nothing here calls a
  `/settings/*` route today.
- **Decline is now guarded (seerr #3385).** `POST /request/{id}/decline` — with approve and `PUT` — answers
  **409** for any request that is not *pending*; retry answers 409 for anything not *failed*.
  `DELETE /request/{id}` is not guarded. Phase 186's removal cascade (`RequestLifecycleService.removeRequest`)
  declines every request whose status is not already *declined* before deleting it, so an approved (2) or
  completed (5) request now draws a 409 first. The result is discarded and the delete still runs, so the cascade
  completes — but the code asks Seerr for something Seerr now refuses, and says nothing about it.
- **A new Seerr setting can empty our feeds (seerr #1855).** `hideRequested` on `GET /settings/main`, off by
  default, makes every `/discover/*` route (and recommendations/similar) drop titles that are requested but not
  yet available. Ravilo's Request rows (137/R171) and 274's suggestion build read exactly those routes, so with
  the switch on a requested title would vanish from the TV rather than show as *Requested*, and nothing on our
  side would say why. Search is not filtered. Same class of thing as `hideBlocklisted`, which 274 already
  notes must stay off for FR-274-16's sentence to be true.
- **Request creation is serialised (seerr #3377, #3380).** `POST /request` now waits behind another request
  from the same user, and behind another user's request for the same title — on auto-approve that wait covers
  Seerr's own Radarr/Sonarr work. A slower answer occasionally, never a different one; the call runs under
  `OutboundHttp`'s 120 s request timeout, which covers it. No change needed.
- **Two fixes help this household.** seerr #3502 sends Jellyfin 12 the current Authorization header (12.0
  rejects the legacy one by default), which is what `/user/import-from-jellyfin` and Seerr's own library sync
  depend on against this house's Jellyfin 12.1; seerr #3324 stops a Jellyfin connection failure reading as a
  bad token. seerr #3510 remaps Overseerr's `DELETED = 6` to Seerr's 7 — the value phase 186 already treats as
  deleted.
- **274's blocklist writes are unaffected.** Its 2026-09-28 dev review verified `GET/POST /api/v1/blacklist`
  and `DELETE /api/v1/blacklist/{tmdbId}` live; none of them changed in 3.5.0.

## Requirements

- **FR-282-1 — Decline only what Seerr can decline.** `removeRequest` sends `POST /request/{id}/decline` only
  for a request whose status is **1 (pending approval)**. Approved, completed, failed and already-declined
  requests go straight to `DELETE /request/{id}`, which is what removes them. The cascade's outcome is
  unchanged on 3.4.1 and 3.5.0 alike: every request row for the title is gone, then the media row, then the
  *arr entity and our own rows, exactly as FR-186-6 lists them.
- **FR-282-2 — A refused decline is logged, never swallowed.** `declineRequest` returns a result that
  distinguishes *declined*, *refused* (409, with Seerr's own message) and *failed* (anything else), and
  `removeRequest` logs a refusal at info with the request id and Seerr's message. The cascade still continues
  to the delete: a refusal means the request was not pending, not that the removal failed.
- **FR-282-3 — Test connection says when Seerr hides requested titles.** The Seerr probe (`SeerrClient.ping`,
  Settings → Download tools → *Test connection*) reads `GET /settings/main` after `/auth/me` succeeds and, when
  `hideRequested` is `true`, the result reads *"Connected · v3.5.0 · Seerr hides requested titles from its
  lists — a title someone has requested will not appear in Ravilo's Request rows or in the suggestions until it
  is available. Turn off Settings → General → Hide requested media in Seerr to show it as Requested."* An absent
  field (3.4.1 and older) or a failed `/settings/main` read counts as `false`: the probe never fails, and never
  says less than *Connected*, because of this check. The result still carries `version` as today.
- **FR-282-4 — The Seerr card's hint names the setting.** The card's standing text gains one sentence:
  *"Leave Seerr's Hide requested media off — Ravilo shows a requested title as Requested rather than hiding
  it."* So the admin reads it before they ever press *Test connection*.

## Non-goals

- Reading or writing any Seerr setting from jellystructure beyond that one read. Seerr owns its settings.
- `hideAvailable` (a title already in the library dropped from Seerr's lists). Pre-existing, and not a fault: a
  viewer has no reason to request what they can already play.
- Polling `/settings/main` on a schedule, or a Dashboard warning. One read on *Test connection* is enough for a
  switch an admin flips by hand in another product's UI.
- Changing what phase 186's sweep (`verdict`) does with request statuses; it only reads them.
- Anything about the library endpoints the release names as breaking.

## Acceptance

1. Unit: `removeRequest` against a `mediaInfo` with requests at status 1, 2, 3 and 5 sends one decline (for
   the status-1 request) and four deletes, then one media delete, in that order. CI:
   `SeerrRemovalCallsTest.onlyAPendingRequestIsDeclined` (the plan is `seerrRemovalCalls`, dev review item 5).
2. Unit: `declineRequest` maps 200/204 → declined, 409 → refused with the body's `message`, 500/timeout →
   failed; `removeRequest` logs the refusal and still deletes. CI: `SeerrDeclineOutcomeTest` (the mapping is
   `declineOutcome`, dev review item 6) and `SeerrRemovalRunTest.aRefusalIsLoggedAtInfoAndTheDeleteStillRuns`.
3. ~~Unit: the probe reports the hide-requested sentence when `/settings/main` answers `{"hideRequested":true}`,
   plain *Connected · v…* when it answers `{}`, `{"hideRequested":false}`, 403 or a timeout.~~
   **Owner decision (2026-10-04):** the Seerr card carries the one-line hint under the version, always. CI:
   `tests/e2e/seerr-card-hint.spec.ts`.
4. ~~Against Seerr 3.5.0 with *Hide requested media* on: *Test connection* shows the sentence; with it off, the
   line reads as today. Against 3.4.1: as today.~~
   **Owner decision (2026-10-04):** nothing else changes: `SeerrClient.ping`, `/config/test-seerr`, `ArrTestResult`
   and Ravilo are untouched, and the card makes no Seerr call for the hint. CI: the same e2e spec asserts the
   no-call part.
5. ~~Removing a request (the phase 186 cascade) for a title whose Seerr request is approved leaves no
   `Request not found` or 409 in the log at warn or above, and the title's request and media rows are gone.~~
   **Dev review item 7:** a failed `deleteRequest` or `deleteMedia` is logged at **warn** with the request or media
   id, and the cascade still runs to the end. Removing an approved request with every call succeeding logs nothing
   at warn or above. CI: `SeerrRemovalRunTest`. Live: the manual check below.

## Open questions

1. Whether `GET /settings/main` should be read with the household key alone or also with `X-API-User` (156): the
   key is the admin's, so the plain read is right, but confirm the route needs no permission a service key
   lacks. Lean: plain read; a 403 counts as `false` per FR-282-3 either way.

## Tests

*Written 2026-10-04 from the dev review (items 5–7) and the owner decision (keep a hint). FR-282-3 and FR-282-4 are
reworked by that decision: there is no probe change to test, only one static line on the card. Every Seerr
response below is a fixture or a stub. No test calls a Seerr.*

**Backend unit tests** (`src/linuxX64Test/kotlin/dev/jellystructure/seerr/`, a new package directory. No Seerr test
exists today, and there is no `MockEngine` in `linuxX64Test`, so the rules are lifted into pure functions). CI:
`./gradlew linuxX64Test`.

- **`SeerrRemovalCallsTest`**: `seerrRemovalCalls(requests: List<SeerrRequestRef>, mediaId: Int): List<SeerrCall>`
  (FR-282-1).
  - `onlyAPendingRequestIsDeclined` (acceptance 1): requests `(11, 1)`, `(12, 2)`, `(13, 3)`, `(15, 5)` and media
    `40` give exactly `[Decline(11), DeleteRequest(11), DeleteRequest(12), DeleteRequest(13), DeleteRequest(15),
    DeleteMedia(40)]`.
  - `failedAndStatuslessRequestsAreOnlyDeleted`: status `4`, and a request with no `status` (decodes to `0`), each
    give one `DeleteRequest` and no `Decline`.
  - `requestsKeepSeerrsOrder`: the plan follows the input order (e.g. statuses 5, 1, 2), never sorted. Each pending
    request's decline sits directly before its own delete.
  - `noMediaRowMeansNoMediaDelete`: `mediaId = 0` gives no `DeleteMedia`.
  - `aMediaRowWithNoRequestsIsStillDeleted`: no requests and media `40` give `[DeleteMedia(40)]`.
- **`SeerrDeclineOutcomeTest`**: `declineOutcome(status: Int, body: String?): SeerrDecline` (FR-282-2, acceptance
  2).
  - `okAndNoContentAreDeclined`: 200 and 204 give `Declined`.
  - `aConflictIsARefusalWithSeerrsMessage`: 409 with `{"message":"Only pending requests can be approved or
    declined."}` (Seerr 3.5.0's own text, PR #3385) gives `Refused` with that message.
  - `aConflictWithoutAMessageKeepsTheRawBody`: 409 with a non-JSON body, or JSON without `message`, gives `Refused`
    with the raw body. 409 with no body gives `Refused("")`.
  - `anythingElseFailed`: 400, 403, 404 and 500 give `Failed`, and the detail names the status code.
- **`SeerrRemovalRunTest`**: the cascade's logging. `Logger` has no test sink, so lift the loop into a runner that
  takes the call executor as a lambda and returns the lines it logs (e.g. `runSeerrRemoval(calls, exec): List<
  SeerrRemovalLog>`, each with a level and a message). `removeRequest` emits them. The names are the build's choice;
  the seam is not.
  - `aRefusalIsLoggedAtInfoAndTheDeleteStillRuns`: the executor refuses `Decline(11)` with Seerr's message. There
    is one info line naming request 11 and that message, and `DeleteRequest(11)` and every later call still run.
  - `aFailedRequestDeleteIsLoggedAtWarnAndTheCascadeGoesOn`: `DeleteRequest(12)` fails. There is one warn line naming
    request 12, and `DeleteRequest(13)`, `DeleteRequest(15)` and `DeleteMedia(40)` still run (FR-186-6's "defensive"
    rule).
  - `aFailedMediaDeleteIsLoggedAtWarn`: `DeleteMedia(40)` fails. There is one warn line naming media 40.
  - `aThrownCallCountsAsFailed`: the executor throws (a timeout) on a delete. It is logged like a failure, and the
    rest of the plan runs.
  - `aFailedDeclineIsLoggedAndTheDeleteStillRuns`: `Decline(11)` fails (500). The delete that follows is what removes
    the request, so it runs. The line is at **info** (a lean, not an owner decision), never warn.
  - `aCleanRemovalLogsNothingAtWarn` (acceptance 5's replacement): every call succeeds, so no line is at warn or
    above.
  - Every message carries ids and Seerr's text only, never a title.
- **`SeerrMediaInfoDecodeTest`**: a fixture check of the 3.5.0 response shape. Use a hand-written, trimmed
  `/movie/{id}` body inline (fictional ids, no title). It has `mediaInfo.id`, `mediaInfo.hasActiveRequest: true`
  (new in 3.5.0, PR #1855), and six requests: one at each status 1–5 and one without `status`. Decode it with the
  exact `Json { ignoreUnknownKeys = true }` that `OutboundHttp.client` installs (lift it to an `internal val` so
  the test shares the instance rather than a copy).
  - `aSeerr35MovieDecodesAndPlansTheRemoval`: the decode succeeds, despite the unknown `hasActiveRequest`. The refs
    carry the right `id`/`status`, and the status-less one reads `0`. `seerrRemovalCalls` on them gives one decline
    (status 1), six request deletes and one media delete. This is dev review item 3's "ignored, not a failure" as a
    test.

**Admin e2e** (`tests/e2e/seerr-card-hint.spec.ts`, Playwright in CI's `e2e` job, built like
`bazarr-dashboard.spec.ts`: one shared login, `/#/settings?tab=downloads`). FR-282-3/4 as reworked by the owner.
- `the Seerr card always carries the hide-requested hint`: with Seerr's toggle on, before *Test connection* is
  pressed, `#sect-seerr` shows the exact sentence *Seerr's "Hide requested media" only hides titles in Seerr's own
  pages; Ravilo's Request rows and suggestions still show them.* It sits under the connection result (`#chk-seerr`,
  where the version appears), e.g. as `#seerr-hide-hint`.
- `the hint makes no Seerr call`: with the page's requests recorded from the tab's load, none went to
  `/api/config/test-seerr`.
- `the hint does not depend on the test result`: press *Test connection* with a URL nothing answers (the e2e stack
  has no Seerr). `#chk-seerr` shows the failure, and the hint is still there, unchanged.
- Leave the toggle as it was found. Do not save.

**Not covered by CI:** that a real Seerr 3.5.0 answers our calls the way the fixtures say (409 on a non-pending
decline, a plain 200/204 on `DELETE /request/{id}` for an approved or completed request), and the wiring of
`removeRequest`'s two callers (`POST /acquisition/request/remove` and the dead-request sweep) to the runner.

**Live only — manual check (the household's Seerr, after its upgrade to 3.5.0; about five minutes):**
1. Settings → Download tools → Seerr: the hint line is under the connection line, before and after *Test
   connection*; the result still reads *Connected · v3.5.0*.
2. Request a title from Ravilo and let Seerr approve it. Then remove it from the admin (phase 186's removal). Seerr's
   Requests page no longer lists it, its media entry is gone, and the backend log has no refusal line and nothing at
   warn for that removal.
3. A pending request removed the same way is declined, then deleted: Seerr shows it gone, and the log has no
   refusal.

The warn on a failed delete is left to `SeerrRemovalRunTest`. By hand it needs Seerr to fail *between* the details
read and the delete, and stopping Seerr does not do that: `removeRequest` reads `/movie/{id}` or `/tv/{id}` first,
and when that read fails the Seerr steps are skipped with no call and no log line. That is today's behaviour and
this phase does not change it.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `SeerrClient.kt`, every caller of it, `RequestLifecycleService`, `ConfigRoutes`' Seerr test,
`SuggestionService.seerrOk`, `OutboundHttp`, and Seerr's v3.5.0 release, PR #3385, PR #1855 and the tagged sources
(`server/routes/discover.ts`, `server/entity/Media.ts`). The live Seerr was not called. FR-282-1/2 hold, while
FR-282-3/4 should be dropped. Nine items, one for the owner.

1. **The decline-guard finding is confirmed.** `removeRequest` (`RequestLifecycleService.kt:186-202`) sends
   `if (req.status != 3) declineRequest(...)` and then `deleteRequest(...)` for every request on the title. PR #3385
   guards `PUT /request/{id}`, `POST …/approve` and `POST …/decline` to *pending only*. They answer **409** with
   `"Only pending requests can be approved or declined."`, and `…/retry` is guarded to *failed only*.
   `DELETE /request/{id}` is not touched. Today the 409 is swallowed: `declineRequest` (`SeerrClient.kt:462`) returns
   `false`, the result is discarded, and the delete still runs. The cascade's outcome is right on 3.5.0. Only the wasted
   call and the silence are wrong.

2. **It also runs unattended.** `removeRequest` has two callers: the admin's `POST /acquisition/request/remove`
   (`AcquisitionRoutes.kt:45`) and the dead-request sweep (`RequestLifecycleService.kt:79`), on every retirement.
   So after the upgrade every retired request that was approved or completed draws one refused call per sweep
   retirement. FR-282-1 is worth doing for that alone.

3. **FR-282-3/4's premise does not hold.** PR #1855 stores `hideRequested` on `MainSettings`, but the server never
   drops a result. `Media.getRelatedMedia(…, includeActiveRequest = true)` only **sets `mediaInfo.hasActiveRequest`**
   on each item (pending or approved request) when the switch is on. Every `/discover/*` route and the
   movie/tv/collection routes return the full list. The hiding happens in Seerr's frontend (`MediaSlider`,
   `useDiscover`). Ravilo's Request rows (`SeerrClient.discover`, `SeerrClient.kt:301`) and 274's build
   (`movieRecommendations` / `movieSimilar` / `collection`) are therefore unaffected. A requested title still reaches
   us and still shows as *Requested*. The extra `hasActiveRequest` field is ignored, because the shared client decodes
   with `ignoreUnknownKeys = true` (`OutboundHttp.kt:190`). **Drop FR-282-3, FR-282-4, acceptance 3 and 4, and the
   open question.** The non-goals about `/settings/main` go with them. 274's note that `hideBlocklisted` must stay
   off was not re-checked here. If it follows the same pattern, that note is moot too; worth a look when 274 is next
   opened.

4. **Had FR-282-3 stayed, it was aimed at the wrong function.** `SeerrClient.ping` is also
   `SuggestionService.seerrOk`'s health check (`SuggestionService.kt:417`, at most once a minute). A
   `/settings/main` read inside `ping` would have run there too. If a hint is ever wanted, it belongs in the
   `/config/test-seerr` route (`ConfigRoutes.kt:357-364`) as its own call. Moot if item 3 is accepted.

5. **FR-282-1 as code.** Make the plan pure, so acceptance 1 is a plain unit test (no Seerr test exists today, and
   there is no `MockEngine` in `linuxX64Test`):
   - add `internal fun seerrRemovalCalls(requests: List<SeerrRequestRef>, mediaId: Int): List<SeerrCall>`, which
     gives decline-then-delete for status **1**, delete only for every other status (2, 3, 4, 5, and an absent
     status, which reads `0`), then `DeleteMedia(mediaId)` when `mediaId != 0`;
   - `removeRequest` runs that list.

   Status values are Seerr's `MediaRequestStatus`: 1 pending, 2 approved, 3 declined, 4 failed, 5 completed. Acceptance
   1's "in that order" then means the list order: decline(1), delete(1), delete(2), delete(3), delete(5), then the
   media delete.

6. **FR-282-2 as code.** Change `declineRequest` to return a sealed `SeerrDecline { Declined; Refused(message);
   Failed(detail) }`. Put the mapping in a pure `internal fun declineOutcome(status: Int, body: String?)`, so
   acceptance 2 tests it without HTTP: 200/204 → Declined, 409 → Refused with the body's `message` (fall back to
   the raw body), anything else → Failed. A thrown exception or timeout maps to `Failed` in the caller.
   `declineRequest` has one caller, so the signature change is internal. With FR-282-1 a refusal should not happen
   any more, so log it at **info** as the spec says: it means Seerr's state changed between our read and our call.

7. **Acceptance 5 is true today, and doesn't test anything.** Nothing logs these calls: no `Logger` call in
   `removeRequest`, and `OutboundHttp` logs no status codes. Replace it with what matters: a **failed
   `deleteRequest` or `deleteMedia`** is logged at **warn** with the request/media id. Today both results are also
   discarded (`RequestLifecycleService.kt:194-196`). So a delete that fails (Seerr down, a 403) is silent, and the
   cascade then deletes our own `acquisition` / `request_intent` rows while Seerr still holds the request. No sweep
   looks at that title again (shipped gap, below). Keep the cascade going after a failure (FR-186-6's
   "defensive" rule), but say so in the log.

8. **The rest of the read-through, checked.**
   - **The call list is incomplete.** It omits `/movie/{id}/recommendations`, `/movie/{id}/similar`,
     `/collection/{id}`, `GET/POST /blacklist`, `DELETE /blacklist/{tmdbId}`, `/service/radarr[/{id}]` and
     `/user/{id}` (`SeerrClient.kt:318-357, 428-442`). None of them is touched by 3.5.0's breaking change (#3321,
     library settings only) or by #3385.
   - **#3377/#3380** (serialised creation) only slow `createRequest`. It runs under `OutboundHttp`'s 120 s request
     timeout (`OutboundHttp.kt:194`), so nothing to do.
   - **#3510** (DELETED → 7) matches what `classify` and the orphan sweep already test (`RequestLifecycleService.kt:120`,
     `:170`).
   - **#3412** (status changes scoped to requested seasons) and **#3279** (orphaned season statuses reset on delete)
     change what Seerr does after our decline or delete, not what we send or read.

   So the answer to the owner's original question stands: upgrading Seerr is safe today. The decline call is wasted
   but harmless.

9. **For the owner — keep a standing hint anyway?** Seerr 3.5.0's *Hide requested media* only hides titles in Seerr's
   own web pages. Ravilo's Request rows and the suggestions are unaffected. **Lean: drop FR-282-3/4 entirely** and
   say nothing on the Seerr card. A sentence about a switch that changes nothing for Ravilo is noise, and it would
   become false if Seerr ever moves the filter server-side without us noticing.

**API:** no route, DTO or wire change (`ArrTestResult` is untouched once FR-282-3 goes). **Tests:**
`seerrRemovalCalls` and `declineOutcome` in `linuxX64Test`, both pure. Only the live house can confirm acceptance 5's
replacement after the upgrade.


## Owner decisions (2026-10-04, after the dev review)

**Keep a hint (FR-282-3/4 reworked, not dropped).** The admin's Seerr card carries one line under the version:
*Seerr's "Hide requested media" only hides titles in Seerr's own pages; Ravilo's Request rows and suggestions still show
them.* Shown always (no Seerr call to detect the setting); no Ravilo change. Acceptance 3–4 become: the line is on the
card; nothing else changes.

## Build notes (2026-10-04)

- **FR-282-1/2 (dev review items 5–7):** new `seerr/SeerrRemoval.kt` — `seerrRemovalCalls(requests, mediaId)` (decline
  then delete for status 1, delete only for every other status incl. a missing one, then `DeleteMedia` when
  `mediaId != 0`, in Seerr's order), `declineOutcome(status, body)` → `SeerrDecline.Declined / Refused(message) /
  Failed(detail)`, and `runSeerrRemoval(calls, exec)` returning the log lines (refused or failed decline at info, failed
  request/media delete at warn, a thrown call counts as failed, never stops early; ids and Seerr's text only).
  `SeerrClient.declineRequest` now returns `SeerrDecline` (409 body read for the message). `RequestLifecycleService.removeRequest`
  runs the plan and logs the lines under `acquisition`, so the admin removal and the dead-request sweep both follow it.
  `OutboundHttp.clientJson` is the client's own decoder, lifted so the fixture test shares it.
- **FR-282-3/4 (owner decision):** one static line on the admin Seerr card, `#seerr-hide-hint`, under the
  Test-connection row inside `#seerr-on` (`ui/Settings.kt`). No Seerr call, no probe change, `/config/test-seerr` and
  `ArrTestResult` untouched, nothing in Ravilo.
- **Tests:** `linuxX64Test` — `SeerrRemovalCallsTest` (5), `SeerrDeclineOutcomeTest` (4), `SeerrRemovalRunTest` (6),
  `SeerrMediaInfoDecodeTest` (1), all green. Admin wasm compiles. `tests/e2e/seerr-card-hint.spec.ts` written (the hint's
  text and place, no `/api/config/test-seerr` call on load, unchanged after a failed test; the toggle is put back, nothing
  saved) — it runs in CI's e2e job, not here (no Docker in this pass).
- **Only the live house confirms:** the manual check above, after Seerr's upgrade to 3.5.0.
