# Support lane — report (round `support-lane`, no phase number)

Package: the directions canvas, the renderer it draws from, 34 PNGs. Nothing else. The canvas links the existing shared furniture `ravilo/mobile-directions.css` (not new) and the self-hosted fonts in `app/fonts/` (not new); page styles are inline. No CDN, no links out.

## Files

- `ravilo/Support Lane - Directions.html` — 64971 bytes · 505 lines
  - first line: `<!doctype html>`
  - last line: `</html>`
- `ravilo/support-lane.js` — 30369 bytes · 173 lines
  - first line: `/* Support lane — screen renderers for "Support Lane - Directions.html". Every [data-scr] mount in the`
  - last line: `})();`
- `ravilo/support-lane-png/` — 34 PNGs, 2× device pixels, element captures of the canvas frames:
- `support-lane-ledger-thread.png` — 373708 bytes
- `support-lane-presence-10-foot-focus.png` — 353651 bytes
- `support-lane-presence-abuse-admin.png` — 306266 bytes
- `support-lane-presence-abuse-viewer.png` — 173734 bytes
- `support-lane-presence-admin-detail.png` — 407689 bytes
- `support-lane-presence-admin-list.png` — 209637 bytes
- `support-lane-presence-ai-answered.png` — 298705 bytes
- `support-lane-presence-ai-composing.png` — 255390 bytes
- `support-lane-presence-alt-activity-tab.png` — 98537 bytes
- `support-lane-presence-alt-chooser.png` — 107624 bytes
- `support-lane-presence-alt-navitem.png` — 35470 bytes
- `support-lane-presence-alt-pill.png` — 373708 bytes
- `support-lane-presence-danish.png` — 274724 bytes
- `support-lane-presence-empty.png` — 121128 bytes
- `support-lane-presence-error-send-failed.png` — 225818 bytes
- `support-lane-presence-faroese.png` — 288267 bytes
- `support-lane-presence-first-composer.png` — 129486 bytes
- `support-lane-presence-forward.png` — 433283 bytes
- `support-lane-presence-held-by-owner.png` — 285301 bytes
- `support-lane-presence-light.png` — 287882 bytes
- `support-lane-presence-loading.png` — 85413 bytes
- `support-lane-presence-longest-string.png` — 653925 bytes
- `support-lane-presence-my-tickets.png` — 161014 bytes
- `support-lane-presence-offline.png` — 297161 bytes
- `support-lane-presence-reopened.png` — 289608 bytes
- `support-lane-presence-resolved.png` — 246358 bytes
- `support-lane-presence-sending.png` — 135922 bytes
- `support-lane-presence-system-line.png` — 286733 bytes
- `support-lane-presence-thread.png` — 402532 bytes
- `support-lane-presence-tv-same-here.png` — 357850 bytes
- `support-lane-presence-unread-nav.png` — 250313 bytes
- `support-lane-presence-vocabulary.png` — 544245 bytes
- `support-lane-presence-waiting-first-reply.png` — 246371 bytes
- `support-lane-statuscard-thread.png` — 393149 bytes

## Integration (2026-10-14, after the owner's picks)

New files
- `ravilo/mobile/ravilo-support.js` — 15843 bytes · 180 lines
  - first line: `/* Ravilo phone — Support (round support-lane, decided 2026-10-14). Direction A "Presence": the ticket header is a`
  - last line: `})();`
- `ravilo/mobile/ravilo-support.css` — 13477 bytes · 130 lines
  - first line: `/* Ravilo phone — Support (support-lane, direction A "Presence"). Lives in the pushed .acct layer; the bottom bar stays`
  - last line: `html[data-skin="daylight"] .sup-cpn.nsent{ color:#8a2a1f; } html[data-skin="daylight"] .sup-cpn b{ color:#15171f; background:rgba(16,18,30,.08); }`
- `app/support.js` — 13645 bytes · 123 lines
  - first line: `/* Jellystructure — Support (support-lane). Renders into #ai-support (AI page tab: list → detail with the forward`
  - last line: `})();`
- `app/support.css` — 9461 bytes · 93 lines
  - first line: `/* Jellystructure — Support (support-lane, decided 2026-10-14): a tab on the AI page, the open-ticket count on the AI`
  - last line: `.tk-lg i { font-style:normal; font-family:'JetBrains Mono',monospace; font-size:.76rem; color:var(--ink-soft); text-align:right; }`

- `ravilo/mobile/ravilo-motion.css` — 3209 bytes · 48 lines
  - first line: `/* Ravilo phone — motion layer (2026-10-14). Loaded after ravilo-phone.css; touches transitions, transforms and`
  - last line: `}`

Edited files (targeted, additive)
- `ravilo/mobile/ravilo-phone.js` — Profile page gains the Support row (via `RaviloSupport.profileRow()`), `data-pf="support"` opens the list, `openAcct` exported on `RaviloHost`.
- `ravilo/platform/ravilo-platforms.js` — phone surface loads `mobile/ravilo-support.css`, `mobile/ravilo-support.js` and `mobile/ravilo-motion.css`.
- `app/app-shell.js` — NAV items accept `count`; the AI entry shows the open-ticket count (sidebar's first count badge).
- `app/ai.html` — tab row Fetchers · Support; `#ai-support` mount; `?tab=support` deep link.
- `app/activity.html` — third view "Support log" (`#view-support`), logs only; `?view=support` deep link.

Motion (2026-10-14)
- Phone shell: pushed layers slide in from the right, the profile sheet rises with its scrim fading, the bottom-bar pill springs onto the lit item, rows/tiles/buttons press with scale feedback. `prefers-reduced-motion` turns it all off.
- Support thread: the thread re-renders incrementally, so new bubbles rise in (viewer from the right, agents from the left), system lines fade up, the typing bubble fades out before the reply lands, the presence dot pulses when the holder changes, the "Sending…" strip slides in, the send button and progress line animate, the ticket list staggers in.

What it does
- Phone: Profile › Support → My tickets → thread. Unread dot on the Profile avatar and on the row. Send locks the composer ("Sending…"), the bubble appears only on the simulated echo, then "AI Agent is writing", then a reply. Writing under a resolved ticket reopens it with the amber line. "Write to support" starts a new ticket with the receipt line.
- Admin: AI › Support lists all viewers' tickets with filters; a ticket opens to the thread + rail (state, class, holder, what the viewer sees, Forward to with the owner row's warning, Resolve, Flag as abuse). Flagging turns replies off and shows the viewer-sees line unchanged. Activity › Support log is the read-only history.

## Direction headings, character for character

- `A · Presence — the ticket header is a chat header` (recommended)
- `B · Ledger — state lives only in the thread`
- `C · Status card — the helpdesk foil`

## Recommendation

A. The ticket half lives in exactly two places a chat already has: the header's second line (presence) and system lines in the thread. The thing that bends is the ticket — no fields, no status box, no assignee row. B is the fallback if the owner wants the header thinner; C should not ship.

## Decided rows (owner, 2026-10-14)

1. **Pinned** header (A). The pill alternative (B, `alt-pill`) stays for the record.
2. Class **inferred, shown after the receipt** (all A frames). The chooser (`alt-chooser`) stays for the record.
3. Unread marker **on the existing Profile entry** (`unread-nav`); no Support tab on the bar (`alt-navitem` not picked).
4. **No own sidebar entry.** Support is a tab on the AI page (Fetchers · Support), the AI sidebar entry carries the open-ticket count (sidebar badges are now a thing), and Activity gets a logs-only "Support log" tab (`alt-activity-tab`, redrawn as the log).

## STATES — drawn / skipped, by name

Standing set
- loading — drawn (`presence-loading`, the list; the thread's skeleton is described, not drawn)
- empty — drawn (`presence-empty`)
- error — send failed, offline — drawn (`presence-error-send-failed`)
- longest string — drawn (`presence-longest-string`, 2 000 characters, clamped at 14 lines with "Show the rest")
- Faroese and Danish — drawn (`presence-faroese`, `presence-danish`)
- 10-foot focus — drawn (`presence-10-foot-focus`)
- offline — drawn (`presence-offline`)
- light toggle — drawn in addition (`presence-light`)

This round's own
- composing the first ticket — drawn (`presence-first-composer`; nothing is asked first, the device is attached by the server and shown as a mono line)
- the pending instant before the server echo — drawn (`presence-sending`, composer-local, no optimistic bubble)
- waiting for the first reply with the deterministic receipt — drawn (`presence-waiting-first-reply`)
- AI Agent is composing — drawn (`presence-ai-composing`, static dots in the canvas)
- AI Agent answered, auto — drawn (`presence-ai-answered`)
- the forward — drawn (`presence-forward`)
- a state change as an inline system line — drawn (`presence-system-line`)
- held by a seat that may not answer, Hervør owes the next message — drawn (`presence-held-by-owner`, ticket #38)
- an unread reply and where its marker lives — drawn (`presence-unread-nav`, `presence-my-tickets`)
- resolved, and reopened by a new message — drawn (`presence-resolved`, `presence-reopened`)
- flagged as abuse — drawn both sides (`presence-abuse-viewer`, `presence-abuse-admin`; the viewer side is identical to waiting-for-first-reply)
- admin list, one ticket's detail with the forward control — drawn (`presence-admin-list`, `presence-admin-detail`)
- TV read-only thread with "Same here" — drawn before and after OK (`presence-10-foot-focus`, `presence-tv-same-here`)
- author-and-badge vocabulary, system-line treatment, presence line, class chips — drawn in one frame (`presence-vocabulary`)

Skipped
- the thread's own loading skeleton (list skeleton drawn; thread described)
- desktop and web layouts of the thread (the brief lists them as surfaces; the phone was the question and the vocabulary is surface-independent — a desktop pass is one frame of work if wanted)
- Faroese/Danish on the TV and in the list (drawn on the thread only)

## Title rule (owner, 2026-10-14)

The viewer's first message is never lifted into the title — it stays the first message in the thread. AI Agent writes a short case title from it, and the receipt line records it: "Received · #41 · logged as a bug · titled “Sound drops in Nordvest S02E04 on the sitting-room TV”". The header, My tickets and the admin list all show that title. In the Ravilo mockup the title is generated client-side as a stand-in for the server.

## Things we had to decide to draw, flagged for the owner

- Who taps "Same here" on a shared TV: drawn as the signed-in TV profile (Ása) adding a line to Jákup's ticket about that TV. If tickets are strictly private per viewer, the TV should list only the signed-in profile's own tickets and "Same here" means "it happened again".
- A resolved ticket keeps its composer with the placeholder "Write here again to reopen"; the alternative is a button that opens a new ticket referencing the old number.
- AI Agent never resolves a ticket in the drawings (auto talks, never acts); Hervør resolves #36 after the auto-answer.
- Hervør is drawn as a named AI agent: square mark with her initial, outlined `AI` badge plus the `support` seat badge. Hallur is the one round, single-badge answering seat.
- The thread screen has no bottom bar (the composer owns the bottom edge); "My tickets" keeps it.

## New UI strings — en / da / fo

sup_title — Support / Support / Support
sup_my_tickets — My tickets / Mine sager / Mínar sakir
sup_write — Write to support / Skriv til support / Skriva til supportina
sup_placeholder — Write to support… / Skriv til support… / Skriva til supportina…
sup_placeholder_reopen — Write here again to reopen / Skriv her igen for at genåbne / Skriva her aftur fyri at lata upp aftur
sup_first_hint — Tell us what happened and where — which TV or phone helps. / Fortæl hvad der skete og hvor — hvilket tv eller hvilken telefon hjælper. / Sig frá, hvat hendi og hvar — hvat sjónvarp ella fartelefon hjálpir.
sup_sent_from_phone — Sent from Ravilo on the phone · {name} / Sendt fra Ravilo på telefonen · {name} / Sent frá Ravilo á fartelefonini · {name}
sup_you — {name} · you / {name} · du / {name} · tú
sup_badge_support — support / support / support
sup_badge_ai — AI / AI / AI
sup_ai_writing — AI Agent is writing / AI Agent skriver / AI Agent skrivar
sup_state_open — Open / Åben / Opin
sup_state_resolved — Resolved / Løst / Loyst
sup_class_bug — Bug / Fejl / Feilur
sup_class_request — Request / Ønske / Ynski
sup_class_question — Question / Spørgsmål / Spurningur
sup_pres_waiting — Open · waiting for a first reply / Åben · venter på første svar / Opin · bíðar eftir fyrsta svari
sup_pres_with — Open · with {name} / Åben · hos {name} / Opin · hjá {name}
sup_pres_with_owner — Open · with the owner · {name} will answer here / Åben · hos ejeren · {name} svarer her / Opin · hjá eigaranum · {name} svarar her
sup_pres_resolved_by — Resolved · by {name} / Løst · af {name} / Loyst · av {name}
sup_replied_in — replied in {n} minutes / svarede på {n} minutter / svaraði á {n} minuttum
sup_forwarded_ago — forwarded {time} / sendt videre {time} / sent víðari {time}
sup_sys_received — Received · #{n} · logged as a {class} · titled “{title}” / Modtaget · #{n} · registreret som {class} · med titlen “{title}” / Móttikið · #{n} · skrásett sum {class} · við heitinum “{title}”
sup_sys_forwarded — AI Agent forwarded this to {name} / AI Agent sendte det videre til {name} / AI Agent sendi hetta víðari til {name}
sup_sys_handling — {name} is handling this / {name} tager sig af det / {name} tekur sær av hesum
sup_sys_owner — Forwarded to the owner · {name} will answer here / Sendt videre til ejeren · {name} svarer her / Sent víðari til eigaran · {name} svarar her
sup_sys_resolved — Resolved by {name} / Løst af {name} / Loyst av {name}
sup_sys_reopened — Reopened — you wrote again / Genåbnet — du skrev igen / Latið upp aftur — tú skrivaði aftur
sup_sys_same_here — {name} said same here · from {device} / {name} sagde det samme · fra {device} / {name} segði sama her · frá {device}
sup_ai_forward_msg — I'll forward this and they will answer you soon. / Jeg sender det videre, og de svarer dig snart. / Eg sendi hetta víðari, og tey svara tær skjótt.
sup_sending — Sending… / Sender… / Sendir…
sup_not_sent_offline — Not sent — you're offline. Your message is kept here. / Ikke sendt — du er offline. Din besked er gemt her. / Ikki sent — tú ert uttan samband. Boðini eru goymd her.
sup_try_again — Try again / Prøv igen / Royn aftur
sup_offline_bar — Offline — showing what was last loaded / Offline — viser det senest hentede / Uttan samband — vísir tað seinast heinta
sup_show_rest — Show the rest / Vis resten / Vís restina
sup_empty_title — No tickets yet / Ingen sager endnu / Ongar sakir enn
sup_empty_body — Something wrong, or something you wish Ravilo did? Write to support — a reply usually comes within minutes. / Er noget galt, eller ønsker du dig noget af Ravilo? Skriv til support — svaret kommer som regel inden for få minutter. / Er okkurt galið, ella ynskir tú tær okkurt av Ravilo? Skriva til supportina — svar kemur vanliga innan fáar minuttir.
sup_unread_one — 1 new reply / 1 nyt svar / 1 nýtt svar
sup_tv_same_here — Same here / Det samme her / Sama her
sup_tv_said_same_here — You said same here / Du sagde det samme / Tú segði sama her
sup_tv_readonly — Reply from Ravilo on the phone or the web. / Svar fra Ravilo på telefonen eller nettet. / Svara frá Ravilo á fartelefonini ella á netinum.
sup_tv_hint_ok — OK on the remote · adds one line, no typing / OK på fjernbetjeningen · tilføjer én linje, ingen indtastning / OK á fjarstýringini · leggur eina linju afturat, einki at skriva

Admin, English only: Support · All · Open · With me · Resolved · Flagged · Viewer · Ticket · Class · State · Holder · Last · Viewer sees · Forward to · Forward · Resolve · Flag as abuse · Remove flag · "Owner — cannot answer here. Hervør keeps the thread and owes the next reply." · "Stops every reply, including AI Agent's. The viewer is told nothing." · "Flagged as abuse by {name} · {time}. No reply will be sent, not even AI Agent's. The viewer sees an open ticket and nothing about the flag." · "Replies are off while this ticket is flagged." · "Reply as {name} · admin" · "AI transcript for #{n} →" · "Nobody · no reply will be sent" · "Owner · {name} answers".

Existing keys reused unchanged: pm_settings, pm_profile, admin, err_retry. The shipped i18n/*.json wins wherever a key already exists.

## Confirmation

This package created nothing called `STATUS.md`, nothing called `CLAUDE.md`, and nothing under `specs/` or `scripts/`. No phase number appears in any file.
