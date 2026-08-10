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
| Claude Code's **Remote Control** might be embeddable in a custom site — "worth a spike" | **It is not**, and this is now a closed question: it's Anthropic-built, claude.ai-auth-bound, and explicitly not a public API. A custom website can never attach to a `remote-control` session. The Agent SDK is the supported path and loses nothing — see §2. |
| Quota exhaustion detection "needs verifying — don't guess at the event shape" | **Settled.** A `rate_limit_event` message carries `status`, `resetsAt` (unix epoch), and `rateLimitType` (`five_hour` / `seven_day` / …). This is the exact primitive auto-resume needs. See §5. |
| Control plane needs "its own database indexing sessions" and would read Claude's JSONL files | **Mostly unnecessary.** The SDK exposes first-class `listSessions()` / `getSessionMessages()` / `getSessionInfo()` / `renameSession()` / `tagSession()` / `forkSession()` / `deleteSession()`. And the on-disk JSONL format is **explicitly not a stable interface** ("changes between versions, scripts that parse these files directly can break on any release") — so the earlier instinct to parse it was actively wrong. |

One further finding removes a whole layer from the original design: **`SessionStore`**, an official
adapter interface for mirroring transcripts to S3/Redis/Postgres, which makes "resume a session on a
different machine than the one that started it" a supported first-class feature rather than
something to engineer. See §4.3.

---

## 2 — Decision: build on the Agent SDK. Remote Control is a dead end for this.

**Settled, so the design pass doesn't reopen it.** Claude Code ships `claude remote-control
--capacity 32 --spawn same-dir`, which superficially looks like the whole feature: it runs on your
machine against your real checkouts, is HTTPS-outbound-only, and handles many concurrent sessions.

**But it can only ever be driven by claude.ai and the Claude mobile apps.** It is not exposed as a
public API, the protocol is undocumented, and it's bound to claude.ai OAuth. A custom website cannot
attach to a `remote-control` session — so "suggest the user paste `claude remote-control` and then
control it from our tool" **is not achievable**, no matter how the UI is arranged. It's strictly
either/or: use Remote Control and accept claude.ai as the UI, or build your own UI and don't use
Remote Control at all.

**The good news: nothing is lost.** Every property that made Remote Control attractive is
reproducible with your own runner daemon, because the **Agent SDK is Anthropic's supported path for
exactly this** — the hosting guide documents a "long-running sessions" pattern (a container exposing
an HTTP/WebSocket endpoint, mapping each active session to a long-lived `query()` and its
subprocess) that is precisely this product. This is not a workaround.

| Property you wanted from Remote Control | Your own runner |
|---|---|
| One copy-paste command per host | ✅ your own enrollment command (§4.4) |
| No inbound ports / firewall changes | ✅ runner dials **out** over WSS |
| Agents rooted anywhere (`~/`, `~/IdeaProjects/jellystructure`) | ✅ workspace allow-list (§4.4) |
| Many concurrent sessions | ✅ one subprocess per session (§3.1) |
| Web tool can actually view + control it | ✅ — **only** on this path |

One related correction worth designing around: **your web tool also cannot authenticate Claude Code
on the host's behalf.** Claude's own auth (`claude /login` OAuth, or `ANTHROPIC_API_KEY`) lives on the
machine where it runs. Your control plane never holds it. That's a security feature, not a gap — a
compromised control plane can't leak Claude credentials — but it does mean onboarding has a
"Claude isn't signed in on this runner yet" state the UI must handle (§4.4.4).

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

### 4.4 Runner distribution and pairing — the copy-paste onboarding flow

This is the UX the Remote Control idea was really reaching for, rebuilt on your own runner. It is a
first-class design surface, not an implementation detail.

#### 4.4.1 The flow
1. Web UI → **Add a runner** → names it ("dev box") → backend mints a **short-lived, single-use
   enrollment token** (~15 min TTL).
2. UI renders a paste-able command with the token baked in, plus a "waiting for runner to
   connect…" state that resolves live.
3. User pastes it on the target host.
4. Runner starts, dials out, exchanges the enrollment token for a **long-lived runner credential**
   stored in its own config file, and reports its hostname, OS, SDK version, allowed roots, and
   Claude auth status.
5. UI flips to connected and shows the runner's workspaces.

#### 4.4.2 Distribution options (offer the first, upsell the second)

| Form | Command | Trade-off |
|---|---|---|
| **npx** (recommended first-run) | `npx @yourtool/runner --token rt_8f3a… --root ~` | Zero install, instant. Dies when the terminal closes. |
| **Installer + systemd** (recommended permanent) | `curl -fsSL https://yourtool.dev/install.sh \| sh -s -- --token rt_8f3a…` | Survives reboots. The "make this permanent" upgrade path after npx. |
| **Docker** | `docker run -d -v ~:/workspaces …` | ⚠️ Caveat worth surfacing in the UI: the runner then sees a *container* filesystem, and Claude's auth + your real checkouts must both be mounted in. Usually the wrong choice for "run against my actual working copies." |

The two-step (npx to try → installer to keep) is a genuinely nice onboarding arc and worth designing
deliberately.

#### 4.4.3 Token model
Do **not** put a long-lived secret in a command the user pastes into a terminal — it lands in shell
history, screenshots, and screen shares. Use the standard device-enrollment shape (same as GitHub
Actions runners and Tailscale auth keys): **short-lived single-use enrollment token in the visible
command → exchanged on first connect for a long-lived credential that is never displayed.** Enrollment
tokens expire, are revocable, and are per-runner.

#### 4.4.4 Workspace roots and the states the UI must handle
The runner starts with one or more **allowed roots** (`--root ~`, repeatable). The UI may then create
a workspace at any path *under* an allowed root; the runner validates every request against them.
This keeps the control plane unable to point a session at `/etc` (§9.3) while still letting you add
`~/IdeaProjects/jellystructure` from the browser without touching the host again.

Onboarding states worth explicit design:
- **Waiting for runner** (token issued, nothing connected yet) — with the token's expiry visible.
- **Connected, but Claude Code not signed in** — must tell the user to run `claude /login` **on that
  host**; your UI cannot do it for them (§2). Likely the single most common onboarding stumble.
- **Connected, signed in, no workspaces yet** — prompt to add the first one.
- **Runner offline** — sessions become read-only (history still renders from the SessionStore, §4.3);
  reconnect is automatic.
- **Version skew** — runner's SDK version vs. what the control plane expects.

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
POST   /api/runners/enroll                   ← {name}
                                             → {enrollmentToken, expiresAt, commands:{npx,installer,docker}}
GET    /api/runners                          → [{id, name, status, lastSeenAt, os, sdkVersion,
                                                 claudeAuth:"ok"|"missing", allowedRoots[],
                                                 quota:{type,status,resetsAt,utilization}}]
DELETE /api/runners/:id                                                  (revoke credential)

GET    /api/workspaces                       → [{id, runnerId, name, path}]
POST   /api/workspaces                       ← {runnerId, name, path}   (runner validates path ⊆ allowedRoots)

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
| `runner.status` | `{runnerId, status, claudeAuth}` | runner online/offline; drives the "not signed in" banner |
| `runner.enrolled` | `{runnerId, name}` | resolves the "waiting for runner…" onboarding state live |
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
2. **Add-a-runner onboarding** (§4.4) — name it → copy-paste command with per-form tabs
   (npx / installer / Docker) and a visible token expiry → live "waiting for runner…" → connected.
   Plus the follow-on states: **"Claude Code not signed in on this host — run `claude /login` there"**,
   "no workspaces yet", "runner offline", version skew.
3. **Runner detail** — allowed roots, workspaces, SDK version, quota, revoke.
4. **Workspace list / picker** — add a workspace (path under an allowed root), choose one to start a
   session in.
5. **New-session composer** — workspace, prompt, permission profile, model/effort, optional
   `maxTurns` / `maxBudgetUsd`.
6. **Session view** (the main screen) — chat-style transcript from the WS event stream:
   text blocks, tool-call cards (name + collapsed input), tool-result cards (collapsed, error state),
   subagent progress, cost/turn footer, a message composer, and an interrupt button.
7. **Status chips** — `running` / `idle` / `awaiting permission` / **`paused: quota — resumes 18:42`** /
   `errored` / `done`. The paused-quota state deserves genuine design attention; it's the headline
   feature and must not read as "broken."
8. **Approval card / modal** — tool name, human-readable input summary, Allow / Deny (+ reason),
   ideally reachable from a push notification without opening the full session.
9. **Session list** — filter by workspace/status, resume / fork / rename / tag / delete.
10. **Limit-hit recovery affordances** — "resume with more turns" / "raise budget" for
    `error_max_turns` / `error_max_budget_usd`.
11. **Notification settings** — which transitions notify, and where.

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

1. **Runner v1**: Agent SDK host + `--root` allow-list, one hardcoded session, streaming to stdout.
   No control plane, no auth. Proves the SDK integration in isolation.
2. **Capture a real `rate_limit_event`** and pin the parser (§11.2) — do this early, the whole
   headline feature depends on its real shape.
3. **Control plane v1**: outbound WS transport, session index, `POST /api/sessions` +
   `/api/stream`. Runner and control plane now talk.
4. **Enrollment + pairing** (§4.4) — token mint/exchange, `npx` distribution. This is what makes it
   feel like a product rather than a script.
5. **`SessionStore` (Postgres)** — transcript history in the website, survives runner restarts,
   unlocks cross-runner resume.
6. **Auto-resume state machine** (§8) + notifications — the headline feature.
7. **`canUseTool` remote approval** (§9) — what makes unattended operation actually safe.
8. UI, against the §7 contract.

Steps 1–3 are the risk-retiring core; if those work, the rest is conventional product engineering.

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
