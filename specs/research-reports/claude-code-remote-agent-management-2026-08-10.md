# Research report — a self-hosted remote control plane for Claude Code

**Date:** 2026-08-10
**Status:** Research only — not a spec. Design brief for a Cosmos design-tool pass on a **new,
standalone product** (a personal Claude Code management website) — unrelated to jellystructure's own
feature set. Placed here only because this repo is the established research-report → design-tool
hand-off channel; nothing in this report describes jellystructure itself.

## 1 — The ask, restated precisely

Run Claude Code persistently on a server (or several), with project checkouts already in place
(`~/`, `~/IdeaProjects/jellystructure`, etc.), and control it from a **custom website** — not
claude.ai's own UI. Requirements, as given:

1. Spin up / resume Claude Code sessions rooted at different working directories on demand.
2. Control it remotely — explicitly allowing for the control website (Docker, possibly on a
   different machine) and the actual Claude Code host to be **different machines on different
   networks**.
3. **Auto-resume after a usage-quota exhaustion**, waiting out the reset window (assumed ~5 hours)
   and continuing the same session with no manual intervention.
4. Reuse whatever Claude Code already provides for "agents available for remote access or
   auto-spinning-up" rather than reinventing it from scratch — the user left the *how* open.

## 2 — What Claude Code already provides (don't rebuild this)

This section is first-hand: written by a Claude Code session that has direct access to the
mechanisms below, not secondary research. Confidence is marked per item.

### 2.1 Headless / scriptable execution — **high confidence**
The CLI has a non-interactive mode built for exactly this kind of orchestration: `claude -p
"<prompt>" --output-format stream-json`. `stream-json` emits one JSON event per line (text deltas,
tool calls, tool results, the final result) — this is the correct integration surface, **not**
scraping the interactive terminal UI's ANSI output. There's also a proper **Agent SDK**
(TypeScript and Python) that wraps this same loop as a library (`query()`/streaming input,
permission-mode control, hook callbacks, MCP server registration) for building custom harnesses —
this is architecturally the better foundation for a long-running server component than shelling out
to the CLI binary per-call, though shelling out is a legitimate, simpler v1.

### 2.2 Session persistence and resume — **high confidence**
Every session is stored as a JSONL transcript, keyed by the **absolute working directory** it was
started in, under `~/.claude/projects/<url-encoded-cwd>/<session-id>.jsonl`. `--resume <session-id>`
(or `--continue` for "most recent in this directory") reopens that exact transcript with full
history — this is the mechanism that makes "auto-resume after quota reset" a matter of *storing a
session ID and cwd*, not reconstructing conversational state from scratch. Confirmed directly: this
session's own transcript lives at exactly that path shape, and mid-session tools in this environment
(`Workflow`'s resume, `SendMessage` to a named agent) already build on the same "session ID = handle
to resumable state" idea.

### 2.3 Subagents are already native — **high confidence**
"Spin up agents" doesn't need bespoke orchestration logic in the control plane. A single top-level
Claude Code session already spawns and manages subagents itself (the `Agent`/`Task` tool, background
execution, `SendMessage` to resume a named subagent) whenever the model decides delegation helps.
**The control plane's job is only to be a remote front end to a top-level session's lifecycle**
(start / resume / send-message / stream-output) — not to reimplement fan-out. This one collapses a
large amount of the surface the user might otherwise plan to build.

### 2.4 Remote Control (existing, first-party) — **medium confidence, worth prototyping first**
This very session has a `/remote-control` slash command, and the `ListAgents` tool's own
documentation lists "your Remote Control sessions on other machines" as a distinct agent-address
class from local subagents and cloud sessions. This indicates Claude Code already ships a
**session-bridging mechanism between machines** — almost certainly relayed through Anthropic's own
infrastructure (so it inherently solves the "control plane and runner are on different networks,
possibly behind NAT" problem the user is worried about, since neither side needs a public inbound
port). What's genuinely uncertain from in-session knowledge alone: the exact wire protocol, whether
it's scriptable/embeddable in a *custom* website versus tied to the official Claude Code
mobile/web surfaces, and whether it exposes the granularity needed (multiple workspaces, custom
branding, custom auth). **Recommendation: spend a short spike verifying what Remote Control actually
exposes before designing a bespoke tunnel** — if it's embeddable or has a documented API, it may
directly replace §4's transport layer.

### 2.5 Cloud / scheduled agents — **high confidence on existence, low relevance to this ask**
Tools available in this session (`CronCreate`/`CronList`/`CronDelete`, the `schedule` skill: *"Create,
update, list, or run scheduled cloud agents (routines) that execute on a cron schedule"*) confirm
Anthropic already runs a hosted, cron-triggered agent execution environment. This is **not** a fit
for the user's core need, though: those agents run in Anthropic's own managed sandbox, not against
the user's own filesystem/checkouts (`~/IdeaProjects/jellystructure` won't exist there). It may still
be useful for a narrow slice — e.g., a scheduled *notifier* that pings the user's own webhook — but
the actual project work has to run on the user's own machine(s), which rules cloud agents out as the
primary execution venue.

### 2.6 MCP — **high confidence, secondary role here**
The Model Context Protocol is how Claude Code (or the SDK) is given *tools*, not how one Claude Code
instance controls another. It's relevant only as an optional detail later (e.g., exposing the
control-plane's own state — "list my other running sessions" — as an MCP server so a session can be
self-aware), not as the backbone of the remote-control architecture itself.

## 3 — Recommended architecture

A three-tier design, chosen specifically because the user's own constraint ("webserver may be in
Docker on a different machine than the Claude Code host") rules out anything that assumes
same-host/same-network co-location.

```
┌─────────────────────────┐        ┌──────────────────────────┐        ┌─────────────────────────┐
│  Control Plane (Website)│        │   Runner Daemon           │        │  Claude Code processes   │
│  — user's own UI/API    │◄──────►│   (one per host with a    │◄──────►│  — one OS process per    │
│  — session index/DB     │  (A)   │   filesystem to work on)  │  (B)   │  live session, spawned   │
│  — auth, notifications  │        │   — outbound-only conn    │        │  by the runner           │
└─────────────────────────┘        └──────────────────────────┘        └─────────────────────────┘
        Machine A                          Machine B (or N of them)         same host as its runner
     (e.g. Docker, cloud)              (e.g. the dev box with the
                                          actual project checkouts)
```

- **(A) Control-plane ↔ runner**: the cross-machine hop the user explicitly flagged. See §4.
- **(B) Runner ↔ Claude Code process**: same-host, so this is just process management (spawn,
  pipe stdio or read the SDK's async stream, track PID/session-id, restart on crash).

### 3.1 Control Plane responsibilities
- Own UI (the "website"): list workspaces, list/start/resume sessions per workspace, live
  transcript view, send-a-message box, approve/deny pending permission prompts, session status
  (running / idle / **paused: quota, resumes at HH:MM** / errored).
- A small database (SQLite is plenty at this scale) indexing: known workspaces, session records
  (runner id, workspace, Claude session-id, status, last-activity, resume-at timestamp) — this is
  metadata *about* sessions, not the conversation content itself, which stays in Claude Code's own
  JSONL storage on the runner's host.
- Auth for the website itself (out of scope for this report — the user's own concern) and a
  separate credential for the control-plane↔runner channel (§5).
- Notifications (push/webhook/email/Telegram/ntfy.sh) for state transitions that matter unattended:
  paused-for-quota, resumed, needs-permission-approval, errored.

### 3.2 Runner Daemon responsibilities
- Runs **on every machine that has project checkouts** the user wants to drive (their dev box,
  a home server, etc.) — a small persistent process (systemd unit / Docker container with the host
  filesystem mounted in).
- Exposes nothing inbound publicly; it **dials out** to the control plane (§4.1) so the machine
  with the actual project directories never needs a port opened to it.
- Spawns/supervises Claude Code processes scoped to a requested working directory, using
  `--output-format stream-json` (or the SDK's native async stream if built on the SDK instead of
  the CLI) so tool calls, text, and results arrive as structured events, not raw terminal bytes.
- Maintains the **workspace allow-list** (§6) — the control plane requests "start a session in
  workspace X," the runner is the thing that actually knows and validates the real filesystem path.
- Tracks each session's Claude-assigned session-id (needed for `--resume`) and forwards it to the
  control plane's index.
- Owns the **auto-resume state machine** (§5) — the runner is closest to the actual process
  exit/error, so it should own detecting "was this a quota exhaustion" and scheduling the retry;
  it reports state transitions up to the control plane rather than the control plane polling.

## 4 — Cross-machine transport (the (A) hop)

Two realistic options; recommend building the first, keeping the second in mind if the deployment
footprint grows beyond a personal homelab.

### 4.1 Outbound WebSocket from runner → control plane (recommended default)
The runner initiates a persistent WebSocket (or gRPC bidi stream) connection *out* to the control
plane and keeps it alive (reconnect-with-backoff on drop). This is the same shape as CI runners,
ngrok-style tunnels, and most "agent phones home" designs — it sidesteps NAT/firewall problems on
the machine that actually holds the project directories entirely, since that machine never needs an
open inbound port. The control plane, in turn, can be reached from anywhere (it's the one piece
meant to be public-ish, behind the website's own auth) and simply multiplexes messages to whichever
runner connection matches the target workspace/session.

### 4.2 Private overlay network (worth it given the user's existing infra)
Per this repo's own project memory, the user already runs a WireGuard-based remote-access VPN and a
UniFi private network with static addressing (`192.0.2.x`) for their homelab. If the runner and the
control plane are both machines the user controls (not a third-party cloud control plane), simply
putting both on the same Tailscale/WireGuard mesh and having the control plane reach the runner's
private IP directly is **less code to write** than §4.1's relay logic — at the cost of needing that
VPN to be up and both machines mutually reachable, which is already true of this user's environment
today. **This is likely the pragmatically fastest path for a personal setup**, with §4.1 as the more
portable fallback if a runner ever needs to sit somewhere the VPN can't reach (e.g., a machine at a
friend's place, a cloud spot instance).

Both are compatible with §2.4's Remote Control spike — if that turns out to expose a usable API, it
could replace this whole layer.

## 5 — Auto-resume after quota exhaustion

This is the part worth being precise about, since a naive implementation either loops forever on a
*real* error or silently drops a paused session.

### 5.1 Detecting "this was quota, not a real failure"
The runner's process supervisor must distinguish a genuine usage-limit condition from: a crash, an
auth failure, a network blip, or a real tool/task error the agent should just report, not retry
past. `stream-json`'s structured event stream is what makes this reliable — a specific error/result
event shape for usage-limit conditions, versus everything else. **This needs verifying against the
current CLI's actual event shape before implementation** (not asserted here as fact) — a first build
task should be capturing one real usage-limit event end-to-end and keying the detector off that
exact shape, not a guessed string match on stderr text.

### 5.2 The reset window
The user's own assumption is a **5-hour rolling window** — this matches how Claude subscription
plans (Pro/Max) are commonly described, but it is meaningfully different from **pay-per-token API
billing**, which has no such reset at all (only RPM/TPM rate limits that clear continuously, not on
a fixed clock). **Which billing model the runner's Claude Code auth is on changes this design
materially** — worth confirming explicitly before building the scheduler, since a pay-per-token setup
needs exponential-backoff-on-429 instead of a fixed-window scheduled wake. If a reset timestamp is
present in the actual error payload, prefer it outright over any assumed window; only fall back to
"now + 5h" when the payload doesn't say.

### 5.3 The resume itself
Once past the reset boundary (plus a small safety buffer — don't schedule to fire exactly on the
boundary, servers can be a little early or late): `claude --resume <session-id>` (or SDK equivalent)
**in the original working directory** — session storage is keyed by cwd, so this must run from the
same workspace path the session started in, not just anywhere with the same session-id.

### 5.4 State visibility
The whole point of this system is not babysitting it — so a paused-for-quota session should be a
first-class, clearly-surfaced state on the website (not just "stopped"), with the computed resume-at
time shown, and a manual override to force-retry sooner or push it later. The control plane should
notify (§3.1) on both the pause and the resume, since a multi-hour silent gap with no confirmation
that it actually came back is the failure mode that defeats the point of automating this at all.

## 6 — Security considerations

- **Workspace allow-list, not arbitrary paths.** The control plane should request sessions by a
  named workspace ("jellystructure", "home"), and the *runner* — not the control plane, and never a
  request-supplied raw path — maps that name to a real filesystem directory from its own local
  config. This bounds what a compromised or buggy control plane can make the runner touch.
- **Don't default to skipping all permission prompts.** Running fully unattended is tempting for a
  "set and forget" tool, but Claude Code's permission-prompt system exists specifically to gate
  risky actions (destructive commands, pushes, etc.) — a remote, unattended agent with permissions
  blanket-disabled is a materially higher-risk configuration than an interactive session. Prefer:
  a curated allow-list of pre-approved safe tools/commands per workspace (mirroring how this
  session's own `settings.json`-driven permission model works) with genuinely risky actions still
  forwarded to the website as an approval prompt the user can act on from their phone, rather than
  either extreme (approve everything / approve nothing automatically).
- **Transport auth**: the control-plane↔runner channel (§4) needs its own credential — a
  per-runner token at minimum; mutual TLS if the transport is a public relay rather than a private
  VPN. If going the §4.2 VPN route, network-level trust may be sufficient, but a shared secret on
  top costs little and guards against another device joining the same mesh later.
- **Secrets never transit the control plane's DB in plaintext** if avoidable — the runner should
  hold Claude Code's own auth locally (it already does, via the CLI's normal login state) rather
  than the control plane pushing credentials to it per session.

## 7 — Open questions for the design pass

1. **Remote Control spike (§2.4)** — does it expose anything embeddable/API-shaped, or is it tied to
   Anthropic's own client surfaces only? This could significantly shrink §4's scope if usable.
2. **Billing model (§5.2)** — subscription (fixed 5h window) vs. API pay-per-token (no fixed window,
   different backoff logic entirely). Confirm before building the scheduler.
3. **How many runners, realistically?** — one (the user's main dev box) to start, per the examples
   given (`~/`, `~/IdeaProjects/jellystructure`). Design the runner-registration flow to support N
   without over-building for a fleet that may never materialize.
4. **Multi-user?** — this reads as a personal tool (one user, their own machines). If it's ever
   meant for more than one person, the auth/workspace-isolation model needs to be designed in from
   the start rather than retrofitted — worth a direct answer before UI design, since it changes the
   information architecture (is a "workspace" scoped to a user, or global?).
5. **Live transcript rendering** — `stream-json` events map naturally to a chat-style UI (text
   deltas, tool-call cards, tool-result cards, permission-prompt cards) — worth designing the
   event→UI-component mapping explicitly as part of the visual pass, since this is the single most
   time-spent surface of the whole tool.

## Source references
- First-hand: this session's own available tooling and documented behavior (`Workflow`'s
  `resumeFromRunId`, `SendMessage`'s named-agent resume, the `/remote-control` slash command, the
  `schedule`/`loop` skills, `CronCreate`/`CronList`/`CronDelete` tool names, `ListAgents`' description
  of Remote Control sessions as a distinct agent class).
- Session storage path shape (`~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`) and
  `--resume`/`--continue`/`--output-format stream-json` CLI flags: first-hand operating knowledge of
  Claude Code's own architecture, not sourced from this repo's code.
- User's existing infra referenced in §4.2: this project's own memory
  (`reference-pixel9-vpn-adb-pairing`, `reference-backend-deployment`) — WireGuard remote-access VPN,
  UniFi private network, `192.0.2.x` static addressing, an always-on dev host.

## Relationships
This report is **standalone** — it doesn't extend, correct, or depend on any jellystructure phase
or spec. It exists in this repo only as the agreed hand-off point to the Cosmos design-tool session
for a UI/UX pass on the control-plane website described in §3.1. Any resulting design work for this
tool belongs in its own project, not jellystructure's `design/` tree.
