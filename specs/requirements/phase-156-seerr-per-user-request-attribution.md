# Phase 156 — Seerr requests attributed to the actual Jellyfin user, not one shared account (FR-SEERR1)

> The operator is opening jellystructure to the public internet with multiple Jellyfin users. Today
> every Ravilo request — regardless of who submitted it — is proxied to Seerr under one shared
> admin-configured API key (Phase 136), so Seerr's approval UI can't show who asked for what. Combined
> with a Seerr-side non-owner service account with auto-approve permissions off (an operator config
> step, not a code change — see the "Do this first" note below), the operator wants: any Jellyfin user
> can request, nothing auto-approves except their own account, and Seerr's UI shows the real requester.

**Status:** Implemented.

## Background — confirmed against Seerr's own source (seerr-team/seerr, `develop`)
- `GET /user/jellyfin/{jellyfinUserId}` resolves a Seerr user by Jellyfin user ID; `404` if that
  Jellyfin user has never been imported/logged in.
- `POST /user/import-from-jellyfin` (body `{"jellyfinUserIds": ["<id>"]}`, requires `MANAGE_USERS` on
  the calling API key) provisions a local Seerr account for Jellyfin users who don't have one yet,
  without them ever needing to log into Seerr's own web UI. Returns the newly-created `User[]`.
- `POST /request` accepts an optional `userId` field. Traced in `server/entity/MediaRequest.ts:58-74`:
  when set, the **effective requester for every downstream check — REQUEST permission, quota, and
  crucially AUTO_APPROVE — becomes that target user**, not the API-key-authenticated caller. The caller
  only needs `MANAGE_USERS` or `MANAGE_REQUESTS` to be allowed to set `userId` at all; its own
  auto-approve status becomes irrelevant once a `userId` is supplied.

This means per-user attribution and per-user auto-approve control fall out of the same mechanism: once
jellystructure resolves and sends the real Jellyfin user's Seerr `userId`, Seerr's UI shows the real
requester **and** approval now depends on that specific person's own Seerr permissions — not the shared
service account's.

## Requirements

### FR-SEERR1-1 — Resolve the Jellyfin user's Seerr account on every request, no new persisted state
`SeerrClient` gains `resolveUserId(url, apiKey, jellyfinUserId): Int?`: try `GET
/user/jellyfin/{jellyfinUserId}` first; on `404`, fall back to `POST /user/import-from-jellyfin` and
take the first (only) returned user's `id`. No local mapping table — resolving live on each request is
cheap (one infrequent, user-initiated action, already behind `OutboundHttp`'s permit gate) and
self-heals if a Seerr account is ever unlinked/relinked, which a cached mapping would not.

### FR-SEERR1-2 — Thread the resolved id through to request creation
`SeerrClient.createRequest` gains an optional `seerrUserId: Int?`; when non-null, adds `"userId":
<id>` to the `POST /request` payload — otherwise reproduces today's plain request exactly.
`SeerrDiscoverService.request(...)` resolves via FR-SEERR1-1 using the Jellyfin `userId` it already
carries, and passes the result through.

### FR-SEERR1-3 — Resolution failure degrades to today's behaviour, not a hard failure
If `resolveUserId` fails (Seerr unreachable, `MANAGE_USERS` missing on the configured key, malformed
response), log a warning and proceed with `seerrUserId = null` — the request still goes through
attributed to the shared service account, exactly as it does today. Losing per-person attribution on an
intermittent Seerr hiccup must never block a request the viewer is actively waiting on.

## Operator setup (not code — do this in Seerr's own UI)
1. The API key configured in jellystructure's `[seerr]` settings must belong to a **non-owner** local
   Seerr account (an Owner account is always auto-approved and that can't be turned off) with
   `MANAGE_USERS` or `MANAGE_REQUESTS` permission, so it's allowed to set `userId` on others' behalf.
2. Check Seerr's Settings → General → default new-user permissions does **not** include any
   `AUTO_APPROVE*` permission — imported-on-demand users (FR-SEERR1-1's fallback) inherit whatever that
   default is.
3. If the operator wants their *own* requests to auto-approve while everyone else's don't, grant
   `AUTO_APPROVE`/`AUTO_APPROVE_MOVIE`/`AUTO_APPROVE_TV` individually to their own Jellyfin-linked Seerr
   account after it's been imported (first request, or a manual import) — otherwise everyone's requests,
   including the operator's own, land pending for manual approval.

## Out of scope
- Building any admin UI in jellystructure to view/manage the Jellyfin↔Seerr link — Seerr's own Settings
  → Users page already shows this once accounts exist.
- 4K request variants (`is4k`/`AUTO_APPROVE_4K*`) — untouched, same as today's plain-request path.
- Retroactively re-attributing requests made before this phase — they remain under the shared account.

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/seerr/SeerrClient.kt` — `createRequest`, new
  `resolveUserId`.
- `src/linuxX64Main/kotlin/dev/jellystructure/seerr/SeerrDiscoverService.kt` — `request(...)`.
- Seerr API confirmed against `seerr-api.yml` (`/user/jellyfin/{jellyfinUserId}`,
  `/user/import-from-jellyfin`, `/request`) and `server/entity/MediaRequest.ts:58-74` on
  `github.com/seerr-team/seerr` `develop`, 2026-08-02.
