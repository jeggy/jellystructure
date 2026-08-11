# towo-runner

The per-host runner daemon for **Towo** (Phase 162) — the piece that actually holds Claude Code
auth and spawns `claude` CLI subprocesses on a machine with real checkouts on it. See
`specs/requirements/phase-162-towo-agent-control-plane.md` for the full design.

Not part of the Gradle build — this is a standalone Node/TypeScript project, run separately from
the jellystructure Ktor backend and admin UI. See the spec's dev-review addendum for why it must
stay a separate process.

## Status

All 8 build-order steps are implemented and verified live end to end against a real Claude account,
including through the real admin UI in a real browser (see the spec's "Live verification" notes):
the Agent SDK integration, folder discovery, enrollment (token → long-lived credential, persisted and
reused across reconnects), a full session round-trip through the real control plane with a durable
transcript (a real `SessionStore` implementation — `append`/`load` relayed over the runner-link
connection, survives a control-plane restart), the complete remote-approval loop (a suspended tool
call, approved via REST or the browser UI, actually executing on disk), and auto-continue (a
`resume_session` command resumes a paused session with its real history via `sessionStore.load()`).

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

Multi-turn (`send_message`, posting into an already-idle session) is handled by `asyncQueue.ts`'s
`AsyncQueue` — every managed session's `prompt` is this queue from the start, not a plain string, so
the underlying process stays alive indefinitely rather than exiting once the first turn completes.
**`Query.streamInput()` alone is not reliable for this** — confirmed live it either throws
`"ProcessTransport is not ready for writing"` or silently produces no response when called from a
genuinely separate later context (a WS message handler firing seconds after the session went idle); it
only works when called synchronously within the same tick as the generator's own message processing,
which "message an idle session" can never guarantee. `pushMessage()` on the retained session handle
was confirmed live across a real multi-second gap (browser composer → REST → runner → a second real
Claude turn, the actual reply text landing in the transcript) and is what auto-continue's resume
also uses internally now (the resumed session's continuation prompt is just the queue's first item).

Killing the runner process does **not** clean up its child `claude` CLI subprocesses — they're left
orphaned, still holding their sessions "active" from the CLI's own perspective with nothing left to
relay them anywhere. Known gap, not yet fixed: a graceful-shutdown handler (SIGTERM/SIGINT) that calls
`.close()` on every active `Query` before exiting.

## Rate-limit event capture

Every `rate_limit_event` message is logged verbatim to `~/.towo-runner/rate-limit-events.jsonl`.
See [[reference-claude-agent-sdk-facts]] / the spec's dev-review addendum for what's been confirmed
against the pinned SDK version (`@anthropic-ai/claude-agent-sdk@0.3.227`) — including a real captured
payload showing `rateLimitType: "seven_day"` at `allowed_warning`, not the `five_hour` bucket the
naive assumption would expect.
