# Support is a tab on the admin's AI page, with the switch that opens it to viewers

Task: DEB-1500

> Owner, 2026-10-14 (DEB-1076), the ask behind the support lane: a viewer reports a problem, asks a question or
> requests a feature from inside Ravilo, and gets a real answer — usually from an AI, and always truthfully.
>
> Owner, 2026-10-14, round 3's decided row 4: *no own sidebar entry. Support is a tab on the AI page, the AI sidebar
> entry carries the open-ticket count, and Activity gets a logs-only "Support log".* Owner, 2026-10-15 (DEB-1253): the
> AI page's tab order is *"Support / Fetchers / Suggestions"*.
>
> Owner, 2026-10-16 (DEB-799, 12:02Z): he switched on the Support job in Settings ▸ AI and nothing happened.

## Scope

Admin, plus the backend it needs. This is the admin half that phases 320 and 321 left out (*"adopted as their own
phases once the round is pulled"*). The Ravilo half is
[`a-support-ticket-reads-as-a-chat.md`](../ravilo/requirements/a-support-ticket-reads-as-a-chat.md) (DEB-1501), and it
is built after this one.

This spec does five things:

1. adds the **Support** tab to the AI page built by DEB-1253 (`suggestions-as-a-tab-on-the-ai-page.md`);
2. gives the admin the **switch** for `[support] enabled` that FR-320-1 promised (*"the settings toggle arrives with the
   admin phase"*);
3. stops Settings ▸ AI's Support card from looking switched on while the lane is closed, which is what the owner hit;
4. adds `AI Agent`'s **case title**, which round 3 drew on every surface and no shipped spec carries;
5. promotes 321's Danish and Faroese fixed lines into `SupportAi.kt` (FR-321-7).

It also adds the **Support log** view on Activity (round 3, decided row 4).

The design is round 3, `support-lane`, quarantined at `design/incoming/2026-10-15-support-lane/`. The admin frames are
`ravilo/support-lane-png/support-lane-presence-admin-list.png`, `…-admin-detail.png`, `…-abuse-admin.png` and
`…-alt-activity-tab.png` (redrawn as the log). The mockup code is `app/support.js` and `app/support.css`. The parts
this build needs are excerpted below, so this spec stands alone after the quarantine is pruned.

**Not decided here:** switching `[support] enabled` on for prod. Prod serves viewers outside the household. The build
ships with the lane closed, and the first press of the switch on prod is the owner's. It comes to him through Tróndur
with a recommendation once both screens have passed QA (DEB-1499).

## Current behaviour

- 320 and 321 are on prod, backend only (DEB-1340). The admin routes `/api/support/**` answer, but no page calls them.
- Prod's `config.toml` (read 2026-10-16 12:05Z) has `[ai] enabled = true`, `[ai.support] enabled = true` and
  `[support] enabled = false`. The lane is closed, so no viewer can file, and the job has nothing to answer.
- Settings ▸ AI's Support card can be ticked and saved while the lane is closed. Its only clue is the last sentence of
  its description: *"Needs `[support] enabled = true` in config.toml as well."*
- `PUT /api/config` copies `[support]` from the stored config, because the admin's form has no `support` key (320's build
  notes). So no admin can change the lane today, except by editing the file.
- DEB-1493 is building the AI page with Suggestions as its only tab. `GET /api/ai/page` lists only `suggestions`.
- A ticket has no title. Its rows carry `preview`, the first 140 characters of the first message (FR-320-8).
- `SupportAi.kt` carries the English text of `forward_notice` and `request_logged` for `da` and `fo` too.

## Requirements

### The tab and its gate

**FR-1 — Support is the AI page's first tab whenever an AI key is set.** `GET /api/ai/page` lists `support` first,
whenever `[ai] api_key` is non-blank. This holds whether `[support] enabled` is true or false: the switch lives inside
the tab (FR-3), and a closed lane still has tickets an admin can read (FR-320-1). The tab order stays as DEB-1253's FR-2
fixed it: **Support · Fetchers · Suggestions**. So:

| Key | Seerr | `tabs` |
|---|---|---|
| blank or absent | any | 404, no AI page (unchanged) |
| set | not connected | `["support"]` |
| set | connected | `["support","suggestions"]` |

With a key set, the AI page now exists without Seerr. A key with no Seerr used to answer 404 (DEB-1253 acceptance 1), and
that is the one answer this changes. `#/ai` with no `tab` lands on Support, by DEB-1253's FR-5.3 (*the first present
tab*).

**FR-2 — The AI entry carries the open-ticket count.** The sidebar's **AI** entry shows `open` from
`GET /api/support/summary` (FR-320-14) as a count badge. The count is hidden when it is 0, and while the lane is closed.
The shell reads it when it mounts, beside the `GET /api/ai/page` read. After that, the count is the one carried by every
`support_ticket` event on `/ws` (FR-320-10). The admin never adds or subtracts on its own. This is the count DEB-1253
FR-3 left out (*"arrives with the Support tab"*). The command palette's *AI* item carries no count.

### The switch

**FR-3 — The lane's switch is on the Support tab.** The tab's first element is the lane strip:

- **Closed** (`[support] enabled = false`): a notice reading *"Support is closed. Ravilo shows viewers no Support entry,
  and nothing can be filed. Tickets already filed are kept."* and a primary button **Open support to viewers**.
- **Open:** one line, *"Open to viewers"*, and a plain button **Close support**. Pressing it asks first, in a dialog:
  *"Close support? Viewers lose the Support entry in Ravilo. Their tickets are kept, and come back when you open it
  again."* The dialog's buttons are **Close support** and **Cancel**.

Either press calls a new admin route, `POST /api/support/enabled {"enabled": true|false}`. The route writes `[support]
enabled` through `ConfigStore.update`, so the `config_changed` event Ravilo re-fetches on is emitted (320's build notes).
It answers with the new summary (FR-4). The strip renders that answer and nothing it assumed. `PUT /api/config` goes on
restoring `[support]` from the stored config, so a Settings save can never flip the lane.

**FR-4 — The server says whether the lane is open.** `GET /api/support/summary` and every `support_ticket` event gain
two admin-only fields:

- `laneOpen` — the value of `[support] enabled`;
- `hervorEnabled` — the value of `[support] hervor_enabled`, which FR-11 reads.

Both are additive on an admin DTO. The tab and the sidebar count read `laneOpen`. Nothing on the admin side works it out
from the config form.

**FR-5 — Settings ▸ AI's Support card says why it is not running.** `AiJobStatus` gains `inactiveReason: String?`. It is
English and written by the server. It is null when the job would run. For `support`, while `[support] enabled` is false,
it reads *"Support is closed to viewers, so there is nothing to answer. Open it on AI › Support."* The card shows it as
its first status line, above *Spent this month*, and *AI › Support* is a link to `#/ai?tab=support`. The card's switch
still saves. Its description loses the sentence *"Needs `[support] enabled = true` in config.toml as well."* The other
three jobs leave `inactiveReason` null. Whether to fill it for them is not part of this spec.

### The list

**FR-6 — Every viewer's tickets, live.** The tab reads `GET /api/support/tickets` with the filter's parameters, and the
lane strip sits above the list. These are the filters, as a segmented control (round 3 drew *All · Open · With me ·
Resolved · Flagged*):

| Filter | Query | Count shown |
|---|---|---|
| **All** | none | — |
| **Open** | `state=OPEN` | `open` |
| **Needs a reply** | `owes=ADMIN` | `owedByAdmin` |
| **Overdue** | `overdue=true` | `overdue` |
| **Resolved** | `state=RESOLVED` | — |
| **Flagged** | `flagged=true` | — |

The counts come from the summary (FR-320-14), never from counting rows. Round 3's *With me* becomes **Needs a reply**,
because `holder = ADMIN` does not name an admin, and `owes = ADMIN` is the question an admin actually asks. *Overdue* is
new. It is FR-320-13's whole defence, and a filter is the only way to see it at a glance.

These are the columns: **#** (with an unread dot when `unread > 0`) · **Viewer** (`ownerName`) · **Ticket** (FR-14's
`englishTitle`; when it is null, the `preview` in muted text) · **Class** · **State** · **Holder** · **Last** (relative
time, plus an `overdue` badge). Pressing a row opens its detail (FR-7). A `support_ticket` event replaces its row in
place, or adds the row when it matches the filter. Rows stay in the server's order. At ≤ 560 px the table stacks, like
the Suggestions tab's Dismissed table.

The empty states are *"No tickets yet."* and, under a filter, *"No tickets match this filter."*

### One ticket

**FR-7 — The detail opens inside the tab.** Opening a ticket writes `?tab=support&ticket=41` with `replaceState`, and
**← AI · Support** removes `ticket` the same way. This is the shape of DEB-1253's in-tab Dismissed view: Back leaves the
AI page, and a refresh or deep link reopens the ticket. An unknown number shows *"This ticket no longer exists."* and
the back link. The detail reads `GET /api/support/tickets/{n}`. It reads it again on every `support_ticket` event for
that number. On open, and after each read, it posts `read {upTo: <last id>}` to `POST /api/support/tickets/{n}/read`.

**FR-8 — The thread.** The header reads *"#41 · {ownerName} · {platform of the first message} · opened {time}"*, and
below it the title (FR-14's `englishTitle`, else *"Ticket #41"*). Then every message, oldest first, `ADMIN_ONLY` lines
included:

- **A message** shows its author and badge. The viewer's shows `ownerName`. `AI Agent`'s shows an **AI** badge.
  Hervør's shows **AI** and **support**. An admin's shows `authorLabel` and **admin**. The time follows.
- **A body** is set with `textContent`, never as HTML (FR-320-16). A body longer than 14 lines is clamped, and **Show
  the rest** sits *inside* the bubble. Round 3 drew it outside, and that is the defect to avoid.
- **A `SYSTEM` line** is a centred, muted line in English: *Received · #41* · *Forwarded to {holder}* · *State: {state}*
  · *Classified as {class}* · *Reopened by the viewer* · *Flagged as abuse* · *Flag removed*. Each is built from
  `event` and `eventArgs`.
- **A `SAME_HERE`** reads *"Same here · from {platform}"*.
- **An `ADMIN_ONLY` line** (a forward note, AI Agent's reason, a flag) carries a small **admin only** tag. It is never
  confused with what the viewer reads.
- **When `aiComposing` is true**, a last line reads *"AI Agent is writing…"*.

**FR-9 — Replying.** The composer's placeholder is *"Reply as {admin's Jellyfin name} · admin — it sends itself and
lands in the thread"*. **Send** calls `POST /api/support/tickets/{n}/messages`. The composer locks, with a *Sending…*
line, until the answer arrives. The new message appears only from the server's thread, never optimistically. On a 409
`support_unavailable`, the composer reads *"Support is closed — open it above to reply."* While a ticket is
**flagged**, the composer is replaced by *"Replies are off while this ticket is flagged."* This is round 3's drawing.
The backend would still take an admin's reply (FR-320-12). The page asks for the flag to be removed first, so that a
flag means one thing on every surface.

**FR-10 — The rail.** Beside the thread, or under it at ≤ 900 px:

1. **State**, as a badge: *Open*, *In progress*, *Resolved* or *Flagged*.
2. **Class**, a select of *Bug · Request · Question · Account · Other*, or *—* while it is null. Choosing one calls
   `POST …/class`.
3. **Title**: `englishTitle`, captioned *"by AI Agent, from the first message"*, or *"none yet"*.
4. **Holder**, and **Owes** with its age. An overdue ticket shows the age in the overdue colour.
5. **Summary**: the `englishSummary`, when there is one.
6. **Viewer sees**: the English form of the presence line the viewer reads (the Ravilo spec's FR-9 table), so the admin
   can tell what a forward or a flag looks like from the other side. A flagged ticket shows the presence line from
   before the flag, unchanged, because the viewer is told nothing (FR-320-12).
7. **Forward to** (FR-11), **Resolve** (`POST …/state RESOLVED`), or **Reopen** on a resolved ticket (`state OPEN`).
8. **Flag as abuse**, captioned *"Stops every reply, including AI Agent's. The viewer is told nothing."*, or **Remove
   flag** on a flagged ticket. A flagged ticket also shows, above the thread: *"Flagged as abuse. No reply will be sent,
   not even AI Agent's. The viewer sees an open ticket and nothing about the flag."* The banner names no admin, because
   the flag line does not record one.
9. **AI transcript for #41 →**, shown only when there is at least one turn. It opens a panel listing every
   `GET …/transcript` turn: time, model, verdict, tokens and cost. Each turn's sent block and answer sit in a collapsed
   `<pre>`, set with `textContent`.

Every write disables its control until the answer comes back. The rail renders from the thread that comes back, never
from what it sent.

**FR-11 — Forward only to someone who can answer.** **Forward to** lists **Admins** and, only while `hervorEnabled`
(FR-4), **Hervør · support**. A destination equal to the current holder is disabled. **Forward** calls `POST …/forward`
with `owes` set to the destination, plus an optional English note from a one-line field (*"Note for whoever answers
(English, admins only)"*). Round 3's **Owner** row (a seat that cannot answer, with Hervør owing the reply) is **not
built**. It needs Hervør, and Hervør cannot post until the credential 320 sent to Tróndur exists. With the household's
config today, the list is *Admins* alone, which is how an admin takes a ticket from `AI Agent`.

### The log

**FR-12 — Activity › Support log.** Activity gains a third view, **Support log**, at `#/activity?view=support`. It is
present only while the AI page lists `support`. It is history only: what every author did to every ticket, newest
first. Nothing on it is actionable apart from a link to the ticket.

New admin route: `GET /api/support/log?before=<id>&limit=<n ≤ 200>` → `{ entries: [{ id, at, ticket, actor,
actorLabel, action, args }] }`. It is built from:

- every `SYSTEM` line, of both visibilities. The actor is whoever's request wrote it: `AI_AGENT` for 321's writes,
  `ADMIN` with the admin's name for the admin routes, `VIEWER` for a reopen. Where the store cannot tell, the line is
  `SYSTEM`;
- every `MESSAGE` by `AI_AGENT`, `HERVOR` or `ADMIN`, as `replied`;
- every `support_ai_turn` verdict, as `ai_verdict` with `SENT`, `FORWARDED: <rule>` or `FLAGGED`.

**No body, note, summary or title is ever in an entry.** The log names tickets and events, the rule FR-320-16 sets for
logs. Each row reads *time · actor · action · #n*, and **#n** links to `#/ai?tab=support&ticket=n`. The view reads the
route on open, again on every `support_ticket` event, and once more on **Older** at the foot.

The build may need to record the actor when a line is written: `SYSTEM` lines carry no author today (FR-320-2). If so,
it adds a nullable `actor` and `actor_label` to `support_message` in the next migration. Lines written before that read
as `SYSTEM`.

### The case title

**FR-13 — `AI Agent` titles the case, once.** Owner, 2026-10-14 (round 3's title rule): *"The viewer's first message is
never lifted into the title — it stays the first message in the thread. AI Agent writes a short case title from it."*
And from 320's settled list: *all tickets and communication should always be in English on our side.* Together, those
mean two titles from the same pass:

- 321's output schema (FR-321-4) gains `title` (the viewer's language) and `englishTitle` (English). Each is a string of
  at most 60 characters, required, with `additionalProperties: false` kept. The system prompt asks for *a few words that
  name the problem or the question, the way a helpdesk would file it. No promise, no claim about the server or the
  library.*
- `support_ticket` gains nullable `title` and `english_title`, in the next migration (re-check the number; `80.sqm` is
  the last at the time of writing). Keep the `.sqm` byte-identical to the `.sq`.
- They are written **only while they are still null**, from the first turn that reaches `end_turn` and is not `ABUSE`.
  Once set, `AI Agent` never changes them, so a title never shifts under the viewer.
- **`englishTitle` is stored** when it is one line, at most 60 code points, and passes FR-321-5 rule 4 (no internal
  strings).
- **`title` is stored** under the same tests, and only when `languageConfidence` is `HIGH` (from FR-321-5 rule 2) and
  `replyLanguage` matches the viewer (FR-321-5 rule 5). The viewer's title is model prose that reaches the viewer even when the reply forwards, so it passes every
  check a sent reply's language must. When it fails, `title` stays null and the next turn may fill it. The failure goes
  into the transcript's verdict as `TITLE_DROPPED: <rule>` beside the main verdict.
- **On the wire:** `SupportTicketRow` (viewer) gains `title: String?`, omitted while null. `SupportAdminTicket` gains
  `title` and `englishTitle`. Both are additive. An installed app ignores them, and a client reading an older server
  sees them absent (FR-320-18).

A ticket filed while the job is not active has no title until a turn writes one. The Ravilo screen shows *Ticket #41*
in its place. No admin route sets a title.

**FR-14 — Where the titles show.** On the admin side: FR-6's Ticket column, FR-8's header and FR-10's rail, all from
`englishTitle`. The viewer's `title` belongs to the Ravilo spec.

### 321's strings

**FR-15 — The Danish and Faroese fixed lines.** `SupportAi.kt`'s table of FR-321-7 lines takes these, character for
character:

| Key | da | fo |
|---|---|---|
| `forward_notice` | Jeg sender det videre, og de svarer dig snart. | Eg sendi hetta víðari, og tey svara tær skjótt. |
| `request_logged` | Tak — det er registreret som ønske #{n}. At tilføje det kræver en beslutning fra dem, der driver denne server, så det sker ikke af sig selv. | Takk — hetta er skrásett sum ynski #{n}. At leggja tað afturat krevur eina avgerð frá teimum, ið reka hendan ambætaran, so tað hendir ikki av sær sjálvum. |

`forward_notice` is round 3's `sup_ai_forward_msg` (`ravilo/support-lane-report.md`). `request_logged` is FR-321-7's
amendment of 2026-10-16. That clears the condition 321 set for itself, *"not `✓ Done` while either Danish or Faroese
line is still English"*.

### Losing the server

**FR-16 — The page's offline strip covers this tab.** DEB-1253's FR-7 strip (`.ai-offl`) is the page's. While it shows,
the Support tab's writes are disabled: the switch, Send, Forward, Resolve, Reopen, the flag and Class. The strip's
sentence names the Suggestions buttons, so it becomes *"Nothing is sent until it's back."* on every tab.

### Styling

**FR-17 — The CSS comes from round 3, promoted by Rannvá before the build.** The build adds no styling beyond what is
promoted. Round 3's `app/support.css` becomes `design/app/support.css`, scoped to the Support tab and the Support log
view, with its `check-css-scoping.sh` row and a `syncDesignAssets` include. The promotion fixes the three defects the
manifest found in the frames:

- the holder slot in the thread header overlaps the title;
- **Show the rest** sits outside a clamped bubble;
- the log's rows squeeze their action column.

It adds the pieces this spec adds and the round did not draw: the lane strip, the *admin only* tag, the overdue badge,
the transcript panel and the count badge on a sidebar entry. Round 5 already promoted `.tk-tabs`, and the round's
`.tk-*` ticket classes must not collide with it. Rannvá's promotion task blocks this one.

## Strings

Admin copy is English. The new strings are the ones quoted in FR-3, FR-5, FR-6, FR-7, FR-8, FR-9, FR-10, FR-11 and FR-16.
These round 3 strings are used as drawn: *Forward to · Forward · Resolve · Flag as abuse · Remove flag · Stops every
reply, including AI Agent's. The viewer is told nothing. · Replies are off while this ticket is flagged. · AI transcript
for #{n} →*.

## Stand-in data

Round 3's, and nothing else: viewers *Jákup Hansen* (`jakup`), *Ása Dam* (`asa`) and *Sunnvør Holm* (`sunnva`); the
admin *Hallur*; tickets **#41** (a bug, *"Sound drops in Nordvest S02E04 on the sitting-room TV"*), **#38** (a request,
*"Remember audio language per series"*) and **#36** (a question, resolved, *"Keeping subtitles on by default"*).
`Nordvest` is the design corpus's own series. A test or a screenshot never uses a real viewer or a real title.

## Acceptance

1. **The owner's case.** With a key set and `[support] enabled = false`: the AI page opens on **Support**. The tab
   shows the closed notice and **Open support to viewers**. Settings ▸ AI's Support card reads *"Support is closed to
   viewers…"* above its spend, with a link to the tab.
2. **Open.** Pressing **Open support to viewers** makes `/api/tv/config` carry `"support_enabled":true`, and a
   connected Ravilo socket receives `config_changed`. **Close support**, confirmed, takes both back. A Settings save in
   between changes neither.
3. **Gate.** `GET /api/ai/page` gives `["support"]` with a key and no Seerr, `["support","suggestions"]` with both, and
   404 with no key. The Suggestions tab's own acceptance holds otherwise.
4. **Live.** A viewer files from a phone-platform client on the test stack. The row appears on the open list without a
   reload. The sidebar count goes from 0 to 1. The ticket opens. An admin's reply lands in the thread from the server's
   answer, and the viewer's next `GET` shows it unread.
5. **Forward and flag.** *Forward to → Admins* on an `AI Agent`-held ticket moves it to `ADMIN`, and *Viewer sees*
   changes. Flagging hides the composer and shows the banner. The viewer's JSON for the ticket is byte-identical before and after
   the flag (a flag does not touch `updated_at`, per 320's build notes).
6. **Title.** A mocked turn with a valid `title` and `englishTitle` stores both. A second turn changes neither. A `title`
   containing `FR-12`, or one with `languageConfidence = LOW`, stays null, while `englishTitle` is still stored when it
   passes. `ABUSE` stores neither.
7. **Log.** The Support log lists the filing, the AI verdict, the forward, the reply and the flag of one ticket. Its JSON
   contains none of that ticket's body, note, summary or titles.
8. **Strings.** A Danish viewer's forwarded ticket carries the Danish `forward_notice`, and a Faroese request carries the
   Faroese `request_logged` with its number.
9. **Offline.** With the backend stopped, the strip shows and every Support write is disabled. With it back, they return.

**Tests.** Backend route tests in `linuxX64Test`: FR-1's table, FR-3's route and its `config_changed`, FR-4's fields,
FR-5's `inactiveReason`, FR-12's route and its no-body rule, FR-13's six storage rules, and FR-15's lines. The Playwright
spec is `tests/e2e/ai-page-support-tab.spec.ts`. It stubs `/api/ai/page`, `/api/support/**` and the `/ws` event at a
phone and a desktop viewport, with the stand-in data above, for acceptance 1, 4, 5, 7 and 9.

## Non-goals

- **Switching the lane on for prod.** That is the owner's call (Scope).
- **Hervør posting, and the Owner forward row.** They wait for the credential that 320 sent to Tróndur.
- **Deleting a ticket** from the admin. The route exists (FR-320-12), but round 3 did not draw it.
- **An admin editing a title.**
- **`inactiveReason` for the other three AI jobs.**
- **Round 2's Fetchers tab and its key-status badge.**
- **Any Ravilo surface.** That is DEB-1501.

## Amendment 2026-10-16 — what the build settled

Written before the Kotlin it describes, from reading the code FR-4, FR-8, FR-12 and FR-13 land in. Nothing above changes
meaning; each line says what a sentence above left open.

- **FR-4 / FR-8 — where the fields live.** `laneOpen` and `hervorEnabled` are fields of `SupportSummary`, which is the
  summary both `GET /api/support/summary` and every `support_ticket` event already carry, so one change gives both. FR-8's
  *AI Agent is writing…* needs `aiComposing`, which the admin thread did not have: `SupportAdminThread` gains it, computed
  as the viewer's thread computes it. Both are additive on admin DTOs.
- **FR-12 — what an entry is.** `action` is a code, not prose: `received` · `forwarded` · `state_changed` · `classified` ·
  `reopened` · `flagged` · `flag_removed` · `replied` · `ai_verdict`. `args` holds the facts that go with it (a holder, a
  state, a class, a verdict) and never text a person wrote. The page words each from the code; the server sends no sentence.
  - `replied` is a message **a viewer can read**, by `AI_AGENT`, `HERVOR` or `ADMIN`. An `ADMIN_ONLY` note rides a forward
    and a flag, which are entries of their own; logging it as a reply would say a viewer was answered when none was.
  - An entry's `id` is `messageId × 2` for a line and `turnId × 2 + 1` for a verdict, so the two sources never collide and
    `before` is one number. Order is time, then id.
  - A verdict is cut at any quotation mark before it is sent: FR-321-5 rule 4's reason quotes the internal string that
    failed it, and a log may name a rule but not the text.
  - `received` and `reopened` are `VIEWER`, labelled with the owner's name read at the time of the request. `SYSTEM` is a
    line with no recorded actor: everything written before the migration, and the sweep that forwards what `AI Agent` held.
- **FR-13 — the transcript's verdict.** A dropped title rides beside the main verdict after a ` | `:
  `SENT | TITLE_DROPPED: title rule 4 — contains an internal string`. It names the field and the rule and quotes nothing of
  the title. The count of replies sent (FR-321-8) reads `SENT` as a prefix, so a dropped title does not cost a turn.
- **FR-13 — an answer without titles is still read.** The schema *requires* both titles, but a batch built before this shipped
  comes back without them. Its reply is judged and sent as before, and the ticket simply has no title yet. `ABUSE` reports no
  dropped title because it stores none.
- **FR-6 — *Open*.** The filter asks `state=OPEN` and the count is `open`, which counts every state but `RESOLVED`. They are
  the same set until something sets `IN_PROGRESS`, which no admin page does. If one ever does, the filter wants a state
  list, not this table.
