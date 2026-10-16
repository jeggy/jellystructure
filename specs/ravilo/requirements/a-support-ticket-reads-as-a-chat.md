# A support ticket in Ravilo reads as a chat — on the phone, the web, the Mac and Linux

Task: DEB-1501

> Owner, 2026-10-14 (DEB-1076), the ask behind the support lane: a viewer reports a problem, asks a question or
> requests a feature from inside Ravilo, and gets a real answer — usually from an AI, and always truthfully.
>
> Owner, 2026-10-14 (round 3's decided rows): direction **A, "Presence"** — *the ticket header is a chat header*,
> pinned; the class is *inferred, shown after the receipt*; the unread marker sits *on the existing Profile entry*, with
> no Support item on the bottom bar.

## Scope

Ravilo, in `:ravilo-ui`'s common code, reached from **Profile**. One screen set covers the four platforms that may file
a ticket (FR-320-5): the **phone** (Android handset), the **web**, the **Mac** and **Linux**. All four already draw the
same Profile page or profile menu. **The TV, Tizen and Cast draw nothing in this spec**, even when `support_enabled`
is true. The TV's read-only thread with *Same here* is drawn in round 3 (`…-presence-10-foot-focus.png`,
`…-tv-same-here.png`). It follows as its own task once this screen has passed QA. A TV can never file, so until then it
has nothing of its own to show.

This spec builds on the backend of 320 and 321 (on prod since DEB-1340). It also builds on the admin half,
[`support-is-a-tab-on-the-ai-page.md`](../../requirements/support-is-a-tab-on-the-ai-page.md) (DEB-1500), which comes
first. That spec adds the ticket's `title` to the wire, and gives an admin the page to answer from and the switch to
open the lane. No new route is added here.

The design is round 3, `support-lane`, quarantined at `design/incoming/2026-10-15-support-lane/`. The phone frames are
under `ravilo/support-lane-png/`. They are compared against `support-lane-presence-*`, and **not** the `alt-*` frames,
which the owner did not pick. The mockup code is `ravilo/mobile/ravilo-support.js` and `.css`. What the build needs is
excerpted below. The round drew the phone only. Its report says the web and desktop layouts were skipped because *"the
vocabulary is surface-independent"*. So the web, the Mac and Linux get the same screens in the app's existing content
column.

## The owner's rules this screen keeps (2026-10-14/15, from 320 and 321)

- *A ticket is filed on a phone, a desktop or the web — never on the TV.*
- *Every message names its author*, from four kinds: the viewer, `AI Agent`, `Hervør` and an admin. `AI Agent` is
  always the literal **AI Agent**, and that label is the only disclosure that an AI reads the ticket (FR-321-11). The
  composer carries no line about AI.
- *A reply either sends itself or forwards. There is no "waiting for approval" state the viewer can see.*
- *The viewer sees the ticket's real state and who holds it, in user-facing words only.*
- *Tickets are private* (FR-320-3).
- The constitution: *server-pushed state only — no derived or optimistic local state.* A message the viewer sent appears
  when the server's answer carries it, and not before.

## Requirements

**FR-1 — The entry follows `support_enabled`, live.** While `RaviloConfig.supportEnabled` is true (`support_enabled` on
the wire, absent = false), and the platform is not a TV, Tizen or Cast:

- the phone's and the computer's **Profile** page gains a **Support** row (`support.title`), after *Settings*;
- the web's profile menu, on a window wide enough to use it rather than the Profile page, gains a **Support** item
  after *Settings*.

The row and the item open **My tickets** (FR-3). When `config_changed` turns the flag off, the entry goes. A Support
screen that is open pops back to Profile, because every route now answers 404 (FR-320-1). An app that reads no flag
draws nothing, which is how every installed app behaves today.

**FR-2 — The unread marker.** `GET /api/tv/support/unread` gives `{tickets}`. While it is above 0, a dot shows on the
**Support** row or item, and on the control that opens Profile: the bottom bar's Profile item on a phone, the profile
entry in the computer's navigation, and the avatar button on the web. That is round 3's `presence-unread-nav`. The app
reads the count when the flag turns on, on every `support_changed`, and after the events socket reconnects. It never
counts on its own.

**FR-3 — My tickets.** `GET /api/tv/support/tickets`, in the server's order. Each row shows:

- the holder's mark;
- `#41`, then the class chip (`support.class_*`, or none while the class is null), then the time of the last activity;
- the **title**: the ticket's `title` (DEB-1500, FR-13), or `support.ticket_n` (*Ticket #41*) while there is none. The
  first message is never shown as the title. That is the owner's title rule;
- the presence line (FR-9);
- an unread dot when `unread > 0`.

The states:

- **Loading:** three skeleton rows, as in `presence-loading`.
- **Empty:** the chat mark, `support.empty_title`, `support.empty_body`, and a primary **Write to support**
  (`support.write`).
- **With tickets:** a quieter **Write to support** at the foot of the list.

**FR-4 — The thread.**

- **The header is pinned.** It holds back, then `#41` and the class chip, then the title as in FR-3, then the presence
  line (FR-9), and the holder's mark at the end.
- **The bubbles.** The viewer's own are on the right, headed `support.you` (*{name} · you*). The others are on the left,
  each headed with its author: **AI Agent** with an `AI` badge (`support.badge_ai`); **Hervør** with `AI` and `support`
  badges; an admin's `authorLabel` with the existing `profile.admin` badge. Each carries its time: `HH:mm` today, the
  weekday within a week, otherwise the date.
- **Bodies are plain text.** A body longer than 14 lines is clamped, with **Show the rest** (`support.show_rest`)
  *inside* the bubble.
- **System lines** are centred and muted, one line each, written in the viewer's language from FR-10's table.
- **While `aiComposing` is true** (FR-321-3), the last entry is an `AI Agent` bubble with `support.ai_writing` and three
  dots.
- The thread opens scrolled to its end, and every re-read keeps it there when it was already at the end.

On open, and after every read while the thread is open, the app posts `POST …/read {upTo: <last id>}` (FR-320-9).

**FR-5 — The composer.** It owns the screen's bottom edge, and the thread screen hides the bottom bar (round 3). Its
placeholder is `support.placeholder`. On a `RESOLVED` ticket it is `support.placeholder_reopen` (*Write here again to
reopen*), because writing there reopens the ticket (FR-320-6). Send is disabled while the field is blank.

**Sending** (`presence-sending`) locks the field and shows `support.sending` above it, with a thin progress line, until
the server answers. Then:

| Answer | What the viewer sees |
|---|---|
| 200 or 201, with the thread | The thread from the answer. The draft clears. |
| No response | `support.not_sent_offline`, with **Try again** (`support.try_again`). The draft stays. |
| 429 | `support.limit`. The draft stays. |
| 400 | `support.too_long`. The draft stays. |
| 404 | Back to My tickets, then FR-1 if the flag went. |

No bubble is drawn before the answer. Failing to send is a line above the composer, never a bubble marked "failed".

**FR-6 — Writing the first message.** **Write to support** opens the thread screen with no ticket. Its header reads
`support.new_ticket` (*New ticket*) and `support.pres_waiting`. Its body holds:

- `support.write` as a heading;
- `support.first_hint` (*Tell us what happened and where — which TV or phone helps.*);
- on the phone only, `support.sent_from_phone` (*Sent from Ravilo on the phone · {name}*), in mono. The server records
  the device, and the line says so. The web and the computers show no such line, because the round's string names the
  phone.

Nothing is asked before writing. There is no class picker: the round's `alt-chooser` was not picked. Sending calls
`POST /api/tv/support/tickets`, and the 201's thread replaces the screen with that ticket's thread, receipt included.

**FR-7 — Live.** The app's events socket asks for `support` in its `features=` list (`EVENTS_FEATURE_SUPPORT`, FR-320-10)
beside what it already asks for. On `{"type":"support_changed","ticket":n}` it reads the unread count again. It also
reads again the list, if it is open, and that ticket's thread, if it is open. A reconnect reads all three. Nothing is
patched in place from the event, because the event carries no content.

**FR-8 — Offline.** When a read fails with no response while a list or thread is shown, a strip shows
`support.offline_bar` (*Offline — showing what was last loaded*). The content stays. The strip goes with the next read
that succeeds. The composer's offline line is FR-5's.

**FR-9 — The presence line.** It is the header's second line and every list row's last line. It is chosen from the
viewer's wire alone, top row first:

| Ticket | Line | Mark |
|---|---|---|
| `RESOLVED` | `support.state_resolved` (*Resolved*) | a check |
| no message yet from anyone but the viewer, and `aiComposing` false | `support.pres_waiting` (*Open · waiting for a first reply*) | empty |
| holder `AI_AGENT` | `support.pres_with` with *AI Agent* | AI Agent's |
| holder `HERVOR` | `support.pres_with` with *Hervør* | Hervør's |
| holder `ADMIN` or `TEAM` | `support.pres_with` with `support.badge_support` (*Open · with support*) | the support mark |

Round 3's *with the owner · Hervør will answer here* and *Resolved · by {name}* need a name the wire does not carry, and
Hervør cannot post yet, so neither is built. `IN_PROGRESS` reads as open. DEB-1500's rail shows the English form of the
same line under *Viewer sees*.

**FR-10 — System lines, by event** (FR-320-15). The client renders each from its own strings:

| `event` / kind | Line |
|---|---|
| `RECEIVED` | `support.sys_received` (*Received · #{n} · logged as a {class} · titled "{title}"*) once the ticket has both a class and a title. Until then, `support.sys_received_bare` (*Received · #{n}*). This is the receipt that round 3 decided carries the class *after* it is inferred. It reads the ticket's current class and title. |
| `CLASSIFIED` | Not drawn. The class shows in the receipt and the header's chip. |
| `FORWARDED` | `support.sys_handling` (*{name} is handling this*), with the holder's name as in FR-9. |
| `STATE_CHANGED` to `RESOLVED` | `support.state_resolved`. |
| `STATE_CHANGED` to anything else | `support.state_open`. |
| `REOPENED` | `support.sys_reopened` (*Reopened — you wrote again*), with the amber accent. |
| `SAME_HERE` (a kind, not an event) | `support.tv_said_same_here` (*You said same here*). |

An `event` the app does not know is not drawn. A newer server may add one, and an old app must not show a code.

**FR-11 — Strings.** These come from round 3's string set (`ravilo/support-lane-report.md`, *New UI strings*). Each goes
into `i18n/en.json`, `da.json` and `fo.json` under the key shown, character for character. The shipped files win where a
key already exists.

| Key | en | da | fo |
|---|---|---|---|
| `support.title` | Support | Support | Support |
| `support.my_tickets` | My tickets | Mine sager | Mínar sakir |
| `support.write` | Write to support | Skriv til support | Skriva til supportina |
| `support.placeholder` | Write to support… | Skriv til support… | Skriva til supportina… |
| `support.placeholder_reopen` | Write here again to reopen | Skriv her igen for at genåbne | Skriva her aftur fyri at lata upp aftur |
| `support.first_hint` | Tell us what happened and where — which TV or phone helps. | Fortæl hvad der skete og hvor — hvilket tv eller hvilken telefon hjælper. | Sig frá, hvat hendi og hvar — hvat sjónvarp ella fartelefon hjálpir. |
| `support.sent_from_phone` | Sent from Ravilo on the phone · {name} | Sendt fra Ravilo på telefonen · {name} | Sent frá Ravilo á fartelefonini · {name} |
| `support.you` | {name} · you | {name} · du | {name} · tú |
| `support.badge_support` | support | support | support |
| `support.badge_ai` | AI | AI | AI |
| `support.ai_writing` | AI Agent is writing | AI Agent skriver | AI Agent skrivar |
| `support.state_open` | Open | Åben | Opin |
| `support.state_resolved` | Resolved | Løst | Loyst |
| `support.class_bug` | Bug | Fejl | Feilur |
| `support.class_request` | Request | Ønske | Ynski |
| `support.class_question` | Question | Spørgsmål | Spurningur |
| `support.pres_waiting` | Open · waiting for a first reply | Åben · venter på første svar | Opin · bíðar eftir fyrsta svari |
| `support.pres_with` | Open · with {name} | Åben · hos {name} | Opin · hjá {name} |
| `support.sys_received` | Received · #{n} · logged as a {class} · titled “{title}” | Modtaget · #{n} · registreret som {class} · med titlen “{title}” | Móttikið · #{n} · skrásett sum {class} · við heitinum “{title}” |
| `support.sys_handling` | {name} is handling this | {name} tager sig af det | {name} tekur sær av hesum |
| `support.sys_reopened` | Reopened — you wrote again | Genåbnet — du skrev igen | Latið upp aftur — tú skrivaði aftur |
| `support.sending` | Sending… | Sender… | Sendir… |
| `support.not_sent_offline` | Not sent — you're offline. Your message is kept here. | Ikke sendt — du er offline. Din besked er gemt her. | Ikki sent — tú ert uttan samband. Boðini eru goymd her. |
| `support.try_again` | Try again | Prøv igen | Royn aftur |
| `support.offline_bar` | Offline — showing what was last loaded | Offline — viser det senest hentede | Uttan samband — vísir tað seinast heinta |
| `support.show_rest` | Show the rest | Vis resten | Vís restina |
| `support.empty_title` | No tickets yet | Ingen sager endnu | Ongar sakir enn |
| `support.empty_body` | Something wrong, or something you wish Ravilo did? Write to support — a reply usually comes within minutes. | Er noget galt, eller ønsker du dig noget af Ravilo? Skriv til support — svaret kommer som regel inden for få minutter. | Er okkurt galið, ella ynskir tú tær okkurt av Ravilo? Skriva til supportina — svar kemur vanliga innan fáar minuttir. |
| `support.tv_said_same_here` | You said same here | Du sagde det samme | Tú segði sama her |

The round has no `Account` or `Other` class string. Those two classes draw no chip until a string round carries them.

**These five strings are new**, and English only for now:

| Key | en |
|---|---|
| `support.ticket_n` | Ticket #{n} |
| `support.new_ticket` | New ticket |
| `support.sys_received_bare` | Received · #{n} |
| `support.limit` | You've written a lot today. Try again later. |
| `support.too_long` | That's too long to send in one message. |

Their Danish and Faroese come from a string round through Rannvá, the way 321's `request_logged` did (DEB-1438). Until
then the build carries the English line in `da` and `fo`. **This work is not done while any of them is still English**,
and its QA plan checks all of them in both languages. The same round is asked to check one composition in Faroese:
*support* in `support.pres_with` and `support.sys_handling`, where `support.write` uses the definite *supportina*.

**FR-12 — Platforms and the wire.** The phone sends `X-Ravilo-Platform: phone`, the web `web`, the Mac `mac` and Linux
`linux`. These are the values FR-320-5 accepts, and the client sends what it already sends. Every DTO is `:shared`'s
`Support.kt` as shipped. The only addition read here is DEB-1500's `title`, and an absent `title` means *Ticket #{n}*.
`aiComposing` is read as shipped. No field is renamed or removed.

**FR-13 — Look.** These are Compose screens in the app's existing theme: dark, and the light skin, as in
`presence-light`. Round 3's CSS is a drawing, not code, here. The marks follow the round's vocabulary
(`presence-vocabulary`):

- **AI Agent:** a round mark with a dot.
- **Hervør:** a square mark with her initial.
- **An admin, and support:** a round mark with an initial.
- **A resolved ticket:** a check.

Round 3's motion layer is not part of this spec. A new bubble may fade in, and nothing else is required to move.

## Stand-in data

Round 3's, and nothing else: the viewer *Eyð*; tickets **#41** (a bug, *"Sound drops in Nordvest S02E04 on the
sitting-room TV"*), **#38** (a request, *"Remember audio language per series"*) and **#36** (a question, resolved,
*"Keeping subtitles on by default"*); *Hervør* and the admin *Hallur*. A test never uses a real viewer or a real title.

## Acceptance

1. **Entry.** With the lane open (DEB-1500, FR-3), Profile on a phone, the web, a Mac and Linux shows **Support**.
   Closing the lane removes it without a restart, and an open Support screen returns to Profile. An Android TV shows
   nothing in either state.
2. **First ticket.** On a phone, *Write to support* → type → send. The composer locks with *Sending…*. No bubble shows
   until the server answers. Then the thread shows the message and *Received · #n*. With the AI job active, *AI Agent is
   writing* appears, and later the reply or the forward notice. The receipt then reads *logged as a … · titled "…"*, and
   the header shows the title.
3. **Unread.** An admin's reply puts a dot on the Profile entry and on the Support row. Opening the thread clears both.
4. **Resolved and reopened.** A resolved ticket reads *Resolved*, and its composer reads *Write here again to reopen*.
   Writing reopens it, with *Reopened — you wrote again*.
5. **Offline.** With the network cut, a send shows *Not sent — you're offline* and keeps the draft. *Try again* sends it
   once the network is back. A list shown before the cut keeps its rows under the offline strip.
6. **Limits.** A 429 and a 400 each show their line and keep the draft.
7. **Danish and Faroese.** A viewer whose app language is Danish, and one whose is Faroese, see every string in FR-11
   in that language. The five new ones are in that language too, once the string round has landed.
8. **Long text.** A 2 000-character message clamps at 14 lines, with *Show the rest* inside its bubble.
9. **Privacy.** Nothing on any screen shows another viewer's ticket, a seat name, a phase or task id, or an English
   summary.

**Tests.** Common unit tests cover FR-9's table, FR-10's table (an unknown event is not drawn), the title fallback, and
the clamp. A `ravilo-web` Playwright spec stubs `/api/tv/config` and `/api/tv/support/**`, for acceptance 1, 2, 4, 6 and
8 at a phone and a desktop viewport. On-device proof (phone, Mac, web) goes on the QA queue as a written test plan.

## Non-goals

- **The TV's read-only thread and *Same here*.** That is its own task, after this one.
- **Tizen and the Cast receiver.** They never show support.
- **Deleting a ticket from the app.** The route exists (FR-320-17), but round 3 did not draw it.
- **Push notifications outside the app** (320).
- **Attaching a screenshot or a log.**
- **The round's motion layer.**

## Amendment 2026-10-16 — as built (DEB-1501)

The screens are in `:ravilo-ui` common code (`ui/support/`), and the places the build read the spec rather than copied it are
these. None changes what a viewer is promised above, and each is something QA can check.

- **A list row cannot choose *waiting for a first reply* (FR-9).** The ticket list carries no messages, and the table's second
  row is chosen from them. A row therefore skips it and reads its holder (*Open · with AI Agent*). The thread's header applies
  the whole table, so a ticket nobody has answered reads *waiting* there and *with AI Agent* in the list until the first reply.
- **A send that is refused with any status other than 400, 404 or 429** (a 5xx, a 403) shows FR-5's *Not sent* line with
  **Try again**, and keeps the draft. The round has no string for it, and the viewer's choices are the same.
- **The receipt's `{class}` is the class string in lower case** (*logged as a bug*), in every language. A ticket whose class
  is `ACCOUNT` or `OTHER` has no string, so its receipt is the bare *Received · #n*.
- **`FORWARDED` and the `AI Agent`/`Hervør` names** come from the event's argument (the viewer-wire holder), so a later holder
  the app does not know reads as *support*, as FR-9's last row does.
- **The week rule (FR-3, FR-4):** `HH:mm` for today, the abbreviated weekday for the six days before it, then `12 Oct`, with the
  year added for another year. These use the existing `wd.abbr.*`, `month.*` and `date.*` keys.
- **The `title` fallback** is also used for the pinned header, so a new ticket reads *Ticket #n* until `AI Agent` titles it.
- **Platform line (FR-6):** *Sent from Ravilo on the phone · {name}* is shown when the app's platform is `phone`; `{name}` is the
  device name the app already sends at sign-in.
- **Tests.** The screens' states (acceptance 1's entry rows excepted) are Compose tests in `:ravilo-ui:testDebugUnitTest`
  (`SupportScreensTest`), and the tables are common unit tests. The `ravilo-web` Playwright spec is **wire-level**: Ravilo web
  is a canvas with no DOM to click, and this repository's CI deliberately never drives its rendering
  (`ravilo-web.spec.ts`), so the spec boots the real app with a stored session and a stubbed server, and asserts what only a
  browser shows — the unread read when the flag is on and never when it is off, `support` in `features=`, a re-read on
  `support_changed` and after a reconnect, at a phone and a desktop viewport. Acceptance 2, 4, 5, 6 and 8 end to end on a device
  are the QA ticket's. A soft-wrapped 2 000-character body (acceptance 8) needs real glyph widths, which Robolectric lacks, so
  that case is on the QA plan; the newline case is a Compose test.
- **Acceptance 7 stays open** for the five new strings until DEB-1504 lands their Danish and Faroese. They carry English in `da`
  and `fo` through the key fallback, and `SupportModelTest` prints which are still English.
