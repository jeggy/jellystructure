# Samsung TV install requests and release readiness
Task: DEB-4842

> Owner approved the Samsung TV install plan on 2026-11-17 in DEB-13, with implementation after the existing Tizen work. This file specifies the backend and admin-site half. DEB-4843 owns the Android guide and installer; DEB-4844 owns device QA. The new wire DTOs and database migration require Sjúrður's on-row approval before implementation.

## Scope

A signed-in Ravilo viewer asks the owner to add one Samsung TV's DUID to the release certificate. The owner approves or rejects in the jellystructure admin site. A request becomes Ready when the current public release's Tizen package contains that DUID. This page is for the owner; the viewer flow is Android phone only. No release workflow or certificate-signing change is part of this task.

## Requirements

**FR-1 · Persist requests per viewer and TV.** Store one `samsung_tv_requests` row per `(account, duid)`, with a server-issued id; account id, DUID, model, Tizen version and friendly name; state; created and decision times and actor; optional decision note; and `readyFromRelease`. Preserve withdrawn and rejected decisions in the row. A new request for the same account and DUID after withdrawal or rejection replaces its request details and returns to Requested; an already active request returns its existing row. A different account's row is separate. Times are UTC epoch seconds. Never log request bodies or DUIDs.

**FR-2 · Viewer routes require a device token and reveal only the caller's rows.** The prefix is `/api/tv/samsung-requests`, which the existing `/api/tv/` auth fence handles. The contract is additive; no existing route or DTO changes.

| Method and path | Request | Success | Refusal |
| --- | --- | --- | --- |
| `POST /api/tv/samsung-requests` | JSON `{duid, model, tizenVersion, name}` | `201` with the viewer row when newly requested; `200` with the existing active row | `400` invalid/oversize fields; `401` absent or invalid device token |
| `GET /api/tv/samsung-requests` | none | `200` JSON `{requests: [row]}` newest first | `401` absent or invalid device token |
| `DELETE /api/tv/samsung-requests/{id}` | none | `200` with the viewer row in Withdrawn state; repeat is idempotent | `404` unknown id or another viewer's row; `409` if the decision is already Approved or Ready |

The viewer row has `id`, `duid`, `model`, `tizenVersion`, `name`, `state`, `createdAt`, `decidedAt`, `decisionNote`, `readyFromRelease`, and `wgtUrl`. States are `REQUESTED`, `APPROVED`, `READY`, `REJECTED`, `WITHDRAWN`. `wgtUrl` is non-null only in Ready and points to the `.wgt` asset of `readyFromRelease`; the phone downloads that exact asset. Empty or malformed DUID, excessive text, or control characters are refused. The server derives account identity from the device token, never from the body. Withdrawal means the owner no longer needs to act on that row.

**FR-3 · Admin routes require the existing admin cookie session.** The prefix is `/api/samsung-tv/requests`; it is outside `/api/tv/` and therefore cannot be reached with a viewer device token. The list returns all rows, newest first, including withdrawn rows, with the viewer's display name and account id. The response also carries the latest release check state and both certificate expiry dates for FR-5.

| Method and path | Request | Success | Refusal |
| --- | --- | --- | --- |
| `GET /api/samsung-tv/requests` | none | `200` JSON `{requests: [adminRow], release: releaseStatus, certificates: certificateStatus}` | `401` no admin session; device token alone cannot enter |
| `POST /api/samsung-tv/requests/{id}/decision` | JSON `{decision: "APPROVE" | "REJECT", note: String?}` | `200` updated admin row, read from persisted state | `400` invalid decision/note; `404` unknown id; `409` already decided or withdrawn; `401` no admin session |

An admin row extends the viewer row with `requesterName`, `accountId`, and `decidedBy`. A decision is atomic and only a Requested row can be decided. Approve sets Approved; Reject sets Rejected. A note is optional and visible to the requester. Admin actions and the page never infer a new state locally: render the server response, then refresh the server list.

**FR-4 · Readiness follows the latest release's signed package.** On startup and periodically, fetch metadata for the latest published GitHub release of `jeggy/jellystructure`, select its `ravilo-tizen-*.wgt` asset, download it with size and timeout bounds, and read `signature1.xml` from inside the ZIP. Parse `URN:tizen:deviceid=` values from the distributor certificate in that signature. An Approved row whose DUID occurs there becomes Ready with `readyFromRelease` equal to the release tag. A Ready row whose DUID is absent from a later latest release becomes Approved again and loses its ready tag and URL; this prevents the phone installing a package that does not cover its TV. A failed check preserves the last verified state and reports a release-check error to the admin page. The parser fails closed on missing or malformed signatures and never treats a release asset name alone as proof. No CI or certificate-signing change is needed. The v1.70 public `.wgt` is a regression fixture: both `EXCKEBBVWCGFC` and `XTCJYJZXZBZVK` must be detected.

**FR-5 · Admin page: Samsung TV requests.** A Ravilo-section navigation item opens a page listing requester, friendly name, model, Tizen version, DUID with a Copy button, state, request/decision dates, note, and Ready release. Requested rows have Approve and Reject controls with an optional note. A button copies the DUIDs of all Approved and Ready requests for certificate issuance, once per DUID. The page warns beginning 60 days before the author certificate expiry (2027-09-18) and the distributor certificate expiry (2027-11-16), including which certificate and its date. Its copy comes from shipped `i18n/*.json` for all admin languages. Clipboard results and server errors are announced accessibly. The UI renders server state after each decision and release refresh.

**FR-6 · Verification.** Automated `:linuxX64Test` route tests cover create/list/withdraw, account isolation, input validation, decision transitions, and non-admin refusal. A parser/reconciliation test uses the real v1.70 `.wgt` or a byte-identical frozen fixture, so Ready changes without an admin checkbox and only for a covered DUID. The admin page is built in the existing Wasm CI job. QA checks its copy controls, state changes, and certificate warning dates, then DEB-4844 covers the Pixel 7 and Tizen emulator end-to-end flow.
