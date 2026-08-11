# Phase 162 — Towo: a self-hosted control plane for Claude Code sessions (FR-TOWO1)

**Status:** Planned → **all 8 build-order steps implemented, 2026-08-11**, and verified end-to-end
against a real Claude account (see §9) — a first functional slice, not full parity with every design
detail in §4–§8 (see the addendum's "What's genuinely done vs. simplified" note). Dev-reviewed
2026-08-11 (see the addendum: three real build-config/incident-precedent gaps found and closed, two
protocol details made explicit, plus two real runtime bugs found and fixed via live browser testing).
Open questions §10.1–2 resolved against the pinned SDK's real types.
**Date:** 2026-08-10
**Research basis:** `specs/research-reports/claude-code-remote-agent-management-2026-08-10.md`
(doc-verified; §11 of that report lists the integration unknowns that must be pinned before build).
**Supersedes nothing. Extends nothing** — this is a new, self-contained surface bolted onto the
existing admin shell. No media, scanner, NFO, Jellyfin or Ravilo behaviour changes.

---

## 1. Problem

Claude Code runs on a machine with the checkouts on it. Once you walk away from that machine you
lose three things: you can't see what a session is doing, you can't answer the permission prompt it
is blocked on, and when the usage window runs out the session simply stops until you come back
hours later and notice.

Claude Code's own **Remote Control** solves the first two — but only for claude.ai and the Claude
mobile apps. It is not a public API, the protocol is undocumented, and it is bound to claude.ai
OAuth, so *no* custom UI can ever attach to a `remote-control` session (report §2 — settled, do not
reopen). The supported path for our own UI is the **Agent SDK**, whose documented "long-running
sessions" hosting pattern is exactly this product.

**Towo** is that UI: an admin section that lists the machines running Claude Code, shows what each
session is doing, answers its permission prompts from wherever you are, and — the headline —
**continues a session by itself once the usage window refills**, if you armed it to.

### Why it lives in the jellystructure admin
Towo has nothing to do with media management, and the research report says as much. It lives here
purely because this is the only always-on operator UI we run, it already has the shell, the theme,
the auth and the notification webhook — and the machine it manages is the same dev host. It is
**feature-flagged off by default** (§A) and is a strictly additive section: nothing outside
`app/towo-*` and the Settings tab is touched.

---

## 2. Goals / non-goals

**Goals**
- See every runner, every session, and each session's live transcript.
- Approve or deny a suspended tool call from a phone.
- Understand *why* a session stopped, and have exactly one of those reasons resolve itself.
- Start a session in any folder a runner is allowed to touch, without touching the host again.
- Idle sessions are a normal resting state you can pick back up by typing.

**Non-goals**
- **No orchestration.** Claude spawns and manages its own subagents; Towo displays them (report §3.3).
- **No transcript parsing off disk.** The JSONL layout is explicitly not a stable interface. Session
  listing/reading goes through the SDK's session functions and our `SessionStore`.
- **No Claude authentication.** Claude's own sign-in lives on the host; Towo never holds it, never
  forwards it, and cannot perform it. This is a security property, not a gap.
- **No money anywhere.** Usage is Claude's two self-refilling allowances — 100% per 5-hour window,
  100% per week. Towo shows how much is left and when it resets. There is nothing to buy, budget,
  cap in currency, or top up, and no cost figure appears in any screen.
- **Not multi-user.** Single operator, same session as the admin app. If that ever changes,
  isolation must be designed in, not retrofitted.

---

## 3. Architecture (three tiers)

```
Control plane (this admin app + its API)  ⇄ WSS (outbound from runner) ⇄  Runner daemon  ⇄ stdio ⇄  claude CLI
   session index · quota cache                                             one per host              1 subprocess per session
   SessionStore (transcripts)                                              Agent SDK host
   approvals · notifications                                               owns Claude auth locally
```

- The **runner dials out** and keeps a reconnecting WebSocket open. No inbound port, no firewall
  change. (Both ends already sit on the WireGuard mesh, so a direct private-IP call is a valid
  simplification for our own deployment; the outbound WS stays the portable default.)
- The runner holds a **root allow-list** (`--root`, repeatable) and resolves every path itself. The
  API only ever names a folder; a compromised control plane cannot point a session at `/etc`.
- The runner owns the **auto-continue state machine** (§E) because it is closest to the process.
- `SessionStore` mirrors transcripts into our own storage, so history renders when a runner is
  offline and a session can later resume on a different runner. It is a mirror, not a replacement —
  a `mirror_error` after retries means silent transcript loss and must surface in the UI (§B).

---

## 4. Requirements

### §A Settings → Towo (feature flag + global defaults)
New URL-addressable Settings tab (`settings.html?tab=towo`), following the Phase-55 tab contract.

- **Master enable toggle**, default **off**. Enabling adds the **Towo** group to the sidebar;
  disabling hides the group and every route, leaving runners and sessions untouched — the UI is
  hidden, nothing is stopped. Persisted as `js-towo` (`'0'` = off) and read by `app-shell.js` when it
  builds the nav.
- **Where runners connect** — the WSS endpoint baked into the enrollment command.
- **How the hosts sign in to Claude** — subscription (`claude /login`) or API key. Copy must state
  plainly that usage is the two refilling allowances. **This choice gates §E**: with an API key there
  are no session/weekly allowances, 429s are ordinary rate limits already retried by the SDK, and
  auto-continue is moot — the UI must say so rather than offering a scheduler that can never fire.
- **Quota watch** — on/off plus interval (default **6 hours**), with the plain-language explanation
  that it continues only sessions you armed, at the reset time Claude reported.
- **Arm new sessions automatically** — default **off**. A session only ever resumes itself because
  someone said so per session.
- **Global defaults**: permission profile (default *Normal*) and **turn cap** (default 40). Copy must
  say this is a starting value that every session can override (§F).
- **Notify me when**: a tool needs approval · a session pauses on quota · it picks back up ·
  a session errors · a window drops below 20%. Reuses the existing Notifications webhook.

### §B Runners
- **List** — name, online/offline, host label, and per-runner quota.
- **Detail** — live 5-hour and weekly gauges with reset times; allowed roots shown as
  **host-owned and read-only** (`--root`, changed on the host, not here); the folders the runner
  reported under those roots; host facts (Claude sign-in state, SDK version, OS, how it runs);
  a `mirror_error` warning when transcript mirroring failed; revoke.
- **Folders are discovered, not declared.** `--root ~` *is* the configuration. The runner reports its
  roots and the repos it found beneath them; a session may run in any folder under a root. Naming a
  folder or pinning it only affects labelling and the order it appears in the composer — **there is no
  "add a workspace" step anywhere in the product.**
- **Enrollment** (`towo-runner-new.html`) — name it → paste one command → it reports what it can see.
  Distribution: `npx` (try it now) → installer + systemd (make it permanent) → Docker, with the
  container caveat surfaced in the UI (a containerised runner sees the container's filesystem; the
  real checkouts and Claude's auth must both be mounted in — usually the wrong choice).
- **Token model**: the visible command carries a **short-lived, single-use enrollment token**
  (~15 min TTL, expiry shown, revocable, per runner) which is exchanged on first connect for a
  long-lived credential that is never displayed. A long-lived secret must never appear in a command
  the user pastes into a terminal.
- States that must be designed, not improvised: waiting for the runner (resolves live) ·
  **connected but Claude isn't signed in** (tell them to run `claude /login` *on that host*; the most
  likely stumble) · ready with nothing to configure · runner offline (sessions read-only, history
  still renders, reconnects itself) · version skew — **explicitly "Not available yet"**, so the UI
  never implies a check that doesn't exist.

### §C Sessions
- **Overview** (`towo.html`) groups sessions by folder under their runner, with the pending-approval
  banner first and the runner shelf above. There is no Sessions item in the sidebar — the count is
  small enough that the overview plus "All sessions" is enough.
- **List** (`towo-sessions.html`) — filter by folder and status, sort by recent/turns/folder, per-row
  overflow (open · fork · rename · tag · export · delete).
- **Statuses**: `running` · `awaiting_permission` · **`idle`** · `paused_quota` (+ resume time) ·
  `stopped_max_turns` · `errored` · `done`.
- **Idle is a normal resting state**, not a problem: an idle session costs nothing, keeps its whole
  context, and starts a new turn the moment you type. Copy must say so — the composer is the primary
  affordance on an idle session, and both the list and the overview must read as "fine" for idle rows.
- **Session view** (`towo-session.html`) — chat-shaped transcript from the event stream: text turns,
  **one-line tool cards** (tool name + a human summary + result, expandable to the payload), subagent
  progress lines, the streaming partial of the current turn, a composer, and interrupt. The rail
  carries runner/folder/profile/model, turns used, the editable cap (§F), runner quota, subagents and
  session actions.
- A **turn** must be explained wherever it appears: one round-trip in which Claude thinks, uses tools
  and comes back. It is Claude's unit, not ours.

### §D Remote approval
- `canUseTool` is async and receives an `AbortSignal`, so the runner suspends the call, pushes it up,
  and awaits the decision. Nothing else in that session proceeds meanwhile; an ignored request stays
  suspended (with a long timeout that denies with an editable reason).
- **The trap to design around**: auto-approved tools never reach `canUseTool`. A bare
  `allowedTools: ["Bash"]` silently bypasses the entire approval UI. Correct mechanisms are **`ask`
  rules** (which fall through to `canUseTool` even under `bypassPermissions`) or a **`PreToolUse`
  hook**. A profile must never be expressed as a naked allow-list of dangerous tools.
- **Permission profiles** are a first-class UI object, not raw config: **Read-only** ·
  **Normal** (default: scoped allows, `ask` on writes and pushes) · **Autonomous** (accepts its own
  edits, asks on destructive commands) · **Unrestricted** (`bypassPermissions`, deliberate opt-in).
  Each profile screen must show what it actually permits, in words.
- The approval surface must be answerable **from a phone notification** without opening the session:
  the notification carries Allow/Deny; the sheet carries the command, the plain-language summary, the
  reason it was asked, and an "always allow this here" option.

### §E Quota pauses and auto-continue (the headline)
- The signal is Claude's **`rate_limit_event`**, which fires continuously as an informational status
  (not only when limited): `status`, `resetsAt` (unix), `rateLimitType`, and sometimes `utilization`.
  Treat the latest event per runner as a **live gauge** — that alone drives the quota widgets.
- `utilization` is only populated after a warning threshold is crossed, so every gauge must degrade
  gracefully to "resets at HH:MM" when the percentage is absent.
- Buckets are `five_hour`, `seven_day`, `seven_day_opus`, `seven_day_sonnet`. **Never hardcode 5
  hours**: key the scheduler off the returned `rateLimitType` + `resetsAt`, or an auto-continue loop
  will spin pointlessly against a weekly limit.
- **`resetsAt` is authoritative.** Wake a minute or two after it and re-check the quota event on
  resume rather than assuming.
- Per session, **Continue after reset**, default **off**. Every `quotaWatchInterval` (default 6 h) the
  runner looks for sessions in `paused_quota` whose reset has passed and continues the armed ones via
  `query({ resume: sessionId, … })` in the same folder.
- **Only `paused_quota` reschedules.** `stopped_max_turns` and errors are never auto-retried.
  `worker_shutting_down` is a clean pause, not an error. Transient 429/5xx retries are the SDK's own
  business (`api_retry`) and must not be confused with a quota pause in the state machine.
- **Notify on both edges** — a multi-hour silent gap with no "it came back" defeats the point.
- The paused state must read as **calm and deliberate**: a nap with a countdown and a reset time,
  never an error colour, never anything that reads as broken.

### §F Turn caps — two levels
- **Global default** in Settings (§A), used to prefill every new session.
- **Per session at start**, in the composer, prefilled from the global default with the override
  stated in words.
- **Per session while it runs**, from the session rail: adjust the cap in steps, see whether the
  session is on the default or overriding it, and reset back to the default. A change applies from
  the session's next turn; it never rewrites the global default.
- After a `stopped_max_turns`, raising the cap is offered as recovery (§G) and is always a **user
  decision** — Towo never raises a ceiling you set.

### §G When a session stops early (`towo-limits.html`)
Three cases, told apart explicitly so the automatic one is legible:
1. **Paused on quota** — Towo's to fix. Countdown, and it continues itself if armed.
2. **Stopped at your turn cap** — your call. Offer "resume with more turns" (steps + presets), showing
   where it got to and that it resumes with the same transcript and permissions; nothing is re-run.
3. **Paused on the weekly window** — a genuinely different shape: waiting out the afternoon won't
   help. Offer wait-for-the-reset (armed), move it to a runner signed in as another account (the
   transcript travels), or stop.
Plus a plain error: shown, transcript kept, one retry offered, **never looped**.

---

## 5. Data model (control plane — thin; content lives in the SessionStore)

```
runner        id, name, host_label, status(online|offline), last_seen_at, auth_mode,
              agent_sdk_version, allowed_roots[]
folder        id, runner_id, name, abs_path, pinned, default_permission_profile, discovered_at
session       id (= Claude session_id), runner_id, folder_id, title, tag,
              status(starting|running|idle|awaiting_permission|paused_quota|
                     stopped_max_turns|errored|done),
              max_turns, max_turns_source(default|override), num_turns,
              continue_after_reset (bool, default false),
              resume_at (nullable), last_error_subtype, created_at, last_activity_at
permission_request  id, session_id, tool_name, input_json, requested_at,
                    decided_at, decision(allow|deny|timeout), reason
quota_status  runner_id, rate_limit_type, status, resets_at, utilization (nullable), observed_at
```

`session.status` is the single field the whole UI keys off. `paused_quota` + `resume_at` is what makes
the headline feature legible. **No cost or currency column exists anywhere.**

---

## 6. API contract (the UI is built against this)

```
POST   /api/towo/runners/enroll        ← {name} → {enrollmentToken, expiresAt, commands:{npx,installer,docker}}
GET    /api/towo/runners               → [{id,name,status,lastSeenAt,os,sdkVersion,
                                           claudeAuth:"ok"|"missing",allowedRoots[],folders[],
                                           quota:[{type,status,resetsAt,utilization?}]}]
DELETE /api/towo/runners/:id                                   (revoke credential)
GET    /api/towo/sessions?folderId=&status=
POST   /api/towo/sessions              ← {folderId|path, prompt, permissionProfile?, model?, effort?, maxTurns?}
GET    /api/towo/sessions/:id          → record + latest quota context
GET    /api/towo/sessions/:id/messages?cursor=
POST   /api/towo/sessions/:id/messages ← {text}                 (streamInput; starts a turn on an idle session)
POST   /api/towo/sessions/:id/interrupt
POST   /api/towo/sessions/:id/resume
PATCH  /api/towo/sessions/:id          ← {title?, tag?, maxTurns?, continueAfterReset?}
POST   /api/towo/sessions/:id/fork     → {newSessionId}
DELETE /api/towo/sessions/:id
GET    /api/towo/permissions?status=pending
POST   /api/towo/permissions/:id       ← {decision:"allow"|"deny", reason?, always?}
```

**WebSocket `/api/towo/stream`** (server → client), every event carrying `sessionId` where relevant:
`session.status` · `message.assistant` · `message.partial` · `message.tool_result` ·
`permission.requested` · `permission.resolved` · `session.result` · `quota.updated` ·
`runner.status` · `runner.enrolled` · `mirror_error`.

---

## 7. Security requirements

- Root allow-list lives on the runner; the API names a folder, the runner resolves the path.
- Per-runner credential on the transport; enrollment tokens short-lived, single-use, revocable.
- Claude auth never leaves the host; Towo cannot sign in on its behalf and must not try.
- `bypassPermissions` requires a deliberate per-session opt-in and must be visually distinct.
- Alert on `mirror_error` — otherwise transcript loss is silent.

---

## 8. Design (all under `design/app/`)

| Screen | File |
|---|---|
| Settings → Towo tab | `settings.html?tab=towo` (`#sect-towo`) |
| Overview | `towo.html` |
| Session list | `towo-sessions.html` |
| Session view (live, with idle/approval/paused/resumed states) | `towo-session.html` |
| New session composer | `towo-session-new.html` |
| Approvals — desktop queue + phone notification/sheet | `towo-approvals.html` |
| Runners list + detail | `towo-runners.html` |
| Runner enrollment | `towo-runner-new.html` |
| Stopped-early recovery | `towo-limits.html` |
| Towo component styles (scoped under `.towo`, on wf.css tokens) | `towo.css` |
| Sidebar group (flag-gated) | `app-shell.js` |

Rejected direction, recorded: an **attention-queue** overview (one column ordered
Needs you → Resting → Working → Settled, runners demoted to a strip) —
`design/claude-console/Dashboard - Direction B.html`. The machine-floor grouping was chosen because
at this scale (a handful of sessions on 2–3 hosts) "which machine" is the question actually being
asked. Keep Direction B on file if session counts ever grow past a screenful.

---

## 9. Build order

1. ✅ **Done 2026-08-11.** Runner v1 (`towo-runner/`): Agent SDK host + `--root` allow-list + folder
   discovery, one hardcoded session, streaming to stdout, `canUseTool` deny-all — all confirmed live
   against this machine's real Claude Code auth (folder discovery, streamed assistant text, a real
   `Write` call correctly denied and the file confirmed never created).
2. ✅ **Done 2026-08-11**, opportunistically, as part of step 1. Captured a real `rate_limit_event` and
   pinned the parser to its actual shape (§10.2) — every event is now logged verbatim to
   `~/.towo-runner/rate-limit-events.jsonl` on every run, so more real payloads accumulate as the
   runner gets used.
3. ✅ **Done 2026-08-11.** Control plane v1: `towo_runner`/`towo_folder`/`towo_session`/
   `towo_permission_request`/`towo_quota_status` tables (migration 22), `TowoStore`/`TowoRunnerRegistry`/
   `TowoEventBus`/`TowoService`, the runner-link + browser-stream WS endpoints in `Server.kt` (following
   the established Ktor-Native crash-safety + auth patterns per the dev-review addendum), and the REST
   surface in `TowoRoutes.kt`. `compileKotlinLinuxX64` + `linuxX64Test` clean.
4. ✅ **Done 2026-08-11**, backend and UI both. Enrollment: `POST /towo/runners/enroll` mints a
   short-lived single-use token and returns real paste-able `npx`/installer/Docker commands (spec §B);
   `/api/towo/runner-link?token=…` exchanges it for a long-lived credential, sent exactly once over
   `ControlToRunner.Enrolled` — confirmed live: the runner persisted it to `~/.towo-runner/
   credential.json` and reconnected using it (no token) on the very next run. `towo-runner-new.html`'s
   real counterpart (name → command tabs → live "waiting for runner" poll) built and browser-tested.
5. ✅ **Done 2026-08-11.** `towo_transcript_entry` table (migration 23; dedup on `entry_uuid` via a
   partial unique index) plus `transcript_append`/`transcript_load_request`/`transcript_load_response`
   protocol messages. The TS side implements the SDK's real `SessionStore` interface (`append`/`load`),
   relaying both directions over the same runner-link connection — **the in-memory placeholder is gone**,
   `GET /sessions/:id/messages` now reads real durable rows. Verified live: a session's transcript
   (36 real entries — `queue-operation`/`user`/`attachment`/`ai-title`/`assistant`/`last-prompt`, richer
   than the SDKMessage shape, confirming the report's "not 1:1 with SDKMessage" framing) survived a full
   control-plane restart.
6. ✅ **Done 2026-08-11.** `TowoAutoContinueScheduler` — a `rootScope` coroutine loop (default 6h,
   `TOWO_AUTOCONTINUE_INTERVAL_MS` overrides for ops/testing) that finds `paused_quota` + armed +
   past-`resume_at` sessions and sends `ControlToRunner.ResumeSession`. **Deliberately control-plane-
   owned, not runner-owned** — see the dev-review addendum for why this departs from the report's
   original framing. `onSessionResult` now implements spec §8's full state diagram (`error_max_turns` →
   `stopped_max_turns`, `error_during_execution` corroborated against the quota cache → `paused_quota`
   vs `errored`). Verified live: a session forced into `paused_quota` (armed, `resume_at` in the past)
   was picked up within one scheduler tick, `sessionStore.load()` correctly supplied its history to the
   resumed `query({resume: sessionId})` call, and the resumed turn ran a real Claude call and completed
   (`idle`, transcript grew from 9 to 15 entries).
7. ✅ **Done 2026-08-11.** Full remote-approval loop **verified live against a real Claude session,
   twice** — once over raw REST, once **driven entirely through the real browser UI** (Playwright):
   a `Write` call suspended the session (`awaiting_permission`), the approval banner rendered the real
   tool call, clicking **Allow** in the browser sent the real `POST /permissions/:id`, the decision was
   relayed down the runner's own connection, the file was actually written to disk, and the UI updated
   to `idle` live via the WS stream with the banner clearing automatically.
8. ✅ **Done 2026-08-11** — a first functional slice, not full parity with §8's screen table. Built:
   Settings → Towo tab (enable toggle only, localStorage-based like `js-theme`); the sidebar Towo group
   (flag-gated, matching spec §A); Overview (runners + sessions, real data); Runners list + enrollment;
   Sessions list; **the live session view** (WS-driven transcript, inline approval banner, composer,
   interrupt, and §G's recovery affordances — arm-toggle/resume-now/raise-cap/retry — folded into the
   same page rather than a separate `towo-limits.html`); Approvals queue. **Not built**: Settings §A's
   other fields (quota-watch interval, default permission profile/turn cap, notify-preferences — all
   have working backend defaults, just no UI to change them yet), permission **profiles** as a chosen
   concept in the composer (sessions always run in SDK `permissionMode: "default"`), and `send_message`
   (posting into an idle session — needs streaming-input mode, logged as unsupported not silently
   dropped). Verified live end-to-end in a real headless-browser session (Playwright) against the real
   production webpack bundle, not just compiled: Settings toggle applies and persists, sidebar renders
   conditionally, all five pages load real data, enrollment generates and tab-switches real commands,
   and the full approve-a-real-permission click-through works with live status updates.

All 8 steps are now implemented and verified live. What's left is polish and completeness against the
full spec (§4's untouched Settings fields, permission profiles as a real choice, multi-turn composing,
notifications) — not new architecture.

### Live verification, 2026-08-11
Run against an isolated scratch instance (port 19505, throwaway config/DB — the real backend on 9505
was never touched) with the real `towo-runner` and this machine's real Claude Code auth:
1. Enrolled a runner via REST → pasted the real generated `npx` command shape → runner connected,
   received its credential, reconnected on a second run using only the saved credential.
2. Folder discovery (`repo-a` under a scratch root) landed correctly via `GET /runners/:id/folders`.
3. `POST /sessions` with a plain prompt → real Claude session ran end to end → `GET /sessions` showed
   `status: idle, numTurns: 1` → `GET /sessions/:id/messages` returned the real transcript (`rate_limit_
   event`, `system`, `assistant`, `result`) → quota utilization ticked from 0.83 to 0.85 between calls,
   `rateLimitType: seven_day` (still, correctly, not `five_hour`).
4. `POST /sessions` with a prompt requiring a `Write` → session correctly went `awaiting_permission` →
   `GET /permissions?status=pending` showed the real tool call (`file_path`, `content`) → approving it
   via REST caused the file to actually be written and the session to resume to `idle`.

### Two real bugs found only by testing live, not by compiling

Both were the exact same root cause, in two different places — worth naming as a pattern, not just two
isolated fixes: **the Agent SDK's options objects react badly to a key that is explicitly present with
an `undefined`/`null` value, as opposed to the key being absent.** `tsc --noEmit` cannot catch this —
the types allow both, and the runtime behaves differently. Every options object passed to `query()` (or
returned from `canUseTool`) needs its optional keys built conditionally (spread-if-defined), never
passed as `key: possiblyUndefinedValue`.

1. **`startManagedSession`'s `query()` call silently produced a `Query` that never emitted a single
   message** — no error, no thrown exception, just total silence — when `maxTurns`/`resume` were passed
   as explicit `undefined` in the options object (both fields are `undefined` on every fresh, non-resumed
   session, which is the common case, so this broke *every* session start). Fixed by only including keys
   with real values.
2. **A resumed session hung forever in `running` after an approval**, with no further messages, no
   error, nothing — found only because a live browser test happened to leave a session running long
   enough to notice. Root cause: `canUseTool`'s resolved `PermissionResult` for the allow case included
   `updatedInput: undefined` explicitly. Removing the key (`{ behavior: "allow" }` alone) fixed it
   immediately — re-tested with a real approval and the file was written, session reached `idle`.

Also found and fixed the same session: `updateSessionHeader` cast the composer's `<button id="towo-sess-
send">` to `HTMLInputElement` to set `.disabled` — a real `ClassCastException` (browsers/Kotlin-Wasm
buttons aren't inputs), silently aborting the rest of `loadSession()` **before** it ever reached the
permission-banner fetch, which is why the approval banner didn't render on first load even though the
backend genuinely had the pending request. Caught via `page.on('pageerror')` in the Playwright script —
`page.on('console')` alone missed it, since an uncaught Kotlin/Wasm coroutine exception surfaces as a
page error, not a console.error. Fixed by using the button's own `.disabled` property directly.

---

## 10. Open questions (must be settled before building the marked parts)

1. ~~**`canUseTool`'s signature differs between doc sources**~~ **RESOLVED 2026-08-11.** Pinned to
   `@anthropic-ai/claude-agent-sdk@0.3.227`, read directly from its `sdk.d.ts`, and confirmed live via
   `towo-runner` v1: `(toolName, input, {signal, suggestions?, blockedPath?, decisionReason?, title?,
   displayName?, description?, toolUseID, agentID?, requestId}) => PermissionResult | null`. `title`/
   `displayName`/`requestId` are real and populated exactly as documented — confirmed live against a
   `Write` call. **Design upgrade found in the process**: returning `null` tells the SDK the
   `control_response` was already sent out-of-band (echoing `requestId`) — this is a first-class
   supported mechanism for §D's remote/phone approval, not something the runner needs to fake by
   holding the callback's promise open. **No built-in timeout** — an accidental `null` with nothing
   sent leaves the tool blocked forever; §D's "long timeout that denies" is entirely the runner's own
   responsibility. Full facts: [[reference-claude-agent-sdk-facts]] memory / `towo-runner/README.md`.
2. ~~**`rate_limit_event` is undocumented in the official reference**~~ **RESOLVED 2026-08-11.**
   Confirmed real, camelCase (`resetsAt`/`rateLimitType`, not the snake_case first guessed from
   WebSearch), part of the stable `SDKMessage` union, and captured live from a real session:
   `{"status":"allowed_warning","resetsAt":1786604400,"rateLimitType":"seven_day","utilization":0.83,
   "isUsingOverage":false,"surpassedThreshold":0.75}`. `resetsAt` is unix-seconds; `utilization` is a
   **0–1 fraction, not 0–100** (correction from the first pass). This capture is itself a live
   confirmation that the currently-active bucket is `seven_day`, not `five_hour` — the "never hardcode
   5 hours" principle in §E is not hypothetical for this account. A separate, richer per-bucket
   snapshot method now exists (`usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET`) but is
   explicitly marked unstable by its own name — §E's quota widgets should stay built on the pushed
   `rate_limit_event`, not that method, until it graduates out of the experimental name.
3. **Subscription vs API key** (§A) — decides whether §E exists at all.
4. **Unattended OAuth on a headless host.** Subscription auth is an interactive login; how it behaves
   over long periods on a server (refresh/expiry) is a genuine operational unknown and a plausible
   3am failure.
5. Does Towo belong in this admin app long-term, or should it graduate to its own deployment once the
   runner exists? The feature flag keeps that door open.

## Relationships
**Standalone within this repo.** Depends on no phase and is depended on by none. Only shared
surfaces are `app/app-shell.js` (one flag-gated nav group) and `app/settings.html` (one tab).

---

## Dev-review addendum (2026-08-11 — backend reality check before implementation starts)

Reviewed against the real Ktor/linuxX64 backend and the wasmJs build pipeline, not just the design
mockups. Three items are build-config gaps that have bitten this exact class of feature before and
are fixed here pre-emptively; two are protocol details the design left implicit; two are new open
questions.

**1. `towo.css` is not in `syncDesignAssets`'s copy list — would ship with zero styling.**
`build.gradle.kts`'s `syncDesignAssets` task (used by `runDev`) and the `wasmJsBrowserDistribution`
`doLast` copy (used by Docker/production) both hardcode the same file list:
`include("wf.css", "app.css", "detail.css", "metadata.css", "seeding.css", "seeding.js")`
(`build.gradle.kts:267` and `:296`). `towo.css` is a **new** file and isn't in either list. This is
the exact same gap that silently shipped Settings' age-rating cascade editor and the dashboard
attention-breakdown grid with **zero CSS** in past phases (see the restoration comments in
`design/app/wf.css` itself). **Fix required at build time, in both places** — missing one causes a
dev/prod split where Towo looks fine locally and unstyled in the Docker image.

**2. The runner↔control-plane WebSocket has no name or auth story in §6.** The API contract lists
REST + the browser-facing `/api/towo/stream`, but §3's "runner dials out and keeps a reconnecting
WebSocket open" names no endpoint. Needs an explicit route (e.g. `webSocket("/api/towo/runner")`)
authenticated by the **runner's own long-lived credential** (§B), never the admin session cookie —
mirroring how `/api/tv/events` authenticates TV devices on a separate identity from the admin app
(`Server.kt:470`, via `tvEventBus.tryRegister`).

**3. Both new WS handlers must follow this codebase's existing crash-safety shape from the first
commit, not as a follow-up hardening pass.** An exception escaping a Ktor Native `webSocket{}` block
crashes the *entire process* — not just that connection — on this server (a real prior incident, not
a theoretical concern). The fix is already established practice here: `Server.kt:434` (`/ws`) and
`Server.kt:470` (`/api/tv/events`) both wrap their whole read loop in
`try { … } catch (e: CancellationException) { … } catch (e: Throwable) { … }`. `/api/towo/stream` and
the new runner-transport endpoint (item 2) must be written the same way from day one — a Towo session
misbehaving must never be able to take down media playback for every other user.

**4. The runner's outbound connection needs an explicit infinite/long timeout — the shared
`HttpClient` default will silently truncate it.** This codebase hit this exact bug once already:
the shared client's blanket `HttpTimeout` (10s, meant for ordinary REST calls) also quietly bounded
`/api/tv/events`, a long-lived WS, causing TVs to reconnect and drop playback roughly every 10
seconds until a per-request `INFINITE_TIMEOUT_MS` override was added. The runner's connection to the
control plane is architecturally identical — a long-lived socket riding infrastructure sized for
short calls — and needs the same explicit override from the start, on whichever client library the
runner daemon uses.

**5. Confirmed: the runner must stay a fully separate process from `linuxX64Main`, never folded in
"for simplicity."** §3 already implies this and nothing here changes that — recorded as an explicit
guard rail because the failure mode is a known one on this backend: ffmpeg/ffprobe `popen()` calls
once shared the same dispatcher as API request-handling and could stall the entire admin site under
load, fixed by moving them to their own thread pool. One `claude` CLI subprocess per session is the
same shape of risk (long-lived, I/O-heavy child processes) and must never compete with jellystructure's
own request handling for a dispatcher.

**6. `quota_status` and the live message stream must not be built as rewrite-the-whole-file-per-event
persistence.** `rate_limit_event` fires continuously per §E, and the session view streams per-turn —
both are high-frequency-write shapes. This codebase already found, the hard way, that an unthrottled
full-file rewrite per log line (`ActivityLog.log()`) was the single dominant cause of a real
site-wide slowness incident during a heavy scan. Whatever backs `quota_status` and the live
transcript needs append/indexed writes, not that pattern.

**7. Towo's REST/WS routes sit behind the existing admin-session auth, not a new auth layer.** §2
already says "same session as the admin app" as a non-goal boundary (not multi-user); this just makes
explicit that `/api/towo/*` should reuse the same auth middleware protecting the rest of `/api/*`
rather than anything bespoke.

**8. Permission-decision delivery path made explicit, plus a genuinely unaddressed edge case.** §D
describes the runner suspending `canUseTool` and awaiting a decision, but not how the decision
physically gets there: a phone's `POST /api/towo/permissions/:id` must be forwarded down the *same*
already-open runner-transport connection (item 2), keyed by session + request id, to unblock that
specific suspended call — it cannot be resolved control-plane-side, since the SDK process only exists
on the runner. **Left open by the design:** if the runner disconnects while a request is pending
(host reboot, network blip), should that request keep counting down the same long ignored-timeout as
"nobody answered," or fail immediately since nothing can be delivered once the runner is gone? These
read very differently in the UI (a countdown vs. an instant "runner dropped" state) — needs a design
decision, not just a backend default.

**9. New open question: what does an auto-continue actually resume?** Report §8 frames resume as
`query({ prompt: <continuation>, options: { resume: sessionId, … } })` — i.e. conventionally paired
with a *new* turn, not a bare reconnect. Neither the data model (§5) nor §E specify what continuation
text an armed auto-continue sends, and it's not yet known whether a quota cutoff lands *mid-turn*
(interrupting an in-flight tool call, which "picks up where it left off" would need to literally
resume) or always after the current turn finishes cleanly (in which case auto-continue only needs to
send something to start the *next* turn). This changes both correctness and the UX copy in §E/§G, and
can only be answered by capturing a real quota cutoff — bundle it into the existing build-order step 2
(`specs/requirements/phase-162-towo-agent-control-plane.md` §9) rather than treating it as a separate
investigation.
