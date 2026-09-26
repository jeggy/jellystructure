# Phase 267 — Users & devices: most recent first, three shown, the rest one click away

> Owner, 2026-09-26: *"Let's add a sorting to all of our devices. So under each user we will sort every
> device, so the last used one will be on the top. And let's also only show the most 3 recent devices, so if
> there are more, we'll add a collapsable view, where it will say something like "+ 10 devices" and you'll
> have to click that to see all the devices for this specific user and when this is open a "Show less"
> should be available to collapse it again."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. One route (`GET /api/tv/admin/overview`) and the admin
page (`RaviloUsers.kt`, plus `design/app/ravilo-users.html` on the design side). **Numbering:** verified
against `STATUS.md` the same day — admin taken through **266**.

## What is there today, measured

`GET /api/tv/admin/overview` on production, 2026-09-26:

| User | Devices | Admin web sessions |
|---|---|---|
| the owner | **12** (3 Pixel 9 rows, 3 web, 3 TVs, 3 Chromecast hand-off rows) | 2 |
| three others | 2 each | 0 |

- **The devices already arrive newest first, but only by accident.** `getAllDevices` is
  `ORDER BY jellyfin_user_id, last_seen DESC`, and the route's per-user `filter` keeps that order. No
  code states the rule and no test holds it, so the next change to the query or the route can undo it
  silently.
- **"Last seen" is not "in use now".** `last_seen` is written at most once a minute, and only when a
  request carries the device token. A TV sitting on Home with its events socket open can be *connected*
  while its `last_seen` is an hour old, so it sorts below a phone that made one request since.
- **The users are in no order a person would choose.** The route lists users in the order their ids
  first appear in `jellyfin_user_id` order, a hex id. Today that puts a household member with two
  devices, last used three days ago, above the owner.
- **Admin web sessions** come from `SessionService.list()` in table order.
- **Every device is listed**, with no cap. The owner's card is twelve rows, each four to seven lines
  tall (capability, version, history toggle, warnings), before the web sessions and *Recently watched*.
- The page re-renders the whole list after *Refresh* and after every *Revoke*.

## Requirements

**FR-267-1 — The server orders, the page renders the order it gets.** `/api/tv/admin/overview` sorts
explicitly, in the route, not through the query's `ORDER BY`:

- **Devices, per user:** a connected device first (it is in use now), then by `last_seen`, newest first.
  Ties break by `created_at`, newest first, so the order is stable between two loads.
- **Admin web sessions, per user:** by `last_used_at`, newest first.
- **Users:** by their most recent activity (the newest `last_seen` / `last_used_at` across their devices
  and sessions, with *connected* counting as now), then by name. The person who used Ravilo last is at
  the top, as their newest device is at the top of their card.

The client does not sort (render-never-compute). A unit test on the route's pure ordering function
holds all three rules, including the connected-but-stale TV case.

**FR-267-2 — Three devices shown, the rest behind one button.** A user's card shows its first **three**
devices. When there are more, a fourth line reads **"+ N devices"**, where N is the number hidden. Clicking
it shows them all, in the same order. The same line then reads **"Show less"** and collapses them back
to three. A user with three or fewer devices has no such line. Hidden rows are in the page, only not
shown, so a device's own controls (Revoke, the version-history toggle) behave identically whether it is
in the first three or the rest.

**FR-267-3 — The same for web sessions, at the same threshold.** Admin web sessions follow FR-267-2's
rule on their own list (*"+ N sessions"* / *"Show less"*). Two today, so nothing changes visibly now, but
an admin who signs in from many browsers gets the same tidy card.

**FR-267-4 — An opened list stays open across a re-render.** Which users' lists are expanded is held in
memory for the page's lifetime (the pattern `Activity.kt` uses for `expandedJobGroups`). *Refresh*, and
the re-render after a *Revoke*, keep an opened list open, so revoking the fifth device does not fold the
list away under the pointer. A new page load starts collapsed.

**FR-267-5 — Counts stay whole.** The page header's *"N users · N devices · N connected now"* counts every
device, hidden or not. A connected device beyond the first three cannot exist (FR-267-1 puts connected
devices first), unless more than three are connected at once, and then the collapsed line says so:
*"+ N devices · 1 connected"*.

## Non-goals

- Revoking, signing out everywhere, the version history (259), the flapping and *behind* lines (256):
  unchanged, just ordered.
- Deleting old device rows automatically. Twelve rows for one person are real history (a re-paired phone
  is a new row). Tidying them is the *Revoke* button's job, or a later phase.
- The per-user *Recently watched* list, which already has its own *Show more*.

## Acceptance

1. Users & devices on production: the owner's card lists their twelve devices newest first, and a TV that
   is connected now is at the top even if another device made a request more recently. Only three
   show, followed by *"+ 9 devices"*.
2. Click *"+ 9 devices"*: all twelve show, in the same order, and the line reads *Show less*. Click it and
   the list folds to three.
3. Open the list, revoke the fifth device: the list re-renders still open, now with eleven devices.
4. The user who used Ravilo most recently is the first card.
5. A user with two devices shows both and no *"+ N"* line.
