# CLAUDE.md

Orientation for anyone — human or agent — working in this repository.

Deliberately short. The long-lived detail lives in files that are maintained for their own sake:
[`README.md`](README.md) for what the product is and how to build it, [`STATUS.md`](STATUS.md) for
phase status, [`specs/`](specs/) for requirements. **This file holds only the rules that are not
obvious from the code, and no facts that go stale.** If you are tempted to add a status, a count or
a phase number here, add it to `STATUS.md` instead.

## What this is

A Kotlin/Native (`linuxX64`) backend plus a Kotlin/Wasm admin frontend that own the metadata for a
Jellyfin library from outside Jellyfin, and **Ravilo**, the Compose Multiplatform client family for
the same library (Android TV, phone, browser, macOS, Linux, Chromecast receiver, Tizen).
[`README.md`](README.md) has the product description, the requirements, the config shape, the
repository layout and the build commands. Read it first.

## The rules that matter

**Phase numbers come from [`STATUS.md`](STATUS.md) and the board — never from prose.** Every number
0–N and R0–RN is a row there; the next free number is the one after the highest row, and finding it
is a query, not a recollection. Three separate renumbering collisions in October 2026 all began with
someone reading a free number out of a prose summary that had gone stale. No document in this repo
other than `STATUS.md` is allowed to claim what is free.

**Every change starts as a spec, bug fixes included.** A new behaviour gets a new numbered file under
[`specs/requirements/`](specs/requirements/) (backend/admin) or
[`specs/ravilo/requirements/`](specs/ravilo/requirements/) (clients). A fix to something already
shipped gets a **dated amendment inside that phase's existing spec**, written before the Kotlin.
`STATUS.md` is the only place a status lives — not the spec body, not a comment.

**[`specs/constitution.md`](specs/constitution.md) wins on conflict.** A change that contradicts it
goes to the owner as a question; it does not quietly go into the file.

**This repository is public, and prod serves users beyond the household.** Never commit a library
title, a filename from the library, or anything else that identifies what the household owns —
`scripts/check-deanonymization.sh` exists because this has gone wrong. Treat anything a viewer would
notice as a break as an owner decision, not a judgement call.

**The wire is backwards compatible.** Installed clients are not upgraded in lockstep with the
backend; an old APK will call today's server. Never delete or retype a field an existing client
reads — add, deprecate, and remove a release later.

**`design/app/wf.css` and `app.css` are production CSS**, copied verbatim into the distribution by
Gradle's `syncDesignAssets`. They are not mockup CSS awaiting reimplementation: a new component class
is added there and that is the whole styling step. No Tailwind, no PostCSS. The admin frontend drives
the DOM through `kotlinx.browser` and renders **server-pushed state only** — no derived or optimistic
state in the frontend.

## Building locally

See [`README.md`](README.md#building-from-source) for the task list. Two local gotchas it does not
carry:

- `JAVA_HOME` is not set in this checkout's shell and there is no `java` on `PATH`. Prefix Gradle
  invocations with a JDK 21: `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew <task> --console=plain`.
- **Never run `./gradlew --stop`.** Gradle daemons are shared across invocations on this host, and
  stopping them has taken a live backend down mid-session. If a build looks wedged, retry it.

Do not deploy, restart a service, or touch a device without being asked to. Those are the owner's
calls, every time.

## Consistency checks

[`scripts/`](scripts/) holds the guards — `check-phases.sh` reconciles every spec file against a
`STATUS.md` row in both directions, and the rest check CSS scoping, mobile CSS, the hand-written
language/country tables, de-anonymisation and more. Run the ones that touch what you changed.

## Historical note

Until 2026-10-12 this file was overwritten on every "updated designs" sync from a separate design
project, and described that project rather than this repository. That sync is retired. The old
content is kept, frozen and clearly marked, as
[`design/design-project-notes-2026-06-to-2026-10.md`](design/design-project-notes-2026-06-to-2026-10.md)
— a useful record of which design directions were drawn, picked and rejected, and a reliable record
of nothing else.
