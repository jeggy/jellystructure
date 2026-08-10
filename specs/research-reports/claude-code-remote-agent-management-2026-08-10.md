# Research report — a self-hosted remote control plane for Claude Code

**Date:** 2026-08-10 (substantially revised same day after documentation research — the first draft
was written from in-session knowledge alone and several of its assumptions turned out to be wrong;
see §1.)
**Status:** Research only — not a spec. Design brief for a Cosmos design-tool pass on a **new,
standalone product** (a personal Claude Code management website), unrelated to jellystructure's own
feature set. Placed here only because this repo is the established research-report → design-tool
hand-off channel.

**Goal of this revision:** make the backend design concrete enough that the remaining work is
genuinely "put a UI on top" — §7 specifies the exact API contract the UI consumes, §8 the exact
auto-resume state machine, §10 the screen inventory.

---

## 1 — What changed after reading the docs

Three findings from the first draft were wrong or unverifiable; all three are now settled:

| First draft said | Verified reality |
|---|---|
| Claude Code's **Remote Control** might be embeddable in a custom site — "worth a spike" | **It is not.** Remote Control is Anthropic-built, tied to claude.ai auth, and explicitly not a public API for third-party apps. But it's a real, capable product in its own right — see §2, because it may make building anything unnecessary. |
| Quota exhaustion detection "needs verifying — don't guess at the event shape" | **Settled.** A `rate_limit_event` message carries `status`, `resetsAt` (unix epoch), and `rateLimitType` (`five_hour` / `seven_day` / …). This is the exact primitive auto-resume needs. See §5. |
| Control plane needs "its own database indexing sessions" and would read Claude's JSONL files | **Mostly unnecessary.** The SDK exposes first-class `listSessions()` / `getSessionMessages()` / `getSessionInfo()` / `renameSession()` / `tagSession()` / `forkSession()` / `deleteSession()`. And the on-disk JSONL format is **explicitly not a stable interface** ("changes between versions, scripts that parse these files directly can break on any release") — so the earlier instinct to parse it was actively wrong. |

One further finding removes a whole layer from the original design: **`SessionStore`**, an official
adapter interface for mirroring transcripts to S3/Redis/Postgres, which makes "resume a session on a
different machine than the one that started it" a supported first-class feature rather than
something to engineer. See §4.3.

---

## 2 — Decide this first: do you need to build anything?

**Claude Code ships a server mode that already does most of the literal ask.**

```bash
claude remote-control --capacity 32 --spawn same-dir
```

Verified properties:
- Runs on **your** machine against **your** real checkouts; code execution stays local.
- **HTTPS outbound only** — no inbound ports, no NAT/firewall work. This solves the "webserver in
  Docker on a different machine" problem outright.
- Drives **multiple concurrent sessions** (`--capacity`, default 32), and `--spawn` chooses whether
  each new session lands in the same directory, a fresh git worktree, or a single shared session.
- Resumable: `-c` / `--session-id`.
- Optional `--sandbox` for filesystem/network isolation.

**The catch, and the whole decision:** the UI is claude.ai / the Claude mobile apps. You cannot point
your own website at it. Also requires a claude.ai subscription (Pro/Max/Team/Enterprise) — **not
available with an API key**, and not on Bedrock/Vertex/Foundry. Transcripts are stored on Anthropic's
servers during a Remote Control session (execution stays local).

So:

- **If the real need is "control my agents from my phone/anywhere"** → Remote Control already does it,
  today, for zero engineering. Try it before building.
- **If the real need is genuinely your own UI** — custom dashboards, your own auth, workspace
  grouping, embedding it in an existing site, automation beyond what the official client exposes —
  → build on the **Agent SDK**, which is what the rest of this report specifies.

Worth stating plainly because the rest of this document is a meaningful amount of work, and a short
experiment could make it unnecessary.

---

## 3 — Verified capability inventory

Everything below is doc-verified; §11 lists what remains uncertain.

### 3.1 The Agent SDK is the integration surface (not CLI scraping)
TypeScript and Python packages that spawn and supervise a `claude` CLI subprocess over stdio and
expose it as a typed async stream. One session = one subprocess = its own working directory and
transcript.

`query({ prompt, options })` returns an async iterable. Options relevant here:

| Option | Purpose |
|---|---|
| `cwd` | **The workspace root** — this is how you get `~/` vs `~/IdeaProjects/jellystructure` |
| `resume` / `continue` / `forkSession` / `sessionId` | Session lifecycle |
| `sessionStore` | Mirror transcripts to your own backend (§4.3) |
| `canUseTool` | **Async permission callback** — the remote-approval primitive (§9) |
| `permissionMode` | `default` / `acceptEdits` / `plan` / `dontAsk` / `bypassPermissions` / `auto` |
| `allowedTools` / `disallowedTools` | Per-session allow/deny rules, supports scoping like `Bash(npm *)` |
| `hooks` | `PreToolUse`, `PostToolUse`, `Stop`, `SubagentStart/Stop`, `PreCompact`, … |
| `agents` | Define subagents programmatically |
| `maxTurns`, `maxBudgetUsd` | Runaway protection — **`maxBudgetUsd` covers subagent spend too** |
| `includePartialMessages` | Token-level deltas for live UI streaming |
| `env`, `settingSources`, `additionalDirectories` | Isolation / config control |

Returned `Query` object also exposes `interrupt()`, `setPermissionMode()`, `setModel()`,
`streamInput()`, `getContextUsage()`, `accountInfo()`, `close()`.

### 3.2 Session management is a first-class API
`listSessions()`, `getSessionMessages()`, `getSessionInfo()`, `renameSession()`, `tagSession()`,
`deleteSession()`, `forkSession()`, `listSubagents()`, `getSubagentMessages()` — all accept a
`sessionStore`, so they work against your backend, not just local disk. **Build session pickers and
transcript viewers on these, never on the JSONL files.**

Session IDs come from `ResultMessage.session_id`, and (TS) from the init `SystemMessage.session_id`.

### 3.3 Subagents are native — don't build orchestration
A session spawns and manages its own subagents (`Agent` tool). Subagent transcripts are separately
addressable (`listSubagents()` / `subpath: "subagents/agent-<id>"`). The control plane's job is
session lifecycle, **not** fan-out.

### 3.4 Built-in retry already handles transient 429s
Transient 429s / 5xx / timeouts are retried automatically (default 10 attempts, `CLAUDE_CODE_MAX_RETRIES`,
`CLAUDE_CODE_RETRY_WATCHDOG=1` for infinite retry in CI), emitting
`{type:"system", subtype:"api_retry", attempt, max_retries, retry_delay_ms, error_status, error}`.
**This is a different thing from subscription quota exhaustion** — do not build backoff for it, and
do not confuse the two in the state machine (§8).

---

## 4 — Architecture

Three tiers. The split exists because the control plane and the machine holding the checkouts are
explicitly allowed to be different machines on different networks.

```
┌──────────────────────────┐       ┌───────────────────────────┐       ┌──────────────────────┐
│  Control Plane            │       │  Runner Daemon             │       │  claude CLI          │
│  — your website + API     │◄─────►│  — one per host with       │◄─────►│  subprocesses        │
│  — session/runner index   │  (A)  │    project checkouts       │  (B)  │  (1 per session)     │
│  — auth, notifications    │ WS    │  — Agent SDK host          │ stdio │                      │
│  — SessionStore backend   │       │  — outbound-only           │       │                      │
└──────────────────────────┘       └───────────────────────────┘       └──────────────────────┘
      Docker / cloud                    dev box, home server, …            same host as runner
```

### 4.1 Runner daemon — the only new "hard" component
A long-lived Node/Python service (systemd unit, or Docker with the host filesystem mounted). It:
- Holds a **workspace allow-list** mapping names → real paths (`jellystructure` → `/home/jeggy/IdeaProjects/jellystructure`).
  The control plane never sends raw filesystem paths (§9.3).
- Owns Claude Code auth locally (`claude /login` for subscription, or `ANTHROPIC_API_KEY`).
- Calls `query()` per session with `cwd` set from the allow-list; keeps the `Query` handle in a map
  keyed by session ID so `streamInput()` / `interrupt()` / `setPermissionMode()` work later.
- Forwards every SDK message up the (A) channel; forwards `canUseTool` requests up and awaits the
  decision (§9).
- Runs the **auto-resume state machine** (§8) — it's closest to the process, so it owns detection
  and scheduling, and reports transitions up rather than being polled.

### 4.2 Transport (A) — outbound WebSocket from runner → control plane
The runner dials **out** and keeps a reconnecting WebSocket open. The machine with your code never
needs an inbound port. Same shape as CI runners. Multiplex by `{runnerId, sessionId}`.

*(Alternative, likely faster for this specific user: both ends already sit on a WireGuard mesh with
static `10.10.10.x` addressing, so the control plane could just call the runner's private IP
directly. Fewer moving parts; costs portability if a runner ever lives somewhere the VPN can't
reach. The WebSocket design is the portable default.)*

### 4.3 SessionStore — make transcripts control-plane-owned
Implement the `SessionStore` interface against the control plane's database (Postgres reference
adapter exists) and pass it to every `query()`:

```typescript
type SessionKey = { projectKey: string; sessionId: string; subpath?: string };

type SessionStore = {
  append(key: SessionKey, entries: SessionStoreEntry[]): Promise<void>;   // required
  load(key: SessionKey): Promise<SessionStoreEntry[] | null>;             // required
  listSessions?(projectKey: string): Promise<Array<{ sessionId: string; mtime: number }>>;
  listSessionSummaries?(projectKey: string): Promise<SessionSummaryEntry[]>;
  delete?(key: SessionKey): Promise<void>;
  listSubkeys?(key: { projectKey: string; sessionId: string }): Promise<string[]>;
};
```

Why this matters a lot here:
- Transcripts land in **your** database, so the website can render history without round-tripping to
  the runner — and history survives the runner being offline.
- A session started on runner A can be resumed on runner B.
- It's a **mirror, not a replacement**: the subprocess still writes local disk first. Failed mirror
  batches retry 3× then emit `{type:"system", subtype:"mirror_error"}` and are dropped — **alert on
  that**, it's silent transcript loss otherwise.
- Constraints: cannot combine with `persistSession: false` (TS throws) or `enableFileCheckpointing`.
  Deduplicate by `entry.uuid` in `append()` (retries can re-deliver).

---

## 5 — Quota exhaustion: the actual mechanism

This is the heart of the ask, and it's now concrete.

### 5.1 The signal
Claude Code emits a **`rate_limit_event`** message. Verified field shape (community-reported from
real payloads; the type exists in the SDK as `SDKRateLimitInfo` / `SDKRateLimitEvent` since Claude
Code v2.1.45 but is **not yet in the official reference docs** — see §11):

```json
{
  "type": "rate_limit_event",
  "rate_limit_info": {
    "status": "allowed",
    "resetsAt": 1729281600,
    "rateLimitType": "five_hour"
  },
  "uuid": "..."
}
```

Fields observed: `status`, `resetsAt` (unix epoch seconds), `rateLimitType`, `utilization`,
`is_using_overage`, `overage_status`, `overage_resets_at`, `overage_disabled_reason`.

Two things to know:
- **It fires on every session, not only when you're limited** — it's an informational status event.
  So the runner should treat it as a *continuously updated quota gauge*, not an error. Cache the
  latest one per runner; that alone drives a live "quota remaining / resets at" widget in the UI.
- `utilization` is reportedly **only populated after crossing a warning threshold** (a request to
  always include it, plus a richer per-bucket `rate_limits_snapshot` message, was **closed as not
  planned**). So a precise always-on percentage gauge is not currently available — design the UI to
  degrade to "resets at HH:MM" when `utilization` is absent.

### 5.2 Bucket types
`five_hour`, `seven_day`, `seven_day_opus`, `seven_day_sonnet`. **The user's "5 hours" assumption is
right for the session bucket — but there is also a weekly bucket**, which matters: an auto-resume
loop that only understands the 5-hour window will spin pointlessly against a 7-day limit. Key the
scheduler off the returned `rateLimitType` + `resetsAt`, never off a hardcoded 5h.

### 5.3 Never hardcode the window
`resetsAt` is authoritative. Schedule from it (plus a small buffer). Only fall back to a computed
window if the field is somehow absent.

### 5.4 Auth model determines whether any of this applies
- **Subscription (Pro/Max) via `claude /login`** → session + weekly allowances → `rate_limit_event`
  with reset times → the auto-resume design applies.
- **API key (pay-per-token)** → no session/weekly allowance; 429s are ordinary rate limits already
  handled by built-in retry (§3.4) → auto-resume is largely moot.

**This is a real fork in the design and should be confirmed before building the scheduler.** Note
also that Remote Control (§2) requires a subscription, so if that path is chosen, subscription is
implied.

---

## 6 — Data model (control plane)

Deliberately thin — session *content* lives in the SessionStore tables; this is the index.

```
runner        id, name, host_label, status(online|offline), last_seen_at, auth_mode, agent_sdk_version
workspace     id, runner_id, name, abs_path, default_permission_profile, created_at
session       id (= Claude session_id), runner_id, workspace_id, title, tag,
              status(starting|running|idle|awaiting_permission|paused_quota|errored|done),
              resume_at (nullable), last_error_subtype, total_cost_usd, num_turns,
              created_at, last_activity_at
permission_request  id, session_id, tool_name, input_json, requested_at,
                    decided_at, decision(allow|deny|timeout), decided_by, reason
quota_status  runner_id, rate_limit_type, status, resets_at, utilization (nullable), observed_at
event_log     id, session_id, seq, type, payload_json, created_at    -- optional; SessionStore already has the transcript
```

`session.status` is the single field the whole UI keys off. `paused_quota` + `resume_at` is what
makes the headline feature legible.

---

## 7 — Control-plane API (the contract the UI is built against)

This is the "just needs a UI on top" layer — design can be done against this without the backend
existing.

### REST
```
GET    /api/runners                          → [{id, name, status, lastSeenAt, quota:{type,status,resetsAt,utilization}}]
GET    /api/workspaces                       → [{id, runnerId, name, path}]
POST   /api/workspaces                       ← {runnerId, name}         (validated against runner allow-list)

GET    /api/sessions?workspaceId=&status=    → [{id, title, workspace, status, resumeAt, lastActivityAt, costUsd}]
POST   /api/sessions                         ← {workspaceId, prompt, permissionProfile?, model?, effort?, maxTurns?, maxBudgetUsd?}
GET    /api/sessions/:id                     → full session record + latest quota context
GET    /api/sessions/:id/messages?cursor=    → paginated transcript      (getSessionMessages)
POST   /api/sessions/:id/messages            ← {text}                    (streamInput → live session)
POST   /api/sessions/:id/interrupt
POST   /api/sessions/:id/resume                                          (manual override of a paused/idle session)
POST   /api/sessions/:id/fork                → {newSessionId}
PATCH  /api/sessions/:id                     ← {title?, tag?}            (renameSession / tagSession)
DELETE /api/sessions/:id
GET    /api/sessions/:id/subagents            → [{id, name, status}]     (listSubagents)
GET    /api/sessions/:id/subagents/:sid/messages

GET    /api/permissions?status=pending       → [{id, sessionId, toolName, input, requestedAt}]
POST   /api/permissions/:id                  ← {decision:"allow"|"deny", reason?}
```

### WebSocket `/api/stream` (server → client)
One multiplexed stream; every event carries `sessionId`.

| Event | Payload | UI meaning |
|---|---|---|
| `session.status` | `{status, resumeAt?, errorSubtype?}` | drive the status chip |
| `message.assistant` | text + tool-call blocks | a turn happened |
| `message.partial` | text delta | live typing (needs `includePartialMessages`) |
| `message.tool_result` | result content, `is_error` | tool-result card |
| `permission.requested` | `{requestId, toolName, input}` | **raise an approval card / push notification** |
| `permission.resolved` | `{requestId, decision}` | clear the card |
| `session.result` | `{subtype, costUsd, numTurns}` | turn/session finished |
| `quota.updated` | `{runnerId, type, status, resetsAt, utilization?}` | quota widget |
| `runner.status` | `{runnerId, status}` | runner online/offline |
| `mirror_error` | `{sessionId}` | transcript-durability warning (§4.3) |

---

## 8 — Auto-resume state machine (runner-owned)

```
                    ┌──────────┐
      POST /sessions│ starting │
                    └────┬─────┘
                         ▼
   ┌───────────────► running ──────────────────────────────┐
   │                   │  │                                │
   │   canUseTool      │  │ rate_limit_event               │ ResultMessage
   │        ▼          │  │ (status != allowed)            ▼
   │ awaiting_permission│ ▼                          ┌──────────────┐
   │        │          │ paused_quota                │  subtype?    │
   │  decision (or     │  (store resetsAt)           └──────┬───────┘
   │   timeout=deny)   │      │                             │
   └────────┘          │      │ wake at resetsAt + buffer    ├─ success ────────► idle/done
                       │      └──────► resume(sessionId) ────┤
                       │                                     ├─ error_max_turns ──► idle (offer resume w/ higher cap)
                       │                                     ├─ error_max_budget_usd ► idle (offer raise budget)
                       │                                     └─ error_during_execution ► check quota cache:
                       │                                            quota-implicated? → paused_quota
                       └────────────────────────────────────────────  otherwise      → errored
```

Implementation notes that matter:

1. **`rate_limit_event` is the primary signal, `ResultMessage` is corroboration.** Cache the latest
   `rate_limit_info` per runner; when a session ends `error_during_execution`, consult that cache to
   decide `paused_quota` vs `errored`. Don't string-match stderr.
2. **Never retry-loop `errored`.** Only `paused_quota` reschedules. `error_max_turns` /
   `error_max_budget_usd` are *user decisions*, not automatic retries — surface them with a
   one-click "resume with a higher limit."
3. **Resume = `query({ prompt: <continuation>, options: { resume: sessionId, sessionStore, cwd } })`**
   from the same workspace. With `sessionStore` attached, this need not be the same runner.
4. **Buffer the wake** (a minute or two past `resetsAt`) and re-check the quota event on resume
   rather than assuming.
5. **Notify on both edges.** A multi-hour silent gap with no "it came back" confirmation defeats the
   point of automating it.
6. **`worker_shutting_down`** (a real `SystemMessage` subtype) means the host is exiting or Remote
   Control disconnected — treat as a clean pause, not an error.

---

## 9 — Remote permission approval

The single nicest thing the SDK gives this design: **`canUseTool` is async and receives an
`AbortSignal`** — so the runner can suspend the tool call, push it to the website, and await your tap
on a phone.

```typescript
canUseTool: async (request, { signal }) => {
  const id = await controlPlane.raisePermission(request);      // emits permission.requested over WS
  const decision = await controlPlane.awaitDecision(id, { signal, timeoutMs: ... });
  return decision === "allow" ? { allow: true } : { deny: true, reason: decision.reason };
}
```

### 9.1 The trap to design around
**Auto-approved tools never reach `canUseTool`.** A bare `allowedTools: ["Bash"]` silently bypasses
your entire approval UI for every Bash call. Two correct patterns:

- **`ask` rules in `settings.json`** force a fall-through to `canUseTool` *even in
  `bypassPermissions`* — this is the mechanism for "auto-approve the safe stuff, always ask me about
  the risky stuff."
- **`PreToolUse` hook** runs before *everything* and can deny even in `bypassPermissions` — the
  belt-and-braces option if a check must run on literally every call.

### 9.2 Recommended per-workspace "permission profile"
A named preset the UI exposes (this is a real design surface, not just config):

| Profile | Composition |
|---|---|
| **Read-only** | `allowedTools: [Read, Glob, Grep]`, `permissionMode: "dontAsk"` |
| **Normal** (default) | scoped allows (`Bash(npm test)`, `Bash(git status)`, `Edit(src/**)`), `ask` rules on writes/pushes, `permissionMode: "default"` → everything else prompts remotely |
| **Autonomous** | `acceptEdits` + `ask` rules on destructive Bash + `maxBudgetUsd` set |
| **Unrestricted** | `bypassPermissions` — should require an explicit, deliberate opt-in in the UI |

### 9.3 Other security requirements
- **Workspace allow-list lives on the runner.** The API takes a workspace *name*; the runner resolves
  the path. A compromised control plane can't point a session at `/etc`.
- Per-runner credential on transport (A); mTLS if it's a public relay rather than the private mesh.
- Claude auth stays on the runner — the control plane never stores or forwards it.
- Multi-tenant isolation knobs exist if ever needed: `settingSources: []`,
  `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`, per-tenant `CLAUDE_CONFIG_DIR`, per-tenant `cwd`.

---

## 10 — UI surface inventory (what design actually needs to produce)

1. **Dashboard** — runners (online/offline), quota widget per runner (`resets at HH:MM`, utilization
   when present), sessions grouped by workspace, count of pending approvals.
2. **Workspace list / picker** — add a workspace, choose one to start a session in.
3. **New-session composer** — workspace, prompt, permission profile, model/effort, optional
   `maxTurns` / `maxBudgetUsd`.
4. **Session view** (the main screen) — chat-style transcript from the WS event stream:
   text blocks, tool-call cards (name + collapsed input), tool-result cards (collapsed, error state),
   subagent progress, cost/turn footer, a message composer, and an interrupt button.
5. **Status chips** — `running` / `idle` / `awaiting permission` / **`paused: quota — resumes 18:42`** /
   `errored` / `done`. The paused-quota state deserves genuine design attention; it's the headline
   feature and must not read as "broken."
6. **Approval card / modal** — tool name, human-readable input summary, Allow / Deny (+ reason),
   ideally reachable from a push notification without opening the full session.
7. **Session list** — filter by workspace/status, resume / fork / rename / tag / delete.
8. **Limit-hit recovery affordances** — "resume with more turns" / "raise budget" for
   `error_max_turns` / `error_max_budget_usd`.
9. **Notification settings** — which transitions notify, and where.

---

## 11 — Open questions and known risks

1. **`canUseTool` signature differs between sources.** The current TS reference documents
   `(request, {signal}) => {allow:true} | {deny:true,reason} | {prompt:true}`, while other doc pages
   (and the Python SDK) show `(toolName, input, {signal, suggestions}) => {behavior:'allow'|'deny',
   updatedInput?, message?}`. Almost certainly an SDK-version difference. **Pin the SDK version and
   verify against its own `.d.ts` before writing this callback** — it's the most important integration
   point in the design.
2. **`rate_limit_event` is undocumented in the official reference** (an open docs issue tracks
   exactly this). Field names are community-verified from real payloads, not from Anthropic docs, and
   may drift. **First build task should be: capture one real event end-to-end and pin the parser to
   its actual shape.** Also note a known Python-SDK bug class where `rate_limit_event` caused
   `MessageParseError` and terminated the message generator in some versions — check the SDK
   changelog for the version you pin.
3. **Billing model** (§5.4) — confirm subscription vs API key before building the scheduler.
4. **Auth for a headless runner on a subscription.** Subscription auth is an OAuth login
   (`claude /login`), not an env var. Confirm how that behaves unattended on a server over long
   periods (token refresh/expiry) — this is a genuine operational unknown and a plausible source of
   3am failures.
5. **Multi-user?** Reads as a personal tool. If it's ever more than one person, workspace/auth
   isolation must be designed in from the start, not retrofitted.
6. **Resource sizing** — docs suggest ~1 GiB RAM / 5 GiB disk / 1 CPU per concurrent agent as a
   *floor*; memory grows with session length. For horizontal scale, pin sessions to a runner via
   consistent hashing on `sessionId`. Almost certainly irrelevant at personal scale, but it bounds
   `--capacity`-style limits.

---

## 12 — Suggested build order

1. **Spike Remote Control (§2)** — half a day. It may end the project.
2. **Runner v1**: SDK host + workspace allow-list + one session, streaming to stdout. No control
   plane yet.
3. **Capture a real `rate_limit_event`** and pin the parser (§11.2).
4. **Control plane v1**: outbound WS, session index, `/api/sessions` + `/api/stream`.
5. **`SessionStore` (Postgres)** — unlocks transcript history in the website and cross-runner resume.
6. **Auto-resume state machine** (§8) + notifications.
7. **`canUseTool` remote approval** (§9) — the piece that makes unattended operation actually safe.
8. UI, against the §7 contract.

---

## Source references
All URLs verified 2026-08-10.

- Agent SDK TypeScript reference (options, `Query` methods, session functions):
  https://code.claude.com/docs/en/agent-sdk/typescript
- Sessions — resume/continue/fork, on-disk layout, cross-host caveats:
  https://code.claude.com/docs/en/agent-sdk/sessions
- Session storage — `SessionStore` interface, S3/Redis/Postgres adapters, `mirror_error`:
  https://code.claude.com/docs/en/agent-sdk/session-storage
- Permissions — six-step evaluation order, modes, `ask` rules, the auto-approve trap:
  https://code.claude.com/docs/en/agent-sdk/permissions
- Agent loop — message types, `ResultMessage` subtypes, compaction, hooks:
  https://code.claude.com/docs/en/agent-sdk/agent-loop
- Hosting — subprocess model, session patterns, sizing, multi-tenant isolation:
  https://code.claude.com/docs/en/agent-sdk/hosting
- Remote Control — flags, transport, subscription requirement, not-a-public-API:
  https://code.claude.com/docs/en/remote-control
- Errors / retries — quota messages, `api_retry`, `CLAUDE_CODE_MAX_RETRIES`:
  https://code.claude.com/docs/en/errors
- Headless CLI — `stream-json` event shapes, flags:
  https://code.claude.com/docs/en/headless
- `rate_limit_event` field shapes + the docs gap:
  https://github.com/anthropics/claude-code/issues/26392 ·
  https://github.com/anthropics/claude-code/issues/50518 (per-bucket snapshot: closed, not planned) ·
  https://github.com/anthropics/claude-agent-sdk-python/issues/689 (MessageParseError bug class)
- User's existing infra referenced in §4.2: this project's own memory
  (`reference-pixel9-vpn-adb-pairing`, `reference-backend-deployment`) — WireGuard VPN, UniFi private
  network, `10.10.10.x` static addressing, an always-on dev host.

## Relationships
**Standalone** — does not extend, correct, or depend on any jellystructure phase or spec. It lives
here only as the agreed hand-off point to the Cosmos design-tool session for the UI pass described in
§10. Resulting design work belongs in its own project, not jellystructure's `design/` tree.
