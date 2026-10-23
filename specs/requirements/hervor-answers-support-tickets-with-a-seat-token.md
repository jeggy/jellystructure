# Hervør answers support tickets with a seat token, and nothing else

Task: DEB-1508

> Owner, 2026-10-16 (DEB-799, 12:30Z): Hervør must not be forgotten. Owner, 2026-10-17 (DEB-799, 17:58Z): he wants her
> soon.
>
> Owner decision, DEB-1508, 2026-10-17, written into [`constitution.md`](../constitution.md) §Authentication (7) at
> `ba2a73f7`: *"A support seat token. An admin issues it on AI ▸ Support. It's stored hashed and revocable there in one
> click. It may only call `/api/support/**`: read tickets, and post or forward as Hervør. It can't touch the library,
> settings, users or Jellyfin, and it works only while `[support] hervor_enabled` is on."*

## Scope

Backend, plus the admin controls on AI ▸ Support that issue the token and flip the switch. This spec gives Hervør her
**reach**: a credential, the routes it opens, and what she may write through them. Phases 320 and 321 already treat
`HERVOR` as a holder, an author and a forward target, and their *Not in this phase* sections sent the credential here.
[`support-is-a-tab-on-the-ai-page.md`](support-is-a-tab-on-the-ai-page.md) (DEB-1500) already lists **Hervør · support**
under *Forward to* while `hervorEnabled` is true (its FR-11), and draws her messages with the **AI** and **support**
badges (its FR-8). Nothing on the viewer wire changes.

**Not this spec:** the Hervør seat itself, meaning its instructions, how it polls or wakes, and its tone. That is Claude
Top's track (DEB-1649). What the seat needs from this API is listed under *What the seat needs* below.
**Not decided here:** switching `hervor_enabled` on for prod. That is the owner's call after QA (DEB-1508, step 5).

## Current behaviour

- `[support] hervor_enabled` exists, defaults to false and is false on prod. Nothing can set it except the file:
  `PUT /api/config` restores `[support]` from the stored config (320's build notes).
- With it false, `AI Agent` forwards to `ADMIN` even when its answer says `HERVOR` (`SupportAi.rule`). A viewer's
  message to a seat-held ticket is owed by `ADMIN` (FR-320-13).
- Every `/api/support/**` route takes the admin cookie only (`AuthPlugin`'s last branch). No route can post as `HERVOR`.
- The code already has the credential's shape. Phase 111's `ApiKeyStore` issues `jsk_` keys, stores only `sha256Hex`
  of them, and `AuthPlugin` fences them to `/api/remote/**`. The Settings page shows a new key once, with *"copy it
  now, it won't be shown again"*. The seat token is built the same way, in its own table and its own branch.
- `support_message` already has `actor` and `actor_label` (DEB-1500 FR-12), which Activity › Support log reads.

## Requirements

### The token

**FR-1 — One live token, for one seat.** A new table, `support_seat_token`, in the next migration (`82.sqm` at the time
of writing; re-check, and keep the `.sqm` byte-identical to the `.sq`):

| Column | |
|---|---|
| `id` | text, random (`generateSecureToken()`) |
| `seat` | text, always `HERVOR` in this spec |
| `token_hash` | `sha256Hex` of the plaintext, indexed |
| `created_at`, `created_by` | epoch seconds; the issuing admin's Jellyfin user name |
| `last_used_at` | epoch seconds, nullable |
| `revoked_at`, `revoked_by` | nullable; the admin's name, or `SYSTEM` for a replacement |

The plaintext is `jss_` followed by `generateSecureToken()`. **It is never stored, never logged, and never sent again
after the response that issues it.** A 256-bit random secret needs no slow hash; `sha256Hex` is what session tokens and
API keys already use. At most one row per seat is live (`revoked_at` null). Rows are never deleted: a revoked row is the
audit record of a token that existed.

**FR-2 — Issuing, replacing, revoking.** Three new admin routes, cookie only (FR-5 refuses them to the seat):

- `GET /api/support/seat-token` → `{ live: { issuedAt, issuedBy, lastUsedAt } | null, past: [ { issuedAt, issuedBy,
  revokedAt, revokedBy, lastUsedAt } ] }`. `past` is the five most recent revoked rows. No hash and no id leave the
  server.
- `POST /api/support/seat-token` → **201** `{ token: "jss_…", live: {…} }`. When a token is already live, the same
  transaction revokes it (`revoked_by = SYSTEM`) and issues the new one. This is **replace**: `hervor_enabled` is left as
  it was, and the old token is dead on its next request.
- `DELETE /api/support/seat-token` → revokes the live token and answers the `GET` shape. **Revoking also switches Hervør
  off** (FR-9's hand-back runs in the same act), so one click stops her completely and a later token never switches her
  back on by itself. With no live token it answers 204 and changes nothing.

**Revocation takes effect on the next request.** If validation is cached the way `ApiKeyStore` caches, revoke and
replace clear the cache entry in the same call. `last_used_at` is written at most once a minute.

### Where it is accepted

**FR-3 — The seat presents it as a bearer.** `Authorization: Bearer jss_…`. `AuthPlugin` judges **every** `/api/**`
request carrying a `jss_` bearer as a seat call, before any other branch, the open paths and `/api/health` included.
That is what makes the constitution's *"anything outside its scope is refused with 403"* hold on routes that would
otherwise answer 401 to an unknown bearer, or not ask for one at all:

1. unknown or revoked token → **401** `seat_token_invalid`, the same as a missing credential;
2. effective `hervor_enabled` (FR-8) false → **403** `hervor_disabled`;
3. method and path not in FR-4's table → **403** `not_for_seat`. This covers every other `/api/**` route, the `/ws` and
   `/api/tv/events` upgrades, `/api/remote/**`, `/api/tv/**` and `/api/health`;
4. otherwise the call proceeds with a `SupportSeatKey` attribute carrying the token's `id`, and **no** `SessionKey`.

A seat call never falls through to the cookie branch. A request with a `jss_` bearer and a valid admin cookie is a seat
call. The admin's browser never sends a bearer, so its requests are unchanged. The path is the decoded routing path
(`decodeRoutingPath`), the same one the other branches match on.

**FR-4 — The routes it reaches.** Exactly these. Each is the existing route, answering the existing DTO, except where
the right-hand column says otherwise:

| Route | For the seat |
|---|---|
| `GET /api/support/summary` | as for an admin, plus FR-10's `owedByHervor` |
| `GET /api/support/tickets` | only FR-6's tickets, whatever the query asks |
| `GET /api/support/tickets/{n}` | only FR-6's tickets; any other number is **404** |
| `POST /api/support/tickets/{n}/messages` | FR-11 |
| `POST /api/support/tickets/{n}/forward` | FR-12 |

Everything else under `/api/support/**` answers **403** `not_for_seat`. That is the switches (`/enabled`, FR-8's
`/hervor`), the token routes (FR-2), `/log`, `/transcript`, `/state`, `/class`, `/flag`, the admin read marker and
`DELETE`. So Hervør can never resolve, reopen, classify, flag, unflag or delete a ticket. Phase 320's first line,
*auto applies to talking, never to acting*, holds for her as it does for `AI Agent`. She cannot move the admin's read
marker, so an admin's unread dots mean what they did before she existed.

**FR-5 — The admin routes never take the seat token, and the seat routes never lose the cookie.** An admin keeps every
`/api/support/**` route exactly as today, by cookie. The seat token is never set as a cookie, never minted from an admin
login, and never extends one (constitution §Authentication, 7).

### What she sees

**FR-6 — Her tickets, and only hers.** A ticket is **Hervør's** when it is not flagged and any of these holds: `holder =
HERVOR`, `owes = HERVOR`, or the thread has a `MESSAGE` authored `HERVOR`. Every other ticket does not exist for the
seat: it is absent from the list and its number answers 404, never 403, so a number reveals nothing (FR-320-3's rule).
A flagged ticket is absent because no automatic reply may reach it (FR-320-12); only an admin answers there. The
constitution's 403 is for a route outside the token's scope (FR-3, FR-4); a ticket outside FR-6 on a route inside it is
a 404, because a 403 there would say the number exists.

The owner's decision says *read tickets*. This spec narrows it to the tickets she is asked to answer, because tickets
are personal data and the seat runs on a hosted model. An admin can still forward any ticket to her. If the owner wants
her to see every ticket (to match one fault across viewers), the change is FR-6 alone.

**FR-7 — The viewer stays anonymous to her.** On a seat call, `SupportAdminTicket.ownerId` and `ownerName` are written
as the empty string, and every `ADMIN` message's `authorLabel` is `Admin`. Nothing else in the DTOs changes. This is
FR-321-4's *never sent: the viewer's user name or id* for `AI Agent`, applied to the second AI that reads the same
threads. The bodies are served as written, and a viewer may type anything into one, including names (FR-321-11).

### The switch

**FR-8 — `hervor_enabled` is switched on the Support tab, and only with a live token.** A new admin route,
`POST /api/support/hervor {"enabled": true|false}`, writes `[support] hervor_enabled` through `ConfigStore.update`,
like DEB-1500's `/enabled`, and answers the summary. `PUT /api/config` goes on restoring `[support]`, so a Settings save
never flips it.

- Turning it **on** with no live token answers **409** `no_seat_token`. A ticket forwarded to someone who cannot answer
  is the failure FR-320-13 exists to prevent.
- **The effective value** is `hervor_enabled` **and** a live token. Every reader that 320 and 321 already have —
  `SupportAi.rule`'s forward target, FR-320-13's `owesFor`, the summary's `hervorEnabled` and so DEB-1500's *Forward to*
  list — reads the effective value. A hand edit of `config.toml` with no token therefore cannot send tickets to her.

**FR-9 — Switching her off hands every ticket back.** Turning the switch off, revoking the token (FR-2), or the effective
value going false any other way runs one pass, in the shape of FR-321-9:

- every ticket with `holder = HERVOR` moves to `holder = ADMIN`, with one `VIEWER` `FORWARDED` line and an `ADMIN_ONLY`
  reason, *"Hervør was switched off"*;
- every ticket with `owes = HERVOR` is owed by `ADMIN`. **`owed_since` keeps its age**, so a ticket that was already
  late shows the admin as overdue at once;
- there is no forward notice in the thread, because Hervør cannot speak once she is off;
- the lines' actor is the admin who pressed it, or `SYSTEM`.

Switching her back on moves nothing back. `AI Agent`'s next forwards, and an admin's, go to her again.

### Her queue

**FR-10 — The queue is `owes=HERVOR`.** `GET /api/support/tickets?owes=HERVOR` is her queue. Each row already carries
`owedSince`, `overdue`, `viewerLanguage`, `class`, `englishSummary` and `englishTitle`. `SupportSummary` gains
`owedByHervor: Int`, counted like `owedByAdmin` over unflagged tickets, so a poll can be one small read. It is additive
on an admin DTO; the admin page does not have to draw it.

### What she writes

**FR-11 — A reply, as Hervør.** `POST /api/support/tickets/{n}/messages {"body"}` on a seat call writes a `MESSAGE` with
`author = HERVOR`, `author_label = Hervør`, `actor = HERVOR`, `actor_label = Hervør`, and `seat_token_id` (FR-13), and
sets `owes = NONE` exactly as an admin's reply does. Then every rule an admin's reply already has applies, plus these,
in this order:

1. the ticket is Hervør's (FR-6), else **404**;
2. support is available, else **409** `support_unavailable` (FR-320-1);
3. `owes = HERVOR` or `holder = HERVOR`, else **409** `not_hervors_turn`. A ticket she wrote in once and that has since
   gone to an admin is readable, but not hers to answer;
4. the body is non-blank and within `max_body_chars`, else **400**, as for a viewer (FR-320-11);
5. the body passes FR-321-5 rule 4, the machine check for internal strings (requirement ids, phase ids, repo paths,
   seat names other than Hervør), else **422** `internal_string`. The reason names the kind of string and quotes none of
   it. The check is `SupportAi`'s own, not a copy;
6. within `[support] hervor_messages_per_hour` (default 30, across all tickets) and `hervor_messages_per_ticket_per_day`
   (default 6, rolling 24 h), counted from the store, else **429** with `Retry-After`. These bound a seat stuck in a
   loop. Both are new `SupportConfig` keys.

The server does not check the reply's language. Writing in the viewer's `viewerLanguage` is the seat's job.

**FR-12 — A forward, as Hervør.** `POST /api/support/tickets/{n}/forward` on a seat call, with FR-11's rules 1–3, and:

- `holder` is `ADMIN` or `SEAT`; `HERVOR` answers **400**, because she already has it;
- `owes` is `ADMIN` or `HERVOR`, as FR-320-12 says. `SEAT` with `owes = HERVOR` is how she parks a ticket with a seat
  that may not answer, and still owes the viewer the next message;
- `note` is **required** and non-blank, English, at most 600 characters, else **400**. An admin taking a ticket from her
  must be told why;
- the `FORWARDED` and `ADMIN_ONLY` lines are written exactly as an admin's forward writes them, with `actor = HERVOR`
  and `seat_token_id`. The note line is authored `HERVOR`, not `ADMIN` (today's forward writes the note as `ADMIN`).

Every line a seat call writes is authored `HERVOR` or is a `SYSTEM` line whose actor is `HERVOR`. There is no request
shape that makes it write as `ADMIN`, `AI_AGENT` or `VIEWER`.

### Audit

**FR-13 — Every line names the token that wrote it.** `support_message` gains a nullable `seat_token_id`, in FR-1's
migration. It is set on every line a seat call writes and null everywhere else. After a leak, *"what did that token
write"* is one query. It is never on any wire.

**FR-14 — Where an admin reads it.**

- **Activity › Support log** (DEB-1500 FR-12) already lists `replied` and `forwarded`. With FR-11's and FR-12's actor,
  Hervør's entries read `HERVOR` · `Hervør`. No new action code.
- **The token's history** is FR-2's `GET`: who issued it and when, when it was last used, and who revoked it. The tab
  shows it (FR-15).
- **The server log** gets one line per issue, replace, revoke and switch change, naming the admin and the event. A
  refused seat call is logged as its status and code with the path. Never the token, never its hash, never a body
  (FR-320-16).

Reads are not audited one by one. `last_used_at` is the record that the seat is reading.

### The admin's controls

**FR-15 — A Hervør strip on AI ▸ Support.** Under DEB-1500 FR-3's lane strip, a second strip, built from the same
`.tk-lane` markup and the Settings page's *copy it now* field. **No new CSS class.** If the build finds it needs one, it
stops and asks for it through Rannvá, because `design/` is hers.

- **No token:** a `.tk-lane.closed` strip reading *"Hervør has no token, so she can't read or answer tickets."* and a
  primary button **Issue a token**.
- **Token live, switch off:** *"Hervør's token was issued {date} by {admin} · last used {relative, or never}. She is
  switched off, so tickets go to admins."* Buttons **Switch Hervør on**, **Replace token**, **Revoke**.
- **Token live, switch on:** *"Hervør answers what AI Agent forwards to her · token issued {date} by {admin} · last used
  {relative, or never}."* Buttons **Switch Hervør off**, **Replace token**, **Revoke**.
- **After issuing or replacing:** *"Token issued — copy it now, it won't be shown again:"* and the token in a read-only
  `.input.mono` field with `user-select: all`, until the admin leaves the tab. The page holds the plaintext in that field
  only, never in a variable that outlives it.
- **Revoke** is one click, as the owner decided. Its result line reads *"Revoked. Hervør is switched off, and her tickets
  are with admins."*
- **Switch Hervør off** asks first: *"Switch Hervør off? Every ticket she holds or owes goes back to admins. The viewers
  see it forwarded."* Buttons **Switch off** and **Cancel**.
- **Replace token** asks first: *"Replace Hervør's token? The one she has stops working at once, and she can't answer
  until she is given the new one."* Buttons **Replace** and **Cancel**.
- One line under the strip says what she reads: *"Hervør reads the tickets she holds — their text and the viewer's
  language, never the viewer's name or id."*

The strip reads `GET /api/support/seat-token` and the summary's `hervorEnabled` on open, and renders each write's answer
and nothing it assumed. While DEB-1253's offline strip shows, its buttons are disabled (DEB-1500 FR-16). It is shown
whenever the Support tab is, lane open or closed: an admin can prepare Hervør before opening the lane.

## What the seat needs

For Claude Top's track (DEB-1649). None of this is built by the seat; it is what the API gives it.

- **Base URL** `https://jelly.jebster.net`, header `Authorization: Bearer jss_…`. The admin pastes the token where the
  seat keeps its secrets; it is shown once.
- **No push.** `/ws` refuses the token (FR-3). The seat polls: `GET /api/support/summary` for `owedByHervor`, then
  `GET /api/support/tickets?owes=HERVOR` and sorts by `owedSince`, oldest first. A ticket goes overdue after
  `owed_reply_hours` (24 by default), which bounds how slowly it may poll.
- **Context per ticket:** `GET /api/support/tickets/{n}`. The `ADMIN_ONLY` lines carry `AI Agent`'s English summary,
  the reproduction and why it forwarded. The viewer's name and id are not served (FR-7).
- **Answering:** `POST …/messages` in the viewer's `viewerLanguage`. A 422 names the internal string to remove. A 409
  `not_hervors_turn` means the ticket has moved on.
- **Handing back:** `POST …/forward {"holder":"ADMIN","owes":"ADMIN","note":"…"}`, with an English note.
- **What it cannot do:** resolve, classify, flag or delete a ticket, see a ticket that is not hers, read the log or a
  transcript, or change any switch. `403 hervor_disabled` means an admin switched her off; the seat stops until it
  stops getting one.
- **Phase 320's first line binds the seat too:** nothing a viewer writes may create a spec, a phase, a board task, a
  code change or a deploy without the owner. The seat may tell a viewer a fault is known only from what it has been
  given to read; this API gives it no known-problem list.
  *Amended 2026-10-23 (DEB-2184):* phase 320's first line is amended. A viewer's words may now reach Bárður through
  Hervør and start an investigation or a design round, and the build queue and prod still need the owner's card.
  The owner also asked for her seat to get wider reach across the organisation in Paperclip: asking a teammate,
  reading the board and the product specs. That reach lives in her instructions, not here. **This API is
  unchanged**: the token still opens exactly FR-4's five routes.

## Strings

Admin copy is English: the strings quoted in FR-2, FR-9 and FR-15. The viewer sees Hervør only as `Hervør` on her
messages and holder lines, which the Ravilo screens already draw.

## Tests

Backend route tests in `linuxX64Test`:

- **The token:** issue returns a `jss_` token once and stores only its hash; replace kills the old token on its next
  request and leaves the switch as it was; revoke kills it, turns the switch off and runs the hand-back.
- **Refusals, across the route table:** a revoked or unknown `jss_` token gets 401 on `/api/support/tickets`. A live
  token with the switch off gets 403 `hervor_disabled`. A live token with the switch on gets 403 `not_for_seat` on at
  least `/api/config`, `/api/support/enabled`, `/api/support/hervor`, `/api/support/seat-token`, `/api/support/log`,
  `…/state`, `…/class`, `…/flag`, `…/read`, `DELETE …/{n}`, `/api/remote/devices`, `/api/tv/config`, `/api/tv/login`,
  `/api/health` and a `/ws` upgrade. A `jss_` bearer beside a valid admin cookie is still a seat call.
- **Visibility:** another ticket's number, and a flagged one, give 404; the list carries only FR-6's tickets whatever the
  query; `ownerId`, `ownerName` and admin labels are blanked on the seat's JSON and unchanged on the admin's.
- **Writes:** a reply is authored `HERVOR`, labelled `Hervør`, sets `owes = NONE` and carries `seat_token_id`; each of
  FR-11's six rules fails alone with its status; a forward without a note, or to `HERVOR`, is a 400; the forward's note
  line is authored `HERVOR`.
- **The switch:** on with no token is a 409; off hands back with the age kept and no notice; the effective value
  governs `SupportAi.rule` and `owesFor` when the file says true and no token is live.
- **Audit:** the log shows Hervør's reply and forward as actor `HERVOR`; the log and the server log contain no token,
  hash or body.

The Playwright spec `tests/e2e/ai-page-support-tab.spec.ts` gains the strip's four states, the one-time token field, and
both confirm dialogs, with `/api/support/seat-token` and `/api/support/hervor` stubbed.

## Acceptance

1. **Issue and switch on.** On the test stack, an admin issues a token. It is shown once, and a reload shows only the
   issued-by line. *Switch Hervør on* succeeds. *Forward to* on a ticket now lists **Hervør · support**.
2. **She answers.** An admin forwards a ticket to Hervør. With the token, `GET …/tickets?owes=HERVOR` lists it with no
   viewer name. A `POST …/messages` lands in the thread as **Hervør**, and the viewer's next `GET` shows it, unread.
   The viewer replies, and the ticket is back on her queue.
3. **She is fenced.** With the same token, `/api/config`, `/api/support/log` and `…/state` answer 403, and an unknown
   ticket number 404.
4. **One-click revoke.** *Revoke*. The next seat request answers 401. The switch reads off. A ticket she held reads
   holder `ADMIN` to the viewer, with a forward line and no notice, and is still overdue if it was.
5. **Replace.** *Replace token* while she is on. The old token is 401 on its next request, the new one works, and the
   switch stays on.

## Non-goals

- **The seat**: its instructions, polling cadence, tone and hiring (DEB-1649, Claude Top).
- **Switching her on for prod** (the owner, DEB-1508 step 5).
- **A push channel for the seat.** It polls.
- **Hervør resolving, classifying or flagging**, and any known-problem list for her to cite.
- **More than one seat or one live token.** `seat` is a column so a later spec can add one without a migration.
- **DEB-1500 FR-11's *Owner* forward row.** It needed Hervør and still needs its own spec.
- **A per-read audit trail.**
