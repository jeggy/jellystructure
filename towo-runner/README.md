# towo-runner

The per-host runner daemon for **Towo** (Phase 162) — the piece that actually holds Claude Code
auth and spawns `claude` CLI subprocesses on a machine with real checkouts on it. See
`specs/requirements/phase-162-towo-agent-control-plane.md` for the full design; this is
build-order step 1 (a hardcoded single session, no control-plane transport yet).

Not part of the Gradle build — this is a standalone Node/TypeScript project, run separately from
the jellystructure Ktor backend and admin UI. See the spec's dev-review addendum for why it must
stay a separate process.

## Status

v1: proves the Agent SDK + local Claude Code auth + folder discovery + `canUseTool` all work end
to end. No WebSocket transport to a control plane yet, no `SessionStore`, no auto-continue — those
are later build-order steps.

## Usage

```bash
npm install
npm run build
node dist/index.js --root ~/IdeaProjects --root ~ --prompt "..."
```

`--root` is repeatable and is the allow-list (spec §7: the control plane only ever names a folder;
this runner is what actually resolves it). Folders are discovered under each root — a root that is
itself a git repo counts as one folder; otherwise every git repo one level below it does (spec §B:
"folders are discovered, not declared").

`canUseTool` currently denies every tool call — this is a deliberate proof-of-concept safety
choice, not a design decision to keep; it exists to validate the callback shape (`title`,
`displayName`, `requestId`) against the real SDK before building the remote-approval transport in
spec §D.

## Rate-limit event capture

Every `rate_limit_event` message is logged verbatim to `~/.towo-runner/rate-limit-events.jsonl` —
build-order step 2 (pin the parser to a real captured payload before the auto-continue state
machine depends on it). See [[reference-claude-agent-sdk-facts]] / the spec's dev-review addendum
for what's been confirmed so far against the pinned SDK version
(`@anthropic-ai/claude-agent-sdk@0.3.227`).
