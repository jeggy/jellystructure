# Phase 320 — A viewer can file a support ticket, and an admin can answer it by hand

> Owner, 2026-10-14 (DEB-1076), the ask behind the support lane: a viewer reports a problem, asks a
> question or requests a feature from inside Ravilo, and gets a real answer — usually from an AI, and
> always truthfully. Owner, 2026-10-15 (DEB-799, via DEB-1238): make the support lane the team's next
> focus.
>
> Owner, 2026-10-15 (DEB-1076): *"If no AI key, then the support feature will not be available in
> jellystructure nor on the ravilo apps."*

**Two lines come before everything else in the support lane, and every later phase inherits them:**

1. **Auto applies to talking, never to acting.** A reply may send itself. Nothing a viewer writes may
   create a spec, a phase, a board task, a code change or a deploy without the owner.
2. **The support lane exists only while an AI key is set.** With no key there is no support anywhere:
   no admin surface, no Ravilo entry. With a key set, the queue, the thread and an admin's reply keep
   working when AI is switched off, when the key is revoked, and when the month's limit is reached.
   `AI Agent` usually answers, and an admin can always answer by hand.

> **Amended 2026-10-15, before any code (DEB-1076).** This spec was adopted at `71e46346` with line 2
> reading *"the queue, the thread and an admin's reply work with no AI key"*. That came from the
> design brief's amendment (DEB-1117), not from the owner, and the owner reversed it the same day.
> FR-320-1, FR-320-19 and the acceptance list were rewritten to match. The file name keeps its
> original slug so that links from the board keep working. The heading above is the phase's title.

## Scope

The backend only: a ticket store, the viewer routes under `/api/tv/support/**`, the admin routes under
`/api/support/**`, and the pushes that let both kinds of client render server state. **No admin page
and no Ravilo screen** — both are drawn (design round 3, `support-lane`, received 2026-10-14) and are
adopted as their own phases once the round is pulled and gated (DEB-803, waiting on DEB-1236). The admin
half's home is settled: it is the studio's pick, a Support tab on the AI page. It needs no exception
for the no-key state, because with no key there is no support to show (FR-320-1). **No `AI Agent`**
— its reply is phase 321, which builds on this one.

What it supersedes: nothing; no support, ticket or feedback surface existed before it. What it does
not touch: phase 270/272's AI rail, the Seerr request lane, R152's server-message toast.

Number allocated from [`STATUS.md`](../../STATUS.md) and the board on 2026-10-15: the admin table's
highest row was 319 and no board row claimed 320, so 320 was the next free one.

The design brief (DEB-1112, amended by DEB-1117), the owner's eight decisions of 2026-10-14 and the
investigation note on DEB-1076 are the sources. Round 3's drawings are **not** a source for this phase:
nothing here depends on a pixel, and everything here holds under each of the three directions the round
drew.

## Settled by the owner (2026-10-14), quoted so this spec stands alone

- *A ticket is filed on a phone, a desktop or the web — never on the TV. On a TV the thread is
  read-only, plus a one-tap "same here".*
- *Every message names its author, from exactly four kinds: the viewer, `AI Agent`, `Hervør`, and a
  jellystructure admin.* `AI Agent` has no name and never gets one.
- *Only `AI Agent`, `Hervør` and jellystructure admins may post a user-visible message. Other seats can
  receive a forward but never answer into the thread.*
- *A reply either sends itself or forwards. There is no "waiting for approval" state the viewer can
  see.*
- *The viewer sees the ticket's real state and who holds it, in user-facing words only — never a phase
  number, an internal name or a repo path.*
- *All tickets and communication should always be in English on our side* — the viewer's own words and
  the replies to them are in the viewer's language; every summary, note and column the admin side
  writes is English.
- From the constitution: *the frontend renders server-pushed state only — no derived or optimistic
  local state.* A sent message exists for a client when the server's echo says so.

## Requirements

**FR-320-1 — Available only while the owner switches it on and an AI key is set.** A new
`[support]` section in `config.toml`, `enabled = false` by default. Support is **available** when both
of these hold:

- `[support] enabled` is true, and
- `[ai] api_key` is non-blank.

The key test is the same one that decides whether the admin's AI page exists. It is deliberately
**not** `ai.enabled`, the key's validity, or the monthly limit. Switching AI off, a revoked key and a
spent limit leave support available, and phase 321 forwards to an admin whatever `AI Agent` cannot
answer. Only a missing key removes the lane.

While support is unavailable, for either reason:

- every `/api/tv/support/**` route answers **404**;
- `/api/tv/config` reports `supportEnabled: false`, so no Ravilo app draws an entry;
- the admin's `GET` routes still answer, so the data stays readable for a later admin surface and
  for tests;
- every admin write (reply, forward, state, class, flag, read, delete) answers **409**
  `support_unavailable`, because a reply no viewer can see is worse than none;
- no `support_changed` push is sent.

**Nothing is deleted when support becomes unavailable.** Tickets, messages and markers stay in the
store. FR-320-17's retention pass keeps running. When the key returns, every ticket reappears exactly
as it was. A viewer whose ticket was open when the key went is told nothing, because no surface is left
to tell them on. That is the owner's call, and it is written here so that nobody adds a notice later
by accident.

Saving or removing the key, and changing `[support] enabled`, both change `supportEnabled`. Either
change is pushed as the `config_changed` event that Ravilo already re-fetches on (`TvEventBus`), so the
entry appears and disappears without a restart.

Prod serves viewers beyond the household, and turning a new inbox on for them is the owner's call, not
a deploy's side effect. Prod has no AI key today (phase 270, *Verified*), so support stays dark there
until the owner sets one. The settings toggle arrives with the admin phase. Until then, the switch is
the file.

**FR-320-2 — The store.** Two tables, added in the next migration (`79.sqm` at the time of writing —
re-check, and keep the `.sqm` byte-identical to the `.sq`):

- `support_ticket` — `id` (integer, autoincrement; **this is the ticket number a viewer sees, `#41`**),
  `owner_user_id` (the Jellyfin user id), `state`, `holder`, `holder_seat` (nullable), `owes`,
  `owed_since`, `class` (nullable), `english_summary` (nullable), `viewer_language`, `flagged`
  (0/1), `admin_read_message_id`, `viewer_read_message_id`, `created_at`, `updated_at`.
- `support_message` — `id`, `ticket_id`, `kind` (`MESSAGE` · `SYSTEM` · `SAME_HERE`), `author`
  (`VIEWER` · `AI_AGENT` · `HERVOR` · `ADMIN`; null for `SYSTEM`), `author_label`, `body` (nullable for
  `SYSTEM`), `event` and `event_args` (for `SYSTEM`), `visibility` (`VIEWER` · `ADMIN_ONLY`),
  `device_platform` (the platform the viewer posted from), `created_at`.

Times are epoch **seconds**, like `media_job` and `file_av_sync`. Deleting a ticket deletes its messages
in the same transaction. The word "ticket" already names a stream ticket in this codebase. Every new
type is prefixed `Support` (`SupportTicket`, `SupportMessage`) and every table `support_`, so the two
are never confused.

**FR-320-3 — The viewer is the session, never the body.** Every viewer route takes the user from
`call.attributes[DeviceKey].jellyfinUserId` (FR-187-2's rule), never from a parameter. A viewer reads
and writes **only their own tickets**. Another viewer's ticket number answers **404**, never 403, so a
number reveals nothing about whether it exists. *(The owner card on DEB-1238 asks whether a bug report
should be visible to other viewers. This phase is written for the recommended answer, strictly
private. A different answer comes back as a dated amendment here.)*

**FR-320-4 — Filing, and the receipt that needs no model.** `POST /api/tv/support/tickets` with
`{ "body": "…" }` creates the ticket and its first message, and answers **201** with the whole thread
(FR-320-8). The same transaction writes one `SYSTEM` line, `RECEIVED`, carrying the ticket number.
That line is the deterministic receipt: it is there the instant the server answers, it cannot be wrong,
and it does not wait on anything. A new ticket starts with `state = OPEN`, `holder = ADMIN`,
`owes = ADMIN`, `owed_since = now`. Phase 321 changes the starting holder when `AI Agent` is on.
`viewer_language` is the viewer's resolved `ui_language` (R162's `ResolvedBehaviour`) at that moment.

**FR-320-5 — Never filed from a TV.** Filing and posting are refused with **403** when the device's
`X-Ravilo-Platform` is `tv`, `tizen`, `cast`, `cast-audio`, or absent. They are allowed from `phone`,
`web`, `mac` and `linux`. The platform header is self-reported, so this is a product rule, not a
security boundary. The limits in FR-320-11 are what bound abuse.

**FR-320-6 — A viewer's message, and reopening.** `POST /api/tv/support/tickets/{n}/messages` with
`{ "body": "…" }` appends a `MESSAGE` by `VIEWER` and answers with the whole thread. On a `RESOLVED`
ticket it also sets `state = OPEN`, `holder = ADMIN`, `owes = ADMIN`, and writes a `SYSTEM` line
`REOPENED`. Reopening is a new message, not a separate action, so a client may draw a composer or a
reopen button and the server does not care which.

**FR-320-7 — "Same here".** `POST /api/tv/support/tickets/{n}/same-here` appends a `SAME_HERE` entry by
`VIEWER` with the posting device's platform, and answers with the thread. It is allowed from **every**
platform, the TV included. A `SAME_HERE` on a resolved ticket reopens it exactly as FR-320-6 does. At
most one per ticket per device in any 6 hours; a repeat inside that window answers **200** with the
thread unchanged, because a second tap is not an error.

**FR-320-8 — Reading.** `GET /api/tv/support/tickets` returns the viewer's own tickets, most recent
activity first. Each row carries the number, state, holder, class, the first message's first 140
characters, the last activity time, and the unread count. `GET /api/tv/support/tickets/{n}` returns one
`SupportThread`: the ticket and every message with `visibility = VIEWER`, oldest first.

**What the viewer wire never carries:** `holder_seat`, `owes`, `owed_since`, `english_summary`,
`flagged`, `ADMIN_ONLY` lines, or any forward note. On the viewer wire the holder is one of `AI_AGENT`,
`HERVOR`, `ADMIN` or `TEAM`. `TEAM` stands for every seat that may hold a ticket but may not answer
into it, so an internal seat name never crosses `/api/tv/**`. An admin's `author_label` is their
Jellyfin user name; `Hervør`'s is `Hervør`; `AI Agent`'s is the literal `AI Agent`.

**FR-320-9 — Unread.** `POST /api/tv/support/tickets/{n}/read` with `{ "upTo": <messageId> }` moves the
viewer's read marker forward, never back, and answers 204. A ticket's unread count is the number of
`VIEWER`-visible entries after that marker that the viewer did not write: `MESSAGE`s by another author,
plus `SYSTEM` lines that change state or holder. `GET /api/tv/support/unread` returns
`{ "tickets": <number of tickets with unread> }` for the nav marker.

**FR-320-10 — Pushes.** Each client gets its own push:

- **To Ravilo:** any change a viewer can see sends `{ "type": "support_changed", "ticket": n }` on
  `/api/tv/events` to that viewer's sockets only. **Only to a socket that opted in with a new
  `features=` flag**, because an installed app treats an unknown event as a config change
  (`Server.kt`'s comment above the events socket). The client re-fetches; the event carries no
  content.
- **To the admin:** any change sends a new `JobEvent` subtype, `support_ticket`, on `/ws`, carrying
  that ticket's admin row (FR-320-12) and the summary counts (FR-320-14). The admin renders the pushed
  row; it never accumulates counts of its own.

**FR-320-11 — Limits, counted from the store so a restart forgets nothing.**

| What | Default | Config key |
|---|---|---|
| New tickets per viewer per rolling 24 h | 5 | `tickets_per_day` |
| Messages per viewer per rolling 24 h, all tickets | 50 | `messages_per_day` |
| One message's body | 4 000 characters (code points) | `max_body_chars` |

Past a limit the route answers **429** with `Retry-After`. An empty or whitespace-only body answers
**400**, and so does an over-long one, with a reason the client can show. The limit stays far above
the brief's 2 000-character report and still bounds what one viewer can write.

**FR-320-12 — The admin side.** All routes sit under `/api/support/**`, behind the constitution's admin
cookie.

- `GET /api/support/tickets?state=&holder=&owes=&flagged=&overdue=` — every viewer's tickets. Each row
  carries what the viewer row carries, plus the viewer's Jellyfin user name, `holder_seat`, `owes`,
  `owed_since`, `overdue`, `flagged`, `english_summary` and the admin unread count. Flagged tickets are
  hidden unless `flagged=true` is asked for.
- `GET /api/support/tickets/{n}` — the whole thread, `ADMIN_ONLY` lines included.
- `POST /api/support/tickets/{n}/messages` `{ "body" }` — an admin's reply: a `MESSAGE` by `ADMIN`,
  labelled with the admin's Jellyfin user name. It sets `owes = NONE` until the viewer next writes.
- `POST /api/support/tickets/{n}/forward` `{ "holder": "ADMIN|HERVOR|SEAT", "seat": "…", "owes":
  "ADMIN|HERVOR", "note": "…" }` — moves the ticket. It writes one `VIEWER` line `FORWARDED` whose
  argument is the viewer-wire holder only (FR-320-8). The English note goes into an `ADMIN_ONLY` line.
  `owes` must be `ADMIN` or `HERVOR`: a seat that may not answer can hold a ticket, but it never owes
  the viewer the next message.
- `POST /api/support/tickets/{n}/state` `{ "state": "OPEN|IN_PROGRESS|RESOLVED" }` — writes a `VIEWER`
  line `STATE_CHANGED`.
- `POST /api/support/tickets/{n}/class` `{ "class": "BUG|REQUEST|QUESTION|ACCOUNT|OTHER" }` — writes a
  `VIEWER` line `CLASSIFIED`.
- `POST /api/support/tickets/{n}/flag` `{ "flagged": true|false }` — writes an `ADMIN_ONLY` line and
  nothing the viewer can see. A flagged ticket looks exactly as it did to the viewer: their messages
  still post (inside FR-320-11's limits) and no reply is ever sent on its own. An admin may still reply
  by hand, because that is a human act.
- `POST /api/support/tickets/{n}/read` `{ "upTo" }` and `DELETE /api/support/tickets/{n}`.
- `GET /api/support/summary` — FR-320-14.

**FR-320-13 — Who owes the viewer the next message.** Every ticket records `owes` (`ADMIN`, `HERVOR`,
`AI_AGENT` or `NONE`) and `owed_since`. A viewer's message or same-here sets `owes` to the holder if the
holder may answer, and otherwise to `HERVOR` when `[support] hervor_enabled` is true, or to `ADMIN`.
A ticket is **overdue** when `owes ≠ NONE` and `now − owed_since` exceeds `owed_reply_hours`
(default 24). Overdue is a computed field on the admin row, never a state the viewer sees. This is the
whole defence against the failure mode the investigation named: a ticket parked with a seat that may
not answer, going quiet. `hervor_enabled` is `false` by default, because Hervør's reach into this API is
not built (see *Not in this phase*).

**FR-320-14 — The count the admin's nav carries.** `GET /api/support/summary` returns `{ open,
owedByAdmin, overdue }`, computed over unflagged tickets. Round 3 drew the count on a sidebar entry; this
phase provides the number and does not decide where it is drawn.

**FR-320-15 — `SYSTEM` lines are codes, not prose.** A `SYSTEM` line stores an `event` (`RECEIVED`,
`FORWARDED`, `STATE_CHANGED`, `CLASSIFIED`, `REOPENED`, `FLAGGED`, `UNFLAGGED`) and its arguments. It
never stores a sentence. The client renders it in the viewer's language from its own strings. That
keeps a system line out of English on the viewer side, and it keeps a sentence from turning into an
internal name later.

**FR-320-16 — The viewer's words are data.** A ticket body is stored verbatim and never interpreted.
The admin renders every body as text (`textContent` or `esc()`), never as HTML, because the text comes
from accounts outside the household. A ticket body is never written to a log. Logs name the ticket
number and the event.

**FR-320-17 — A viewer can delete what they wrote; nothing is kept forever.**
`DELETE /api/tv/support/tickets/{n}` deletes the viewer's own ticket and every message on it, and
answers 204. A daily pass deletes tickets that have been `RESOLVED` with no activity for
`retention_days` (default 365). Tickets are personal data. This is the whole retention policy for
this phase.

**FR-320-18 — The wire is additive.** Every new DTO goes in `:shared`. `supportEnabled` is a new field on
the existing config DTO with a default, so an installed app that has never heard of it decodes it
unharmed. The new viewer DTOs join `WIRE_ROOTS` so `WireCompatTest` holds them from the first release.

**FR-320-19 — Tests.** These run in `linuxX64Test`, against the store and the routes:

- Ownership: another viewer's number gives 404 on every viewer route.
- Platforms: TV, Tizen, Cast and an absent platform are refused on file and post. Same-here is allowed
  from a TV.
- Reopening by a message and by a same-here.
- Same-here's 6-hour window.
- Each limit's 429, and the body-length 400.
- `ADMIN_ONLY` lines and every internal field absent from the viewer JSON.
- `TEAM` on the viewer wire when a seat holds the ticket.
- Unread arithmetic, and the marker never moving back.
- `owes` and overdue across forward, reply and viewer message.
- The flag invisible to the viewer.
- Delete and the retention pass.
- **Availability (FR-320-1), across four configs:** `[ai]` absent, a blank key, `[support] enabled =
  false`, and a key set with `ai.enabled = false`. In the first three, every viewer route answers 404,
  every admin write answers 409, admin reads answer, and `supportEnabled` is false. The fourth is
  fully available, with no Anthropic call made.
- Removing the key and putting it back loses nothing, and each change emits `config_changed`.
- `WireCompatTest` over the new roots.

## Acceptance

- With `[support] enabled = true` and a key set, a phone-platform viewer files a ticket. The 201 carries the thread
  with the `RECEIVED` line and ticket number. An admin sees the row arrive on `/ws` without a reload,
  replies, and the viewer's next `GET` shows the reply with an unread count of 1.
- The same `POST` from a `tv` platform answers 403. Same-here from that TV lands in the thread.
- An admin forwards to a seat. The viewer sees holder `TEAM` and never the seat's name. `owes` is
  `HERVOR` or `ADMIN`, and after `owed_reply_hours` the admin row is `overdue`.
- With a key set and `ai.enabled = false`, every acceptance line above still holds. No admin ever
  needs AI switched on to answer.
- With the key removed, or `enabled = false`, every viewer route answers 404 and `/api/tv/config`
  reports `supportEnabled: false`. A connected Ravilo app drops its entry without a restart, and an
  admin reply answers 409. With the key put back, the open ticket is there unchanged.

## Not in this phase

- **`AI Agent`** — classification, the English summary, the auto-reply and the forward notice are
  phase 321.
- **Hervør posting into a thread.** That needs a credential kind the constitution's *Authentication*
  section does not have: a seat that is neither an admin's browser session nor a viewer's device. It
  goes to Tróndur as its own thread, because it touches the constitution. Until it exists, `HERVOR`
  is a valid holder and author in the schema and on the wire, and no route can post as Hervør.
- **The admin page and the Ravilo screens** — adopted from round 3 once it is pulled. The admin half
  is the Support tab on the AI page, and it goes when the page goes (FR-320-1). No no-key state needs
  drawing.
- **A Requests queue** for accepted feature requests (the Seerr-lane shape the investigation proposed).
  A request is a ticket with `class = REQUEST` until that exists.
- **Push notifications** outside the app. The unread marker is in-app only.
