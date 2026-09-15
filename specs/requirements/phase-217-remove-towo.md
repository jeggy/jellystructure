# Phase 217 — Remove Towo

> Phase 162 put a control plane for Claude Code sessions inside the jellystructure admin. Its own spec
> admitted why: *"Towo has nothing to do with media management… it lives here purely because this is the
> only always-on operator UI we run."* That tenancy is now withdrawn. This phase removes Towo from the
> product completely — UI, routes, data, runner and CI — rather than leaving it feature-flagged off
> behind a switch nobody will ever turn on again.

## Status

`Planned` — written 2026-09-16, **not dev-reviewed**. The **design side is already done**: every Towo
mockup was deleted from this project on 2026-09-16 (FR-217-7).

**Numbering:** verified against `main` on 2026-09-16 — admin taken through **215**, and the same tree
scan that cleared 216 also cleared 217. Next free after this: 218 / R244.

Retires: **162** (`specs/requirements/phase-162-towo-agent-control-plane.md`, `✓ Done` 2026-08-11).

## Current state

162 shipped in full on 2026-08-11 — all eight build-order steps, three dev-review addenda, verified live
against a real Claude account. What exists today, as far as this project can see it:

- **Admin UI** — a sidebar group (Overview · Approvals · Runners) behind the `js-towo` localStorage flag
  read by `app-shell.js`, eight pages, `towo.css`, and a **Settings → Towo** tab carrying the master
  enable switch plus the server-owned defaults (connect URL, sign-in mode, quota watcher, auto-arm,
  approval timeout + reason, low-quota threshold, five notification toggles).
- **Server** — Towo's own routes, the `towo_settings` table, runner/session/permission/transcript
  storage, and the **control-plane-owned auto-continue scheduler** (a 6-hour sweep that resumes sessions
  paused on a quota window, deliberately backend-owned because no browser is open when it runs).
- **Runner** — a `towo-runner/` module, a Gradle subproject, its own `towo-runner-ci.yml` workflow, an
  entry in 167's `.dockerignore` list, and **live enrolled runners** dialing out from at least the dev
  box and the home server (plus one long-offline laptop), each holding a credential and a systemd unit.
- **Cross-references** — 165 cites Towo's "where runners connect" field as the shape for its own Jellyfin
  reachability field; 167 and the 2026-08-17 exposure report list `towo-runner/` in the Docker context.

The flag makes the UI hideable, which is not the same as removed: the routes answer, the scheduler runs,
the runners keep dialing, and the tables keep growing.

## Goal

Leave no Towo behind — no route that answers, no table that grows, no process that retries, no switch in
Settings, and no dead link in any document — while keeping the *record* of phase 162 readable.

## Functional requirements

**FR-217-1 — The UI is deleted, not gated.** Remove the sidebar group and its nav icons, all eight pages,
`towo.css`, and the Settings → Towo tab in full (nav item, section, its tab-map entry, its toggle
wiring). **The `js-towo` flag goes with them**: no code may read it, and a browser that still has
`js-towo: '1'` in localStorage must get no group, no route and no trace — a stale key cannot resurrect a
section that no longer exists. `settings.html?tab=towo` resolves to the default tab, not a blank pane
(the Phase-55 tab contract already requires an unknown tab to fall back).

**FR-217-2 — The API surface is deleted, not stubbed.** Remove Towo's route file(s) and their
registration. No `501`/`410` placeholder endpoints, no empty-list responses, no feature-flag branch left
in a shared router. Any WebSocket topic, event type or notification hook that exists only for Towo goes
with them.

**FR-217-3 — The scheduler stops before anything else is removed.** The auto-continue sweep and any
Towo-owned background job are unregistered **first**, so the removal cannot race a resume attempt
against a half-dropped schema. After this phase there is no periodic Towo work of any kind.

**FR-217-4 — Transcripts are exported once, then the schema is dropped.** Transcripts are the only
artefact here that cannot be reconstructed, so before the drop, dump the session transcripts (and the
runner/session metadata needed to read them) to a single file on the config volume, at a path the
operator is told, and log it. Then drop the Towo tables in **one forward-only migration** —
`towo_settings`, runners, sessions, permission requests, transcripts. No orphan tables, no
`_deprecated_` renames, no rows left behind to confuse a future migration.

**FR-217-5 — The runner is uninstalled, not orphaned.** Revoke every enrolled runner's credential; stop
and remove the systemd unit on each host it was installed on (dev box, home server, and the offline
laptop if it ever returns); delete the `towo-runner/` module, its Gradle subproject entry, its
`towo-runner-ci.yml` workflow, and its line in the `.dockerignore` list 167 introduced. A runner whose
server is gone must not sit in a reconnect loop on a machine somebody owns — that is the one failure mode
of a half-removal that costs somebody a debugging session.

**FR-217-6 — Nothing shared is removed with it.** The sidebar's **nav-group machinery** (grouping,
collapse, `js-nav-collapsed:*`) is used by Setup and Ravilo and stays exactly as it is — only the
Towo-specific `hidden()` branch goes. The notification webhook, the toast helper, `wf.css`/`app.css`
tokens and the Settings tab contract are all shared infrastructure and are untouched. This phase removes
a tenant, not a floor.

**FR-217-7 — The design set is already cleared; an export must not re-add it.** Deleted from the design
project on 2026-09-16: `design/app/towo.html`, `towo-sessions.html`, `towo-session.html`,
`towo-session-new.html`, `towo-approvals.html`, `towo-runners.html`, `towo-runner-new.html`,
`towo-limits.html`, `towo.css`, the whole `design/claude-console/` directory, the Settings tab, the
sidebar group and its icons. The next design export therefore **deletes** these repo-side; it must not be
mistaken for a mirror that has fallen behind.

**FR-217-8 — Phase 162's record stays, and says so.** Do **not** delete
`phase-162-towo-agent-control-plane.md` or its `STATUS.md` row — the history of a shipped phase is worth
more than a tidy directory. Instead: 162's spec gains a header note *"Removed by 217"*, its `STATUS.md`
row reads **Removed** (with the date and a pointer here), and `scripts/check-phases.sh` accepts that
status. 165's open question keeps citing 162 for the *shape* of its reachability field, which is still a
good precedent; 167's `.dockerignore` list drops the line per FR-217-5, and the 2026-08-17 research
report is left as written (research is dated, not maintained).

## Non-goals

- **No replacement.** Nothing in jellystructure takes over running Claude Code sessions, and no reduced
  "just the approvals" version survives.
- **No change to how anyone uses Claude Code.** Sessions on the dev host keep working exactly as they did
  before 162 existed; Claude's sign-in always lived on the host and was never ours to touch.
- **No spec or STATUS deletion** (FR-217-8).
- **No deprecation period.** There is one operator and one household; a flag-off-then-remove-later dance
  buys nothing and leaves the scheduler running in the meantime.
- **No migration of transcripts into the media store.** They are dumped to a file (FR-217-4) and that is
  the end of jellystructure's involvement.

## Acceptance

1. A fresh admin load shows no Towo group, and `settings.html?tab=towo` lands on the default tab with no
   empty section.
2. With `js-towo` set to `'1'` by hand in localStorage, the admin behaves identically — no group, no page,
   no request.
3. `grep -ri towo` over the admin frontend and the backend returns hits only in `specs/`, `STATUS.md` and
   dated research reports.
4. Every former Towo URL returns the app's normal not-found handling; no route answers 200, 410 or 501.
5. A restart runs no Towo job: no auto-continue sweep in the logs, no scheduler registration at boot.
6. The transcript export file exists at the logged path and is readable **before** the migration runs;
   after it, none of the Towo tables exist and the schema version has moved forward once.
7. On each host that ran a runner: no systemd unit, no process, no reconnect attempts in the journal, and
   the credential is rejected if replayed.
8. CI is green with `towo-runner-ci.yml` gone, `settings.gradle.kts` has one fewer subproject, and the
   Docker build context is unchanged in size or smaller.
9. `scripts/check-phases.sh` and `scripts/check-mobile-css.sh` both pass with 162 marked **Removed**.
10. 162's spec opens with the *"Removed by 217"* note and links here; no other spec links to a file that
    no longer exists.

## Source references

- `specs/requirements/phase-162-towo-agent-control-plane.md` — what is being removed, and its own
  statement of why it lived here (§"Why it lives in the jellystructure admin").
- `specs/research-reports/claude-code-remote-agent-management-2026-08-10.md` — the research behind 162;
  left as a dated document.
- `specs/requirements/phase-167-docker-packaging-internet-exposure.md` — the `.dockerignore` list that
  names `towo-runner/` (FR-217-5).
- `specs/requirements/phase-165-jellyfin-webhook-ingest.md` — open question 2 cites Towo's connect-URL
  field as a precedent; the citation survives the removal (FR-217-8).
- Design side: the 2026-09-16 deletion pass recorded in this project's `CLAUDE.md` and `github.md`.

## Open questions

1. **Is the transcript export wanted at all, or should the tables just be dropped?** FR-217-4 exports
   because the data is unreconstructable and the export is cheap. If nobody will ever read it, say so and
   the requirement becomes a plain drop.
2. **Does anything else read `js-towo`?** The design side had exactly two readers (`app-shell.js` and the
   Settings tab) and both are gone here; confirm the shipped frontend has no third.
3. **Where does the retired spec live?** It stays in `specs/requirements/` under FR-217-8. If the dev team
   would rather have `specs/retired/`, that is a convention change worth making once, for every future
   removal, not just this one.
4. **Anything else in the admin that only existed for Towo?** The notification toggles are shared, but a
   Towo-only notification *channel* or webhook destination, if one was added, should go with it.
