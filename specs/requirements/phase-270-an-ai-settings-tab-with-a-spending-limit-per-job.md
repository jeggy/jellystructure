# Phase 270 — An *AI* settings tab, with a spending limit per job

> Owner, 2026-09-26: *"So in jellystructure settings, we could have a new settings tab called AI, where I can
> choose a AI Provider (initially we will only support Antrophic) and then paste an API KEY and then on the
> different things we are using (currently only this recommended job), we can set a spending limit on each
> type of job. So let's add this as an optional feature ontop of the standard recommended algorithm that
> we are gonna build."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Backend (a provider client, a job runner, a usage ledger) and the admin (a new Settings
tab). It sits **on top of Phase 269**: with AI off, or when a limit is reached, 269's standard list is
what every viewer gets, unchanged. **Numbering:** verified against `STATUS.md` the same day — admin
taken through **269**.

Facts about the Claude API below come from Anthropic's API reference as bundled with Claude Code (model
table cached 2026-06-24). Prices must be re-checked before this ships (FR-270-6).

## Two jobs, and why these two

1. **Re-rank (per viewer).** 269 scores the library with arithmetic over metadata. It is good at *what
   is similar*, and blind to things a language model reads easily from a synopsis: tone, audience, "the
   same kind of evening". The AI gets 269's **top 60** for a viewer plus a compact view of what they
   watched, and returns the **20** it would show, with a short reason each. It never adds a title of its
   own: it re-orders a shortlist the engine already judged eligible.
2. **Theme tags (per title, once).** TMDB keywords are thin or missing on local and regional titles,
   such as the household's Faroese and Danish television. For those, the AI reads the synopsis once and
   writes 5–8 theme tags, stored on the title as one more feature for 269's similarity. It runs once per
   title, and again only when the synopsis changes.

Both are background work that nobody waits for. So both go through the **Message Batches API**, which
bills every token at 50 % (cache reads and writes included) and returns results within 24 hours, usually
within one.

## Requirements

**FR-270-1 — A Settings tab, *AI*.** Beside the other tabs:

- **Provider:** *Anthropic*, the only choice for now, drawn as a choice so a second can be added later
  without a redesign.
- **API key:** masked once saved (last four characters shown), stored with the other API keys in
  `config.toml` under `[ai]`, never sent to any Ravilo client, never logged. **Test key** calls
  `GET https://api.anthropic.com/v1/models` (`x-api-key`, `anthropic-version: 2023-06-01`). That costs
  nothing, and answers *valid* (200), *invalid key* (401) or *can't reach Anthropic*.
- **AI on/off** for the whole installation. Off means no request leaves the server, whatever the jobs
  say. Off by default.
- **One card per job** (*Recommendations re-rank*, *Theme tags*), each with:
  - an on/off switch;
  - the **model**;
  - the **monthly spending limit** in USD;
  - **spent this month**, from the ledger (FR-270-5);
  - the last run's outcome in one line (*ran 06:10 · 4 viewers · $0.21* / *skipped: limit reached* /
    *batch expired — standard list kept*);
  - an estimate of a month at the current settings (FR-270-7).

**FR-270-2 — The model is the owner's choice, per job.** Offered: `claude-opus-5` (**the default**),
`claude-sonnet-5` and `claude-haiku-4-5`, each with its price beside it (input / output per million
tokens: $5 / $25, $2 / $10, $1 / $5). All three support structured JSON output. The default is not
lowered for cost on the owner's behalf: the limit is how cost is controlled, and the estimate shows what
each choice would cost.

**FR-270-3 — The requests.** Plain HTTPS from the backend to `POST /v1/messages/batches` (the backend is
Kotlin/Native, for which there is no Anthropic SDK). One request per viewer (re-rank) or per title
(themes), each with a `custom_id` that is an opaque key, never a user name. Each request:

- constrains the answer with `output_config.format` (a JSON schema, `additionalProperties: false`):
  re-rank → `{"picks":[{"id","reason"}]}`, themes → `{"themes":[…]}`;
- puts the unchanging instructions first, so they can be cached (`cache_control`). Within one batch,
  cache hits are best-effort;
- uses effort `medium` for the re-rank and `low` for themes, adjustable per job. Effort is sent only to
  a model that takes it: Haiku 4.5 rejects the parameter, so for Haiku the field is omitted and the card
  hides the control.

Results are read by `custom_id`, never by position.

**FR-270-4 — Nothing trusted blindly.**

- A re-rank answer is accepted only if every id is in that viewer's shortlist, none repeats, and there
  are 20. Short answers are topped up from 269's order. Anything else is discarded.
- `stop_reason` is checked before the content is read: `refusal` or `max_tokens` means the viewer keeps
  269's list.
- A batch that fails or expires keeps every viewer on 269's list, and the card says so.
- Reasons are stored with the list (269 FR-269-9) and shown to the admin, never to viewers.
- Theme tags are lower-cased, de-duplicated, and capped at 8.

**FR-270-5 — A ledger, from the API's own numbers.** Every result's `usage` (`input_tokens`,
`output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`) is written to an
`ai_usage` ledger (job, month, model, requests, the four token counts, cost in micro-dollars). Cost is
computed from the dated price table (FR-270-6) with the batch discount, cache writes at 1.25× and cache
reads at 0.1× of input. *Spent this month* is the ledger's sum. Pending batches are kept in an
`ai_batch` table (id, job, submitted, status), so a server restart resumes polling instead of losing the
results or paying twice.

**FR-270-6 — A limit is a hard stop, checked before spending.** Before submitting, the job estimates the
batch's **worst case**:

> input tokens (the requests' size, or `POST /v1/messages/count_tokens`) + `max_tokens` × requests,
> priced at the model's rates

If *spent this month + worst case* would pass the job's limit, the batch is not sent. The job skips this
run, 269's lists stand, and the card says *limit reached*. A month is the calendar month in the
server's time zone. The price table is shipped in code with the date it was confirmed and shown on the
tab (*prices as of …*). An estimate can be wrong; the ledger's actuals are what the next check uses.

**FR-270-7 — What a month costs, shown before it is spent.** The card's estimate uses the job's own recent
runs once there are any, and a documented starting assumption before that:

| Job | Assumption | Opus 5 | Sonnet 5 | Haiku 4.5 |
|---|---|---|---|---|
| Re-rank, per viewer | ~7k input, ~3k output (incl. thinking) | ≈ $0.055 | ≈ $0.022 | ≈ $0.011 |
| Re-rank, 4 viewers daily | 120 runs a month | ≈ $6.60 | ≈ $2.60 | ≈ $1.30 |
| Themes, once over 528 titles | ~0.5k in, ~0.15k out each | ≈ $1.70 | ≈ $0.70 | ≈ $0.35 |

All figures are batch prices, before cache savings. A re-rank runs on 269's **daily** build and on a
viewer's first build, not on every finish-triggered rebuild: those keep the AI's last order, filtered and
topped up by 269 (FR-269-7).

**FR-270-8 — What leaves the house, stated on the tab.** The tab says what is sent, in one paragraph:

- **re-rank:** titles, years, genres, keywords and a shortened synopsis of the candidates, and the titles
  and genres a viewer watched recently;
- **themes:** a title's name, year, genres, keywords and synopsis.

Never names, user ids, device names, IP addresses or anything from Jellyfin's user records. A kids
profile's history is sent under the same rules as anyone's. The owner switches AI on knowing this.

**FR-270-9 — Tests.** The price arithmetic, including the batch discount and cache multipliers. The limit
check refusing a batch that would pass it. A re-rank answer with a foreign id, a repeat, 18 picks and a
refusal (each falls back correctly). Batch resume after a restart. The key test's three outcomes, against
a mocked Anthropic.

## Non-goals

- A second provider. The tab is built to take one; this phase ships Anthropic only.
- AI anywhere else: chat, search, descriptions for viewers. The job list is built to grow, one card per
  job, with a limit each.
- Streaming or interactive use. Nothing here is waited on by a person.

## Acceptance

1. Settings → AI: paste a key, *Test key* says *valid*. A wrong key says *invalid key*.
2. Turn on *Recommendations re-rank* with a $5 limit. After the next daily build, *Users & devices* shows
   each viewer's list marked *AI*, with reasons, and the card shows the run's cost.
3. Set the limit below what has been spent: the next run is skipped, the card says *limit reached*, and
   the lists are 269's.
4. Turn on *Theme tags*: every title without keywords gains themes, and the card's spend matches the
   ledger.
5. Turn AI off: no request reaches Anthropic (proved by the dev review's item 8 test) and Ravilo is
   unchanged.

## Dev review (2026-09-26, against `main` `0e5e434f`)

Buildable as written, with two corrections (items 1 and 8). Nine items.

1. **Correction: the key is never read back, so the hint is its own route.** `GET /api/config` replaces
   every non-blank secret with the `##KEEP##` sentinel, and `PUT` restores it (`ConfigRoutes.kt:81-87`,
   `:191`). The AI key joins that list. FR-270-1's *last four characters* therefore come from a new
   `GET /api/ai/status`: key hint `…abcd`, per job *spent this month*, the last run's line, and the
   pending batch if any. It never returns the key. The config shape is a new `AiConfig` beside `ApiKeys`
   (`AppConfig.kt:253`): `enabled`, `provider`, `apiKey`, and `jobs: Map<String, AiJobConfig(enabled,
   model, effort, monthlyLimitUsd)>`.
2. **The tab.** `SETTINGS_TABS` (`Settings.kt:780`) gains `"ai"`. The page is built like its siblings
   from `wf.css` components. There is no design mockup for it, so it is built from this spec, and
   `design/app/settings.html` can follow.
3. **Outbound.** Through `OutboundHttp` on `GateClass.BACKGROUND`, like every background fetch
   (Phase 182), with the same Ktor client TMDB uses. Headers `x-api-key` and
   `anthropic-version: 2023-06-01`. A 429 or 5xx on create or poll backs off (Phase 183's jittered
   exponential backoff).
4. **The batch lifecycle.**
   - `POST /v1/messages/batches` returns the batch id.
   - Poll `GET /v1/messages/batches/{id}` every five minutes until `processing_status` is `ended`.
   - Stream the `results_url` JSONL line by line, one result per `custom_id`, each `succeeded`,
     `errored`, `canceled` or `expired`.

   Migration **55** (after 269's 54) creates `ai_batch (id, job, submitted_at, status, request_count)`
   and `ai_usage (job, month, model, requests, input_tokens, output_tokens, cache_write_tokens,
   cache_read_tokens, cost_micro_usd)`. At boot, `submitted` rows resume polling.
5. **Validation (FR-270-4) is a pure function per job,** unit-tested with a foreign id, a repeat, 18
   picks, `refusal`, and `max_tokens`. On `claude-opus-5` a refusal is not routed to a fallback model:
   falling back to 269's list is the right outcome for a ranking.
6. **The price table is code, dated.** An `AiPricing` object holds input/output rates per model (Opus 5
   $5/$25, Sonnet 5 $2/$10, Haiku 4.5 $1/$5 per MTok, *as of 2026-06-24*), plus ×0.5 batch, ×1.25
   cache write (5-minute TTL) and ×0.1 cache read. It is re-checked against Anthropic's pricing page
   before release, and the tab shows its date.
7. **The worst-case estimate (FR-270-6) needs no extra call.** Input is the serialised prompt's
   characters ÷ 3 (conservative for these prompts), and output is `max_tokens` × requests (re-rank
   4,000, themes 600). It errs high on purpose; the ledger corrects the running total after each batch.
   `count_tokens` stays available if the estimate turns out too loose.
8. **Correction to acceptance 5.** There is no outbound request log to read. Prove *off* with a test
   instead: with AI off, running both jobs against a mocked Anthropic that fails on any request passes,
   and no `ai_batch` row appears.
9. **The seam with 269.** `RecommendationService` exposes `shortlist(viewer, 60)` and
   `applyAiOrder(viewer, picks)`. The AI job never scores, filters or stores titles itself; 269 stays
   the only author of a list.

**Net effect.** A small Anthropic client, a batch runner with resume, migration 55, a price table, two job
definitions with pure validators, one status route, one Settings tab. Nothing changes for viewers while
it is off.
