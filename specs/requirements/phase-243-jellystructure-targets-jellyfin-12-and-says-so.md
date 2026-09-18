# Phase 243 — jellystructure targets Jellyfin 12.x, and says so

> Owner direction 2026-09-18: we want to be fully up to date with the latest Jellyfin and we do not
> care about older versions any more.

## Status

`Planned` — written 2026-09-18, not dev-reviewed, not built. The umbrella decision behind **238**,
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
