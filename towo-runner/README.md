# towo-runner

The per-host runner daemon for **Towo** (Phase 162) — the piece that actually holds Claude Code
auth and spawns `claude` CLI subprocesses on a machine with real checkouts on it. See
`specs/requirements/phase-162-towo-agent-control-plane.md` for the full design.

Not part of the Gradle build — this is a standalone Node/TypeScript project, run separately from
the jellystructure Ktor backend and admin UI. See the spec's dev-review addendum for why it must
stay a separate process.

## Status

Build-order steps 1–4(backend)+7 are done and verified live end to end against a real Claude
account (see the spec's "Live verification, 2026-08-11" note): the Agent SDK integration, folder
discovery, enrollment (token → long-lived credential, persisted and reused across reconnects), a
full session round-trip through the real control plane, and the complete remote-approval loop (a
suspended tool call, approved over REST, actually executing on disk). Not yet built: `SessionStore`
(a real transcript persistence layer — v1 uses a capped in-memory placeholder on the control-plane
side), the auto-continue state machine, and the admin UI.

## Usage

### Standalone (no control plane — build-order step 1's original proof of concept)

```bash
npm install && npm run build
node dist/index.js --root ~/IdeaProjects --root ~ --prompt "..."
```

Runs one hardcoded session locally, streaming to stdout. `canUseTool` denies everything.

### Connected to a Towo control plane

```bash
npm install && npm run build
# First connect: token from Towo's "Add a runner" enrollment command.
node dist/index.js --connect "ws://host:9505" --enroll-token "twe_..." --root ~/IdeaProjects --root ~
# Every reconnect after: the credential saved to ~/.towo-runner/credential.json is used automatically.
node dist/index.js --connect "ws://host:9505" --root ~/IdeaProjects --root ~
```

Dials `/api/towo/runner-link`, sends `hello` + discovered `folders`, then runs whatever sessions
the control plane starts — each one's messages, permission requests, and results relay back over
the same connection. Reconnects automatically on drop.

`--root` is repeatable and is the allow-list (spec §7: the control plane only ever names a folder;
this runner is what actually resolves it). Folders are discovered under each root — a root that is
itself a git repo counts as one folder; otherwise every git repo one level below it does (spec §B:
"folders are discovered, not declared").

`canUseTool` holds the SDK's callback open in-process until the control plane relays a decision back
down this same connection — confirmed live: a `Write` call suspended, was approved via
`POST /api/towo/permissions/:id`, and the file was actually written.

**Not yet supported**: `send_message` (posting into an already-idle session) — multi-turn needs
streaming-input mode (an `AsyncIterable` prompt) from session start, which `startManagedSession`
doesn't use yet. Logged as a warning, not silently dropped. Deferred to build-order step 8, when the
composer UI actually needs it.

## Rate-limit event capture

Every `rate_limit_event` message is logged verbatim to `~/.towo-runner/rate-limit-events.jsonl`.
See [[reference-claude-agent-sdk-facts]] / the spec's dev-review addendum for what's been confirmed
against the pinned SDK version (`@anthropic-ai/claude-agent-sdk@0.3.227`) — including a real captured
payload showing `rateLimitType: "seven_day"` at `allowed_warning`, not the `five_hour` bucket the
naive assumption would expect.
