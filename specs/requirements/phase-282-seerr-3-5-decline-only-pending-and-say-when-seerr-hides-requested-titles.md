# Phase 282 — Seerr 3.5.0: decline only what is pending, and say when Seerr hides requested titles

## Status

`Planned` — written 2026-09-28, not dev-reviewed. Prompted by Seerr's v3.5.0 release the same day
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
   the status-1 request) and four deletes, then one media delete, in that order.
2. Unit: `declineRequest` maps 200/204 → declined, 409 → refused with the body's `message`, 500/timeout →
   failed; `removeRequest` logs the refusal and still deletes.
3. Unit: the probe reports the hide-requested sentence when `/settings/main` answers `{"hideRequested":true}`,
   plain *Connected · v…* when it answers `{}`, `{"hideRequested":false}`, 403 or a timeout.
4. Against Seerr 3.5.0 with *Hide requested media* on: *Test connection* shows the sentence; with it off, the
   line reads as today. Against 3.4.1: as today.
5. Removing a request (the phase 186 cascade) for a title whose Seerr request is approved leaves no
   `Request not found` or 409 in the log at warn or above, and the title's request and media rows are gone.

## Open questions

1. Whether `GET /settings/main` should be read with the household key alone or also with `X-API-User` (156): the
   key is the admin's, so the plain read is right, but confirm the route needs no permission a service key
   lacks. Lean: plain read; a 403 counts as `false` per FR-282-3 either way.
