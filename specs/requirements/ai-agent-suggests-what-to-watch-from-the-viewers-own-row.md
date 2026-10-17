# `AI Agent` answers "what should I watch?" from the viewer's own Recommended row

Task: DEB-1667

> Owner, 2026-10-17, on the DEB-1663 card (option B): `AI Agent` answers a recommendation question itself, from
> **this viewer's own** suggestions, which the server already computes. Today it forwards, because the system prompt
> bans anything about the library (FR-321-5 rule 3). Queued after the forward-line fix (DEB-1665).

## Scope

Backend only, inside the `support` job phase 321 built. **No schema change to `AI Agent`'s answer, no new DTO field,
no admin or Ravilo screen.** The reply is still a `MESSAGE` by `AI_AGENT`, rendered as text wherever a reply renders
today, so this work has no design surface.

It changes three shipped promises, and each spec carries a dated amendment pointing here:

- FR-321-5 rule 3 gains one narrow exception, and a seventh rule checks it by machine;
- FR-321-11's support line, and FR-270-8's through 270's 2026-10-15 amendment, gain one clause about what leaves the
  house;
- FR-321-4's *also sent* list gains the suggestion block.

It builds on DEB-1665 (the 2026-10-17 amendment to FR-321-7). That work rewrites the same file (`SupportAgent.kt`), so it
ships first.

## Current behaviour

- **What the server computes per viewer** (phase 269, re-ordered by 270's re-rank). `RecommendationService` stores
  each viewer's list in `recommendation`, keyed by `(user_id, scope_key)`, up to 50 rows (`LIST_SIZE`). Where re-rank
  has run, the list follows the AI's order (`ai_order`, source `ai`). Elsewhere it is 269's own order. The
  *Recommended* row on Home is `servedFor(device, visible)`: that stored list, minus whatever the viewer can no longer
  see, or the scope's starter list while the viewer has none yet.
- **The scope** (`RecommendationEngine.scopeKey`) is a hash of the viewer's library access and their allowed and blocked
  tags. A Jellyfin policy is per user, and phase 258 keeps every device row of a user in step with it, so one user has
  one live scope.
- **Kids profiles.** The list is filtered by library access and tag policy, the same visibility the Home feed uses
  (FR-269-5). **Jellyfin's `MaxParentalRating` is not applied**: in this codebase it only sets `isKids`. Phase 142 left
  rating-level filtering out on purpose (*Non-goals*), and only R320's Seerr row checks a certification. So a kids
  profile's Recommended row is only as narrow as the household's library and tag fences for that user.
- **The support job** builds one request per ticket in `SupportAgent.build` from `support_ticket.owner_user_id`, the
  thread and the viewer's language. Today it sends nothing about the library, and the system prompt forwards *"a
  recommendation of what to watch or listen to"* to `HERVOR` (the DEB-1663 amendment to FR-321-7).
- **What already leaves the house for a viewer** (FR-270-8, re-rank): up to 100 shortlisted titles with year, genres,
  keywords and a 280-character synopsis, plus the titles and genres they watched recently. It is sent weekly, on a
  first build, and on *Rebuild now*.

## Options considered

1. **Hand the viewer's served row to the model, and let the server write every title** (recommended, specified below).
   The model refers to a suggestion only by a short id in braces, and the server swaps in the title. A machine check
   forwards any reply that writes a library title itself. Every title a viewer reads comes from the server, so nothing
   is hallucinated and nothing outside their row is named.
2. **Hand the row and let the model write titles freely, then check each one against the handed set.** This reads more
   naturally, but the check has to find titles in free text in three languages. That is fuzzy, and a miss names a title
   the viewer was never shown.
3. **Answer without a model.** The server posts the top five of the row under a fixed line. This is cheap and safe, but
   it ignores what the viewer asked for (*"something short"*, *"for the kids"*) and is no better than the row itself.

## Requirements

**FR-1 — The suggestion block.** When `SupportAgent.build` builds a request, it adds a block of suggestions to the user
message if all four of these hold:

- the `rerank` job is **active** (FR-270, the same `jobOn` test). Re-rank already sends these titles and these
  fields for this viewer, so the support job sends nothing that the owner has not already agreed to send;
- the ticket owner has a device row: their most recently seen `ravilo_device` row for `owner_user_id`, of any age;
- that device is not `isKids` (FR-4);
- what Home would serve them now is not empty. That is `servedFor`'s list for that device and its `liveItems(device)`,
  read **without** its `markStale` side effect: the support job reads, and never queues a rebuild.

The block holds the first **30** titles of that list in Home's order. Each is one line in re-rank's exact form
(`AiRequests.describe`): a short id `s1`…`s30`, the title, year, film or series, genres, keywords and a
280-character synopsis. **Never** a Jellyfin id, a reason re-rank gave (FR-270 promises the viewer never sees those),
the viewer's watch history, or anything from Jellyfin's user records. The id never leaves the server, because the
lookup runs on `owner_user_id` and only the short ids are sent.

The block sits in the user message, after the language and class lines and before `<thread>`, between its own
delimiters (`<suggestions>` … `</suggestions>`). The system prompt stays unchanging and cached. When any of the four
conditions fails, no block is sent and the request is byte-for-byte what it is today.

The map from short id to Jellyfin id is kept in the queue row's `ctx` (`SupportAgent.Ctx` gains it, defaulting to
empty so a row queued before this work still reads), because the result is applied against the set that was sent, not
one rebuilt an hour later.

**FR-2 — The prompt's narrow exception to rule 3.** The system prompt gains a paragraph. Its substance, worded in
code:

*When a `<suggestions>` block is given, it lists titles this server already recommends to this viewer. If they ask
what to watch, you may answer with up to five of them, and you refer to each **only** as its id in braces, like
`{s3}`. The server replaces each id with the title, so never write a title yourself, and never name or describe any
title that is not in the block. Choosing from the block is not a claim about the library, so it does not make
`claimsState` true. Everything else in rule 3 holds. When no block is given, a recommendation question is forwarded
as before.*

The `forwardTo` line keeps *"a recommendation of what to watch or listen to"* for `HERVOR`. It applies whenever the
model cannot answer from the block, which includes music and audiobooks, since the block carries only films and series.

**FR-3 — Rule 7, checked by machine.** FR-321-5 gains a seventh rule. A reply sends only if every rule holds, 1 to 6
as before and 7 as follows:

1. Every `{…}` token in the reply is a short id handed in this request's block. There are at most **5** distinct ids,
   and there are none when no block was sent.
2. The reply as the model wrote it, before the ids are replaced, contains **no film or series title from the library**:
   no `title` or `originalTitle` of any live film or series in `MediaStore`, from **every** library and not only the
   viewer's. The check folds text with `SupportAi.fold`, matches whole words, and skips titles shorter than four
   letters after folding. This also catches a handed title written out instead of by id. A false match forwards, and
   that is the safe direction.
3. When the result is applied, each id resolves through `ctx` to a title that is still live and still
   `visibleTo(device)` under the owner's current device row. A title that has left the library, or a policy that has
   narrowed while the batch was out, forwards.

A failure forwards like any other rule (FR-321-6), and the `ADMIN_ONLY` note names the part in words that quote no
title: *"rule 7 — an id not in the suggestions"*, *"rule 7 — more than five suggestions"*, *"rule 7 — the reply names
a library title itself"*, *"rule 7 — a suggestion is no longer visible to this viewer"*.

The case title (FR-13 of [`support-is-a-tab-on-the-ai-page.md`](support-is-a-tab-on-the-ai-page.md)) gets the same
library-title check as part 2, and **no** `{…}` ids. A title that fails is dropped, never forwarded, like FR-13's
other tests.

**FR-4 — Kids profiles get no block.** A ticket whose owner's device row has `isKids` is built without the suggestion
block. A recommendation question from a kids profile therefore forwards to `HERVOR` or `ADMIN`, as it does today. The
reason is that the row is not filtered by the parental cap (see *Current behaviour*). A text reply from `AI Agent`
reads as a personal endorsement, which a row of posters does not. This lifts in its own amendment once Home's
Recommended row applies `MaxParentalRating`, which is separate work.

**FR-5 — The server writes the titles.** On a sent reply, each `{sN}` is replaced with the title and year **as the
server stores them** (`MediaItem.title`, then ` (year)` when known), the same title Home's card shows. This happens
after rules 1–7 pass and before the `MESSAGE` is written. The viewer's thread holds the replaced text. The
transcript (FR-321-10) keeps both: the exact user block sent, which includes the suggestions, and the answer as
returned, with its ids.

On a later turn, `threadBlock` sends the `AI_AGENT` entry as the viewer saw it, with titles. Rule 7 part 2 reads only
the new reply, so a title quoted from an earlier turn's thread does not trip it. A reply that writes such a title
itself still does.

**FR-6 — What leaves the house, stated on the tab.** FR-321-11's support line, and the Support card's one line, gain a
clause:

*… and, while re-rank is on, up to 30 titles from the viewer's own Recommended row, with the same details re-rank
sends. Never for a kids profile, and never their watch history.*

FR-270-8's re-rank line is unchanged.

**FR-7 — Cost and lane.** This work rides the batch lane, like every support turn (FR-321-3). The synchronous turn is
still its own later work. When it lands it carries this block unchanged, because the block is part of the request and
not of the transport.

The block adds about 30 × 110 ≈ 3 300 input tokens to every turn it is sent on, whether or not the viewer asked
for a recommendation, because the server cannot tell before the model reads the thread. On the default model (Opus 5,
$5 per million input tokens, halved on the batch lane) that is about **$0.008 a turn**, and at most about **$0.05 a
ticket** under FR-321-8's six turns. FR-321-8's three bounds and 270's monthly limit apply unchanged. The Support
card's estimate (FR-270-1) includes the block when re-rank is on.

**FR-8 — Tests,** in `linuxX64Test` against a mocked Anthropic. Every title is an invented stand-in (below):

- The block is present with re-rank active, a device row and a non-empty row. It is absent, and the request is
  byte-identical to today's, when any one of the four conditions fails. Kids is one of them.
- The exact request JSON carries the short ids and the `describe` lines, and no Jellyfin id, user id, user name, re-rank
  reason or watch history.
- A reply with `{s2}` and `{s5}` sends, and the stored `MESSAGE` holds both titles with their years.
- Each part of rule 7 failing alone forwards with its reason: `{s31}` when 30 were sent, six distinct ids, a stand-in
  title written out in the prose (handed, and not handed), a title invisible to the viewer, a title gone from the
  library, and any id when no block was sent.
- A library title in the case title drops the title, and the reply still sends.
- A stand-in title of three letters in prose does not trip part 2.
- A batch built before this work (no block, no ids in the answer) is ruled exactly as today.

## Stand-in data

Invented titles only, in tests, fixtures and this spec. None of them is a real title:

| id | title | kind |
|---|---|---|
| `s1` | The Copper Orchard (2016) | film |
| `s2` | Lantern Street Mysteries (2021) | series |
| `s3` | Nine Quiet Winters (2009) | film |
| `s4` | Owl (2018) | film — the three-letter title that part 2 skips |
| — | Harbour of Small Lights (2014) | film in a library the test viewer cannot see |

## Acceptance

- With re-rank and support both active, an adult viewer asks in Faroese what to watch tonight, *something calm*. Within
  a batch round-trip, `AI Agent` replies in Faroese and names two or three titles from that viewer's own Recommended
  row, each exactly as Home shows it. The transcript shows `SENT`.
- The same question from a kids profile gets the forward line, and the transcript shows no suggestion block.
- With re-rank off, the same question from an adult forwards, and the request carries no block.
- A viewer asks how to keep subtitles on. The reply is unchanged in kind and names no title.

## Non-goals

- Applying `MaxParentalRating` to Home's Recommended row. That is a separate piece of work, and FR-4 lifts once it ships.
- Music and audiobook suggestions. 269's row holds films and series only.
- A tappable title in the support thread. The reply is text, and a deep link is a client change with its own design.
- Sending the viewer's watch history or re-rank's reasons to the support job.
