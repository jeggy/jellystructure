# Samsung TV install requests and release readiness
Task: DEB-4842

> Owner approved the Samsung TV install plan on 2026-11-17 in DEB-13, with implementation after the existing Tizen work. This file specifies the backend and admin-site half. DEB-4843 owns the Android guide and installer; DEB-4844 owns device QA. Sjúrður approved the migration and additive DTOs on DEB-4842 on 2026-11-17, with the contract details in the amendment below.

## Scope

A signed-in Ravilo viewer asks the owner to add one Samsung TV's DUID to the release certificate. The owner approves or rejects in the jellystructure admin site. A request becomes Ready when the current public release's Tizen package contains that DUID. This page is for the owner; the viewer flow is Android phone only. No release workflow or certificate-signing change is part of this task.

## Requirements

**FR-1 · Persist requests per viewer and TV.** Store one `samsung_tv_requests` row per `(account, duid)`, with a server-issued id; the lowercase, dash-free Jellyfin `account_id` from the device token, DUID, model, Tizen version and friendly name; state; created and decision times and actor; optional decision note; `ready_from_release` and the verified `ready_wgt_url`. Preserve withdrawn and rejected decisions in the row. A new request for the same account and DUID after withdrawal or rejection replaces its request details and returns to Requested; an already active request returns its existing row. A different account's row is separate. Times are UTC epoch seconds. Never log request bodies or DUIDs.

**FR-2 · Viewer routes require a device token and reveal only the caller's rows.** The prefix is `/api/tv/samsung-requests`, which the existing `/api/tv/` auth fence handles. The contract is additive; no existing route or DTO changes.

| Method and path | Request | Success | Refusal |
| --- | --- | --- | --- |
| `POST /api/tv/samsung-requests` | JSON `{duid, model, tizen_version, name}` | `201` with the viewer row when newly requested; `200` with the existing active row | `400` invalid/oversize fields; `409` active-request cap; `401` absent or invalid device token |
| `GET /api/tv/samsung-requests` | none | `200` JSON `{requests: [row]}` newest first | `401` absent or invalid device token |
| `DELETE /api/tv/samsung-requests/{id}` | none | `200` with Withdrawn after a Requested row, or the unchanged Rejected/Withdrawn row; repeat is idempotent | `404` unknown id or another viewer's row; `409` if the decision is Approved or Ready |

The viewer row has `id`, `duid`, `model`, `tizen_version`, `name`, `state`, `created_at`, `decided_at`, `decision_note`, `ready_from_release`, and `wgt_url`. DTO properties use `@SerialName` for these snake_case wire names. States are `REQUESTED`, `APPROVED`, `READY`, `REJECTED`, `WITHDRAWN`. `wgt_url` is non-null only in Ready and points to the `.wgt` asset of `ready_from_release`; the phone downloads that exact asset. DUID is case-normalized to uppercase and must match `[A-Z0-9]{8,32}`. `model` and `name` are 1–120 characters, `tizen_version` is 1–32, and a decision `note` is at most 500; control characters are refused. A viewer may have at most 10 active (`REQUESTED`, `APPROVED`, or `READY`) rows; a new active request above that cap answers `409`. The server derives account identity from the device token, never from the body. Withdrawal of Requested changes it to Withdrawn. DELETE on Rejected returns 200 with the row unchanged; repeat DELETE on Withdrawn does too. The owner no longer needs to act on a Withdrawn row. `state` is a string on the wire, so old installed phones can decode a future state.

**FR-3 · Admin routes require the existing admin cookie session.** The prefix is `/api/samsung-tv/requests`; it is outside `/api/tv/` and therefore cannot be reached with a viewer device token. The list returns all rows, newest first, including withdrawn rows, with the viewer's display name and account id. `release` is `{tag: String?, checked_at: epochSeconds?, error: String?}`. `certificates` is `{author_expires_on: "2027-09-18", author_days_left: Int, distributor_expires_on: "2027-11-16", distributor_days_left: Int}`; days left are computed from the server clock. The admin page warns when either value is at most 60. The error field reports a release-check failure without changing the last verified state.

| Method and path | Request | Success | Refusal |
| --- | --- | --- | --- |
| `GET /api/samsung-tv/requests` | none | `200` JSON `{requests: [adminRow], release: releaseStatus, certificates: certificateStatus}` | `401` no admin session; device token alone cannot enter |
| `POST /api/samsung-tv/requests/{id}/decision` | JSON `{decision: "APPROVE" | "REJECT", note: String?}` | `200` updated admin row, read from persisted state | `400` invalid decision/note; `404` unknown id; `409` already decided or withdrawn; `401` no admin session |

An admin row extends the viewer row with `requester_name`, `account_id`, and `decided_by`. A decision is one conditional `UPDATE … WHERE id=? AND state='REQUESTED'` with `changes()` checked, so only a Requested row can be decided. Withdrawal uses a guarded `UPDATE … WHERE id=? AND state IN ('REQUESTED','REJECTED')`; the Rejected branch leaves its row unchanged. Reconciliation uses `WHERE state IN ('APPROVED','READY')` so it cannot undo a concurrent decision or withdrawal. Approve sets Approved; Reject sets Rejected. A note is optional and visible to the requester. Admin actions and the page never infer a new state locally: render the server response, then refresh the server list.

**FR-4 · Readiness follows the latest release's signed package.** On startup and at most hourly, fetch metadata for the latest published GitHub release of `jeggy/jellystructure`, select its `ravilo-tizen-*.wgt` asset, download it with size and timeout bounds, and read `signature1.xml` from inside the ZIP. Parse `URN:tizen:deviceid=` values from the distributor certificate in that signature. The certificate parser reads exact ASN.1 URI lengths and accepts only 8–32 uppercase alphanumeric device IDs, matching request validation. An Approved row whose DUID occurs there becomes Ready with `ready_from_release` equal to the release tag. A Ready row whose DUID is absent from a later latest release becomes Approved again and loses its ready tag and URL; this prevents the phone installing a package that does not cover its TV. Send `If-None-Match` with the saved ETag; `304` keeps the last verified state. A failed check preserves the last verified state and reports a release-check error to the admin page. The parser fails closed on missing or malformed signatures and never treats a release asset name alone as proof. The URL is only built from the GitHub release asset for the verified tag, using HTTPS under `github.com/jeggy/jellystructure/releases/download/`; no client input supplies it. No CI or certificate-signing change is needed. The v1.70 public `.wgt` is a regression fixture: both `EXCKEBBVWCGFC` and `XTCJYJZXZBZVK` must be detected.

**FR-5 · Admin page: Samsung TV requests.** A Ravilo-section navigation item opens a page listing requester, friendly name, model, Tizen version, DUID with a Copy button, state, request/decision dates, note, and Ready release. Requested rows have Approve and Reject controls with an optional note. A button copies the DUIDs of all Approved and Ready requests for certificate issuance, once per DUID. The page warns beginning 60 days before the author certificate expiry (2027-09-18) and the distributor certificate expiry (2027-11-16), including which certificate and its date. Its copy comes from shipped `i18n/*.json` for all admin languages. Clipboard results and server errors are announced accessibly. The UI renders server state after each decision and release refresh.

**FR-6 · Verification.** Automated `:linuxX64Test` route tests cover create/list/withdraw, account isolation, input validation, decision transitions, and non-admin refusal. A parser/reconciliation test uses the real v1.70 `.wgt` or a byte-identical frozen fixture, so Ready changes without an admin checkbox and only for a covered DUID. The admin page is built in the existing Wasm CI job. QA checks its copy controls, state changes, and certificate warning dates, then DEB-4844 covers the Pixel 7 and Tizen emulator end-to-end flow.

## Amendment — 2026-11-17 (Sjúrður protocol and migration review)

The approved wire uses snake_case names with `@SerialName` in `:shared`, and state remains an open string. `samsung_tv_requests` uses `UNIQUE(account_id, duid)` and is created in the next free `.sqm` and byte-identically in `SamsungTvRequests.sq`; both migration replay tests drop it before replay. All decisions, withdrawals and release reconciliation are guarded SQL writes with `changes()` checks. FR-2/FR-3 now fix validation limits, the ten-active-request cap, Rejected DELETE idempotence, release/certificate status shapes, and the verified GitHub-only asset URL. Release polling is at most hourly with ETag revalidation. These clarify the owner-approved behavior; no existing DTO or route is changed.

## Amendment — 2026-11-23 (DEB-5409): an approved TV the current release already covers is Ready at once

On DEB-5356 the owner approved his own set, whose DUID `URN:tizen:deviceid=` already carried in the current release. His phone then read *Approved, waiting for release* until the next hourly FR-4 check. His words (15:48Z): *"no need for this state … as this device is already approved."* The server already holds the device ids of the last verified release in memory (`SamsungTvRelease`'s verified tag, URL and covered set). This amendment uses them at the moment of the approval, without waiting for the next poll.

**FR-7 · The last verified release decides at approval.** The owner answered on DEB-5409 (2026-11-23): a TV already in the certificate is still approved by him; once approved, it is ready at once.
- **At create, nothing changes.** Every new or reopened request (FR-1) is `REQUESTED` and waits for the owner's decision, covered or not.
- **At approval.** An APPROVE of a DUID in the last verified latest release's covered set writes `READY`, that release's tag and verified `wgt_url`, `decided_at` now and `decided_by` the approver, in the one guarded `UPDATE … WHERE id=? AND state='REQUESTED'` of FR-3. An uncovered DUID is `APPROVED` as before.
- **Reconciliation is unchanged** (FR-4): it still touches only `APPROVED` and `READY` rows, so a covered `REQUESTED` row stays with the owner.
- **When nothing is verified yet** (startup before the first check, or a failed check), APPROVE writes `APPROVED` and the next check moves it, as before FR-7.
- **Demotion is unchanged.** A Ready row whose DUID a later release drops goes back to `APPROVED`.
- The covered set is never written to the database or the log (FR-1's rule on DUIDs).

**FR-8 · The admin page.** `samsung_requests.state_approved` (*Approved, waiting for release*) keeps its meaning, but only a TV that is not covered yet can still show it. Approve and Reject on a Requested row are unchanged.

**Verification.** `:linuxX64Test`, against the frozen v1.70 fixture:
- a create of `EXCKEBBVWCGFC` answers `REQUESTED`, as every create does;
- an APPROVE of that row answers `READY` with the fixture's tag and URL;
- an APPROVE of an uncovered DUID answers `APPROVED`;
- a reconciliation leaves a covered Requested row `REQUESTED`;
- with no verified release, APPROVE answers `APPROVED`.

**No wire change.** The states, fields and routes are FR-2's and FR-3's, and every installed phone from v1.72 decodes `READY` already.
