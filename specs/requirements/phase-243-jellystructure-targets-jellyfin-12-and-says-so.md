# Phase 243 — jellystructure targets Jellyfin 12.x, and says so

> Owner direction 2026-09-18: we want to be fully up to date with the latest Jellyfin and we do not
> care about older versions any more.

## Status

`Planned` — written 2026-09-18, **dev-reviewed 2026-09-19 against `main` `dcb97f2c`** (see §Dev review
at the foot: read the version from the *public* probe, which already runs and still answers when
authentication is broken). The umbrella decision behind **238**,
**239**, **240**, **241**, **242** and **R271**; those are the individual repairs, this is the policy
that makes them legitimate and stops the next one being written as a compatibility shim.

## What is wrong

**Nothing in the product knows what Jellyfin it is talking to.** `JellyfinSystemInfoAuth`
(`auth/Models.kt:386-388`) carries exactly one field, `HasPendingRestart`, which phase 212's advisor
uses. `GET /System/Info/Public` returns `Version`, and no code reads it. There is no floor, no
warning, no record in `/api/health`, and no statement anywhere of which Jellyfin this product
supports.

That absence has a cost, and the 12.1 upgrade collected it. Three route-level behaviours changed at
once: `/socket` stopped accepting `api_key`, `X-Emby-Token` stopped authenticating, and
`/api-docs/openapi.json` started returning 500. The first broke a live feature silently for hours.
Nothing announced the version change, because nothing was watching for one.

**The codebase is anchored to a version it no longer runs against.** Nineteen comments across nine
files cite `10.11.11` as the thing a behaviour was verified against, including load-bearing ones:
`JellyfinClient.kt:1240` explains the `Version`-header rule by citing `AuthorizationContext.cs:167-176`
in 10.11.11, and `tests/mock-jellyfin/server.js` justifies its 400 by the same. Those notes are
valuable — they record *why* code looks the way it does — but a reader today cannot tell which are
still true. As it happens the `Version` rule is still true, re-measured on 12.1: 401 for bad
credentials with a `Version` present, 400 without it. Most of the others were never re-checked.

**And there is no place to put the answer to "what do we support?"** Phase 167 made this
internet-facing and versioned. A household running an older Jellyfin will hit failures with no
diagnostic, and a contributor writing a new route has no stated target to verify against.

## Requirements

**FR-243-1 — A declared floor.** jellystructure supports Jellyfin **12.0 and later**. This is written
in `specs/constitution.md` as an architectural fact, and in the README where an operator will see it.
Below the floor is unsupported: not blocked, not shimmed, undefined.

**FR-243-2 — The version is read and reported.** `JellyfinSystemInfoAuth` gains `Version`, and
`/api/health` reports the connected server's version string alongside the existing Jellyfin
connectivity state. This is the fact that was missing when the upgrade happened.

**FR-243-3 — Below the floor is a visible finding, not a crash.** When the connected server's major
version is below 12, `/health/full` fails a check naming the version found and the version required,
and phase 212's advisor carries a server-wide finding saying the same. Nothing refuses to start and
nothing degrades deliberately — the product simply stops claiming the behaviour is defined.

**FR-243-4 — No compatibility branches, ever.** No code path may branch on Jellyfin version to
support an older one. Where a newer Jellyfin changes a contract, the product moves; it does not carry
both. This is the rule that phases 238, 239 and 241 are each written against, and it is stated here
once so it does not have to be re-argued per phase.

A version check is permitted for *reporting* (FR-243-2, FR-243-3) and for *refusing* a known-broken
combination. It is not permitted for choosing between two request shapes.

**FR-243-5 — Verification notes carry their version and date.** Every comment recording "verified
against Jellyfin X" states the version and the date. Existing `10.11.11` citations are either
re-verified against 12.x and re-stamped, or marked as historical with the reason the note is still
worth keeping. The nineteen sites are listed in the research report so this is a finite task rather
than an open-ended sweep.

**FR-243-6 — A Jellyfin upgrade is a checklist, not a surprise.** A short document,
`specs/jellyfin-upgrade-checklist.md`, naming what to run after a Jellyfin major upgrade: phase
240's live route guard, its model-field check, the auth-form matrix, and a look at `/health` for the
new version string and the bridge state from FR-238-3. The 2026-09-18 audit is the worked example.

## Non-goals

- Supporting Jellyfin 10.x or 11.x in any form.
- Pinning Jellyfin to an exact version in the compose files. The floor is a minimum, not a pin, and
  the household should keep taking upgrades.
- Auto-detecting capabilities at runtime and adapting. That is the compatibility branch FR-243-4
  forbids, wearing a different hat.
- Rewriting the nineteen comments as part of this phase's build. FR-243-5 is a rule; whoever next
  touches each file applies it, with the list in the report so nothing is lost.

## Acceptance

1. `/api/health` reports `12.1.0` against the household server.
2. Pointed at a Jellyfin below the floor, `/health/full` fails one check naming both versions, and the
   advisor shows the matching server-wide finding. Nothing else behaves differently.
3. `grep` for a version comparison in the request-building paths finds nothing. The only version
   comparisons in the codebase are the two reporting sites from FR-243-2 and FR-243-3.
4. The constitution and README both state the floor, and they agree.
5. `specs/jellyfin-upgrade-checklist.md` exists and, followed against the current server, reproduces
   the 2026-09-18 audit's findings.

## Open questions

1. Is the floor 12.0 or 12.1? The audit only ever saw 12.1.0, and the three behaviour changes it
   found are not known to be 12.1-specific rather than 12.0-wide. If nobody will ever run 12.0, the
   distinction is academic and 12.0 is the safer statement.
2. Should the below-floor state be sticky — once seen, recorded — so that an operator who downgrades
   mid-session is caught? Leaning no. The check is cheap and runs per request; a transient wrong
   answer during a restart would be worse than a late one.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

The policy is right and the gap is real: `JellyfinSystemInfoAuth` carries exactly one field
(`Models.kt:386-388`), nothing reads a version anywhere, and neither `specs/constitution.md` nor
`README.md` mentions a Jellyfin version at all — so FR-243-1 is purely additive and acceptance 4 starts
from zero. Four refinements, one of which changes where FR-243-2 reads from.

1. **Read the version from the public probe, not only the authenticated one — it is absent precisely
   when it matters most.** FR-243-2 adds `Version` to `JellyfinSystemInfoAuth`, which is `GET
   /System/Info` (authenticated). But the failure mode this whole cluster exists for is *the credential
   form changing*: on such a server `getSystemInfoAuth` returns null, `computeFindings` may fall through
   to `reachable = false` (`JellyfinAdvisorService.kt:64-66`), and the version — the one fact that would
   have explained everything — is the field that goes missing with it. Meanwhile `testConnection` already
   fetches `GET /System/Info/Public` (`JellyfinClient.kt:315-319`), which **returns `Version`, needs no
   credential, and whose body the code currently throws away**, keeping only the status. Read it there:
   it costs nothing, it is already on the hot path, and it still answers when authentication is broken.
   Keep the authenticated field too if 212's advisor wants it, but the public read is the one that has to
   exist for FR-243-2 to do its job.
2. **Acceptance 3 is not greppable as written, but it can be made so.** "grep for a version comparison in
   the request-building paths finds nothing" has no mechanical form — there is no token to search for.
   There is a better one available for free: FR-243-2 introduces exactly one new value, so a script can
   assert that the *symbol* appears only in the allowed files. That is precisely the shape of the
   existing `scripts/check-css-scoping.sh` / `check-fd-hygiene.sh` family, it runs in CI already
   (`ci.yml:31-35`), and it turns FR-243-4 from a rule people remember into a rule the build enforces.
   Recommend `scripts/check-jellyfin-version-use.sh`, allow-listing the two reporting sites by path.
3. **Open question 1 closes: 12.0, and the question is academic for a reason worth writing down.**
   FR-243-3 already specifies the comparison on the **major** version ("when the connected server's major
   version is below 12"). A 12.0 server therefore passes under either answer, and no code path can
   distinguish them. So the choice only affects one sentence of prose in the constitution and README —
   declare **12.0**, the safer statement, and note inline that the check is major-only, which is what
   makes the minor version irrelevant. If a minor-level floor is ever genuinely needed it will be because
   a specific behaviour demands it, and that behaviour is what should carry the number.
4. **Open question 2's lean is right, with a concrete reason.** `/api/health` is hit every 30 seconds by
   the container's own `HEALTHCHECK` (`Dockerfile:129`), so a sticky below-floor flag would latch on any
   blip inside a Jellyfin restart window and then need a manual reset — a worse failure than a late
   answer, and one that would be indistinguishable from the real thing. Keep it per-request, and close
   the question.
5. **FR-243-6's checklist would cite two things that do not exist yet.** It names phase 240's live route
   guard and its model-field check; both are `Planned`. Write the checklist now with the **manual
   equivalents the 2026-09-18 audit actually used** — the auth-form matrix, the `/socket` handshake
   probe, the `@SerialName` sweep by hand — so it is usable on the next upgrade whether or not 240 has
   landed, and mark each step with the automation that will replace it. A checklist that can only be run
   after another phase ships is a checklist that will not be run.
6. **FR-243-5's count, measured.** There are **18** `10.11.11`/`10.11.x` citations in code today, across
   **10** files (`.kt`/`.js`/`.kts`, excluding build output), plus further occurrences in the spec tree.
   The spec says nineteen across nine, which is close enough to be the same sweep counted differently —
   but since FR-243-5's whole value is being "a finite task rather than an open-ended sweep", the list
   should live in exactly one place and be reproducible by a command. Put the command in the research
   report next to the list. Also worth noting which one is load-bearing rather than historical:
   `JellyfinClient.kt:1240`'s `Version`-header rule, which the spec has already re-verified on 12.1 and
   which should be re-stamped first, since `tests/mock-jellyfin/server.js` justifies its only real
   refusal by citing it (see 241's review, item 4).

**One cross-phase note.** FR-243-4's "a version check is permitted for refusing a known-broken
combination" is the one clause in this phase that could be read as licence for the branching it forbids.
Tighten it: the permitted refusal produces a *finding or a health failure* and nothing else — it may not
select a request shape, a header form or an endpoint. That is what 238, 239 and 241 each assume, and
stating it here in those terms is what stops the next phase re-arguing it.
